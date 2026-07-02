package com.cookbook.ui.discover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.cookbook.data.remote.DiscoveredRecipe
import com.cookbook.data.remote.RecipePreviewOut
import com.cookbook.ui.recipe.formatQuantity
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onImported: (String) -> Unit,
    onOpenPhotoDraft: () -> Unit = {},
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val urlDraft by viewModel.urlDraft.collectAsState()
    val importingUrl by viewModel.importingUrl.collectAsState()
    val importingPhoto by viewModel.importingPhoto.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (bytes != null) viewModel.importPhoto(bytes, mimeType, "recipe.jpg")
    }

    LaunchedEffect(Unit) { viewModel.imported.collect { onImported(it) } }
    LaunchedEffect(Unit) { viewModel.photoDraftReady.collect { onOpenPhotoDraft() } }
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
                actions = {
                    IconButton(
                        onClick = { photoPicker.launch("image/*") },
                        enabled = !importingPhoto,
                    ) {
                        if (importingPhoto) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = CookbookTheme.colors.heat.base,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.CameraAlt,
                                contentDescription = "Import from a photo",
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.openUrlDialog() }) {
                        Icon(Icons.Outlined.Link, contentDescription = "Import from a link")
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
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search real recipes") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    IconButton(onClick = viewModel::search) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (val s = results) {
                UiState.Idle -> EmptyState(
                    icon = Icons.Outlined.TravelExplore,
                    title = "Find something to cook",
                    subtitle = "Search real recipes, paste a link, or snap a photo of a recipe " +
                        "card with the icons up top — you can also Share a recipe page from " +
                        "your browser straight to Cookbook.",
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
                                    onClick = { viewModel.openPreview(hit.sourceId) },
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }

    preview?.let { state ->
        ModalBottomSheet(onDismissRequest = viewModel::closePreview) {
            PreviewSheetBody(
                state = state,
                importingId = importing,
                onImport = viewModel::import,
            )
        }
    }

    urlDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::closeUrlDialog,
            title = { Text("Import from a link") },
            text = {
                Column {
                    Text(
                        "Paste a recipe page's URL — Cookbook reads the recipe out of it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = viewModel::setUrlDraft,
                        label = { Text("https://…") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::importFromUrl,
                    enabled = draft.isNotBlank() && !importingUrl,
                ) { Text(if (importingUrl) "Importing…" else "Import") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeUrlDialog) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DiscoverCard(hit: DiscoveredRecipe, onClick: () -> Unit) {
    val colors = CookbookTheme.colors
    design.pulse.ui.components.PanelCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hit.image != null) {
                AsyncImage(
                    model = hit.image,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(hit.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
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
                            style = CookbookTheme.dataType.numeral,
                            color = colors.plum.base,
                        )
                        Spacer(Modifier.width(4.dp))
                        Caption("min")
                        Spacer(Modifier.width(14.dp))
                    }
                    hit.servings?.let {
                        DataText(
                            "$it",
                            style = CookbookTheme.dataType.numeral,
                            color = colors.info.base,
                        )
                        Spacer(Modifier.width(4.dp))
                        Caption(if (it == 1) "serving" else "servings")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSheetBody(
    state: UiState<RecipePreviewOut>,
    importingId: String?,
    onImport: (String) -> Unit,
) {
    val colors = CookbookTheme.colors
    when (state) {
        is UiState.Loading, UiState.Idle -> {
            Column(
                Modifier.fillMaxWidth().height(200.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(color = colors.heat.base) }
        }
        is UiState.Error -> {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(24.dp))
            }
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
                item {
                    Column {
                        Text(recipe.title, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            recipe.servings?.let {
                                DataText(
                                    "$it",
                                    style = CookbookTheme.dataType.dataSmall,
                                    color = colors.info.base,
                                )
                                Spacer(Modifier.width(4.dp))
                                Caption("servings")
                                Spacer(Modifier.width(16.dp))
                            }
                            recipe.readyInMinutes?.let {
                                DataText(
                                    "$it",
                                    style = CookbookTheme.dataType.dataSmall,
                                    color = colors.plum.base,
                                )
                                Spacer(Modifier.width(4.dp))
                                Caption("min")
                            }
                        }
                    }
                }
                item {
                    PulseButton(
                        text = if (importingId == recipe.sourceId) "Importing…" else "Import this recipe",
                        onClick = { onImport(recipe.sourceId) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = importingId == null,
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
                    Row {
                        val qty = formatQuantity(ing.quantity, ing.unit)
                        if (qty != null) {
                            DataText(
                                qty,
                                style = CookbookTheme.dataType.numeral,
                                color = colors.heat.base,
                                modifier = Modifier.width(72.dp),
                            )
                        } else {
                            Spacer(Modifier.width(72.dp))
                        }
                        Text(ing.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (recipe.steps.isNotEmpty()) {
                    item { SectionHeader("Steps · ${recipe.steps.size}", channel = colors.plum.base) }
                    items(recipe.steps.take(4)) { step ->
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (recipe.steps.size > 4) {
                        item {
                            Caption("+ ${recipe.steps.size - 4} more steps after import")
                        }
                    }
                }
            }
        }
    }
}
