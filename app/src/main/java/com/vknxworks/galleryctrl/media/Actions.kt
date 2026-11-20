package com.vknxworks.galleryctrl.media

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Changes to the library go through Android's own confirmation dialogs (MediaStore requests), so
 * GalleryCtrl never needs broad storage access: favourite, and delete. There is no trash bin: delete means gone,
 * after Android's own confirmation, so nothing lingers in a hidden folder.
 */
object MediaRequests {
    fun favorite(context: Context, items: List<MediaItem>, on: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(context.contentResolver, items.map { it.uri }, on)

    fun delete(context: Context, items: List<MediaItem>): PendingIntent =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
}

/** Sharing: by default the shared copy has its location removed. */
object Sharing {
    /**
     * Shares [items]. With [stripLocation], every item is replaced (off the main thread) by a copy in the share
     * cache that has no location, see [strippedCopy]. An item whose location can't be removed, or can't be ruled
     * out, is left out rather than shared with it, and a message says how many photos, videos and other files
     * were left out. Without [stripLocation] the originals are shared exactly as they are.
     */
    fun share(context: Context, items: List<MediaItem>, stripLocation: Boolean) {
        val app = context.applicationContext
        app.container.appScope.launch {
            // Rewriting a video can take a while and nothing else shows on screen meanwhile.
            if (stripLocation && items.any { it.isVideo }) {
                Toast.makeText(app, R.string.share_removing_video_location, Toast.LENGTH_SHORT).show()
            }
            val copies: List<Uri?> = withContext(Dispatchers.IO) {
                if (stripLocation) items.map { strippedCopy(app, it) } else items.map { it.uri }
            }
            val uris = copies.filterNotNull()
            if (uris.size < items.size) {
                val left = items.filterIndexed { i, _ -> copies[i] == null }
                val videos = left.count { it.isVideo }
                val photos = left.count { !it.isVideo && it.mime in REWRITABLE_PHOTOS }
                val others = left.size - videos - photos
                if (photos > 0) Toast.makeText(app, app.resources.getQuantityString(R.plurals.share_location_not_removed, photos, photos), Toast.LENGTH_LONG).show()
                if (videos > 0) Toast.makeText(app, app.resources.getQuantityString(R.plurals.share_video_location_not_removed, videos, videos), Toast.LENGTH_LONG).show()
                if (others > 0) Toast.makeText(app, app.resources.getQuantityString(R.plurals.share_type_location_not_removed, others, others), Toast.LENGTH_LONG).show()
            }
            if (uris.isEmpty()) return@launch
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
            intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { app.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /**
     * A copy of [item] in the share cache that carries no location, or null when that can't be done. It never
     * returns the original: null means "don't share this item".
     *
     * - JPEG, WebP, PNG, HEIC and HEIF photos: the copy's GPS tags (and XMP that carries a position) are removed
     *   and the copy is checked afterwards.
     * - MP4, MOV and 3GP videos: the copy is rewritten without the location ([remuxedVideo]) and checked.
     * - Everything else (other video containers, AVIF, RAW/DNG, GIF, JPEG XL...) can't be rewritten. An exact copy
     *   is shared only when [carriesNoLocation] can tell that the file has no location in it; when it finds one,
     *   or can't read the format well enough to be sure, the item isn't shared. That is also what happens to an
     *   MP4/MOV/3GP video that can't be rewritten.
     */
    private fun strippedCopy(context: Context, item: MediaItem): Uri? = when {
        item.mime in REWRITABLE_PHOTOS -> rewrittenPhoto(context, item)
        item.isVideo -> remuxedVideo(context, item) ?: checkedCopy(context, item, "video")
        else -> checkedCopy(context, item, "file")
    }

    /** A fresh folder per copy, and only a safe file name (the name comes from the media store). */
    private fun shareFile(context: Context, name: String, fallback: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        return File(File(dir, UUID.randomUUID().toString()).apply { mkdirs() }, safeName(name, fallback))
    }

    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** True when the share cache has room for a copy of [item] (with a little to spare). */
    private fun hasRoomFor(context: Context, item: MediaItem): Boolean =
        File(context.cacheDir, "share").apply { mkdirs() }.usableSpace > item.size + 32L * 1024 * 1024

    /**
     * Copies a photo into the share cache and removes its location (GPS tags, and XMP that carries a position).
     * The copy is checked afterwards: if the location is still there, null is returned so the photo isn't shared.
     */
    private fun rewrittenPhoto(context: Context, item: MediaItem): Uri? {
        val out = shareFile(context, item.name, "photo.jpg")
        return runCatching {
            context.contentResolver.openInputStream(item.uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            val exif = ExifInterface(out)
            LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
            if (exif.getAttribute(ExifInterface.TAG_XMP)?.contains("GPS", ignoreCase = true) == true) exif.setAttribute(ExifInterface.TAG_XMP, null)
            exif.saveAttributes()
            val check = ExifInterface(out)
            check(check.latLong == null && LOCATION_TAGS.none { check.getAttribute(it) != null })
            shareUri(context, out)
        }.getOrElse {
            out.delete()
            null
        }
    }

    /**
     * An exact copy of [item] in the share cache, but only if the copy can be shown to carry no location
     * ([carriesNoLocation]); otherwise null. The copy (not the original) is what gets checked and shared, so the
     * check is about exactly the bytes the other app receives.
     */
    private fun checkedCopy(context: Context, item: MediaItem, fallback: String): Uri? {
        // No point copying a file that carriesNoLocation can never clear.
        if (item.mime !in ISO_VIDEOS && item.mime !in EXIF_READABLE && item.mime != "image/gif") return null
        if (!hasRoomFor(context, item)) return null
        val out = shareFile(context, item.name, fallback)
        return runCatching {
            context.contentResolver.openInputStream(item.uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            check(carriesNoLocation(out, item.mime))
            shareUri(context, out)
        }.getOrElse {
            out.delete()
            null
        }
    }

    /**
     * Whether [file] can be shown to have no location in it. False also means "can't tell": only formats whose
     * metadata can really be read here are ever answered with true.
     *
     * - MP4/MOV/3GP: Android reports no location, and none of the boxes outside the picture and sound data holds
     *   a known location entry or XMP position ([isoBoxesClean]).
     * - AVIF, DNG and the RAW formats ExifInterface reads: the EXIF block was really read (it names a camera,
     *   program or capture date; a file without any of those counts as "can't tell"), it has no GPS tags, and
     *   there's no XMP position anywhere in the file.
     * - GIF: has no EXIF; the only place for a position is XMP, which is searched for.
     * - Anything else (JPEG XL, MKV, WebM, Canon CR3...): can't be read here, so false.
     *
     * Limits, to be honest about them: a position a camera stores in its own private format (a maker note, a
     * telemetry or subtitle track, or data inside the video stream itself) isn't recognised.
     */
    private fun carriesNoLocation(file: File, mime: String): Boolean = runCatching {
        when (mime) {
            in ISO_VIDEOS -> {
                // A null from videoFacts means Android couldn't read the file at all: can't tell.
                val facts = videoFacts { it.setDataSource(file.path) }
                facts != null && facts.location.isNullOrBlank() && isoBoxesClean(file)
            }
            in EXIF_READABLE -> {
                val exif = ExifInterface(file)
                EXIF_WAS_READ.any { exif.getAttribute(it) != null } &&
                    exif.latLong == null && LOCATION_TAGS.none { exif.getAttribute(it) != null } &&
                    exif.getAttribute(ExifInterface.TAG_XMP)?.contains("GPS", ignoreCase = true) != true &&
                    !fileContains(file, XMP_POSITION)
            }
            "image/gif" -> !fileContains(file, XMP_POSITION)
            else -> false
        }
    }.getOrDefault(false)

    private class VideoFacts(val location: String?, val rotation: Int, val hasVideo: Boolean)

    /** What Android's own reader says about a video; null when it can't read it. */
    private fun videoFacts(open: (MediaMetadataRetriever) -> Unit): VideoFacts? {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            VideoFacts(
                location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Rewrites an MP4, MOV or 3GP video into a new MP4 in the share cache without its location, or returns null
     * when that can't be done (then nothing of it is left in the cache).
     *
     * How: the platform MediaExtractor reads the picture and sound tracks and MediaMuxer writes the very same
     * compressed samples into a new file. Nothing is decoded or re-encoded, so the quality is untouched. The
     * muxer only writes a location when `setLocation` is called, and it never is. The rotation is carried over.
     *
     * What else is lost, because a rewrite builds a new container: every other piece of container metadata (the
     * recording date becomes "now", camera make/model, titles, XMP, chapter and edit lists) and data tracks
     * (`application/...`: motion or sensor data, which can hold positions too). A MOV or 3GP comes out as an MP4.
     *
     * Anything unexpected means "could not strip": a track that is neither picture, sound nor data (subtitles),
     * a codec the muxer refuses, encrypted or partial samples, any exception, and a result that fails the check
     * at the end (Android still reports a location, a location box is found, the rotation changed, the picture
     * is gone, or the file is shorter than what was written).
     *
     * NOT TESTED ON A DEVICE: this was written and compiled by CI only. Whether every phone's recordings
     * (Dolby Vision, HDR10+, slow motion, spatial audio) go through the muxer and play correctly afterwards has
     * not been tried. A recording the muxer refuses falls back to [checkedCopy], so it's shared only if it had
     * no location to begin with.
     */
    private fun remuxedVideo(context: Context, item: MediaItem): Uri? {
        if (item.mime !in ISO_VIDEOS || !hasRoomFor(context, item)) return null
        val source = videoFacts { it.setDataSource(context, item.uri) } ?: return null
        if (source.rotation !in setOf(0, 90, 180, 270)) return null
        val out = shareFile(context, item.name, "video.mp4").let { File(it.parentFile, it.name.substringBeforeLast('.', it.name) + ".mp4") }
        val written = copyTracks(context, item.uri, out, source.rotation)
        val ok = written > 0 && runCatching {
            val copy = videoFacts { it.setDataSource(out.path) }
            copy != null && copy.location.isNullOrBlank() && copy.rotation == source.rotation &&
                copy.hasVideo == source.hasVideo && out.length() >= written && isoBoxesClean(out)
        }.getOrDefault(false)
        if (!ok) {
            out.delete()
            return null
        }
        return runCatching { shareUri(context, out) }.getOrElse {
            out.delete()
            null
        }
    }

    /**
     * Copies the picture and sound samples of [uri] into a new MP4 at [out], unchanged. Returns the number of
     * sample bytes written, or -1 when the video can't be copied this way (see [remuxedVideo]).
     */
    private fun copyTracks(context: Context, uri: Uri, out: File, rotation: Int): Long {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val target = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = target
            val outTrack = IntArray(extractor.trackCount) { -1 }
            var bufferSize = 1024 * 1024
            var tracks = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return -1L
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                    // Throws for a codec the MP4 muxer can't write: caught below, the video isn't rewritten.
                    outTrack[i] = target.addTrack(format)
                    extractor.selectTrack(i)
                    tracks++
                } else if (!mime.startsWith("application/")) {
                    // Subtitles and the like: the muxer can't carry them and dropping them unasked would change the video.
                    return -1L
                }
                // application/...: a data track (motion, sensors). Left out on purpose, it may hold positions.
            }
            if (tracks == 0 || bufferSize > MAX_SAMPLE_BYTES) return -1L
            target.setOrientationHint(rotation)
            target.start()
            var buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            var written = 0L
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                if (outTrack[track] < 0) return -1L
                val size = extractor.sampleSize
                if (size > MAX_SAMPLE_BYTES) return -1L
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size.toInt())
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val flags = extractor.sampleFlags
                if (flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) return -1L
                val key = if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, read, extractor.sampleTime, key)
                target.writeSampleData(outTrack[track], buffer, info)
                written += read
                if (!extractor.advance()) break
            }
            // stop() writes the index; it throws when the file couldn't be finished.
            target.stop()
            written
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Walks the top-level boxes of an MP4/MOV/3GP file and searches everything except the picture and sound data
     * (`mdat`) for the known places a position is kept: the `©xyz`, `loci`, `gps ` and `gps0` boxes, Apple's
     * `com.apple.quicktime.location...` keys and an XMP position. True only if the whole file was walked and
     * nothing was found; a file whose boxes don't add up is "can't tell", so false.
     */
    private fun isoBoxesClean(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val head = ByteArray(16)
            var pos = 0L
            var boxes = 0
            while (pos < length) {
                check(pos + 8 <= length)
                raf.seek(pos)
                raf.readFully(head, 0, 8)
                var size = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (head[i].toLong() and 0xffL) }
                var header = 8L
                if (size == 1L) {
                    check(pos + 16 <= length)
                    raf.readFully(head, 8, 8)
                    size = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (head[i].toLong() and 0xffL) }
                    header = 16L
                } else if (size == 0L) {
                    size = length - pos
                }
                check(size >= header && size <= length - pos)
                val isMediaData = head[4] == 'm'.code.toByte() && head[5] == 'd'.code.toByte() &&
                    head[6] == 'a'.code.toByte() && head[7] == 't'.code.toByte()
                if (!isMediaData && regionContains(raf, size - header, ISO_LOCATION)) return@use false
                pos += size
                boxes++
            }
            boxes > 0
        }
    }.getOrDefault(false)

