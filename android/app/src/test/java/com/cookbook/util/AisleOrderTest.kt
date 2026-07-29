package com.cookbook.util

import org.junit.Test
import kotlin.test.assertEquals

class AisleOrderTest {

    @Test
    fun `a saved full permutation is preserved`() {
        val saved = DEFAULT_AISLE_ORDER.reversed()
        assertEquals(saved, reconcileAisleOrder(saved))
    }

    @Test
    fun `missing canonical categories are appended in default order`() {
        // Saved only reordered the first three; the rest must reappear (in default order).
        val saved = listOf("meat", "produce", "dairy")
        val result = reconcileAisleOrder(saved)
        val rest = DEFAULT_AISLE_ORDER.filter { it !in saved }
        assertEquals(saved + rest, result)
        assertEquals(DEFAULT_AISLE_ORDER.toSet(), result.toSet()) // nothing lost
    }

    @Test
    fun `unknown or stale categories are dropped`() {
        val saved = listOf("produce", "seafood", "meat") // "seafood" is not a real category
        val result = reconcileAisleOrder(saved)
        assertEquals("produce", result.first())
        assert(!result.contains("seafood"))
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

    @Test
    fun `category labels cover the multi-word aisles and the null case`() {
        // Moved here from RecipeDetailScreen when the recipe screen stopped grouping by aisle —
        // aisles are a shopping concern, so their labels live with the aisle order.
        assertEquals("Meat & Seafood", categoryLabel("meat"))
        assertEquals("Dairy & Eggs", categoryLabel("dairy"))
        assertEquals("Personal care", categoryLabel("personal"))
        assertEquals("Produce", categoryLabel("produce"))
        assertEquals("Other", categoryLabel(null))
        // Every canonical aisle gets a non-empty, capitalized label.
        DEFAULT_AISLE_ORDER.forEach {
            val label = categoryLabel(it)
            assert(label.isNotBlank())
            assertEquals(label.first().uppercaseChar(), label.first())
        }
    }
}
