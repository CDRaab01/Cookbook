package com.cookbook.ui.pantry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.recipe.categoryLabel
import com.cookbook.ui.theme.CookbookTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton

/**
 * "I see these — anything to add?" Review of a pantry scan. Nothing persists until the
 * user taps Confirm (the RecipeDraftStore review idiom, applied to the pantry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryConfirmScreen(
    onBack: () -> Unit,
    onConfirmed: () -> Unit,
    viewModel: PantryConfirmViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val replace by viewModel.replace.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<DetectedItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.confirmed.collect { onConfirmed() } }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check the haul", style = MaterialTheme.typography.titleLarge) },
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
        if (items.isEmpty() && viewModel.scanNote != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.Kitchen,
                    title = "Nothing recognized",
                    subtitle = viewModel.scanNote + " You can still add items by hand below.",
                )
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    PulseButton(
                        text = "Add an item",
                        onClick = { adding = true },
                        modifier = Modifier.fillMaxWidth(),
                        tonal = true,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "I can see these — tap to fix a name, or add anything I missed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                items(items, key = { it.key }) { item ->
                    DetectedItemRow(
                        item = item,
                        onClick = { editing = item },
                        onRemove = { viewModel.removeItem(item.key) },
                    )
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { adding = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            tint = CookbookTheme.colors.heat.base,
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text("Add something else", color = CookbookTheme.colors.heat.base)
                    }
                }
                item {
                    PanelCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Replace current pantry",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Caption("Off = these merge into what's already there")
                            }
                            Switch(checked = replace, onCheckedChange = viewModel::setReplace)
                        }
                    }
                }
                item {
                    Caption(
                        "Staples like oil, salt and flour are always assumed — edit them in " +
                            "Settings.",
                    )
                }
                item {
                    PulseButton(
                        text = when {
                            saving -> "Saving…"
                            items.size == 1 -> "Add 1 item to my pantry"
                            else -> "Add ${items.size} items to my pantry"
                        },
                        onClick = viewModel::confirm,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = items.isNotEmpty() && !saving,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (adding) {
        PantryItemDialog(
            title = "Add an item",
            confirmLabel = "Add",
            onDismiss = { adding = false },
            onConfirm = { name, category ->
                adding = false
                viewModel.addItem(name, category)
            },
        )
    }

    editing?.let { item ->
        PantryItemDialog(
            title = "Fix this item",
            confirmLabel = "Save",
            initialName = item.name,
            initialCategory = item.category,
            onDismiss = { editing = null },
            onConfirm = { name, category ->
                editing = null
                viewModel.updateItem(
                    item.key,
                    name = name,
                    category = category,
                    clearCategory = category == null,
                )
            },
        )
    }
}

@Composable
private fun DetectedItemRow(
    item: DetectedItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    PanelCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                Row {
                    item.category?.let { Caption(categoryLabel(it)) }
                    if (item.lowConfidence) {
                        if (item.category != null) Spacer(Modifier.padding(horizontal = 4.dp))
                        Caption("Not sure about this one — tap to check")
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove ${item.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
