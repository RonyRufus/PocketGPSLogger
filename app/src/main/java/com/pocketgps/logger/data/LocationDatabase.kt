package com.pocketgps.logger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Room database holding saved location records.
 *
 * Version 1 ships with no migrations. When the schema changes, bump [VERSION] and add a
 * [Migration] to [MIGRATIONS] instead of relying on destructive migration in release builds.
 */
@Database(
    entities = [LocationRecord::class],
    version = LocationDatabase.VERSION,
    exportSchema = true,
)
abstract class LocationDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        const val VERSION = 1
        const val NAME = "pocket-gps-logger.db"

        /** Future migrations are registered here, applied in order. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        @Volatile
        private var instance: LocationDatabase? = null

        fun get(context: Context): LocationDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): LocationDatabase =
            Room.databaseBuilder(context.applicationContext, LocationDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
