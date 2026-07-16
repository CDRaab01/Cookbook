package com.cookbook.data.repository

import com.cookbook.data.local.db.ShoppingDao
import com.cookbook.data.local.db.ShoppingItemEntity
import com.cookbook.data.remote.AddRecipeToListRequest
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.CookedOut
import com.cookbook.data.remote.CookedRequest
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.ForgotPasswordRequest
import com.cookbook.data.remote.SuiteLoginRequest
import com.cookbook.data.remote.ListCreateRequest
import com.cookbook.data.remote.ListRenameRequest
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.LogToPlateRequest
import com.cookbook.data.remote.LogToPlateResult
import com.cookbook.data.remote.LoginRequest
import com.cookbook.data.remote.PlanEntryCreateRequest
import com.cookbook.data.remote.PlanEntryOut
import com.cookbook.data.remote.PlanEntryUpdateRequest
import com.cookbook.data.remote.PlanToListRequest
import com.cookbook.data.remote.PlanToListResult
import com.cookbook.data.remote.PlateMigrationResult
import com.cookbook.data.remote.RecipePhotoDraftOut
import com.cookbook.data.remote.RecipeCreateRequest
import com.cookbook.data.remote.RecipeImportRequest
import com.cookbook.data.remote.RecipeImportUrlRequest
import com.cookbook.data.remote.RecipeNutritionOut
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.data.remote.RecipeUpdateRequest
import com.cookbook.data.remote.RegisterRequest
import com.cookbook.data.remote.ResetPasswordRequest
import com.cookbook.data.remote.ShoppingItemCreateRequest
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.ShoppingItemUpdateRequest
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.TokenResponse
import com.cookbook.data.remote.UserOut
import com.cookbook.data.remote.VersionOut
import com.cookbook.util.AppPreferences
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory DAO — the Room interface is small enough to fake faithfully. */
private class FakeShoppingDao : ShoppingDao {
    val rows = linkedMapOf<String, ShoppingItemEntity>()

    override suspend fun visibleItems(listId: String?) =
        rows.values
            .filter { !it.deleted && (listId == null || it.listId == listId) }
            .sortedBy { it.order }

    override suspend fun byLocalId(localId: String) = rows[localId]

    override suspend fun pendingRows() =
        rows.values.filter { it.dirty || it.deleted || it.serverId == null }

    override suspend fun upsert(item: ShoppingItemEntity) {
        rows[item.localId] = item
    }

    override suspend fun upsertAll(items: List<ShoppingItemEntity>) = items.forEach { upsert(it) }

    override suspend fun update(item: ShoppingItemEntity) {
        rows[item.localId] = item
    }

    override suspend fun delete(localId: String) {
        rows.remove(localId)
    }

    override suspend fun deleteClean(listId: String?): Int {
        val clean = rows.values.filter {
            !it.dirty && !it.deleted && it.serverId != null &&
                (listId == null || it.listId == listId)
        }
        clean.forEach { rows.remove(it.localId) }
        return clean.size
    }
}

/** Fake server: an in-memory item list + an `offline` switch that throws IOException. */
private class FakeApi : ApiService {
    var offline = false
    val serverItems = mutableListOf<ShoppingItemOut>()
    val listId = "list-1"

    private fun gate() {
        if (offline) throw IOException("offline")
    }

    private fun list() = ShoppingListOut(listId, "Groceries", serverItems.toList())

    override suspend fun getDefaultList(): ShoppingListOut {
        gate()
        return list()
    }

    override suspend fun getLists(): List<ListSummaryOut> {
        gate()
        return listOf(ListSummaryOut(id = listId, name = "Groceries"))
    }

    override suspend fun createList(req: ListCreateRequest): ShoppingListOut = error("unused")

    override suspend fun getList(listId: String): ShoppingListOut {
        gate()
        return list()
    }

    override suspend fun renameList(listId: String, req: ListRenameRequest): ShoppingListOut =
        error("unused")

    override suspend fun deleteList(listId: String) = error("unused")

    override suspend fun addShoppingItem(
        listId: String,
        req: ShoppingItemCreateRequest,
    ): ShoppingListOut {
        gate()
        serverItems += ShoppingItemOut(
            id = UUID.randomUUID().toString(),
            name = req.name,
            quantity = req.quantity,
            unit = req.unit,
            category = req.category,
            order = serverItems.size,
        )
        return list()
    }

