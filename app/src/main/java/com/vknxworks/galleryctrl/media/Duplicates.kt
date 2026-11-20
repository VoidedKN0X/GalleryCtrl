package com.vknxworks.galleryctrl.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds duplicate and near-identical photos on the phone with a 64-bit difference hash (dHash) of each
 * thumbnail: bursts, re-saved copies, screenshots taken twice. No AI model, nothing leaves the phone.
 */
object Duplicates {
    /** What was worked out from one photo's thumbnail, and which version of the photo it was worked out from. */
    private class Hashed(val stamp: Long, val hash: Long, val colors: ByteArray)

    /**
     * Hashes of photos already looked at (item id -> hash), so looking again after deleting a few duplicates
     * doesn't read every thumbnail again. In memory only, never written anywhere; forgotten when the app locks,
     * and a photo that changed (date, size or dimensions) is read again.
     */
    private val known = ConcurrentHashMap<Long, Hashed>()

    private fun stamp(item: MediaItem) = ((item.date * 31 + item.size) * 31 + item.width) * 31 + item.height

    /** Forgets every hash (the app locked). */
    fun clear() {
        known.clear()
    }

    /**
     * Groups of 2+ similar photos, best (largest) first in each group.
     *
     * [thumb] gives a photo's thumbnail. With [recycleThumbs] each thumbnail is freed as soon as it has been
     * hashed; only pass true when [thumb] hands out bitmaps nothing else uses.
     */
    suspend fun find(
        items: List<MediaItem>,
        thumb: suspend (MediaItem) -> Bitmap?,
        onProgress: (Float) -> Unit,
        maxDistance: Int = 4,
        recycleThumbs: Boolean = false,
    ): List<List<MediaItem>> = withContext(Dispatchers.Default) {
        val photos = items.filterNot { it.isVideo }
        // One slot per photo, in library order; null where the thumbnail couldn't be read.
        val hashed = arrayOfNulls<Hashed>(photos.size)
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val progressLock = Any()
        var reported = -1f
        coroutineScope {
            repeat(WORKERS) {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= photos.size) break
                        ensureActive()
                        val item = photos[i]
                        val stamp = stamp(item)
                        var h = known[item.id]?.takeIf { it.stamp == stamp }
                        if (h == null) {
                            val bmp = thumb(item)
                            if (bmp != null) {
                                h = Hashed(stamp, dHash(bmp), colorGrid(bmp))
                                if (recycleThumbs) bmp.recycle()
                                known[item.id] = h
                            }
                        }
                        hashed[i] = h
                        val n = done.incrementAndGet()
                        if (n % 20 == 1) {
                            val p = (n - 1) / photos.size.toFloat()
                            // Several threads report: never let the bar step back.
                            synchronized(progressLock) { if (p > reported) { reported = p; onProgress(p) } }
                        }
                    }
                }
            }
        }
        onProgress(1f)
        // Hashes of photos that are no longer in the library aren't needed any more.
        val ids = HashSet<Long>(photos.size * 2)
        photos.forEach { ids += it.id }
        known.keys.retainAll(ids)

        // The photos that have a hash, still in library order, as plain arrays for the grouping.
        val kept = ArrayList<MediaItem>(photos.size)
        for (i in photos.indices) if (hashed[i] != null) kept += photos[i]
        val hashes = LongArray(kept.size)
        val colors = ByteArray(kept.size * DuplicateGroups.COLORS)
        var k = 0
        for (i in photos.indices) {
            val h = hashed[i] ?: continue
            hashes[k] = h.hash
            h.colors.copyInto(colors, k * DuplicateGroups.COLORS)
            k++
        }
        // Same structure (dHash) and the same colours (a 3×3 grid of average colours).
        val groups = DuplicateGroups.group(hashes, colors, maxDistance, MAX_COLOR) { ensureActive() }
            .map { g -> g.map { kept[it] }.sortedByDescending { it.width.toLong() * it.height } }
        groups.sortedByDescending { it.size }
    }

    /** How many thumbnails are hashed at the same time. */
    private const val WORKERS = 4

    private fun dHash(src: Bitmap): Long {
        val b = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(b.getPixel(x, y)) > luma(b.getPixel(x + 1, y))) hash = hash or (1L shl bit)
            bit++
        }
        if (b !== src) b.recycle()
        return hash
    }

    private const val MAX_COLOR = 18

    /** Average colour of a 3×3 grid, as 27 channel values (0 to 255 each). */
    private fun colorGrid(src: Bitmap): ByteArray {
        val b = Bitmap.createScaledBitmap(src, 3, 3, true)
        val out = ByteArray(DuplicateGroups.COLORS)
        for (i in 0 until 9) {
            val c = b.getPixel(i % 3, i / 3)
            out[i * 3] = Color.red(c).toByte(); out[i * 3 + 1] = Color.green(c).toByte(); out[i * 3 + 2] = Color.blue(c).toByte()
        }
        if (b !== src) b.recycle()
        return out
    }

    private fun luma(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
}
