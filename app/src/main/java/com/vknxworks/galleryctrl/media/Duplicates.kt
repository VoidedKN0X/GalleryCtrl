package com.vknxworks.galleryctrl.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Finds duplicate and near-identical photos on the phone with a 64-bit difference hash (dHash) of each
 * thumbnail: bursts, re-saved copies, screenshots taken twice. No AI model, nothing leaves the phone.
 */
object Duplicates {
    /** Groups of 2+ similar photos, best (largest) first in each group. */
    suspend fun find(
        items: List<MediaItem>,
        thumb: suspend (MediaItem) -> Bitmap?,
        onProgress: (Float) -> Unit,
        maxDistance: Int = 4,
    ): List<List<MediaItem>> = withContext(Dispatchers.Default) {
        val photos = items.filterNot { it.isVideo }
        val hashes = ArrayList<Triple<MediaItem, Long, IntArray>>(photos.size)
        photos.forEachIndexed { i, item ->
            coroutineContext.ensureActive()
            thumb(item)?.let { hashes += Triple(item, dHash(it), colorGrid(it)) }
            if (i % 20 == 0) onProgress(i / photos.size.toFloat())
        }
        onProgress(1f)
        // Photos close in time are the likely duplicates; compare each with its neighbours by hash buckets.
        val used = BooleanArray(hashes.size)
        val groups = ArrayList<List<MediaItem>>()
        for (i in hashes.indices) {
            if (used[i]) continue
            val group = arrayListOf(hashes[i].first)
            for (j in i + 1 until hashes.size) {
                if (used[j]) continue
                // Same structure (dHash) and the same colours (a 3×3 grid of average colours).
                if (java.lang.Long.bitCount(hashes[i].second xor hashes[j].second) <= maxDistance && colorDistance(hashes[i].third, hashes[j].third) <= MAX_COLOR) {
                    group += hashes[j].first
                    used[j] = true
                }
            }
            if (group.size > 1) groups += group.sortedByDescending { it.width.toLong() * it.height }
        }
        groups.sortedByDescending { it.size }
    }

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

    /** Average colour of a 3×3 grid, as 27 channel values. */
    private fun colorGrid(src: Bitmap): IntArray {
        val b = Bitmap.createScaledBitmap(src, 3, 3, true)
        val out = IntArray(27)
        for (i in 0 until 9) {
            val c = b.getPixel(i % 3, i / 3)
            out[i * 3] = Color.red(c); out[i * 3 + 1] = Color.green(c); out[i * 3 + 2] = Color.blue(c)
        }
        if (b !== src) b.recycle()
        return out
    }

    /** Largest per-channel difference between two colour grids. */
    private fun colorDistance(a: IntArray, b: IntArray): Int = a.indices.maxOf { kotlin.math.abs(a[it] - b[it]) }

    private fun luma(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
}