    /** Whether any of [patterns] occurs anywhere in [file]. Throws when the file can't be read. */
    private fun fileContains(file: File, patterns: List<ByteArray>): Boolean =
        RandomAccessFile(file, "r").use { raf -> regionContains(raf, raf.length(), patterns) }

    /** Whether any of [patterns] occurs in the next [length] bytes of [raf]. Throws when they can't all be read. */
    private fun regionContains(raf: RandomAccessFile, length: Long, patterns: List<ByteArray>): Boolean {
        val keep = patterns.maxOf { it.size } - 1
        val buf = ByteArray(64 * 1024 + keep)
        var carried = 0
        var left = length
        while (left > 0) {
            val n = raf.read(buf, carried, minOf(left, (buf.size - carried).toLong()).toInt())
            check(n > 0)
            val filled = carried + n
            for (pattern in patterns) {
                val last = filled - pattern.size
                var i = 0
                while (i <= last) {
                    if (buf[i] == pattern[0]) {
                        var j = 1
                        while (j < pattern.size && buf[i + j] == pattern[j]) j++
                        if (j == pattern.size) return true
                    }
                    i++
                }
            }
            // Keep the tail, so a pattern that straddles two reads is still found.
            carried = minOf(keep, filled)
            System.arraycopy(buf, filled - carried, buf, 0, carried)
            left -= n
        }
        return false
    }

