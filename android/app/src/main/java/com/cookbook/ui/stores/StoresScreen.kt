package com.cookbook.ui.stores

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton

/**
 * Manage the stores the household shops. Adding one offers two routes to a floor plan: start from
 * the standard aisles, or let the local model propose the chain's typical walk order as a draft to
 * edit. Either way nothing is saved until the editor's Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoresScreen(
    onBack: () -> Unit,
    onEditStore: (String) -> Unit,
    onEditDraft: () -> Unit,
    viewModel: StoresViewModel = hiltViewModel(),
) {
    val state by viewModel.stores.collectAsState()
    val error by viewModel.error.collectAsState()
    val suggesting by viewModel.suggesting.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    // A suggestion arriving means the user asked for one — hand straight to the editor.
    LaunchedEffect(draft) { if (draft != null) onEditDraft() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stores") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "A store is the order you actually walk its aisles. Pick one on the shopping list " +
                    "and it regroups into that store's aisles instead of plain categories.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (val s = state) {
                is UiState.Idle, is UiState.Loading ->
                    CircularProgressIndicator(Modifier.padding(24.dp))
                is UiState.Error -> Text(
                    s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                is UiState.Success -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (s.data.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.Storefront,
                                title = "No stores yet",
                                subtitle = "Add the store you shop most and arrange its aisles.",
                            )
                        }
                    }
                    items(s.data, key = { it.id }) { store ->
                        PanelCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(store.name, style = MaterialTheme.typography.bodyLarge)
                                    store.label?.takeIf { it.isNotBlank() }?.let { Caption(it) }
                                }
                                PulseButton(
                                    text = "Aisles",
                                    onClick = { onEditStore(store.id) },
                                    tonal = true,
                                    compact = true,
                                )
                                // Deleting someone else's store isn't yours to do; the server
                                // enforces it too, this just doesn't offer a button that 404s.
                                if (store.isOwner) {
                                    IconButton(onClick = { confirmDelete = store.id }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete ${store.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        PulseButton(
                            text = "Add a store",
                            onClick = { adding = true },
                            channel = CookbookTheme.colors.heat.base,
                            onChannel = CookbookTheme.colors.heat.on,
                            dimChannel = CookbookTheme.colors.heat.dim,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AddStoreDialog(
            suggesting = suggesting,
            onDismiss = { if (!suggesting) adding = false },
            onStartFromDefaults = { name, label ->
                adding = false
                viewModel.createStore(name, label) { onEditStore(it) }
            },
            onSuggest = { name, label -> viewModel.suggestLayout(name, label) },
        )
    }

    confirmDelete?.let { storeId ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this store?") },
            text = {
                Text(
                    "Its aisle order and everything it learned about where items live here will " +
                        "be gone. Your shopping list and its categories are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.deleteStore(storeId)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddStoreDialog(
    suggesting: Boolean,
    onDismiss: () -> Unit,
    onStartFromDefaults: (String, String?) -> Unit,
    onSuggest: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a store") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Store") },
                    placeholder = { Text("Meijer") },
                    singleLine = true,
                    enabled = !suggesting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Which one (optional)") },
                    placeholder = { Text("Maysville Rd") },
                    singleLine = true,
                    enabled = !suggesting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggesting) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(12.dp))
                        // ~10s on the local model, so say why the wait is happening.
                        Caption("Asking the local model what this store looks like…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSuggest(name, label.ifBlank { null }) },
                enabled = name.isNotBlank() && !suggesting,
            ) { Text("Suggest layout") }
        },
        dismissButton = {
            TextButton(
                onClick = { onStartFromDefaults(name, label.ifBlank { null }) },
                enabled = name.isNotBlank() && !suggesting,
            ) { Text("Start from defaults") }
        },
    )
}
