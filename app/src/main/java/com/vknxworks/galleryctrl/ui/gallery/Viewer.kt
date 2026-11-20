package com.vknxworks.galleryctrl.ui.gallery

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MediaRequests
import com.vknxworks.galleryctrl.media.Sharing
import com.vknxworks.galleryctrl.media.VaultEntry
import com.vknxworks.galleryctrl.ui.components.CtrlBottomSheet
import com.vknxworks.galleryctrl.ui.components.CtrlSquareButton
import com.vknxworks.galleryctrl.ui.components.GroupRow
import com.vknxworks.galleryctrl.ui.lock.findActivity
import com.vknxworks.galleryctrl.ui.nav.LocalNavigator
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Full-screen photo or video. Swipe between them, pinch or double-tap to zoom, tap to hide the bars. */
@Composable
fun ViewerScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val source by c.viewerList.collectAsStateWithLifecycle()
    val live by c.media.items.collectAsStateWithLifecycle()
    val loading by c.media.loading.collectAsStateWithLifecycle()
    // Keep favourites and deletions current while viewing. (A photo opened in the moment the library is still
    // being read for the first time is shown from the list it was opened from until the library is in.)
    val items = remember(source, live, loading) {
        if (loading && live.isEmpty()) source else { val byId = live.associateBy { it.id }; source.mapNotNull { byId[it.id] } }
    }
    val start = remember { c.viewerStart.value }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (source.size - 1).coerceAtLeast(0))) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var slideshow by remember { mutableStateOf(c.viewerSlideshow.value) }
    var info by remember { mutableStateOf<MediaItem?>(null) }
    var more by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val request = rememberMediaRequest()

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty() && source.isNotEmpty()) nav.back() }
    LaunchedEffect(slideshow) {
        if (slideshow) chrome = false
        while (slideshow && items.isNotEmpty()) {
            delay(settings.slideInterval.seconds * 1000L)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    BackHandler(enabled = slideshow) { slideshow = false; chrome = true }
    val current = items.getOrNull(pager.currentPage)

    // Ultra HDR photos show their extra brightness on HDR screens.
    val activity = context.findActivity()
    var hdr by remember { mutableStateOf(false) }
    DisposableEffect(hdr) {
        activity?.window?.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        onDispose { activity?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item, active = page == pager.currentPage)
            else ZoomableImage(item.uri, onTap = { chrome = !chrome }, onHdr = { if (page == pager.currentPage) hdr = it })
        }
        AnimatedVisibility(chrome && current != null, enter = fadeIn(), exit = fadeOut()) {
            val item = current ?: return@AnimatedVisibility
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtrlSquareButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), { nav.back() }, tint = Color.White)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date)), color = Color.White, style = CtrlType.Status)
                        Text(item.album, color = Color.White.copy(alpha = 0.7f), style = CtrlType.Data)
                    }
                    if (!item.isVideo) CtrlSquareButton(Icons.Outlined.Slideshow, stringResource(R.string.slideshow), { slideshow = true }, tint = Color.White)
                    CtrlSquareButton(Icons.Outlined.MoreVert, stringResource(R.string.more), { more = true }, tint = Color.White)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CtrlSquareButton(Icons.Outlined.Share, stringResource(R.string.share), { Sharing.share(context, listOf(item)) }, tint = Color.White)
                    CtrlSquareButton(
                        if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, stringResource(R.string.favorite),
                        { request(MediaRequests.favorite(context, listOf(item), !item.favorite)) }, tint = if (item.favorite) CtrlColors.Accent else Color.White,
                    )
                    CtrlSquareButton(Icons.Outlined.Edit, stringResource(R.string.edit), { edit(context, item) }, tint = Color.White)
                    CtrlSquareButton(Icons.Outlined.Info, stringResource(R.string.info), { info = item }, tint = Color.White)
                    CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.delete), { request(MediaRequests.delete(context, listOf(item))) }, tint = Color.White)
                }
            }
        }
        if (slideshow) {
            Text(
                stringResource(R.string.slideshow_stop), color = Color.White.copy(alpha = 0.7f), style = CtrlType.Label,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { slideshow = false; chrome = true } })
        }
    }
    if (more && current != null) {
        val item = current
        CtrlBottomSheet(onDismissRequest = { more = false }) {
            GroupRow(stringResource(R.string.set_as), icon = Icons.Outlined.Wallpaper, onClick = {
                more = false
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("mimeType", item.mime),
                            context.getString(R.string.set_as),
                        ),
                    )
                }
            })
            GroupRow(stringResource(R.string.move_to_vault), stringResource(R.string.move_to_vault_sub), icon = Icons.Outlined.Lock, onClick = {
                more = false
                scope.launch {
                    val stored = c.photoVault.add(listOf(item))
                    if (stored.isNotEmpty()) request(MediaRequests.delete(context, stored))
                }
            })
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
    info?.let { InfoSheet(it) { info = null } }
}

