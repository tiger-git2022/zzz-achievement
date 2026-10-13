package com.local.stardbzzz.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val primaryCategory: String,
    val category: String,
    val reward: String,
    val version: String,
    val displayOrder: Int,
    val iconTier: Int?,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    val isCompleted: Boolean,
    val syncStatus: SyncStatus,
    val updatedAtEpochMillis: Long,
)

enum class SyncStatus {
    Synced,
    Pending,
    Failed,
}
