package com.vknxworks.galleryctrl.ui.gallery

import android.Manifest
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MimeFilter
import com.vknxworks.galleryctrl.ui.common.hasPermission
import com.vknxworks.galleryctrl.ui.common.openAppSettings
import com.vknxworks.galleryctrl.ui.components.ChoiceSheet
import com.vknxworks.galleryctrl.ui.components.CtrlButton
import com.vknxworks.galleryctrl.ui.components.CtrlSquareButton
import com.vknxworks.galleryctrl.ui.components.MessageView
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import com.vknxworks.galleryctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- Opened from another app ----------

/** One photo or video another app handed over: zoom, animation and the video player, and its name at the top. */
@Composable
fun ExternalViewer(uri: Uri, mime: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    var chrome by remember { mutableStateOf(true) }
    /** Null while checking; then whether the file can be read, and its name ("" when the other app doesn't say). */
    val opened by produceState<Pair<Boolean, String>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val readable = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull().orEmpty()
            readable to name
        }
    }
    val video = mime?.lowercase()?.startsWith("video/") == true
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val state = opened
        when {
            state == null -> Unit
            !state.first -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                MessageView(Icons.Outlined.BrokenImage, stringResource(R.string.external_unreadable), stringResource(R.string.external_unreadable_body), actionLabel = stringResource(R.string.close), onAction = onClose)
            }
            video -> {
                val insets = WindowInsets.systemBars.asPaddingValues()
                VideoBox(uri, active = true, padding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 16.dp))
            }
            else -> ZoomableImage(uri, onTap = { chrome = !chrome }, mime = mime)
        }
        AnimatedVisibility(chrome || video, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtrlSquareButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onClose, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(opened?.second.orEmpty(), color = Color.White, style = CtrlType.Status, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------- Photo picker ----------

private val PICK_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

/**
 * GalleryCtrl as a picker for another app: your library (hidden folders left out, never the vault), filtered to what
 * the app asked for. Tap a photo to hand it over; when the app allows several, tap to select and then Done.
 */
@Composable
fun PickerScreen(filter: MimeFilter, multiple: Boolean, onCancel: () -> Unit, onPick: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val full = remember(tick) { context.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) || context.hasPermission(Manifest.permission.READ_MEDIA_VIDEO) }
    val partial = remember(tick) { !full && context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        tick++
        c.media.reload()
    }
    LaunchedEffect(full, partial) {
        if (full || partial) c.media.start(access = full) else if (!asked) launcher.launch(PICK_PERMISSIONS)
    }
    val title = stringResource(
        when {
            filter.photos && !filter.videos -> if (multiple) R.string.pick_photos else R.string.pick_photo
            filter.videos && !filter.photos -> if (multiple) R.string.pick_videos else R.string.pick_video
            else -> if (multiple) R.string.pick_items else R.string.pick_item
        },
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        if (!(full || partial)) {
            PickerTopBar(title, onCancel, null)
            MessageView(
                Icons.Outlined.Photo, stringResource(R.string.access_title), stringResource(R.string.access_body),
                actionLabel = stringResource(if (asked) R.string.open_permissions else R.string.allow_access),
                onAction = { if (asked) openAppSettings(context) else launcher.launch(PICK_PERMISSIONS) },
            )
            return@Column
        }
        PickerGrid(filter, multiple, partial, title, onCancel, onMore = { launcher.launch(PICK_PERMISSIONS) }, onPick = onPick)
    }
}

@Composable
private fun PickerTopBar(title: String, onCancel: () -> Unit, onFolders: (() -> Unit)?, folderLabel: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CtrlSquareButton(Icons.Outlined.Close, stringResource(R.string.cancel), onCancel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (folderLabel != null) Text(folderLabel, style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onFolders != null) CtrlSquareButton(Icons.Outlined.Folder, stringResource(R.string.pick_folder), onFolders)
    }
}

@Composable
private fun PickerGrid(filter: MimeFilter, multiple: Boolean, limited: Boolean, title: String, onCancel: () -> Unit, onMore: () -> Unit, onPick: (List<Uri>) -> Unit) {
    val c = LocalContext.current.container
    val library by c.visible.collectAsStateWithLifecycle()
    val all = remember(library.items, filter) { library.items.filter { filter.accepts(it.mime) } }
    val albums = remember(all) { c.media.albums(all) }
    var album by remember { mutableStateOf<Long?>(null) }
    val shown = remember(all, album) { album?.let { id -> all.filter { it.albumId == id } } ?: all }
    var selected by remember { mutableStateOf(listOf<MediaItem>()) }
    var folders by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val dates = remember(shown) { gridDates(listOf(shown.map { it.date }), headings = false) }
    val px = with(LocalDensity.current) { 90.dp.roundToPx() }
    val allLabel = stringResource(R.string.pick_all_folders)
    val folderName = album?.let { id -> albums.firstOrNull { it.id == id }?.name } ?: allLabel

    PickerTopBar(title, onCancel, onFolders = { folders = true }, folderLabel = folderName)
    if (limited) {
        Row(
            Modifier.padding(horizontal = Gutter, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(CtrlColors.Surface).clickable(onClick = onMore).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.limited_access), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.choose_more), style = CtrlType.Status, color = CtrlColors.AccentText)
        }
    }
    when {
        library.loading && all.isEmpty() -> Text(stringResource(R.string.storage_counting), color = CtrlColors.Muted, modifier = Modifier.padding(Gutter))
        shown.isEmpty() -> MessageView(Icons.Outlined.Photo, stringResource(R.string.pick_nothing))
        else -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    GridCells.Fixed(4), Modifier.fillMaxSize(), state = gridState,
                    contentPadding = PaddingValues(bottom = if (multiple) 8.dp else 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(shown, key = { it.id }, contentType = { "photo" }) { item ->
                        val isSel = selected.any { it.id == item.id }
                        Box(
                            Modifier.aspectRatio(1f).clickable {
                                if (!multiple) onPick(listOf(item.uri))
                                else selected = if (isSel) selected.filterNot { it.id == item.id } else selected + item
                            },
                        ) {
                            Thumb(item, px, Modifier.fillMaxSize().scale(if (isSel) 0.86f else 1f).clip(RoundedCornerShape(if (isSel) 12.dp else 0.dp)))
                            if (isSel) CheckMark(Icons.Outlined.CheckCircle, Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                        }
                    }
                }
                FastScroller(gridState, dates, Modifier.align(Alignment.TopEnd))
            }
            if (multiple) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gutter, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.selected_count, selected.size, selected.size), style = CtrlType.Status, color = CtrlColors.Text,
                        modifier = Modifier.weight(1f),
                    )
                    CtrlButton(stringResource(R.string.pick_done), { onPick(selected.map { it.uri }) }, enabled = selected.isNotEmpty())
                }
            }
        }
    }
    if (folders) {
        val options: List<Pair<Long?, String>> = listOf<Pair<Long?, String>>(null to allLabel) + albums.map { a -> Pair<Long?, String>(a.id, a.name.ifBlank { "—" }) }
        ChoiceSheet(stringResource(R.string.pick_folder), options, album, onDismiss = { folders = false }) { picked ->
            folders = false
            album = picked
        }
    }
}

@Composable
private fun CheckMark(item: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    androidx.compose.material3.Icon(item, null, tint = CtrlColors.Accent, modifier = modifier)
}