    override suspend fun updateShoppingItem(
        listId: String,
        itemId: String,
        req: ShoppingItemUpdateRequest,
    ): ShoppingListOut {
        gate()
        val index = serverItems.indexOfFirst { it.id == itemId }
        if (index >= 0 && req.checked != null) {
            serverItems[index] = serverItems[index].copy(checked = req.checked!!)
        }
        return list()
    }

    override suspend fun deleteShoppingItem(listId: String, itemId: String): ShoppingListOut {
        gate()
        serverItems.removeAll { it.id == itemId }
        return list()
    }

    override suspend fun clearCheckedItems(listId: String): ShoppingListOut {
        gate()
        serverItems.removeAll { it.checked }
        return list()
    }

    // Unused surface.
    override suspend fun register(req: RegisterRequest): TokenResponse = error("unused")
    override suspend fun login(req: LoginRequest): TokenResponse = error("unused")
    override suspend fun forgotPassword(req: ForgotPasswordRequest) = error("unused")
    override suspend fun resetPassword(req: ResetPasswordRequest) = error("unused")
    override suspend fun suiteLogin(req: SuiteLoginRequest): TokenResponse = error("unused")
    override suspend fun getMe(): UserOut = error("unused")
    override suspend fun listRecipes(): List<RecipeSummaryOut> = error("unused")
    override suspend fun getRecipe(id: String): RecipeOut = error("unused")
    override suspend fun createRecipe(req: RecipeCreateRequest): RecipeOut = error("unused")
    override suspend fun updateRecipe(id: String, req: RecipeUpdateRequest): RecipeOut =
        error("unused")
    override suspend fun deleteRecipe(id: String) = error("unused")
    override suspend fun importPhoto(photo: okhttp3.MultipartBody.Part): RecipePhotoDraftOut =
        error("unused")
    override suspend fun getPlan(start: String, end: String, listId: String?): List<PlanEntryOut> = error("unused")
    override suspend fun createPlanEntry(req: PlanEntryCreateRequest, listId: String?): PlanEntryOut = error("unused")
    override suspend fun updatePlanEntry(id: String, req: PlanEntryUpdateRequest): PlanEntryOut = error("unused")
    override suspend fun shareRecipe(id: String, req: com.cookbook.data.remote.RecipeShareRequest): RecipeOut = error("unused")
    override suspend fun getHousehold() = error("unused")
    override suspend fun addHouseholdMember(req: com.cookbook.data.remote.AddMemberRequest) = error("unused")
    override suspend fun removeHouseholdMember(userId: String) = error("unused")
    override suspend fun leaveHousehold() = error("unused")
    override suspend fun getHouseholdInvite() = error("unused")
    override suspend fun acceptHouseholdInvite() = error("unused")
    override suspend fun declineHouseholdInvite() = error("unused")
    override suspend fun deletePlanEntry(id: String) = error("unused")
    override suspend fun planToList(req: PlanToListRequest): PlanToListResult = error("unused")
    override suspend fun addRecipeToList(
        listId: String,
        req: AddRecipeToListRequest,
    ): ShoppingListOut = error("unused")
    override suspend fun discoverRecipes(query: String): List<DiscoveredRecipe> = error("unused")
    override suspend fun previewRecipe(sourceId: String): RecipePreviewOut = error("unused")
    override suspend fun importRecipe(req: RecipeImportRequest): RecipeOut = error("unused")
    override suspend fun importRecipeFromUrl(req: RecipeImportUrlRequest): RecipeOut =
        error("unused")
    override suspend fun suggestItems(query: String): List<SuggestionOut> = error("unused")
    override suspend fun getGrocerySpend(): com.cookbook.data.remote.GrocerySpendOut? = null
    override suspend fun migrateFromPlate(): PlateMigrationResult = error("unused")
    override suspend fun scanPantry(photo: okhttp3.MultipartBody.Part) = error("unused")
    override suspend fun getPantry() = error("unused")
    override suspend fun addPantryItem(req: com.cookbook.data.remote.PantryItemCreateRequest) =
        error("unused")
    override suspend fun confirmPantryItems(req: com.cookbook.data.remote.PantryConfirmRequest) =
        error("unused")
    override suspend fun updatePantryItem(
        id: String,
        req: com.cookbook.data.remote.PantryItemUpdateRequest,
    ) = error("unused")
    override suspend fun deletePantryItem(id: String) = error("unused")
    override suspend fun getStaples() = error("unused")
    override suspend fun putStaples(req: com.cookbook.data.remote.StaplesPutRequest) =
        error("unused")
    override suspend fun getPantrySuggestions(maxMissing: Int) = error("unused")
    override suspend fun markCooked(id: String, body: CookedRequest): CookedOut = error("unused")
    override suspend fun unmarkCooked(id: String): CookedOut = error("unused")
    override suspend fun getRecipeNutrition(id: String): RecipeNutritionOut = error("unused")
    override suspend fun logRecipeToPlate(id: String, req: LogToPlateRequest): LogToPlateResult =
        error("unused")
    override suspend fun getServerVersion(): VersionOut = error("unused")
}

