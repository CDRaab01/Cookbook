package com.cookbook.util

import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeTimeTest {

    private val now: Instant = Instant.parse("2026-07-02T12:00:00Z")

    @Test
    fun buckets() {
        assertEquals("today", relativeDays("2026-07-02T08:00:00Z", now))
        assertEquals("yesterday", relativeDays("2026-07-01T08:00:00Z", now))
        assertEquals("5d ago", relativeDays("2026-06-27T08:00:00Z", now))
        assertEquals("3w ago", relativeDays("2026-06-07T08:00:00Z", now))
        assertEquals("2mo ago", relativeDays("2026-04-20T08:00:00Z", now))
        assertEquals("1y ago", relativeDays("2025-05-01T08:00:00Z", now))
    }

    @Test
    fun handlesOffsetsAndJunk() {
        assertEquals("today", relativeDays("2026-07-02T10:00:00+02:00", now))
        assertNull(relativeDays(null, now))
        assertNull(relativeDays("", now))
        assertNull(relativeDays("not-a-date", now))
    }
}
