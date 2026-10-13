package com.local.stardbzzz.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.filterDataStore by preferencesDataStore(name = "filter_state")

data class FilterState(
    val search: String = "",
    val primaryCategory: String = "",
    val category: String = "",
    val version: String = "",
    val completionFilter: CompletionFilter = CompletionFilter.All,
    val sortMode: SortMode = SortMode.Category,
    val firstVisibleItemIndex: Int = 0,
    val lastSyncEpochMillis: Long = 0L,
    /** From the last refresh: achievements shown completed only because another member of their stardb group is. */
    val completedViaGroup: Int = 0,
)

enum class CompletionFilter {
    All,
    Completed,
    Incomplete,
    PendingSync,
}

enum class SortMode {
    Category,
    Name,
    Completion,
    Version,
}

class FilterPreferences(private val context: Context) {
    val state: Flow<FilterState> = context.filterDataStore.data.map { preferences ->
        FilterState(
            search = preferences[Keys.Search].orEmpty(),
            primaryCategory = preferences[Keys.PrimaryCategory].orEmpty(),
            category = preferences[Keys.Category].orEmpty(),
            version = preferences[Keys.Version].orEmpty(),
            completionFilter = enumPreference(preferences, Keys.Completion, CompletionFilter.All),
            sortMode = enumPreference(preferences, Keys.Sort, SortMode.Category),
            firstVisibleItemIndex = preferences[Keys.FirstVisibleItemIndex] ?: 0,
            lastSyncEpochMillis = preferences[Keys.LastSyncEpochMillis] ?: 0L,
            completedViaGroup = preferences[Keys.CompletedViaGroup] ?: 0,
        )
    }

    suspend fun updateSearch(value: String) = update(Keys.Search, value)
    suspend fun updatePrimaryCategory(value: String) {
        context.filterDataStore.edit {
            it[Keys.PrimaryCategory] = value
            it[Keys.Category] = ""
        }
    }
    suspend fun updateCategory(value: String) = update(Keys.Category, value)
    suspend fun updateCategorySelection(primaryCategory: String, category: String) {
        context.filterDataStore.edit {
            it[Keys.PrimaryCategory] = primaryCategory
            it[Keys.Category] = category
            it[Keys.FirstVisibleItemIndex] = 0
        }
    }
    suspend fun updateCompletionFilter(value: CompletionFilter) = update(Keys.Completion, value.name)
    suspend fun updateVersion(value: String) = update(Keys.Version, value)
    suspend fun updateSortMode(value: SortMode) = update(Keys.Sort, value.name)
    suspend fun updateFirstVisibleItemIndex(value: Int) = context.filterDataStore.edit { it[Keys.FirstVisibleItemIndex] = value }
    suspend fun updateLastSyncTime(value: Long) = context.filterDataStore.edit { it[Keys.LastSyncEpochMillis] = value }
    suspend fun updateCompletedViaGroup(value: Int) = context.filterDataStore.edit { it[Keys.CompletedViaGroup] = value }

    private suspend fun update(key: Preferences.Key<String>, value: String) {
        context.filterDataStore.edit { it[key] = value }
    }

    private inline fun <reified T : Enum<T>> enumPreference(
        preferences: Preferences,
        key: Preferences.Key<String>,
        default: T,
    ): T = preferences[key]?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private object Keys {
        val Search = stringPreferencesKey("search")
        val PrimaryCategory = stringPreferencesKey("primary_category")
        val Category = stringPreferencesKey("category")
        val Version = stringPreferencesKey("version")
        val Completion = stringPreferencesKey("completion")
        val Sort = stringPreferencesKey("sort")
        val FirstVisibleItemIndex = intPreferencesKey("first_visible_item_index")
        val LastSyncEpochMillis = longPreferencesKey("last_sync_epoch_millis")
        val CompletedViaGroup = intPreferencesKey("completed_via_group")
    }
}
