package com.local.stardbzzz.data.repository

import com.local.stardbzzz.data.local.AchievementDao
import com.local.stardbzzz.data.local.AchievementEntity
import com.local.stardbzzz.data.local.CompletionFilter
import com.local.stardbzzz.data.local.FilterPreferences
import com.local.stardbzzz.data.local.FilterState
import com.local.stardbzzz.data.local.SecureCredentialStore
import com.local.stardbzzz.data.local.SortMode
import com.local.stardbzzz.data.local.SyncStatus
import com.local.stardbzzz.data.network.AchievementDto
import com.local.stardbzzz.data.network.RngAchievementIconClient
import com.local.stardbzzz.data.network.StardbApi
import com.local.stardbzzz.data.network.parseAchievementTrackerData
import com.local.stardbzzz.scan.ScanCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import retrofit2.Response

data class AchievementUiModel(
    val id: String,
    val name: String,
    val description: String,
    val primaryCategory: String,
    val category: String,
    val reward: String,
    val version: String,
    val iconTier: Int?,
    val isPinned: Boolean,
    val isCompleted: Boolean,
    val syncStatus: SyncStatus,
)

data class AchievementScreenState(
    val achievements: List<AchievementUiModel> = emptyList(),
    val primaryCategories: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val categoryGroups: Map<String, List<String>> = emptyMap(),
    val versions: List<String> = emptyList(),
    val filter: FilterState = FilterState(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
)

class AchievementRepository(
    private val dao: AchievementDao,
    private val api: StardbApi,
    private val credentialStore: SecureCredentialStore,
    val filterPreferences: FilterPreferences,
    private val writeSyncClient: AchievementWriteSyncClient,
    private val iconClient: RngAchievementIconClient,
) {
    private var sessionEstablished = false
    private val retainedVisibleIds = MutableStateFlow<Set<String>>(emptySet())
    private val remoteOperationMutex = Mutex()

    val screenState: Flow<AchievementScreenState> =
        combine(dao.observeAll(), filterPreferences.state, retainedVisibleIds) { achievements, filter, retainedIds ->
            val completedCategories = achievements
                .filter { it.category.isNotBlank() }
                .groupBy { it.category }
                .filterValues { items -> items.all { it.isCompleted } }
                .keys
            val primaryCategories = achievements
                .map { it.primaryCategory }
                .filter { it.isNotBlank() }
                .distinct()
                .sortedByPrimaryCategory()
            val rawCategoryGroups = achievements
                .filter { it.primaryCategory.isNotBlank() && it.category.isNotBlank() }
                .groupBy({ it.primaryCategory }, { it.category })
                .mapValues { (_, values) -> values.distinct() }
            val categoryGroups = linkedMapOf<String, List<String>>().apply {
                primaryCategories.forEach { primary ->
                    rawCategoryGroups[primary]
                        ?.filterNot { it in completedCategories }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { put(primary, it) }
                }
                if (completedCategories.isNotEmpty()) put(CompletedCategory, completedCategories.sorted())
            }
            val showingPinned = filter.primaryCategory == PinnedCategory
            val showingCompleted = filter.primaryCategory == CompletedCategory
            val selectedPrimary = filter.primaryCategory.ifBlank { categoryGroups.keys.firstOrNull().orEmpty() }
            val categories = achievements
                .filter { !showingPinned && (showingCompleted || selectedPrimary.isBlank() || it.primaryCategory == selectedPrimary) }
                .map { it.category }
                .filter { it.isNotBlank() }
                .distinct()
                .let { values -> if (showingCompleted) values.filter { it in completedCategories } else values }
            val selectedCategory = filter.category.ifBlank { categories.firstOrNull().orEmpty() }
            val filtered = (if (filter.search.isNotBlank()) {
                achievements.filter { item ->
                    item.name.contains(filter.search, ignoreCase = true) ||
                        item.description.contains(filter.search, ignoreCase = true)
                }
            } else {
                achievements
                    .filter { item ->
                        if (showingPinned) item.isPinned
                        else if (showingCompleted) item.category == selectedCategory
                        else selectedPrimary.isBlank() || item.primaryCategory == selectedPrimary
                    }
                    .filter { showingPinned || selectedCategory.isBlank() || it.category == selectedCategory }
                    .filter { item ->
                        when (filter.completionFilter) {
                            CompletionFilter.All -> true
                            CompletionFilter.Completed -> item.isCompleted || item.id in retainedIds
                            CompletionFilter.Incomplete -> !item.isCompleted || item.id in retainedIds
                            CompletionFilter.PendingSync -> item.syncStatus != SyncStatus.Synced
                        }
                    }
            })
                .filter { filter.version.isBlank() || it.version == filter.version }
                .sortedWith(compareByDescending<AchievementEntity> { it.isPinned }.then(sortComparator(filter.sortMode)))
                .map { it.toUiModel() }

            AchievementScreenState(
                achievements = filtered,
                primaryCategories = primaryCategories,
                categories = categories,
                categoryGroups = categoryGroups,
                versions = achievements.map { it.version }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith(compareByDescending<String> { it.removePrefix("V").removePrefix("v").toFloatOrNull() ?: 0f }),
                filter = filter,
                completedCount = achievements.count { it.isCompleted },
                totalCount = achievements.size,
            )
        }

    fun savedCredentials() = credentialStore.credentials()

    suspend fun saveSessionCookie(sessionCookie: String) {
        credentialStore.saveCredentials("", "", sessionCookie)
    }

    suspend fun logout() {
        sessionEstablished = false
        credentialStore.clearSession()
        writeSyncClient.close()
        dao.clearAll()
    }

    suspend fun clearExpiredSession() {
        sessionEstablished = false
        credentialStore.clearSession()
        writeSyncClient.close()
    }

    suspend fun login(username: String, password: String) {
        api.loginPageData().close()
        val response = api.login(username = username, password = password)
        if (!response.isSuccessful) error("登录失败：HTTP ${response.code()}")
        api.homeData().close()
        credentialStore.saveAuthToken(null)
        sessionEstablished = true
    }

    suspend fun refreshAchievements() {
        remoteOperationMutex.withLock {
            clearRetainedVisibleAchievements()
            val now = System.currentTimeMillis()
            loginWithSavedCredentialsIfNeeded()
            val html = writeSyncClient.fetchTrackerPageHtml()
            if (html.isAuthenticationExpiredTrackerResponse()) {
                throw AuthenticationExpiredException()
            }
            val tracker = parseAchievementTrackerData(html)
            if (tracker.isEmpty()) {
                error("Refresh failed: tracker page returned no achievement data")
            }
            val rngData = iconClient.fetchAchievementData(
                tracker.associate { it.id to it.description.orEmpty() },
            )
            val remote = tracker.map { item ->
                item.toEntity(now, rngData.iconTiers[item.id], rngData.descriptions[item.id])
            }
            dao.reconcileRemote(remote, tracker.map { it.id })
            filterPreferences.updateLastSyncTime(now)
            syncPendingLocked()
        }
    }

    suspend fun toggleAchievement(id: String, completed: Boolean) {
        val selected = dao.getById(id) ?: return
        val linkedIds = dao.allSnapshot()
            .filter { item ->
                item.name == selected.name &&
                    item.category == selected.category &&
                    item.version == selected.version
            }
            .map { it.id }
        linkedIds.forEach(::retainVisibleAchievement)
        val now = System.currentTimeMillis()
        linkedIds.forEach { linkedId ->
            dao.updateCompletion(linkedId, completed, SyncStatus.Pending, now)
        }
        remoteOperationMutex.withLock {
            syncCurrentLocked(id)
        }
    }

    suspend fun scanCandidates(): List<ScanCandidate> =
        dao.allSnapshot().map { ScanCandidate(it.id, it.name, it.description, it.category, it.isCompleted) }

    /** Applies completion states read from the game, then syncs them to stardb in one pass. */
    suspend fun applyScannedCompletion(changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        val all = dao.allSnapshot()
        val byId = all.associateBy { it.id }
        val now = System.currentTimeMillis()
        changes.forEach { (id, completed) ->
            val selected = byId[id] ?: return@forEach
            all.filter { item ->
                item.name == selected.name &&
                    item.category == selected.category &&
                    item.version == selected.version
            }.forEach { linked ->
                dao.updateCompletion(linked.id, completed, SyncStatus.Pending, now)
            }
        }
        retryPendingSync()
    }

    fun clearRetainedVisibleAchievements() {
        retainedVisibleIds.value = emptySet()
    }

    suspend fun clearCompletedPinned() {
        dao.clearCompletedPinned()
    }

    private fun retainVisibleAchievement(id: String) {
        retainedVisibleIds.value = retainedVisibleIds.value + id
    }

    suspend fun updatePinned(id: String, pinned: Boolean) {
        dao.updatePinned(id, pinned)
    }

    suspend fun retryPendingSync() {
        remoteOperationMutex.withLock {
            syncPendingLocked()
        }
    }

    private suspend fun syncPendingLocked() {
        loginWithSavedCredentialsIfNeeded()
        var firstFailure: Throwable? = null
        dao.pendingSync().forEach { pending ->
            runCatching { syncOneLocked(pending) }
                .onFailure { failure ->
                    if (failure is AuthenticationExpiredException) throw failure
                    if (firstFailure == null) firstFailure = failure
                }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun syncCurrentLocked(id: String) {
        val current = dao.getById(id) ?: return
        if (current.syncStatus == SyncStatus.Synced) return
        syncOneLocked(current)
    }

    private suspend fun syncOneLocked(pending: AchievementEntity) {
        val current = dao.getById(pending.id) ?: return
        if (current.syncStatus == SyncStatus.Synced) return
        val linkedIds = dao.allSnapshot()
            .filter { item ->
                item.name == current.name &&
                    item.category == current.category &&
                    item.version == current.version
            }
            .map { it.id }
        runCatching {
            loginWithSavedCredentialsIfNeeded()
            val response = writeSyncClient.syncCompletion(pending.id, pending.isCompleted)
            if (response.isAuthenticationExpired()) {
                throw AuthenticationExpiredException()
            }
            if (!response.isSuccessful) {
                val action = if (pending.isCompleted) "Complete sync" else "Uncomplete sync"
                error(response.errorMessage(action))
            }
        }.onSuccess {
            dao.updateSyncStatusIfCurrent(
                linkedIds,
                pending.isCompleted,
                pending.updatedAtEpochMillis,
                SyncStatus.Synced,
            )
        }.onFailure { failure ->
            val updated = dao.updateSyncStatusIfCurrent(
                linkedIds,
                pending.isCompleted,
                pending.updatedAtEpochMillis,
                SyncStatus.Failed,
            )
            if (updated > 0 || failure is AuthenticationExpiredException) throw failure
        }
    }

    private fun Response<ResponseBody>.syncErrorMessage(completed: Boolean): String {
        val action = if (completed) "勾选同步" else "取消同步"
        val bodyText = errorBody()?.string()?.take(200)?.trim().orEmpty()
        return if (bodyText.isBlank()) {
            "$action 失败：HTTP ${code()}"
        } else {
            "$action 失败：HTTP ${code()}，$bodyText"
        }
    }

    private suspend fun loginWithSavedCredentialsIfNeeded() {
        if (sessionEstablished) return
        val saved = credentialStore.credentials() ?: return
        if (saved.sessionCookie.isNotBlank()) {
            sessionEstablished = true
        } else {
            login(saved.username, saved.password)
        }
    }

    private fun AchievementWriteSyncResult.isAuthenticationExpired(): Boolean {
        return status == 401 || status == 403 || body.isAuthenticationExpiredTrackerResponse()
    }

    private fun String.isAuthenticationExpiredTrackerResponse(): Boolean {
        val text = trim()
        if (text.isBlank()) return false
        if (text.contains("\"__stardbAuthExpired\":true")) return true

        val sample = text.take(10_000).lowercase()
        val looksLikeHtml = sample.startsWith("<") || sample.contains("<html") || sample.contains("<!doctype")
        val pointsToLogin = sample.contains("/zh-cn/login") || sample.contains("/login")
        val hasLoginForm = sample.contains("name=\"username\"") || sample.contains("name=\"password\"")
        val unauthorized = sample.contains("unauthorized") || sample.contains("forbidden")
        return unauthorized || (looksLikeHtml && pointsToLogin && hasLoginForm)
    }

    private fun sortComparator(sortMode: SortMode): Comparator<AchievementEntity> =
        when (sortMode) {
            SortMode.Category -> compareBy<AchievementEntity> { it.displayOrder }.thenBy { it.id }
            SortMode.Name -> compareBy { it.name }
            SortMode.Completion -> compareBy<AchievementEntity> { it.isCompleted }.thenBy { it.category }.thenBy { it.name }
            SortMode.Version -> compareBy<AchievementEntity> { it.version }.thenBy { it.category }.thenBy { it.name }
        }

    private fun AchievementEntity.toUiModel() = AchievementUiModel(
        id = id,
        name = name,
        description = description,
        primaryCategory = primaryCategory,
        category = category,
        reward = reward,
        version = version,
        iconTier = iconTier,
        isPinned = isPinned,
        isCompleted = isCompleted,
        syncStatus = syncStatus,
    )

    private fun AchievementDto.toEntity(now: Long, iconTier: Int?, rngDescription: String?) = AchievementEntity(
        id = id,
        name = name,
        description = description.orEmpty()
            .takeUnless { PlaceholderRegex.containsMatchIn(it) }
            .orEmpty()
            .ifBlank { rngDescription.orEmpty() }
            .normalizeGameText(),
        primaryCategory = primaryCategory.orEmpty().ifBlank { category.orEmpty().toPrimaryCategory() },
        category = category.orEmpty(),
        reward = reward.orEmpty(),
        version = version.orEmpty(),
        displayOrder = displayOrder,
        iconTier = iconTier?.takeIf { it in 1..3 },
        isPinned = false,
        isCompleted = completed,
        syncStatus = SyncStatus.Synced,
        updatedAtEpochMillis = now,
    )

    private fun List<String>.sortedByPrimaryCategory(): List<String> =
        sortedWith(compareBy<String> { PrimaryCategoryOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.thenBy { it })

    private fun String.normalizeGameText(): String =
        replace(GenderVariantRegex) { match ->
            "（男主：${match.groupValues[1]}；女主：${match.groupValues[2]}）"
        }

    private fun String.toPrimaryCategory(): String = when (this) {
        "法厄同纪事", "代理人秘闻", "代理人信赖", "际会之时", "绳网热议", "独家视界" -> "故事"
        "绳匠业务", "雅努斯区", "布亚斯特", "卫非地", "外环地带", "索恩区" -> "城市"
        "迷失之地", "枯萎之都", "作战技巧", "敌对目标", "战斗成就" -> "战术"
        "空洞探索指南", "零号密钥", "勘域探境" -> "探索"
        else -> "其他"
    }

    private companion object {
        val GenderVariantRegex = Regex("""\{M#([^{}]*)\}\{F#([^{}]*)\}""", RegexOption.IGNORE_CASE)
        val PlaceholderRegex = Regex("""\[\{\d+\}\]""")
        const val CompletedCategory = "已完成"
        const val PinnedCategory = "置顶"
        val PrimaryCategoryOrder = listOf("故事", "城市", "战术", "探索", "其他")
    }
}
