package com.cookbook.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** "today" / "yesterday" / "5d ago" / "3w ago" / "2mo ago" from an ISO-8601 timestamp. */
fun relativeDays(iso: String?, now: Instant = Instant.now()): String? {
    if (iso.isNullOrBlank()) return null
    val then = try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: DateTimeParseException) {
        return null
    }
    val days = ChronoUnit.DAYS.between(then, now)
    return when {
        days < 1 -> "today"
        days == 1L -> "yesterday"
        days < 14 -> "${days}d ago"
        days < 60 -> "${days / 7}w ago"
        days < 365 -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}
