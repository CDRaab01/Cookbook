package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One aggregated amount ("2 tbsp"); null unit is a bare count ("3"). */
@Serializable
data class MeasureOut(
    val quantity: Double,
    val unit: String? = null,
)

@Serializable
data class ShoppingItemOut(
    val id: String,
    val name: String,
    // Legacy single measure (populated when the aggregate has exactly one entry).
    val quantity: Double? = null,
    val unit: String? = null,
    // The full aggregate across merges — what the row displays ("2 tbsp + 2 tsp").
    val measures: List<MeasureOut> = emptyList(),
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
