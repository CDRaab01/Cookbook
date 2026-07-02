package com.cookbook.util

import com.cookbook.data.remote.RecipePhotoDraftOut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a photo-import draft from the Discover screen to a freshly-opened recipe editor —
 * same idiom as [SharedIntentStore] for shared URLs. Never auto-saved: the editor pre-fills
 * from this and the user still has to review and tap Save.
 */
@Singleton
class RecipeDraftStore @Inject constructor() {
    private val _draft = MutableStateFlow<RecipePhotoDraftOut?>(null)
    val draft: StateFlow<RecipePhotoDraftOut?> = _draft

    fun offer(draft: RecipePhotoDraftOut) {
        _draft.value = draft
    }

    fun consume(): RecipePhotoDraftOut? {
        val value = _draft.value
        _draft.value = null
        return value
    }
}
