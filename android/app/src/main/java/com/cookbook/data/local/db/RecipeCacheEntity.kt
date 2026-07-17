package com.cookbook.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Read cache for the recipe book (CLAUDE.md §7 Phase 4): whole DTOs stored as JSON blobs so the
 * list + detail screens render offline. Every write-through stamps [cachedAtMs] (capture time)
 * so an offline read can say *how old* the data it serves is — the repository surfaces it via
 * `Stale`. The one offline-capable write is the favorite toggle, which flips the cached blobs
 * optimistically and queues a `PendingRecipeOpEntity`; everything else is edited online only.
 *
 * cachedAtMs == 0 means "cached before stamping existed" (migration default) — treated as
 * unknown age, not 1970.
 */
@Entity(tableName = "recipe_summaries")
data class RecipeSummaryCacheEntity(
    @PrimaryKey val id: String,
    val json: String,
    val sortName: String,
    @ColumnInfo(defaultValue = "0") val cachedAtMs: Long,
)

@Entity(tableName = "recipe_details")
data class RecipeDetailCacheEntity(
    @PrimaryKey val id: String,
    val json: String,
    @ColumnInfo(defaultValue = "0") val cachedAtMs: Long,
)

@Dao
interface RecipeCacheDao {

    @Query("SELECT * FROM recipe_summaries ORDER BY sortName")
    suspend fun summaries(): List<RecipeSummaryCacheEntity>

    @Query("SELECT * FROM recipe_summaries WHERE id = :id")
    suspend fun summary(id: String): RecipeSummaryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(items: List<RecipeSummaryCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(item: RecipeSummaryCacheEntity)

    @Query("DELETE FROM recipe_summaries")
    suspend fun clearSummaries()

    @Query("SELECT * FROM recipe_details WHERE id = :id")
    suspend fun detail(id: String): RecipeDetailCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetail(item: RecipeDetailCacheEntity)

    @Query("DELETE FROM recipe_details WHERE id = :id")
    suspend fun deleteDetail(id: String)
}
