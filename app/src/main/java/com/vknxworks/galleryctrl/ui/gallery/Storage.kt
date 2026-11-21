package com.vknxworks.galleryctrl.ui.gallery

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.StorageStats
import com.vknxworks.galleryctrl.ui.components.MessageView
import com.vknxworks.galleryctrl.ui.components.ScreenTopBar
import com.vknxworks.galleryctrl.ui.components.SectionLabel
import com.vknxworks.galleryctrl.ui.components.StatRow
import com.vknxworks.galleryctrl.ui.components.StatTile
import com.vknxworks.galleryctrl.ui.nav.LocalNavigator
import com.vknxworks.galleryctrl.ui.nav.ViewerRoute
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import com.vknxworks.galleryctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone's free and total space, from Android (no permission needed). Null when Android won't say. */
private fun phoneSpace(context: Context): Pair<Long, Long>? = runCatching {
    val stats = context.getSystemService(StorageStatsManager::class.java)
    stats.getFreeBytes(StorageManager.UUID_DEFAULT) to stats.getTotalBytes(StorageManager.UUID_DEFAULT)
}.getOrNull()

/**
 * Albums > Storage: how much space the library takes, split by photos and videos, by folder and by file type, with the
 * largest files. Worked out on the phone from what Android's media library already knows (sizes), nothing is read
 * from the files themselves. Hidden folders count too: this is about the space they take.
 */
@Composable
fun StorageScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val items by c.media.items.collectAsStateWithLifecycle()
    val loading by c.media.loading.collectAsStateWithLifecycle()
    val vault by c.photoVault.entries.collectAsStateWithLifecycle()
    val stats by produceState<StorageStats?>(null, items) { value = withContext(Dispatchers.Default) { StorageStats.of(items) } }
    val space by produceState<Pair<Long, Long>?>(null) { value = withContext(Dispatchers.IO) { phoneSpace(context) } }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.storage_title))
        val s = stats
        when {
            s == null || (loading && items.isEmpty()) -> Text(
                stringResource(R.string.storage_counting), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted,
                modifier = Modifier.padding(Gutter),
            )
            s.totalCount == 0 -> MessageView(Icons.Outlined.PieChart, stringResource(R.string.no_photos), stringResource(R.string.no_photos_body))
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "tiles") {
                    Column {
                        StatRow(
                            { m -> StatTile(stringResource(R.string.storage_library), size(s.totalBytes), m, pluralStringResource(R.plurals.storage_items, s.totalCount, s.totalCount)) },
                            { m ->
                                val sp = space
                                if (sp != null) {
                                    StatTile(stringResource(R.string.storage_phone_free), size(sp.first), m, stringResource(R.string.storage_of_total, size(sp.second)))
                                } else {
                                    StatTile(stringResource(R.string.storage_phone_free), "—", m)
                                }
                            },
                        )
                        StatRow(
                            { m -> StatTile(stringResource(R.string.storage_photos), size(s.photoBytes), m, pluralStringResource(R.plurals.storage_items, s.photoCount, s.photoCount)) },
                            { m -> StatTile(stringResource(R.string.storage_videos), size(s.videoBytes), m, pluralStringResource(R.plurals.storage_items, s.videoCount, s.videoCount)) },
                        )
                        StatRow(
                            { m ->
                                val bytes = vault.sumOf { it.size }
                                StatTile(stringResource(R.string.vault), size(bytes), m, pluralStringResource(R.plurals.storage_items, vault.size, vault.size))
                            },
                            { m ->
                                val sp = space
                                val share = if (sp != null && sp.second > sp.first) (s.totalBytes * 100.0 / (sp.second - sp.first)).coerceIn(0.0, 100.0) else null
                                StatTile(stringResource(R.string.storage_share), share?.let { "%.0f%%".format(it) } ?: "—", m, stringResource(R.string.storage_share_sub))
                            },
                        )
                    }
                }
                item(key = "folders_label") { Label(stringResource(R.string.storage_by_folder)) }
                items(s.folders.take(TOP), key = { "f" + it.name }) { f -> BarRow(f.name.ifBlank { "—" }, size(f.bytes), pluralStringResource(R.plurals.storage_items, f.count, f.count), f.bytes, s.totalBytes) }
                item(key = "types_label") { Label(stringResource(R.string.storage_by_type)) }
                items(s.types.take(TOP), key = { "t" + it.name }) { t -> BarRow(t.name, size(t.bytes), pluralStringResource(R.plurals.storage_items, t.count, t.count), t.bytes, s.totalBytes) }
                item(key = "largest_label") { Label(stringResource(R.string.storage_largest)) }
                items(s.largest, key = { "l" + it.id }) { item ->
                    LargestRow(item, size(item.size)) {
                        c.viewerList.value = s.largest
                        c.viewerStart.value = s.largest.indexOf(item).coerceAtLeast(0)
                        c.viewerSlideshow.value = false
                        nav.navigate(ViewerRoute)
                    }
                }
                item(key = "note") {
                    Text(
                        stringResource(R.string.storage_note), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted,
                        modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

private const val TOP = 12

@Composable
private fun Label(text: String) {
    SectionLabel(text, Modifier.padding(start = Gutter, end = Gutter, top = 20.dp, bottom = 6.dp))
}

/** A name with its size, and a bar for its share of the library. */
@Composable
private fun BarRow(name: String, value: String, sub: String, bytes: Long, total: Long) {
    val fraction = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(sub, style = CtrlType.Data, color = CtrlColors.Muted)
            Spacer(Modifier.width(10.dp))
            Text(value, style = CtrlType.Data, color = CtrlColors.Text)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CtrlColors.ProgressTrack)) {
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.005f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(CtrlColors.Accent))
        }
    }
}

@Composable
private fun LargestRow(item: MediaItem, size: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(item, 160, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.album, style = CtrlType.Data, color = CtrlColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(size, style = CtrlType.Data, color = CtrlColors.Text)
    }
}
