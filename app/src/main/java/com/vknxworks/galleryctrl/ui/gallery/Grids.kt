package com.vknxworks.galleryctrl.ui.gallery

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.vknxworks.galleryctrl.media.Album
import com.vknxworks.galleryctrl.media.Duplicates
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MediaRequests
import com.vknxworks.galleryctrl.media.OnThisDay
import com.vknxworks.galleryctrl.media.Sharing
import com.vknxworks.galleryctrl.media.VaultEntry
import com.vknxworks.galleryctrl.ui.common.hasPermission
import com.vknxworks.galleryctrl.ui.common.openAppSettings
import com.vknxworks.galleryctrl.ui.components.CtrlBottomSheet
import com.vknxworks.galleryctrl.ui.components.CtrlButton
import com.vknxworks.galleryctrl.ui.components.GroupRow
import com.vknxworks.galleryctrl.ui.components.CtrlSquareButton
import com.vknxworks.galleryctrl.ui.components.CtrlTextField
import com.vknxworks.galleryctrl.ui.components.MessageView
import com.vknxworks.galleryctrl.ui.components.ScreenTopBar
import com.vknxworks.galleryctrl.ui.components.SectionLabel
import com.vknxworks.galleryctrl.ui.components.TabTitle
import com.vknxworks.galleryctrl.ui.components.bottomClearance
import com.vknxworks.galleryctrl.ui.lock.AppLockGate
import com.vknxworks.galleryctrl.ui.nav.AlbumRoute
import com.vknxworks.galleryctrl.ui.nav.DuplicatesRoute
import com.vknxworks.galleryctrl.ui.nav.LocalNavigator
import com.vknxworks.galleryctrl.ui.nav.OnThisDayRoute
import com.vknxworks.galleryctrl.ui.nav.SearchRoute
import com.vknxworks.galleryctrl.ui.nav.StorageRoute
import com.vknxworks.galleryctrl.ui.nav.VaultRoute
import com.vknxworks.galleryctrl.ui.nav.ViewerRoute
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import com.vknxworks.galleryctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---------- Permission ----------

private val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

/** Asks for access to photos and videos, then starts the library; shows a card when access is limited or missing. */
@Composable
fun MediaAccess(content: @Composable (limited: Boolean, pickMore: () -> Unit) -> Unit) {
    val context = LocalContext.current
    val c = context.container
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val full = remember(tick) { context.hasPermission(Manifest.permission.READ_MEDIA_IMAGES) }
    val partial = remember(tick) { !full && context.hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        tick++
        c.media.reload()
    }
    LaunchedEffect(full, partial) {
        if (full || partial) c.media.start() else if (!asked) launcher.launch(MEDIA_PERMISSIONS)
    }
    if (full || partial) {
        content(partial) { launcher.launch(MEDIA_PERMISSIONS) }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TabTitle(stringResource(R.string.tab_photos))
            MessageView(
                Icons.Outlined.Photo,
                stringResource(R.string.access_title),
                stringResource(R.string.access_body),
                actionLabel = stringResource(if (asked) R.string.open_permissions else R.string.allow_access),
                onAction = { if (asked) openAppSettings(context) else launcher.launch(MEDIA_PERMISSIONS) },
            )
        }
    }
}

// ---------- Shared grid with selection ----------

/** Runs a MediaStore request (Android shows its own confirmation) and reloads afterwards. */
@Composable
fun rememberMediaRequest(onDone: (Boolean) -> Unit = {}): (android.app.PendingIntent) -> Unit {
    val c = LocalContext.current.container
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        c.media.reload()
        onDone(res.resultCode == Activity.RESULT_OK)
    }
    return { pi -> runCatching { launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) } }
}

@Composable
fun Thumb(item: MediaItem, px: Int, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    // Starts from the copy in memory when there is one, so a tile scrolled back into view isn't blank for a frame.
    val bmp by produceState<android.graphics.Bitmap?>(c.media.cachedThumbnail(item, px), item.id) { value = c.media.thumbnail(item, px) }
    Box(modifier.background(CtrlColors.Raised)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (item.isVideo) {
            Row(Modifier.align(Alignment.BottomStart).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(3.dp))
                Text(durationText(item.durationMs), style = CtrlType.Data, color = Color.White)
            }
        }
        if (item.favorite) Icon(Icons.Outlined.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(14.dp))
    }
}

