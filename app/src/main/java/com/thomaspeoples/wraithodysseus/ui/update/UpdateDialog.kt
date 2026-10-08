package com.thomaspeoples.wraithodysseus.ui.update

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.thomaspeoples.wraithodysseus.BuildConfig
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.update.UpdateChecker

/** Only ever shown for a confirmed newer release (UpdateChecker.checkForUpdate()
 * returns null both on failure and when already current) -- no "up to date"
 * state to render here, just this one case. */
@Composable
fun UpdateAvailableDialog(info: UpdateChecker.UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(stringResource(R.string.update_available_body, info.latestVersionName, BuildConfig.VERSION_NAME))
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, info.releaseUrl.toUri()))
                onDismiss()
            }) {
                Text(stringResource(R.string.update_available_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_available_dismiss))
            }
        },
    )
}
