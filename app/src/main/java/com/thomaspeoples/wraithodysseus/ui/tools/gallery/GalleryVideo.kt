package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where downloaded videos live: a cache subdirectory the system may also reclaim. */
internal fun videoCacheDir(cacheDir: File) = File(cacheDir, "gallery-video")

/** Removes every file left in the video cache, e.g. by a process that died while a video was open. */
internal fun clearVideoCache(cacheDir: File) {
    videoCacheDir(cacheDir).listFiles()?.forEach { it.delete() }
}

/** A name that cannot collide with a file a previous (possibly still disposing) page is using. */
internal fun videoCacheFile(cacheDir: File, img: GalleryImage): File {
    val ext = img.filename.substringAfterLast('.', "mp4").filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
    return File(videoCacheDir(cacheDir), "${img.id.filter { it.isLetterOrDigit() }.take(40)}-${System.nanoTime()}.$ext")
}

private sealed interface VideoState {
    data object Idle : VideoState
    data class Loading(val read: Long, val total: Long?) : VideoState
    data class Ready(val file: File) : VideoState
    data class Failed(val message: String) : VideoState
}

/**
 * A video page of the viewer. Nothing is fetched until the play button is tapped (a video can be hundreds of MB);
 * the file is then downloaded with the app's own Http, with progress and a cancel, and played from the local copy by
 * a VideoView, which never sees a URL or the cookie. Leaving the page cancels a running download and deletes the file.
 */
@Composable
internal fun VideoPage(api: GalleryApi, img: GalleryImage, modifier: Modifier = Modifier) {
    val cacheDir = LocalContext.current.cacheDir
    val scope = rememberCoroutineScope()
    var state by remember(img.id) { mutableStateOf<VideoState>(VideoState.Idle) }
    var job by remember(img.id) { mutableStateOf<Job?>(null) }
    var file by remember(img.id) { mutableStateOf<File?>(null) }

    DisposableEffect(img.id) {
        onDispose {
            job?.cancel()
            file?.delete()
        }
    }

    fun start() {
        file?.delete()
        val dest = videoCacheFile(cacheDir, img)
        file = dest
        state = VideoState.Loading(0, img.fileSize)
        job = scope.launch {
            try {
                api.downloadVideo(img.url, dest) { read, total -> state = VideoState.Loading(read, total ?: img.fileSize) }
                state = VideoState.Ready(dest)
            } catch (e: CancellationException) {
                state = VideoState.Idle
                throw e
            } catch (e: Exception) {
                state = VideoState.Failed(userMessage(e))
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        when (val s = state) {
            is VideoState.Idle -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start) {
                    Icon(Lucide.Play, null, modifier = Modifier.size(18.dp))
                    Text("Play video", modifier = Modifier.padding(start = 8.dp))
                }
                img.fileSize?.let { Text(humanSize(it), color = Muted, style = MaterialTheme.typography.labelMedium) }
            }
            is VideoState.Loading -> Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val total = s.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (s.read.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${humanSize(s.read)} of ${humanSize(total)}", color = Muted, style = MaterialTheme.typography.labelMedium)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(humanSize(s.read), color = Muted, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
            }
            is VideoState.Failed -> Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = ::start) { Text("Retry") }
            }
            is VideoState.Ready -> LocalVideo(s.file, onError = { state = VideoState.Failed("This video could not be played") })
        }
    }
}

/** Plays [file], a path on this device, with the platform's own controls. */
@Composable
private fun LocalVideo(file: File, onError: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> onError(); true }
                setVideoPath(file.path)
            }
        },
        onRelease = { it.stopPlayback() },
    )
}
