package com.cookbook.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.components.BrandLogo
import com.cookbook.ui.components.PrimaryButtonFullWidth
import com.cookbook.util.UiState

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState is UiState.Success) onLoginSuccess()
    }

    LoginContent(
        email = email,
        onEmailChange = { email = it },
        password = password,
        onPasswordChange = { password = it },
        state = authState,
        onSubmit = { viewModel.login(email, password) },
        onNavigateToRegister = onNavigateToRegister,
        onNavigateToForgotPassword = onNavigateToForgotPassword,
    )
}

/** Stateless body — rendered by [LoginScreen] in the app and directly by screenshot tests. */
@Composable
fun LoginContent(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    state: UiState<Unit>,
    onSubmit: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo()
        Spacer(Modifier.height(16.dp))
        Text("Cookbook", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Recipes in, groceries out",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButtonFullWidth(
            text = if (state is UiState.Loading) "Signing in…" else "Sign In",
            onClick = onSubmit,
            enabled = state !is UiState.Loading,
        )
        if (state is UiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNavigateToForgotPassword) {
            Text("Forgot password?")
        }
        TextButton(onClick = onNavigateToRegister) {
            Text("Don't have an account? Create one")
        }
    }
}