fun durationText(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

@Composable
private fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> stringResource(R.string.today)
    LocalDate.now().minusDays(1) -> stringResource(R.string.yesterday)
    else -> d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
}

/**
 * A grid of photos grouped by day. Tap opens the viewer; long-press starts selecting (tap more to add).
 * Selection actions: share, favourite, delete (for good), move to the vault.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    groupByDay: Boolean = true,
    /** Groups with their own headings (On this day: one per year), instead of grouping by day. */
    sections: List<Pair<String, List<MediaItem>>>? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    val organize = rememberOrganize()
    val request = rememberMediaRequest { selected = emptySet() }
    val scope = rememberCoroutineScope()
    val px = with(LocalDensity.current) { (360.dp / settings.columns).roundToPx() }.coerceAtLeast(96)
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    // Grouped once per list (and per time zone), not again on every tap while selecting or change of grid size.
    val zone = ZoneId.systemDefault()
    val groups = remember<List<Pair<Any, List<MediaItem>>>>(items, sections, groupByDay, zone) {
        sections ?: if (groupByDay) items.groupBy { dayOf(it.date, zone) }.toList() else listOf(LocalDate.MIN to items)
    }
    val showHeadings = sections != null || groupByDay
    // Where every grid position is in time, for the fast-scroll bubble.
    val dates = remember(groups, showHeadings) { gridDates(groups.map { (_, l) -> l.map { it.date } }, showHeadings) }
    val gridState = rememberLazyGridState()

    fun open(item: MediaItem) {
        c.viewerList.value = items
        c.viewerStart.value = items.indexOf(item).coerceAtLeast(0)
        c.viewerSlideshow.value = false
        nav.navigate(ViewerRoute)
    }

    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.columns),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            contentPadding = PaddingValues(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            header?.invoke(this)
            groups.forEach { (day, list) ->
                if (showHeadings) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$day", contentType = "header") {
                        Text(if (day is LocalDate) dayLabel(day) else day.toString(), style = MaterialTheme.typography.titleSmall, color = CtrlColors.Text, modifier = Modifier.padding(start = Gutter, top = 18.dp, bottom = 8.dp))
                    }
                }
                items(list, key = { it.id }, contentType = { "photo" }) { item ->
                    val isSel = item.id in selected
                    val scale by animateFloatAsState(if (isSel) 0.86f else 1f, label = "sel")
                    Box(
                        Modifier.aspectRatio(1f).combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - item.id else selected + item.id else open(item) },
                            onLongClick = { selected = selected + item.id },
                        ),
                    ) {
                        Thumb(item, px, Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (isSel) 12.dp else 0.dp)))
                        if (isSel) Icon(Icons.Outlined.CheckCircle, null, tint = CtrlColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                    }
                }
            }
        }
        FastScroller(gridState, dates, Modifier.align(Alignment.TopEnd).padding(bottom = 120.dp))
        if (selected.isNotEmpty()) {
            val sel = items.filter { it.id in selected }
            SelectionBar(
                count = sel.size,
                modifier = Modifier.align(Alignment.BottomCenter).bottomClearance(8.dp),
                onShare = { Sharing.share(context, sel) },
                onFavorite = { request(MediaRequests.favorite(context, sel, !sel.all { it.favorite })) },
                onDelete = { request(MediaRequests.delete(context, sel)) },
                onVault = {
                    scope.launch {
                        val stored = c.photoVault.add(sel)
                        if (stored.isNotEmpty()) request(MediaRequests.delete(context, stored))
                    }
                },
                onMore = { selectionMenu = true },
                onClear = { selected = emptySet() },
            )
            if (selectionMenu) {
                CtrlBottomSheet(onDismissRequest = { selectionMenu = false }) {
                    GroupRow(stringResource(R.string.move_to), icon = Icons.AutoMirrored.Outlined.DriveFileMove, onClick = { selectionMenu = false; organize.move(sel) })
                    GroupRow(stringResource(R.string.copy_to), icon = Icons.Outlined.FileCopy, onClick = { selectionMenu = false; organize.copy(sel) })
                    if (sel.size == 1) GroupRow(stringResource(R.string.rename), icon = Icons.Outlined.DriveFileRenameOutline, onClick = { selectionMenu = false; organize.rename(sel.first()) })
                    Spacer(Modifier.navigationBarsPadding().height(12.dp))
                }
            }
        }
        OrganizeDialogs(organize) { selected = emptySet() }
    }
}

