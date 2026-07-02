package com.cookbook.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val shoppingRepository: ShoppingRepository,
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<ShoppingListOut>>(UiState.Loading)
    val list: StateFlow<UiState<ShoppingListOut>> = _list

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun load() {
        viewModelScope.launch {
            if (_list.value !is UiState.Success) _list.value = UiState.Loading
            _list.value = try {
                UiState.Success(shoppingRepository.getDefaultList())
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the list")
            }
        }
    }

    fun toggleChecked(itemId: String, checked: Boolean) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        // Optimistic flip so the in-store tap feels instant; reconciled with the server response
        // (or rolled back on failure). Full offline queueing lands in Phase 4.
        _list.value = UiState.Success(
            current.copy(
                items = current.items.map {
                    if (it.id == itemId) it.copy(checked = checked) else it
                },
            ),
        )
        viewModelScope.launch {
            try {
                _list.value =
                    UiState.Success(shoppingRepository.setChecked(current.id, itemId, checked))
            } catch (e: Exception) {
                _list.value = UiState.Success(current) // roll back
                _error.value = e.message ?: "Couldn't update the item"
            }
        }
    }

    fun addItem(name: String, quantity: Double?, unit: String?) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(
                    shoppingRepository.addItem(current.id, name, quantity, unit),
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't add the item"
            }
        }
    }

    fun deleteItem(itemId: String) {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.deleteItem(current.id, itemId))
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't delete the item"
            }
        }
    }

    fun clearChecked() {
        val current = (_list.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            try {
                _list.value = UiState.Success(shoppingRepository.clearChecked(current.id))
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't clear checked items"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
