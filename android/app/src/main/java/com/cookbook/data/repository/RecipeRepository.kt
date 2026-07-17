package com.cookbook.data.repository

import com.cookbook.data.remote.CookedOut
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.LogToPlateResult
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipePhotoDraftOut
import com.cookbook.data.remote.RecipeNutritionOut
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest

/** Recipe-book operations the UI layer depends on. Implemented by [RecipeRepositoryImpl]. */
interface RecipeRepository {
    /** Fresh from the server when reachable; the cached book (asOfMs = capture time) offline. */
    suspend fun listRecipes(): Stale<List<RecipeSummaryOut>>

    /** Fresh when reachable; the cached detail (asOfMs = capture time) offline, else rethrows. */
    suspend fun getRecipe(id: String): Stale<RecipeOut>
    suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut
    suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut
    suspend fun deleteRecipe(id: String)
    suspend fun discoverRecipes(query: String): List<DiscoveredRecipe>
    suspend fun previewRecipe(sourceId: String): RecipePreviewOut
    suspend fun importRecipe(sourceId: String): RecipeOut
    suspend fun importRecipeFromUrl(url: String): RecipeOut

    /**
     * Offline-capable: flips the cached blobs optimistically; an unreachable server queues the
     * op for reconnect sync instead of failing. A server *rejection* still throws (and reverts).
     */
    suspend fun setFavorite(id: String, favorite: Boolean): RecipeOut

    /** Family mode: share this recipe with the household (or make it private again). Creator only. */
    suspend fun setShared(id: String, shared: Boolean): RecipeOut
    suspend fun markCooked(id: String, rating: Int? = null): CookedOut
    suspend fun unmarkCooked(id: String): CookedOut
    suspend fun getRecipeNutrition(id: String): RecipeNutritionOut
    suspend fun logRecipeToPlate(id: String, req: LogToPlateRequest): LogToPlateResult
    suspend fun importPhoto(bytes: ByteArray, mimeType: String, fileName: String): RecipePhotoDraftOut
}
