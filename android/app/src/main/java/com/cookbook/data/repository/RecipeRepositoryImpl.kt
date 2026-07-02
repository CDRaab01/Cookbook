package com.cookbook.data.repository

import com.cookbook.data.local.db.RecipeCacheDao
import com.cookbook.data.local.db.RecipeDetailCacheEntity
import com.cookbook.data.local.db.RecipeSummaryCacheEntity
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeImportRequest
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recipes with a read cache (CLAUDE.md §7 Phase 4): the book and details render offline from
 * JSON mirrors refreshed on every successful fetch. Writes stay online-only — editing a recipe
 * in a dead zone fails loudly rather than growing a merge story the app doesn't need.
 */
@Singleton
class RecipeRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val cache: RecipeCacheDao,
    private val json: Json,
) : RecipeRepository {

    override suspend fun listRecipes(): List<RecipeSummaryOut> = try {
        val fresh = api.listRecipes()
        cache.clearSummaries()
        cache.upsertSummaries(
            fresh.map {
                RecipeSummaryCacheEntity(
                    id = it.id,
                    json = json.encodeToString(RecipeSummaryOut.serializer(), it),
                    sortName = it.name.lowercase(),
                )
            },
        )
        fresh
    } catch (_: IOException) {
        cache.summaries().map { json.decodeFromString(RecipeSummaryOut.serializer(), it.json) }
    }

    override suspend fun getRecipe(id: String): RecipeOut = try {
        val fresh = api.getRecipe(id)
        cache.upsertDetail(
            RecipeDetailCacheEntity(id, json.encodeToString(RecipeOut.serializer(), fresh)),
        )
        fresh
    } catch (e: IOException) {
        val cached = cache.detail(id) ?: throw e
        json.decodeFromString(RecipeOut.serializer(), cached.json)
    }

    override suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut = api.createRecipe(req)

    override suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut {
        val updated = api.updateRecipe(id, req)
        cache.upsertDetail(
            RecipeDetailCacheEntity(id, json.encodeToString(RecipeOut.serializer(), updated)),
        )
        return updated
    }

    override suspend fun deleteRecipe(id: String) {
        api.deleteRecipe(id)
        cache.deleteDetail(id)
    }

    override suspend fun discoverRecipes(query: String): List<DiscoveredRecipe> =
        api.discoverRecipes(query)

    override suspend fun importRecipe(sourceId: String): RecipeOut {
        val imported = api.importRecipe(RecipeImportRequest(sourceId))
        cache.upsertDetail(
            RecipeDetailCacheEntity(
                imported.id,
                json.encodeToString(RecipeOut.serializer(), imported),
            ),
        )
        return imported
    }
}
