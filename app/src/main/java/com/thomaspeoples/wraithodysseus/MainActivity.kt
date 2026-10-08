package com.thomaspeoples.wraithodysseus

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thomaspeoples.wraithodysseus.ui.nav.AppNav
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
            WraithOdysseusTheme {
                AppNav()
            }
        }
    }
}
