package com.pocketgps.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [LocationRecord] entities.
 *
 * The UI observes [observeAll] / [observeLatest] / [observeRecent] / [searchNewest] /
 * [searchOldest] as cold Flows. The service inserts on an IO dispatcher; Room's Flow
 * invalidation pushes the change back to the UI automatically.
 *
 * Count-only queries ([observeCount], [countBetween]) let the UI show stats without ever
 * materialising full rows into memory.
 *
 * Search patterns are built by the caller with wildcards escaped
 * (see [com.pocketgps.logger.data.SearchPatterns]) and passed in as `:pattern`.
 */
@Dao
interface LocationDao {

    /** Insert a single record. Returns the generated row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: LocationRecord): Long

    /** Replace an edited record (observation text, category, attached location). */
    @Update
    suspend fun update(record: LocationRecord)

    /** Fetch one record by id, or null. */
    @Query("SELECT * FROM location_records WHERE id = :id")
    suspend fun getById(id: Long): LocationRecord?

    /** All records, newest first. */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp DESC")
    fun observeAll(): Flow<List<LocationRecord>>

    /** The most recently saved record, if any. */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<LocationRecord?>

    /** The [limit] most recently saved records (compact recent list on the main screen). */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LocationRecord>>

    /** The most recent fresh fix (used as a cached candidate). */
    @Query(
        "SELECT * FROM location_records WHERE location_source = 'fresh' " +
            "ORDER BY location_timestamp DESC LIMIT 1"
    )
    fun observeLatestFresh(): Flow<LocationRecord?>

    /** Live count of stored records — O(1) index scan, no rows materialised. */
    @Query("SELECT COUNT(*) FROM location_records")
    fun observeCount(): Flow<Int>

    /** Number of records whose trigger time is in [fromMs, toMs). */
    @Query(
        "SELECT COUNT(*) FROM location_records " +
            "WHERE trigger_timestamp >= :fromMs AND trigger_timestamp < :toMs",
    )
    suspend fun countBetween(fromMs: Long, toMs: Long): Int

    /**
     * Records matching [pattern] (SQL LIKE pattern over observation, category, trigger phrase),
     * optionally restricted to [category], newest first.
     * `:category` may be null to mean "any category".
     */
    @Query(
        "SELECT * FROM location_records " +
            "WHERE (observation LIKE :pattern ESCAPE '\\' " +
            "OR category LIKE :pattern ESCAPE '\\' " +
            "OR trigger_phrase LIKE :pattern ESCAPE '\\' " +
            "OR transcript LIKE :pattern ESCAPE '\\') " +
            "AND (:category IS NULL OR category = :category) " +
            "ORDER BY trigger_timestamp DESC",
    )
    fun searchNewest(pattern: String, category: String?): Flow<List<LocationRecord>>

    /** Same as [searchNewest], oldest first. */
    @Query(
        "SELECT * FROM location_records " +
            "WHERE (observation LIKE :pattern ESCAPE '\\' " +
            "OR category LIKE :pattern ESCAPE '\\' " +
            "OR trigger_phrase LIKE :pattern ESCAPE '\\' " +
            "OR transcript LIKE :pattern ESCAPE '\\') " +
            "AND (:category IS NULL OR category = :category) " +
            "ORDER BY trigger_timestamp ASC",
    )
    fun searchOldest(pattern: String, category: String?): Flow<List<LocationRecord>>

    /** Distinct non-blank categories in use, for the history filter chips. */
    @Query(
        "SELECT DISTINCT category FROM location_records " +
            "WHERE category IS NOT NULL AND category != '' ORDER BY category",
    )
    fun observeCategories(): Flow<List<String>>

    /** Records saved strictly before [beforeMs], newest first (used for paged export). */
    @Query("SELECT * FROM location_records WHERE trigger_timestamp < :beforeMs ORDER BY trigger_timestamp ASC")
    suspend fun listBefore(beforeMs: Long): List<LocationRecord>

    /** All records, newest first, one-shot read (used by export). */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp ASC")
    suspend fun listAll(): List<LocationRecord>

    /** Delete one record. */
    @Query("DELETE FROM location_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Delete every record. */
    @Query("DELETE FROM location_records")
    suspend fun clearAll()

    /** Number of stored records. */
    @Query("SELECT COUNT(*) FROM location_records")
    suspend fun count(): Int
}

/**
 * Builds the SQL LIKE pattern for history search, escaping the user's text so `%` and `_`
 * in a query are treated literally rather than as wildcards. Must be the single place where
 * search text becomes a pattern so the DAO contract stays consistent.
 */
object SearchPatterns {
    fun forQuery(raw: String): String {
        val q = raw.trim()
        if (q.isEmpty()) return "%"
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
