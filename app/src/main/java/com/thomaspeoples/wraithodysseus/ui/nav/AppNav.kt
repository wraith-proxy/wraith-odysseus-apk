package com.thomaspeoples.wraithodysseus.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.WraithOdysseusApp
import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.about.AboutScreen
import com.thomaspeoples.wraithodysseus.ui.chat.ChatScreen
import com.thomaspeoples.wraithodysseus.ui.chat.SearchScreen
import com.thomaspeoples.wraithodysseus.ui.chat.SessionsScreen
import com.thomaspeoples.wraithodysseus.ui.email.ComposeScreen
import com.thomaspeoples.wraithodysseus.ui.email.InboxScreen
import com.thomaspeoples.wraithodysseus.ui.email.ReadScreen
import com.thomaspeoples.wraithodysseus.ui.email.accountFromArg
import com.thomaspeoples.wraithodysseus.ui.email.emailReadRoute
import com.thomaspeoples.wraithodysseus.ui.faq.FaqScreen
import com.thomaspeoples.wraithodysseus.ui.login.LoginScreen
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
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
    const val CHATS = "chats"
    const val EMAIL = "email"
    const val CHAT = "chat/{sid}"
    const val SEARCH = "search"
    const val EMAIL_READ = "email/read/{acct}/{folder}/{uid}"
    const val EMAIL_COMPOSE = "email/compose"
    const val ABOUT = "about"
    const val FAQ = "faq"
}

private val TABS = setOf(Routes.CHATS, Routes.EMAIL)

/**
 * Two tabs (Chats, Email) with everything else pushed on top. The bottom bar
 * shows on the tabs only; the pushed screens draw their own back arrow.
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

    val menu: @Composable () -> Unit = {
        OverflowMenu(
            onAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
            onFaq = { nav.navigate(Routes.FAQ) { launchSingleTop = true } },
            onLogout = {
                scope.launch {
                    AuthRepo(app.http, app.session).logout()
                    // The server address stays, so the next login only needs
                    // a username and password.
                    app.session.username = null
                    expiredFor = null
                    nav.navigate(Routes.LOGIN) { popUpTo(nav.graph.id) { inclusive = true } }
                }
            },
        )
    }

    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    // No content insets of its own: every screen handles the status bar
    // itself, and the bottom bar (when shown) pads for the navigation bar.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (current in TABS) TabBar(nav, current) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.LOGIN) {
                // Pushed over an expired screen, Back must not reveal it again
                // without a session; it leaves the app instead.
                val activity = LocalActivity.current
                BackHandler(enabled = nav.previousBackStackEntry != null) { activity?.finish() }
                LoginScreen(onLoggedIn = {
                    val back = returnAfterLogin(expiredFor, identity(app.session.baseUrl?.toString(), app.session.username))
                    expiredFor = null
                    if (back && nav.previousBackStackEntry != null) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.CHATS) { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                })
            }
            composable(Routes.CHATS) {
                SessionsScreen(
                    api = chatApi,
                    onOpen = { nav.navigate(chatRoute(it)) },
                    onSearch = { nav.navigate(Routes.SEARCH) },
                    menu = menu,
                )
            }
            composable(Routes.EMAIL) {
                InboxScreen(
                    api = emailApi,
                    onOpen = { nav.navigate(emailReadRoute(it)) },
                    onCompose = { nav.navigate(Routes.EMAIL_COMPOSE) },
                    menu = menu,
                )
            }
            composable(Routes.CHAT) { entry ->
                val canUseBash by produceState(false) { value = canUseBash(app) }
                ChatScreen(
                    api = chatApi,
                    sessionId = entry.arguments?.getString("sid").orEmpty(),
                    canUseBash = canUseBash,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    api = chatApi,
                    onOpen = { nav.navigate(chatRoute(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.EMAIL_READ) { entry ->
                val args = entry.arguments
                ReadScreen(
                    api = emailApi,
                    accountId = accountFromArg(args?.getString("acct").orEmpty()),
                    folder = args?.getString("folder").orEmpty(),
                    uid = args?.getString("uid").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.EMAIL_COMPOSE) {
                ComposeScreen(api = emailApi, onDone = { nav.popBackStack() })
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.FAQ) { FaqScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun chatRoute(sid: String) = "chat/" + Uri.encode(sid)

/** /api/auth/status, asked fresh each time a chat opens, so a privilege
 * change on the server shows up without logging out. Off on any failure:
 * the Terminal chip is the one control that must never appear by mistake. */
private suspend fun canUseBash(app: WraithOdysseusApp): Boolean {
    val origin = app.session.baseUrl ?: return false
    return try {
        AuthRepo(app.http, app.session).status(origin).canUseBash
    } catch (_: IOException) {
        false
    } catch (_: ApiException) {
        false
    } catch (_: JSONException) {
        false
    }
}

/** Tabs keep their own state: switching saves the one being left and
 * restores the one being entered, so the merged inbox is not refetched from
 * every account on each visit. Chats is always the root of the tab stack. */
@Composable
private fun TabBar(nav: NavHostController, current: String?) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(Routes.CHATS, R.string.nav_chats, ChatBubble),
            Triple(Routes.EMAIL, R.string.nav_email, Icons.Filled.Email),
        ).forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                onClick = {
                    if (current != route) {
                        nav.navigate(route) {
                            popUpTo(Routes.CHATS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = Muted,
                    unselectedTextColor = Muted,
                ),
            )
        }
    }
}

@Composable
private fun OverflowMenu(onAbout: () -> Unit, onFaq: () -> Unit, onLogout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            R.string.menu_about to onAbout,
            R.string.menu_faq to onFaq,
            R.string.menu_logout to onLogout,
        ).forEach { (label, action) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    expanded = false
                    action()
                },
            )
        }
    }
}

// icons-core has no chat bubble, and icons-extended is megabytes with minify
// off. This is Material's "ChatBubble" path (Apache-2.0), drawn by hand.
private val ChatBubble: ImageVector = materialIcon(name = "Filled.ChatBubble") {
    materialPath {
        moveTo(20f, 2f)
        horizontalLineTo(4f)
        curveTo(2.9f, 2f, 2.01f, 2.9f, 2.01f, 4f)
        lineTo(2f, 22f)
        lineToRelative(4f, -4f)
        horizontalLineToRelative(14f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(4f)
        curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
        close()
    }
}
