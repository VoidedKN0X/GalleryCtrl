package com.vknxworks.galleryctrl.media

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One photo or video in the phone's media library. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val album: String,
    val albumId: Long,
    val relativePath: String,
    /** Date taken (or added) in ms. */
    val date: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val favorite: Boolean,
    val trashed: Boolean,
    val trashExpires: Long,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

data class Album(val id: Long, val name: String, val cover: MediaItem, val count: Int)

/**
 * The phone's photos and videos through Android's MediaStore, refreshed when anything changes.
 * GalleryCtrl never copies your library anywhere; it reads it in place.
 */
class MediaRepository(private val context: Context, private val scope: CoroutineScope) {
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()
    val loading = MutableStateFlow(true)

    /**
     * The newest [FIRST_ROWS] photos and videos, handed out as soon as they've been read while the rest of the
     * library is still being read at the very first load, so the Photos tab has something to show sooner. Only
     * ever the start of what [items] gets a moment later; empty before that and again once [items] is filled.
     * Everything that needs the whole library (albums, search, On this day, duplicates) keeps using [items].
     */
    private val _firstRows = MutableStateFlow<List<MediaItem>>(emptyList())
    val firstRows: StateFlow<List<MediaItem>> = _firstRows.asStateFlow()

    private val thumbs = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Thumbnails are read by a few threads at a time, so a fast scroll doesn't start hundreds of reads at once. */
    private val thumbLoader = Dispatchers.IO.limitedParallelism(THUMB_LOADERS)
    private var reloadJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(debounce = true)
    }

    /** Whether [observer] is registered: the main screen and the picker may both start the library. */
    private var observing = false

    fun start() {
        if (!observing) {
            context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
            observing = true
        }
        reload()
    }

    fun reload(debounce: Boolean = false) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            if (debounce) delay(400)
            // Only the very first load hands out its first rows early; a later reload replaces the list in one go.
            val first = loading.value && _items.value.isEmpty()
            val publishFirstRows: (List<MediaItem>) -> Unit = { rows -> if (loading.value) _firstRows.value = rows }
            _items.value = withContext(Dispatchers.IO) {
                query(trashedOnly = false, onFirstRows = if (first) publishFirstRows else null)
            }
            loading.value = false
            _firstRows.value = emptyList()
        }
    }

    /** [onFirstRows] gets a copy of the first [FIRST_ROWS] rows as soon as they're read, when there are more. */
    private fun query(trashedOnly: Boolean, onFirstRows: ((List<MediaItem>) -> Unit)? = null): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val args = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
            )
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashedOnly) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<MediaItem>()
        runCatching {
            context.contentResolver.query(uri, projection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iBucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val iFav = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
                val iTrash = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        name = c.getString(iName) ?: "",
                        mime = c.getString(iMime) ?: if (video) "video/*" else "image/*",
                        album = c.getString(iBucket) ?: "",
                        albumId = c.getLong(iBucketId),
                        relativePath = c.getString(iPath) ?: "",
                        date = taken,
                        size = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        durationMs = c.getLong(iDur),
                        favorite = c.getInt(iFav) == 1,
                        trashed = c.getInt(iTrash) == 1,
                        trashExpires = c.getLong(iExp) * 1000,
                    )
                    if (onFirstRows != null && out.size == FIRST_ROWS && !c.isLast) onFirstRows(ArrayList(out))
                }
            }
        }
        return out
    }

    fun albums(list: List<MediaItem>): List<Album> =
        list.groupBy { it.albumId }.map { (id, l) -> Album(id, l.first().album, l.first(), l.size) }
            .sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.count })

    /** The thumbnail if it's already in memory, without waiting; null when it still has to be loaded. */
    fun cachedThumbnail(item: MediaItem, px: Int): Bitmap? = thumbs.get("${item.id}:$px")

    /**
     * A cached thumbnail (Android's own thumbnail store), on a background thread. At most [THUMB_LOADERS] are read
     * at a time; a request that's cancelled while it waits its turn (its tile scrolled away) is never started, and one
     * that's cancelled while Android is reading is told to stop.
     */
    suspend fun thumbnail(item: MediaItem, px: Int): Bitmap? {
        val key = "${item.id}:$px"
        thumbs.get(key)?.let { return it }
        return load(item, px)?.also { thumbs.put(key, it) }
    }

    /**
     * A thumbnail read fresh for the caller alone and not kept in memory here: for the duplicate finder, which
     * looks at every photo once and would otherwise push the grid's thumbnails out of the cache. The caller may
     * recycle it. Read the same way, and as few at a time, as [thumbnail].
     */
    suspend fun uncachedThumbnail(item: MediaItem, px: Int): Bitmap? = load(item, px)

    private suspend fun load(item: MediaItem, px: Int): Bitmap? =
        withContext(thumbLoader) {
            val signal = CancellationSignal()
            // Waits for this request to be cancelled; resumes right on the cancelling thread and passes it on.
            val watcher = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    signal.cancel()
                }
            }
            try {
                runCatching { context.contentResolver.loadThumbnail(item.uri, Size(px, px), signal) }.getOrNull()
            } finally {
                watcher.cancel()
            }
        }

    fun stop() {
        context.contentResolver.unregisterContentObserver(observer)
        observing = false
    }

    private companion object {
        const val THUMB_LOADERS = 4
        const val FIRST_ROWS = 500
    }
}
