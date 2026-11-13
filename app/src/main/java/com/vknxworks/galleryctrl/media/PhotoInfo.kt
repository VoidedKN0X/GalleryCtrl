package com.vknxworks.galleryctrl.media

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * What GalleryCtrl reads from the photos themselves for On this day and the map: the day they were taken where they
 * were taken, and their GPS position. Read from the library's own files (MediaStore and EXIF) only, kept in memory
 * only and never written anywhere, so nothing about where your photos were taken is stored by GalleryCtrl.
 * Vault items aren't in the library, so they never appear here.
 */
class PhotoInfo(private val context: Context) {
    /** Item id -> (what it was read from, the result). Re-read when a photo changes. */
    private val days = ConcurrentHashMap<Long, Pair<Long, LocalDate>>()
    private val places = ConcurrentHashMap<Long, Pair<Long, GeoPoint?>>()

    private fun stamp(item: MediaItem) = item.date * 31 + item.size

    // ---------- On this day ----------

    /** Today's memories: photos and videos from this date in earlier years, newest year first. */
    suspend fun onThisDay(items: List<MediaItem>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<MediaItem, LocalDate>> =
        withContext(Dispatchers.IO) {
            OnThisDay.select(items, { it.date }, today, zone) { localDay(it, zone) }
        }

    private fun localDay(item: MediaItem, zone: ZoneId): LocalDate {
        days[item.id]?.takeIf { it.first == stamp(item) }?.let { return it.second }
        var dateTime: String? = null
        var offset: String? = null
        if (!item.isVideo) runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { s ->
                val x = ExifInterface(s)
                dateTime = x.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                offset = x.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: x.getAttribute(ExifInterface.TAG_OFFSET_TIME)
            }
        }
        val d = OnThisDay.localDate(item.date, dateTime, offset, zone)
        days[item.id] = stamp(item) to d
        return d
    }

    // ---------- Map ----------

    private val _scanned = MutableStateFlow(0 to 0)
    /** (photos checked, photos to check) while [places] reads locations. */
    val scanned: StateFlow<Pair<Int, Int>> = _scanned.asStateFlow()

    /**
     * Photos with a GPS position, read from their EXIF (Android only hands it out with "Access media location").
     * [onProgress] gets what's found so far, so pins appear while the rest is still being read.
     */
    suspend fun places(items: List<MediaItem>, onProgress: (List<Pair<GeoPoint, MediaItem>>) -> Unit): List<Pair<GeoPoint, MediaItem>> =
        withContext(Dispatchers.IO) {
            val photos = items.filter { !it.isVideo }
            val found = ArrayList<Pair<GeoPoint, MediaItem>>()
            var last = System.nanoTime()
            photos.forEachIndexed { i, item ->
                coroutineContext.ensureActive()
                place(item)?.let { found += it to item }
                if (System.nanoTime() - last > 400_000_000L) {
                    _scanned.value = (i + 1) to photos.size
                    onProgress(found.toList())
                    last = System.nanoTime()
                }
            }
            _scanned.value = photos.size to photos.size
            found
        }

    private fun place(item: MediaItem): GeoPoint? {
        places[item.id]?.takeIf { it.first == stamp(item) }?.let { return it.second }
        val p = runCatching {
            val uri = MediaStore.setRequireOriginal(item.uri)
            context.contentResolver.openInputStream(uri)?.use { s -> ExifInterface(s).latLong }
        }.getOrNull()?.let { GeoPoint(it[0], it[1]) }?.takeIf { it.isValid }
        places[item.id] = stamp(item) to p
        return p
    }

    /** Forgets everything read (the app locked). */
    fun clear() {
        days.clear()
        places.clear()
        _scanned.value = 0 to 0
    }
}
