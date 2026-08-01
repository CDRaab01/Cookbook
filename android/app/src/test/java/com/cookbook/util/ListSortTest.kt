package com.cookbook.util

import com.cookbook.data.remote.StoreAisleOut
import com.cookbook.data.remote.StorePlacementOut
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.StoreDetailOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four sort modes.
 *
 * Two properties matter most and are asserted for every mode: **nothing is ever dropped** (an item
 * you can't see is one you don't buy), and the *set* of rows is identical across modes — switching
 * only re-arranges. Everything else is a detail of one mode.
 */
class ListSortTest {

    private fun item(
        name: String,
        category: String? = null,
        key: String = name.lowercase(),
        createdAt: String = "",
        order: Int = 0,
    ) = ShoppingItemOut(
        id = "id_$name",
        name = name,
        category = category,
        key = key,
        createdAt = createdAt,
        order = order,
    )

    private fun store(vararg aisles: Pair<String, List<String>>) = StoreDetailOut(
        id = "store1",
        name = "Meijer",
        label = "Maysville Rd",
        createdAt = "2026-08-01T00:00:00Z",
        aisles = aisles.mapIndexed { i, (name, cats) ->
            StoreAisleOut(id = "a$i", order = i, name = name, categories = cats)
        },
        placements = emptyList(),
    )

    private val basket = listOf(
        item("Zucchini", "produce", createdAt = "2026-08-01T10:00:00Z", order = 0),
        item("apples", "produce", createdAt = "2026-08-01T12:00:00Z", order = 1),
        item("Milk", "dairy", createdAt = "2026-08-01T11:00:00Z", order = 2),
        item("bread", "bakery", createdAt = "2026-08-01T09:00:00Z", order = 3),
    )

    private fun List<AisleSection>.names() = flatMap { it.items }.map { it.name }

    private fun AisleSection.names() = items.map { it.name }

    // --- the invariants that hold for every mode ---

    @Test
    fun `no mode ever drops an item`() {
        val expected = basket.map { it.name }.toSet()
        ListSortMode.entries.forEach { mode ->
            val got = groupForDisplay(basket, mode, store("Produce" to listOf("produce"))).names()
            assertEquals("mode $mode changed the row set", expected, got.toSet())
            assertEquals("mode $mode duplicated a row", basket.size, got.size)
        }
    }

    @Test
    fun `an item with no category survives every mode`() {
        val items = basket + item("mystery thing", category = null)
        ListSortMode.entries.forEach { mode ->
            val got = groupForDisplay(items, mode, store("Produce" to listOf("produce"))).names()
            assertTrue("mode $mode dropped the uncategorized item", "mystery thing" in got)
        }
    }

    @Test
    fun `every mode renders an empty list as no sections rather than an empty heading`() {
        ListSortMode.entries.forEach { mode ->
            assertEquals(emptyList<AisleSection>(), groupForDisplay(emptyList(), mode, null))
        }
    }

    // --- store layout ---

    @Test
    fun `store layout routes by the store's aisles`() {
        val s = store(
            "Aisle A | 11" to listOf("produce"),
            "Aisle B | 17" to listOf("dairy"),
            "Aisle A | 27" to listOf("bakery"),
        )
        val sections = groupForDisplay(basket, ListSortMode.STORE_LAYOUT, s)
        assertEquals(listOf("Aisle A | 11", "Aisle B | 17", "Aisle A | 27"), sections.map { it.title })
        assertEquals(setOf("Zucchini", "apples"), sections[0].items.map { it.name }.toSet())
    }

    @Test
    fun `store layout with no store selected is the category grouping`() {
        // This is what makes STORE_LAYOUT a safe default: on upgrade, a user with no store sees
        // exactly the v0.11 behaviour rather than a list that silently changed shape.
        assertEquals(
            groupForDisplay(basket, ListSortMode.CATEGORIES, null).map { it.title },
            groupForDisplay(basket, ListSortMode.STORE_LAYOUT, null).map { it.title },
        )
    }

    @Test
    fun `a placement outranks the category, in store layout only`() {
        val s = store("Aisle A | 11" to listOf("produce"), "Aisle B | 17" to listOf("dairy"))
            .let { it.copy(placements = listOf(StorePlacementOut("p1", "a1", "apples", "apples"))) }

        val layout = groupForDisplay(basket, ListSortMode.STORE_LAYOUT, s)
        assertTrue("apples" in layout.first { it.title == "Aisle B | 17" }.items.map { it.name })

        // Categories mode ignores the store entirely, so the placement must not leak into it.
        val categories = groupForDisplay(basket, ListSortMode.CATEGORIES, s)
        assertTrue("apples" in categories.first { it.title == "Produce" }.items.map { it.name })
    }

