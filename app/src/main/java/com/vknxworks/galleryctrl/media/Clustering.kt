package com.vknxworks.galleryctrl.media

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** A position on Earth in degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    /** A real GPS fix: on Earth, and not the 0,0 some apps write when they have none. */
    val isValid: Boolean
        get() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}

/** Several items shown as one pin: where it's drawn (the middle of its items) and what's in it. */
data class Cluster<T>(val center: GeoPoint, val items: List<T>) {
    val size: Int get() = items.size
}

/**
 * Web Mercator maths (the projection of OpenStreetMap, Mapsforge and the plain grid map) and pin clustering.
 * World pixels: the whole world is a square of 256 · 2^zoom pixels, x to the east, y to the south.
 */
object Clustering {
    const val TILE = 256.0
    const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Double): Double = TILE * 2.0.pow(zoom)

    fun x(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun y(lat: Double, zoom: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(l) + 1.0 / kotlin.math.cos(l)) / PI) / 2.0 * worldSize(zoom)
    }

    fun lon(x: Double, zoom: Double): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Double): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /**
     * Groups [points] that would be drawn within [cellPx] pixels of each other at [zoom]: first into a grid of
     * [cellPx] cells, then neighbouring groups whose middles are still closer than [cellPx] are merged, so a pair
     * of photos on either side of a cell border doesn't show as two overlapping pins. Every item ends up in exactly
     * one cluster; the result is the same for the same input.
     */
    fun <T> cluster(points: List<Pair<GeoPoint, T>>, zoom: Double, cellPx: Double): List<Cluster<T>> {
        require(cellPx > 0)
        class Acc(var sx: Double, var sy: Double, val items: MutableList<T>, var n: Int) {
            val cx get() = sx / n
            val cy get() = sy / n
        }
        val cells = LinkedHashMap<Long, Acc>()
        for ((p, item) in points) {
            if (!p.isValid) continue
            val px = x(p.lon, zoom)
            val py = y(p.lat, zoom)
            val key = (floor(px / cellPx).toLong() shl 32) xor (floor(py / cellPx).toLong() and 0xFFFFFFFFL)
            val a = cells.getOrPut(key) { Acc(0.0, 0.0, ArrayList(), 0) }
            a.sx += px; a.sy += py; a.items += item; a.n++
        }
        // Merge groups whose middles are close, biggest first so small ones join the big pin next to them.
        val groups = cells.values.sortedWith(compareByDescending<Acc> { it.n }.thenBy { it.cx }.thenBy { it.cy }).toMutableList()
        val out = ArrayList<Acc>()
        for (g in groups) {
            val near = out.firstOrNull { o -> sqrt((o.cx - g.cx).let { it * it } + (o.cy - g.cy).let { it * it }) < cellPx }
            if (near != null) {
                near.sx += g.sx; near.sy += g.sy; near.items += g.items; near.n += g.n
            } else out += g
        }
        return out.map { Cluster(GeoPoint(lat(it.cy, zoom), lon(it.cx, zoom)), it.items) }
    }
}
