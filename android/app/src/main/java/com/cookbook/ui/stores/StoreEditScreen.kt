package com.cookbook.ui.stores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.cookbook.util.categoryLabel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton

/**
 * Arrange a store's aisles: reorder to match the walk, rename to match the signs, and say which
 * categories each aisle collects.
 *
 * Reached either from an existing store or from a model-suggested draft. In the draft case the
 * store does not exist yet — **nothing is saved until Save**, which is the house rule for anything
 * the model produced.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoreEditScreen(
    storeId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: StoreEditViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val title by viewModel.title.collectAsState()
    val note by viewModel.note.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var addingAisle by remember { mutableStateOf(false) }
    var assigningTo by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(storeId) { viewModel.open(storeId) }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "Aisles" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save(onSaved) }, enabled = !saving) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Put these in the order you walk the store, and rename them to match the signs. " +
                    "Each aisle collects the item categories you assign to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows, key = { i, row -> row.id ?: "new-$i-${row.name}" }) { index, row ->
                    PanelCard(Modifier.fillMaxWidth()) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}.",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = row.name,
                                    onValueChange = { viewModel.rename(index, it) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { viewModel.move(index, -1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                                }
                                IconButton(
                                    onClick = { viewModel.move(index, 1) },
                                    enabled = index < rows.size - 1,
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                                }
                                IconButton(onClick = { viewModel.removeAisle(index) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Remove ${row.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.categories.forEach { category ->
                                    AssistChip(
                                        onClick = { viewModel.unassignCategory(index, category) },
                                        label = { Text(categoryLabel(category)) },
                                    )
                                }
                                AssistChip(
                                    onClick = { assigningTo = index },
                                    label = { Text("+ category") },
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseButton(
                            text = "Add an aisle",
                            onClick = { addingAisle = true },
                            tonal = true,
                            compact = true,
                        )
                        PulseButton(
                            text = "Reset to standard",
                            onClick = { viewModel.resetToDefaults() },
                            tonal = true,
                            compact = true,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // Nothing is unroutable — but say so rather than leaving it to be discovered
                    // as a mystery "Unsorted" pile in the store.
                    val unclaimed = unclaimedCategories(rows)
                    if (unclaimed.isNotEmpty()) {
                        Text(
                            "Not in any aisle yet: " + unclaimed.joinToString { categoryLabel(it) } +
                                ". Items in these land under \"Unsorted\" at the end of the list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PulseButton(
                        text = if (saving) "Saving…" else "Save",
                        onClick = { viewModel.save(onSaved) },
                        channel = CookbookTheme.colors.heat.base,
                        onChannel = CookbookTheme.colors.heat.on,
                        dimChannel = CookbookTheme.colors.heat.dim,
                    )
                }
            }
        }
    }

    if (addingAisle) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingAisle = false },
            title = { Text("Add an aisle") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Aisle 12 — Baking") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addAisle(name)
                        addingAisle = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingAisle = false }) { Text("Cancel") } },
        )
    }

    assigningTo?.let { index ->
        val row = rows.getOrNull(index)
        AlertDialog(
            onDismissRequest = { assigningTo = null },
            title = { Text("Categories in ${row?.name.orEmpty()}") },
            text = {
                Column {
                    Text(
                        "A category belongs to one aisle — assigning it here takes it off whichever " +
                            "aisle had it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.cookbook.util.DEFAULT_AISLE_ORDER.forEach { category ->
                            val selected = row?.categories?.contains(category) == true
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (selected) {
                                        viewModel.unassignCategory(index, category)
                                    } else {
                                        viewModel.assignCategory(index, category)
                                    }
                                },
                                label = { Text(categoryLabel(category)) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigningTo = null }) { Text("Done") } },
        )
    }
}
