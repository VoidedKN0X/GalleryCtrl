package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ClusteringTest {
    private val brussels = GeoPoint(50.8467, 4.3525)
    private val antwerp = GeoPoint(51.2194, 4.4025)
    private val tokyo = GeoPoint(35.6762, 139.6503)

    @Test fun mercatorRoundTrip() {
        for (z in listOf(0.0, 3.5, 12.0)) {
            for (p in listOf(brussels, tokyo, GeoPoint(-33.86, 151.2), GeoPoint(0.0001, -179.9))) {
                assertEquals(p.lat, Clustering.lat(Clustering.y(p.lat, z), z), 1e-9)
                assertEquals(p.lon, Clustering.lon(Clustering.x(p.lon, z), z), 1e-9)
            }
        }
        // The middle of the world, and the edges.
        assertEquals(128.0, Clustering.x(0.0, 0.0), 1e-9)
        assertEquals(128.0, Clustering.y(0.0, 0.0), 1e-9)
        assertEquals(0.0, Clustering.y(90.0, 0.0), 1e-6) // Clamped to the Mercator limit.
    }

    @Test fun nearbyPhotosMergeWhenZoomedOut() {
        val pts = listOf(brussels to "b", antwerp to "a", tokyo to "t")
        val world = Clustering.cluster(pts, zoom = 3.0, cellPx = 60.0)
        assertEquals(2, world.size)
        assertEquals(setOf("a", "b"), world.single { it.size == 2 }.items.toSet())
        // Zoomed in on Belgium, Brussels and Antwerp (40 km apart) are separate pins.
        val close = Clustering.cluster(pts, zoom = 12.0, cellPx = 60.0)
        assertEquals(3, close.size)
    }

    @Test fun samePlaceAlwaysOnePin() {
        val pts = (1..50).map { GeoPoint(brussels.lat + it * 1e-6, brussels.lon) to it }
        for (z in listOf(0.0, 10.0, 18.0)) assertEquals(1, Clustering.cluster(pts, z, 48.0).size)
    }

    @Test fun cellBordersDontSplitAPair() {
        // Two points 2 px apart on either side of a grid line at zoom 10.
        val z = 10.0
        val cell = 50.0
        val borderX = 300 * cell
        val lat = Clustering.lat(400 * cell + 10, z)
        val a = GeoPoint(lat, Clustering.lon(borderX - 1, z))
        val b = GeoPoint(lat, Clustering.lon(borderX + 1, z))
        assertEquals(1, Clustering.cluster(listOf(a to 1, b to 2), z, cell).size)
    }

    @Test fun everyItemOnceAndCenterInside() {
        val r = Random(42)
        val pts = (0 until 2000).map { GeoPoint(r.nextDouble(-60.0, 70.0), r.nextDouble(-179.0, 179.0)) to it }
        for (z in listOf(1.0, 4.0, 9.0)) {
            val cs = Clustering.cluster(pts, z, 56.0)
            val all = cs.flatMap { it.items }
            assertEquals(pts.size, all.size)
            assertEquals(pts.size, all.toSet().size)
            for (c in cs) {
                val lats = c.items.map { pts[it].first.lat }
                val lons = c.items.map { pts[it].first.lon }
                assertTrue(c.center.lat >= lats.min() - 1e-6 && c.center.lat <= lats.max() + 1e-6)
                assertTrue(c.center.lon >= lons.min() - 1e-6 && c.center.lon <= lons.max() + 1e-6)
            }
        }
        // Same input, same pins.
        assertEquals(Clustering.cluster(pts, 4.0, 56.0), Clustering.cluster(pts, 4.0, 56.0))
    }

    @Test fun noFixNoPin() {
        assertFalse(GeoPoint(0.0, 0.0).isValid)
        assertFalse(GeoPoint(91.0, 0.0).isValid)
        assertFalse(GeoPoint(Double.NaN, 3.0).isValid)
        assertTrue(GeoPoint(-0.5, 0.0).isValid)
        val cs = Clustering.cluster(listOf(GeoPoint(0.0, 0.0) to 1, brussels to 2), 5.0, 50.0)
        assertEquals(listOf(listOf(2)), cs.map { it.items })
        assertTrue(abs(cs.single().center.lat - brussels.lat) < 1e-9)
    }

    @Test fun outlineParses() {
        val rings = WorldOutline.parse(
            """{"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}},
              {"type":"Feature","properties":{},"geometry":{"type":"MultiPolygon","coordinates":[[[[2,2],[3,2],[3,3],[2,2]]],[[[5,5],[6,5],[6,6],[5,5]],[[5.2,5.2],[5.4,5.2],[5.4,5.4],[5.2,5.2]]]]}}
            ]}""",
        )
        assertEquals(4, rings.size)
        assertEquals(8, rings[0].size)
        assertEquals(5.2, rings[3][0], 1e-9)
    }
}
