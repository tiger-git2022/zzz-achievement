package com.local.stardbzzz.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AchievementEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(AchievementConverters::class)
abstract class AchievementDatabase : RoomDatabase() {
    abstract fun achievementDao(): AchievementDao

    companion object {
        fun create(context: Context): AchievementDatabase =
            Room.databaseBuilder(context, AchievementDatabase::class.java, "zzz-achievements.db")
                .addMigrations(AddGroupKey)
                .fallbackToDestructiveMigration()
                .build()

        /** Keeps pins and unsynced changes; the next refresh fills in the groups. */
        private val AddGroupKey = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE achievements ADD COLUMN groupKey TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

class AchievementConverters {
    @TypeConverter
    fun syncStatusToString(value: SyncStatus): String = value.name

    @TypeConverter
    fun stringToSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
