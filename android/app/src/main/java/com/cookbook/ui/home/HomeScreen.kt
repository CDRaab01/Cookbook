package com.cookbook.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.cookbook.R
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onGoToRecipes: () -> Unit,
    onGoToShopping: () -> Unit,
    onGoToPlan: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val greeting by viewModel.greeting.collectAsState()

    // Refresh on resume so the counts stay current after adding a recipe / list item elsewhere.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(36.dp),
                        )
                        Text("Cookbook", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is UiState.Error -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Couldn't load",
                    subtitle = s.message,
                )
                is UiState.Success -> HomeContent(
                    greeting = greeting,
                    data = s.data,
                    onOpenRecipe = onOpenRecipe,
                    onGoToRecipes = onGoToRecipes,
                    onGoToShopping = onGoToShopping,
                    onGoToPlan = onGoToPlan,
                )
                else -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Loading your kitchen…",
                )
            }
        }
    }
}

@Composable
internal fun HomeContent(
    greeting: String,
    data: HomeData,
    onOpenRecipe: (String) -> Unit,
    onGoToRecipes: () -> Unit,
    onGoToShopping: () -> Unit,
    onGoToPlan: () -> Unit,
) {
    val colors = CookbookTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Greeting on the heat gradient (dark on-color text for contrast on amber).
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.heroGradient)
                .padding(20.dp),
        ) {
            Column {
                Text(
                    greeting + (data.userName?.let { ", $it" } ?: ""),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.heat.on,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "What are you cooking today?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.heat.on.copy(alpha = 0.85f),
                )
            }
        }

        // Quick counts — dense metric tiles: each rolls its count up on load, carries a channel
        // icon, and taps through to its tab with the panel's own press-scale.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountTile(
                label = "Recipes",
                count = data.recipeCount,
                channel = colors.heat.base,
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                onClick = onGoToRecipes,
                modifier = Modifier.weight(1f),
            )
            CountTile(
                label = "To buy",
                count = data.uncheckedItems,
                channel = colors.fresh.base,
                icon = Icons.Outlined.ShoppingCart,
                onClick = onGoToShopping,
                modifier = Modifier.weight(1f),
            )
            CountTile(
                label = "Planned",
                count = data.plannedThisWeek,
                channel = colors.info.base,
                icon = Icons.Outlined.CalendarMonth,
                onClick = onGoToPlan,
                modifier = Modifier.weight(1f),
            )
        }

        // Recent recipes.
        if (data.recentRecipes.isEmpty()) {
            PanelCard(Modifier.fillMaxWidth(), onClick = onGoToRecipes) {
                Column {
                    Text("No recipes yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Head to Recipes to add your first one or import from Discover.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            SectionHeader(
                "Recent recipes",
                modifier = Modifier.fillMaxWidth(),
                channel = colors.heat.base,
                trailing = { TextButton(onClick = onGoToRecipes) { Text("See all") } },
            )
            data.recentRecipes.forEach { recipe ->
                RecentRecipeRow(recipe = recipe, onClick = { onOpenRecipe(recipe.id) })
            }
        }
    }
}

@Composable
private fun CountTile(
    label: String,
    count: Int,
    channel: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StatTile(
        label = label,
        modifier = modifier,
        channel = channel,
        dense = true,
        animatedValue = count,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = channel,
                modifier = Modifier.size(16.dp),
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun RecentRecipeRow(recipe: RecipeSummaryOut, onClick: () -> Unit) {
    PanelCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            recipe.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val mins = (recipe.prepMinutes ?: 0) + (recipe.cookMinutes ?: 0)
                val meta = buildString {
                    append(recipe.ingredientCount)
                    append(if (recipe.ingredientCount == 1) " ingredient" else " ingredients")
                    if (mins > 0) append(" · $mins min")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