@Composable
private fun SelectionBar(count: Int, modifier: Modifier, onShare: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit, onVault: () -> Unit, onMore: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CtrlColors.Surface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.selected_count, count, count), style = CtrlType.Status, color = CtrlColors.Text,
            modifier = Modifier.weight(1f).clickable(onClick = onClear).padding(12.dp),
        )
        CtrlSquareButton(Icons.Outlined.Share, stringResource(R.string.share), onShare)
        CtrlSquareButton(Icons.Outlined.FavoriteBorder, stringResource(R.string.favorite), onFavorite)
        CtrlSquareButton(Icons.Outlined.Lock, stringResource(R.string.move_to_vault), onVault)
        CtrlSquareButton(Icons.Outlined.Delete, stringResource(R.string.delete), onDelete)
        CtrlSquareButton(Icons.Outlined.MoreVert, stringResource(R.string.more), onMore)
    }
}

// ---------- Tabs ----------

/** The Photos tab: everything, newest first, grouped by day, with "On this day" at the top. */
@Composable
fun PhotosScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    MediaAccess { limited, pickMore ->
        val library by c.visible.collectAsStateWithLifecycle()
        val items = library.items
        val loading = library.loading
        // While the library is read for the first time, the newest photos show as soon as they're in.
        val firstRows by c.visibleFirstRows.collectAsStateWithLifecycle()
        val shown = if (loading && items.isEmpty()) firstRows else items
        val settings by c.settings.settings.collectAsStateWithLifecycle()
        val memories = rememberOnThisDay(items, settings.onThisDay)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TabTitle(stringResource(R.string.tab_photos)) {
                CtrlSquareButton(Icons.Outlined.Search, stringResource(R.string.search), { nav.navigate(SearchRoute) })
                CtrlSquareButton(Icons.Outlined.GridView, stringResource(R.string.grid_size), {
                    scope.launch { c.settings.update { s -> s.copy(columns = if (s.columns >= 5) 3 else s.columns + 1) } }
                })
            }
            if (items.isEmpty() && !loading) {
                if (limited) {
                    MessageView(
                        Icons.Outlined.Photo, stringResource(R.string.limited_access), stringResource(R.string.limited_empty),
                        actionLabel = stringResource(R.string.choose_photos), onAction = pickMore,
                    )
                } else {
                    MessageView(Icons.Outlined.Photo, stringResource(R.string.no_photos), stringResource(R.string.no_photos_body))
                }
            } else {
                MediaGrid(shown, Modifier.weight(1f)) {
                    if (limited) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "limited") {
                            Row(
                                Modifier.padding(horizontal = Gutter, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(CtrlColors.Surface).clickable(onClick = pickMore).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.limited_access), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.choose_more), style = CtrlType.Status, color = CtrlColors.AccentText)
                            }
                        }
                    }
                    if (settings.onThisDay && memories.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "memories") { Memories(memories) { nav.navigate(OnThisDayRoute) } }
                    }
                }
            }
        }
    }
}

/**
 * Today's memories (photos and videos from this date in earlier years, each by the day it was taken where it was
 * taken), worked out off the main thread and again when the library or the date changes.
 */
@Composable
fun rememberOnThisDay(items: List<MediaItem>, enabled: Boolean = true): List<Pair<MediaItem, LocalDate>> {
    val c = LocalContext.current.container
    var tick by remember { mutableIntStateOf(0) }
    // Coming back the next morning shows the new day.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val today = remember(tick) { LocalDate.now() }
    val result by produceState(emptyList<Pair<MediaItem, LocalDate>>(), items, today, enabled) {
        value = if (enabled) c.photoInfo.onThisDay(items, today) else emptyList()
    }
    return result
}

