package com.cookbook.ui.auth

import com.cookbook.data.repository.AuthRepository
import com.cookbook.util.AuthEventBus
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository: AuthRepository = mock()
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = AuthViewModel(repository, AuthEventBus())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login success lands in Success`() = runTest(dispatcher) {
        viewModel.login("a@b.com", "password1")
        dispatcher.scheduler.advanceUntilIdle()

        verify(repository).login("a@b.com", "password1")
        assertIs<UiState.Success<Unit>>(viewModel.authState.value)
    }

    @Test
    fun `login failure surfaces the error message`() = runTest(dispatcher) {
        whenever(repository.login(any(), any())).doThrow(RuntimeException("boom"))

        viewModel.login("a@b.com", "wrong")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.authState.value
        assertIs<UiState.Error>(state)
        assertEquals("boom", state.message)
    }

    @Test
    fun `register success lands in Success`() = runTest(dispatcher) {
        viewModel.register("Sonic", "a@b.com", "password1")
        dispatcher.scheduler.advanceUntilIdle()

        verify(repository).register("Sonic", "a@b.com", "password1", null)
        assertIs<UiState.Success<Unit>>(viewModel.authState.value)
    }

    @Test
    fun `clearState returns to Idle`() = runTest(dispatcher) {
        viewModel.login("a@b.com", "password1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.clearState()

        assertEquals(UiState.Idle, viewModel.authState.value)
    }
}
