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

    fun observeRecent(limit: Int): Flow<List<LocationRecord>> = dao.observeRecent(limit)

    fun observeLatestFresh(): Flow<LocationRecord?> = dao.observeLatestFresh()

    fun observeCategories(): Flow<List<String>> = dao.observeCategories()

    /** Live record count without materialising rows — cheaper than observing all records. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Live filtered/sorted history; [pattern] is a SQL LIKE pattern, null [category] = any. */
    fun search(pattern: String, newestFirst: Boolean, category: String?): Flow<List<LocationRecord>> =
        if (newestFirst) dao.searchNewest(pattern, category) else dao.searchOldest(pattern, category)

    /** Number of records saved in [fromMs, toMs). */
    suspend fun countBetween(fromMs: Long, toMs: Long): Int = dao.countBetween(fromMs, toMs)

    /** One-shot read of every record ordered oldest → newest (for export). */
    suspend fun listAll(): List<LocationRecord> = dao.listAll()

    /** One record by id, or null. */
    suspend fun byId(id: Long): LocationRecord? = dao.getById(id)

    /** Insert and return the generated id. Throws if the write fails. */
    suspend fun insert(record: LocationRecord): Long = dao.insert(record)

    /** Persist an edit (observation text, category, attach-later). Throws if the write fails. */
    suspend fun update(record: LocationRecord) = dao.update(record)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()

    suspend fun count(): Int = dao.count()
}