    // --- categories ---

    @Test
    fun `categories ignores a selected store even when one is set`() {
        val s = store("Aisle A | 11" to listOf("produce"), "Aisle B | 17" to listOf("dairy"))
        val sections = groupForDisplay(basket, ListSortMode.CATEGORIES, s)
        assertTrue(sections.none { it.title.startsWith("Aisle ") })
        assertEquals(listOf("Produce", "Dairy & Eggs", "Bakery"), sections.map { it.title })
    }

    @Test
    fun `categories honours the user's saved aisle order`() {
        val reversed = DEFAULT_AISLE_ORDER.reversed()
        val sections = groupForDisplay(basket, ListSortMode.CATEGORIES, null, reversed)
        assertEquals(listOf("Bakery", "Dairy & Eggs", "Produce"), sections.map { it.title })
    }

    // --- alphabetical ---

    @Test
    fun `alphabetical is one flat case-insensitive run`() {
        val sections = groupForDisplay(basket, ListSortMode.ALPHABETICAL, null)
        assertEquals(1, sections.size)
        // Case-insensitive: "apples" must precede "bread" and "Milk", not be exiled by its case.
        assertEquals(listOf("apples", "bread", "Milk", "Zucchini"), sections.single().names())
    }

    @Test
    fun `flat modes suppress their section header`() {
        // A lone "All items" heading above the whole list says nothing the counts row doesn't.
        listOf(ListSortMode.ALPHABETICAL, ListSortMode.LAST_ADDED).forEach { mode ->
            assertFalse(groupForDisplay(basket, mode, null).single().showHeader)
        }
        assertTrue(groupForDisplay(basket, ListSortMode.CATEGORIES, null).first().showHeader)
    }

    @Test
    fun `alphabetical ignores the store and the category entirely`() {
        val s = store("Aisle A | 11" to listOf("produce"))
        assertEquals(
            groupForDisplay(basket, ListSortMode.ALPHABETICAL, null).names(),
            groupForDisplay(basket, ListSortMode.ALPHABETICAL, s).names(),
        )
    }

    // --- last added ---

    @Test
    fun `last added is newest first`() {
        val sections = groupForDisplay(basket, ListSortMode.LAST_ADDED, null)
        assertEquals(listOf("apples", "Milk", "Zucchini", "bread"), sections.single().names())
    }

    @Test
    fun `last added falls back to insertion order when nothing is stamped`() {
        // A Room mirror written before schema v8 has no createdAt on any row. Sorting those by an
        // empty string would be arbitrary; `order` is the list's own insertion sequence and is the
        // right answer for exactly that cache.
        val unstamped = basket.map { it.copy(createdAt = "") }
        val sections = groupForDisplay(unstamped, ListSortMode.LAST_ADDED, null)
        assertEquals(listOf("bread", "Milk", "apples", "Zucchini"), sections.single().names())
    }

    @Test
    fun `unstamped rows sink below stamped ones during a partial refresh`() {
        val mixed = basket.map { if (it.name == "Milk") it.copy(createdAt = "") else it }
        val names = groupForDisplay(mixed, ListSortMode.LAST_ADDED, null).single().names()
        assertEquals("Milk", names.last())
    }

    // --- the persisted value ---

    @Test
    fun `an unrecognised stored mode falls back to the default instead of throwing`() {
        // A preference written by a different build must never stop the list from rendering.
        assertEquals(ListSortMode.DEFAULT, ListSortMode.fromKey("SORT_BY_VIBES"))
        assertEquals(ListSortMode.DEFAULT, ListSortMode.fromKey(null))
        assertEquals(ListSortMode.DEFAULT, ListSortMode.fromKey(""))
    }

    @Test
    fun `every mode round-trips through its stored key`() {
        ListSortMode.entries.forEach { assertEquals(it, ListSortMode.fromKey(it.name)) }
    }

    @Test
    fun `the default is store layout so upgrading changes nothing`() {
        assertEquals(ListSortMode.STORE_LAYOUT, ListSortMode.DEFAULT)
    }
}
