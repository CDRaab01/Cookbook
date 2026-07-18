package com.cookbook.data.repository

import com.cookbook.data.local.db.PendingRecipeOpDao
import com.cookbook.data.local.db.PendingRecipeOpEntity
import com.cookbook.data.local.db.RecipeCacheDao
import com.cookbook.data.local.db.RecipeDetailCacheEntity
import com.cookbook.data.local.db.RecipeSummaryCacheEntity
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.CookedOut
import com.cookbook.data.remote.CookedRequest
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.LogToPlateResult
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeImportRequest
import com.cookbook.data.remote.RecipeImportUrlRequest
import com.cookbook.data.remote.RecipeNutritionOut
import com.cookbook.data.remote.RecipePhotoDraftOut
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recipes with a read cache (CLAUDE.md §7 Phase 4): the book and details render offline from
 * JSON mirrors refreshed (and capture-time-stamped) on every successful fetch; offline reads
 * come back as [Stale] carrying that capture time so screens can say how old they are.
 *
 * Writes stay online-only — editing a recipe in a dead zone fails loudly rather than growing a
 * merge story the app doesn't need — with ONE exception: the favorite toggle (the shopping
 * contract applied to the book's lightest write). Offline ([IOException]) it flips the cached
 * blobs and queues a [PendingRecipeOpEntity] that [syncPendingRecipeOps] drains on reconnect;
 * a server rejection ([HttpException]) reverts the flip and still throws.
 */
