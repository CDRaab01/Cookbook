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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.util.AppPreferences
import com.cookbook.util.DEFAULT_AISLE_ORDER
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AisleOrderViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {
    val order: StateFlow<List<String>> = prefs.aisleOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_AISLE_ORDER)

    /** Move the aisle at [index] by [delta] positions (bounds-checked), and persist. */
    fun move(index: Int, delta: Int) {
        val current = order.value.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        current.add(target, current.removeAt(index))
        viewModelScope.launch { prefs.setAisleOrder(current) }
    }

    fun reset() = viewModelScope.launch { prefs.setAisleOrder(DEFAULT_AISLE_ORDER) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AisleOrderScreen(onBack: () -> Unit, viewModel: AisleOrderViewModel = hiltViewModel()) {
    val order by viewModel.order.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aisle order") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Reorder the aisles to match how you walk your store — the shopping list groups items in this order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(order, key = { _, c -> c }) { index, category ->
                    PanelCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                category.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.move(index, -1) },
                                enabled = index > 0,
                            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up") }
                            IconButton(
                                onClick = { viewModel.move(index, 1) },
                                enabled = index < order.size - 1,
                            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down") }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    PulseButton(text = "Reset to default", onClick = { viewModel.reset() }, tonal = true)
                }
            }
        }
    }
}
