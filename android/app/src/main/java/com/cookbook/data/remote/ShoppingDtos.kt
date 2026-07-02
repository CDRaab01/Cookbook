package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShoppingItemOut(
    val id: String,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String? = null,
    val checked: Boolean = false,
    @SerialName("checked_at") val checkedAt: String? = null,
    @SerialName("recipe_id") val recipeId: String? = null,
    val order: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ShoppingListOut(
    val id: String,
    val name: String,
    val items: List<ShoppingItemOut> = emptyList(),
)

@Serializable
data class ShoppingItemCreateRequest(
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String? = null,
)

@Serializable
data class ShoppingItemUpdateRequest(
    val name: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val category: String? = null,
    val checked: Boolean? = null,
)

/** One autocomplete hit for the add dialog, from the user's item history. */
@Serializable
data class SuggestionOut(
    val name: String,
    val unit: String? = null,
    val category: String? = null,
)

@Serializable
data class AddRecipeToListRequest(
    @SerialName("recipe_id") val recipeId: String,
    val scale: Double = 1.0,
    val force: Boolean = false,
)
