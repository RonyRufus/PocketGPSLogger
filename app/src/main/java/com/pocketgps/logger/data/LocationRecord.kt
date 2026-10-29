package com.pocketgps.logger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single saved GPS location record.
 *
 * One row is written per accepted trigger. Rows are only written after the GPS fix has been
 * acquired and the insert has been confirmed by Room.
 */
@Entity(tableName = "location_records")
data class LocationRecord(
    /** Unique, auto-generated row identifier. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    @ColumnInfo(name = "latitude") val latitude: Double,

    @ColumnInfo(name = "longitude") val longitude: Double,

    /** Wall-clock time (ms since epoch) at which the trigger phrase was recognised. */
    @ColumnInfo(name = "trigger_timestamp") val triggerTimestamp: Long,

    /** Wall-clock time (ms since epoch) associated with the GPS fix itself. */
    @ColumnInfo(name = "location_timestamp") val locationTimestamp: Long,

    /** Reported horizontal accuracy in metres, when the provider supplies it. */
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float?,

    /** "fresh", "cached" or "fallback". See [LocationSource]. */
    @ColumnInfo(name = "location_source") val locationSource: String,

    /** Age of the fix in seconds at trigger time, for cached/fallback fixes. */
    @ColumnInfo(name = "location_age_seconds") val locationAgeSeconds: Int?,

    /** The normalised trigger phrase that caused this record. */
    @ColumnInfo(name = "trigger_phrase") val triggerPhrase: String,

    /** "success" once committed. Failure rows are never inserted. */
    @ColumnInfo(name = "save_status") val saveStatus: String,

    /** Reserved for future expansion; always null in v1. */
    @ColumnInfo(name = "notes") val notes: String? = null,
)
