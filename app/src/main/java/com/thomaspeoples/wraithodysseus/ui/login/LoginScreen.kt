package com.thomaspeoples.wraithodysseus.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.WraithOdysseusApp
import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.demo.Demo

/** Server address, then username and password, then a 2FA code if the
 * server asks for one. [onLoggedIn] fires once the cookie is stored. */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val app = LocalContext.current.applicationContext as WraithOdysseusApp
    val vm = viewModel {
        LoginViewModel(
            repo = AuthRepo(app.http, app.session),
            initialUrl = app.session.baseUrl?.takeUnless { Demo.isDemo(it) }?.toString().orEmpty(),
            initialUsername = app.session.username.orEmpty(),
            saveConnection = { origin, username ->
                app.session.baseUrl = origin
                app.session.username = username
            },
        )
    }
    val state by vm.state.collectAsState()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

            // Checked when focus leaves the field or Next is pressed, not per
            // keystroke: a half-typed address is not worth a request.
            var urlHadFocus by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = vm::onServerUrl,
                label = { Text("Server") },
                placeholder = { Text(stringResource(R.string.server_url_hint)) },
                singleLine = true,
                enabled = !state.busy,
                isError = state.serverError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { vm.checkServer() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (urlHadFocus && !it.isFocused) vm.checkServer()
                        urlHadFocus = it.isFocused
                    },
            )
            state.serverError?.let { Note(it, MaterialTheme.colorScheme.error) }
                ?: state.serverVersion?.let { Note(it, MaterialTheme.colorScheme.tertiary) }

            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUsername,
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPassword,
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (state.needsTotp) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.needsTotp) {
                OutlinedTextField(
                    value = state.totpCode,
                    onValueChange = vm::onTotpCode,
                    label = { Text("2FA code") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { Note(it, MaterialTheme.colorScheme.error) }

            Button(
                onClick = vm::submit,
                enabled = !state.busy,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text(if (state.needsTotp) "Verify" else "Log in", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = vm::startDemo, enabled = !state.busy) { Text("No server? Try the demo") }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
