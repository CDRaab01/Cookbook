package com.cookbook.util

import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.StoreDetailOut

/**
 * How the shopping list is organised on screen. The user picks one; it is presentation only and
 * never changes a single stored field.
 *
 * Two of these group and two don't, which is the whole point of offering all four: [STORE_LAYOUT]
 * and [CATEGORIES] answer *"where do I walk next"*, while [ALPHABETICAL] and [LAST_ADDED] answer
 * *"is the thing I'm thinking of already on here"* — a question no grouping helps with, because
 * you don't know which aisle to look under.
 */
enum class ListSortMode(val label: String, val description: String) {
    /**
     * This store's real aisles in walk order. With no store selected this is exactly [CATEGORIES],
     * which is why it is the default: it reproduces the behaviour that shipped in v0.11 for both
     * cases, so nobody's list changes shape on upgrade.
     */
    STORE_LAYOUT("Store layout", "Aisles in walk order"),

    /** The 13 canonical categories in your saved aisle order, ignoring any selected store. */
    CATEGORIES("Categories", "Produce, dairy, pantry…"),

    /** One flat list, A–Z, case-insensitive. */
    ALPHABETICAL("A–Z", "One flat list by name"),

    /** One flat list, newest first. */
    LAST_ADDED("Last added", "Newest first"),
    ;

    companion object {
        val DEFAULT = STORE_LAYOUT

        /**
         * Parse a persisted value. Anything unrecognised — a older/newer build's key, a corrupted
         * preference — falls back to [DEFAULT] rather than throwing: a bad preference must never
         * be able to stop the shopping list from rendering.
         */
        fun fromKey(key: String?): ListSortMode =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** Section key for the single ungrouped section the flat modes produce. */
const val FLAT_KEY = "__flat"

/**
 * Group [items] for display under [mode].
 *
 * The grouped modes delegate to [groupForStore], which owns every routing rule (placement → first
 * aisle claiming the category → trailing "Unsorted") and is table-tested separately. [CATEGORIES]
 * is deliberately implemented as "route with no store" rather than as a second code path, so the
 * two can never disagree about how a category maps to a section.
 *
 * The flat modes return exactly one section with `showHeader = false`. They never drop an item and
 * never re-order anything but the top level, so switching modes is always reversible and always
 * shows the same set of rows.
 *
 * Pure: [items] is not mutated and nothing here reads a clock, a preference or the network.
 */
fun groupForDisplay(
    items: List<ShoppingItemOut>,
    mode: ListSortMode,
    store: StoreDetailOut?,
    aisleOrder: List<String> = DEFAULT_AISLE_ORDER,
): List<AisleSection> = when (mode) {
    ListSortMode.STORE_LAYOUT -> groupForStore(items, store, aisleOrder)
    // Explicitly pass no store: "Categories" means the category grouping even while standing in a
    // store you've selected, which is the only reason to offer it as a separate choice.
    ListSortMode.CATEGORIES -> groupForStore(items, null, aisleOrder)
    ListSortMode.ALPHABETICAL -> flatSection(items.sortedWith(byName))
    ListSortMode.LAST_ADDED -> flatSection(sortedByRecency(items))
}

private val byName: Comparator<ShoppingItemOut> =
    Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }

/**
 * Newest first.
 *
 * `createdAt` is the server's ISO-8601 UTC stamp, so a plain descending string compare *is* a
 * chronological compare — no date parsing, and therefore no locale or malformed-input hazard.
 *
 * The blank case is real rather than theoretical: rows served from the Room mirror carried no
 * stamp before schema v8, so a cache written by an older build has `createdAt == ""` on every row.
 * When **nothing** is stamped we sort by `order` instead, which is the list's own insertion
 * sequence and gives exactly the right answer for that cache. When only *some* rows are stamped
 * (a mirror mid-refresh) the unstamped ones sink to the bottom, ordered among themselves by
 * `order` — they are the stale rows, and they stop existing on the next successful sync.
 */
private fun sortedByRecency(items: List<ShoppingItemOut>): List<ShoppingItemOut> =
    if (items.none { it.createdAt.isNotBlank() }) {
        items.sortedByDescending { it.order }
    } else {
        items.sortedWith(
            compareByDescending<ShoppingItemOut> { it.createdAt }.thenByDescending { it.order },
        )
    }

private fun flatSection(sorted: List<ShoppingItemOut>): List<AisleSection> =
    if (sorted.isEmpty()) {
        emptyList()
    } else {
        listOf(AisleSection(FLAT_KEY, "All items", sorted, showHeader = false))
    }
