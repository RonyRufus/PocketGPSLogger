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
 *
 * Version 3 turns the app into an observation recorder: `latitude`, `longitude` and
 * `location_timestamp` become nullable (a note saved without a fix has no location), and four
 * columns are appended — `observation` (user text), `transcript` (raw recognised utterance),
 * `category` (optional tag) and `modified_timestamp` (last edit). SQLite cannot relax a NOT
 * NULL constraint in place, so [MIGRATION_V2_V3] rebuilds the table and copies every existing
 * row; no data is dropped or rewritten.
 */
@Database(
    entities = [LocationRecord::class],
    version = LocationDatabase.VERSION,
    exportSchema = true,
)
abstract class LocationDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {
        const val VERSION = 3
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

        /**
         * v2 → v3: make coordinates and fix timestamp nullable, add the observation columns.
         * Rebuild-and-copy so every existing row survives verbatim; new columns start null/empty.
         */
        val MIGRATION_V2_V3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_location_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`latitude` REAL, " +
                        "`longitude` REAL, " +
                        "`trigger_timestamp` INTEGER NOT NULL, " +
                        "`location_timestamp` INTEGER, " +
                        "`accuracy_meters` REAL, " +
                        "`location_source` TEXT NOT NULL, " +
                        "`location_age_seconds` INTEGER, " +
                        "`trigger_phrase` TEXT NOT NULL, " +
                        "`save_status` TEXT NOT NULL, " +
                        "`notes` TEXT, " +
                        "`observation` TEXT, " +
                        "`transcript` TEXT, " +
                        "`category` TEXT, " +
                        "`modified_timestamp` INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO `_new_location_records` " +
                        "(`id`,`latitude`,`longitude`,`trigger_timestamp`,`location_timestamp`," +
                        "`accuracy_meters`,`location_source`,`location_age_seconds`," +
                        "`trigger_phrase`,`save_status`,`notes`) " +
                        "SELECT `id`,`latitude`,`longitude`,`trigger_timestamp`,`location_timestamp`," +
                        "`accuracy_meters`,`location_source`,`location_age_seconds`," +
                        "`trigger_phrase`,`save_status`,`notes` FROM `location_records`",
                )
                db.execSQL("DROP TABLE `location_records`")
                db.execSQL(
                    "ALTER TABLE `_new_location_records` RENAME TO `location_records`",
                )
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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_V1_V2, MIGRATION_V2_V3)

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
