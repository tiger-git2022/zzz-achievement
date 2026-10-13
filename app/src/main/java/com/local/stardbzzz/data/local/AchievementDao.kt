package com.local.stardbzzz.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY displayOrder, id")
    fun observeAll(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements")
    suspend fun allSnapshot(): List<AchievementEntity>

    @Query("SELECT * FROM achievements WHERE syncStatus IN ('Pending', 'Failed')")
    suspend fun pendingSync(): List<AchievementEntity>

    @Query("SELECT * FROM achievements WHERE id = :id")
    suspend fun getById(id: String): AchievementEntity?

    @Upsert
    suspend fun upsertAll(items: List<AchievementEntity>)

    @Upsert
    suspend fun upsert(item: AchievementEntity)

    @Query("UPDATE achievements SET isCompleted = :completed, syncStatus = :status, updatedAtEpochMillis = :updatedAt WHERE id = :id")
    suspend fun updateCompletion(id: String, completed: Boolean, status: SyncStatus, updatedAt: Long)

    @Query(
        """UPDATE achievements
           SET syncStatus = :status
           WHERE id = :id
             AND isCompleted = :completed
             AND updatedAtEpochMillis = :expectedUpdatedAt""",
    )
    suspend fun updateSyncStatusIfCurrent(
        id: String,
        completed: Boolean,
        expectedUpdatedAt: Long,
        status: SyncStatus,
    ): Int

    @Query(
        """UPDATE achievements
           SET syncStatus = :status
           WHERE id IN (:ids)
             AND isCompleted = :completed
             AND updatedAtEpochMillis = :expectedUpdatedAt""",
    )
    suspend fun updateSyncStatusIfCurrent(
        ids: List<String>,
        completed: Boolean,
        expectedUpdatedAt: Long,
        status: SyncStatus,
    ): Int

    @Query("UPDATE achievements SET isPinned = :pinned WHERE id = :id AND isCompleted = 0")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("UPDATE achievements SET isPinned = 0 WHERE isCompleted = 1 AND isPinned = 1")
    suspend fun clearCompletedPinned()

    @Query("DELETE FROM achievements WHERE id NOT IN (:ids)")
    suspend fun deleteMissingIds(ids: List<String>)

    @Transaction
    suspend fun reconcileRemote(items: List<AchievementEntity>, validIds: List<String>) {
        val localById = allSnapshot().associateBy { it.id }
        val merged = items.map { remote ->
            val local = localById[remote.id]
            when {
                local == null -> remote
                local.syncStatus != SyncStatus.Synced -> remote.copy(
                    isCompleted = local.isCompleted,
                    isPinned = local.isPinned,
                    syncStatus = local.syncStatus,
                    updatedAtEpochMillis = local.updatedAtEpochMillis,
                )
                else -> remote.copy(isPinned = local.isPinned)
            }
        }
        upsertAll(merged)
        deleteMissingIds(validIds)
    }

    @Query("SELECT COUNT(*) FROM achievements")
    suspend fun count(): Int

    @Query("DELETE FROM achievements")
    suspend fun clearAll()
}
