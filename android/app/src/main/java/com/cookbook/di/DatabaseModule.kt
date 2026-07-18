package com.cookbook.di

import android.content.Context
import androidx.room.Room
import com.cookbook.data.local.db.CookbookDatabase
import com.cookbook.data.local.db.PendingRecipeOpDao
import com.cookbook.data.local.db.RecipeCacheDao
import com.cookbook.data.local.db.ShoppingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CookbookDatabase =
        Room.databaseBuilder(context, CookbookDatabase::class.java, "cookbook.db")
            // shopping_items carries offline queue rows (dirty/tombstoned/serverless) and
            // pending_recipe_ops is a write queue — a destructive rebuild DROPS unpushed user
            // writes, so schema bumps get real migrations now. The destructive fallback stays
            // only as a last-resort backstop for a version jump no migration covers.
            .addMigrations(
                CookbookDatabase.MIGRATION_3_4,
                CookbookDatabase.MIGRATION_4_5,
                CookbookDatabase.MIGRATION_5_6,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideShoppingDao(db: CookbookDatabase): ShoppingDao = db.shoppingDao()

    @Provides
    fun provideRecipeCacheDao(db: CookbookDatabase): RecipeCacheDao = db.recipeCacheDao()

    @Provides
    fun providePendingRecipeOpDao(db: CookbookDatabase): PendingRecipeOpDao =
        db.pendingRecipeOpDao()
}
