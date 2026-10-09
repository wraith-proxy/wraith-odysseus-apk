package com.thomaspeoples.wraithodysseus.ui.nav

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import com.thomaspeoples.wraithodysseus.ui.sessions.chatListSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thomaspeoples.wraithodysseus.WraithOdysseusApp
import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.chat.SessionsApi
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.about.AboutScreen
import com.thomaspeoples.wraithodysseus.ui.chat.ChatScreen
import com.thomaspeoples.wraithodysseus.ui.chat.ChatViewModel
import com.thomaspeoples.wraithodysseus.ui.chat.HomeScreen
import com.thomaspeoples.wraithodysseus.ui.chat.SearchScreen
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.email.ComposeScreen
import com.thomaspeoples.wraithodysseus.ui.email.InboxScreen
import com.thomaspeoples.wraithodysseus.ui.email.ReadScreen
import com.thomaspeoples.wraithodysseus.ui.email.accountFromArg
import com.thomaspeoples.wraithodysseus.ui.email.emailReadRoute
import com.thomaspeoples.wraithodysseus.ui.faq.FaqScreen
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.login.LoginScreen
import com.thomaspeoples.wraithodysseus.ui.sessions.ArchivedController
import com.thomaspeoples.wraithodysseus.ui.sessions.ArchivedScreen
import com.thomaspeoples.wraithodysseus.ui.sessions.ChatListController
import com.thomaspeoples.wraithodysseus.ui.settings.AccountScreen
import com.thomaspeoples.wraithodysseus.ui.settings.PrefsApi
import com.thomaspeoples.wraithodysseus.ui.theme.AppPalette
import com.thomaspeoples.wraithodysseus.ui.theme.syncTheme
import com.thomaspeoples.wraithodysseus.ui.settings.SettingsScreen
import com.thomaspeoples.wraithodysseus.ui.tools.Tool
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import com.thomaspeoples.wraithodysseus.ui.tools.ToolScreen
import com.thomaspeoples.wraithodysseus.chat.FirstSend
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.ui.update.UpdateAvailableDialog
import com.thomaspeoples.wraithodysseus.update.UpdateChecker
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

/** Every route in the app, so the screens can be built separately and wired
 * in one place. Path arguments are URL-encoded by the caller. */
object Routes {
    const val LOGIN = "login"
    /** Straight on to the most recent chat; only an account with none stays. */
    const val HOME = "home"
    const val CHAT = "chat/{sid}"
    /** A chat that exists only on the phone until its first message is sent. */
    const val NEW = "new"
    const val ARCHIVED = "archived"
    const val ARCHIVED_CHAT = "archived/{sid}"
    const val ACCOUNT = "account"
    const val SEARCH = "search"
    const val EMAIL = "email"
    const val EMAIL_READ = "email/read/{acct}/{folder}/{uid}"
    const val EMAIL_COMPOSE = "email/compose"
    const val SETTINGS = "settings"
    const val TOOL = "tool/{id}"
    const val ABOUT = "about"
    const val FAQ = "faq"
}

private const val NO_BROWSER = "No browser to open it with"

/**
 * The web UI's layout: a chat is the home screen, and a drawer from the
 * right (its sidebar) leads everywhere else. A chat is always the bottom of
 * the stack; the drawer's other places sit on top of it one at a time, so
 * Back from any of them returns to the chat, and Back from the chat leaves.
 *
 * A session the server rejects (Http's sessionExpired) pushes login on top
 * of whatever is open rather than clearing the stack, so logging back in as
 * the same account just pops back to that exact screen.
 */
