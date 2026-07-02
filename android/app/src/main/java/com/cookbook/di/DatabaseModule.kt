package com.cookbook.di

import android.content.Context
import androidx.room.Room
import com.cookbook.data.local.db.CookbookDatabase
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
            // The DB is a server mirror + pending-write buffer; on a schema bump a rebuild is
            // cheaper than a migration and only loses unpushed edits (the Spotter precedent).
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideShoppingDao(db: CookbookDatabase): ShoppingDao = db.shoppingDao()

    @Provides
    fun provideRecipeCacheDao(db: CookbookDatabase): RecipeCacheDao = db.recipeCacheDao()
}