@Singleton
class RecipeRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val cache: RecipeCacheDao,
    private val pendingOps: PendingRecipeOpDao,
    private val json: Json,
) : RecipeRepository {

    override suspend fun listRecipes(): Stale<List<RecipeSummaryOut>> = try {
        val fresh = api.listRecipes()
        val now = System.currentTimeMillis()
        cache.clearSummaries()
        cache.upsertSummaries(
            fresh.map {
                RecipeSummaryCacheEntity(
                    id = it.id,
                    json = json.encodeToString(RecipeSummaryOut.serializer(), it),
                    sortName = it.name.lowercase(),
                    cachedAtMs = now,
                )
            },
        )
        Stale(fresh, asOfMs = null)
    } catch (_: IOException) {
        val rows = cache.summaries()
        Stale(
            rows.map { json.decodeFromString(RecipeSummaryOut.serializer(), it.json) },
            // Rows are stamped in one pass, so min == the batch's capture time; 0 = a
            // pre-stamping row (unknown age — no honest timestamp to show).
            asOfMs = rows.mapNotNull { row -> row.cachedAtMs.takeIf { it > 0 } }.minOrNull(),
        )
    }

    override suspend fun getRecipe(id: String): Stale<RecipeOut> = try {
        val fresh = api.getRecipe(id)
        cacheDetail(fresh)
        Stale(fresh, asOfMs = null)
    } catch (e: IOException) {
        val cached = cache.detail(id) ?: throw e
        Stale(
            json.decodeFromString(RecipeOut.serializer(), cached.json),
            asOfMs = cached.cachedAtMs.takeIf { it > 0 },
        )
    }

    override suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut = api.createRecipe(req)

    override suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut {
        val updated = api.updateRecipe(id, req)
        cacheDetail(updated)
        return updated
    }

    override suspend fun deleteRecipe(id: String) {
        api.deleteRecipe(id)
        cache.deleteDetail(id)
    }

    override suspend fun discoverRecipes(query: String): List<DiscoveredRecipe> =
        api.discoverRecipes(query)

    override suspend fun previewRecipe(sourceId: String): RecipePreviewOut =
        api.previewRecipe(sourceId)

    override suspend fun importRecipeFromUrl(url: String): RecipeOut {
        val imported = api.importRecipeFromUrl(RecipeImportUrlRequest(url))
        cacheDetail(imported)
        return imported
    }

    override suspend fun setFavorite(id: String, favorite: Boolean): RecipeOut {
        // Optimistic: flip the cached blobs first so the heart is right even if we go offline.
        val detailBefore = cache.detail(id)
        val summaryBefore = cache.summary(id)
        applyFavoriteToCache(id, favorite)
        return try {
            val updated = api.updateRecipe(id, RecipeUpdateRequest(favorite = favorite))
            cacheDetail(updated)
            applySummaryFavorite(id, updated.favorite)
            updated
        } catch (e: IOException) {
            // Unreachable, not rejected: keep the optimistic state and queue the op for sync.
            // No cached detail to carry the optimistic state ⇒ fail like any online-only write
            // (nothing queued — a silent later sync of an "errored" tap would be a lie).
            val cached = cache.detail(id) ?: throw e
            pendingOps.insert(
                PendingRecipeOpEntity(
                    recipeId = id,
                    opType = PendingRecipeOpEntity.OP_FAVORITE,
                    boolValue = favorite,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
            json.decodeFromString(RecipeOut.serializer(), cached.json)
        } catch (e: HttpException) {
            // The server REFUSED (not unreachable): put the blobs back and let the caller error.
            detailBefore?.let { cache.upsertDetail(it) }
            summaryBefore?.let { cache.upsertSummary(it) }
            throw e
        }
    }

    /**
     * Drain the offline favorite queue in order (reconnect hook, after the shopping sync).
     * Per op: success ⇒ delete + refresh the blobs from the response; [HttpException] ⇒ the
     * server rejected it — drop the op and re-pull truth (server wins; a 404 purges the cached
     * detail); [IOException] ⇒ still offline — stop and keep the rest for the next reconnect.
     */
    suspend fun syncPendingRecipeOps() {
        for (op in pendingOps.all()) {
            try {
                when (op.opType) {
                    PendingRecipeOpEntity.OP_FAVORITE -> {
                        val updated = api.updateRecipe(
                            op.recipeId,
                            RecipeUpdateRequest(favorite = op.boolValue),
                        )
                        cacheDetail(updated)
                        applySummaryFavorite(op.recipeId, updated.favorite)
                    }
                    // Unknown op kind (downgrade artifact): nothing to push, fall through to delete.
                }
                pendingOps.deleteById(op.localId)
            } catch (_: HttpException) {
                pendingOps.deleteById(op.localId)
                try {
                    val truth = api.getRecipe(op.recipeId)
                    cacheDetail(truth)
                    applySummaryFavorite(op.recipeId, truth.favorite)
                } catch (inner: HttpException) {
                    if (inner.code() == 404) cache.deleteDetail(op.recipeId)
                } catch (_: IOException) {
                    // Re-pull retries via the next load; the stale blob keeps the optimistic flag
                    // until then.
                }
            } catch (_: IOException) {
                return // still offline; keep the backlog for the next reconnect
            }
        }
    }

    override suspend fun setShared(id: String, shared: Boolean): RecipeOut {
        val updated = api.shareRecipe(id, com.cookbook.data.remote.RecipeShareRequest(shared))
        cacheDetail(updated)
        return updated
    }

    override suspend fun markCooked(id: String, rating: Int?): CookedOut =
        api.markCooked(id, CookedRequest(rating = rating))

    override suspend fun unmarkCooked(id: String): CookedOut = api.unmarkCooked(id)

    override suspend fun getRecipeNutrition(id: String): RecipeNutritionOut =
        api.getRecipeNutrition(id)

    override suspend fun logRecipeToPlate(id: String, req: LogToPlateRequest): LogToPlateResult =
        api.logRecipeToPlate(id, req)

    override suspend fun importRecipe(sourceId: String): RecipeOut {
        val imported = api.importRecipe(RecipeImportRequest(sourceId))
        cacheDetail(imported)
        return imported
    }

    override suspend fun importPhoto(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): RecipePhotoDraftOut {
        val body = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("photo", fileName, body)
        return api.importPhoto(part)
    }

    /** Write-through for a fresh server detail: JSON blob + capture-time stamp. */
    private suspend fun cacheDetail(recipe: RecipeOut) {
        cache.upsertDetail(
            RecipeDetailCacheEntity(
                id = recipe.id,
                json = json.encodeToString(RecipeOut.serializer(), recipe),
                cachedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Flip `favorite` inside both cached blobs, preserving their capture-time stamps. */
    private suspend fun applyFavoriteToCache(id: String, favorite: Boolean) {
        cache.detail(id)?.let { row ->
            val recipe = json.decodeFromString(RecipeOut.serializer(), row.json)
            cache.upsertDetail(
                row.copy(
                    json = json.encodeToString(
                        RecipeOut.serializer(),
                        recipe.copy(favorite = favorite),
                    ),
                ),
            )
        }
        applySummaryFavorite(id, favorite)
    }

    private suspend fun applySummaryFavorite(id: String, favorite: Boolean) {
        cache.summary(id)?.let { row ->
            val summary = json.decodeFromString(RecipeSummaryOut.serializer(), row.json)
            if (summary.favorite != favorite) {
                cache.upsertSummary(
                    row.copy(
                        json = json.encodeToString(
                            RecipeSummaryOut.serializer(),
                            summary.copy(favorite = favorite),
                        ),
                    ),
                )
            }
        }
    }
}
