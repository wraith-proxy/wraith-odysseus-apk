package com.thomaspeoples.wraithodysseus.ui.tools.research

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.io.ByteArrayInputStream

/** A menu of choices behind a button that shows the current one. */
@Composable
internal fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    onPick(value)
                })
            }
        }
    }
}

/** The last few rows of what the researcher is doing, as the web UI's live trace shows. */
@Composable
internal fun TraceList(action: List<TraceRow>, navigation: List<TraceRow>) {
    if (action.isEmpty() && navigation.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        action.takeLast(8).forEach { TraceLine(it) }
        if (navigation.isNotEmpty()) {
            Text("Navigation", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            navigation.takeLast(8).forEach { TraceLine(it) }
        }
    }
}

@Composable
private fun TraceLine(row: TraceRow) {
    val text = listOf(row.tool, row.target, row.status.takeIf { it.isNotBlank() && it != "skipped" }, row.meta.takeIf { it.isNotBlank() })
        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    Text(text, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A job that is running, or that ended badly and can be retried, edited or dismissed. */
@Composable
internal fun JobCard(
    job: LiveJob,
    now: Long,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(job.query, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (job.running) {
                Text(formatElapsed(now - job.startedAtMs), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, "Cancel research", tint = Muted, modifier = Modifier.size(18.dp)) }
            }
        }
        if (job.running) {
            val maxRounds = job.settings?.maxRounds ?: 0
            val model = job.progress.model.ifBlank { job.model }
            if (model.isNotBlank()) Text(model, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(formatPhase(job.progress, maxRounds), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progressPercent(job.progress.round, maxRounds, job.mode) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            TraceList(job.actionTrace, job.navigationTrace)
        } else {
            Text(
                job.error ?: if (job.status == "cancelled") "Cancelled" else "Research failed.",
                color = if (job.status == "cancelled") Muted else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** A finished report in the history. A hold, or the menu, offers the rest. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryCard(item: LibraryItem, onOpen: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val explainOnly = item.mode == "explain"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.query, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                categoryName(item.category).takeIf { item.category.isNotBlank() },
                item.duration.takeIf { it.isNotBlank() },
                if (explainOnly) "model only" else "${item.sourceCount} sources",
            ).joinToString(" · ")
            Text(meta, color = Muted, style = MaterialTheme.typography.labelSmall)
            // The web flags a report that gathered nothing, since it is likely to be empty.
            if (item.sourceCount == 0 && !explainOnly) {
                Text("Couldn't extract, try again or change Settings.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.Ellipsis, "Report actions", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

private const val BASE_URL = "http://wraith.invalid/"

/**
 * The visual report in a locked-down WebView. JavaScript is off, file and
 * content access are off, and network loads are blocked outright: the report
 * is fetched by the app's own authenticated client and handed over as text,
 * so the WebView never holds the session cookie and never reaches the
 * network. A tapped link never navigates this view: it goes to [onLink]
 * (the browser). The page scrolls and zooms itself.
 */
@Composable
fun ReportView(html: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val link by rememberUpdatedState(onLink)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.WHITE)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                webViewClient = object : WebViewClient() {
                    // Nothing but the document itself loads.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.isForMainFrame) null
                        else WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

                    // A link tap, a meta refresh or a redirect: out to the browser. A same-page anchor scrolls here.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("$BASE_URL#")) return false
                        // A relative link resolves against the placeholder base and goes nowhere.
                        if (!url.startsWith(BASE_URL)) safeHref(url)?.let(link)
                        return true
                    }
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}
