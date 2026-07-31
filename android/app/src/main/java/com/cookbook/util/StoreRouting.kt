package com.cookbook.util

import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.StoreDetailOut

/**
 * One rendered section of the shopping list — an aisle when a store is selected, a category
 * otherwise. [key] is stable for LazyColumn; [title] is what the shopper reads.
 */
data class AisleSection(
    val key: String,
    val title: String,
    val items: List<ShoppingItemOut>,
)

/** Where items land when no aisle claims them. Rendered last, never persisted. */
const val UNSORTED_KEY = "__unsorted"
private const val UNSORTED_TITLE = "Unsorted"

/**
 * Group the list for display.
 *
 * With **no store selected** this is exactly the behaviour that shipped in v0.7: group by the
 * canonical category, in the user's saved aisle order, coercing null/unknown into "other" so an
 * item counted in "to buy" can never render under no section.
 *
 * With a **store selected**, sections become that store's real aisles in its walk order, and an
 * item finds its aisle by:
 *  1. a **placement** for its [ShoppingItemOut.key] — the store-specific exception someone learned
 *     by actually finding the thing ("peanut butter is aisle 5 here"), which outranks everything;
 *  2. otherwise the first aisle in walk order whose categories include the item's category. First
 *     wins so a category listed twice resolves deterministically and you meet it earliest;
 *  3. otherwise the trailing "Unsorted" section. Nothing is ever dropped — an item with no home is
 *     still an item you have to buy.
 *
 * Empty aisles are omitted: a walk order is only useful if it shows what's actually left.
 *
 * Pure and side-effect free — the server owns `key` (= `normalize_name`), so this never
 * re-implements normalization and can't drift from the merge module.
 */
fun groupForStore(
    items: List<ShoppingItemOut>,
    store: StoreDetailOut?,
    aisleOrder: List<String> = DEFAULT_AISLE_ORDER,
): List<AisleSection> {
    if (store == null || store.aisles.isEmpty()) {
        val known = aisleOrder.toSet()
        val grouped = items.groupBy { it.category?.takeIf { c -> c in known } ?: "other" }
        return aisleOrder.mapNotNull { category ->
            grouped[category]?.let { AisleSection(category, categoryLabel(category), it) }
        }
    }

    val aisles = store.aisles.sortedBy { it.order }
    // First aisle to claim a category keeps it; a later duplicate is unreachable by design.
    val aisleForCategory = HashMap<String, String>()
    for (aisle in aisles) {
        for (category in aisle.categories) aisleForCategory.putIfAbsent(category, aisle.id)
    }
    val aisleForItemKey = store.placements.associate { it.key to it.aisleId }
    val validAisleIds = aisles.map { it.id }.toSet()

    val grouped = items.groupBy { item ->
        // A placement pointing at an aisle that no longer exists (deleted on another device
        // before this cache refreshed) falls through to the category rule rather than vanishing.
        aisleForItemKey[item.key]?.takeIf { it in validAisleIds }
            ?: aisleForCategory[item.category ?: "other"]
            ?: UNSORTED_KEY
    }

    val sections = aisles.mapNotNull { aisle ->
        grouped[aisle.id]?.let { AisleSection(aisle.id, aisle.name, it) }
    }
    val unsorted = grouped[UNSORTED_KEY]
    return if (unsorted != null) {
        sections + AisleSection(UNSORTED_KEY, UNSORTED_TITLE, unsorted)
    } else {
        sections
    }
}
