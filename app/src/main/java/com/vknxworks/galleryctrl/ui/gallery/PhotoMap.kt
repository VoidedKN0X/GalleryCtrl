package com.vknxworks.galleryctrl.ui.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.media.Cluster
import com.vknxworks.galleryctrl.media.Clustering
import com.vknxworks.galleryctrl.media.GeoPoint
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.WorldOutline
import com.vknxworks.galleryctrl.ui.common.hasPermission
import com.vknxworks.galleryctrl.ui.common.launch
import com.vknxworks.galleryctrl.ui.components.CtrlBottomSheet
import com.vknxworks.galleryctrl.ui.components.CtrlSquareButton
import com.vknxworks.galleryctrl.ui.components.MessageView
import com.vknxworks.galleryctrl.ui.components.ScreenTopBar
import com.vknxworks.galleryctrl.ui.components.SectionLabel
import com.vknxworks.galleryctrl.ui.lock.AppLock
import com.vknxworks.galleryctrl.ui.nav.LocalNavigator
import com.vknxworks.galleryctrl.ui.nav.ViewerRoute
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import com.vknxworks.galleryctrl.ui.theme.Gutter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Canvas as MfCanvas
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.FileInputStream
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val PIN_CELL_DP = 56f
private const val MAX_ZOOM = 17.0

/** Where to look first: the middle of [points] and a zoom that fits them all in [w] x [h] pixels. */
private fun frame(points: List<GeoPoint>, w: Float, h: Float): Triple<Double, Double, Double> {
    if (points.isEmpty() || w <= 0 || h <= 0) return Triple(128.0, 128.0, 1.0)
    val xs = points.map { Clustering.x(it.lon, 0.0) }
    val ys = points.map { Clustering.y(it.lat, 0.0) }
    val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-6)
    val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-6)
    val zoom = min(ln(w * 0.75 / spanX), ln(h * 0.75 / spanY)) / ln(2.0)
    return Triple((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, zoom.coerceIn(0.0, 14.0))
}

