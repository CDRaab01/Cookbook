package com.cookbook.ui.stores

import com.cookbook.util.DEFAULT_AISLE_ORDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the aisle editor. These are the rules a user would notice getting wrong:
 * every category reachable, and no category claimed by two aisles (the server routes a duplicate
 * to the first aisle in walk order, so showing it twice would display a rule the list doesn't
 * follow).
 */
class StoreEditLogicTest {

    @Test
    fun `default rows cover every category exactly once, in walk order`() {
        val rows = defaultAisleRows()
        assertEquals(DEFAULT_AISLE_ORDER, rows.flatMap { it.categories })
        assertTrue(rows.all { it.id == null })
        assertEquals("Meat & Seafood", rows[1].name)
    }

    @Test
    fun `nothing is unclaimed in the default layout`() {
        assertEquals(emptyList<String>(), unclaimedCategories(defaultAisleRows()))
    }

    @Test
    fun `unclaimed reports what has no aisle, in canonical order`() {
        val rows = listOf(
            AisleRow(null, "Produce", listOf("produce")),
            AisleRow(null, "Cooler", listOf("dairy")),
        )
        val unclaimed = unclaimedCategories(rows)
        assertTrue("produce" !in unclaimed && "dairy" !in unclaimed)
        assertTrue("pantry" in unclaimed && "other" in unclaimed)
        assertEquals(DEFAULT_AISLE_ORDER.filter { it in unclaimed }, unclaimed)
    }

    @Test
    fun `an aisle with no categories leaves everything unclaimed`() {
        val rows = listOf(AisleRow(null, "Checkout", emptyList()))
        assertEquals(DEFAULT_AISLE_ORDER, unclaimedCategories(rows))
    }

    @Test
    fun `empty rows means nothing is claimed`() {
        assertEquals(DEFAULT_AISLE_ORDER, unclaimedCategories(emptyList()))
    }
}
