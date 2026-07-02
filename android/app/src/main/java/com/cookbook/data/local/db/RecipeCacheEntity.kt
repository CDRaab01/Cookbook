package com.cookbook.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Read cache for the recipe book (CLAUDE.md §7 Phase 4): whole DTOs stored as JSON blobs so the
 * list + detail screens render offline. Recipes are only *edited* online, so a serialized mirror
 * is honest — there's no local write state to merge, and the cache is simply replaced on each
 * successful fetch.
 */
@Entity(tableName = "recipe_summaries")
data class RecipeSummaryCacheEntity(
    @PrimaryKey val id: String,
    val json: String,
    val sortName: String,
)

@Entity(tableName = "recipe_details")
data class RecipeDetailCacheEntity(
    @PrimaryKey val id: String,
    val json: String,
)

@Dao
interface RecipeCacheDao {

    @Query("SELECT * FROM recipe_summaries ORDER BY sortName")
    suspend fun summaries(): List<RecipeSummaryCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummaries(items: List<RecipeSummaryCacheEntity>)

    @Query("DELETE FROM recipe_summaries")
    suspend fun clearSummaries()

    @Query("SELECT * FROM recipe_details WHERE id = :id")
    suspend fun detail(id: String): RecipeDetailCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetail(item: RecipeDetailCacheEntity)

    @Query("DELETE FROM recipe_details WHERE id = :id")
    suspend fun deleteDetail(id: String)
}