/** The "On this day" row at the top of Photos: up to 20 memories, and See all for the rest. */
@Composable
private fun Memories(memories: List<Pair<MediaItem, LocalDate>>, onSeeAll: () -> Unit) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val today = LocalDate.now()
    val shown = memories.take(20)
    val items = shown.map { it.first }
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.on_this_day), Modifier.weight(1f))
            Text(
                stringResource(R.string.see_all), style = CtrlType.Status, color = CtrlColors.AccentText,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowItems(shown, key = { it.first.id }) { (item, day) ->
                Box(Modifier.size(132.dp, 176.dp).clip(RoundedCornerShape(18.dp)).clickable {
                    c.viewerList.value = items
                    c.viewerStart.value = items.indexOf(item)
                    c.viewerSlideshow.value = false
                    nav.navigate(ViewerRoute)
                }) {
                    Thumb(item, 384, Modifier.fillMaxSize())
                    val years = OnThisDay.yearsAgo(day, today)
                    Text(
                        pluralStringResource(R.plurals.years_ago_count, years, years),
                        style = CtrlType.LabelStrong, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    )
                }
            }
        }
    }
}

/** On this day as a page: every memory for today, grouped by year, newest first. */
@Composable
fun OnThisDayScreen() {
    val c = LocalContext.current.container
    val items = c.visible.collectAsStateWithLifecycle().value.items
    val memories = rememberOnThisDay(items)
    val today = LocalDate.now()
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }
    val sections = remember(memories) {
        memories.groupBy { it.second.year }.map { (_, list) ->
            val day = list.first().second
            val years = OnThisDay.yearsAgo(day, today)
            "${day.format(fmt)} · ${c.context.resources.getQuantityString(R.plurals.years_ago_lower, years, years)}" to list.map { it.first }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.on_this_day_title))
        if (memories.isEmpty()) MessageView(Icons.Outlined.History, stringResource(R.string.on_this_day_empty), stringResource(R.string.on_this_day_empty_body))
        else MediaGrid(memories.map { it.first }, Modifier.weight(1f).navigationBarsPadding(), sections = sections)
    }
}

/** The Albums tab: collections (favourites, videos, vault, duplicates, storage) and every folder, pinned ones first. */
@Composable
fun AlbumsScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    MediaAccess { _, _ ->
        val items = c.visible.collectAsStateWithLifecycle().value.items
        val vault by c.photoVault.entries.collectAsStateWithLifecycle()
        val settings by c.settings.settings.collectAsStateWithLifecycle()
        val pinned = settings.pinnedAlbums
        val albums = remember(items, pinned) { pinnedFirst(c.media.albums(items), pinned) }
        var menuFor by remember { mutableStateOf<Album?>(null) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TabTitle(stringResource(R.string.tab_albums))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Gutter - 6.dp, end = Gutter - 6.dp, bottom = 120.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 6.dp)) {
                        CollectionRow(Icons.Outlined.Favorite, stringResource(R.string.favorites), items.count { it.favorite }) { nav.navigate(AlbumRoute("favorites")) }
                        CollectionRow(Icons.Outlined.Videocam, stringResource(R.string.videos), items.count { it.isVideo }) { nav.navigate(AlbumRoute("videos")) }
                        CollectionRow(Icons.Outlined.Lock, stringResource(R.string.vault), vault.size) { nav.navigate(VaultRoute) }
                        CollectionRow(Icons.Outlined.ContentCopy, stringResource(R.string.duplicates), null) { nav.navigate(DuplicatesRoute) }
                        CollectionRow(Icons.Outlined.History, stringResource(R.string.on_this_day_title), null) { nav.navigate(OnThisDayRoute) }
                        CollectionRow(Icons.Outlined.PieChart, stringResource(R.string.storage_title), null) { nav.navigate(StorageRoute) }
                        Spacer(Modifier.height(12.dp))
                        SectionLabel(stringResource(R.string.folders))
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(albums, key = { it.id }) { a ->
                    AlbumCard(a, pinned = a.id in pinned, onLongClick = { menuFor = a }) { nav.navigate(AlbumRoute("album", a.id, a.name)) }
                }
            }
        }
        menuFor?.let { a ->
            AlbumMenu(
                a, pinned = a.id in pinned, onDismiss = { menuFor = null },
                onPin = { on ->
                    menuFor = null
                    scope.launch { c.settings.update { s -> s.copy(pinnedAlbums = if (on) s.pinnedAlbums - a.id + a.id else s.pinnedAlbums - a.id) } }
                },
                onHide = {
                    menuFor = null
                    scope.launch { c.settings.update { s -> s.copy(hiddenAlbums = s.hiddenAlbums + a.id, pinnedAlbums = s.pinnedAlbums - a.id) } }
                },
            )
        }
    }
}

