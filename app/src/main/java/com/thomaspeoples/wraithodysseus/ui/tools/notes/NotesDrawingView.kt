package com.thomaspeoples.wraithodysseus.ui.tools.notes

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.gallery.sampleSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longer side, in pixels, a drawing is decoded to: more than a phone screen shows. */
private const val DRAWING_DECODE_PX = 2048

/** What the viewer has for the picture: still loading, the bitmap, or why there is none. */
private sealed interface Picture {
    data object Loading : Picture
    class Ready(val image: ImageBitmap) : Picture
    class Missing(val reason: String) : Picture
}

/**
 * A drawing note, read-only. The web draws on a canvas and keeps the picture as a file; this shows that
 * file and leaves drawing to the web UI ([onEditInWeb]). A picture that is missing, too big or not an
 * image shows a placeholder line, never an error screen.
 */
@Composable
internal fun DrawingViewer(note: Note, api: NotesApi, onEditInWeb: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val picture by produceState<Picture>(Picture.Loading, note.id, note.imageUrl) {
        value = loadDrawing(note.imageUrl, api)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "Back") }
            Text(
                note.title.ifBlank { "Drawing" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEditInWeb) {
                Icon(Lucide.ExternalLink, null, modifier = Modifier.padding(end = 6.dp))
                Text("Edit in web UI")
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val p = picture) {
                Picture.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                is Picture.Ready -> Image(
                    p.image,
                    note.title.ifBlank { "Drawing" },
                    contentScale = ContentScale.FillWidth,
                    // The web paints a drawing on white; a transparent one would vanish on a dark theme.
                    modifier = Modifier.fillMaxWidth().background(Color.White),
                )
                is Picture.Missing -> Text(p.reason, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private suspend fun loadDrawing(imageUrl: String?, api: NotesApi): Picture {
    val bytes = try {
        when (val source = drawingSource(imageUrl)) {
            is DrawingSource.Inline -> source.bytes
            is DrawingSource.Remote -> api.drawingBytes(source.url)
                ?: return Picture.Missing("This drawing is too large to show here.")
            DrawingSource.Unusable -> return Picture.Missing("This drawing can't be shown here.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return Picture.Missing("Couldn't load the drawing: ${userMessage(e)}")
    }
    return withContext(Dispatchers.Default) { decodeDrawing(bytes) }
}

/** Decodes [bytes], sampled down to [DRAWING_DECODE_PX]; a placeholder when they are not an image. */
private fun decodeDrawing(bytes: ByteArray): Picture = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Picture.Missing("This drawing can't be shown here.")
    } else {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, DRAWING_DECODE_PX) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { Picture.Ready(it.asImageBitmap()) }
            ?: Picture.Missing("This drawing can't be shown here.")
    }
} catch (_: OutOfMemoryError) {
    Picture.Missing("This drawing is too large to show here.")
}
