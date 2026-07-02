package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IngredientIn(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String? = null,
    val note: String? = null,
)

@Serializable
data class RecipeCreateRequest(
    val name: String,
    val description: String? = null,
    val servings: Int = 1,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val steps: List<String> = emptyList(),
    val ingredients: List<IngredientIn> = emptyList(),
)

/** Partial update; null fields are left untouched by the server. */
@Serializable
data class RecipeUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val servings: Int? = null,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val steps: List<String>? = null,
    val ingredients: List<IngredientIn>? = null,
)

@Serializable
data class StepOut(
    val order: Int,
    val text: String,
)

@Serializable
data class IngredientOut(
    val id: String,
    val order: Int,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String? = null,
    val note: String? = null,
    @SerialName("plate_food_id") val plateFoodId: String? = null,
)

@Serializable
data class RecipeOut(
    val id: String,
    val name: String,
    val description: String? = null,
    val servings: Int,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    val source: String = "manual",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val steps: List<StepOut> = emptyList(),
    val ingredients: List<IngredientOut> = emptyList(),
)

@Serializable
data class DiscoveredRecipe(
    @SerialName("source_id") val sourceId: String,
    val title: String,
    val image: String? = null,
    @SerialName("ready_in_minutes") val readyInMinutes: Int? = null,
    val servings: Int? = null,
)

@Serializable
data class RecipeImportRequest(
    @SerialName("source_id") val sourceId: String,
)

@Serializable
data class RecipeSummaryOut(
    val id: String,
    val name: String,
    val description: String? = null,
    val servings: Int,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    val source: String = "manual",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("ingredient_count") val ingredientCount: Int = 0,
    @SerialName("step_count") val stepCount: Int = 0,
)
