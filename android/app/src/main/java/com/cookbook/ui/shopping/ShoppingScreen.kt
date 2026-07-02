package com.cookbook.ui.shopping

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.ui.recipe.CATEGORY_ORDER
import com.cookbook.ui.recipe.categoryLabel
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
    val suggestions by viewModel.suggestions.collectAsState()
    val undoable by viewModel.undoable.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItemOut?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(undoable) {
        undoable?.let { item ->
            val result = snackbar.showSnackbar(
                message = "Removed ${item.name}",
                actionLabel = "Undo",
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.clearUndoable()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
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
                        onEdit = { editing = it },
                        onClearChecked = viewModel::clearChecked,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddItemDialog(
            suggestions = suggestions,
            onNameChanged = viewModel::onAddNameChanged,
            onAdd = { name, qty, unit, category ->
                viewModel.addItem(name, qty, unit, category)
                viewModel.clearSuggestions()
                showAdd = false
            },
            onDismiss = {
                viewModel.clearSuggestions()
                showAdd = false
            },
        )
    }

    editing?.let { item ->
        EditItemDialog(
            item = item,
            onSave = { name, qty, unit, category ->
                viewModel.editItem(item.id, name, qty, unit, category)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ShoppingListBody(
    items: List<ShoppingItemOut>,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (ShoppingItemOut) -> Unit,
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
                    onEdit = { onEdit(item) },
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
                    onEdit = { onEdit(item) },
                )
            }
        }
    }
}

/** Pretty-print one measure: "2 tbsp", "1.5 cups", "3". */
internal fun formatMeasure(quantity: Double, unit: String?): String {
    val q = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    if (unit == null) return q
    return "$q ${displayUnit(unit, quantity)}"
}

private val PLURAL_ES = setOf("box", "pinch", "dash", "bunch")
private val PLURALIZABLE = setOf(
    "cup", "can", "clove", "slice", "stick", "package", "head", "piece",
    "serving", "tub", "bottle", "jar", "bag",
) + PLURAL_ES

private fun displayUnit(unit: String, quantity: Double): String = when {
    quantity == 1.0 || unit !in PLURALIZABLE -> unit
    unit in PLURAL_ES -> unit + "es"
    else -> unit + "s"
}

/** The row's secondary line: the aggregate ("2 tbsp + 2 tsp") or the single measure. */
internal fun ShoppingItemOut.measuresLabel(): String? = when {
    measures.isNotEmpty() -> measures.joinToString(" + ") { formatMeasure(it.quantity, it.unit) }
    quantity != null -> formatMeasure(quantity!!, unit)
    else -> null
}

@Composable
private fun ShoppingItemRow(
    item: ShoppingItemOut,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
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
        // Name leads — it's a list of things to BUY; the recipe amounts are the caption.
        // The text area opens the editor; the checkbox and × keep their own targets.
        Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                ),
            )
            item.measuresLabel()?.let { label ->
                DataText(
                    label,
                    style = CookbookTheme.dataType.numeral,
                    color = if (item.checked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        colors.heat.base
                    },
                )
            }
        }
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

/** Shared field block for add + edit: name / qty / unit / category chips. */
@Composable
private fun ItemFields(
    name: String,
    onName: (String) -> Unit,
    quantity: String,
    onQuantity: (String) -> Unit,
    unit: String,
    onUnit: (String) -> Unit,
    category: String?,
    onCategory: (String?) -> Unit,
) {
    val colors = CookbookTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text("Item") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = onQuantity,
                label = { Text("Qty") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = unit,
                onValueChange = onUnit,
                label = { Text("Unit") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(CATEGORY_ORDER, key = { it }) { option ->
                FilterChip(
                    selected = category == option,
                    onClick = { onCategory(if (category == option) null else option) },
                    label = { Text(categoryLabel(option)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.fresh.dim,
                        selectedLabelColor = colors.fresh.base,
                    ),
                )
            }
        }
    }
}

@Composable
private fun AddItemDialog(
    suggestions: List<SuggestionOut>,
    onNameChanged: (String) -> Unit,
    onAdd: (name: String, quantity: Double?, unit: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(STAPLES, key = { it }) { staple ->
                        SuggestionChip(
                            onClick = {
                                name = staple
                                onNameChanged(staple)
                            },
                            label = { Text(staple) },
                        )
                    }
                }
                ItemFields(
                    name = name,
                    onName = {
                        name = it
                        onNameChanged(it)
                    },
                    quantity = quantity,
                    onQuantity = { quantity = it },
                    unit = unit,
                    onUnit = { unit = it },
                    category = category,
                    onCategory = { category = it },
                )
                if (suggestions.isNotEmpty()) {
                    Caption("From your history")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestions, key = { it.name }) { hit ->
                            SuggestionChip(
                                onClick = {
                                    name = hit.name
                                    unit = hit.unit.orEmpty()
                                    category = hit.category
                                },
                                label = { Text(hit.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        name.trim(),
                        quantity.toDoubleOrNull(),
                        unit.trim().lowercase().ifEmpty { null },
                        category,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditItemDialog(
    item: ShoppingItemOut,
    onSave: (name: String, quantity: Double?, unit: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var quantity by remember {
        mutableStateOf(
            item.quantity?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            }.orEmpty(),
        )
    }
    var unit by remember { mutableStateOf(item.unit.orEmpty()) }
    var category by remember { mutableStateOf(item.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.measures.size > 1) {
                    Text(
                        "Currently ${item.measuresLabel()} — saving replaces this with what you enter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ItemFields(
                    name = name,
                    onName = { name = it },
                    quantity = quantity,
                    onQuantity = { quantity = it },
                    unit = unit,
                    onUnit = { unit = it },
                    category = category,
                    onCategory = { category = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        quantity.toDoubleOrNull(),
                        unit.trim().lowercase().ifEmpty { null },
                        category,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
