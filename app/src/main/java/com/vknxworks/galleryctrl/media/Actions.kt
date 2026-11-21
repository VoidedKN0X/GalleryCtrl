package com.vknxworks.galleryctrl.media

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
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

    /** Android's "Allow GalleryCtrl to modify…" confirmation, needed before moving or renaming files other apps made. */
    fun write(context: Context, items: List<MediaItem>): PendingIntent =
        MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })

    fun delete(context: Context, items: List<MediaItem>): PendingIntent =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
}

/** Sharing: the photos and videos you pick are handed to the other app exactly as they are, straight from the library. */
object Sharing {
    fun share(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        val uris = items.map { it.uri }
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

    /** Deletes copies that older versions (which shared copies without the location) left in the cache. */
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
