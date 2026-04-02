package com.trafficwatch.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.trafficwatch.app.core.data.local.dao.ReportDao
import com.trafficwatch.app.core.data.local.entity.ReportEntity

@Database(
    entities = [ReportEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao

    companion object {
        const val DATABASE_NAME = "trafficwatch.db"
    }
}
