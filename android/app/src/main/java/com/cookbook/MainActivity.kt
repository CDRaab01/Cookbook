package com.cookbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cookbook.ui.navigation.CookbookNavHost
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.SharedIntentStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sharedIntentStore: SharedIntentStore

    // The static launcher shortcut (if any) that opened us. Updated on a warm re-launch too
    // (launchMode=singleTask delivers those via onNewIntent), so the nav can react. The nav host
    // holds it across the auth gate and honors it once the user reaches the signed-in graph.
    private var shortcutTarget by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        shortcutTarget = intent?.shortcutTarget()
        setContent {
            CookbookTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CookbookNavHost(
                        shortcutTarget = shortcutTarget,
                        onShortcutHandled = { shortcutTarget = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        shortcutTarget = intent.shortcutTarget()
    }

    /** A recipe page shared from the browser lands here; Discover's import flow picks it up. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            // Shared text may wrap the URL in prose ("Check this out: https://…").
            val url = Regex("""https?://\S+""").find(text)?.value
            sharedIntentStore.offer(url)
        }
    }
}

/** The `cookbook://shortcut/<target>` segment for a launcher-shortcut VIEW intent, else null. */
private fun Intent.shortcutTarget(): String? =
    if (action == Intent.ACTION_VIEW && data?.scheme == "cookbook") data?.lastPathSegment else null
