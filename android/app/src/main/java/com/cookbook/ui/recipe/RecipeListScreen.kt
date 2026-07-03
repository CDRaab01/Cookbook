package com.cookbook.ui.recipe

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.cookbook.R
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeListScreen(
    onRecipeClick: (String) -> Unit,
    onAddRecipe: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RecipeListViewModel = hiltViewModel(),
) {
    val state by viewModel.recipes.collectAsState()
    val query by viewModel.query.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    val colors = CookbookTheme.colors

    LaunchedEffect(Unit) { viewModel.load() }

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
                        Text("Recipes", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        RecipeSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (option == sort) "✓ ${option.label}" else option.label)
                                },
                                onClick = {
                                    viewModel.setSort(option)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = onAddRecipe) {
                        Icon(Icons.Outlined.Add, contentDescription = "New recipe")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            is UiState.Loading, UiState.Idle -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = colors.heat.base) }
            }
            is UiState.Error -> {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Couldn't load recipes",
                    subtitle = s.message,
                    modifier = Modifier.padding(padding),
                )
            }
            is UiState.Success -> {
                val filtered = viewModel.filtered(s.data)
                val tags = viewModel.availableTags(s.data)
                if (s.data.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "Your recipe book is empty",
                        subtitle = "Add a recipe with + or import one from Discover.",
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                label = { Text("Search recipes") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        if (tags.isNotEmpty() || s.data.any { it.favorite }) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item {
                                        FilterChip(
                                            selected = favoritesOnly,
                                            onClick = viewModel::toggleFavoritesOnly,
                                            label = { Text("Favorites") },
                                            leadingIcon = {
                                                Icon(
                                                    if (favoritesOnly) Icons.Filled.Favorite
                                                    else Icons.Outlined.FavoriteBorder,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = colors.heat.dim,
                                                selectedLabelColor = colors.heat.base,
                                                selectedLeadingIconColor = colors.heat.base,
                                            ),
                                        )
                                    }
                                    items(tags, key = { it }) { tag ->
                                        FilterChip(
                                            selected = selectedTag == tag,
                                            onClick = { viewModel.selectTag(tag) },
                                            label = { Text(tag) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = colors.plum.dim,
                                                selectedLabelColor = colors.plum.base,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        items(filtered, key = { it.id }) { recipe ->
                            RecipeCard(recipe = recipe, onClick = { onRecipeClick(recipe.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(recipe: RecipeSummaryOut, onClick: () -> Unit) {
    val colors = CookbookTheme.colors
    PanelCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            if (recipe.imageUrl != null) {
                AsyncImage(
                    model = recipe.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recipe.favorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = "Favorite",
                        tint = colors.heat.base,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (recipe.source == "imported") {
                    Caption("Imported", color = colors.info.base)
                } else if (recipe.source == "plate") {
                    Caption("From Plate", color = colors.plum.base)
                }
            }
            if (!recipe.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    recipe.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DataText(
                    "${recipe.ingredientCount}",
                    style = CookbookTheme.dataType.numeralLarge,
                    color = colors.heat.base,
                )
                Spacer(Modifier.width(4.dp))
                Caption("ingredients")
                Spacer(Modifier.width(16.dp))
                val totalMinutes = (recipe.prepMinutes ?: 0) + (recipe.cookMinutes ?: 0)
                if (totalMinutes > 0) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = colors.plum.base,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    DataText(
                        "$totalMinutes",
                        style = CookbookTheme.dataType.numeralLarge,
                        color = colors.plum.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption("min")
                    Spacer(Modifier.width(16.dp))
                }
                DataText(
                    "${recipe.servings}",
                    style = CookbookTheme.dataType.numeralLarge,
                    color = colors.info.base,
                )
                Spacer(Modifier.width(4.dp))
                Caption(if (recipe.servings == 1) "serving" else "servings")
                if (recipe.timesCooked > 0) {
                    Spacer(Modifier.width(16.dp))
                    DataText(
                        "${recipe.timesCooked}×",
                        style = CookbookTheme.dataType.numeralLarge,
                        color = colors.fresh.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption(
                        com.cookbook.util.relativeDays(recipe.lastCookedAt)
                            ?.let { "made · $it" } ?: "made",
                    )
                }
            }
        }
    }
}
