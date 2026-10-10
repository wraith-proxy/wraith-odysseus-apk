package com.thomaspeoples.wraithodysseus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.auth.AccountState
import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.auth.ChangePasswordForm
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONException
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground
import androidx.compose.ui.graphics.Color

/**
 * The web's account panel: who is logged in and their role, the password
 * change, whether two-factor sign-in is on (read only; turning it on needs
 * the QR code, which stays in the web UI) and Logout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    username: String?,
    status: AuthStatus?,
    auth: AuthRepo,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var twoFactor by remember { mutableStateOf<AccountState>(AccountState.Loading) }
    LaunchedEffect(Unit) {
        twoFactor = try {
            AccountState.Known(auth.totpEnabled())
        } catch (e: ApiException) {
            AccountState.Failed(userMessage(e))
        } catch (e: IOException) {
            AccountState.Failed(userMessage(e))
        } catch (e: JSONException) {
            AccountState.Failed(userMessage(e))
        }
    }

    Scaffold(
        modifier = Modifier.themeBackground(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(username, size = 40)
                Column {
                    Text(username ?: "Signed in", style = MaterialTheme.typography.bodyLarge)
                    status?.let { Text(if (it.isAdmin) "Admin" else "User", color = Muted, style = MaterialTheme.typography.labelSmall) }
                }
            }
            HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Lucide.ShieldCheck, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("Two-factor authentication", style = MaterialTheme.typography.bodyLarge)
                    Text("Set up or turned off in the web UI", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                when (val t = twoFactor) {
                    AccountState.Loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    is AccountState.Known -> Text(if (t.enabled) "On" else "Off", color = if (t.enabled) MaterialTheme.colorScheme.primary else Muted)
                    is AccountState.Failed -> Text("Unknown", color = Muted)
                }
            }
            (twoFactor as? AccountState.Failed)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))

            PasswordSection(
                onChange = { current, new ->
                    auth.changePassword(current, new)
                    scope.launch { snackbar.showSnackbar("Password changed") }
                },
            )
            HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onLogout) {
                    Icon(Lucide.LogOut, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text("Log out", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Current, new and repeated password. The server's refusal is shown as it is worded. */
@Composable
private fun PasswordSection(onChange: suspend (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var again by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Lucide.KeyRound, null, modifier = Modifier.size(18.dp))
        Text("Change password", modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PasswordField("Current password", current, !busy) { current = it }
        PasswordField("New password", new, !busy) { new = it }
        PasswordField("Repeat new password", again, !busy) { again = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = !busy,
            onClick = {
                val form = ChangePasswordForm(current, new, again)
                val problem = form.problem()
                if (problem != null) {
                    error = problem
                    return@Button
                }
                error = null
                busy = true
                scope.launch {
                    try {
                        onChange(current, new)
                        current = ""
                        new = ""
                        again = ""
                        open = false
                    } catch (e: ApiException) {
                        error = userMessage(e)
                    } catch (e: IOException) {
                        error = userMessage(e)
                    } catch (e: JSONException) {
                        error = userMessage(e)
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Changing" else "Change password") }
    }
}

@Composable
private fun PasswordField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
