package com.vknxworks.galleryctrl.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Country outlines for the map without a map file: Natural Earth's 1:110m countries (public domain), bundled as
 * GeoJSON in assets (geometry only). Each ring is lon,lat pairs in one array.
 */
object WorldOutline {
    const val ASSET = "ne_110m_countries.geojson"

    /** Every polygon ring (outer edges and holes) in a GeoJSON FeatureCollection of Polygons and MultiPolygons. */
    fun parse(geojson: String): List<DoubleArray> {
        val root = Json.parseToJsonElement(geojson).jsonObject
        val out = ArrayList<DoubleArray>()
        fun ring(r: JsonArray) {
            val a = DoubleArray(r.size * 2)
            r.forEachIndexed { i, p ->
                val c = p.jsonArray
                a[i * 2] = c[0].jsonPrimitive.double
                a[i * 2 + 1] = c[1].jsonPrimitive.double
            }
            if (r.size >= 2) out += a
        }
        for (f in root["features"]?.jsonArray.orEmpty()) {
            val g = (f as? JsonObject)?.get("geometry") as? JsonObject ?: continue
            val coords = g["coordinates"] as? JsonArray ?: continue
            when (g["type"]?.jsonPrimitive?.content) {
                "Polygon" -> coords.forEach { ring(it.jsonArray) }
                "MultiPolygon" -> coords.forEach { poly -> poly.jsonArray.forEach { ring(it.jsonArray) } }
            }
        }
        return out
    }
}
