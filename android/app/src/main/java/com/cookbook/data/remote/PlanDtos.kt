package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val MEAL_SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

@Serializable
data class PlanEntryCreateRequest(
    val date: String, // yyyy-MM-dd
    val slot: String,
    @SerialName("recipe_id") val recipeId: String? = null,
    val note: String? = null,
)

@Serializable
data class PlanEntryOut(
    val id: String,
    val date: String,
    val slot: String,
    @SerialName("recipe_id") val recipeId: String? = null,
    @SerialName("recipe_name") val recipeName: String? = null,
    @SerialName("recipe_image_url") val recipeImageUrl: String? = null,
    val note: String? = null,
    // Per-user (the signed-in caller's): whether they've confirmed eating this, and the portion.
    val eaten: Boolean = false,
    val servings: Double = 1.0,
)

@Serializable
data class PlanEntryUpdateRequest(val eaten: Boolean, val servings: Double = 1.0)

@Serializable
data class PlanToListRequest(
    val start: String,
    val end: String,
    @SerialName("list_id") val listId: String? = null,
    val scale: Double = 1.0,
)

@Serializable
data class PlanToListResult(
    @SerialName("recipes_added") val recipesAdded: Int,
    @SerialName("items_on_list") val itemsOnList: Int,
    @SerialName("list_id") val listId: String,
)
