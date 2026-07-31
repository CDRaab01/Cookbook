package com.cookbook.di

import com.cookbook.BuildConfig
import com.cookbook.data.remote.ApiService
import com.cookbook.data.remote.AuthInterceptor
import com.cookbook.data.remote.HostSelectionInterceptor
import com.cookbook.data.remote.TokenRefreshAuthenticator
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        hostSelectionInterceptor: HostSelectionInterceptor,
        authInterceptor: AuthInterceptor,
        tokenRefreshAuthenticator: TokenRefreshAuthenticator,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            // OkHttp's 10s default read timeout is far too short for the LM Studio-backed
            // endpoints: a store-layout suggestion takes ~10s warm and the model reasons for
            // hundreds of tokens before replying, while a cold load costs more still. The read
            // budget is deliberately above the server's own 60s LM_STUDIO_TIMEOUT so the server's
            // honest 502/503/504 reaches the user instead of a generic client timeout.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Host selection runs first so the rewritten URL is what the auth header + logging see.
            .addInterceptor(hostSelectionInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            // Refreshes the access token on a 401 and retries, instead of failing the request.
            .authenticator(tokenRefreshAuthenticator)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
