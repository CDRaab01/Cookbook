package com.cookbook.data.repository

import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepositoryImpl @Inject constructor(
    private val api: ApiService,
) : RecipeRepository {

    override suspend fun listRecipes(): List<RecipeSummaryOut> = api.listRecipes()

    override suspend fun getRecipe(id: String): RecipeOut = api.getRecipe(id)

    override suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut = api.createRecipe(req)

    override suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut =
        api.updateRecipe(id, req)

    override suspend fun deleteRecipe(id: String) {
        api.deleteRecipe(id)
    }
}
