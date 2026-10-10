package com.thomaspeoples.wraithodysseus.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.BuildConfig
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.update.UpdateChecker
import com.thomaspeoples.wraithodysseus.ui.theme.CodeStyle
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground
import androidx.compose.ui.graphics.Color

private const val REPO_URL = "https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk"

// Everything that ends up inside the APK, with its licence. Kept by hand: the
// list is short and only changes when app/build.gradle.kts does.
private val libraries = listOf(
    "AndroidX Core, Activity, Lifecycle, Navigation" to "Apache-2.0",
    "Jetpack Compose and Material 3" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "Kotlin standard library and kotlinx.coroutines" to "Apache-2.0",
    "commonmark-java" to "BSD-2-Clause",
    "Material icons" to "Apache-2.0",
    "Lucide icons (licence text in assets/licenses/Lucide-ISC.txt)" to "ISC",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val updateCheck = UpdateChecker.enabled(LocalContext.current)
    Scaffold(
        modifier = Modifier.themeBackground(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_about)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = Muted)

            Section("What this is")
            Para(
                "An unofficial Android client for a self-hosted Odysseus AI server. It is " +
                    "not affiliated with or endorsed by the Odysseus project. It talks to a " +
                    "stock server (tested with 1.0.3) over the same routes the server's own " +
                    "web UI uses, so the server needs no changes."
            )

            Section("Privacy")
            Para(
                "The app talks to the server you enter and nothing else" +
                    (if (updateCheck) ", apart from the update check below. " else ". ") +
                    "There are no analytics and no tracking. Only the session cookie is " +
                    "stored, sealed with a key that never leaves this device; your password " +
                    "is never stored. Plain http is allowed only for private network " +
                    "addresses, so the cookie never crosses the internet unencrypted."
            )

            Section("Update check")
            Para(
                if (updateCheck) {
                    "Once per launch the app asks this project's Gitea releases page " +
                        "($REPO_URL) whether a newer version exists, and offers a link if so. " +
                        "Nothing about you or your server is sent. It is off when F-Droid " +
                        "installed the app."
                } else {
                    "The update check is off: F-Droid installed this copy, or it was built " +
                        "without one. It never contacts anything but your own server."
                }
            )

            Section("Source code")
            Para("Open source under the MIT licence.")
            SelectionContainer { Para(REPO_URL) }

            Section("Licences")
            Para("Wraith for Odysseus: MIT. Copyright (c) 2026 Thomas Peoples.")
            libraries.forEach { (name, licence) -> Para("$name: $licence") }
            Para("Fira Code 6.2 font: SIL Open Font License 1.1 (OFL-1.1).")
            FontLicence()
        }
    }
}

/** The OFL asks for its text to travel with the font, so it ships in the
 * APK and can be read here. */
@Composable
private fun FontLicence() {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        text = if (text == null) {
            context.assets.open("licenses/FiraCode-OFL.txt").bufferedReader().use { it.readText() }
        } else {
            null
        }
    }) {
        Text(if (text == null) "Show the font licence" else "Hide the font licence")
    }
    text?.let { SelectionContainer { Text(it, style = CodeStyle, color = MaterialTheme.colorScheme.onBackground) } }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
}
