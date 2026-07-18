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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.SuggestionOut
import com.cookbook.ui.recipe.CATEGORY_ORDER
import com.cookbook.ui.recipe.categoryLabel
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.LinkText
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
    // Set when the share chooser routed a browser-shared URL here: added as a link item.
    sharedAddText: String? = null,
    onSharedAddConsumed: () -> Unit = {},
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
    // The persistent add bar's text lives here (hoisted) so the + button and the "Add item"
    // launcher shortcut can focus it, and it survives while the list reloads underneath.
    var addQuery by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val onAddQueryChange: (String) -> Unit = { q ->
        addQuery = q
        viewModel.onAddNameChanged(q)
    }
    // Jump straight into the docked add bar. runCatching: the bar isn't composed until the list
    // loads (Success), so a tap during the brief Loading state is a no-op rather than a crash.
    fun focusAddBar() {
        runCatching { addFocus.requestFocus() }
        keyboard?.show()
    }
    var editing by remember { mutableStateOf<ShoppingItemOut?>(null) }
    var listMenuOpen by remember { mutableStateOf(false) }
    var namingList by remember { mutableStateOf<String?>(null) } // "new" or "rename"
    var confirmDeleteList by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    // Honor the "Add item" launcher shortcut once we've landed here.
    LaunchedEffect(openAddItem) {
        if (openAddItem) {
            focusAddBar()
            onAddItemConsumed()
        }
    }
    // A browser-shared URL routed here by the chooser: add it through the normal path (the
    // server splits it into a titled link item) once the list is loaded to receive it.
    LaunchedEffect(sharedAddText, state) {
        if (sharedAddText != null && state is UiState.Success) {
            viewModel.addItem(sharedAddText, null, null, null)
            onSharedAddConsumed()
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
                    IconButton(onClick = { focusAddBar() }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add item")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Persistent "Add an item" bar, docked above the tab bar and lifting over the
            // keyboard. Only shown once a list exists (adds target the current list).
            if (state is UiState.Success) {
                AddItemBar(
                    query = addQuery,
                    suggestions = suggestions,
                    onQueryChanged = onAddQueryChange,
                    onAdd = { name, unit, category -> viewModel.addItem(name, null, unit, category) },
                    focusRequester = addFocus,
                )
            }
        },
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
                            subtitle = "Type below to add items, or open a recipe and tap \"Add to shopping list\".",
                        )
                    } else {
                        ShoppingListBody(
                            items = items,
                            grocerySpend = grocerySpend,
                            onToggle = viewModel::toggleChecked,
                            onDelete = viewModel::deleteItem,
                            onEdit = { editing = it },
                            onQuantityChange = viewModel::setLinkItemQuantity,
                            onClearChecked = viewModel::clearChecked,
                            aisleOrder = aisleOrder,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    editing?.let { item ->
        EditItemDialog(
            item = item,
            onSave = { name, qty, unit, category, clearLink ->
                viewModel.editItem(item.id, name, qty, unit, category, clearLink)
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
    onQuantityChange: (ShoppingItemOut, Int) -> Unit,
    onClearChecked: () -> Unit,
    aisleOrder: List<String> = CATEGORY_ORDER,
    modifier: Modifier = Modifier,
) {
    val colors = CookbookTheme.colors
    val (checked, unchecked) = items.partition { it.checked }
    // Null OR unknown categories both land in "Other" — an item must never be counted in
    // "to buy" yet render under no section (reconcileAisleOrder guarantees "other" exists).
    val knownCategories = aisleOrder.toSet()
    val grouped = unchecked.groupBy { it.category?.takeIf { c -> c in knownCategories } ?: "other" }

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
                    onQuantityChange = { onQuantityChange(item, it) },
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
                    onQuantityChange = { onQuantityChange(item, it) },
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
    onQuantityChange: (Int) -> Unit,
) {
    val colors = CookbookTheme.colors
    val isLink = item.linkUrl != null
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
        // A link item's product thumbnail leads the row so you recognize it on the shelf.
        item.imageUrl?.let { image ->
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        // Name leads — it's a list of things to BUY; the recipe amounts are the caption.
        // The text area opens the editor; the checkbox and × keep their own targets.
        Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                ),
                // Safety net: an over-long name (e.g. a pasted URL on an old server) must not
                // become a wall of wrapped text.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Recipe measures caption — link items use the count stepper instead, so skip it.
            if (!isLink) {
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
            item.linkUrl?.let { link ->
                // The product link a pasted URL was split into: its own tap target (opens the
                // browser) inside the row's edit-tap area — the inner clickable wins.
                val uriHandler = LocalUriHandler.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { runCatching { uriHandler.openUri(link) } },
                ) {
                    Icon(
                        Icons.Outlined.Link,
                        contentDescription = "Open product page",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Caption(
                        LinkText.displayHost(link),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // "×N" count stepper for a link item (a distinct product you buy some number of).
        if (isLink) {
            QuantityStepper(
                count = item.quantity?.toInt()?.coerceAtLeast(1) ?: 1,
                onChange = onQuantityChange,
            )
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

/** Compact −/× N/＋ count control for link items. Floors at 1 (removing is the × button). */
@Composable
private fun QuantityStepper(count: Int, onChange: (Int) -> Unit) {
    val colors = CookbookTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { if (count > 1) onChange(count - 1) },
            enabled = count > 1,
            modifier = Modifier.size(32.dp),
        ) { Icon(Icons.Outlined.Remove, contentDescription = "Fewer", Modifier.width(18.dp)) }
        DataText(
            "×$count",
            style = CookbookTheme.dataType.numeral,
            color = colors.heat.base,
        )
        IconButton(onClick = { onChange(count + 1) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "More", Modifier.width(18.dp))
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
 * The persistent "Add an item" bar docked at the bottom of the shopping list. Type an item and
 * send it (the keyboard action, the button, or a tap on a history suggestion) — it's added and
 * the field clears but keeps focus, so a whole mental list rattles off without reopening anything.
 * History suggestions (substring + fuzzy, from the server) appear just above the field as you
 * type; no quantity/unit/category up front — set those later via tap-to-edit on the row.
 */
@Composable
private fun AddItemBar(
    query: String,
    suggestions: List<SuggestionOut>,
    onQueryChanged: (String) -> Unit,
    onAdd: (name: String, unit: String?, category: String?) -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = CookbookTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit(name: String, unit: String? = null, category: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        onAdd(trimmed, unit, category)
        onQueryChanged("") // clears the field and, via onAddNameChanged(""), the suggestion list
        focusRequester.requestFocus() // keep the field hot for the next item
        keyboard?.show()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (query.isNotBlank() && suggestions.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(suggestions, key = { it.name }) { hit ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { submit(hit.name, hit.unit, hit.category) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                androidx.compose.material3.HorizontalDivider()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text("Add an item") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit(query) }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { submit(query) },
                    enabled = query.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.heat.base,
                        contentColor = colors.heat.on,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add item")
                }
            }
        }
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
    onSave: (name: String, quantity: Double?, unit: String?, category: String?, clearLink: Boolean) -> Unit,
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
    var removeLink by remember { mutableStateOf(false) }

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
                // View/remove only — links arrive by pasting a URL into the add bar.
                if (item.linkUrl != null && !removeLink) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Caption(
                            LinkText.displayHost(item.linkUrl),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { removeLink = true }) { Text("Remove link") }
                    }
                }
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
                        removeLink,
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
