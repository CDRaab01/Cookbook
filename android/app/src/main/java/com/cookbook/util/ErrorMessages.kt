package com.cookbook.util

import java.io.IOException

/**
 * Error copy for the online-only surfaces (pantry, meal plan, recipe create/edit/delete/import —
 * deliberately not offline-capable): an unreachable server ([IOException]) gets one honest,
 * consistent line instead of a raw exception message; anything else (server rejections,
 * validation) keeps its own message with [fallback] as the last resort.
 */
fun Throwable.offlineAwareMessage(fallback: String): String = when (this) {
    is IOException -> "Can't reach the Cookbook server"
    else -> message ?: fallback
}