    /** A file name without folders or odd characters, so it can't point outside the share cache. */
    internal fun safeName(name: String, fallback: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in "._- ()" }.trim().trimStart('.')
        return clean.take(120).ifBlank { fallback }
    }

    private val LOCATION_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION,
    )

    /** Photo formats whose metadata can be rewritten, so the location is removed from the copy. */
    private val REWRITABLE_PHOTOS = setOf("image/jpeg", "image/webp", "image/png", "image/heic", "image/heif")

    /** Video containers built from ISO boxes: these can be rewritten by the muxer and searched for location boxes. */
    private val ISO_VIDEOS = setOf("video/mp4", "video/quicktime", "video/3gpp", "video/3gpp2")

    /** Formats ExifInterface can read but not rewrite. */
    private val EXIF_READABLE = setOf(
        "image/avif", "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-nikon-nrw",
        "image/x-sony-arw", "image/x-panasonic-rw2", "image/x-olympus-orf", "image/x-pentax-pef",
        "image/x-samsung-srw", "image/x-fuji-raf",
    )

    /** Tags ExifInterface never fills in by itself: one of them being there shows the EXIF block was really read. */
    private val EXIF_WAS_READ = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_DATETIME_ORIGINAL,
    )

    /** How an XMP packet names a position (`exif:GPSLatitude` and the like). */
    private val XMP_POSITION = listOf(":GPS".toByteArray(Charsets.ISO_8859_1))

    /** Where MP4/MOV/3GP files keep a position: `©xyz`, `loci`, `gps `, `gps0`, Apple's keys, XMP. */
    private val ISO_LOCATION = listOf("\u00A9xyz", "loci", "gps ", "gps0", "quicktime.location", ":GPS").map { it.toByteArray(Charsets.ISO_8859_1) }

    /** No single sample is anywhere near this big; a larger one means a broken file. */
    private const val MAX_SAMPLE_BYTES = 64L * 1024 * 1024

    fun clearCache(context: Context) {
        File(context.cacheDir, "share").deleteRecursively()
    }
}

