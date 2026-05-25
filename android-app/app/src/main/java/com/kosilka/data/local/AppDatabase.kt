package com.kosilka.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kosilka.data.local.dao.AnchorDao
import com.kosilka.data.local.dao.CoverageDao
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.dao.SessionHistoryDao
import com.kosilka.data.local.dao.ZoneDao
import com.kosilka.data.local.entity.AnchorEntity
import com.kosilka.data.local.entity.CoverageSegmentEntity
import com.kosilka.data.local.entity.ScheduleEntity
import com.kosilka.data.local.entity.SessionHistoryEntity
import com.kosilka.data.local.entity.ZoneEntity

@Database(
    entities = [
        ZoneEntity::class,
        CoverageSegmentEntity::class,
        ScheduleEntity::class,
        SessionHistoryEntity::class,
        AnchorEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun zoneDao(): ZoneDao
    abstract fun coverageDao(): CoverageDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun sessionHistoryDao(): SessionHistoryDao
    abstract fun anchorDao(): AnchorDao

    companion object {
        // Placeholder migration for future schema changes
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op placeholder — update when schema version 2 is introduced
            }
        }
    }
}
