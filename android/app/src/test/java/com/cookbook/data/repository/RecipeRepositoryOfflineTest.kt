package com.cookbook.data.repository

import com.cookbook.data.local.db.PendingRecipeOpDao
import com.cookbook.data.local.db.PendingRecipeOpEntity
import com.cookbook.data.local.db.RecipeCacheDao
import com.cookbook.data.local.db.RecipeDetailCacheEntity
import com.cookbook.data.local.db.RecipeSummaryCacheEntity
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipeSummaryOut
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory recipe cache — the Room interface is small enough to fake faithfully. */
private class FakeRecipeCacheDao : RecipeCacheDao {
    val summaryRows = linkedMapOf<String, RecipeSummaryCacheEntity>()
    val detailRows = linkedMapOf<String, RecipeDetailCacheEntity>()

    override suspend fun summaries() = summaryRows.values.sortedBy { it.sortName }
    override suspend fun summary(id: String) = summaryRows[id]
    override suspend fun upsertSummaries(items: List<RecipeSummaryCacheEntity>) {
        items.forEach { summaryRows[it.id] = it }
    }
    override suspend fun upsertSummary(item: RecipeSummaryCacheEntity) {
        summaryRows[item.id] = item
    }
    override suspend fun clearSummaries() = summaryRows.clear()
    override suspend fun detail(id: String) = detailRows[id]
    override suspend fun upsertDetail(item: RecipeDetailCacheEntity) {
        detailRows[item.id] = item
    }
    override suspend fun deleteDetail(id: String) {
        detailRows.remove(id)
    }
}

/** In-memory offline-op queue. */
private class FakePendingRecipeOpDao : PendingRecipeOpDao {
    private var nextId = 1L
    val ops = linkedMapOf<Long, PendingRecipeOpEntity>()

    override suspend fun insert(op: PendingRecipeOpEntity) {
        val id = nextId++
        ops[id] = op.copy(localId = id)
    }
    override suspend fun all() =
        ops.values.sortedWith(compareBy({ it.createdAtMs }, { it.localId }))
    override suspend fun deleteById(localId: Long) {
        ops.remove(localId)
    }
}

class RecipeRepositoryOfflineTest {

    private val api: ApiService = mock()
    private val cache = FakeRecipeCacheDao()
    private val pendingOps = FakePendingRecipeOpDao()
    private val json = Json
    private val repo = RecipeRepositoryImpl(api, cache, pendingOps, json)

    private fun recipe(id: String, favorite: Boolean = false) =
        RecipeOut(id = id, name = "Chili", servings = 2, favorite = favorite)

    private fun summaryOut(id: String, favorite: Boolean = false) =
        RecipeSummaryOut(id = id, name = "Chili", servings = 2, favorite = favorite)

