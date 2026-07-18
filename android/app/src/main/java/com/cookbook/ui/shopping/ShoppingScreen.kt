package com.cookbook.ui.shopping

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import design.pulse.ui.components.StaleBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    viewModel: ShoppingViewModel = hiltViewModel(),
    // Set when reached via the "Add item" launcher shortcut: open the quick-add sheet on arrival.
    openAddItem: Boolean = false,
    onAddItemConsumed: () -> Unit = {},
) {
    val state by viewModel.list.collectAsState()
    val offline by viewModel.offline.collectAsState()
    val error by viewModel.error.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val undoable by viewModel.undoable.collectAsState()
    val allLists by viewModel.allLists.collectAsState()
    val grocerySpend by viewModel.grocerySpend.collectAsState()
    val aisleOrder by viewModel.aisleOrder.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ShoppingItemOut?>(null) }
    var listMenuOpen by remember { mutableStateOf(false) }
    var namingList by remember { mutableStateOf<String?>(null) } // "new" or "rename"
    var confirmDeleteList by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    // Honor the "Add item" launcher shortcut once we've landed here.
    LaunchedEffect(openAddItem) {
        if (openAddItem) {
            showAdd = true
            onAddItemConsumed()
        }
    }
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
                title = {
                    // The list switcher: current list name + dropdown of all lists.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { listMenuOpen = true },
                    ) {
                        Text(
                            (state as? UiState.Success)?.data?.name ?: "Shopping",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Icon(
                            Icons.Outlined.ArrowDropDown,
                            contentDescription = "Switch list",
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = listMenuOpen,
                        onDismissRequest = { listMenuOpen = false },
                    ) {
                        allLists.forEach { entry ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        if (entry.uncheckedCount > 0) {
                                            "${entry.name}  ·  ${entry.uncheckedCount} to buy"
                                        } else {
                                            entry.name
                                        },
                                    )
                                },
                                onClick = {
                                    listMenuOpen = false
                                    viewModel.switchList(entry.id)
                                },
                            )
                        }
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("New list…") },
                            onClick = {
                                listMenuOpen = false
                                namingList = "new"
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Rename this list…") },
                            onClick = {
                                listMenuOpen = false
                                namingList = "rename"
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Delete this list…", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                listMenuOpen = false
                                confirmDeleteList = true
                            },
                        )
                    }
                },
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
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (offline) {
                        // The mirror + local queue are authoritative offline, so this is a
                        // "will sync" notice, not an "as of" stamp (asOfMs is unused).
                        StaleBanner(
                            asOfMs = 0L,
                            channel = CookbookTheme.colors.heat.base,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            prefix = "Offline",
                            formatAsOf = { "changes will sync" },
                        )
                    }
                    if (items.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.ShoppingCart,
                            title = "Nothing on the list",
                            subtitle = "Add items with +, or open a recipe and tap \"Add to shopping list\".",
                        )
                    } else {
                        ShoppingListBody(
                            items = items,
                            grocerySpend = grocerySpend,
                            onToggle = viewModel::toggleChecked,
                            onDelete = viewModel::deleteItem,
                            onEdit = { editing = it },
                            onClearChecked = viewModel::clearChecked,
                            aisleOrder = aisleOrder,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddItemSheet(
            suggestions = suggestions,
            onQueryChanged = viewModel::onAddNameChanged,
            onQuickAdd = { name, unit, category -> viewModel.addItem(name, null, unit, category) },
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

    namingList?.let { mode ->
        var name by remember {
            mutableStateOf(
                if (mode == "rename") (state as? UiState.Success)?.data?.name.orEmpty() else "",
            )
        }
        AlertDialog(
            onDismissRequest = { namingList = null },
            title = { Text(if (mode == "new") "New list" else "Rename list") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("List name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (mode == "new") viewModel.createList(name) else viewModel.renameCurrentList(name)
                        namingList = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text(if (mode == "new") "Create" else "Rename") }
            },
            dismissButton = {
                TextButton(onClick = { namingList = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteList) {
        val current = (state as? UiState.Success)?.data
        AlertDialog(
            onDismissRequest = { confirmDeleteList = false },
            title = { Text("Delete \"${current?.name}\"?") },
            text = { Text("Everything on it goes too. The default list recreates itself if this was the last one.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteList = false
                    viewModel.deleteCurrentList()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteList = false }) { Text("Cancel") }
            },
        )
    }

}

@Composable
internal fun ShoppingListBody(
    items: List<ShoppingItemOut>,
    grocerySpend: com.cookbook.data.remote.GrocerySpendOut?,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (ShoppingItemOut) -> Unit,
    onClearChecked: () -> Unit,
    aisleOrder: List<String> = CATEGORY_ORDER,
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
        grocerySpend?.let { spend ->
            item(key = "grocery_spend") {
                GrocerySpendTile(spend)
                Spacer(Modifier.height(4.dp))
            }
        }
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

        aisleOrder.filter { grouped.containsKey(it) }.forEach { category ->
            item(key = "header_$category") {
                SectionHeader(
                    categoryLabel(category),
                    channel = colors.heat.base,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    trailing = { Caption("${grouped.getValue(category).size}") },
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
                SectionHeader(
                    "In the cart",
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                    channel = colors.fresh.base,
                    trailing = {
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
                    },
                )
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

/**
 * Federated-awareness Link D: the household's grocery spend so far this month, reported by Magpie.
 * A quiet, read-only tile — source-labeled ("via Magpie") per CROSS-APP.md rule 7, and only shown
 * when Magpie answered (the ViewModel leaves it null otherwise).
 */
@Composable
private fun GrocerySpendTile(spend: com.cookbook.data.remote.GrocerySpendOut) {
    val colors = CookbookTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DataText(
            "$${spend.spentDollars}",
            style = CookbookTheme.dataType.dataMedium,
            color = colors.fresh.base,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Caption("on groceries this month")
            Caption("via Magpie", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Small category glyphs for the quick-add suggestion rows (Family Wall-style aisle hint). */
private fun categoryEmoji(category: String?): String = when (category) {
    "produce" -> "🥦" // 🥦
    "meat" -> "🥩" // 🥩
    "dairy" -> "🥛" // 🥛
    "bakery" -> "🍞" // 🍞
    "frozen" -> "🧊" // 🧊
    "pantry" -> "🥫" // 🥫
    else -> "🛒" // 🛒
}

/**
 * Quick-add, Family Wall-style: one search field, live results from your item history as you
 * type, tap a row to add it instantly and keep going — no quantity/unit/category fields up
 * front (set those later via tap-to-edit on the row). Stays open across adds so a whole
 * mental list can be typed out in one sitting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemSheet(
    suggestions: List<SuggestionOut>,
    onQueryChanged: (String) -> Unit,
    onQuickAdd: (name: String, unit: String?, category: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CookbookTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun addAndContinue(name: String, unit: String? = null, category: String? = null) {
        onQuickAdd(name.trim(), unit, category)
        query = ""
        onQueryChanged("")
        requester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add items", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onQueryChanged(it)
                    },
                    modifier = Modifier.weight(1f).focusRequester(requester),
                    placeholder = { Text("Milk, eggs, paper towels…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (query.isNotBlank()) addAndContinue(query) },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (query.isNotBlank()) addAndContinue(query) },
                    enabled = query.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.heat.base,
                        contentColor = colors.heat.on,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add")
                }
            }
            Spacer(Modifier.height(4.dp))
            if (suggestions.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                ) {
                    items(suggestions, key = { it.name }) { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { addAndContinue(hit.name, hit.unit, hit.category) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(categoryEmoji(hit.category), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                hit.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Caption(categoryLabel(hit.category))
                        }
                    }
                }
            } else if (query.length >= 2) {
                Spacer(Modifier.height(12.dp))
                Caption("No matches in your history — tap + to add it fresh")
            }
        }
    }

    LaunchedEffect(Unit) {
        requester.requestFocus()
        keyboard?.show()
    }
}

/** Shared field block for the edit dialog: name / qty / unit / category chips. */
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
