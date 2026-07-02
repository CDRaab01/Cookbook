package com.cookbook.data.repository

import com.cookbook.data.remote.AddRecipeToListRequest
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.ShoppingItemCreateRequest
import com.cookbook.data.remote.ShoppingItemUpdateRequest
import com.cookbook.data.remote.ShoppingListOut
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShoppingRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : ShoppingRepository {

    override suspend fun getDefaultList(): ShoppingListOut = api.getDefaultList()

    override suspend fun addItem(
        listId: String,
        name: String,
        quantity: Double?,
        unit: String?,
        category: String?,
    ): ShoppingListOut =
        api.addShoppingItem(listId, ShoppingItemCreateRequest(name, quantity, unit, category))

    override suspend fun addRecipe(
        listId: String,
        recipeId: String,
        scale: Double,
        force: Boolean,
    ): ShoppingListOut = try {
        api.addRecipeToList(listId, AddRecipeToListRequest(recipeId, scale, force))
    } catch (e: HttpException) {
        if (e.code() == 409) throw RecipeAlreadyOnListException() else throw e
    }

    override suspend fun setChecked(
        listId: String,
        itemId: String,
        checked: Boolean,
    ): ShoppingListOut =
        api.updateShoppingItem(listId, itemId, ShoppingItemUpdateRequest(checked = checked))

    override suspend fun deleteItem(listId: String, itemId: String): ShoppingListOut =
        api.deleteShoppingItem(listId, itemId)

    override suspend fun clearChecked(listId: String): ShoppingListOut =
        api.clearCheckedItems(listId)
}
