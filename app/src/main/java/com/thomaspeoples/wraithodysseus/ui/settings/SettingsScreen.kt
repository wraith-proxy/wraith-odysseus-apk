package com.thomaspeoples.wraithodysseus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.Raised

/** Where the drawer's account row and gear lead: who is logged in, and the
 * app's own pages. Server settings stay in the web UI for now. */
@Composable
fun SettingsScreen(
    username: String?,
    status: AuthStatus?,
    onMenu: () -> Unit,
    onAccount: () -> Unit,
    onAbout: () -> Unit,
    onFaq: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopLevelBar("Settings", onMenu = onMenu)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
            SettingsRow(Lucide.User, "Account", onClick = onAccount)
            SettingsRow(Lucide.Info, stringResource(R.string.menu_about), onClick = onAbout)
            SettingsRow(Lucide.FileText, stringResource(R.string.menu_faq), onClick = onFaq)
            SettingsRow(Lucide.LogOut, stringResource(R.string.menu_logout), color = MaterialTheme.colorScheme.error, onClick = onLogout)
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The web UI's avatar: the username's first letter in a circle. */
@Composable
fun Avatar(username: String?, size: Int = 32) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Raised),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            username?.firstOrNull()?.uppercase() ?: "?",
            style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}
