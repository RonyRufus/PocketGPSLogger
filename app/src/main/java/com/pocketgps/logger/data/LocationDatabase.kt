package com.pocketgps.logger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database holding saved location records.
 *
 * Version 2 adds indexes on `trigger_timestamp` and `location_source`+`location_timestamp`
 * (see [LocationRecord]); [MIGRATION_V1_V2] alters no columns, only adds indexes, so existing
 * records are untouched.
 */
@Database(
    entities = [LocationRecord::class],
    version = LocationDatabase.VERSION,
    exportSchema = true,
)
abstract class LocationDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        const val VERSION = 2
        const val NAME = "pocket-gps-logger.db"

        /** v1 → v2: add the query-supporting indexes. Pure metadata change; no data is touched. */
        val MIGRATION_V1_V2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_location_records_trigger_timestamp` " +
                        "ON `location_records` (`trigger_timestamp`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_location_records_location_source_location_timestamp` " +
                        "ON `location_records` (`location_source`, `location_timestamp`)",
                )
            }
        }

        /** Migrations applied in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_V1_V2)

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
