package com.cookbook.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.components.PrimaryButtonFullWidth
import com.cookbook.util.UiState

/**
 * Two-step flow on one screen: request a reset code by email, then enter the code + a new
 * password. The server emails the code (or logs it when SMTP is unconfigured).
 */
@Composable
fun ForgotPasswordScreen(
    onResetSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by viewModel.authState.collectAsState()
    var email by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    LaunchedEffect(authState) {
        if (authState is UiState.Success) {
            if (!codeSent) {
                codeSent = true
                viewModel.clearState()
            } else {
                onResetSuccess()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reset password", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!codeSent) {
                "Enter your account email and we'll send a reset code."
            } else {
                "Enter the code from the email and choose a new password."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (!codeSent) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButtonFullWidth(
                text = if (authState is UiState.Loading) "Sending…" else "Send Reset Code",
                onClick = { viewModel.forgotPassword(email) },
                enabled = authState !is UiState.Loading,
            )
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Reset code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("New password (8+ characters)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButtonFullWidth(
                text = if (authState is UiState.Loading) "Resetting…" else "Set New Password",
                onClick = { viewModel.resetPassword(token, newPassword) },
                enabled = authState !is UiState.Loading,
            )
        }
        if (authState is UiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                (authState as UiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNavigateToLogin) {
            Text("Back to sign in")
        }
    }
}
