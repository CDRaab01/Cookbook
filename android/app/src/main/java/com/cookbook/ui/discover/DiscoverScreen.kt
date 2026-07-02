package com.cookbook.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onImported: (String) -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }

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
                title = { Text("Discover", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search real recipes") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = viewModel::search) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (val s = results) {
                UiState.Idle -> EmptyState(
                    icon = Icons.Outlined.TravelExplore,
                    title = "Find something to cook",
                    subtitle = "Search real recipes and import one straight into your book.",
                )
                is UiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = CookbookTheme.colors.heat.base) }
                is UiState.Error -> EmptyState(
                    icon = Icons.Outlined.TravelExplore,
                    title = "Search didn't work",
                    subtitle = s.message,
                )
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.TravelExplore,
                            title = "No recipes found",
                            subtitle = "Try a different search.",
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(s.data, key = { it.sourceId }) { hit ->
                                DiscoverCard(
                                    hit = hit,
                                    importing = importing == hit.sourceId,
                                    anyImporting = importing != null,
                                    onImport = { viewModel.import(hit.sourceId) },
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    hit: DiscoveredRecipe,
    importing: Boolean,
    anyImporting: Boolean,
    onImport: () -> Unit,
) {
    val colors = CookbookTheme.colors
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(hit.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                hit.readyInMinutes?.let {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = colors.plum.base,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    DataText(
                        "$it",
                        style = CookbookTheme.dataType.numeralLarge,
                        color = colors.plum.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption("min")
                    Spacer(Modifier.width(16.dp))
                }
                hit.servings?.let {
                    DataText(
                        "$it",
                        style = CookbookTheme.dataType.numeralLarge,
                        color = colors.info.base,
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption(if (it == 1) "serving" else "servings")
                }
                Spacer(Modifier.weight(1f))
                PulseButton(
                    text = if (importing) "Importing…" else "Import",
                    onClick = onImport,
                    tonal = true,
                    compact = true,
                    enabled = !anyImporting,
                )
            }
        }
    }
}
