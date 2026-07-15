package com.cookbook.util

import org.junit.Test
import kotlin.test.assertEquals

class AisleOrderTest {

    @Test
    fun `a saved permutation is preserved`() {
        val saved = listOf("dairy", "produce", "meat", "bakery", "frozen", "pantry", "other")
        assertEquals(saved, reconcileAisleOrder(saved))
    }

    @Test
    fun `missing canonical categories are appended in default order`() {
        // Saved only reordered the first three; the rest must reappear (in default order).
        val saved = listOf("meat", "produce", "dairy")
        val result = reconcileAisleOrder(saved)
        assertEquals(listOf("meat", "produce", "dairy", "bakery", "frozen", "pantry", "other"), result)
        assertEquals(DEFAULT_AISLE_ORDER.toSet(), result.toSet()) // nothing lost
    }

    @Test
    fun `unknown or stale categories are dropped`() {
        val saved = listOf("produce", "deli", "meat") // "deli" is not a real category
        val result = reconcileAisleOrder(saved)
        assertEquals("produce", result.first())
        assert(!result.contains("deli"))
        assertEquals(DEFAULT_AISLE_ORDER.toSet(), result.toSet())
    }

    @Test
    fun `empty save falls back to the default order`() {
        assertEquals(DEFAULT_AISLE_ORDER, reconcileAisleOrder(emptyList()))
    }

    @Test
    fun `duplicates in a save are de-duped`() {
        val result = reconcileAisleOrder(listOf("meat", "meat", "produce"))
        assertEquals(DEFAULT_AISLE_ORDER.size, result.size)
        assertEquals("meat", result[0])
        assertEquals("produce", result[1])
    }
}
