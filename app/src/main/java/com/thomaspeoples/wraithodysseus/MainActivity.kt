package com.thomaspeoples.wraithodysseus

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.thomaspeoples.wraithodysseus.demo.Demo
import com.thomaspeoples.wraithodysseus.ui.nav.AppNav
import com.thomaspeoples.wraithodysseus.ui.theme.AppPalette
import com.thomaspeoples.wraithodysseus.ui.theme.WraithOdysseusTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // On every API level, not just 35+ where it is forced: one layout path
        // to get right instead of two. Dark bars whatever the system theme:
        // the app itself is dark-only.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // The bars' icons follow the theme: dark icons on a light one.
            val light = AppPalette.current.isLight
            DisposableEffect(light) {
                val style = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // Demo mode keeps nothing: say so whenever a write is dropped.
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                Demo.writes.collect { Toast.makeText(context, "Demo mode: nothing is saved", Toast.LENGTH_SHORT).show() }
            }
            WraithOdysseusTheme {
                AppNav()
            }
        }
    }
}
