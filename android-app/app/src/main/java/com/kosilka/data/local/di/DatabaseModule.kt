package com.kosilka.data.local.di

import android.content.Context
import androidx.room.Room
import com.kosilka.data.local.AppDatabase
import com.kosilka.data.local.dao.AnchorDao
import com.kosilka.data.local.dao.CoverageDao
import com.kosilka.data.local.dao.ScheduleDao
import com.kosilka.data.local.dao.SessionHistoryDao
import com.kosilka.data.local.dao.ZoneDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "kosilka.db")
            .fallbackToDestructiveMigration() // dev only — replace with explicit migrations before release
            .build()

    @Provides fun provideZoneDao(db: AppDatabase): ZoneDao = db.zoneDao()
    @Provides fun provideCoverageDao(db: AppDatabase): CoverageDao = db.coverageDao()
    @Provides fun provideScheduleDao(db: AppDatabase): ScheduleDao = db.scheduleDao()
    @Provides fun provideSessionHistoryDao(db: AppDatabase): SessionHistoryDao = db.sessionHistoryDao()
    @Provides fun provideAnchorDao(db: AppDatabase): AnchorDao = db.anchorDao()
}