    private fun httpException(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

    private fun cachedDetail(id: String): RecipeOut =
        json.decodeFromString(RecipeOut.serializer(), cache.detailRows.getValue(id).json)

    private fun cachedSummary(id: String): RecipeSummaryOut =
        json.decodeFromString(RecipeSummaryOut.serializer(), cache.summaryRows.getValue(id).json)

    // --- Staleness surfacing ---

    @Test
    fun `online list is fresh and stamps the cache`() = runTest {
        whenever(api.listRecipes()).thenReturn(listOf(summaryOut("r1")))

        val result = repo.listRecipes()

        assertNull(result.asOfMs)
        assertTrue(cache.summaryRows.getValue("r1").cachedAtMs > 0)
    }

    @Test
    fun `offline list serves the cache with its capture time`() = runTest {
        whenever(api.listRecipes())
            .thenReturn(listOf(summaryOut("r1")))
            .thenAnswer { throw IOException("offline") }
        repo.listRecipes() // prime + stamp

        val result = repo.listRecipes()

        assertEquals(listOf("r1"), result.value.map { it.id })
        val asOf = result.asOfMs
        assertNotNull(asOf)
        assertEquals(cache.summaryRows.getValue("r1").cachedAtMs, asOf)
    }

    @Test
    fun `offline detail serves the cache with its capture time`() = runTest {
        whenever(api.getRecipe("r1"))
            .thenReturn(recipe("r1"))
            .thenAnswer { throw IOException("offline") }
        repo.getRecipe("r1") // prime + stamp

        val result = repo.getRecipe("r1")

        assertEquals("r1", result.value.id)
        assertEquals(cache.detailRows.getValue("r1").cachedAtMs, result.asOfMs)
    }

    @Test
    fun `detail rejection still throws even with a cache present`() = runTest {
        whenever(api.getRecipe("r1"))
            .thenReturn(recipe("r1"))
            .thenThrow(httpException(403))
        repo.getRecipe("r1")

        // HttpException = the server refusing, not unreachable — never masked by the cache.
        assertFailsWith<HttpException> { repo.getRecipe("r1") }
    }

    @Test
    fun `offline detail with no cache rethrows`() = runTest {
        whenever(api.getRecipe("r1")).thenAnswer { throw IOException("offline") }

        assertFailsWith<IOException> { repo.getRecipe("r1") }
    }

    // --- Favorite offline queue ---

    private suspend fun primeCaches() {
        whenever(api.listRecipes()).thenReturn(listOf(summaryOut("r1")))
        whenever(api.getRecipe("r1")).thenReturn(recipe("r1"))
        repo.listRecipes()
        repo.getRecipe("r1")
    }

    @Test
    fun `offline favorite keeps the optimistic flip and queues the op`() = runTest {
        primeCaches()
        whenever(api.updateRecipe(eq("r1"), any())).thenAnswer { throw IOException("offline") }

        val result = repo.setFavorite("r1", true)

        assertTrue(result.favorite)
        assertTrue(cachedDetail("r1").favorite)
        assertTrue(cachedSummary("r1").favorite)
        val op = pendingOps.ops.values.single()
        assertEquals("r1", op.recipeId)
        assertEquals(PendingRecipeOpEntity.OP_FAVORITE, op.opType)
        assertTrue(op.boolValue)
    }

    @Test
    fun `rejected favorite reverts the blobs and rethrows`() = runTest {
        primeCaches()
        whenever(api.updateRecipe(eq("r1"), any())).thenThrow(httpException(403))

        assertFailsWith<HttpException> { repo.setFavorite("r1", true) }

        assertEquals(false, cachedDetail("r1").favorite)
        assertEquals(false, cachedSummary("r1").favorite)
        assertTrue(pendingOps.ops.isEmpty())
    }

    @Test
    fun `sync drains the queue and refreshes the blobs from the response`() = runTest {
        primeCaches()
        whenever(api.updateRecipe(eq("r1"), any()))
            .thenAnswer { throw IOException("offline") } // queues
            .thenReturn(recipe("r1", favorite = true)) // drains
        repo.setFavorite("r1", true)

        repo.syncPendingRecipeOps()

        assertTrue(pendingOps.ops.isEmpty())
        assertTrue(cachedDetail("r1").favorite)
        assertTrue(cachedSummary("r1").favorite)
    }

    @Test
    fun `sync rejection drops the op and re-pulls truth`() = runTest {
        primeCaches()
        whenever(api.updateRecipe(eq("r1"), any()))
            .thenAnswer { throw IOException("offline") } // queues (optimistic favorite=true)
            .thenThrow(httpException(409)) // then the server refuses the push
        repo.setFavorite("r1", true)
        // Server truth stays unfavorited; the re-pull must win over the optimistic blob.
        whenever(api.getRecipe("r1")).thenReturn(recipe("r1", favorite = false))

        repo.syncPendingRecipeOps()

        assertTrue(pendingOps.ops.isEmpty())
        assertEquals(false, cachedDetail("r1").favorite)
        assertEquals(false, cachedSummary("r1").favorite)
    }

    @Test
    fun `sync interrupted by renewed outage keeps the backlog`() = runTest {
        primeCaches()
        whenever(api.updateRecipe(eq("r1"), any())).thenAnswer { throw IOException("offline") }
        repo.setFavorite("r1", true) // queues, and the drain below stays offline too

        repo.syncPendingRecipeOps()

        assertEquals(1, pendingOps.ops.size)
    }
}
