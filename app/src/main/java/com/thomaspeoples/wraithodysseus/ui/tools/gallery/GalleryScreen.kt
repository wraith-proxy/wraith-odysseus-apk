package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The web UI's Gallery: a paged grid of the library (newest first) with an
 * album / favourites filter, a full-screen viewer to swipe through with
 * favourite and delete, and upload through the system photo picker, which
 * needs no storage permission. Albums are only filtered by here; creating,
 * renaming and filing into them, tags, AI tagging, the photo editor and
 * bulk download stay in the web UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(env: ToolEnv) {
    val vm = viewModel { GalleryViewModel(GalleryApi(env.http)) }
    val snackbar = LocalSnackbar.current
    val resolver = LocalContext.current.contentResolver
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<GalleryImage?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = viewing != null) { viewing = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.upload(uris.map { uploadSource(resolver, it) })
    }

    val open = viewing
    if (open != null && vm.items.isNotEmpty()) {
        Viewer(vm, open.coerceIn(0, vm.items.lastIndex), onClose = { viewing = null }, onDelete = { deleting = it })
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            TopLevelBar("Gallery", onMenu = env.onMenu, actions = {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = vm.uploading == null,
                ) { Icon(Lucide.Upload, "Upload photos") }
            })
            vm.uploading?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Filters(vm)
            Grid(vm, onOpen = { viewing = it })
        }
    }
    if (viewing != null && vm.items.isEmpty() && !vm.loading) viewing = null

    deleting?.let { img ->
        ConfirmDialog(
            title = "Delete this photo?",
            confirm = "Delete",
            text = "It is removed from the server and cannot be restored.",
            onConfirm = { vm.delete(img) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Filters(vm: GalleryViewModel) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = vm.album == null && !vm.favoritesOnly, onClick = { vm.selectAlbum(null) }, label = { Text("All") }) }
        item { FilterChip(selected = vm.favoritesOnly, onClick = { vm.selectFavorites(!vm.favoritesOnly) }, label = { Text("Favourites") }) }
        items(vm.albums.size) { i ->
            val a = vm.albums[i]
            FilterChip(selected = vm.album == a.id, onClick = { vm.selectAlbum(if (vm.album == a.id) null else a.id) }, label = { Text("${a.name} (${a.count})") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Grid(vm: GalleryViewModel, onOpen: (Int) -> Unit) {
    val state = rememberLazyGridState()
    // Ask for the next page when the last few tiles come into view.
    LaunchedEffect(state, vm) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index to state.layoutInfo.totalItemsCount }
            .collect { (last, count) -> if (last != null && last >= count - PREFETCH) vm.loadMore() }
    }
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        when {
            vm.items.isEmpty() && vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            vm.items.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(vm.loadError ?: if (vm.favoritesOnly) "No favourites yet." else "No photos yet.", color = Muted)
                if (vm.loadError != null) TextButton(onClick = vm::refresh) { Text("Retry") }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                state = state,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(vm.items.size, key = { vm.items[it].id }) { i ->
                    Tile(vm, vm.items[i], onClick = { onOpen(i) })
                }
            }
        }
    }
}

@Composable
private fun Tile(vm: GalleryViewModel, img: GalleryImage, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        Photo(vm, img, THUMB_PX, ContentScale.Crop, Modifier.fillMaxSize())
        if (img.favorite) {
            Icon(Lucide.Heart, "Favourite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp))
        }
    }
}

/** The image downsampled to [maxPx]; a spinner while it loads, a note when it cannot be shown (videos, failed fetches). */
@Composable
private fun Photo(vm: GalleryViewModel, img: GalleryImage, maxPx: Int, scale: ContentScale, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, img.id, maxPx) { value = vm.bitmap(img, maxPx)?.asImageBitmap() }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, img.prompt, contentScale = scale, modifier = Modifier.fillMaxSize())
        } else {
            Text(if (img.isVideo) "Video" else "", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Viewer(vm: GalleryViewModel, start: Int, onClose: () -> Unit, onDelete: (GalleryImage) -> Unit) {
    val pager = rememberPagerState(initialPage = start) { vm.items.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { if (it >= vm.items.size - PREFETCH) vm.loadMore() }
    }
    val img = vm.items.getOrNull(pager.currentPage)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Lucide.ArrowLeft, "Back") }
            Text(
                "${pager.currentPage + 1} / ${vm.total.coerceAtLeast(vm.items.size)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (img != null) {
                IconButton(onClick = { vm.toggleFavorite(img) }, enabled = img.id !in vm.busy) {
                    Icon(
                        Lucide.Heart,
                        if (img.favorite) "Remove from favourites" else "Add to favourites",
                        tint = if (img.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { onDelete(img) }, enabled = img.id !in vm.busy) {
                    Icon(Lucide.Trash2, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { vm.items.getOrNull(it)?.id ?: it }) { page ->
            vm.items.getOrNull(page)?.let { Photo(vm, it, FULL_PX, ContentScale.Fit, Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraSmall)) }
        }
        if (img != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (img.prompt.isNotBlank()) Text(img.prompt, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(details(img), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun details(img: GalleryImage): String = buildList {
    parseServerTime(img.takenAt ?: img.createdAt)?.let {
        add(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault())))
    }
    if (img.width != null && img.height != null) add("${img.width}×${img.height}")
    img.fileSize?.let { add(humanSize(it)) }
}.joinToString(" · ")

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10} KB"
    else -> "$bytes B"
}

/** A picked photo as an upload: name and type from the provider, bytes read only when sent. */
private fun uploadSource(resolver: ContentResolver, uri: Uri): UploadSource {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "photo"
    return UploadSource(name, resolver.getType(uri)) { resolver.openInputStream(uri) }
}

private const val THUMB_PX = 360
private const val FULL_PX = 2048
private const val PREFETCH = 12
