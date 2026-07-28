package com.jobhunt.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ResumeEntity::class,
        JobSourceEntity::class,
        ListingEntity::class,
        RunLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class JobHuntDatabase : RoomDatabase() {

    abstract fun resumeDao(): ResumeDao
    abstract fun jobSourceDao(): JobSourceDao
    abstract fun listingDao(): ListingDao
    abstract fun runLogDao(): RunLogDao

    companion object {
        @Volatile
        private var instance: JobHuntDatabase? = null

        fun get(context: Context): JobHuntDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JobHuntDatabase::class.java,
                "jobhunt.db",
            ).build().also { instance = it }
        }
    }
}
