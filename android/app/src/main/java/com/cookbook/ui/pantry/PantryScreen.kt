package com.cookbook.ui.pantry

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.cookbook.data.remote.PantryItemOut
import com.cookbook.ui.recipe.CATEGORY_ORDER
import com.cookbook.ui.recipe.categoryLabel
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.ImageBytes
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.EmptyState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryScreen(
    onScanConfirm: () -> Unit,
    onSuggestions: () -> Unit,
    onEditStaples: () -> Unit,
    viewModel: PantryViewModel = hiltViewModel(),
) {
    val pantry by viewModel.pantry.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val staples by viewModel.staples.collectAsState()
    val savingStaples by viewModel.savingStaples.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var fabMenuOpen by remember { mutableStateOf(false) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PantryItemOut?>(null) }
    // Swiping the first-use staples sheet away skips it for this visit only — it returns
    // until confirmed (the confirmation lives on the server, not in local prefs).
    var staplesSheetDismissed by remember { mutableStateOf(false) }
    // The camera writes here; consumed when TakePicture reports success.
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // Refresh on resume so a confirm on the next screen shows up when we pop back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun scanBytes(bytes: ByteArray) {
        viewModel.scanPhoto(ImageBytes.downscaleToJpeg(bytes), "image/jpeg", "pantry.jpg")
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) scanBytes(bytes)
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = cameraUri
        if (!saved || uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) scanBytes(bytes)
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(
            context,
            "com.cookbook.fileprovider",
            File(dir, "pantry.jpg"),
        )
        cameraUri = uri
        takePicture.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            // Still usable: photos taken with the camera app arrive via the gallery picker.
            galleryPicker.launch("image/*")
        }
    }

    fun startCameraScan() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) { viewModel.scanDraftReady.collect { onScanConfirm() } }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pantry", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onSuggestions) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = "What can I make?",
                            tint = CookbookTheme.colors.heat.base,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!scanning) fabMenuOpen = true },
                containerColor = CookbookTheme.colors.heat.base,
                contentColor = CookbookTheme.colors.heat.on,
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = CookbookTheme.colors.heat.on,
                    )
                } else {
                    Icon(Icons.Outlined.Add, contentDescription = "Add to pantry")
                }
                DropdownMenu(expanded = fabMenuOpen, onDismissRequest = { fabMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Scan with camera") },
                        leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                        onClick = {
                            fabMenuOpen = false
                            startCameraScan()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Scan from gallery") },
                        leadingIcon = {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        },
                        onClick = {
                            fabMenuOpen = false
                            galleryPicker.launch("image/*")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add item") },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        onClick = {
                            fabMenuOpen = false
                            addDialogOpen = true
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when (val s = pantry) {
                UiState.Idle, is UiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = CookbookTheme.colors.heat.base) }
                is UiState.Error -> EmptyState(
                    icon = Icons.Outlined.Kitchen,
                    title = "Couldn't load your pantry",
                    subtitle = s.message,
                )
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Kitchen,
                            title = "What's in your kitchen?",
                            subtitle = "Snap a photo of your fridge or pantry shelves and " +
                                "Cookbook will work out what's there — then it can tell you " +
                                "what you can make.",
                        )
                    } else {
                        PantryList(
                            items = s.data,
                            onEdit = { editing = it },
                            onDelete = { viewModel.deleteItem(it.id) },
                        )
                    }
                }
            }
        }
    }

    if (addDialogOpen) {
        PantryItemDialog(
            title = "Add to pantry",
            confirmLabel = "Add",
            onDismiss = { addDialogOpen = false },
            onConfirm = { name, category ->
                addDialogOpen = false
                viewModel.addItem(name, category)
            },
        )
    }

    editing?.let { item ->
        PantryItemDialog(
            title = "Edit item",
            confirmLabel = "Save",
            initialName = item.name,
            initialCategory = item.category,
            onDismiss = { editing = null },
            onConfirm = { name, category ->
                editing = null
                viewModel.updateItem(item.id, name, category)
            },
        )
    }

    // One-time staples review: shown until the user confirms (server-tracked, not local).
    val staplesState = staples
    if (staplesState != null && !staplesState.confirmed && !staplesSheetDismissed) {
        StaplesFirstUseSheet(
            defaults = staplesState.staples,
            saving = savingStaples,
            onConfirm = viewModel::confirmStaples,
            onEditInstead = {
                staplesSheetDismissed = true
                onEditStaples()
            },
            onDismiss = { staplesSheetDismissed = true },
        )
    }
}

@Composable
private fun PantryList(
    items: List<PantryItemOut>,
    onEdit: (PantryItemOut) -> Unit,
    onDelete: (PantryItemOut) -> Unit,
) {
    val colors = CookbookTheme.colors
    val grouped = items.groupBy { it.category ?: "other" }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CATEGORY_ORDER.filter { grouped.containsKey(it) }.forEach { category ->
            item(key = "header_$category") {
                SectionHeader(
                    categoryLabel(category),
                    channel = colors.heat.base,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(grouped.getValue(category), key = { it.id }) { item ->
                PanelCard(Modifier.fillMaxWidth(), onClick = { onEdit(item) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            if (item.source == "scan") {
                                Caption("From a scan")
                            }
                        }
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove ${item.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) } // clear the FAB
    }
}

/** Name + category-chip dialog shared by add and edit. */
@Composable
internal fun PantryItemDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: String?) -> Unit,
    initialName: String = "",
    initialCategory: String? = null,
) {
    val colors = CookbookTheme.colors
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var category by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Item") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CATEGORY_ORDER, key = { it }) { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = if (category == option) null else option },
                            label = { Text(categoryLabel(option)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.heat.dim,
                                selectedLabelColor = colors.heat.base,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, category) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One-time review of the assumed-staples list. Swiping it away only skips this visit —
 * it returns until confirmed, so matching never silently assumes something the user
 * doesn't keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StaplesFirstUseSheet(
    defaults: List<String>,
    saving: Boolean,
    onConfirm: (List<String>) -> Unit,
    onEditInstead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = CookbookTheme.colors
    val checked = remember(defaults) { mutableStateOf(defaults.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 32.dp,
            ),
        ) {
            item {
                Text("Your staples", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "These are assumed to always be in your kitchen — recipes never count " +
                        "them as missing. Untick anything you don't keep; you can edit the " +
                        "list in Settings later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            items(defaults, key = { it }) { staple ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            checked.value = if (staple in checked.value) {
                                checked.value - staple
                            } else {
                                checked.value + staple
                            }
                        },
                ) {
                    Checkbox(
                        checked = staple in checked.value,
                        onCheckedChange = {
                            checked.value = if (it) checked.value + staple else checked.value - staple
                        },
                    )
                    Text(staple, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                PulseButton(
                    text = if (saving) "Saving…" else "Looks right",
                    onClick = { onConfirm(defaults.filter { it in checked.value }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onEditInstead) {
                        Text("Add my own in Settings", color = colors.info.base)
                    }
                }
            }
        }
    }
}
