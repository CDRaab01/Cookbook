package com.cookbook.ui.pantry

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.cookbook.data.remote.CookbookSuggestion
import com.cookbook.data.remote.ExternalSuggestion
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

/** "What can I make?" — recipes coverable by the pantry, own cookbook first, web ideas after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantrySuggestionsScreen(
    onBack: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onImported: (String) -> Unit,
    viewModel: PantrySuggestionsViewModel = hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val colors = CookbookTheme.colors

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) { viewModel.imported.collect { onImported(it) } }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("What can I make?", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (val s = suggestions) {
                UiState.Idle, is UiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = colors.heat.base) }
                is UiState.Error -> EmptyState(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Couldn't check your pantry",
                    subtitle = s.message,
                )
                is UiState.Success -> {
                    val data = s.data
                    if (data.cookbook.isEmpty() && data.external.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.AutoAwesome,
                            title = "Nothing to suggest yet",
                            subtitle = "Scan or add what's in your kitchen first — then " +
                                "Cookbook can match it against your recipes.",
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (data.cookbook.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        "From your cookbook",
                                        channel = colors.heat.base,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        trailing = { Caption("${data.cookbook.size}") },
                                    )
                                }
                                items(data.cookbook, key = { it.recipeId }) { hit ->
                                    CookbookSuggestionRow(
                                        hit = hit,
                                        onClick = { onOpenRecipe(hit.recipeId) },
                                    )
                                }
                            }
                            if (data.external.isNotEmpty()) {
                                item {
                                    SectionHeader(
                                        "Ideas from the web",
                                        channel = colors.info.base,
                                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                        trailing = { Caption("${data.external.size}") },
                                    )
                                }
                                items(data.external, key = { it.sourceId }) { hit ->
                                    ExternalSuggestionRow(
                                        hit = hit,
                                        onClick = { viewModel.openPreview(hit.sourceId) },
                                    )
                                }
                            } else if (!data.externalAvailable) {
                                item {
                                    Caption(
                                        "Web ideas are off — add a Spoonacular key to the " +
                                            "server to fill this in.",
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }

    preview?.let { state ->
        ModalBottomSheet(onDismissRequest = viewModel::closePreview) {
            SuggestionPreviewSheet(
                state = state,
                importing = importing,
                onImport = viewModel::import,
            )
        }
    }
}

@Composable
private fun CookbookSuggestionRow(hit: CookbookSuggestion, onClick: () -> Unit) {
    val colors = CookbookTheme.colors
    PanelCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hit.imageUrl != null) {
                AsyncImage(
                    model = hit.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(hit.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DataText(
                        "${hit.matched}/${hit.total}",
                        style = CookbookTheme.dataType.numeral,
                        color = if (hit.missing.isEmpty()) colors.fresh.base else colors.heat.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption("ingredients")
                }
                if (hit.missing.isNotEmpty()) {
                    Caption("Missing: ${hit.missing.joinToString(", ")}")
                }
            }
        }
    }
}

@Composable
private fun ExternalSuggestionRow(hit: ExternalSuggestion, onClick: () -> Unit) {
    val colors = CookbookTheme.colors
    PanelCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hit.image != null) {
                AsyncImage(
                    model = hit.image,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DataText(
                        "${hit.usedCount}",
                        style = CookbookTheme.dataType.numeral,
                        color = colors.info.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption("of yours used")
                }
                if (hit.missing.isNotEmpty()) {
                    Caption("Needs: ${hit.missing.joinToString(", ")}")
                }
            }
        }
    }
}

@Composable
private fun SuggestionPreviewSheet(
    state: UiState<RecipePreviewOut>,
    importing: Boolean,
    onImport: (String) -> Unit,
) {
    val colors = CookbookTheme.colors
    when (state) {
        is UiState.Loading, UiState.Idle -> Column(
            Modifier.fillMaxWidth().height(200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator(color = colors.heat.base) }
        is UiState.Error -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
        is UiState.Success -> {
            val recipe = state.data
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recipe.image != null) {
                    item {
                        AsyncImage(
                            model = recipe.image,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                item { Text(recipe.title, style = MaterialTheme.typography.headlineSmall) }
                item {
                    PulseButton(
                        text = if (importing) "Importing…" else "Import this recipe",
                        onClick = { onImport(recipe.sourceId) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !importing,
                    )
                }
                item {
                    SectionHeader(
                        "Ingredients · ${recipe.ingredients.size}",
                        channel = colors.heat.base,
                    )
                }
                // Positional keys: scraped recipes can repeat an ingredient line verbatim.
                items(recipe.ingredients) { ing ->
                    Text(ing.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