/** Overwrites a file's bytes before deleting it (best effort: flash storage may keep old blocks). */
internal fun File.wipe() {
    if (isDirectory) {
        listFiles()?.forEach { it.wipe() }
    } else if (isFile) {
        runCatching {
            RandomAccessFile(this, "rw").use { raf ->
                val zeros = ByteArray(64 * 1024)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    left -= n
                }
                raf.fd.sync()
            }
        }
    }
    delete()
}

@Serializable
data class VaultEntry(val id: String, val name: String, val mime: String, val date: Long, val size: Long, val width: Int, val height: Int)

/**
 * The private vault: photos and videos moved here are encrypted (AES-256-GCM, key in the phone's secure
 * hardware) inside GalleryCtrl's own storage, and the originals are deleted from the library (Android asks
 * you to confirm). Opening the vault needs the app PIN when the app lock is on.
 *
 * - Every file is `[IV length][IV][ciphertext + GCM tag]`; the Keystore makes a fresh random IV for every file.
 * - The list of what's in the vault (names, dates, sizes) is encrypted the same way (`index.bin`). An older
 *   plain `index.json` is converted, then overwritten and deleted, the first time the vault loads.
 * - Photos (and their thumbnails) are decrypted in memory only. Videos need a file for the player: they're
 *   decrypted into `cacheDir/vault`, which is overwritten and deleted when you leave the vault, when the app
 *   locks, and at the next start if the app was closed while a video was open.
 * - Deleting everything ("Forgot PIN?") also deletes the Keystore key, so nothing left on the flash can be decrypted.
 */
