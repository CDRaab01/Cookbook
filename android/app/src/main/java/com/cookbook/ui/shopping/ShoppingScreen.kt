package com.cookbook.ui.shopping

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.ui.recipe.CATEGORY_ORDER
import com.cookbook.ui.recipe.categoryLabel
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
fun ShoppingScreen(viewModel: ShoppingViewModel = hiltViewModel()) {
    val state by viewModel.list.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add item")
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
        when (val s = state) {
            is UiState.Loading, UiState.Idle -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = CookbookTheme.colors.heat.base) }
            }
            is UiState.Error -> {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Couldn't load the list",
                    subtitle = s.message,
                    modifier = Modifier.padding(padding),
                )
            }
            is UiState.Success -> {
                val items = s.data.items
                if (items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.ShoppingCart,
                        title = "Nothing on the list",
                        subtitle = "Add items with +, or open a recipe and tap \"Add to shopping list\".",
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    ShoppingListBody(
                        items = items,
                        onToggle = viewModel::toggleChecked,
                        onDelete = viewModel::deleteItem,
                        onClearChecked = viewModel::clearChecked,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddItemSheet(
            onAdd = { name, qty, unit ->
                viewModel.addItem(name, qty, unit)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun ShoppingListBody(
    items: List<ShoppingItemOut>,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClearChecked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CookbookTheme.colors
    val (checked, unchecked) = items.partition { it.checked }
    val grouped = unchecked.groupBy { it.category ?: "other" }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                DataText(
                    "${unchecked.size}",
                    style = CookbookTheme.dataType.dataMedium,
                    color = colors.heat.base,
                )
                Spacer(Modifier.width(6.dp))
                Caption("to buy")
                Spacer(Modifier.weight(1f))
                if (checked.isNotEmpty()) {
                    DataText(
                        "${checked.size}",
                        style = CookbookTheme.dataType.dataSmall,
                        color = colors.fresh.base,
                    )
                    Spacer(Modifier.width(6.dp))
                    Caption("in cart")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        CATEGORY_ORDER.filter { grouped.containsKey(it) }.forEach { category ->
            item(key = "header_$category") {
                SectionHeader(
                    categoryLabel(category),
                    channel = colors.heat.base,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(grouped.getValue(category), key = { it.id }) { item ->
                ShoppingItemRow(
                    item = item,
                    onToggle = { onToggle(item.id, it) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }

        if (checked.isNotEmpty()) {
            item(key = "header_checked") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader("In the cart", channel = colors.fresh.base)
                    Spacer(Modifier.weight(1f))
                    PulseButton(
                        text = "Clear checked",
                        onClick = onClearChecked,
                        tonal = true,
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = null,
                                Modifier.width(18.dp),
                            )
                        },
                    )
                }
            }
            items(checked, key = { it.id }) { item ->
                ShoppingItemRow(
                    item = item,
                    onToggle = { onToggle(item.id, it) },
                    onDelete = { onDelete(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItemOut,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CookbookTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.checked) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.fresh.base,
                checkmarkColor = colors.fresh.on,
            ),
        )
        val qty = formatQuantity(item.quantity, item.unit)
        if (qty != null) {
            DataText(
                qty,
                style = CookbookTheme.dataType.numeral,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else colors.heat.base,
                modifier = Modifier.width(72.dp),
            )
        } else {
            Spacer(Modifier.width(72.dp))
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove ${item.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One-tap staples for the add dialog — the things that go on every list. */
private val STAPLES = listOf("Milk", "Eggs", "Bread", "Butter", "Bananas", "Coffee", "Paper towels")

@Composable
private fun AddItemSheet(
    onAdd: (name: String, quantity: Double?, unit: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(STAPLES, key = { it }) { staple ->
                        androidx.compose.material3.SuggestionChip(
                            onClick = { name = staple },
                            label = { Text(staple) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("Qty") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onAdd(
                        name.trim(),
                        quantity.toDoubleOrNull(),
                        unit.trim().lowercase().ifEmpty { null },
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
