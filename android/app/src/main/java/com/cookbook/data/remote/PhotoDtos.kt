package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A recipe transcribed from a photo — nothing saved yet. The editor pre-fills from this and
 * the user reviews/edits before the normal create endpoint commits it. */
@Serializable
data class RecipePhotoDraftOut(
    val name: String = "Untitled recipe",
    val servings: Int? = null,
    @SerialName("prep_minutes") val prepMinutes: Int? = null,
    @SerialName("cook_minutes") val cookMinutes: Int? = null,
    val ingredients: List<PreviewIngredientOut> = emptyList(),
    val steps: List<String> = emptyList(),
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val note: String? = null,
)
