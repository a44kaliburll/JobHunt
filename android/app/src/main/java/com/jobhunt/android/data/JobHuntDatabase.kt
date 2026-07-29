package com.jobhunt.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ResumeEntity::class,
        ProfileItemEntity::class,
        JobSourceEntity::class,
        ListingEntity::class,
        RunLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class JobHuntDatabase : RoomDatabase() {

    abstract fun resumeDao(): ResumeDao
    abstract fun profileItemDao(): ProfileItemDao
    abstract fun jobSourceDao(): JobSourceDao
    abstract fun listingDao(): ListingDao
    abstract fun runLogDao(): RunLogDao

    companion object {
        /** Adds the hand-edited profile layer; existing data is untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `profile_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `field` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `normalized` TEXT NOT NULL,
                        `hidden` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_profile_items_field_normalized` " +
                        "ON `profile_items` (`field`, `normalized`)",
                )
            }
        }

        @Volatile
        private var instance: JobHuntDatabase? = null

        fun get(context: Context): JobHuntDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JobHuntDatabase::class.java,
                "jobhunt.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
