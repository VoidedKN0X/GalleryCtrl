package com.vknxworks.galleryctrl.media

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class VaultEntry(val id: String, val name: String, val mime: String, val date: Long, val size: Long, val width: Int, val height: Int)

/**
 * The private vault: photos and videos moved here are encrypted inside GalleryCtrl's own storage, and the
 * originals are deleted from the library (Android asks you to confirm). Opening the vault asks for the app PIN
 * (or fingerprint) when the app lock and "Ask for the PIN for the vault" are on.
 *
 * - Every file (`<id>.gcv`) has its own random AES-256 key, wrapped by the vault's key in the phone's secure
 *   hardware (AndroidKeyStore), and is encrypted in 64 KB pieces ([VaultFormat]). So videos stream straight from
 *   the encrypted file (play and seek, no decrypted copy), and restoring copies piece by piece: nothing ever needs
 *   the whole file in memory.
 * - Every entry also has a small encrypted thumbnail (`<id>.thumb`, same format), so the vault grid never
 *   decrypts the originals.
 * - Files from GalleryCtrl 0.6.0 and older (`<id>.bin`: one AES-GCM stream through the Keystore, which only gives
 *   the plaintext back once the whole file is in memory) are converted once, in the background, by
 *   [VaultConversion]: never losing a file, and carrying on after being cut off. Until then they are read the old way.
 * - The list of what's in the vault (names, dates, sizes) is small and stays encrypted straight with the Keystore
 *   key (`index.bin`). An older plain `index.json` is converted, then overwritten and deleted, the first time.
 * - Photos are decrypted in memory only. Old-format videos that haven't been converted yet still need a decrypted
 *   copy for the player, in `cacheDir/vault`, overwritten and deleted when you leave the vault, when the app locks,
 *   and at the next start.
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

    private val _converting = MutableStateFlow(0)

    /** How many old-format files are still waiting to be converted (counts down while it runs). */
    val converting: StateFlow<Int> = _converting.asStateFlow()

    private val _unconverted = MutableStateFlow(0)

    /** Old-format files that couldn't be converted this time (too large or not readable); tried again next start. */
    val unconverted: StateFlow<Int> = _unconverted.asStateFlow()

    /** Guards the index and the files: adds, removes, restores and conversions happen one at a time. */
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    /** At most two decryptions into memory at once, so a scrolling grid can't run out of memory. */
    private val decrypting = Semaphore(2)

    /** Decoded thumbnails, in memory only (cleared with [closeCache]). */
    private val thumbs = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /**
     * Set while the vault screen is open (and unlocked): only then does [openStream] hand out a video stream.
     * Cleared by [closeCache], so a stream can't be opened after you leave the vault or the app locks.
     */
    @Volatile var viewing = false

    /** The Keystore key wraps and unwraps every file's own key. */
    private val wrapper = object : KeyWrapper {
        override fun wrap(key: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = cipher.iv
            return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(key)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            val n = wrapped[0].toInt()
            require(n in 12..16 && wrapped.size > 1 + n + 16) { "bad wrapped key" }
            val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 1, n)) }
            return cipher.doFinal(wrapped, 1 + n, wrapped.size - 1 - n)
        }
    }

    /** Runs the read callbacks of video streams handed to the player (see [openStream]). */
    private val streamHandler: Handler by lazy { Handler(HandlerThread("vault-stream").apply { start() }.looper) }

    init {
        scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            // Plaintext left behind if the app was closed while an old-format video was open.
            cache.wipe()
            _entries.value = mutex.withLock {
                // Half-written files from an add or thumbnail that was cut off. (A cut-off conversion cleans up its own.)
                dir.listFiles()?.filter { it.name.endsWith(".thumb.tmp") || (it.name.endsWith(".gcv.tmp") && !isConverting(it)) }?.forEach { it.delete() }
                load()
            }
            loaded.complete(Unit)
            convertOldFiles()
        }
    }

    /** A leftover `<id>.gcv.tmp` next to an old `<id>.bin`: a cut-off conversion, which [VaultConversion] redoes. */
    private fun isConverting(tmp: File): Boolean = File(dir, tmp.name.removeSuffix(".gcv.tmp") + ".bin").exists()

    private fun load(): List<VaultEntry> {
        if (index.exists()) {
            val list = runCatching { json.decodeFromString<List<VaultEntry>>(legacyDecrypt(index).use { it.readBytes() }.decodeToString()) }.getOrNull()
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
        legacyEncrypt(tmp) { it.write(json.encodeToString(list).encodeToByteArray()) }
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
        _entries.value = list
    }

    /**
     * Converts every old-format file, smallest first, one at a time (each under [mutex], so nothing else touches
     * that entry meanwhile). Old files too large to decrypt in memory without risking the app running out of memory
     * are left as they are; they still open the old way, and are tried again at the next start.
     */
    private suspend fun convertOldFiles() {
        val pending = _entries.value
            .filter { VaultConversion.legacyFile(dir, it.id).exists() }
            .sortedBy { VaultConversion.legacyFile(dir, it.id).length() }
        if (pending.isEmpty()) return
        _converting.value = pending.size
        var left = 0
        // The Keystore holds the whole old file in memory (more than once while it grows), so stay well below the limit.
        val limit = Runtime.getRuntime().maxMemory() / 6
        for (entry in pending) {
            val result = mutex.withLock {
                if (_entries.value.none { it.id == entry.id }) return@withLock VaultConversion.Result.MISSING
                VaultConversion.convert(dir, entry.id, wrapper, ::legacyDecrypt, limit)
            }
            if (result == VaultConversion.Result.TOO_LARGE || result == VaultConversion.Result.FAILED) left++
            if (result == VaultConversion.Result.CONVERTED || result == VaultConversion.Result.RESUMED) thumbs.remove(entry.id)
            _converting.value = (_converting.value - 1).coerceAtLeast(0)
        }
        _converting.value = 0
        _unconverted.value = left
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

    /** The old format (and the index): one AES-GCM stream straight through the Keystore, which picks a new random IV. */
    private fun legacyEncrypt(file: File, write: (OutputStream) -> Unit) {
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        file.outputStream().use { raw ->
            raw.write(iv.size)
            raw.write(iv)
            CipherOutputStream(raw, cipher).use(write)
        }
    }

    /**
     * The old format's plaintext. The Keystore only hands it out once the whole file has been read and its GCM tag
     * checked, so this holds the whole file in memory: fine for the index, the reason old files are converted.
     */
    private fun legacyDecrypt(file: File): InputStream {
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

    private fun chunked(id: String) = VaultConversion.chunkedFile(dir, id)
    private fun legacy(id: String) = VaultConversion.legacyFile(dir, id)
    private fun thumbFile(id: String) = File(dir, "$id.thumb")

    /**
     * An entry's plaintext from the start, whichever format it is in right now. Tries the new file again last, in
     * case a conversion replaced the old one between the two checks.
     */
    private fun openPlain(entry: VaultEntry): InputStream {
        val new = chunked(entry.id)
        if (new.exists()) return ChunkedReader.open(new, wrapper).inputStream()
        val old = legacy(entry.id)
        return try {
            legacyDecrypt(old)
        } catch (e: FileNotFoundException) {
            ChunkedReader.open(new, wrapper).inputStream()
        }
    }

    /** Where an old-format video's decrypted copy goes: named by its id only, inside the private cache. */
    private fun plainCopy(entry: VaultEntry): File {
        val ext = entry.mime.substringAfter('/', "bin").filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        return File(cache, "${entry.id.filter { it.isLetterOrDigit() || it == '-' }}.$ext")
    }

    /**
     * Encrypts copies of [items] into the vault, each with an encrypted thumbnail. Returns the items that were
     * stored (delete those originals next). Runs to the end even if the screen that started it is gone.
     */
    suspend fun add(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val done = ArrayList<MediaItem>()
            val list = _entries.value.toMutableList()
            for (item in items) {
                val id = UUID.randomUUID().toString()
                val ok = runCatching {
                    VaultFormat.writeFile(chunked(id), wrapper) { enc ->
                        context.contentResolver.openInputStream(item.uri)!!.use { it.copyTo(enc, 64 * 1024) }
                    }
                }.isSuccess
                if (ok) {
                    // The thumbnail Android already has for the original; best effort (made later from the file if missing).
                    runCatching {
                        context.contentResolver.loadThumbnail(item.uri, Size(THUMB_PX, THUMB_PX), null).let { bmp ->
                            try { writeThumb(id, bmp) } finally { bmp.recycle() }
                        }
                    }
                    list += VaultEntry(id, item.name, item.mime, item.date, item.size, item.width, item.height)
                    done += item
                } else {
                    chunked(id).delete()
                }
            }
            if (done.isNotEmpty()) save(list)
            done
        }
    }

    private fun writeThumb(id: String, bmp: Bitmap) {
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        VaultFormat.writeFile(thumbFile(id), wrapper) { it.write(jpeg) }
    }

    /**
     * A content:// address the video player can stream [entry] from (see [VaultStreamProvider]): decrypted piece by
     * piece as it plays, with seeking, and never written anywhere. Null for an old-format file not converted yet.
     */
    fun streamUri(entry: VaultEntry): Uri? =
        if (chunked(entry.id).exists()) Uri.parse("content://${context.packageName}.vaultstream/v/${Uri.encode(entry.id)}") else null

    /** For [VaultStreamProvider]: a read-only, seekable stream of [id]'s plaintext, only while the vault is open. */
    fun openStream(id: String): ParcelFileDescriptor? {
        if (!viewing) return null
        if (_entries.value.none { it.id == id }) return null
        val file = chunked(id)
        if (!file.exists()) return null
        return proxyFd(file)
    }

    /**
     * A file descriptor whose reads are answered by decrypting [file] piece by piece (Android's proxy file
     * descriptors): what the video player and the image decoder read from. Nothing decrypted is written anywhere.
     */
    private fun proxyFd(file: File): ParcelFileDescriptor {
        val reader = ChunkedReader.open(file, wrapper)
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ChunkedProxy(reader), streamHandler)
        } catch (t: Throwable) {
            reader.close()
            throw t
        }
    }

    private class ChunkedProxy(private val reader: ChunkedReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var total = 0
                while (total < size) {
                    val n = reader.read(offset + total, data, total, size - total)
                    if (n <= 0) break
                    total += n
                }
                return total
            } catch (e: IOException) {
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        override fun onRelease() = reader.close()
    }

    /** For a video's thumbnail: the retriever reads the encrypted file piece by piece. */
    private class ChunkedSource(private val reader: ChunkedReader) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.read(position, buffer, offset, size)
        override fun getSize(): Long = reader.size
        override fun close() = reader.close()
    }

    /**
     * Decrypts an old-format entry into the private cache for the video player (or a photo too large to open in
     * memory). Written to a temporary name first, so a half-written or failed copy is never used; wiped by
     * [closeCache]. New-format videos stream instead ([streamUri]).
     */
    suspend fun open(entry: VaultEntry): File? = withContext(Dispatchers.IO) {
        val out = plainCopy(entry)
        if (out.exists()) return@withContext out
        val part = File(out.path + ".part")
        runCatching {
            cache.mkdirs()
            openPlain(entry).use { dec -> part.outputStream().use { dec.copyTo(it, 64 * 1024) } }
            check(part.renameTo(out))
            out
        }.getOrElse {
            part.wipe()
            null
        }
    }

    /** A photo from the vault, decoded at most [maxEdge] pixels on its longer side, without a plaintext file. */
    suspend fun image(entry: VaultEntry, maxEdge: Int): Bitmap? = withContext(Dispatchers.IO) {
        decrypting.withPermit { runCatching { decodeImage(entry) { w, h -> maxEdge.toFloat() / maxOf(w, h) } }.getOrNull() }
    }

    /** A thumbnail whose shorter side is about [px] pixels: from the small encrypted thumbnail, cached in memory. */
    suspend fun thumbnail(entry: VaultEntry, px: Int): Bitmap? {
        thumbs.get(entry.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            decrypting.withPermit {
                val fromThumb = runCatching {
                    val file = thumbFile(entry.id)
                    if (!file.exists()) return@runCatching null
                    val bytes = ChunkedReader.open(file, wrapper).use { it.readAll() }
                    decodeScaled(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { w, h -> px.toFloat() / minOf(w, h) }
                }.getOrNull()
                fromThumb ?: runCatching { makeThumb(entry) }.getOrNull()?.let { full ->
                    // Made from the file itself (older entries): stored for next time, then scaled for the grid.
                    if (chunked(entry.id).exists()) runCatching { writeThumb(entry.id, full) }
                    if (_entries.value.none { it.id == entry.id }) thumbFile(entry.id).delete()
                    val s = px.toFloat() / minOf(full.width, full.height)
                    if (s < 1f) {
                        Bitmap.createScaledBitmap(full, (full.width * s).toInt().coerceAtLeast(1), (full.height * s).toInt().coerceAtLeast(1), true)
                            .also { if (it !== full) full.recycle() }
                    } else full
                }
            }
        }?.also { thumbs.put(entry.id, it) }
    }

    /** A thumbnail made from the entry's own file: for entries added before thumbnails were stored. Call with [decrypting]. */
    private suspend fun makeThumb(entry: VaultEntry): Bitmap? {
        val new = chunked(entry.id)
        if (entry.mime.startsWith("video/")) {
            // Old-format videos would have to be decrypted whole: they get a thumbnail once converted.
            if (!new.exists()) return null
            val reader = ChunkedReader.open(new, wrapper)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ChunkedSource(reader))
                return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_PX * 2, THUMB_PX * 2)
            } finally {
                runCatching { retriever.release() }
                reader.close()
            }
        }
        return decodeImage(entry) { w, h -> THUMB_PX.toFloat() / minOf(w, h) }
    }

    /** Decodes a vault photo: in memory when it fits, otherwise streamed piece by piece (new format) or via the cache (old). */
    private suspend fun decodeImage(entry: VaultEntry, scale: (Int, Int) -> Float): Bitmap? {
        val new = chunked(entry.id)
        val source = if (new.exists()) {
            val reader = ChunkedReader.open(new, wrapper)
            if (reader.size <= IN_MEMORY_MAX) {
                ImageDecoder.createSource(ByteBuffer.wrap(reader.use { it.readAll() }))
            } else {
                reader.close()
                ImageDecoder.createSource(Callable<AssetFileDescriptor> { AssetFileDescriptor(proxyFd(new), 0, AssetFileDescriptor.UNKNOWN_LENGTH) })
            }
        } else if (legacy(entry.id).length() <= IN_MEMORY_MAX) {
            ImageDecoder.createSource(ByteBuffer.wrap(openPlain(entry).use { it.readBytes() }))
        } else {
            ImageDecoder.createSource(open(entry) ?: return null)
        }
        return decodeScaled(source, scale)
    }

    private fun decodeScaled(source: ImageDecoder.Source, scale: (Int, Int) -> Float): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val s = scale(info.size.width, info.size.height)
            if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }

    /**
     * Puts an entry back in the library (Pictures/GalleryCtrl or Movies/GalleryCtrl) and removes it from the vault.
     * Copies piece by piece, straight from the encrypted file into the library (no plaintext copy on the way). Returns
     * false if it failed; the entry then stays in the vault and nothing half-written is left in the library.
     */
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
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { o -> openPlain(entry).use { it.copyTo(o, 64 * 1024) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (!ok) { runCatching { context.contentResolver.delete(uri, null, null) }; return@withContext false }
        remove(entry)
        true
    }

    /** Deletes one entry from the vault for good. */
    suspend fun remove(entry: VaultEntry) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            legacy(entry.id).delete()
            chunked(entry.id).delete()
            thumbFile(entry.id).delete()
            plainCopy(entry).wipe()
            thumbs.remove(entry.id)
            save(_entries.value.filterNot { it.id == entry.id })
        }
    }

    /** Forgets the thumbnails, stops handing out streams, and wipes old-format decrypted copies (off the main thread). */
    fun closeCache() {
        viewing = false
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
            _converting.value = 0
            _unconverted.value = 0
        }
    }

    companion object {
        private const val ALIAS = "galleryctrl_vault"
        private const val GCM = "AES/GCM/NoPadding"

        /** Photos up to this size are decrypted in memory; bigger ones are read piece by piece. */
        private const val IN_MEMORY_MAX = 64L * 1024 * 1024

        /** The stored thumbnails' shorter side, in pixels. */
        private const val THUMB_PX = 384
    }
}