class Vault(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "vault")
    private val index = File(dir, "index.bin")
    private val legacyIndex = File(dir, "index.json")
    private val cache: File get() = File(context.cacheDir, "vault")
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    /** Guards the index: adds, removes and restores happen one at a time. */
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    /** At most two decryptions into memory at once, so a scrolling grid can't run out of memory. */
    private val decrypting = Semaphore(2)

    /** Decoded thumbnails, in memory only (cleared with [closeCache]). */
    private val thumbs = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            // Plaintext left behind if the app was closed while a video was open.
            cache.wipe()
            _entries.value = mutex.withLock { load() }
            loaded.complete(Unit)
        }
    }

    private fun load(): List<VaultEntry> {
        if (index.exists()) {
            val list = runCatching { json.decodeFromString<List<VaultEntry>>(decrypt(index).use { it.readBytes() }.decodeToString()) }.getOrNull()
            if (list != null) return list
            // Never overwrite an index that can't be read right now: keep it aside.
            index.renameTo(File(dir, "index.unreadable-${System.currentTimeMillis()}.bin"))
        }
        if (legacyIndex.exists()) {
            val list = runCatching { json.decodeFromString<List<VaultEntry>>(legacyIndex.readText()) }.getOrNull()
            if (list != null && runCatching { save(list) }.isSuccess) {
                legacyIndex.wipe()
                return list
            }
            return list.orEmpty()
        }
        return emptyList()
    }

    /** Writes the encrypted index (to a temporary file first, then renamed). Call with [mutex] held. */
    private fun save(list: List<VaultEntry>) {
        val tmp = File(dir, "index.bin.tmp")
        encrypt(tmp) { it.write(json.encodeToString(list).encodeToByteArray()) }
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        _entries.value = list
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun deleteKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /** Encrypts what [write] produces into [file]; the Keystore picks a new random IV. */
    private fun encrypt(file: File, write: (OutputStream) -> Unit) {
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        file.outputStream().use { raw ->
            raw.write(iv.size)
            raw.write(iv)
            CipherOutputStream(raw, cipher).use(write)
        }
    }

    /** A stream of [file]'s plaintext. The GCM tag is checked at the end: a changed file fails with an IOException. */
    private fun decrypt(file: File): InputStream {
        val raw = file.inputStream().buffered()
        try {
            val ivLen = raw.read()
            require(ivLen in 12..16) { "bad header" }
            val iv = raw.readNBytes(ivLen)
            require(iv.size == ivLen) { "short header" }
            val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            return CipherInputStream(raw, cipher)
        } catch (e: Throwable) {
            raw.close()
            throw e
        }
    }

    private fun file(id: String) = File(dir, "$id.bin")

    /** Where a video's decrypted copy goes: named by its id only, inside the private cache. */
    private fun plainCopy(entry: VaultEntry): File {
        val ext = entry.mime.substringAfter('/', "bin").filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        return File(cache, "${entry.id.filter { it.isLetterOrDigit() || it == '-' }}.$ext")
    }

    /** Encrypts copies of [items] into the vault. Returns the items that were stored (delete those originals next). */
    suspend fun add(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val done = ArrayList<MediaItem>()
            val list = _entries.value.toMutableList()
            for (item in items) {
                val id = UUID.randomUUID().toString()
                val ok = runCatching {
                    encrypt(file(id)) { enc -> context.contentResolver.openInputStream(item.uri)!!.use { it.copyTo(enc) } }
                }.isSuccess
                if (ok) {
                    list += VaultEntry(id, item.name, item.mime, item.date, item.size, item.width, item.height)
                    done += item
                } else {
                    file(id).delete()
                }
            }
            if (done.isNotEmpty()) save(list)
            done
        }
    }

    /**
     * Decrypts an entry into the private cache for the video player (or a photo too large to open in memory).
     * Written to a temporary name first, so a half-written or failed copy is never used; wiped by [closeCache].
     */
    suspend fun open(entry: VaultEntry): File? = withContext(Dispatchers.IO) {
        val out = plainCopy(entry)
        if (out.exists()) return@withContext out
        val part = File(out.path + ".part")
        runCatching {
            cache.mkdirs()
            decrypt(file(entry.id)).use { dec -> part.outputStream().use { dec.copyTo(it) } }
            check(part.renameTo(out))
            out
        }.getOrElse {
            part.wipe()
            null
        }
    }

    /** A photo from the vault, decoded at most [maxEdge] pixels on its longer side, without a plaintext file. */
    suspend fun image(entry: VaultEntry, maxEdge: Int): Bitmap? = decode(entry) { w, h -> maxEdge.toFloat() / maxOf(w, h) }

    /** A thumbnail whose shorter side is about [px] pixels, cached in memory. */
    suspend fun thumbnail(entry: VaultEntry, px: Int): Bitmap? {
        if (entry.mime.startsWith("video/")) return null
        thumbs.get(entry.id)?.let { return it }
        return decode(entry) { w, h -> px.toFloat() / minOf(w, h) }?.also { thumbs.put(entry.id, it) }
    }

    private suspend fun decode(entry: VaultEntry, scale: (Int, Int) -> Float): Bitmap? = withContext(Dispatchers.IO) {
        decrypting.withPermit {
            runCatching {
                val source = if (file(entry.id).length() <= IN_MEMORY_MAX) {
                    ImageDecoder.createSource(ByteBuffer.wrap(decrypt(file(entry.id)).use { it.readBytes() }))
                } else {
                    ImageDecoder.createSource(open(entry) ?: return@runCatching null)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val s = scale(info.size.width, info.size.height)
                    if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }

    /** Puts an entry back in the library (Pictures/GalleryCtrl or Movies/GalleryCtrl) and removes it from the vault. */
    suspend fun restore(entry: VaultEntry): Boolean = withContext(Dispatchers.IO) {
        val video = entry.mime.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/GalleryCtrl")
            put(MediaStore.MediaColumns.DATE_TAKEN, entry.date)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { context.contentResolver.insert(coll, values) }.getOrNull() ?: return@withContext false
        // Straight from the encrypted file into the library: no plaintext copy on the way.
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { o -> decrypt(file(entry.id)).use { it.copyTo(o) } }
        }.isSuccess
        if (!ok) { runCatching { context.contentResolver.delete(uri, null, null) }; return@withContext false }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        remove(entry)
        true
    }

    /** Deletes one entry from the vault for good. */
    suspend fun remove(entry: VaultEntry) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            file(entry.id).delete()
            plainCopy(entry).wipe()
            thumbs.remove(entry.id)
            save(_entries.value.filterNot { it.id == entry.id })
        }
    }

    /** Forgets the thumbnails, and overwrites and deletes the decrypted video copies (off the main thread). */
    fun closeCache() {
        thumbs.evictAll()
        scope.launch(Dispatchers.IO) { cache.wipe() }
    }

    /** Deletes every file in the vault and its key: what's left on the flash can't be decrypted any more. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            closeCache()
            deleteKey()
            _entries.value = emptyList()
        }
    }

    companion object {
        private const val ALIAS = "galleryctrl_vault"
        private const val GCM = "AES/GCM/NoPadding"

        /** Photos up to this size (encrypted) are decrypted in memory; bigger ones go through the cache. */
        private const val IN_MEMORY_MAX = 64L * 1024 * 1024
    }
}
