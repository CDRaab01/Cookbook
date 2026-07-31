package com.cookbook.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Store profiles (v0.11): a named store and the order you walk its aisles.
 *
 * The 13 canonical categories stay portable — an item keeps its category wherever you shop. A
 * store layers a floor plan on top: ordered aisles that each claim some categories, plus per-item
 * placements for the things this particular store files somewhere odd. See `util/StoreRouting.kt`.
 */
@Serializable
data class StoreAisleOut(
    val id: String,
    val order: Int = 0,
    val name: String,
    /** Canonical category keys this aisle collects. May be empty (a placement-only aisle). */
    val categories: List<String> = emptyList(),
)

/** "This item lives in that aisle, at this store" — keyed on the server's normalized name. */
@Serializable
data class StorePlacementOut(
    val id: String,
    @SerialName("aisle_id") val aisleId: String,
    val key: String,
    val name: String,
)

/** Store-picker projection — no aisles, so listing stays cheap. */
@Serializable
data class StoreOut(
    val id: String,
    val name: String,
    val label: String? = null,
    @SerialName("is_owner") val isOwner: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
) {
    /** "Meijer — Maysville Rd", or just the chain when there's no label. */
    val displayName: String
        get() = if (label.isNullOrBlank()) name else "$name — $label"
}

/** Everything needed to route a list: the walk order plus the learned exceptions. */
@Serializable
data class StoreDetailOut(
    val id: String,
    val name: String,
    val label: String? = null,
    @SerialName("is_owner") val isOwner: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
    val aisles: List<StoreAisleOut> = emptyList(),
    val placements: List<StorePlacementOut> = emptyList(),
) {
    val displayName: String
        get() = if (label.isNullOrBlank()) name else "$name — $label"
}

/**
 * One aisle on the way in. [id] present updates that row in place — which is what lets a reorder
 * or rename keep the placements learned by walking the store; omitting an aisle deletes it and
 * cascades its placements.
 */
@Serializable
data class AisleIn(
    val id: String? = null,
    val name: String,
    val categories: List<String> = emptyList(),
)

@Serializable
data class StoreCreateRequest(
    val name: String,
    val label: String? = null,
    /** Omitted seeds the canonical walk order server-side. */
    val aisles: List<AisleIn>? = null,
)

/** PATCH convention: null = untouched, "" = clear (label only; a store needs a name). */
@Serializable
data class StoreUpdateRequest(
    val name: String? = null,
    val label: String? = null,
)

@Serializable
data class AislesPutRequest(val aisles: List<AisleIn>)

@Serializable
data class PlacementRequest(
    val name: String,
    @SerialName("aisle_id") val aisleId: String,
)

@Serializable
data class SuggestLayoutRequest(val chain: String)

/**
 * A **draft** layout — nothing is saved. [lowConfidence] means the model couldn't be read (or
 * reached) and these are the standard aisles instead; the note says which.
 */
@Serializable
data class StoreLayoutDraftOut(
    val aisles: List<AisleIn> = emptyList(),
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val note: String? = null,
)

// --- "Organize list" (v0.11) ---

@Serializable
data class OrganizeSuggestion(
    @SerialName("item_id") val itemId: String,
    val name: String,
    @SerialName("current_category") val currentCategory: String? = null,
    @SerialName("suggested_category") val suggestedCategory: String,
)

/** A draft: nothing has been saved. Empty + [lowConfidence] = unreadable reply; empty without
 *  it = the list already looks right. The UI says different things about the two. */
@Serializable
data class OrganizeDraftOut(
    val suggestions: List<OrganizeSuggestion> = emptyList(),
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
    val note: String? = null,
)

@Serializable
data class OrganizeMove(
    @SerialName("item_id") val itemId: String,
    val category: String,
)

@Serializable
data class OrganizeApplyRequest(val moves: List<OrganizeMove>)
