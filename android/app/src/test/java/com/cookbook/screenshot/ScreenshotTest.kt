package com.cookbook.screenshot

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.ui.home.HomeContent
import com.cookbook.ui.home.HomeData
import com.cookbook.ui.theme.CookbookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot tests (Robolectric native graphics + Roborazzi) — render Cookbook screens to PNGs
 * without a device or emulator. Run with `:app:testDebugUnitTest`; images land in `app/screenshots/`.
 * Record with `-Proborazzi.test.record=true`. Mirrors Plate's `ScreenshotTest` (the suite reference).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    // A small tolerance so sub-pixel AA / font-hinting noise across machines doesn't flag a diff.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.03f),
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            CookbookTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = roborazziOptions)
    }

    @Test fun home_light() = capture("home_light", dark = false) { HomeScene() }
    @Test fun home_dark() = capture("home_dark", dark = true) { HomeScene() }

    // Regression: a recipe plan entry used to crash SlotRow (Modifier.weight(0f)). Rendering one
    // proves it doesn't crash and lays out correctly, alongside a note and an empty slot.
    @Test fun plan_slots_light() = capture("plan_slots_light", dark = false) { PlanSlotsScene() }
    @Test fun plan_slots_dark() = capture("plan_slots_dark", dark = true) { PlanSlotsScene() }

    @Test fun share_sheet_light() = capture("share_sheet_light", dark = false) { ShareSheetScene() }
    @Test fun share_sheet_dark() = capture("share_sheet_dark", dark = true) { ShareSheetScene() }
}

@Composable
private fun ShareSheetScene() {
    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(24.dp)) {
        com.cookbook.ui.shopping.ShareSheetContent(
            isOwner = true,
            currentUserId = "u1",
            members = listOf(
                com.cookbook.data.remote.MemberOut(userId = "u1", email = "chris@dragonfly.org", name = "Chris", isOwner = true),
                com.cookbook.data.remote.MemberOut(userId = "u2", email = "sam@dragonfly.org", name = "Sam", isOwner = false),
            ),
            onShare = {},
            onRemove = {},
        )
    }
}

@Composable
private fun PlanSlotsScene() {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        com.cookbook.ui.plan.SlotRow(
            slot = "dinner",
            entry = com.cookbook.data.remote.PlanEntryOut(
                id = "1", date = "2026-07-14", slot = "dinner",
                recipeId = "r1", recipeName = "Sheet-Pan Chicken & Veg",
            ),
            onTap = {}, onRemove = {}, onOpenRecipe = {},
        )
        com.cookbook.ui.plan.SlotRow(
            slot = "lunch",
            entry = com.cookbook.data.remote.PlanEntryOut(
                id = "2", date = "2026-07-14", slot = "lunch", note = "Leftovers", eaten = true,
            ),
            onTap = {}, onRemove = {}, onOpenRecipe = {}, onSetEaten = { _, _ -> },
        )
        // An eaten recipe entry shows the per-user portion picker ("2×").
        com.cookbook.ui.plan.SlotRow(
            slot = "dinner",
            entry = com.cookbook.data.remote.PlanEntryOut(
                id = "3", date = "2026-07-14", slot = "dinner",
                recipeId = "r2", recipeName = "Beef Chili", eaten = true, servings = 2.0,
            ),
            onTap = {}, onRemove = {}, onOpenRecipe = {}, onSetEaten = { _, _ -> },
        )
        com.cookbook.ui.plan.SlotRow(
            slot = "breakfast", entry = null,
            onTap = {}, onRemove = {}, onOpenRecipe = {},
        )
    }
}

private fun recipe(id: String, name: String, ingredients: Int, prep: Int, cook: Int) = RecipeSummaryOut(
    id = id,
    name = name,
    servings = 4,
    prepMinutes = prep,
    cookMinutes = cook,
    ingredientCount = ingredients,
)

@Composable
internal fun HomeScene() {
    HomeContent(
        // Real app passes the bare time-greeting; HomeContent appends the user name itself.
        greeting = "Good morning",
        data = HomeData(
            userName = "Casey",
            recipeCount = 42,
            recentRecipes = listOf(
                recipe("1", "Chicken Parmesan", ingredients = 9, prep = 20, cook = 25),
                recipe("2", "Overnight Oats", ingredients = 5, prep = 10, cook = 0),
                recipe("3", "Sheet-Pan Salmon & Veg", ingredients = 7, prep = 15, cook = 20),
            ),
            uncheckedItems = 12,
            plannedThisWeek = 5,
        ),
        onOpenRecipe = {},
        onGoToRecipes = {},
        onGoToShopping = {},
        onGoToPlan = {},
    )
}
