package com.cookbook.data.repository

import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.SuggestionOut

/** Shopping-list operations the UI layer depends on. Implemented by [ShoppingRepositoryImpl]. */
interface ShoppingRepository {
    suspend fun getDefaultList(): ShoppingListOut
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
}

/** The server's "this recipe is already on the list" 409 — the UI turns it into re-add/skip. */
class RecipeAlreadyOnListException : Exception("This recipe's items are already on the list")
