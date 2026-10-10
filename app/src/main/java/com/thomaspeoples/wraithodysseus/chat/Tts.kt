package com.thomaspeoples.wraithodysseus.chat

import android.media.MediaPlayer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays what POST /api/tts/synthesize returned. A seam so the view model can be tested without a speaker. */
interface SpeechOutput {
    /** Plays [speech] to its end, then calls [onEnd] (also when it fails to play or is replaced by another). Throws IOException if it cannot start. */
    suspend fun play(speech: Speech, onEnd: () -> Unit)

    /** Cuts any playing speech off. */
    fun stop()
}

/**
 * [SpeechOutput] on android.media.MediaPlayer. MediaPlayer wants a file or a
 * URL, not bytes, so the audio goes to a temp file in the app's cache
 * directory (no storage permission) that is deleted as soon as playback ends.
 * Used from the main thread only, which is also where MediaPlayer reports back.
 */
class MediaPlayerOutput(private val cacheDir: File) : SpeechOutput {
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var onEnd: (() -> Unit)? = null
    /** Bumped by every stop, so a play still writing its file notices it was cancelled. */
    private var generation = 0

    override suspend fun play(speech: Speech, onEnd: () -> Unit) {
        stop()
        val mine = generation
        val ext = if (speech.mime.contains("mpeg") || speech.mime.contains("mp3")) "mp3" else "wav"
        // Made before the write, so a cancel that lands while withContext is
        // returning can still delete it.
        val f = File(cacheDir, "speech-${System.nanoTime()}.$ext")
        try {
            withContext(Dispatchers.IO) { f.writeBytes(speech.bytes) }
        } catch (e: Throwable) {
            f.delete()
            throw e
        }
        if (mine != generation) {
            f.delete()
            return
        }
        val p = MediaPlayer()
        file = f
        player = p
        this.onEnd = onEnd
        try {
            p.setDataSource(f.path)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, _, _ ->
                finish()
                true
            }
            p.prepareAsync()
        } catch (e: IOException) {
            finish()
            throw e
        } catch (e: IllegalStateException) {
            finish()
            throw IOException("Could not play the audio", e)
        }
    }

    override fun stop() {
        generation++
        finish()
    }

    /** Releases the player and file, and tells whoever was waiting. */
    private fun finish() {
        val done = onEnd
        onEnd = null
        player?.let { runCatching { it.release() } }
        player = null
        file?.delete()
        file = null
        done?.invoke()
    }
}
