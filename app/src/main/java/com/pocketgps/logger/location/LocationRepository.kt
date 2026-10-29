package com.pocketgps.logger.location

import com.pocketgps.logger.data.LocationDao
import com.pocketgps.logger.data.LocationRecord
import kotlinx.coroutines.flow.Flow

/**
 * Thin repository around [LocationDao]. The UI observes Flows; the engine inserts.
 *
 * Every public write is a suspend function that throws on failure, so the caller (engine)
 * can distinguish a committed record from a failed write and never signal success early.
 */
class LocationRepository(private val dao: LocationDao) {

    fun observeAll(): Flow<List<LocationRecord>> = dao.observeAll()

    fun observeLatest(): Flow<LocationRecord?> = dao.observeLatest()

    fun observeLatestFresh(): Flow<LocationRecord?> = dao.observeLatestFresh()

    /** Insert and return the generated id. Throws if the write fails. */
    suspend fun insert(record: LocationRecord): Long = dao.insert(record)

    /** Attach a completed voice note to a record; returns 0 if GPS persistence has not finished. */
    suspend fun updateNotesForTrigger(triggerTimestamp: Long, notes: String): Int =
        dao.updateNotesForTrigger(triggerTimestamp, notes)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()

    suspend fun count(): Int = dao.count()
}
