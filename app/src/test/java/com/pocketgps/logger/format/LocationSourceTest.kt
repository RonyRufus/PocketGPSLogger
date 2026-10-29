package com.pocketgps.logger.format

import com.pocketgps.logger.location.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationSourceTest {

    @Test
    fun wireValuesAreStable() {
        assertEquals("fresh", LocationSource.FRESH.wire)
        assertEquals("cached", LocationSource.CACHED.wire)
        assertEquals("fallback", LocationSource.FALLBACK.wire)
    }

    @Test
    fun fromWireRoundTrips() {
        LocationSource.entries.forEach { source ->
            assertEquals(source, LocationSource.fromWire(source.wire))
        }
    }

    @Test
    fun unknownWireValueFallsBackSafely() {
        assertEquals(LocationSource.FALLBACK, LocationSource.fromWire(null))
        assertEquals(LocationSource.FALLBACK, LocationSource.fromWire("weird"))
    }
}
