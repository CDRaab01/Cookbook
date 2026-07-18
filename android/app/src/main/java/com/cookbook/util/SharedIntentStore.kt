package com.cookbook.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a URL shared from another app (browser → share sheet → Cookbook) into the app.
 * MainActivity writes it from the incoming ACTION_SEND intent; the nav host's share chooser
 * ("Import as recipe" / "Add to shopping list") consumes it — except when Discover is already
 * open, whose ViewModel still consumes directly into the pre-filled import dialog.
 */
@Singleton
class SharedIntentStore @Inject constructor() {
    private val _sharedUrl = MutableStateFlow<String?>(null)
    val sharedUrl: StateFlow<String?> = _sharedUrl

    fun offer(url: String?) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            _sharedUrl.value = trimmed
        }
    }

    fun consume(): String? {
        val value = _sharedUrl.value
        _sharedUrl.value = null
        return value
    }
}
