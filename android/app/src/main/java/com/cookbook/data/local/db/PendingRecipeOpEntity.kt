package com.cookbook.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One queued offline recipe write (the shopping list's op-queue idea, applied to the recipe
 * book's single offline-capable write). Today [opType] is only [OP_FAVORITE] and [boolValue]
 * carries the desired flag; the shape leaves room for more op kinds without a schema change.
 *
 * Ops are drained in [createdAtMs] order by `RecipeRepositoryImpl.syncPendingRecipeOps` on
 * reconnect. An op the server *rejects* (HttpException) is dropped and truth re-pulled — the
 * server wins; an op that can't reach the server (IOException) stays queued.
 */
@Entity(tableName = "pending_recipe_ops")
data class PendingRecipeOpEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val recipeId: String,
    val opType: String,
    val boolValue: Boolean,
    val createdAtMs: Long,
) {
    companion object {
        const val OP_FAVORITE = "favorite"
    }
}

@Dao
interface PendingRecipeOpDao {

    @Insert
    suspend fun insert(op: PendingRecipeOpEntity)

    @Query("SELECT * FROM pending_recipe_ops ORDER BY createdAtMs, localId")
    suspend fun all(): List<PendingRecipeOpEntity>

    @Query("DELETE FROM pending_recipe_ops WHERE localId = :localId")
    suspend fun deleteById(localId: Long)
}
