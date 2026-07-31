package com.cookbook.util

import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.StoreAisleOut
import com.cookbook.data.remote.StoreDetailOut
import com.cookbook.data.remote.StorePlacementOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aisle routing. The two rules worth protecting: nothing is ever dropped (an item with no home is
 * still an item you have to buy), and selecting a store can never make the list *worse* than the
 * category grouping it replaces.
 */
class StoreRoutingTest {

    private fun item(name: String, category: String?, key: String = name.lowercase()) =
        ShoppingItemOut(id = "id-$name", name = name, category = category, key = key)

    private fun store(
        aisles: List<StoreAisleOut>,
        placements: List<StorePlacementOut> = emptyList(),
    ) = StoreDetailOut(id = "s1", name = "Meijer", aisles = aisles, placements = placements)

    private fun aisle(id: String, order: Int, name: String, vararg categories: String) =
        StoreAisleOut(id = id, order = order, name = name, categories = categories.toList())

    // --- no store: exactly the v0.7 behaviour ---

    @Test
    fun `no store groups by category in the saved aisle order`() {
        val items = listOf(item("Milk", "dairy"), item("Apples", "produce"))
        val sections = groupForStore(items, store = null, aisleOrder = DEFAULT_AISLE_ORDER)
        assertEquals(listOf("produce", "dairy"), sections.map { it.key })
        assertEquals("Dairy & Eggs", sections[1].title)
    }

    @Test
    fun `no store honours a custom aisle order`() {
        val items = listOf(item("Milk", "dairy"), item("Apples", "produce"))
        val sections = groupForStore(items, null, listOf("dairy", "produce", "other"))
        assertEquals(listOf("dairy", "produce"), sections.map { it.key })
    }

    @Test
    fun `no store coerces null and unknown categories into other`() {
        // An item counted in "to buy" must never render under no section.
        val items = listOf(item("Mystery", null), item("Weird", "electronics"))
        val sections = groupForStore(items, null, DEFAULT_AISLE_ORDER)
        assertEquals(listOf("other"), sections.map { it.key })
        assertEquals(2, sections[0].items.size)
    }

    @Test
    fun `a store with no aisles falls back to category grouping`() {
        val sections = groupForStore(listOf(item("Milk", "dairy")), store(emptyList()))
        assertEquals(listOf("dairy"), sections.map { it.key })
    }

    // --- store selected ---

    @Test
    fun `items route to the aisle claiming their category, in walk order`() {
        val s = store(
            listOf(
                aisle("a1", 0, "Produce", "produce"),
                aisle("a2", 1, "Aisle 5 — Pantry", "pantry"),
            ),
        )
        val sections = groupForStore(listOf(item("Rice", "pantry"), item("Apples", "produce")), s)
        assertEquals(listOf("Produce", "Aisle 5 — Pantry"), sections.map { it.title })
        assertEquals(listOf("Apples"), sections[0].items.map { it.name })
    }

    @Test
    fun `aisles are ordered by their walk order, not their payload order`() {
        val s = store(
            listOf(
                aisle("a2", 1, "Second", "pantry"),
                aisle("a1", 0, "First", "produce"),
            ),
        )
        val sections = groupForStore(listOf(item("Rice", "pantry"), item("Apples", "produce")), s)
        assertEquals(listOf("First", "Second"), sections.map { it.title })
    }

    @Test
    fun `empty aisles are omitted`() {
        // A walk order is only useful if it shows what's actually left to buy.
        val s = store(
            listOf(
                aisle("a1", 0, "Produce", "produce"),
                aisle("a2", 1, "Bakery", "bakery"),
            ),
        )
        val sections = groupForStore(listOf(item("Apples", "produce")), s)
        assertEquals(listOf("Produce"), sections.map { it.title })
    }

    @Test
    fun `a category claimed twice resolves to the first aisle in walk order`() {
        val s = store(
            listOf(
                aisle("a1", 0, "Front cooler", "dairy"),
                aisle("a2", 1, "Back cooler", "dairy"),
            ),
        )
        val sections = groupForStore(listOf(item("Milk", "dairy")), s)
        assertEquals(listOf("Front cooler"), sections.map { it.title })
    }

