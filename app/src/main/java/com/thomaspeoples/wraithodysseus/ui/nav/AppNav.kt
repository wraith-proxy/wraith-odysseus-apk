package com.thomaspeoples.wraithodysseus.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.thomaspeoples.wraithodysseus.WraithOdysseusApp

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

/** Bootstrap shell: the graph is real, the screens are placeholders until
 * their own units land. */
@Composable
fun AppNav() {
    val navController = rememberNavController()
    val session = (LocalContext.current.applicationContext as WraithOdysseusApp).session
    val cookie = session.cookie
    val start = if (cookie == null || cookie.expiresAt <= System.currentTimeMillis()) Routes.LOGIN else Routes.CHATS

    NavHost(navController = navController, startDestination = start) {
        listOf(
            Routes.LOGIN, Routes.CHATS, Routes.EMAIL, Routes.CHAT, Routes.SEARCH,
            Routes.EMAIL_READ, Routes.EMAIL_COMPOSE, Routes.ABOUT, Routes.FAQ,
        ).forEach { route -> composable(route) { Placeholder(route) } }
    }
}

@Composable
private fun Placeholder(route: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(route, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