/** Pinned folders first, in the order they were pinned; then the rest in the usual order. */
internal fun pinnedFirst(albums: List<Album>, pinned: List<Long>): List<Album> {
    if (pinned.isEmpty()) return albums
    val byId = albums.associateBy { it.id }
    val first = pinned.mapNotNull { byId[it] }
    val ids = first.map { it.id }.toSet()
    return first + albums.filterNot { it.id in ids }
}

/** Long-press on a folder: pin it to the top of Albums, or hide it. */
@Composable
private fun AlbumMenu(a: Album, pinned: Boolean, onDismiss: () -> Unit, onPin: (Boolean) -> Unit, onHide: () -> Unit) {
    CtrlBottomSheet(onDismissRequest = onDismiss) {
        Text(a.name.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge, color = CtrlColors.Text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        GroupRow(
            stringResource(if (pinned) R.string.album_unpin else R.string.album_pin),
            stringResource(if (pinned) R.string.album_unpin_sub else R.string.album_pin_sub),
            icon = Icons.Outlined.PushPin,
            onClick = { onPin(!pinned) },
        )
        GroupRow(stringResource(R.string.album_hide), stringResource(R.string.album_hide_sub), icon = Icons.Outlined.VisibilityOff, onClick = onHide)
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
private fun CollectionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = CtrlColors.Text)
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text, modifier = Modifier.weight(1f))
        if (count != null) Text(count.toString(), style = CtrlType.Data, color = CtrlColors.Muted)
    }
}

@Composable
private fun AlbumCard(a: Album, pinned: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.padding(6.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.album_options))) {
        Box {
            Thumb(a.cover, 480, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)))
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin, stringResource(R.string.album_pinned), tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(5.dp).size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(a.name.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a.count.toString(), style = CtrlType.Data, color = CtrlColors.Muted)
    }
}

/** One album, favourites or videos. */
@Composable
fun AlbumScreen(route: AlbumRoute) {
    val c = LocalContext.current.container
    val items = c.visible.collectAsStateWithLifecycle().value.items
    val list = remember(items, route) {
        when (route.kind) {
            "favorites" -> items.filter { it.favorite }
            "videos" -> items.filter { it.isVideo }
            else -> items.filter { it.albumId == route.albumId }
        }
    }
    val title = when (route.kind) {
        "favorites" -> stringResource(R.string.favorites)
        "videos" -> stringResource(R.string.videos)
        else -> route.title
    }
    val nav = LocalNavigator.current
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(title) {
            if (list.any { !it.isVideo }) {
                CtrlSquareButton(Icons.Outlined.Slideshow, stringResource(R.string.slideshow), {
                    c.viewerList.value = list.filterNot { it.isVideo }
                    c.viewerStart.value = 0
                    c.viewerSlideshow.value = true
                    nav.navigate(ViewerRoute)
                })
            }
        }
        if (list.isEmpty()) MessageView(Icons.Outlined.PhotoLibrary, stringResource(R.string.empty_album))
        else MediaGrid(list, Modifier.weight(1f).navigationBarsPadding(), groupByDay = route.kind != "album")
    }
}

/** Search by file name, folder, or date ("2024", "june", "12 november"). */
@Composable
fun SearchScreen() {
    val c = LocalContext.current.container
    val items = c.visible.collectAsStateWithLifecycle().value.items
    var query by remember { mutableStateOf("") }
    /** The query [shown] was last worked out for; "No results" waits for it, so it doesn't flash while matching. */
    var searched by remember { mutableStateOf("") }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMMM yyyy EEEE") }
    // The whole library is matched off the main thread, so typing stays smooth with many photos. The last results
    // stay on screen until the new ones are ready.
    var shown by remember { mutableStateOf(emptyList<MediaItem>()) }
    LaunchedEffect(items, query) {
        val q = query.trim().lowercase()
        shown = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            val zone = ZoneId.systemDefault()
            items.filter {
                it.name.lowercase().contains(q) || it.album.lowercase().contains(q) || dayOf(it.date, zone).format(fmt).lowercase().contains(q)
            }
        }
        searched = query
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.search))
        CtrlTextField(
            query, { query = it },
            Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
        )
        if (query.isNotBlank() && shown.isEmpty() && searched.isNotBlank()) MessageView(Icons.Outlined.Search, stringResource(R.string.no_results))
        else MediaGrid(shown, Modifier.weight(1f).navigationBarsPadding())
    }
}

