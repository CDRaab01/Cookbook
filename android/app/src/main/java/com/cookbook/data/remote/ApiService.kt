package com.cookbook.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    // --- Auth ---
    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenResponse

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): TokenResponse

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body req: ForgotPasswordRequest)

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body req: ResetPasswordRequest)

    // --- Users ---
    @GET("users/me")
    suspend fun getMe(): UserOut

    // --- Recipes ---
    @GET("recipes")
    suspend fun listRecipes(): List<RecipeSummaryOut>

    @GET("recipes/{id}")
    suspend fun getRecipe(@Path("id") id: String): RecipeOut

    @POST("recipes")
    suspend fun createRecipe(@Body req: RecipeCreateRequest): RecipeOut

    @PATCH("recipes/{id}")
    suspend fun updateRecipe(@Path("id") id: String, @Body req: RecipeUpdateRequest): RecipeOut

    @DELETE("recipes/{id}")
    suspend fun deleteRecipe(@Path("id") id: String)

    // --- Plate integration (nutrition estimate + log-to-diary) ---
    @GET("recipes/{id}/nutrition")
    suspend fun getRecipeNutrition(@Path("id") id: String): RecipeNutritionOut

    @POST("recipes/{id}/log-to-plate")
    suspend fun logRecipeToPlate(
        @Path("id") id: String,
        @Body req: LogToPlateRequest,
    ): LogToPlateResult

    // --- Recipe discovery (Spoonacular via the server) ---
    @GET("recipes/discover")
    suspend fun discoverRecipes(@Query("q") query: String): List<DiscoveredRecipe>

    @POST("recipes/import")
    suspend fun importRecipe(@Body req: RecipeImportRequest): RecipeOut

    // --- Shopping list ---
    @GET("lists/default")
    suspend fun getDefaultList(): ShoppingListOut

    @POST("lists/{listId}/items")
    suspend fun addShoppingItem(
        @Path("listId") listId: String,
        @Body req: ShoppingItemCreateRequest,
    ): ShoppingListOut

    @POST("lists/{listId}/add-recipe")
    suspend fun addRecipeToList(
        @Path("listId") listId: String,
        @Body req: AddRecipeToListRequest,
    ): ShoppingListOut

    @PATCH("lists/{listId}/items/{itemId}")
    suspend fun updateShoppingItem(
        @Path("listId") listId: String,
        @Path("itemId") itemId: String,
        @Body req: ShoppingItemUpdateRequest,
    ): ShoppingListOut

    @DELETE("lists/{listId}/items/{itemId}")
    suspend fun deleteShoppingItem(
        @Path("listId") listId: String,
        @Path("itemId") itemId: String,
    ): ShoppingListOut

    @POST("lists/{listId}/clear-checked")
    suspend fun clearCheckedItems(@Path("listId") listId: String): ShoppingListOut

    // --- Plate migration ---
    @POST("migrate/plate")
    suspend fun migrateFromPlate(): PlateMigrationResult

    // --- Meta ---
    @GET("version")
    suspend fun getServerVersion(): VersionOut
}
