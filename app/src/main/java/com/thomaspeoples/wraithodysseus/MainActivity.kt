package com.thomaspeoples.wraithodysseus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.thomaspeoples.wraithodysseus.ui.nav.AppNav
import com.thomaspeoples.wraithodysseus.ui.theme.WraithOdysseusTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // On every API level, not just 35+ where it is forced: one layout path
        // to get right instead of two.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WraithOdysseusTheme {
                AppNav()
            }
        }
    }
}
