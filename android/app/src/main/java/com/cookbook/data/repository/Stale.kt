package com.cookbook.data.repository

/**
 * A read that may have been served from the local cache instead of the server.
 *
 * [asOfMs] is null for a fresh server read; non-null it is the epoch millis the cached value was
 * captured at, so screens can render an honest "Offline — as of …" banner. Rows cached before
 * capture-time stamping existed (cachedAtMs == 0) surface as null too — there is no honest
 * timestamp to show, and the next successful fetch stamps them.
 */
data class Stale<T>(val value: T, val asOfMs: Long?)
