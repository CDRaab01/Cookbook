package com.cookbook.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Redraws the home-screen widget after in-app list mutations, so it never shows a stale
 * checklist next to a fresh app. Cheap no-op when no widget is placed. */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh() {
        scope.launch {
            runCatching { ShoppingWidget().updateAll(context) }
        }
    }
}