    @Test
    fun `a placement beats the category mapping`() {
        // "Peanut butter is aisle 5 at *this* Meijer" — someone actually found it there.
        val s = store(
            listOf(
                aisle("a1", 0, "Pantry", "pantry"),
                aisle("a2", 1, "Aisle 5", "snacks"),
            ),
            listOf(StorePlacementOut("p1", "a2", "peanut butter", "Peanut Butter")),
        )
        val items = listOf(item("Peanut Butter", "pantry", key = "peanut butter"))
        val sections = groupForStore(items, s)
        assertEquals(listOf("Aisle 5"), sections.map { it.title })
    }

    @Test
    fun `a placement pointing at a deleted aisle falls back to the category`() {
        // The aisle was removed on another device before this cache refreshed; the item must not
        // vanish into a section that no longer exists.
        val s = store(
            listOf(aisle("a1", 0, "Pantry", "pantry")),
            listOf(StorePlacementOut("p1", "gone", "peanut butter", "Peanut Butter")),
        )
        val items = listOf(item("Peanut Butter", "pantry", key = "peanut butter"))
        val sections = groupForStore(items, s)
        assertEquals(listOf("Pantry"), sections.map { it.title })
    }

    @Test
    fun `an item whose key is blank routes by category`() {
        // An older server omits `key`; placements simply never match and routing still works.
        val s = store(
            listOf(aisle("a1", 0, "Pantry", "pantry")),
            listOf(StorePlacementOut("p1", "a1", "", "")),
        )
        val sections = groupForStore(listOf(item("Rice", "pantry", key = "")), s)
        assertEquals(listOf("Pantry"), sections.map { it.title })
        assertEquals(1, sections[0].items.size)
    }

    @Test
    fun `unclaimed categories land in a trailing unsorted section`() {
        val s = store(listOf(aisle("a1", 0, "Produce", "produce")))
        val sections = groupForStore(listOf(item("Apples", "produce"), item("Milk", "dairy")), s)
        assertEquals(listOf("Produce", "Unsorted"), sections.map { it.title })
        assertEquals(UNSORTED_KEY, sections.last().key)
        assertEquals(listOf("Milk"), sections.last().items.map { it.name })
    }

    @Test
    fun `an uncategorized item routes through the other category`() {
        val s = store(listOf(aisle("a1", 0, "Odds and ends", "other")))
        val sections = groupForStore(listOf(item("Mystery", null)), s)
        assertEquals(listOf("Odds and ends"), sections.map { it.title })
    }

    @Test
    fun `nothing is ever dropped`() {
        val s = store(
            listOf(aisle("a1", 0, "Produce", "produce")),
            listOf(StorePlacementOut("p1", "gone", "ghost", "Ghost")),
        )
        val items = listOf(
            item("Apples", "produce"),
            item("Milk", "dairy"),
            item("Mystery", null),
            item("Weird", "electronics"),
            item("Ghost", "produce", key = "ghost"),
        )
        val sections = groupForStore(items, s)
        assertEquals(items.size, sections.sumOf { it.items.size })
        assertEquals(items.map { it.id }.toSet(), sections.flatMap { it.items }.map { it.id }.toSet())
    }

    @Test
    fun `an empty list produces no sections`() {
        assertTrue(groupForStore(emptyList(), store(listOf(aisle("a1", 0, "Produce", "produce")))).isEmpty())
        assertTrue(groupForStore(emptyList(), null).isEmpty())
    }

    @Test
    fun `a default-seeded store reproduces the canonical category grouping`() {
        // The promise that makes selecting a store safe: before you edit anything, it looks
        // exactly like what you already had.
        val seeded = store(
            DEFAULT_AISLE_ORDER.mapIndexed { i, c -> aisle("a$i", i, categoryLabel(c), c) },
        )
        val items = listOf(item("Milk", "dairy"), item("Apples", "produce"), item("Rice", "pantry"))
        val viaStore = groupForStore(items, seeded)
        val viaCategories = groupForStore(items, null, DEFAULT_AISLE_ORDER)
        assertEquals(viaCategories.map { it.title }, viaStore.map { it.title })
        assertEquals(
            viaCategories.map { s -> s.items.map { it.id } },
            viaStore.map { s -> s.items.map { it.id } },
        )
    }
}
