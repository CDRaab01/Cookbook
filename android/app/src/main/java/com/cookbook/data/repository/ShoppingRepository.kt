package com.cookbook.data.repository

import com.cookbook.data.remote.GrocerySpendOut
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.SuggestionOut
import kotlinx.coroutines.flow.StateFlow

/** Shopping-list operations the UI layer depends on. Implemented by [ShoppingRepositoryImpl]. */
interface ShoppingRepository {
    /**
     * True while the latest server round-trip failed as *unreachable* (IOException) and the
     * repository is serving the Room mirror — the screen's "Offline — changes will sync"
     * banner. Cleared by the next successful reconcile (load, mutation, or reconnect sync).
     */
    val offline: StateFlow<Boolean>

    /** The ACTIVE list (user-switchable; falls back to the server default). */
    suspend fun getDefaultList(): ShoppingListOut

    /** All lists with to-buy counts; empty when offline. */
    suspend fun lists(): List<ListSummaryOut>

    suspend fun setActiveList(listId: String)
    suspend fun createList(name: String): ShoppingListOut
    suspend fun renameList(listId: String, name: String): ShoppingListOut
    suspend fun deleteList(listId: String)
    suspend fun addItem(
        listId: String,
        name: String,
        quantity: Double? = null,
        unit: String? = null,
        category: String? = null,
    ): ShoppingListOut

    /** @throws RecipeAlreadyOnListException when the server 409s (re-add needs [force]). */
    suspend fun addRecipe(
        listId: String,
        recipeId: String,
        scale: Double = 1.0,
        force: Boolean = false,
    ): ShoppingListOut

    suspend fun setChecked(listId: String, itemId: String, checked: Boolean): ShoppingListOut

    /** Edit an item's fields in place; offline-capable like check-off (pushed on sync). */
    suspend fun editItem(
        listId: String,
        itemId: String,
        name: String,
        quantity: Double?,
        unit: String?,
        category: String?,
    ): ShoppingListOut

    suspend fun deleteItem(listId: String, itemId: String): ShoppingListOut
    suspend fun clearChecked(listId: String): ShoppingListOut

    /** Autocomplete from the user's item history; empty when offline. */
    suspend fun suggest(query: String): List<SuggestionOut>

    /** This month's grocery spend from Magpie; null when the integration is off or unreachable. */
    suspend fun grocerySpend(): GrocerySpendOut?
}

/** The server's "this recipe is already on the list" 409 — the UI turns it into re-add/skip. */
class RecipeAlreadyOnListException : Exception("This recipe's items are already on the list")
