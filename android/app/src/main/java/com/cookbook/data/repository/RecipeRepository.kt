package com.cookbook.data.repository

import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.LogToPlateResult
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeNutritionOut
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
    suspend fun discoverRecipes(query: String): List<DiscoveredRecipe>
    suspend fun importRecipe(sourceId: String): RecipeOut
    suspend fun getRecipeNutrition(id: String): RecipeNutritionOut
    suspend fun logRecipeToPlate(id: String, req: LogToPlateRequest): LogToPlateResult
}