@Composable
fun AppNav() {
    val app = LocalContext.current.applicationContext as WraithOdysseusApp
    // The first cookie read decrypts it through the Keystore (a disk read and
    // binder IPC), so it is done on IO; the theme's background shows meanwhile.
    val start = produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { startRoute(app.session.cookie, System.currentTimeMillis()) }
    }.value ?: return
    val nav = rememberNavController()
    val chatApi = remember { ChatApi(app.http) }
    val emailApi = remember { EmailApi(app.http) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current

    // What the drawer shows, fetched afresh each time it opens.
    var sessions by remember { mutableStateOf<List<ChatSession>?>(null) }
    var drawerError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<AuthStatus?>(null) }

    // Who was logged in when the server said the session was gone. Saveable,
    // so a rotation on the login screen does not forget where to go back to.
    var expiredFor by rememberSaveable { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    // Saveable, so a rotation neither checks again nor brings back a
    // dismissed dialog. Set once nothing is left to show.
    var updateChecked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.http.sessionExpired.collect {
            if (nav.currentDestination?.route != Routes.LOGIN) {
                expiredFor = identity(app.session.baseUrl?.toString(), app.session.username)
                nav.navigate(Routes.LOGIN) { launchSingleTop = true }
            }
        }
    }

    // One check per cold start; a no-op in an F-Droid build. It fails
    // silently and never blocks the UI -- see UpdateChecker.
    LaunchedEffect(Unit) {
        if (updateChecked) return@LaunchedEffect
        updateInfo = UpdateChecker.checkForUpdate()
        if (updateInfo == null) updateChecked = true
    }
    updateInfo?.let {
        UpdateAvailableDialog(info = it, onDismiss = {
            updateInfo = null
            updateChecked = true
        })
    }

    val entry = nav.currentBackStackEntryAsState().value
    val route = entry?.destination?.route
    val currentChatId = if (route == Routes.CHAT) entry.arguments?.getString("sid") else null
    val loggedIn = route != null && route != Routes.LOGIN

    // One account's chats must never show to the next one.
    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            sessions = null
            drawerError = null
            status = null
            drawer.snapTo(DrawerValue.Closed)
        }
    }

    // The account's theme, once logged in; the next account never sees it.
    LaunchedEffect(loggedIn) {
        if (loggedIn) syncTheme(PrefsApi(app.http)) else AppPalette.reset()
    }

    suspend fun refreshSessions() {
        try {
            sessions = chatApi.sessions()
            drawerError = null
        } catch (e: ApiException) {
            drawerError = userMessage(e)
        } catch (e: IOException) {
            drawerError = userMessage(e)
        } catch (e: JSONException) {
            drawerError = userMessage(e)
        }
    }

    // As soon as it starts to open, not once the animation has settled.
    val opening = drawer.targetValue == DrawerValue.Open
    LaunchedEffect(opening) {
        if (!opening) return@LaunchedEffect
        launch { status = loadStatus(app) }
        refreshSessions()
    }

    // The model "+ New" was pressed in, and the first message of a chat made
    // by its first send, for the screen that opens next to pick up once.
    var newSeed by remember { mutableStateOf<ModelChoice?>(null) }
    var handoff by remember { mutableStateOf<Pair<String, FirstSend>?>(null) }

    // Chats this app has a reply running in: only the open one can, since
    // leaving a chat ends its follower.
    var runningIds by remember { mutableStateOf(emptySet<String>()) }

    /** A chat that is gone or archived is not left on screen: back to Home,
     * which opens the latest one that is left. */
    fun leaveChat(sid: String, say: String? = null) {
        nav.navigate(Routes.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
        say?.let { scope.launch { snackbar.showSnackbar(it) } }
        runningIds = runningIds - sid
    }
    val removed by rememberUpdatedState<(Set<String>) -> Unit> { gone ->
        val open = currentChatId
        if (open != null && open in gone) scope.launch {
            drawer.close()
            leaveChat(open)
        }
    }
    val makeChatList = {
        ChatListController(
            api = SessionsApi(app.http, chatApi),
            prefs = app.getSharedPreferences("sessions", Context.MODE_PRIVATE),
            scope = scope,
            say = { snackbar.showSnackbar(it) },
            reload = { refreshSessions() },
            copy = {
                try {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chat", it))
                    true
                } catch (e: RuntimeException) {
                    false
                }
            },
            onRemoved = { removed(it) },
        )
    }
    // Select mode and expanded groups survive rotation and process death.
    val chatList = rememberSaveable(saver = chatListSaver(makeChatList), init = makeChatList)

    // A stale selection or open dialog must not reach the next account.
    LaunchedEffect(loggedIn) { if (!loggedIn) chatList.reset() }

    fun openDrawer() {
        scope.launch { drawer.open() }
    }

    /** Runs [go] once the drawer is shut, so the next screen is not drawn under it. */
    fun fromDrawer(go: () -> Unit) {
        scope.launch {
            drawer.close()
            go()
        }
    }

    /** The empty new chat. Nothing is made on the server until its first
     * message is sent, so opening it again and again leaves no empty chats. */
    fun openNewChat(model: ModelChoice?) {
        if (route == Routes.NEW) return
        newSeed = model
        nav.navigate(Routes.NEW) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    fun newChat() = openNewChat(null)

    fun openWebUi() {
        val url = app.session.baseUrl?.toString()
        if (url == null || !openInBrowser(context, url)) scope.launch { snackbar.showSnackbar(NO_BROWSER) }
    }

    fun logout() {
        scope.launch {
            AuthRepo(app.http, app.session).logout()
            // The server address stays, so the next login only needs a
            // username and password.
            app.session.username = null
            expiredFor = null
            nav.navigate(Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    val toolEnv = ToolEnv(app.http, app.session.baseUrl?.toString(), status, ::openDrawer) { nav.openChat(it) }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        EndDrawer(
            state = drawer,
            enabled = loggedIn,
            drawerContent = {
                AppDrawer(
                    sessions = sessions,
                    error = drawerError,
                    currentChatId = currentChatId,
                    chatList = chatList,
                    running = runningIds,
                    status = status,
                    username = app.session.username,
                    onNewChat = { fromDrawer(::newChat) },
                    onSearch = { fromDrawer { nav.openOnTop(Routes.SEARCH) } },
                    onOpenChat = { sid -> fromDrawer { if (sid != currentChatId) nav.openChat(sid) } },
                    onEmail = { fromDrawer { nav.openOnTop(Routes.EMAIL) } },
                    onCompose = { fromDrawer { nav.openOnTop(Routes.EMAIL_COMPOSE) } },
                    onTool = { tool -> fromDrawer { if (tool.webOnly) openWebUi() else nav.openOnTop("tool/" + tool.id) } },
                    onArchived = { fromDrawer { nav.openOnTop(Routes.ARCHIVED) } },
                    onSettings = { fromDrawer { nav.openOnTop(Routes.SETTINGS) } },
                )
            },
        ) {
            // No content insets of its own: every screen handles the system
            // bars itself.
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = start,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                ) {
                    composable(Routes.LOGIN) {
                        // Pushed over an expired screen, Back must not reveal it
                        // again without a session; it leaves the app instead.
                        val activity = LocalActivity.current
                        BackHandler(enabled = nav.previousBackStackEntry != null) { activity?.finish() }
                        LoginScreen(onLoggedIn = {
                            val back = returnAfterLogin(expiredFor, identity(app.session.baseUrl?.toString(), app.session.username))
                            expiredFor = null
                            if (back && nav.previousBackStackEntry != null) {
                                nav.popBackStack()
                            } else {
                                nav.navigate(Routes.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
                            }
                        })
                    }
                    composable(Routes.HOME) {
                        HomeScreen(api = chatApi, onOpenChat = { nav.openChat(it) }, onNewChat = ::newChat, onMenu = ::openDrawer)
                    }
                    composable(Routes.CHAT) { chat ->
                        val canUseBash by produceState(false) { value = loadStatus(app)?.canUseBash ?: false }
                        ChatScreen(
                            api = chatApi,
                            sessionId = chat.arguments?.getString("sid").orEmpty(),
                            canUseBash = canUseBash,
                            onMenu = ::openDrawer,
                            onOpenChat = { nav.openChat(it) },
                            onNewChat = ::openNewChat,
                            takeFirst = {
                                handoff?.takeIf { it.first == chat.arguments?.getString("sid") }?.second.also { handoff = null }
                            },
                        )
                        // ChatScreen made its view model; this reads two things off it
                        // (the header menu and slash commands are not this file's).
                        val sid = chat.arguments?.getString("sid").orEmpty()
                        val vm = viewModel<ChatViewModel>(key = sid) { error("ChatScreen makes it") }
                        DisposableEffect(sid, vm.running) {
                            if (vm.running) runningIds = runningIds + sid
                            onDispose { runningIds = runningIds - sid }
                        }
                        LaunchedEffect(vm.closed) { vm.closed?.let { leaveChat(sid, it) } }
                    }
                    composable(Routes.NEW) {
                        val canUseBash by produceState(false) { value = loadStatus(app)?.canUseBash ?: false }
                        ChatScreen(
                            api = chatApi,
                            sessionId = "",
                            canUseBash = canUseBash,
                            onMenu = ::openDrawer,
                            onOpenChat = { nav.openChat(it) },
                            onNewChat = ::openNewChat,
                            onCreated = { id, first ->
                                handoff = id to first
                                nav.openChat(id)
                            },
                            startModel = newSeed,
                        )
                    }
                    composable(Routes.ARCHIVED) {
                        val archivedScope = rememberCoroutineScope()
                        val archived = remember {
                            ArchivedController(SessionsApi(app.http, chatApi), archivedScope, { snackbar.showSnackbar(it) })
                        }
                        ArchivedScreen(archived, onOpen = { nav.navigate("archived/" + Uri.encode(it)) }, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.ARCHIVED_CHAT) { chat ->
                        val sid = chat.arguments?.getString("sid").orEmpty()
                        ChatScreen(
                            api = chatApi,
                            sessionId = sid,
                            canUseBash = false,
                            onMenu = ::openDrawer,
                            onOpenChat = { nav.openChat(it) },
                            onNewChat = ::openNewChat,
                            archived = true,
                            onRestore = {
                                scope.launch {
                                    val problem = try {
                                        SessionsApi(app.http, chatApi).unarchive(sid)
                                        null
                                    } catch (e: ApiException) {
                                        userMessage(e)
                                    } catch (e: IOException) {
                                        userMessage(e)
                                    } catch (e: JSONException) {
                                        userMessage(e)
                                    }
                                    if (problem == null) nav.openChat(sid) else snackbar.showSnackbar(problem)
                                }
                            },
                        )
                        // Delete from the header closes it, as in a normal chat.
                        val vm = viewModel<ChatViewModel>(key = sid) { error("ChatScreen makes it") }
                        LaunchedEffect(vm.closed) { vm.closed?.let { leaveChat(sid, it) } }
                    }
                    composable(Routes.SEARCH) {
                        SearchScreen(
                            api = chatApi,
                            onOpen = { nav.openChat(it) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable(Routes.EMAIL) {
                        InboxScreen(
                            api = emailApi,
                            onOpen = { nav.navigate(emailReadRoute(it)) },
                            onCompose = { nav.navigate(Routes.EMAIL_COMPOSE) },
                            menu = { IconButton(onClick = ::openDrawer) { Icon(Lucide.Menu, "Menu") } },
                        )
                    }
                    composable(Routes.EMAIL_READ) { read ->
                        val args = read.arguments
                        ReadScreen(
                            api = emailApi,
                            accountId = accountFromArg(args?.getString("acct").orEmpty()),
                            folder = args?.getString("folder").orEmpty(),
                            uid = args?.getString("uid").orEmpty(),
                            onBack = { nav.popBackStack() },
                            isAdmin = status?.isAdmin == true,
                        )
                    }
                    composable(Routes.EMAIL_COMPOSE) {
                        ComposeScreen(api = emailApi, onDone = { nav.popBackStack() }, isAdmin = status?.isAdmin == true)
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            username = app.session.username,
                            status = status,
                            onMenu = ::openDrawer,
                            onAccount = { nav.navigate(Routes.ACCOUNT) { launchSingleTop = true } },
                            onAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
                            onFaq = { nav.navigate(Routes.FAQ) { launchSingleTop = true } },
                            onLogout = ::logout,
                        )
                    }
                    composable(Routes.ACCOUNT) {
                        val auth = remember { AuthRepo(app.http, app.session) }
                        AccountScreen(
                            username = app.session.username,
                            status = status,
                            auth = auth,
                            onBack = { nav.popBackStack() },
                            onLogout = ::logout,
                        )
                    }
                    composable(Routes.TOOL) { tool ->
                        Tool.byId(tool.arguments?.getString("id"))?.let { ToolScreen(it, toolEnv) }
                    }
                    composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.FAQ) { FaqScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

/**
 * The web UI's sidebar comes in from the right on a phone. Material's drawer
 * only comes from the start edge, so the layout direction is flipped around
 * it and flipped back inside. Swipes only close it: opening from the right
 * edge would fight the system Back gesture. Back closes it first; its
 * handler is composed after the NavHost's, so it takes precedence.
 */
@Composable
private fun EndDrawer(state: DrawerState, enabled: Boolean, drawerContent: @Composable () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val direction = LocalLayoutDirection.current
    val flipped = if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides flipped) {
        ModalNavigationDrawer(
            drawerState = state,
            gesturesEnabled = enabled && state.isOpen,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    BackHandler(enabled = state.isOpen) { scope.launch { state.close() } }
                    if (enabled) drawerContent()
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { content() }
        }
    }
}

private fun chatRoute(sid: String) = "chat/" + Uri.encode(sid)

/** A chat is the bottom of the stack: opening one replaces everything. */
private fun NavHostController.openChat(sid: String) =
    navigate(chatRoute(sid)) { popUpTo(graph.id) { inclusive = true } }

/** Anywhere else from the drawer goes on top of the chat (or Home, with no
 * chat yet), replacing whatever the drawer opened before. */
private fun NavHostController.openOnTop(route: String) {
    // The empty new chat is a root too, or each destination would stack on it.
    val root = listOf(Routes.CHAT, Routes.NEW).firstOrNull { runCatching { getBackStackEntry(it) }.isSuccess } ?: Routes.HOME
    navigate(route) {
        popUpTo(root)
        launchSingleTop = true
    }
}

/** /api/auth/status, asked fresh each time a chat opens, so a privilege
 * change on the server shows up without logging out. Null on any failure:
 * the Terminal chip is the one control that must never appear by mistake. */
private suspend fun loadStatus(app: WraithOdysseusApp): AuthStatus? {
    val origin = app.session.baseUrl ?: return null
    return try {
        AuthRepo(app.http, app.session).status(origin)
    } catch (_: IOException) {
        null
    } catch (_: ApiException) {
        null
    } catch (_: JSONException) {
        null
    }
}
