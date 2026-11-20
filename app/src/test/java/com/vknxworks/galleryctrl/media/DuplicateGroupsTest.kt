package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class DuplicateGroupsTest {
    private fun same(hashes: LongArray, colors: ByteArray, maxDistance: Int = 4, maxColor: Int = 18) {
        val expected = DuplicateGroups.groupAllPairs(hashes, colors, maxDistance, maxColor).map { it.toList() }
        val actual = DuplicateGroups.group(hashes, colors, maxDistance, maxColor).map { it.toList() }
        assertEquals(expected, actual)
    }

    /** [n] photos: random ones, with near-copies (a few flipped bits, slightly shifted colours) mixed in. */
    private fun library(r: Random, n: Int, copyRate: Double, maxFlips: Int, colorShift: Int): Pair<LongArray, ByteArray> {
        val hashes = LongArray(n)
        val colors = ByteArray(n * DuplicateGroups.COLORS)
        for (i in 0 until n) {
            if (i > 0 && r.nextDouble() < copyRate) {
                val src = r.nextInt(i)
                var h = hashes[src]
                repeat(r.nextInt(maxFlips + 1)) { h = h xor (1L shl r.nextInt(64)) }
                hashes[i] = h
                for (k in 0 until DuplicateGroups.COLORS) {
                    val v = (colors[src * DuplicateGroups.COLORS + k].toInt() and 0xFF) + r.nextInt(2 * colorShift + 1) - colorShift
                    colors[i * DuplicateGroups.COLORS + k] = v.coerceIn(0, 255).toByte()
                }
            } else {
                hashes[i] = r.nextLong()
                for (k in 0 until DuplicateGroups.COLORS) colors[i * DuplicateGroups.COLORS + k] = r.nextInt(256).toByte()
            }
        }
        return hashes to colors
    }

    @Test fun sameGroupsAsComparingEveryPair() {
        val r = Random(20261120)
        repeat(300) {
            val (h, c) = library(r, 1 + r.nextInt(400), r.nextDouble() * 0.6, 1 + r.nextInt(8), r.nextInt(30))
            same(h, c)
        }
    }

    @Test fun sameGroupsForOtherLimits() {
        val r = Random(7)
        repeat(200) {
            val (h, c) = library(r, 1 + r.nextInt(200), 0.5, 12, 25)
            same(h, c, maxDistance = r.nextInt(12), maxColor = r.nextInt(40))
        }
    }

    @Test fun manyIdenticalPhotos() {
        val r = Random(3)
        val n = 500
        val h = LongArray(n) { if (it % 7 == 0) r.nextLong() else 0x0123456789ABCDEFL }
        val c = ByteArray(n * DuplicateGroups.COLORS) { if (it / DuplicateGroups.COLORS % 11 == 0) 90 else 100 }
        same(h, c)
        same(LongArray(n), ByteArray(n * DuplicateGroups.COLORS))
    }

    @Test fun edgeBits() {
        // Differences only in the lowest and the highest bits, and exactly at the limit.
        val h = longArrayOf(0L, 1L, 1L shl 63, 0xFL, 0x1FL, -1L, -1L xor 0xF000000000000000uL.toLong(), 0x8000000000000001uL.toLong())
        same(h, ByteArray(h.size * DuplicateGroups.COLORS))
        same(longArrayOf(), ByteArray(0))
        same(longArrayOf(5L), ByteArray(DuplicateGroups.COLORS))
    }

    @Test fun bigLibrary() {
        val r = Random(99)
        val (h, c) = library(r, 6000, 0.3, 6, 20)
        same(h, c)
    }
}