class ShoppingRepositorySyncTest {

    private val api = FakeApi()
    private val dao = FakeShoppingDao()
    private val prefs: AppPreferences = mock()

    private fun repository(): ShoppingRepositoryImpl {
        whenever(prefs.shoppingListId).thenReturn(flowOf("list-1"))
        return ShoppingRepositoryImpl(api, dao, prefs, kotlinx.serialization.json.Json)
    }

    @Test
    fun `online fetch mirrors the server into Room`() = runTest {
        api.serverItems += ShoppingItemOut(id = "s1", name = "Milk")
        val repo = repository()

        val list = repo.getDefaultList()

        assertEquals(1, list.items.size)
        assertEquals(1, dao.rows.size)
        assertEquals("s1", dao.rows.values.single().serverId)
    }

    @Test
    fun `offline fetch serves the mirror`() = runTest {
        api.serverItems += ShoppingItemOut(id = "s1", name = "Milk")
        val repo = repository()
        repo.getDefaultList() // prime the mirror

        api.offline = true
        val list = repo.getDefaultList()

        assertEquals(listOf("Milk"), list.items.map { it.name })
    }

    @Test
    fun `offline toggle keeps a dirty row and the local view flips`() = runTest {
        api.serverItems += ShoppingItemOut(id = "s1", name = "Milk")
        val repo = repository()
        repo.getDefaultList()

        api.offline = true
        val list = repo.setChecked("list-1", "s1", true)

        assertTrue(list.items.single().checked)
        assertTrue(dao.rows.getValue("s1").dirty)
        // The server hasn't heard about it yet.
        assertTrue(api.serverItems.single().checked.not())
    }

    @Test
    fun `offline add creates a serverless row that survives reconciliation`() = runTest {
        val repo = repository()
        repo.getDefaultList()

        api.offline = true
        val list = repo.addItem("list-1", "Paper towels", 1.0, null, null)

        assertEquals(listOf("Paper towels"), list.items.map { it.name })
        val row = dao.rows.values.single()
        assertEquals(null, row.serverId)
    }

    @Test
    fun `syncPending pushes toggles, adds, and deletes, then re-pulls`() = runTest {
        api.serverItems += ShoppingItemOut(id = "s1", name = "Milk")
        api.serverItems += ShoppingItemOut(id = "s2", name = "Bread")
        val repo = repository()
        repo.getDefaultList()

        api.offline = true
        repo.setChecked("list-1", "s1", true) // dirty toggle
        repo.addItem("list-1", "Eggs", 12.0, null, null) // serverless add
        repo.deleteItem("list-1", "s2") // tombstone

        api.offline = false
        repo.syncPending()

        // Server state reflects all three ops.
        val serverNames = api.serverItems.map { it.name }
        assertTrue("Eggs" in serverNames)
        assertTrue("Bread" !in serverNames)
        assertTrue(api.serverItems.first { it.name == "Milk" }.checked)
        // Mirror is clean: no pending rows, all rows have server ids.
        assertEquals(0, dao.pendingRows().size)
        assertTrue(dao.rows.values.all { it.serverId != null })
    }

    @Test
    fun `offline-added item checked offline lands checked after sync`() = runTest {
        val repo = repository()
        repo.getDefaultList()

        api.offline = true
        val list = repo.addItem("list-1", "Eggs", null, null, null)
        val localId = list.items.single().id
        repo.setChecked("list-1", localId, true)

        api.offline = false
        repo.syncPending()

        assertTrue(api.serverItems.single { it.name == "Eggs" }.checked)
    }

    @Test
    fun `sync interrupted by renewed outage keeps the backlog`() = runTest {
        api.serverItems += ShoppingItemOut(id = "s1", name = "Milk")
        val repo = repository()
        repo.getDefaultList()

        api.offline = true
        repo.setChecked("list-1", "s1", true)
        repo.syncPending() // still offline: no-op, backlog intact

        assertEquals(1, dao.pendingRows().size)
    }
}
