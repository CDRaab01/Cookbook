package com.cookbook.ui.cook

/**
 * Finds cookable durations in step text so cook mode can offer tap-to-start timers:
 * "simmer 30 minutes", "bake 1 hour 15 minutes", "rest for 45 min", "microwave 90 seconds".
 * Deliberately conservative — a miss just means no timer chip, never a wrong one.
 */
object StepDurations {

    private val PATTERN = Regex(
        """(\d+(?:\.\d+)?)\s*(hours?|hrs?|hr\b|h\b|minutes?|mins?|min\b|seconds?|secs?|sec\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** Total seconds of the first duration phrase in [text] (adjacent units combine:
     * "1 hour 15 minutes" → 4500), or null when none is found. */
    fun firstDurationSeconds(text: String): Int? {
        val matches = PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return null
        var total = 0.0
        var lastEnd = -1
        for (match in matches) {
            // Combine only *adjacent* phrases ("1 hour 15 minutes"); a second, separate
            // duration later in the step ("…then rest 10 minutes") starts a new phrase we skip.
            if (lastEnd != -1 && match.range.first - lastEnd > 4) break
            val value = match.groupValues[1].toDouble()
            total += when (match.groupValues[2].lowercase().trimEnd('.')) {
                in setOf("hour", "hours", "hr", "hrs", "h") -> value * 3600
                in setOf("minute", "minutes", "min", "mins") -> value * 60
                else -> value
            }
            lastEnd = match.range.last + 1
        }
        val seconds = total.toInt()
        // Guard nonsense: sub-5-second or multi-day "durations" are usually not timers.
        return seconds.takeIf { it in 5..(24 * 3600) }
    }

    /** "30:00" / "1:15:00" / "0:45" style label for a timer chip. */
    fun label(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
