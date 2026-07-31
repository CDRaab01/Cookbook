package com.cookbook.data.repository

import com.cookbook.data.remote.AisleIn
import com.cookbook.data.remote.StoreDetailOut
import com.cookbook.data.remote.StoreLayoutDraftOut
import com.cookbook.data.remote.StoreOut

/**
 * Store profiles and their floor plans (v0.11).
 *
 * Reads are cache-backed so aisle routing survives the dead-signal aisle it exists for; writes are
 * online-only, with one deliberate exception — [placeItem], because moving an item to the aisle you
 * actually found it in is an *in-store* action and must not need bars.
 */
interface StoreRepository {
    /** Cached stores first; refreshed from the server when reachable. */
    suspend fun stores(): List<StoreOut>

    /** The full floor plan. Falls back to the cache when the server is unreachable. */
    suspend fun store(storeId: String): StoreDetailOut?

    suspend fun create(name: String, label: String?, aisles: List<AisleIn>?): StoreDetailOut

    suspend fun rename(storeId: String, name: String?, label: String?): StoreDetailOut

    suspend fun delete(storeId: String)

    suspend fun putAisles(storeId: String, aisles: List<AisleIn>): StoreDetailOut

    /** A draft layout for a chain name. Never throws on the model — see the server endpoint. */
    suspend fun suggestLayout(chain: String): StoreLayoutDraftOut

    /**
     * "I found it in that aisle." Applied optimistically to the cache and queued when offline, so
     * the regrouped list is immediate either way.
     */
    suspend fun placeItem(storeId: String, itemName: String, aisleId: String)

    suspend fun removePlacement(storeId: String, placementId: String)

    /** Drain the offline placement queue. Called by the same observer that drains shopping. */
    suspend fun syncPendingPlacements()
}
