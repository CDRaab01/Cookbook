package com.cookbook.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cookbook.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
        private val AISLE_ORDER = stringPreferencesKey("pref_aisle_order")
    }

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
}
