package com.cookbook.di

import com.cookbook.data.repository.AuthRepository
import com.cookbook.data.repository.AuthRepositoryImpl
import com.cookbook.data.repository.PlanRepository
import com.cookbook.data.repository.PlanRepositoryImpl
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.data.repository.RecipeRepositoryImpl
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.data.repository.ShoppingRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindRecipeRepository(impl: RecipeRepositoryImpl): RecipeRepository

    @Binds
    @Singleton
    abstract fun bindShoppingRepository(impl: ShoppingRepositoryImpl): ShoppingRepository

    @Binds
    @Singleton
    abstract fun bindPlanRepository(impl: PlanRepositoryImpl): PlanRepository
}
