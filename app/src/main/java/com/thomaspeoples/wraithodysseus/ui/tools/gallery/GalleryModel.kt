package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf

/** The power of two to pass as BitmapFactory's inSampleSize so the result is still at least [target] px on its longer side. */
fun sampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0 || target <= 0) return 1
    var size = 1
    val longer = maxOf(width, height)
    while (longer / (size * 2) >= target) size *= 2
    return size
}

/**
 * The gallery's state, so it survives rotation. The list is whatever pages
 * have been fetched so far for the current filter; changing the filter
 * starts again from offset 0, and a response for a filter that is no longer
 * current is dropped.
 */
class GalleryViewModel(internal val api: GalleryApi) : ViewModel() {
    var items by mutableStateOf<List<GalleryImage>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    var albums by mutableStateOf<List<Album>>(emptyList()); private set
    var loadError by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var uploading by mutableStateOf<String?>(null); private set

    /** The selected album id, or null for every photo. */
    var album by mutableStateOf<String?>(null); private set
    var favoritesOnly by mutableStateOf(false); private set

    /** Ids with a favourite or delete in flight. */
    var busy by mutableStateOf(emptySet<String>()); private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var generation = 0
    private val fetches = Semaphore(FETCHES)
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    val hasMore: Boolean get() = items.size < total

    init { refresh() }

    fun selectAlbum(id: String?) { album = id; favoritesOnly = false; reload() }

    fun selectFavorites(on: Boolean) { favoritesOnly = on; album = null; reload() }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        loadAlbums()
        reload(clear = false)
    }

    private fun reload(clear: Boolean = true) {
        generation++
        if (clear) items = emptyList()
        total = 0
        loading = false
        loadMore(reset = true)
    }

    /** The next page, or with [reset] the first one (replacing the list when it arrives). */
    fun loadMore(reset: Boolean = false) {
        if (loading || (!reset && items.isNotEmpty() && !hasMore)) return
        loading = true
        val gen = generation
        val offset = if (reset) 0 else items.size
        val filterAlbum = album
        val fav = favoritesOnly
        viewModelScope.launch {
            try {
                val page = api.library(offset, album = filterAlbum, favoritesOnly = fav)
                if (gen != generation) return@launch
                val base = if (offset == 0) emptyList() else items
                items = base + page.items.filter { n -> base.none { it.id == n.id } }
                // A page that comes back empty while total says more would loop forever on scroll.
                total = if (page.items.isEmpty()) items.size else page.total
                loadError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) loadError = userMessage(e)
            } finally {
                if (gen == generation) loading = false
                refreshing = false
            }
        }
    }

    private fun loadAlbums() {
        viewModelScope.launch {
            try { albums = api.albums() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the filter row just stays as it was */ }
        }
    }

    fun toggleFavorite(img: GalleryImage) {
        if (img.id in busy) return
        busy = busy + img.id
        viewModelScope.launch {
            try {
                val now = api.setFavorite(img.id, !img.favorite)
                items = items.map { if (it.id == img.id) it.copy(favorite = now) else it }
                // Un-favouriting inside the favourites view removes it from that view.
                if (favoritesOnly && !now) { items = items.filter { it.id != img.id }; total = maxOf(0, total - 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                busy = busy - img.id
            }
        }
    }

    fun delete(img: GalleryImage) {
        if (img.id in busy) return
        busy = busy + img.id
        viewModelScope.launch {
            try {
                api.delete(img.id)
                items = items.filter { it.id != img.id }
                total = maxOf(0, total - 1)
                messages.tryEmit("Deleted")
                loadAlbums()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                busy = busy - img.id
            }
        }
    }

    /** Sends the picked files one after another into the selected album, then shows the newest first. */
    fun upload(files: List<UploadSource>) {
        if (uploading != null || files.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            var dupes = 0
            val errors = mutableListOf<String>()
            files.forEachIndexed { i, f ->
                uploading = "Uploading ${i + 1}/${files.size}"
                try {
                    when (api.upload(f, album)) {
                        UploadResult.Imported -> imported++
                        UploadResult.Duplicate -> dupes++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += userMessage(e)
                }
            }
            uploading = null
            messages.tryEmit(uploadSummary(imported, dupes, errors))
            if (imported > 0) { loadAlbums(); reload() }
        }
    }

    /** A decoded bitmap at most [maxPx] on its longer side, or null when it is not a decodable image (a video, a failed fetch). */
    suspend fun bitmap(img: GalleryImage, maxPx: Int): Bitmap? {
        if (img.isVideo) return null
        val key = "${img.id}:$maxPx"
        cache.get(key)?.let { return it }
        val bitmap = try {
            val bytes = fetches.withPermit { api.imageBytes(img.url) }
            withContext(Dispatchers.Default) { decode(bytes, maxPx) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private companion object {
        const val FETCHES = 4
        const val CACHE_BYTES = 32 * 1024 * 1024
    }
}

/** The web's own wording: "N imported, M duplicates skipped, K errors". */
fun uploadSummary(imported: Int, dupes: Int, errors: List<String>): String {
    val parts = mutableListOf("$imported imported")
    if (dupes > 0) parts += "$dupes duplicate${if (dupes != 1) "s" else ""} skipped"
    if (errors.isNotEmpty()) parts += "${errors.size} error${if (errors.size != 1) "s" else ""}: ${errors.first()}"
    return parts.joinToString(", ")
}
