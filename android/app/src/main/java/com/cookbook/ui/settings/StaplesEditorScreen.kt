package com.cookbook.ui.settings

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
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.repository.PantryRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StaplesEditorViewModel @Inject constructor(
    private val pantryRepository: PantryRepository,
) : ViewModel() {

    private val _staples = MutableStateFlow<UiState<List<String>>>(UiState.Idle)
    val staples: StateFlow<UiState<List<String>>> = _staples

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    fun load() {
        viewModelScope.launch {
            _staples.value = UiState.Loading
            _staples.value = try {
                UiState.Success(pantryRepository.getStaples().staples)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load your staples")
            }
        }
    }

    fun add(name: String) {
        val trimmed = name.trim()
        val current = (_staples.value as? UiState.Success)?.data ?: return
        if (trimmed.isEmpty()) return
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return
        save(current + trimmed)
    }

    fun remove(name: String) {
        val current = (_staples.value as? UiState.Success)?.data ?: return
        save(current - name)
    }

    /** Every edit persists immediately — a staples list is too small to need a Save step. */
    private fun save(next: List<String>) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            try {
                _staples.value = UiState.Success(pantryRepository.putStaples(next).staples)
            } catch (e: Exception) {
                _status.value = e.message ?: "Couldn't save your staples"
            } finally {
                _saving.value = false
            }
        }
    }

    fun clearStatus() {
        _status.value = null
    }
}

/** Settings → Pantry staples: the always-assumed-available list, one row per staple. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaplesEditorScreen(
    onBack: () -> Unit,
    viewModel: StaplesEditorViewModel = hiltViewModel(),
) {
    val staples by viewModel.staples.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val status by viewModel.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pantry staples", style = MaterialTheme.typography.titleLarge) },
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
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "Staples are always assumed to be in your kitchen — recipe suggestions never " +
                    "count them as missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Add a staple") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PulseButton(
                    text = "Add",
                    onClick = {
                        viewModel.add(newName)
                        newName = ""
                    },
                    compact = true,
                    enabled = newName.isNotBlank() && !saving,
                )
            }
            Spacer(Modifier.height(12.dp))
            when (val s = staples) {
                is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is UiState.Success -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(s.data, key = { it }) { staple ->
                        PanelCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    staple,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { viewModel.remove(staple) },
                                    enabled = !saving,
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Remove $staple",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
                else -> {}
            }
        }
    }
}
