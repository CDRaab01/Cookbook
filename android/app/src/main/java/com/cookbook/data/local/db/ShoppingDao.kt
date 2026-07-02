package com.cookbook.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ShoppingDao {

    @Query("SELECT * FROM shopping_items WHERE deleted = 0 AND (:listId IS NULL OR listId = :listId) ORDER BY `order`")
    suspend fun visibleItems(listId: String? = null): List<ShoppingItemEntity>

    @Query("SELECT * FROM shopping_items WHERE localId = :localId")
    suspend fun byLocalId(localId: String): ShoppingItemEntity?

    @Query("SELECT * FROM shopping_items WHERE dirty = 1 OR deleted = 1 OR serverId IS NULL")
    suspend fun pendingRows(): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShoppingItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ShoppingItemEntity>)

    @Update
    suspend fun update(item: ShoppingItemEntity)

    @Query("DELETE FROM shopping_items WHERE localId = :localId")
    suspend fun delete(localId: String)

    /** Reconciliation step: clear a list's fully-synced rows before re-inserting the server's. */
    @Query(
        "DELETE FROM shopping_items WHERE dirty = 0 AND deleted = 0 AND serverId IS NOT NULL " +
            "AND (:listId IS NULL OR listId = :listId)",
    )
    suspend fun deleteClean(listId: String? = null): Int
}
