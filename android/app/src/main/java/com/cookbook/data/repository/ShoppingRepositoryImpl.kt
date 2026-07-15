package com.cookbook.data.repository

import com.cookbook.data.local.db.ShoppingDao
import com.cookbook.data.local.db.ShoppingItemEntity
import com.cookbook.data.remote.AddRecipeToListRequest
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.GrocerySpendOut
import com.cookbook.data.remote.ShareRequest
import com.cookbook.data.remote.ListCreateRequest
import com.cookbook.data.remote.ListRenameRequest
import com.cookbook.data.remote.ListSummaryOut
import com.cookbook.data.remote.MeasureOut
import com.cookbook.data.remote.ShoppingItemCreateRequest
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.ShoppingItemUpdateRequest
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.util.AppPreferences
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first shopping list (CLAUDE.md §1, §7 Phase 4). The server owns the truth and all
 * merge math; Room mirrors it so the in-store checklist works with zero signal.
 *
 * Contract: a network *outage* ([IOException]) is absorbed — the mutation lands in Room (dirty /
 * tombstoned / serverless) and the method returns the local view; [syncPending] pushes the
 * backlog when connectivity returns (NetworkSyncObserver) and every online call reconciles the
 * mirror. A real API rejection ([HttpException]) still throws — the server refusing is not the
 * same as the server being unreachable.
 */