/** Duplicate and near-identical photos, found on the phone. Keep the best one of each group. */
@Composable
fun DuplicatesScreen() {
    val context = LocalContext.current
    val c = context.container
    val items = c.visible.collectAsStateWithLifecycle().value.items
    var progress by remember { mutableStateOf(0f) }
    var groups by remember { mutableStateOf<List<List<MediaItem>>?>(null) }
    val request = rememberMediaRequest()
    LaunchedEffect(items.size) {
        // Thumbnails read for hashing aren't kept in the grid's thumbnail memory, and are freed right after.
        groups = Duplicates.find(items, { c.media.uncachedThumbnail(it, 96) }, { progress = it }, recycleThumbs = true)
    }
    val px = with(LocalDensity.current) { 110.dp.roundToPx() }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.duplicates))
        val g = groups
        when {
            g == null -> Column(Modifier.padding(Gutter)) {
                Text(stringResource(R.string.finding_duplicates), color = CtrlColors.Text)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = CtrlColors.Accent, trackColor = CtrlColors.ProgressTrack)
            }
            g.isEmpty() -> MessageView(Icons.Outlined.ContentCopy, stringResource(R.string.no_duplicates))
            else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(Gutter)) {
                g.forEachIndexed { i, group ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "g$i") {
                        Row(Modifier.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pluralStringResource(R.plurals.similar_count, group.size, group.size), style = MaterialTheme.typography.titleSmall, color = CtrlColors.Text, modifier = Modifier.weight(1f))
                            CtrlButton(stringResource(R.string.keep_best), { request(MediaRequests.delete(context, group.drop(1))) })
                        }
                    }
                    items(group, key = { "d${it.id}" }) { item ->
                        Box(Modifier.padding(2.dp).aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
                            Thumb(item, px, Modifier.fillMaxSize())
                            if (item == group.first()) Text(stringResource(R.string.best), style = CtrlType.LabelStrong, color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(CtrlColors.Accent.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The vault, behind the app PIN when the lock is on. */
@Composable
fun VaultGate() {
    val c = LocalContext.current.container
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    if (settings.lockVault && c.vault.enabled) AppLockGate { VaultScreen() } else VaultScreen()
}

@Composable
private fun VaultScreen() {
    val context = LocalContext.current
    val c = context.container
    val entries by c.photoVault.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<VaultEntry?>(null) }
    val px = with(LocalDensity.current) { 120.dp.roundToPx() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { c.photoVault.closeCache() } }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.vault))
        if (entries.isEmpty()) {
            MessageView(Icons.Outlined.Lock, stringResource(R.string.vault_empty), stringResource(R.string.vault_body))
        } else {
            Text(stringResource(R.string.vault_hint), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries, key = { it.id }) { e ->
                    val bmp by produceState<android.graphics.Bitmap?>(null, e.id) { value = c.photoVault.thumbnail(e, px) }
                    Box(Modifier.aspectRatio(1f).background(CtrlColors.Raised).clickable { open = e }) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        if (e.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
    var confirmDelete by remember { mutableStateOf<VaultEntry?>(null) }
    open?.let { e ->
        VaultViewer(e, onClose = { open = null }, onRestore = { scope.launch { c.photoVault.restore(e); open = null } }, onDelete = { confirmDelete = e })
    }
    confirmDelete?.let { e ->
        com.vknxworks.galleryctrl.ui.components.ConfirmDialog(
            stringResource(R.string.vault_delete_title), stringResource(R.string.vault_delete_body), stringResource(R.string.vault_delete),
            onDismiss = { confirmDelete = null },
        ) { scope.launch { c.photoVault.remove(e) }; confirmDelete = null; open = null }
    }
}

