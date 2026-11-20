package com.vknxworks.galleryctrl.media

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What GalleryCtrl reads from the photos themselves for On this day: the day they were taken where they were taken.
 * Read from the library's own files (MediaStore and EXIF dates) only, kept in memory only and never written
 * anywhere. Photo locations aren't read here (only a photo's own Info shows its location).
 * Vault items aren't in the library, so they never appear here.
 */
class PhotoInfo(private val context: Context) {
    /** Item id -> (what it was read from, the result). Re-read when a photo changes. */
    private val days = ConcurrentHashMap<Long, Pair<Long, LocalDate>>()

    private fun stamp(item: MediaItem) = item.date * 31 + item.size

    // ---------- On this day ----------

    /** Today's memories: photos and videos from this date in earlier years, newest year first. */
    suspend fun onThisDay(items: List<MediaItem>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<MediaItem, LocalDate>> =
        withContext(Dispatchers.IO) {
            // The photos that could match have their dates read from the files, a few at a time instead of one
            // after the other. That only fills the memory of days below: the selection itself is unchanged.
            val unread = items.filter { OnThisDay.mightMatch(it.date, today, zone) && days[it.id]?.first != stamp(it) }
            if (unread.size > 1) {
                val next = AtomicInteger(0)
                coroutineScope {
                    repeat(minOf(READERS, unread.size)) {
                        launch {
                            while (true) {
                                val i = next.getAndIncrement()
                                if (i >= unread.size) break
                                ensureActive()
                                localDay(unread[i], zone)
                            }
                        }
                    }
                }
            }
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

    private companion object {
        /** How many photos have their dates read at the same time. */
        const val READERS = 4
    }

    /** Forgets everything read (the app locked). */
    fun clear() {
        days.clear()
    }
}
