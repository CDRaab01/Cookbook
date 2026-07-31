package com.cookbook.util

import com.cookbook.data.remote.AisleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A model-proposed store layout, on its way from the Stores screen to the aisle editor. */
data class StoreLayoutDraft(
    val chain: String,
    val label: String?,
    val aisles: List<AisleIn>,
    val lowConfidence: Boolean,
    val note: String?,
)

/**
 * Hands a suggested layout from the Stores screen to the aisle editor — the same idiom as
 * [PantryDraftStore] and [RecipeDraftStore]. **Never auto-saved:** the user reorders, renames and
 * taps Save, and only then does the store exist. The model has world knowledge of a chain, not the
 * floor plan of the branch you shop, so edit-before-save is the expected path rather than a
 * failure.
 */
@Singleton
class StoreLayoutDraftStore @Inject constructor() {
    private val _draft = MutableStateFlow<StoreLayoutDraft?>(null)
    val draft: StateFlow<StoreLayoutDraft?> = _draft

    fun offer(draft: StoreLayoutDraft) {
        _draft.value = draft
    }

    fun consume(): StoreLayoutDraft? {
        val value = _draft.value
        _draft.value = null
        return value
    }
}
