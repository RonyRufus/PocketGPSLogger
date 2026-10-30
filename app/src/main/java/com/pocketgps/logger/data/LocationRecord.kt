package com.pocketgps.logger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing one saved record: either a located spot, a located observation,
 * or a note without any location (GPS unavailable).
 *
 * Rows are only written after the write has been confirmed by Room; a failed write never
 * produces a row that claims success.
 *
 * Coordinates are nullable since v3: a record saved without a valid fix has null coordinates
 * and [locationSource] `"none"`. A note that later gets a fix (attach-later) keeps its original
 * [triggerTimestamp] and only gains coordinates.
 *
 * Indexes: the list and latest queries sort by [triggerTimestamp] and the cached-candidate
 * query filters on [locationSource]; both columns are therefore indexed (v2, migration
 * [LocationDatabase.MIGRATION_V1_V2]) so the UI stays smooth as the record count grows.
 */
@Entity(
    tableName = "location_records",
    indices = [
        Index(value = ["trigger_timestamp"]),
        Index(value = ["location_source", "location_timestamp"]),
    ],
)
data class LocationRecord(
    /** Unique, auto-generated row identifier. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    /** Latitude of the fix, or null when this record has no location. */
    @ColumnInfo(name = "latitude") val latitude: Double?,

    /** Longitude of the fix, or null when this record has no location. */
    @ColumnInfo(name = "longitude") val longitude: Double?,

    /** Wall-clock time (ms since epoch) at which the trigger was recognised / capture began. */
    @ColumnInfo(name = "trigger_timestamp") val triggerTimestamp: Long,

    /** Wall-clock time (ms since epoch) associated with the GPS fix itself; null when unlocated. */
    @ColumnInfo(name = "location_timestamp") val locationTimestamp: Long?,

    /** Reported horizontal accuracy in metres, when the provider supplies it. */
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,

    /** "fresh", "cached", "fallback" or "none". See [com.pocketgps.logger.location.LocationSource]. */
    @ColumnInfo(name = "location_source") val locationSource: String,

    /** Age of the fix in seconds when it was selected and used, for cached/fallback fixes. */
    @ColumnInfo(name = "location_age_seconds") val locationAgeSeconds: Int?,

    /** The normalised trigger phrase that caused this record, or "manual save" for buttons. */
    @ColumnInfo(name = "trigger_phrase") val triggerPhrase: String,

    /** "success" once committed. Failure rows are never inserted. */
    @ColumnInfo(name = "save_status") val saveStatus: String,

    /** System warnings attached at save time, e.g. stale fix or poor accuracy. Not user text. */
    @ColumnInfo(name = "notes") val notes: String? = null,

    /** The user's observation text (typed or dictated), or null for a location-only save. */
    @ColumnInfo(name = "observation") val observation: String? = null,

    /**
     * The raw recognised utterance behind [observation] for voice captures. Kept so nothing
     * recognised is silently discarded when the deterministic trigger/text split is ambiguous.
     */
    @ColumnInfo(name = "transcript") val transcript: String? = null,

    /** Optional user tag, e.g. "Building" or "Client". Free text, may be null. */
    @ColumnInfo(name = "category") val category: String? = null,

    /** Wall-clock time (ms since epoch) of the last edit or attach-later, null when never edited. */
    @ColumnInfo(name = "modified_timestamp") val modifiedTimestamp: Long? = null,
)
