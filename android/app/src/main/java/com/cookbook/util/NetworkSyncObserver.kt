package com.cookbook.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.cookbook.data.repository.ShoppingRepositoryImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the offline shopping-list backlog as soon as connectivity returns (CLAUDE.md §7
 * Phase 4). Registered once from [com.cookbook.CookbookApp]; mirrors Spotter/Plate's
 * `NetworkSyncObserver`.
 */
@Singleton
class NetworkSyncObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shoppingRepository: ShoppingRepositoryImpl,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register() {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        runCatching { shoppingRepository.syncPending() }
                    }
                }
            },
        )
    }
}
