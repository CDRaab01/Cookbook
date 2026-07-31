package com.cookbook.util

import com.cookbook.data.remote.OrganizeDraftOut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands an "Organize list" draft from the shopping screen to the review screen — the same idiom as
 * [PantryDraftStore] and [StoreLayoutDraftStore].
 *
 * **Never auto-applied.** Unlike background classification, which only touches items nobody has
 * filed, this can propose moving something the user placed by hand — so every move is a checkbox
 * they tick before anything is written.
 */
@Singleton
class OrganizeDraftStore @Inject constructor() {
    private val _draft = MutableStateFlow<OrganizeDraftOut?>(null)
    val draft: StateFlow<OrganizeDraftOut?> = _draft

    fun offer(draft: OrganizeDraftOut) {
        _draft.value = draft
    }

    fun consume(): OrganizeDraftOut? {
        val value = _draft.value
        _draft.value = null
        return value
    }
}
