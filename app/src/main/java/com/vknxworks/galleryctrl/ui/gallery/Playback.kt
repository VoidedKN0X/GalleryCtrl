package com.vknxworks.galleryctrl.ui.gallery

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.ui.components.ChoiceSheet
import com.vknxworks.galleryctrl.ui.components.CtrlSquareButton
import com.vknxworks.galleryctrl.ui.theme.CtrlType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------- Animated images ----------

/** Types that can hold an animation Android plays itself (GIF, WebP, AVIF and HEIF image sequences). */
private val ANIMATABLE = setOf("image/gif", "image/webp", "image/avif", "image/heif-sequence", "image/heic-sequence")

fun mightAnimate(mime: String?): Boolean = mime?.lowercase() in ANIMATABLE

/**
 * The animation in [uri], decoded at most [maxEdge] pixels on its longer side, or null when the file has a single
 * frame (then the still picture is used as before). Frames are decoded while it plays, not all at once.
 */
fun decodeAnimation(context: Context, uri: Uri, maxEdge: Int): AnimatedImageDrawable? = runCatching {
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxEdge) {
            val s = maxEdge.toFloat() / big
            decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
    } as? AnimatedImageDrawable
}.getOrNull()

/** Draws an animated image fitted into the available space, playing while [playing]. */
@Composable
fun AnimatedImage(drawable: AnimatedImageDrawable, playing: Boolean, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    // The drawable keeps only a weak reference to its callback, so the callback is remembered here.
    val callback = remember(drawable) {
        val handler = Handler(Looper.getMainLooper())
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { frame++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, `when`) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
    }
    DisposableEffect(drawable) {
        drawable.callback = callback
        onDispose {
            drawable.stop()
            drawable.callback = null
        }
    }
    LaunchedEffect(drawable, playing) { if (playing) drawable.start() else drawable.stop() }
    Canvas(modifier) {
        frame // Read here so a new frame redraws only this canvas.
        val dw = drawable.intrinsicWidth.coerceAtLeast(1)
        val dh = drawable.intrinsicHeight.coerceAtLeast(1)
        val s = minOf(size.width / dw, size.height / dh)
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.translate((size.width - dw * s) / 2f, (size.height - dh * s) / 2f)
            c.scale(s, s)
            drawable.setBounds(0, 0, dw, dh)
            drawable.draw(c)
            c.restore()
        }
    }
}

// ---------- Video ----------

private val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f)

private fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"

/** How long one frame lasts in [uri], from the video's frame rate; 1/30 s when the file doesn't say. */
private fun frameMillis(context: Context, uri: Uri): Int {
    val fps = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true && it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.let { f -> runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) } }
        } finally {
            ex.release()
        }
    }.getOrNull() ?: runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull()
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull()
            if (frames != null && ms != null && ms > 0) frames * 1000f / ms else null
        } finally {
            r.release()
        }
    }.getOrNull()
    return if (fps != null && fps in 1f..480f) (1000f / fps).toInt().coerceAtLeast(1) else 33
}

/**
 * A video with Android's own player controls (play, pause, seek), plus GalleryCtrl's own row under it: one frame
 * back, the playback speed (0.25× to 2×) and one frame forward. Stepping a frame pauses the video first. Pauses
 * when [active] turns false (swiped away). [padding] keeps the video and the row clear of the screen's own bars.
 */
@Composable
fun VideoBox(uri: Uri, active: Boolean, padding: PaddingValues = PaddingValues(0.dp)) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var speed by remember(uri) { mutableFloatStateOf(1f) }
    var speedSheet by remember { mutableStateOf(false) }
    val frameMs by produceState(33, uri) { value = withContext(Dispatchers.IO) { frameMillis(context, uri) } }
    val view = remember { VideoView(context).apply { setMediaController(MediaController(context).also { it.setAnchorView(this) }) } }
    var activeNow by remember { mutableStateOf(active) }
    activeNow = active

    fun applySpeed(mp: MediaPlayer, s: Float) {
        runCatching {
            val wasPlaying = mp.isPlaying
            // Setting a speed also starts playback; a paused video stays paused and plays at this speed when started.
            mp.playbackParams = mp.playbackParams.setSpeed(s)
            if (!wasPlaying) mp.pause()
        }
    }

    DisposableEffect(uri) {
        view.setOnPreparedListener { mp ->
            player = mp
            if (speed != 1f) {
                runCatching {
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    if (!activeNow) mp.pause()
                }
            }
        }
        view.setVideoURI(uri)
        onDispose {
            player = null
            view.stopPlayback()
        }
    }
    LaunchedEffect(active) { if (active) view.start() else view.pause() }

    fun step(frames: Int) {
        val mp = player ?: return
        runCatching {
            if (view.isPlaying) view.pause()
            val to = (view.currentPosition + frames * frameMs).coerceIn(0, view.duration.coerceAtLeast(0))
            mp.seekTo(to.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }

    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
            AndroidView({ view }, Modifier.fillMaxWidth())
        }
        Row(
            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CtrlSquareButton(Icons.Outlined.SkipPrevious, stringResource(R.string.video_frame_back), { step(-1) }, tint = Color.White, enabled = player != null)
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = player != null) { speedSheet = true }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(speedLabel(speed), style = CtrlType.Status, color = Color.White)
            }
            CtrlSquareButton(Icons.Outlined.SkipNext, stringResource(R.string.video_frame_forward), { step(1) }, tint = Color.White, enabled = player != null)
        }
    }
    if (speedSheet) {
        ChoiceSheet(stringResource(R.string.video_speed), SPEEDS.map { it to speedLabel(it) }, speed, onDismiss = { speedSheet = false }) { s ->
            speedSheet = false
            speed = s
            player?.let { applySpeed(it, s) }
        }
    }
}

