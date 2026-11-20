package com.vknxworks.galleryctrl.media

/**
 * The grouping step of the duplicate finder, on plain numbers so it can be tested: which photos are similar
 * enough to be shown as one group.
 *
 * Photo `j` joins the group of an earlier photo `i` when their 64-bit hashes differ in at most `maxDistance`
 * bits and no colour value differs by more than `maxColor`. Photos are taken in order; a photo that already
 * joined a group neither starts one nor joins another.
 *
 * [group] gives exactly the groups [groupAllPairs] gives, without comparing every photo with every other one:
 * two hashes that differ in at most `d` bits are identical in at least one of `d + 1` slices of the hash (the
 * differing bits can't touch them all), so only photos that share a slice with `i` have to be looked at.
 */
object DuplicateGroups {
    /** Colour values per photo: a 3×3 grid of red, green and blue. */
    const val COLORS = 27

    /**
     * Groups of 2 or more similar photos as indexes into [hashes], in the order they are found; within a group
     * the first photo first, then the others in rising order. [colors] holds [COLORS] bytes per photo.
     */
    fun group(hashes: LongArray, colors: ByteArray, maxDistance: Int, maxColor: Int, checkpoint: () -> Unit = {}): List<IntArray> {
        val n = hashes.size
        require(colors.size == n * COLORS)
        val slices = maxDistance + 1
        // Slices must fit in 32 bits (so at least 2 of them) and be wide enough to be worth it. The app's own
        // maxDistance of 4 gives 5 slices of 12 or 13 bits.
        if (maxDistance < 1 || slices > 8 || n < 2) return groupAllPairs(hashes, colors, maxDistance, maxColor, checkpoint)

        // Slice s covers bits [from[s], from[s + 1]).
        val from = IntArray(slices + 1) { it * 64 / slices }
        fun slice(hash: Long, s: Int): Long {
            val width = from[s + 1] - from[s]
            return (hash ushr from[s]) and ((1L shl width) - 1)
        }
        // Per slice: every photo as (slice value, index) in one long, sorted, so equal values sit together in
        // rising index order.
        val sorted = Array(slices) { s ->
            LongArray(n) { i -> (slice(hashes[i], s) shl 32) or i.toLong() }.also { it.sort() }
        }

        val used = BooleanArray(n)
        val out = ArrayList<IntArray>()
        var candidates = IntArray(64)
        var members = IntArray(16)
        for (i in 0 until n) {
            if (used[i]) continue
            if (i and 255 == 0) checkpoint()
            // Everything after i that shares a slice with it.
            var count = 0
            var tooMany = false
            for (s in 0 until slices) {
                val keys = sorted[s]
                val value = slice(hashes[i], s)
                // First entry with this slice value and an index above i.
                var p = lowerBound(keys, (value shl 32) or (i + 1).toLong())
                while (p < n && (keys[p] ushr 32) == value) {
                    if (count == candidates.size) candidates = candidates.copyOf(count * 2)
                    candidates[count++] = (keys[p] and 0xFFFFFFFFL).toInt()
                    p++
                    // As many as simply looking at every later photo: do that instead.
                    if (count >= n - i - 1) { tooMany = true; break }
                }
                if (tooMany) break
            }
            var size = 0
            fun consider(j: Int) {
                if (used[j]) return
                if (java.lang.Long.bitCount(hashes[i] xor hashes[j]) <= maxDistance && colorDistance(colors, i, j) <= maxColor) {
                    if (size == members.size) members = members.copyOf(size * 2)
                    members[size++] = j
                    used[j] = true
                }
            }
            if (tooMany) {
                for (j in i + 1 until n) consider(j)
            } else {
                java.util.Arrays.sort(candidates, 0, count)
                var last = -1
                for (k in 0 until count) {
                    val j = candidates[k]
                    if (j == last) continue
                    last = j
                    consider(j)
                }
            }
            if (size > 0) out += IntArray(size + 1) { if (it == 0) i else members[it - 1] }
        }
        return out
    }

    /** The same groups by comparing every photo with every later one: the reference [group] is tested against. */
    fun groupAllPairs(hashes: LongArray, colors: ByteArray, maxDistance: Int, maxColor: Int, checkpoint: () -> Unit = {}): List<IntArray> {
        val n = hashes.size
        val used = BooleanArray(n)
        val out = ArrayList<IntArray>()
        for (i in 0 until n) {
            if (used[i]) continue
            if (i and 255 == 0) checkpoint()
            val group = ArrayList<Int>()
            group += i
            for (j in i + 1 until n) {
                if (used[j]) continue
                if (java.lang.Long.bitCount(hashes[i] xor hashes[j]) <= maxDistance && colorDistance(colors, i, j) <= maxColor) {
                    group += j
                    used[j] = true
                }
            }
            if (group.size > 1) out += group.toIntArray()
        }
        return out
    }

    /** Largest per-channel difference between the colour grids of photos [a] and [b]. */
    fun colorDistance(colors: ByteArray, a: Int, b: Int): Int {
        var max = 0
        val pa = a * COLORS
        val pb = b * COLORS
        for (k in 0 until COLORS) {
            val d = (colors[pa + k].toInt() and 0xFF) - (colors[pb + k].toInt() and 0xFF)
            val abs = if (d < 0) -d else d
            if (abs > max) max = abs
        }
        return max
    }

    /** Index of the first element of sorted [keys] that is >= [key]. */
    private fun lowerBound(keys: LongArray, key: Long): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