@Singleton
class ShoppingRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val dao: ShoppingDao,
    private val appPreferences: AppPreferences,
    private val json: Json,
) : ShoppingRepository {

    override suspend fun getDefaultList(): ShoppingListOut {
        val activeId = appPreferences.shoppingListId.firstOrNull()
        return try {
            val fresh = if (activeId == null) {
                api.getDefaultList()
            } else {
                try {
                    api.getList(activeId)
                } catch (e: HttpException) {
                    // The active list was deleted elsewhere: fall back to the default.
                    if (e.code() == 404) api.getDefaultList() else throw e
                }
            }
            appPreferences.setShoppingListId(fresh.id)
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView(activeId)
        }
    }

    override suspend fun lists(): List<ListSummaryOut> = try {
        api.getLists()
    } catch (_: IOException) {
        emptyList()
    }

    override suspend fun setActiveList(listId: String) {
        appPreferences.setShoppingListId(listId)
    }

    override suspend fun createList(name: String): ShoppingListOut {
        val created = api.createList(ListCreateRequest(name))
        appPreferences.setShoppingListId(created.id)
        reconcile(created)
        return localView(created.id, created.name)
    }

    override suspend fun renameList(listId: String, name: String): ShoppingListOut {
        val renamed = api.renameList(listId, ListRenameRequest(name))
        reconcile(renamed)
        return localView(renamed.id, renamed.name)
    }

    override suspend fun deleteList(listId: String) {
        api.deleteList(listId)
        dao.deleteClean(listId)
        if (appPreferences.shoppingListId.firstOrNull() == listId) {
            val fallback = api.getDefaultList()
            appPreferences.setShoppingListId(fallback.id)
            reconcile(fallback)
        }
    }

    override suspend fun addItem(
        listId: String,
        name: String,
        quantity: Double?,
        unit: String?,
        category: String?,
    ): ShoppingListOut {
        val row = ShoppingItemEntity(
            localId = UUID.randomUUID().toString(),
            serverId = null,
            listId = listId,
            name = name,
            quantity = quantity,
            unit = unit,
            category = category,
            checked = false,
            recipeId = null,
            order = nextLocalOrder(listId),
        )
        dao.upsert(row)
        return try {
            val fresh = api.addShoppingItem(
                listId,
                ShoppingItemCreateRequest(name, quantity, unit, category),
            )
            dao.delete(row.localId) // the server list (which may have merged it) is now the truth
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView()
        }
    }

    override suspend fun addRecipe(
        listId: String,
        recipeId: String,
        scale: Double,
        force: Boolean,
    ): ShoppingListOut {
        // Requires the server: the merge + scale math is deliberately server-only. Offline is a
        // real failure here (unlike the in-store ops), surfaced to the caller as-is.
        val fresh = try {
            api.addRecipeToList(listId, AddRecipeToListRequest(recipeId, scale, force))
        } catch (e: HttpException) {
            if (e.code() == 409) throw RecipeAlreadyOnListException() else throw e
        }
        reconcile(fresh)
        return localView(fresh.id, fresh.name)
    }

    override suspend fun setChecked(
        listId: String,
        itemId: String,
        checked: Boolean,
    ): ShoppingListOut {
        val row = dao.byLocalId(itemId) ?: return localView()
        dao.update(row.copy(checked = checked, dirty = true))
        val serverId = row.serverId ?: return localView() // serverless rows push on sync
        return try {
            val fresh = api.updateShoppingItem(
                listId, serverId, ShoppingItemUpdateRequest(checked = checked),
            )
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView()
        }
    }

    override suspend fun editItem(
        listId: String,
        itemId: String,
        name: String,
        quantity: Double?,
        unit: String?,
        category: String?,
    ): ShoppingListOut {
        val row = dao.byLocalId(itemId) ?: return localView()
        dao.update(
            // An explicit edit overrides the aggregate; drop stale measures so the display
            // falls back to the edited single quantity/unit until the server reconciles.
            row.copy(
                name = name,
                quantity = quantity,
                unit = unit,
                measuresJson = null,
                category = category,
                dirty = true,
            ),
        )
        val serverId = row.serverId ?: return localView() // pushed by sync with the add
        return try {
            val fresh = api.updateShoppingItem(
                listId,
                serverId,
                ShoppingItemUpdateRequest(
                    name = name, quantity = quantity, unit = unit, category = category,
                ),
            )
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView()
        }
    }

    override suspend fun suggest(query: String): List<SuggestionOut> = try {
        if (query.isBlank()) emptyList() else api.suggestItems(query)
    } catch (_: IOException) {
        emptyList()
    }

    override suspend fun grocerySpend(): GrocerySpendOut? = try {
        api.getGrocerySpend()
    } catch (_: Exception) {
        null // integration off / offline / server hiccup — the tile just doesn't show
    }

    // Sharing is online-only (no offline mirror) — a light-touch, connectivity-required flow.
    override suspend fun listMembers(listId: String) = api.getListMembers(listId)

    override suspend fun shareList(listId: String, email: String) =
        api.shareList(listId, ShareRequest(email))

    override suspend fun removeMember(listId: String, memberId: String) =
        api.removeListMember(listId, memberId)

    override suspend fun me() = api.getMe()

    override suspend fun deleteItem(listId: String, itemId: String): ShoppingListOut {
        val row = dao.byLocalId(itemId) ?: return localView()
        if (row.serverId == null) {
            dao.delete(row.localId) // never reached the server; just drop it
            return localView()
        }
        dao.update(row.copy(deleted = true))
        return try {
            val fresh = api.deleteShoppingItem(listId, row.serverId)
            dao.delete(row.localId)
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView()
        }
    }

    override suspend fun clearChecked(listId: String): ShoppingListOut {
        // Tombstone locally first so the sweep works offline too.
        dao.visibleItems().filter { it.checked }.forEach { row ->
            if (row.serverId == null) dao.delete(row.localId)
            else dao.update(row.copy(deleted = true))
        }
        return try {
            val fresh = api.clearCheckedItems(listId)
            dao.pendingRows().filter { it.deleted }.forEach { dao.delete(it.localId) }
            reconcile(fresh)
            localView(fresh.id, fresh.name)
        } catch (_: IOException) {
            localView()
        }
    }

    /**
     * Push the offline backlog, then re-pull. Called on reconnect; every step tolerates the row
     * having changed server-side in the meantime (404s are treated as done).
     */
    suspend fun syncPending() {
        val fallbackListId = appPreferences.shoppingListId.firstOrNull() ?: return
        val pending = dao.pendingRows()
        if (pending.isEmpty()) return

        for (row in pending) {
            val listId = row.listId ?: fallbackListId
            try {
                when {
                    row.deleted && row.serverId != null -> {
                        api.deleteShoppingItem(listId, row.serverId)
                        dao.delete(row.localId)
                    }
                    row.deleted -> dao.delete(row.localId)
                    row.serverId == null -> {
                        val fresh = api.addShoppingItem(
                            listId,
                            ShoppingItemCreateRequest(
                                row.name, row.quantity, row.unit, row.category,
                            ),
                        )
                        // The POST payload can't carry `checked`; if the row was checked off
                        // while offline, find its (possibly merged) unchecked twin and check it.
                        if (row.checked) {
                            val twin = fresh.items.firstOrNull {
                                !it.checked &&
                                    it.name.equals(row.name, ignoreCase = true) &&
                                    it.unit == row.unit
                            }
                            if (twin != null) {
                                api.updateShoppingItem(
                                    listId, twin.id, ShoppingItemUpdateRequest(checked = true),
                                )
                            }
                        }
                        dao.delete(row.localId)
                    }
                    row.dirty && row.serverId != null -> {
                        // Push the full local state: dirty can mean an offline edit, not just
                        // a check-off (server-side nulls mean "untouched", so this is safe).
                        api.updateShoppingItem(
                            listId,
                            row.serverId,
                            ShoppingItemUpdateRequest(
                                name = row.name,
                                quantity = row.quantity,
                                unit = row.unit,
                                category = row.category,
                                checked = row.checked,
                            ),
                        )
                        dao.update(row.copy(dirty = false))
                    }
                }
            } catch (e: HttpException) {
                // The row is gone or conflicted server-side; the re-pull below is the truth.
                if (e.code() == 404 || e.code() == 409) dao.delete(row.localId) else throw e
            } catch (_: IOException) {
                return // still offline; keep the backlog for the next reconnect
            }
        }
        try {
            reconcile(api.getDefaultList())
        } catch (_: IOException) {
            // The pull retries next reconnect; the pushes above already landed.
        }
    }

    /** Replace the list's clean mirror rows with the server's; pending rows keep local truth. */
    private suspend fun reconcile(fresh: ShoppingListOut) {
        dao.deleteClean(fresh.id)
        val pendingIds = dao.pendingRows().mapNotNull { it.serverId }.toSet()
        dao.upsertAll(
            fresh.items
                .filter { it.id !in pendingIds }
                .map { it.toEntity(json, fresh.id) },
        )
    }

    private suspend fun localView(
        listId: String? = null,
        name: String = "Groceries",
    ): ShoppingListOut {
        val id = listId ?: appPreferences.shoppingListId.firstOrNull() ?: ""
        return ShoppingListOut(
            id = id,
            name = name,
            items = dao.visibleItems(id.ifEmpty { null }).map { it.toDto(json) },
        )
    }

    private suspend fun nextLocalOrder(listId: String): Int =
        (dao.visibleItems(listId).maxOfOrNull { it.order } ?: -1) + 1
}

private fun ShoppingItemOut.toEntity(json: Json, listId: String) = ShoppingItemEntity(
    localId = id,
    serverId = id,
    listId = listId,
    name = name,
    quantity = quantity,
    unit = unit,
    measuresJson = if (measures.isEmpty()) {
        null
    } else {
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MeasureOut.serializer()),
            measures,
        )
    },
    category = category,
    checked = checked,
    recipeId = recipeId,
    order = order,
)

private fun ShoppingItemEntity.toDto(json: Json) = ShoppingItemOut(
    id = localId,
    name = name,
    quantity = quantity,
    unit = unit,
    measures = measuresJson?.let {
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MeasureOut.serializer()),
                it,
            )
        }.getOrDefault(emptyList())
    } ?: emptyList(),
    category = category,
    checked = checked,
    recipeId = recipeId,
    order = order,
)
