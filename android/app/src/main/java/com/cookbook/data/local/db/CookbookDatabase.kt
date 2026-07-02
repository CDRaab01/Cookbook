package com.cookbook.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ShoppingItemEntity::class,
        RecipeSummaryCacheEntity::class,
        RecipeDetailCacheEntity::class,
    ],
    version = 2, // v0.2.1: measuresJson on shopping_items (destructive rebuild — it's a mirror)
    exportSchema = false,
)
abstract class CookbookDatabase : RoomDatabase() {
    abstract fun shoppingDao(): ShoppingDao
    abstract fun recipeCacheDao(): RecipeCacheDao
}
