package com.cookbook.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShoppingItemEntity::class,
        RecipeSummaryCacheEntity::class,
        RecipeDetailCacheEntity::class,
        PendingRecipeOpEntity::class,
    ],
    // v4: cachedAtMs on the recipe caches + the pending_recipe_ops queue. NOTE the old
    // "destructive rebuild — it's a mirror" stance is retired: shopping_items carries offline
    // queue rows (dirty/tombstoned/serverless) and pending_recipe_ops is a queue outright —
    // migrate, don't drop. The destructive fallback in DatabaseModule is a last resort only.
    // v5: shopping_items.linkUrl (pasted product links). v6: shopping_items.imageUrl (thumbnails).
    version = 6,
    exportSchema = false,
)
abstract class CookbookDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao
    abstract fun recipeCacheDao(): RecipeCacheDao
    abstract fun pendingRecipeOpDao(): PendingRecipeOpDao

    companion object {
        /** v3 → v4: stamp columns on the recipe caches + the recipe offline-op queue. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recipe_summaries ADD COLUMN cachedAtMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE recipe_details ADD COLUMN cachedAtMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_recipe_ops (" +
                        "localId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "recipeId TEXT NOT NULL, " +
                        "opType TEXT NOT NULL, " +
                        "boolValue INTEGER NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL)",
                )
            }
        }

        /** v4 → v5: product-link column on the shopping mirror. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN linkUrl TEXT")
            }
        }

        /** v5 → v6: product-thumbnail column on the shopping mirror. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN imageUrl TEXT")
            }
        }
    }
}
