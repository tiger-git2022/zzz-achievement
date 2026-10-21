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
    /** The stardb group of alternative story-branch achievements, blank for most; see AchievementDto.group. */
    @ColumnInfo(defaultValue = "")
    val groupKey: String = "",
    /** Share of stardb users who completed it (0..1), -1 until a refresh fills it in. */
    @ColumnInfo(defaultValue = "-1")
    val percent: Double = -1.0,
)

/** Counted as one achievement: the members of a stardb group of story branches are one. */
val AchievementEntity.countKey: String get() = groupKey.ifBlank { id }

enum class SyncStatus {
    Synced,
    Pending,
    Failed,
}

/**
 * The list without story branches whose group another branch completed: they can no longer be earned and the game
 * counts the group as done. Uncompleting that branch shows them again. [keep] stays shown, e.g. just toggled.
 */
fun List<AchievementEntity>.withoutCoveredBranches(keep: Set<String> = emptySet()): List<AchievementEntity> {
    val doneGroups = filter { it.isCompleted && it.groupKey.isNotBlank() }.map { it.groupKey }.toSet()
    return filter { it.isCompleted || it.groupKey !in doneGroups || it.id in keep }
}