/** Hands the photo or video to an editor: LumenCtrl if it's installed, otherwise any app that edits. */
private fun edit(context: android.content.Context, item: MediaItem) {
    val lumen = Intent(Intent.ACTION_SEND).setPackage("com.vknxworks.lumenctrl").setType(item.mime)
        .putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(lumen) }.isSuccess) return
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_EDIT).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A photo you can pinch or double-tap to zoom. Big photos (up to 200 MP) are decoded at screen size, then sharper when zoomed. */
@Composable
fun ZoomableImage(uri: Uri, onTap: () -> Unit, onHdr: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val screen = context.resources.displayMetrics
    val target = maxOf(screen.widthPixels, screen.heightPixels)
    val bitmap by produceState<Bitmap?>(null, uri, scale > 1.5f) {
        val edge = if (scale > 1.5f) target * 3 else target
        value = withContext(Dispatchers.IO) { decode(context, uri, edge) } ?: value
    }
    LaunchedEffect(bitmap) { onHdr(bitmap?.hasGainmap() == true) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan * scale
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                )
            }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun decode(context: android.content.Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val big = maxOf(w, h)
        if (big > maxEdge) {
            val s = maxEdge.toFloat() / big
            decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
    }
}.getOrNull()

private fun decodeFile(file: File, maxEdge: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxEdge) {
            val s = maxEdge.toFloat() / big
            decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
    }
}.getOrNull()

/** Video with Android's own player controls; pauses when you swipe away. */
@Composable
private fun VideoPage(item: MediaItem, active: Boolean) {
    VideoBox(item.uri, active)
}

@Composable
private fun VideoBox(uri: Uri, active: Boolean) {
    val context = LocalContext.current
    val view = remember { VideoView(context).apply { setMediaController(MediaController(context).also { it.setAnchorView(this) }) } }
    DisposableEffect(uri) {
        view.setVideoURI(uri)
        onDispose { view.stopPlayback() }
    }
    LaunchedEffect(active) { if (active) view.start() else view.pause() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView({ view }, Modifier.fillMaxWidth())
    }
}

/** Details: date, size, resolution, camera, exposure and location (shown here only; never sent anywhere). */
@Composable
private fun InfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exif by produceState<Map<String, String>>(emptyMap(), item.id) {
        value = withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext emptyMap()
            runCatching {
                context.contentResolver.openInputStream(runCatching { android.provider.MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri))!!.use { s ->
                    val e = ExifInterface(s)
                    buildMap {
                        listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").takeIf { it.isNotBlank() }?.let { put("camera", it) }
                        e.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { put("lens", it) }
                        val parts = listOfNotNull(
                            e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "ƒ/$it" },
                            e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { if (it < 1) "1/${(1 / it).toInt()} s" else "$it s" },
                            e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                            e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { f -> f.split('/').let { if (it.size == 2) "%.1f mm".format(it[0].toDouble() / it[1].toDouble()) else "$f mm" } },
                        )
                        if (parts.isNotEmpty()) put("exposure", parts.joinToString(" · "))
                        e.latLong?.let { put("location", "%.5f, %.5f".format(it[0], it[1])) }
                    }
                }
            }.getOrDefault(emptyMap())
        }
    }
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        val rows = buildList {
            add(stringResource(R.string.info_name) to item.name)
            add(stringResource(R.string.info_date) to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(item.date)))
            add(stringResource(R.string.info_size) to "${item.width} × ${item.height} · ${android.text.format.Formatter.formatShortFileSize(context, item.size)}")
            if (item.isVideo) add(stringResource(R.string.info_duration) to durationText(item.durationMs))
            add(stringResource(R.string.info_folder) to item.relativePath)
            exif["camera"]?.let { add(stringResource(R.string.info_camera) to it) }
            exif["lens"]?.let { add(stringResource(R.string.info_lens) to it) }
            exif["exposure"]?.let { add(stringResource(R.string.info_exposure) to it) }
            exif["location"]?.let { add(stringResource(R.string.info_location) to it) }
        }
        rows.forEach { (k, v) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(k.uppercase(), style = CtrlType.Label, color = CtrlColors.Muted)
                Text(v, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = CtrlColors.Text)
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** A photo or video from the vault, decrypted only in GalleryCtrl's private cache while you look at it. */
@Composable
fun VaultViewer(entry: VaultEntry, onClose: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = LocalContext.current.container
    val video = entry.mime.startsWith("video/")
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (video) {
            // The player needs a file: a decrypted copy in the private cache, wiped when you leave the vault.
            val file by produceState<File?>(null, entry.id) { value = c.photoVault.open(entry) }
            file?.let { f -> VideoBox(Uri.fromFile(f), active = true) }
        } else {
            // Photos are decrypted in memory only.
            val bmp by produceState<Bitmap?>(null, entry.id) { value = c.photoVault.image(entry, 4096) }
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).navigationBarsPadding().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CtrlSquareButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onClose, tint = Color.White)
            CtrlSquareButton(Icons.Outlined.RestoreFromTrash, stringResource(R.string.vault_restore), onRestore, tint = Color.White)
            CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.vault_delete), onDelete, tint = Color.White)
        }
    }
}

