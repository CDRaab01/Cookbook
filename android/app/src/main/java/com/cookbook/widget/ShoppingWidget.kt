package com.cookbook.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.cookbook.MainActivity
import com.cookbook.data.local.db.ShoppingDao
import com.cookbook.data.repository.ShoppingRepositoryImpl
import com.cookbook.util.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull

/** Hilt can't inject Glance objects directly; the widget pulls what it needs via an EntryPoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun shoppingDao(): ShoppingDao
    fun shoppingRepository(): ShoppingRepositoryImpl
    fun appPreferences(): AppPreferences
}

private fun entryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingWidget()
}

/** Toggle an item done from the home screen, then refresh every widget instance. */
class ToggleItemAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val itemId = parameters[KEY_ITEM_ID] ?: return
        val checked = parameters[KEY_CHECKED] ?: return
        val entry = entryPoint(context)
        val listId = entry.appPreferences().shoppingListId.firstOrNull() ?: return
        runCatching { entry.shoppingRepository().setChecked(listId, itemId, checked) }
        ShoppingWidget().updateAll(context)
    }

    companion object {
        val KEY_ITEM_ID = ActionParameters.Key<String>("itemId")
        val KEY_CHECKED = ActionParameters.Key<Boolean>("checked")
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        // Pull fresh state (reconciles Room), then redraw.
        runCatching { entryPoint(context).shoppingRepository().getDefaultList() }
        ShoppingWidget().updateAll(context)
    }
}

/** The home-screen checklist: unchecked items of the active list, tap to check off. Reads the
 * Room mirror, so it shows the same truth as the app — offline included. */
class ShoppingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = entryPoint(context)
        val listId = entry.appPreferences().shoppingListId.firstOrNull()
        val items = entry.shoppingDao()
            .visibleItems(listId)
            .filter { !it.checked }
            .take(12)

        provideContent {
            GlanceTheme {
                WidgetBody(names = items.map { it.localId to it.name }, toBuy = items.size)
            }
        }
    }
}

// PULSE-adjacent colors, hardcoded: Glance can't consume the Compose theme objects.
private val InkBg = Color(0xFF13161B)
private val HeatOrange = Color(0xFFFF8A5C)
private val TextPrimary = Color(0xFFE7EAF0)
private val TextDim = Color(0xFF9AA3B2)

@Composable
private fun WidgetBody(names: List<Pair<String, String>>, toBuy: Int) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(InkBg))
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Shopping",
                style = TextStyle(
                    color = ColorProvider(HeatOrange),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                if (toBuy == 0) "all set" else "$toBuy to buy",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 13.sp),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "↻",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 16.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (names.isEmpty()) {
            Text(
                "Nothing on the list — tap to open Cookbook.",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            names.forEach { (itemId, name) ->
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(
                            actionRunCallback<ToggleItemAction>(
                                actionParametersOf(
                                    ToggleItemAction.KEY_ITEM_ID to itemId,
                                    ToggleItemAction.KEY_CHECKED to true,
                                ),
                            ),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Empty checkbox reads as "tap to check off" (Glance can't host a real
                    // Compose Checkbox); tapping still checks the item and drops it from the list.
                    Text(
                        "☐",
                        style = TextStyle(color = ColorProvider(HeatOrange), fontSize = 16.sp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        name,
                        style = TextStyle(color = ColorProvider(TextPrimary), fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
