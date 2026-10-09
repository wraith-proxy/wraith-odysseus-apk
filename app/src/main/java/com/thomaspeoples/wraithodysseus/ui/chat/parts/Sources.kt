package com.thomaspeoples.wraithodysseus.ui.chat.parts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.Finding
import com.thomaspeoples.wraithodysseus.chat.RagSource
import com.thomaspeoples.wraithodysseus.chat.hostOf
import com.thomaspeoples.wraithodysseus.chat.Source
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.Primary
import java.util.Locale
import kotlinx.coroutines.launch

/** A source row's tap: the phone's browser, for a web address only. */
@Composable
private fun rememberOpenLink(): (String) -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    return { url ->
        if (!isWebUrl(url) || !openInBrowser(context, url)) scope.launch { snackbar.showSnackbar("Nothing can open that link") }
    }
}

@Composable
private fun LinkRow(number: Int, title: String, domain: String, url: String, open: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClickLabel = "Open $domain") { open(url) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$number", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.widthIn(min = 14.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Primary, style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.Underline, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(domain, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "N Web sources" (or "N Research sources"): title and host, a tap opens the page (chatRenderer.js:1590). */
@Composable
internal fun SourcesBox(label: String, sources: List<Source>) {
    if (sources.isEmpty()) return
    val open = rememberOpenLink()
    Collapsible("${sources.size} $label", icon = Lucide.Globe) {
        sources.forEachIndexed { i, s -> LinkRow(i + 1, s.title, s.domain, s.url, open) }
    }
}

/** "N Raw collected findings" of a research turn. */
@Composable
internal fun FindingsBox(findings: List<Finding>) {
    if (findings.isEmpty()) return
    val open = rememberOpenLink()
    Collapsible("${findings.size} Raw collected findings", icon = Lucide.FileText) {
        findings.forEachIndexed { i, f ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LinkRow(i + 1, f.title, hostOf(f.url), f.url, open)
                if (f.summary.isNotBlank()) Text(f.summary, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "Sources (N documents)": retrieved chunks, with how close each matched (chatRenderer.js:1620). */
@Composable
internal fun RagBox(sources: List<RagSource>) {
    if (sources.isEmpty()) return
    Collapsible("Sources (${sources.size} documents)", icon = Lucide.Library) {
        sources.forEach { s ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val pct = s.similarity?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) }
                Text(listOfNotNull(s.filename, pct).joinToString("  "), style = MaterialTheme.typography.labelMedium)
                if (s.snippet.isNotBlank()) Text(s.snippet, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
