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
        StoreEntity::class,
        StoreAisleEntity::class,
        StorePlacementEntity::class,
        PendingPlacementEntity::class,
    ],
    // v4: cachedAtMs on the recipe caches + the pending_recipe_ops queue. NOTE the old
    // "destructive rebuild — it's a mirror" stance is retired: shopping_items carries offline
    // queue rows (dirty/tombstoned/serverless) and pending_recipe_ops is a queue outright —
    // migrate, don't drop. The destructive fallback in DatabaseModule is a last resort only.
    // v5: shopping_items.linkUrl (pasted product links). v6: shopping_items.imageUrl (thumbnails).
    // v7: store profiles + their aisles/placements (a read cache — aisle routing is only useful
    // inside the store, which is where the signal is worst), plus pending_placements, the one
    // store mutation that must survive no signal because moving an item to the aisle you actually
    // found it in is an in-store action.
    // v8: shopping_items.key + createdAt — the server-computed placement key and creation stamp.
    // Mirrored so store routing and the "Last added" sort keep working with no signal; without
    // them an offline list silently fell back to category grouping in the one place (the store)
    // the routing exists for.
    version = 8,
    exportSchema = false,
)
abstract class CookbookDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao
    abstract fun recipeCacheDao(): RecipeCacheDao
    abstract fun pendingRecipeOpDao(): PendingRecipeOpDao
    abstract fun storeDao(): StoreDao

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

        /** v6 → v7: the store cache + the offline "move to aisle" queue. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS stores (" +
                        "id TEXT PRIMARY KEY NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "label TEXT, " +
                        "isOwner INTEGER NOT NULL DEFAULT 1, " +
                        "`order` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS store_aisles (" +
                        "id TEXT PRIMARY KEY NOT NULL, " +
                        "storeId TEXT NOT NULL, " +
                        "`order` INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "categories TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS store_placements (" +
                        "id TEXT PRIMARY KEY NOT NULL, " +
                        "storeId TEXT NOT NULL, " +
                        "aisleId TEXT NOT NULL, " +
                        "key TEXT NOT NULL, " +
                        "name TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_placements (" +
                        "localId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "storeId TEXT NOT NULL, " +
                        "aisleId TEXT NOT NULL, " +
                        "itemName TEXT NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v7 → v8: mirror the server's placement `key` and creation stamp on the shopping rows.
         *
         * Both default to "" for existing rows, which is the honest value — the mirror genuinely
         * never stored them — and both are self-healing: the next successful list load overwrites
         * every clean row from the server. Until then an offline list routes by category and
         * "Last added" falls back to insertion order, which is what those columns' consumers are
         * written to expect.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN `key` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE shopping_items ADD COLUMN createdAt TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