/** The photo map: pins and clusters for library photos that have a GPS position. Offline only. */
@Composable
fun PhotoMapScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val items by c.media.items.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val scanned by c.photoInfo.scanned.collectAsStateWithLifecycle()

    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val canReadLocation = remember(tick) { context.hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    var points by remember { mutableStateOf<List<Pair<GeoPoint, MediaItem>>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(items, canReadLocation) {
        if (!canReadLocation) { scanning = false; return@LaunchedEffect }
        scanning = true
        points = c.photoInfo.places(items) { partial -> points = partial }
        scanning = false
    }

    var options by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<MediaItem>?>(null) }
    var mapFailed by remember(settings.mapFile) { mutableStateOf(false) }

    val pickMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep reading the map after a restart, without copying it.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val old = settings.mapFile
            scope.launch {
                c.settings.update { it.copy(mapFile = uri.toString()) }
                if (old.isNotEmpty() && old != uri.toString()) releaseGrant(context, old)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.map)) {
            CtrlSquareButton(Icons.Outlined.Layers, stringResource(R.string.map_options), { options = true })
        }
        if (scanning && scanned.second > 0) {
            Text(
                stringResource(R.string.map_scanning, scanned.first, scanned.second),
                style = CtrlType.Data, color = CtrlColors.Muted,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !canReadLocation -> MessageView(
                    Icons.Outlined.LocationOff, stringResource(R.string.map_location_permission), stringResource(R.string.map_location_permission_body),
                    actionLabel = stringResource(R.string.allow_access),
                    onAction = { askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
                )
                !scanning && points.isEmpty() -> MessageView(Icons.Outlined.Map, stringResource(R.string.map_no_places), stringResource(R.string.map_no_places_body))
                settings.mapFile.isNotEmpty() && !mapFailed -> MapsforgeMap(settings.mapFile, points, onOpen = { selected = it }) {
                    mapFailed = true
                    Toast.makeText(context, R.string.map_file_error, Toast.LENGTH_LONG).show()
                }
                else -> SimpleMap(points) { selected = it }
            }
        }
    }

    if (options) {
        CtrlBottomSheet(onDismissRequest = { options = false }) {
            Column(Modifier.padding(horizontal = Gutter).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(stringResource(R.string.map_options).uppercase())
                Text(stringResource(R.string.map_file_hint), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted, modifier = Modifier.padding(vertical = 8.dp))
                OptionRow(stringResource(R.string.map_pick_file)) {
                    options = false
                    AppLock.expectReturn()
                    pickMap.launch(arrayOf("*/*"))
                }
                if (settings.mapFile.isNotEmpty()) OptionRow(stringResource(R.string.map_use_simple)) {
                    options = false
                    val old = settings.mapFile
                    scope.launch {
                        c.settings.update { it.copy(mapFile = "") }
                        releaseGrant(context, old)
                    }
                }
                OptionRow(stringResource(R.string.map_get_maps)) {
                    options = false
                    // Opens the page in the browser; GalleryCtrl itself has no internet access.
                    if (!context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://download.mapsforge.org/")))) {
                        Toast.makeText(context, R.string.map_no_browser, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    selected?.let { list ->
        CtrlBottomSheet(onDismissRequest = { selected = null }) {
            val px = with(LocalDensity.current) { 110.dp.roundToPx() }
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text(
                    pluralStringResource(R.plurals.map_photo_count, list.size, list.size),
                    style = MaterialTheme.typography.titleMedium, color = CtrlColors.Text,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp),
                )
                LazyVerticalGrid(
                    GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = Gutter),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable {
                            c.viewerList.value = list
                            c.viewerStart.value = list.indexOf(item).coerceAtLeast(0)
                            c.viewerSlideshow.value = false
                            selected = null
                            nav.navigate(ViewerRoute)
                        }) { Thumb(item, px, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = CtrlColors.Text,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
    )
}

/** Gives back the read grant of a map file that's no longer used. */
private fun releaseGrant(context: Context, uri: String) {
    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

// ---------- Without a map file: a coordinate grid with country outlines ----------

/**
 * The map without a map file: Web Mercator with lines every 30° (10° and 5° when zoomed in) and Natural Earth's
 * country outlines bundled with the app. Pinch to zoom, drag to move, tap a pin.
 */
@Composable
private fun SimpleMap(points: List<Pair<GeoPoint, MediaItem>>, onOpen: (List<MediaItem>) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val outline by produceState<Path?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val json = context.assets.open(WorldOutline.ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
                val path = Path()
                for (ring in WorldOutline.parse(json)) {
                    for (i in 0 until ring.size / 2) {
                        val x = Clustering.x(ring[i * 2], 0.0).toFloat()
                        val y = Clustering.y(ring[i * 2 + 1], 0.0).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                }
                path
            }.getOrNull()
        }
    }
    val measurer = rememberTextMeasurer()
    val land = CtrlColors.Raised
    val border = CtrlColors.ControlOutline
    val grid = CtrlColors.Divider
    val sea = CtrlColors.Background
    val accent = CtrlColors.Accent
    val onAccent = CtrlColors.OnAccent
    val credit = stringResource(R.string.map_simple_credit)
    val muted = CtrlColors.Muted

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Camera: centre in zoom-0 world pixels (0..256) and the zoom.
        var cx by remember { mutableDoubleStateOf(128.0) }
        var cy by remember { mutableDoubleStateOf(128.0) }
        var zoom by remember { mutableDoubleStateOf(1.0) }
        var framed by remember { mutableStateOf(false) }
        LaunchedEffect(points.isNotEmpty(), w, h) {
            if (!framed && points.isNotEmpty() && w > 0) {
                val (x, y, z) = frame(points.map { it.first }, w, h)
                cx = x; cy = y; zoom = z.coerceAtLeast(minZoom(w, h)); framed = true
            }
        }
        val cell = with(density) { PIN_CELL_DP.dp.toPx() }.toDouble()
        val zoomStep = floor(zoom * 4) / 4
        val clusters = remember(points, zoomStep) { Clustering.cluster(points, zoomStep, cell) }
        val latest = rememberUpdatedState(clusters)

        fun screen(p: GeoPoint): Offset {
            val s = 2.0.pow(zoom)
            return Offset(((Clustering.x(p.lon, 0.0) - cx) * s + w / 2).toFloat(), ((Clustering.y(p.lat, 0.0) - cy) * s + h / 2).toFloat())
        }

        Canvas(
            Modifier.fillMaxSize().background(sea)
                .pointerInput(w, h) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val s0 = 2.0.pow(zoom)
                        // The point under the fingers stays under them while zooming.
                        val wx = cx + (centroid.x - w / 2) / s0
                        val wy = cy + (centroid.y - h / 2) / s0
                        zoom = (zoom + ln(gestureZoom.toDouble()) / ln(2.0)).coerceIn(minZoom(w, h), MAX_ZOOM)
                        val s1 = 2.0.pow(zoom)
                        cx = (wx - (centroid.x - w / 2) / s1 - pan.x / s1).coerceIn(0.0, 256.0)
                        cy = (wy - (centroid.y - h / 2) / s1 - pan.y / s1).coerceIn(0.0, 256.0)
                    }
                }
                .pointerInput(w, h) {
                    detectTapGestures(
                        onDoubleTap = { zoom = (zoom + 1).coerceAtMost(MAX_ZOOM) },
                        onTap = { tap ->
                            val hit = latest.value.minByOrNull { (screen(it.center) - tap).getDistance() }
                            if (hit != null && (screen(hit.center) - tap).getDistance() < cell * 0.6) onOpen(hit.items)
                        },
                    )
                },
        ) {
            val s = 2.0.pow(zoom).toFloat()
            // Lines of latitude and longitude.
            val step = when { zoom < 3 -> 30.0; zoom < 5 -> 10.0; zoom < 7 -> 5.0; else -> 1.0 }
            var lon = -180.0
            while (lon <= 180.0) {
                val x = screen(GeoPoint(0.0, lon)).x
                if (x in 0f..size.width) drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                lon += step
            }
            var lat = -80.0
            while (lat <= 80.0) {
                val y = screen(GeoPoint(lat, 0.0)).y
                if (y in 0f..size.height) drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                lat += step
            }
            outline?.let { path ->
                withTransform({
                    translate((w / 2 - cx * s).toFloat(), (h / 2 - cy * s).toFloat())
                    scale(s, s, Offset.Zero)
                }) {
                    drawPath(path, land)
                    drawPath(path, border, style = Stroke(width = 1.dp.toPx() / s))
                }
            }
            for (cl in clusters) drawPin(screen(cl.center), cl.size, accent, onAccent, measurer)
            drawText(measurer, credit, Offset(8.dp.toPx(), size.height - 20.dp.toPx()), TextStyle(color = muted, fontSize = 10.sp))
        }
    }
}

/** Smallest zoom at which the world still fills the view's width. */
private fun minZoom(w: Float, h: Float): Double = (ln(max(w, h) / 256.0) / ln(2.0)).coerceAtLeast(0.0) - 0.5

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPin(
    at: Offset, count: Int, fill: Color, text: Color, measurer: androidx.compose.ui.text.TextMeasurer,
) {
    if (at.x < -40 || at.y < -40 || at.x > size.width + 40 || at.y > size.height + 40) return
    val r = (if (count == 1) 9.dp else if (count < 10) 14.dp else if (count < 100) 17.dp else 20.dp).toPx()
    drawCircle(Color.White, r + 2.dp.toPx(), at)
    drawCircle(fill, r, at)
    if (count > 1) {
        val label = if (count > 999) "999+" else count.toString()
        val layout = measurer.measure(label, TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
}

// ---------- With a map file: Mapsforge ----------

private object Mapsforge {
    @Volatile private var ready = false

    /** Mapsforge's Android graphics, set up once and only when the map is first opened. */
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (!ready) { AndroidGraphicFactory.createInstance(context.applicationContext as Application); ready = true }
        }
    }
}

/**
 * The map from a Mapsforge .map file the user picked (read in place through its content URI, never copied), with
 * the photo pins drawn on top. Rendered tiles stay in memory only. [onError] when the file can't be opened.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun MapsforgeMap(uri: String, points: List<Pair<GeoPoint, MediaItem>>, onOpen: (List<MediaItem>) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = CtrlColors.Accent.toArgb()
    val onAccent = CtrlColors.OnAccent.toArgb()
    val holder = remember(uri) { MapHolder() }
    val currentOpen = rememberUpdatedState(onOpen)
    val currentError = rememberUpdatedState(onError)
    DisposableEffect(uri) { onDispose { holder.close() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Mapsforge.init(ctx)
            val view = MapView(ctx)
            holder.view = view
            view.isClickable = true
            view.mapScaleBar.isVisible = false
            view.setBuiltInZoomControls(false)
            view.model.mapViewPosition.setZoomLevelMin(1.toByte())
            view.model.mapViewPosition.setZoomLevelMax(MAX_ZOOM.toInt().toByte())
            val layer = PinLayer(density.density, PIN_CELL_DP * density.density, accent, onAccent) { currentOpen.value(it) }
            holder.pins = layer
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: throw IllegalStateException()
                holder.pfd = pfd
                val file = MapFile(FileInputStream(pfd.fileDescriptor), 0L, null)
                holder.file = file
                val cache = InMemoryTileCache(96)
                holder.cache = cache
                val renderer = TileRendererLayer(cache, file, view.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
                renderer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
                view.layerManager.layers.add(renderer)
            } catch (e: Exception) {
                currentError.value()
            }
            view.layerManager.layers.add(layer)
            view
        },
        update = { view ->
            val pins = holder.pins ?: return@AndroidView
            pins.points = points
            if (!holder.framed && points.isNotEmpty() && view.width > 0) {
                val (x, y, z) = frame(points.map { it.first }, view.width.toFloat(), view.height.toFloat())
                view.model.mapViewPosition.mapPosition = MapPosition(
                    LatLong(Clustering.lat(y, 0.0), Clustering.lon(x, 0.0)), z.toInt().coerceIn(1, MAX_ZOOM.toInt()).toByte(),
                )
                holder.framed = true
            }
            pins.requestRedraw()
        },
    )
}

private class MapHolder {
    var view: MapView? = null
    var pins: PinLayer? = null
    var file: MapFile? = null
    var cache: InMemoryTileCache? = null
    var pfd: ParcelFileDescriptor? = null
    var framed = false

    fun close() {
        runCatching { view?.destroyAll() }
        runCatching { file?.close() }
        runCatching { cache?.destroy() }
        runCatching { pfd?.close() }
        view = null; pins = null; file = null; cache = null; pfd = null
    }
}

/** The photo pins on the Mapsforge map, clustered at the zoom level being drawn. */
private class PinLayer(
    private val density: Float,
    private val cellPx: Float,
    private val fillColor: Int,
    private val textColor: Int,
    private val onOpen: (List<MediaItem>) -> Unit,
) : Layer() {
    @Volatile var points: List<Pair<GeoPoint, MediaItem>> = emptyList()
    /** The pins last drawn, in world pixels at [drawnMapSize] (the zoom level last drawn). */
    @Volatile private var drawn: List<Pair<Cluster<MediaItem>, Point>> = emptyList()
    @Volatile private var drawnMapSize = 0L
    private var cacheKey: Pair<List<Pair<GeoPoint, MediaItem>>, Byte>? = null
    private var cached: List<Cluster<MediaItem>> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: MfCanvas, topLeftPoint: Point, rotation: Rotation) {
        val c = AndroidGraphicFactory.getCanvas(canvas)
        val tile = displayModel.tileSize
        val mapSize = MercatorProjection.getMapSize(zoomLevel, tile)
        // Clustering works in 256-pixel tiles; scale the cell so pins keep their size on screen.
        val zoomFor256 = zoomLevel + ln(tile / 256.0) / ln(2.0)
        val key = points to zoomLevel
        if (key != cacheKey) { cached = Clustering.cluster(points, zoomFor256, cellPx.toDouble()); cacheKey = key }
        val out = ArrayList<Pair<Cluster<MediaItem>, Point>>(cached.size)
        for (cl in cached) {
            val x = MercatorProjection.longitudeToPixelX(cl.center.lon, mapSize) - topLeftPoint.x
            val y = MercatorProjection.latitudeToPixelY(cl.center.lat, mapSize) - topLeftPoint.y
            out += cl to Point(x + topLeftPoint.x, y + topLeftPoint.y)
            val r = density * (if (cl.size == 1) 9f else if (cl.size < 10) 14f else if (cl.size < 100) 17f else 20f)
            fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(x.toFloat(), y.toFloat(), r + 2 * density, fill)
            fill.color = fillColor; c.drawCircle(x.toFloat(), y.toFloat(), r, fill)
            if (cl.size > 1) {
                text.color = textColor
                text.textSize = 12 * density
                val label = if (cl.size > 999) "999+" else cl.size.toString()
                c.drawText(label, x.toFloat(), (y - (text.descent() + text.ascent()) / 2).toFloat(), text)
            }
        }
        drawn = out
        drawnMapSize = mapSize
    }

    override fun onTap(tapLatLong: LatLong, layerXY: Point?, tapXY: Point?): Boolean {
        val size = drawnMapSize.takeIf { it > 0 } ?: return false
        val tx = MercatorProjection.longitudeToPixelX(tapLatLong.longitude, size)
        val ty = MercatorProjection.latitudeToPixelY(tapLatLong.latitude, size)
        val hit = drawn.minByOrNull { hypot(it.second.x - tx, it.second.y - ty) } ?: return false
        if (hypot(hit.second.x - tx, hit.second.y - ty) > cellPx * 0.6) return false
        onOpen(hit.first.items)
        return true
    }
}
