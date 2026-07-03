package com.cookbook.util

import com.cookbook.data.remote.PantryScanDraftOut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a pantry-scan draft from the Pantry screen to the confirmation screen — same idiom
 * as [RecipeDraftStore] for photo-import drafts. Never auto-saved: the user reviews, edits,
 * and taps Confirm before POST /pantry/confirm writes anything.
 */
@Singleton
class PantryDraftStore @Inject constructor() {
    private val _draft = MutableStateFlow<PantryScanDraftOut?>(null)
    val draft: StateFlow<PantryScanDraftOut?> = _draft

    fun offer(draft: PantryScanDraftOut) {
        _draft.value = draft
    }

    fun consume(): PantryScanDraftOut? {
        val value = _draft.value
        _draft.value = null
        return value
    }
}
