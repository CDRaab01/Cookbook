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
    // Product-page URL for a pasted-link item; the name is a clean human title.
    @SerialName("link_url") val linkUrl: String? = null,
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

/** List-picker projection: name + how much is left to buy on it. */
@Serializable
data class ListSummaryOut(
    val id: String,
    val name: String,
    @SerialName("unchecked_count") val uncheckedCount: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
    val shared: Boolean = false,
    @SerialName("is_owner") val isOwner: Boolean = true,
)

@Serializable
data class ListCreateRequest(val name: String)

@Serializable
data class ListRenameRequest(val name: String)

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
    // PATCH clearing convention: null = untouched, "" = clear the link.
    @SerialName("link_url") val linkUrl: String? = null,
)

/** One autocomplete hit for the add dialog, from the user's item history. */
@Serializable
data class SuggestionOut(
    val name: String,
    val unit: String? = null,
    val category: String? = null,
)

/** This month's grocery dollars spent, reported by Magpie (federated awareness Link D). */
@Serializable
data class GrocerySpendOut(
    val month: String,
    @SerialName("spent_dollars") val spentDollars: Int,
)

@Serializable
data class AddRecipeToListRequest(
    @SerialName("recipe_id") val recipeId: String,
    val scale: Double = 1.0,
    val force: Boolean = false,
)
