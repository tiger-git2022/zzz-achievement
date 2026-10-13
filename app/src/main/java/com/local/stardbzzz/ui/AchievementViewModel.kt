package com.local.stardbzzz.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.local.stardbzzz.data.local.CompletionFilter
import com.local.stardbzzz.data.local.SortMode
import com.local.stardbzzz.data.repository.AchievementRepository
import com.local.stardbzzz.data.repository.AuthenticationExpiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppStatus(
    val hasCredentials: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class AchievementViewModel(private val repository: AchievementRepository) : ViewModel() {
    val screenState = repository.screenState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = com.local.stardbzzz.data.repository.AchievementScreenState(),
    )

    private val _status = MutableStateFlow(AppStatus(hasCredentials = repository.savedCredentials() != null))
    val status: StateFlow<AppStatus> = _status.asStateFlow()

    init {
        if (_status.value.hasCredentials) {
            viewModelScope.launch {
                val lastSync = repository.filterPreferences.state.first().lastSyncEpochMillis
                val now = System.currentTimeMillis()
                if (lastSync == 0L || now - lastSync >= AutoRefreshIntervalMillis) {
                    refresh()
                }
            }
        }
    }

    fun login(username: String, password: String) {
        launchWithStatus {
            repository.login(username, password)
            _status.value = _status.value.copy(hasCredentials = true)
            repository.refreshAchievements()
            repository.retryPendingSync()
        }
    }

    fun loginWithSessionCookie(sessionCookie: String) {
        launchWithStatus {
            repository.saveSessionCookie(sessionCookie)
            _status.value = _status.value.copy(hasCredentials = true)
            repository.refreshAchievements()
            repository.retryPendingSync()
        }
    }

    fun refresh() {
        launchWithStatus {
            repository.clearRetainedVisibleAchievements()
            repository.refreshAchievements()
        }
    }

    fun refreshIfStale(minIntervalMillis: Long = AutoRefreshIntervalMillis) {
        val currentStatus = _status.value
        if (!currentStatus.hasCredentials || currentStatus.isRefreshing) return

        viewModelScope.launch {
            val lastSync = repository.filterPreferences.state.first().lastSyncEpochMillis
            val now = System.currentTimeMillis()
            if (_status.value.hasCredentials && !_status.value.isRefreshing &&
                (lastSync == 0L || now - lastSync >= minIntervalMillis)
            ) {
                refresh()
            }
        }
    }

    fun toggleAchievement(id: String, completed: Boolean) {
        viewModelScope.launch {
            try {
                repository.toggleAchievement(id, completed)
            } catch (failure: Throwable) {
                handleFailure(failure)
            }
        }
    }

    fun updatePinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.updatePinned(id, pinned) }
    }

    fun updateSearch(value: String) {
        val enteringSearch = screenState.value.filter.search.isBlank() && value.isNotBlank()
        if (enteringSearch) {
            repository.clearRetainedVisibleAchievements()
        }
        viewModelScope.launch {
            if (enteringSearch) {
                repository.clearCompletedPinned()
            }
            repository.filterPreferences.updateSearch(value)
        }
    }

    fun updatePrimaryCategory(value: String) {
        viewModelScope.launch { repository.filterPreferences.updatePrimaryCategory(value) }
    }

    fun updateCategory(value: String) {
        viewModelScope.launch { repository.filterPreferences.updateCategory(value) }
    }

    fun updateCategorySelection(primaryCategory: String, category: String) {
        repository.clearRetainedVisibleAchievements()
        viewModelScope.launch {
            repository.clearCompletedPinned()
            repository.filterPreferences.updateCategorySelection(primaryCategory, category)
        }
    }

    fun updateCompletionFilter(value: CompletionFilter) {
        repository.clearRetainedVisibleAchievements()
        viewModelScope.launch { repository.filterPreferences.updateCompletionFilter(value) }
    }

    fun updateVersion(value: String) {
        repository.clearRetainedVisibleAchievements()
        viewModelScope.launch { repository.filterPreferences.updateVersion(value) }
    }

    fun updateSortMode(value: SortMode) {
        viewModelScope.launch { repository.filterPreferences.updateSortMode(value) }
    }

    fun updateFirstVisibleItemIndex(value: Int) {
        viewModelScope.launch { repository.filterPreferences.updateFirstVisibleItemIndex(value) }
    }

    fun clearError() {
        _status.value = _status.value.copy(error = null)
    }

    fun logout() {
        _status.value = _status.value.copy(hasCredentials = false, error = null)
        launchWithStatus {
            repository.logout()
            _status.value = _status.value.copy(hasCredentials = false)
        }
    }

    private fun launchWithStatus(block: suspend () -> Unit) {
        if (_status.value.isRefreshing) return
        _status.value = _status.value.copy(isRefreshing = true, error = null)
        viewModelScope.launch {
            try {
                block()
            } catch (failure: Throwable) {
                handleFailure(failure)
            } finally {
                _status.value = _status.value.copy(isRefreshing = false)
            }
        }
    }

    private suspend fun handleFailure(failure: Throwable) {
        if (failure is AuthenticationExpiredException) {
            repository.clearExpiredSession()
            _status.value = _status.value.copy(
                hasCredentials = false,
                error = failure.message ?: "登录状态已失效，请重新登录",
            )
        } else {
            _status.value = _status.value.copy(error = failure.message ?: "操作失败")
        }
    }

    private companion object {
        const val AutoRefreshIntervalMillis = 12 * 60 * 60 * 1000L
    }
}

class AchievementViewModelFactory(private val repository: AchievementRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AchievementViewModel(repository) as T
    }
}
