package com.cookbook.ui.shopping

import com.cookbook.data.remote.ShoppingItemOut
import com.cookbook.data.remote.ShoppingListOut
import com.cookbook.data.repository.ShoppingRepository
import com.cookbook.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: ShoppingRepository = mock()
    private lateinit var viewModel: ShoppingViewModel

    private fun item(id: String, name: String, checked: Boolean = false) = ShoppingItemOut(
        id = id, name = name, checked = checked,
    )

    private fun list(vararg items: ShoppingItemOut) =
        ShoppingListOut(id = "list-1", name = "Groceries", items = items.toList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // The VM mirrors the repository's offline flag at construction; the mock must serve one.
        whenever(repository.offline).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        val prefs = org.mockito.kotlin.mock<com.cookbook.util.AppPreferences> {
            org.mockito.kotlin.whenever(it.aisleOrder)
                .thenReturn(kotlinx.coroutines.flow.flowOf(com.cookbook.util.DEFAULT_AISLE_ORDER))
        }
        viewModel = ShoppingViewModel(repository, mock(), prefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load surfaces the default list`() = runTest(dispatcher) {
        whenever(repository.getDefaultList()).thenReturn(list(item("1", "Milk")))

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.list.value
        assertIs<UiState.Success<ShoppingListOut>>(state)
        assertEquals("Milk", state.data.items.single().name)
    }

    @Test
    fun `toggle is optimistic and reconciles with the server`() = runTest(dispatcher) {
        whenever(repository.getDefaultList()).thenReturn(list(item("1", "Milk")))
        whenever(repository.setChecked(eq("list-1"), eq("1"), eq(true)))
            .thenReturn(list(item("1", "Milk", checked = true)))
        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleChecked("1", true)
        // Before the server responds, the flip is already visible.
        val optimistic = viewModel.list.value
        assertIs<UiState.Success<ShoppingListOut>>(optimistic)
        assertEquals(true, optimistic.data.items.single().checked)

        dispatcher.scheduler.advanceUntilIdle()
        val settled = viewModel.list.value
        assertIs<UiState.Success<ShoppingListOut>>(settled)
        assertEquals(true, settled.data.items.single().checked)
    }

    @Test
    fun `undo of a link item re-adds name plus url through the split path`() = runTest(dispatcher) {
        val linked = item("1", "milk collector").copy(linkUrl = "https://meijer.com/p/1")
        whenever(repository.getDefaultList()).thenReturn(list(linked))
        whenever(repository.deleteItem(eq("list-1"), eq("1"))).thenReturn(list())
        whenever(
            repository.addItem(
                eq("list-1"),
                any(),
                org.mockito.kotlin.anyOrNull(),
                org.mockito.kotlin.anyOrNull(),
                org.mockito.kotlin.anyOrNull(),
            ),
        ).thenReturn(list(linked))
        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteItem("1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.undoDelete()
        dispatcher.scheduler.advanceUntilIdle()

        // The re-add carries "name url" so the server's split restores the link.
        org.mockito.kotlin.verify(repository).addItem(
            eq("list-1"),
            eq("milk collector https://meijer.com/p/1"),
            org.mockito.kotlin.anyOrNull(),
            org.mockito.kotlin.anyOrNull(),
            org.mockito.kotlin.anyOrNull(),
        )
    }

    @Test
    fun `failed toggle rolls back and surfaces an error`() = runTest(dispatcher) {
        whenever(repository.getDefaultList()).thenReturn(list(item("1", "Milk")))
        whenever(repository.setChecked(any(), any(), any())).doThrow(RuntimeException("offline"))
        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleChecked("1", true)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.list.value
        assertIs<UiState.Success<ShoppingListOut>>(state)
        assertEquals(false, state.data.items.single().checked)
        assertNotNull(viewModel.error.value)
    }
}
