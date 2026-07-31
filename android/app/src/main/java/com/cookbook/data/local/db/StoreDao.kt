package com.cookbook.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StoreDao {

    @Query("SELECT * FROM stores ORDER BY `order`")
    suspend fun stores(): List<StoreEntity>

    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun store(storeId: String): StoreEntity?

    @Query("SELECT * FROM store_aisles WHERE storeId = :storeId ORDER BY `order`")
    suspend fun aisles(storeId: String): List<StoreAisleEntity>

    @Query("SELECT * FROM store_placements WHERE storeId = :storeId")
    suspend fun placements(storeId: String): List<StorePlacementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStores(rows: List<StoreEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAisles(rows: List<StoreAisleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlacements(rows: List<StorePlacementEntity>)

    @Query("DELETE FROM stores") suspend fun clearStores()

    @Query("DELETE FROM store_aisles WHERE storeId = :storeId") suspend fun clearAisles(storeId: String)

    @Query("DELETE FROM store_placements WHERE storeId = :storeId")
    suspend fun clearPlacements(storeId: String)

    /**
     * Replace the cached store list wholesale. Aisles and placements are keyed by store id and
     * refreshed by [replaceDetail] when a store is actually opened, so a store deleted on another
     * device leaves no rows behind here.
     */
    @Transaction
    suspend fun replaceStores(rows: List<StoreEntity>) {
        clearStores()
        upsertStores(rows)
    }

    /** Replace one store's floor plan — the aisle set is authoritative, never merged. */
    @Transaction
    suspend fun replaceDetail(
        store: StoreEntity,
        aisles: List<StoreAisleEntity>,
        placements: List<StorePlacementEntity>,
    ) {
        upsertStores(listOf(store))
        clearAisles(store.id)
        clearPlacements(store.id)
        upsertAisles(aisles)
        upsertPlacements(placements)
    }

    // --- the offline "move to aisle" queue ---

    @Query("SELECT * FROM pending_placements ORDER BY createdAtMs")
    suspend fun pendingPlacements(): List<PendingPlacementEntity>

    @Insert
    suspend fun enqueuePlacement(row: PendingPlacementEntity)

    @Query("DELETE FROM pending_placements WHERE localId = :localId")
    suspend fun dequeuePlacement(localId: Long)
}
