package com.thomaspeoples.wraithodysseus.demo

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Walkthrough steps shown once when the demo opens: title to text. */
internal val demoTourSteps: List<Pair<String, String>> = listOf(
    "Welcome to the demo" to "Everything here is made up and runs on your phone. Nothing is sent anywhere, and nothing you change is saved.",
    "Chats" to "Open any chat in the menu to see tables, code and a reasoning block. Send a message and you get a canned reply.",
    "The menu" to "Swipe in from the right edge, or tap the menu button. It holds your chats, search, mail and the Tools list.",
    "Tools" to "Calendar, Gallery, Library, Memory, Notes, Skills, Tasks and Theme each have sample data. Try changing the theme.",
    "Mail" to "The inbox has unread filters, attachments and a compose screen. Sending does nothing.",
    "Settings" to "The gear in the menu opens account settings. Log out to leave the demo and use your own server.",
)

/** True from entering demo mode until the walkthrough is closed. */
object DemoTour {
    var pending by mutableStateOf(false)
    var step by mutableIntStateOf(0)

    fun start() {
        step = 0
        pending = true
    }
}

@Composable
fun DemoTourDialog() {
    if (!DemoTour.pending) return
    val step = DemoTour.step
    val (title, text) = demoTourSteps[step]
    val last = step == demoTourSteps.lastIndex
    AlertDialog(
        onDismissRequest = { DemoTour.pending = false },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { if (last) DemoTour.pending = false else DemoTour.step++ }) { Text(if (last) "Done" else "Next") }
        },
        dismissButton = {
            TextButton(onClick = { DemoTour.pending = false }) { Text("Skip") }
        },
    )
}
