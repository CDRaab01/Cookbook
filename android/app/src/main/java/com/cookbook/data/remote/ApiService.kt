package com.cookbook.data.remote

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
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

    // Trade a Dragonfly suite token for a Cookbook session (BROKER.md Phase 2c).
    @POST("auth/suite")
    suspend fun suiteLogin(@Body req: SuiteLoginRequest): TokenResponse

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

    @Multipart
    @POST("recipes/import-photo")
    suspend fun importPhoto(@Part photo: MultipartBody.Part): RecipePhotoDraftOut

    // --- Weekly meal planner ---
    @GET("plan")
    suspend fun getPlan(@Query("start") start: String, @Query("end") end: String): List<PlanEntryOut>

    @POST("plan")
    suspend fun createPlanEntry(@Body req: PlanEntryCreateRequest): PlanEntryOut

    @DELETE("plan/{id}")
    suspend fun deletePlanEntry(@Path("id") id: String)

    @POST("plan/to-list")
    suspend fun planToList(@Body req: PlanToListRequest): PlanToListResult

    // --- Made-it tracking ---
    @POST("recipes/{id}/cooked")
    suspend fun markCooked(@Path("id") id: String): CookedOut

    @DELETE("recipes/{id}/cooked/last")
    suspend fun unmarkCooked(@Path("id") id: String): CookedOut

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

    @GET("recipes/discover/{sourceId}")
    suspend fun previewRecipe(@Path("sourceId") sourceId: String): RecipePreviewOut

    @POST("recipes/import")
    suspend fun importRecipe(@Body req: RecipeImportRequest): RecipeOut

    @POST("recipes/import-url")
    suspend fun importRecipeFromUrl(@Body req: RecipeImportUrlRequest): RecipeOut

    // --- Shopping lists ---
    @GET("lists")
    suspend fun getLists(): List<ListSummaryOut>

    @POST("lists")
    suspend fun createList(@Body req: ListCreateRequest): ShoppingListOut

    @GET("lists/default")
    suspend fun getDefaultList(): ShoppingListOut

    @GET("lists/{listId}")
    suspend fun getList(@Path("listId") listId: String): ShoppingListOut

    @PATCH("lists/{listId}")
    suspend fun renameList(
        @Path("listId") listId: String,
        @Body req: ListRenameRequest,
    ): ShoppingListOut

    @DELETE("lists/{listId}")
    suspend fun deleteList(@Path("listId") listId: String)

    @GET("lists/suggest")
    suspend fun suggestItems(@Query("q") query: String): List<SuggestionOut>

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

    // --- Pantry ---
    @Multipart
    @POST("pantry/scan")
    suspend fun scanPantry(@Part photo: MultipartBody.Part): PantryScanDraftOut

    @GET("pantry")
    suspend fun getPantry(): List<PantryItemOut>

    @POST("pantry/items")
    suspend fun addPantryItem(@Body req: PantryItemCreateRequest): PantryItemOut

    @POST("pantry/confirm")
    suspend fun confirmPantryItems(@Body req: PantryConfirmRequest): List<PantryItemOut>

    @PATCH("pantry/items/{id}")
    suspend fun updatePantryItem(
        @Path("id") id: String,
        @Body req: PantryItemUpdateRequest,
    ): PantryItemOut

    @DELETE("pantry/items/{id}")
    suspend fun deletePantryItem(@Path("id") id: String)

    @GET("pantry/staples")
    suspend fun getStaples(): StaplesOut

    @PUT("pantry/staples")
    suspend fun putStaples(@Body req: StaplesPutRequest): StaplesOut

    @GET("pantry/suggestions")
    suspend fun getPantrySuggestions(@Query("max_missing") maxMissing: Int = 2): PantrySuggestionsOut

    // --- Plate migration ---
    @POST("migrate/plate")
    suspend fun migrateFromPlate(): PlateMigrationResult

    // --- Meta ---
    @GET("version")
    suspend fun getServerVersion(): VersionOut
}
