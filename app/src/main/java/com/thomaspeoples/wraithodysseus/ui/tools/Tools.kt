package com.thomaspeoples.wraithodysseus.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.calendar.CalendarScreen
import com.thomaspeoples.wraithodysseus.ui.tools.gallery.GalleryScreen
import com.thomaspeoples.wraithodysseus.ui.tools.library.LibraryScreen
import com.thomaspeoples.wraithodysseus.ui.tools.memory.MemoryScreen
import com.thomaspeoples.wraithodysseus.ui.tools.notes.NotesScreen
import com.thomaspeoples.wraithodysseus.ui.tools.research.ResearchScreen
import com.thomaspeoples.wraithodysseus.ui.tools.skills.SkillsScreen
import com.thomaspeoples.wraithodysseus.ui.tools.tasks.TasksScreen
import com.thomaspeoples.wraithodysseus.ui.tools.theme.ThemeScreen
import kotlinx.coroutines.launch

/**
 * The web UI's Tools section, in its order. A [webOnly] tool opens the web
 * UI rather than a screen. A row whose [privilege] the account lacks is
 * hidden, as the web UI hides it (static/js/init.js).
 */
enum class Tool(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val privilege: String? = null,
    val adminOnly: Boolean = false,
    val webOnly: Boolean = false,
) {
    Calendar("calendar", "Calendar", Lucide.Calendar),
    Compare("compare", "Compare", Lucide.Columns2, webOnly = true),
    Cookbook("cookbook", "Cookbook", Lucide.BookOpen, adminOnly = true, webOnly = true),
    Research("research", "Deep Research", Lucide.ZoomIn, privilege = "can_use_research"),
    Gallery("gallery", "Gallery", Lucide.Image),
    Library("library", "Library", Lucide.Library, privilege = "can_use_documents"),
    Memory("memory", "Memory", Lucide.Brain, privilege = "can_manage_memory"),
    Notes("notes", "Notes", Lucide.NotebookPen),
    Skills("skills", "Skills", Lucide.Zap, privilege = "can_manage_memory"),
    Tasks("tasks", "Tasks", Lucide.CalendarCheck),
    Theme("theme", "Theme", Lucide.Palette),
    ;

    /** Unknown status (not loaded yet) shows what any account may see. */
    fun visibleTo(status: AuthStatus?): Boolean =
        (!adminOnly || status?.isAdmin == true) && (privilege == null || status?.allows(privilege) != false)

    companion object {
        fun byId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}

/** What a tool screen gets from the app shell. Tool screens are top level:
 * the drawer behind [onMenu] is the way out, as on every top-level screen. */
class ToolEnv(
    val http: Http,
    /** The server's address, for "Open in browser". */
    val baseUrl: String?,
    val status: AuthStatus?,
    val onMenu: () -> Unit,
    val openChat: (String) -> Unit,
)

/** Each tool has its own file, so each can be built without touching the
 * others or the navigation. */
@Composable
fun ToolScreen(tool: Tool, env: ToolEnv) = when (tool) {
    Tool.Calendar -> CalendarScreen(env)
    Tool.Research -> ResearchScreen(env)
    Tool.Gallery -> GalleryScreen(env)
    Tool.Library -> LibraryScreen(env)
    Tool.Memory -> MemoryScreen(env)
    Tool.Notes -> NotesScreen(env)
    Tool.Skills -> SkillsScreen(env)
    Tool.Tasks -> TasksScreen(env)
    Tool.Theme -> ThemeScreen(env)
    Tool.Compare, Tool.Cookbook -> WebOnlyScreen(tool, env)
}

/** A tool the app does not have yet: it says so, and opens the web UI. */
@Composable
fun WebOnlyScreen(tool: Tool, env: ToolEnv) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopLevelBar(tool.label, onMenu = env.onMenu)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tool.icon, contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
            Text("${tool.label} is not in the app yet.", textAlign = TextAlign.Center)
            Text("It works in the web UI.", color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.size(4.dp))
            Button(
                onClick = {
                    val url = env.baseUrl
                    if (url == null || !openInBrowser(context, url)) {
                        scope.launch { snackbar.showSnackbar("No browser to open it with") }
                    }
                },
            ) {
                Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Open in browser")
            }
        }
    }
}
