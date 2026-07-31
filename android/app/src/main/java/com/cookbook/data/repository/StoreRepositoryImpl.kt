package com.cookbook.data.repository

import com.cookbook.data.local.db.PendingPlacementEntity
import com.cookbook.data.local.db.StoreAisleEntity
import com.cookbook.data.local.db.StoreDao
import com.cookbook.data.local.db.StoreEntity
import com.cookbook.data.local.db.StorePlacementEntity
import com.cookbook.data.remote.AisleIn
import com.cookbook.data.remote.AislesPutRequest
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.PlacementRequest
import com.cookbook.data.remote.StoreAisleOut
import com.cookbook.data.remote.StoreCreateRequest
import com.cookbook.data.remote.StoreDetailOut
import com.cookbook.data.remote.StoreLayoutDraftOut
import com.cookbook.data.remote.StoreOut
import com.cookbook.data.remote.StorePlacementOut
import com.cookbook.data.remote.StoreUpdateRequest
import com.cookbook.data.remote.SuggestLayoutRequest
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache-backed reads, online-only writes — with one deliberate exception.
 *
 * The same error discipline the shopping repository established (ARCHITECTURE.md): an
 * [IOException] is "unreachable", so reads fall back to the Room cache silently; an [HttpException]
 * is the server *refusing*, which is never absorbed. Store mutations are online-only because
 * reshaping a floor plan is a settling-down-at-home activity, and a half-synced layout is worse
 * than an error message.
 *
 * [placeItem] is the exception: it happens while standing in the aisle, so it applies optimistically
 * to the cache and queues on [IOException].
 */
@Singleton
class StoreRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val dao: StoreDao,
) : StoreRepository {

    override suspend fun stores(): List<StoreOut> = try {
        val fresh = api.getStores()
        dao.replaceStores(
            fresh.mapIndexed { index, s ->
                StoreEntity(s.id, s.name, s.label, s.isOwner, index)
            },
        )
        fresh
    } catch (_: IOException) {
        dao.stores().map { StoreOut(it.id, it.name, it.label, it.isOwner) }
    }

    override suspend fun store(storeId: String): StoreDetailOut? = try {
        val fresh = api.getStore(storeId)
        cache(fresh)
        fresh
    } catch (_: IOException) {
        cached(storeId)
    }

    override suspend fun create(
        name: String,
        label: String?,
        aisles: List<AisleIn>?,
    ): StoreDetailOut = api.createStore(StoreCreateRequest(name, label, aisles)).also { cache(it) }

    override suspend fun rename(storeId: String, name: String?, label: String?): StoreDetailOut =
        api.updateStore(storeId, StoreUpdateRequest(name, label)).also { cache(it) }

    override suspend fun delete(storeId: String) {
        api.deleteStore(storeId)
        dao.clearAisles(storeId)
        dao.clearPlacements(storeId)
        dao.replaceStores(api.getStores().mapIndexed { i, s -> StoreEntity(s.id, s.name, s.label, s.isOwner, i) })
    }

    override suspend fun putAisles(storeId: String, aisles: List<AisleIn>): StoreDetailOut =
        api.putStoreAisles(storeId, AislesPutRequest(aisles)).also { cache(it) }

    override suspend fun suggestLayout(chain: String): StoreLayoutDraftOut =
        api.suggestStoreLayout(SuggestLayoutRequest(chain))

    override suspend fun placeItem(storeId: String, itemName: String, aisleId: String) {
        // Optimistic first, so the list regroups under the finger regardless of signal. The id is
        // local and gets replaced wholesale by the server's row on the next successful fetch.
        dao.upsertPlacements(
            listOf(
                StorePlacementEntity(
                    id = "local:${UUID.randomUUID()}",
                    storeId = storeId,
                    aisleId = aisleId,
                    key = normalizeKeyForCacheOnly(itemName),
                    name = itemName,
                ),
            ),
        )
        try {
            cache(api.addStorePlacement(storeId, PlacementRequest(itemName, aisleId)))
        } catch (_: IOException) {
            dao.enqueuePlacement(
                PendingPlacementEntity(
                    storeId = storeId,
                    aisleId = aisleId,
                    itemName = itemName,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun removePlacement(storeId: String, placementId: String) {
        cache(api.deleteStorePlacement(storeId, placementId))
    }

    override suspend fun syncPendingPlacements() {
        for (row in dao.pendingPlacements()) {
            try {
                api.addStorePlacement(row.storeId, PlacementRequest(row.itemName, row.aisleId))
                dao.dequeuePlacement(row.localId)
            } catch (_: HttpException) {
                // The server refused (aisle deleted on another device, store gone). Drop it and
                // keep draining — one poison row must never wedge the backlog (the v0.5 lesson).
                dao.dequeuePlacement(row.localId)
            } catch (_: IOException) {
                return // still offline; keep the backlog for the next reconnect
            }
        }
        // Re-pull the stores we touched so the local ids are replaced by the server's truth.
        for (storeId in dao.stores().map { it.id }) {
            try {
                cache(api.getStore(storeId))
            } catch (_: IOException) {
                return
            } catch (_: HttpException) {
                // Deleted elsewhere; the next stores() refresh drops it from the cache.
            }
        }
    }

    private suspend fun cache(detail: StoreDetailOut) {
        val existing = dao.store(detail.id)
        dao.replaceDetail(
            StoreEntity(
                detail.id, detail.name, detail.label, detail.isOwner, existing?.order ?: 0,
            ),
            detail.aisles.map {
                StoreAisleEntity(it.id, detail.id, it.order, it.name, it.categories.joinToString(","))
            },
            detail.placements.map {
                StorePlacementEntity(it.id, detail.id, it.aisleId, it.key, it.name)
            },
        )
    }

    private suspend fun cached(storeId: String): StoreDetailOut? {
        val store = dao.store(storeId) ?: return null
        return StoreDetailOut(
            id = store.id,
            name = store.name,
            label = store.label,
            isOwner = store.isOwner,
            aisles = dao.aisles(storeId).map {
                StoreAisleOut(
                    it.id,
                    it.order,
                    it.name,
                    it.categories.split(',').map(String::trim).filter(String::isNotEmpty),
                )
            },
            placements = dao.placements(storeId).map {
                StorePlacementOut(it.id, it.aisleId, it.key, it.name)
            },
        )
    }

    /**
     * A **cache-only** stand-in for the server's `normalize_name`, used solely so an optimistic
     * placement matches the item the user just moved before the server's real row arrives. It is
     * deliberately not exposed and never persisted as truth: the server owns the key space (it is
     * the same one `item_history` uses), and a second implementation of that normalizer would drift.
     * Covering casefold + the plural fold is enough for the one row, one refresh window it serves.
     */
    private fun normalizeKeyForCacheOnly(name: String): String {
        val key = name.lowercase().trim().split(Regex("\\s+")).joinToString(" ")
        return when {
            key.length > 4 && key.endsWith("ies") -> key.dropLast(3) + "y"
            key.length > 4 && key.endsWith("oes") -> key.dropLast(2)
            key.length > 3 && key.endsWith("s") &&
                !key.endsWith("ss") && !key.endsWith("us") && !key.endsWith("is") -> key.dropLast(1)
            else -> key
        }
    }
}
