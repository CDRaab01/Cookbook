package com.cookbook.util

/**
 * The store's aisles in default walk order — produce first, uncategorized ("other") last.
 * Mirrors the server's `STORE_CATEGORIES` (app/models/recipe.py); v0.7 widened this from 7
 * food-only buckets to the aisles a big-box store is actually walked.
 */
val DEFAULT_AISLE_ORDER: List<String> =
    listOf(
        "produce", "meat", "deli", "dairy", "bakery", "frozen", "pantry",
        "snacks", "beverages", "household", "personal", "baby", "other",
    )

/**
 * Reconcile a saved aisle order against the canonical set: keep the known categories in the saved
 * order, drop anything no longer a real category, and append any canonical category the save is
 * missing (so a new category can never vanish from the list). A blank/empty save → the default.
 */
fun reconcileAisleOrder(saved: List<String>): List<String> {
    val known = DEFAULT_AISLE_ORDER.toSet()
    val kept = saved.filter { it in known }.distinct()
    val missing = DEFAULT_AISLE_ORDER.filter { it !in kept }
    val result = kept + missing
    return result.ifEmpty { DEFAULT_AISLE_ORDER }
}
