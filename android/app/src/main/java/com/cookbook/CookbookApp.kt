package com.cookbook

import android.app.Application
import com.cookbook.util.NetworkSyncObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CookbookApp : Application() {

    @Inject lateinit var networkSyncObserver: NetworkSyncObserver

    override fun onCreate() {
        super.onCreate()
        // Push any offline-queued shopping-list writes as soon as connectivity returns.
        networkSyncObserver.register()
    }
}
