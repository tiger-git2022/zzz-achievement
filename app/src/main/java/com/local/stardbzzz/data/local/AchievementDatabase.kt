package com.local.stardbzzz.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [AchievementEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(AchievementConverters::class)
abstract class AchievementDatabase : RoomDatabase() {
    abstract fun achievementDao(): AchievementDao

    companion object {
        fun create(context: Context): AchievementDatabase =
            Room.databaseBuilder(context, AchievementDatabase::class.java, "zzz-achievements.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

class AchievementConverters {
    @TypeConverter
    fun syncStatusToString(value: SyncStatus): String = value.name

    @TypeConverter
    fun stringToSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
