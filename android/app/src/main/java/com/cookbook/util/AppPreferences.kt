package com.cookbook.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cookbook.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * General app preferences (server URL, future settings), DataStore-backed so they survive process
 * death. Kept separate from [com.cookbook.data.local.TokenStore]'s auth store. Mirrors
 * Spotter/Plate's `AppPreferences`.
 */
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "cookbook_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val SERVER_URL = stringPreferencesKey("pref_server_url")
        private val SHOPPING_LIST_ID = stringPreferencesKey("pref_shopping_list_id")
        private val PINNED_LIST_ID = stringPreferencesKey("pref_pinned_list_id")
        private val AISLE_ORDER = stringPreferencesKey("pref_aisle_order")
        private val SELECTED_STORE_ID = stringPreferencesKey("pref_selected_store_id")
        private val SHARE_ALL_NUDGE_DISMISSED = booleanPreferencesKey("pref_share_all_nudge_dismissed")
    }

    /**
     * Which store the list is currently being routed for; null = plain category grouping (v0.11).
     *
     * Per-device on purpose, like [pinnedListId]: this is presentation state about where *you* are
     * standing right now. Two household members shopping different stores at the same time each
     * need their own selection, so it must not be server state even though the stores themselves
     * are shared.
     */
    val selectedStoreId: Flow<String?> = context.prefsDataStore.data.map { prefs ->
        prefs[SELECTED_STORE_ID]?.takeIf { it.isNotBlank() }
    }

    suspend fun setSelectedStoreId(value: String?) {
        context.prefsDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(SELECTED_STORE_ID) else prefs[SELECTED_STORE_ID] = value
        }
    }

    // Flipped the first time we seed the active list from the pinned default this process, so a
    // within-session list switch isn't clobbered by the pin on every screen re-entry.
    @Volatile
    private var pinSeeded = false

    /** The user's store-walk aisle order (reconciled against the canonical set); default order when unset. */
    val aisleOrder: Flow<List<String>> = context.prefsDataStore.data.map { prefs ->
        val saved = prefs[AISLE_ORDER]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        reconcileAisleOrder(saved ?: emptyList())
    }

    suspend fun setAisleOrder(order: List<String>) {
        context.prefsDataStore.edit { it[AISLE_ORDER] = order.joinToString(",") }
    }

    /** Base URL of the Cookbook server. Defaults to the build-time value when unset. */
    val serverUrl: Flow<String> = context.prefsDataStore.data.map { prefs ->
        prefs[SERVER_URL]?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL
    }

    suspend fun setServerUrl(value: String) {
        context.prefsDataStore.edit { it[SERVER_URL] = value }
    }

    /** The server id of the default shopping list, cached so offline mutations know their list. */
    val shoppingListId: Flow<String?> = context.prefsDataStore.data.map { prefs ->
        prefs[SHOPPING_LIST_ID]?.takeIf { it.isNotBlank() }
    }

    suspend fun setShoppingListId(value: String) {
        context.prefsDataStore.edit { it[SHOPPING_LIST_ID] = value }
    }

    /**
     * The user's pinned default list — what both the Shopping and Plan tabs open to (e.g. the
     * list shared with a partner), set via "Set as default". Null when the user hasn't pinned one,
     * in which case the tabs fall back to their prior behavior (last-opened / server default).
     */
    val pinnedListId: Flow<String?> = context.prefsDataStore.data.map { prefs ->
        prefs[PINNED_LIST_ID]?.takeIf { it.isNotBlank() }
    }

    suspend fun setPinnedList(value: String) {
        context.prefsDataStore.edit { it[PINNED_LIST_ID] = value }
    }

    /**
     * Whether the user dismissed the recipe-list "your recipes are still private" prompt. A plain
     * one-way flag on purpose: dismissing means *don't ask again*, not "ask again next time I add
     * a recipe". The action itself never disappears — it stays in Settings → Family.
     */
    val shareAllNudgeDismissed: Flow<Boolean> = context.prefsDataStore.data.map { prefs ->
        prefs[SHARE_ALL_NUDGE_DISMISSED] ?: false
    }

    suspend fun dismissShareAllNudge() {
        context.prefsDataStore.edit { it[SHARE_ALL_NUDGE_DISMISSED] = true }
    }

    /**
     * Once per process launch, make the pinned default the active list so the app opens to it.
     * Guarded so it runs only on the first list load of a launch — afterwards a user's in-session
     * list switch governs, and the pin re-applies on the next cold start. No-op when nothing is
     * pinned (the existing last-opened default stands).
     */
    suspend fun seedActiveListFromPin() {
        if (pinSeeded) return
        pinSeeded = true
        pinnedListId.firstOrNull()?.let { pinned ->
            context.prefsDataStore.edit { it[SHOPPING_LIST_ID] = pinned }
        }
    }
}
