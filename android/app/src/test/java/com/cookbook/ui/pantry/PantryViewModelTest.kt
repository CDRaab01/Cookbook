package com.cookbook.ui.pantry

import com.cookbook.data.remote.PantryItemOut
import com.cookbook.data.remote.PantryScanDraftOut
import com.cookbook.data.remote.PantryScanItem
import com.cookbook.data.remote.StaplesOut
import com.cookbook.data.repository.PantryRepository
import com.cookbook.util.PantryDraftStore
import com.cookbook.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PantryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: PantryRepository = mock()
    private val draftStore = PantryDraftStore()
    private lateinit var viewModel: PantryViewModel

    private val item = PantryItemOut(
        id = "1",
        name = "Cheddar cheese",
        category = "dairy",
        source = "scan",
        createdAt = "2026-07-03T00:00:00Z",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PantryViewModel(repository, draftStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load lands pantry and staples`() = runTest(dispatcher) {
        whenever(repository.getPantry()).thenReturn(listOf(item))
        whenever(repository.getStaples()).thenReturn(StaplesOut(confirmed = false, staples = listOf("salt")))

        viewModel.load()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.pantry.value
        assertIs<UiState.Success<List<PantryItemOut>>>(state)
        assertEquals(listOf(item), state.data)
        // Unconfirmed staples arrive so the screen can show the first-use sheet.
        val staples = assertNotNull(viewModel.staples.value)
        assertEquals(false, staples.confirmed)
    }

    @Test
    fun `scan success routes draft to the store and fires the one-shot`() = runTest(dispatcher) {
        val draft = PantryScanDraftOut(items = listOf(PantryScanItem(name = "Pasta")))
        whenever(repository.scanPhoto(any(), any(), any())).thenReturn(draft)

        var fired = false
        val collector = launch { viewModel.scanDraftReady.first().also { fired = true } }
        viewModel.scanPhoto(byteArrayOf(1), "image/jpeg", "pantry.jpg")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(fired)
        assertEquals(draft, draftStore.consume())
        collector.cancel()
    }

    @Test
    fun `scan 503 maps to the LM Studio copy`() = runTest(dispatcher) {
        whenever(repository.scanPhoto(any(), any(), any()))
            .doThrow(HttpException(Response.error<Any>(503, "".toResponseBody())))

        viewModel.scanPhoto(byteArrayOf(1), "image/jpeg", "pantry.jpg")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Couldn't reach LM Studio. Is it running?", viewModel.error.value)
    }

    @Test
    fun `confirmStaples persists and updates state`() = runTest(dispatcher) {
        whenever(repository.putStaples(any()))
            .thenReturn(StaplesOut(confirmed = true, staples = listOf("salt")))

        viewModel.confirmStaples(listOf("salt"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.staples.value?.confirmed)
    }

    @Test
    fun `delete failure surfaces an error`() = runTest(dispatcher) {
        whenever(repository.deleteItem(any())).doThrow(RuntimeException("boom"))

        viewModel.deleteItem("1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("boom", viewModel.error.value)
    }
}
