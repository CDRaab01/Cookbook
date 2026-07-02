package com.cookbook.data.repository

import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest

/** Recipe-book operations the UI layer depends on. Implemented by [RecipeRepositoryImpl]. */
interface RecipeRepository {
    suspend fun listRecipes(): List<RecipeSummaryOut>
    suspend fun getRecipe(id: String): RecipeOut
    suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut
    suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut
    suspend fun deleteRecipe(id: String)
}
