package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One food candidate the vision model spotted in a fridge/pantry photo — draft data the
 * user reviews on the confirmation screen before anything is saved. */
@Serializable
data class PantryScanItem(
    val name: String,
    val category: String? = null,
    val confidence: String = "high",
)

@Serializable
data class PantryScanDraftOut(
    val items: List<PantryScanItem> = emptyList(),
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val note: String? = null,
)

@Serializable
data class PantryItemOut(
    val id: String,
    val name: String,
    val category: String? = null,
    val source: String = "manual",
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PantryItemCreateRequest(
    val name: String,
    val category: String? = null,
)

@Serializable
data class PantryItemUpdateRequest(
    val name: String? = null,
    val category: String? = null,
)

@Serializable
data class PantryConfirmRequest(
    val items: List<PantryItemCreateRequest>,
    val replace: Boolean = false,
)

/** `confirmed` false means the user is still on the seeded defaults and the client shows
 * the one-time review sheet. */
@Serializable
data class StaplesOut(
    val confirmed: Boolean,
    val staples: List<String>,
)

@Serializable
data class StaplesPutRequest(
    val staples: List<String>,
)

@Serializable
data class CookbookSuggestion(
    @SerialName("recipe_id") val recipeId: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val total: Int,
    val matched: Int,
    val missing: List<String> = emptyList(),
)

/** `sourceId` feeds the existing Discover preview/import endpoints. */
@Serializable
data class ExternalSuggestion(
    @SerialName("source_id") val sourceId: String,
    val title: String,
    val image: String? = null,
    @SerialName("used_count") val usedCount: Int,
    @SerialName("missed_count") val missedCount: Int,
    val missing: List<String> = emptyList(),
)

@Serializable
data class PantrySuggestionsOut(
    val cookbook: List<CookbookSuggestion> = emptyList(),
    val external: List<ExternalSuggestion> = emptyList(),
    @SerialName("external_available") val externalAvailable: Boolean = false,
)
