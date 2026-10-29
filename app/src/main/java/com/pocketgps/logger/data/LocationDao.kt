package com.pocketgps.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [LocationRecord] entities.
 *
 * The UI observes [observeAll] / [observeLatest] as cold Flows. The service inserts on an IO
 * dispatcher; Room's Flow invalidation pushes the change back to the UI automatically.
 */
@Dao
interface LocationDao {

    /** Insert a single record. Returns the generated row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: LocationRecord): Long

    /** All records, newest first. */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp DESC")
    fun observeAll(): Flow<List<LocationRecord>>

    /** The most recently saved record, if any. */
    @Query("SELECT * FROM location_records ORDER BY trigger_timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<LocationRecord?>

    /** The most recent fresh fix (used as a cached candidate). */
    @Query(
        "SELECT * FROM location_records WHERE location_source = 'fresh' " +
            "ORDER BY location_timestamp DESC LIMIT 1"
    )
    fun observeLatestFresh(): Flow<LocationRecord?>

    /** Attach a completed offline voice note to its matching GPS capture. */
    @Query("UPDATE location_records SET notes = CASE WHEN notes IS NULL OR notes = '' THEN :notes WHEN instr(notes, :notes) > 0 THEN notes ELSE notes || char(10) || :notes END WHERE trigger_timestamp = :triggerTimestamp")
    suspend fun updateNotesForTrigger(triggerTimestamp: Long, notes: String): Int

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
