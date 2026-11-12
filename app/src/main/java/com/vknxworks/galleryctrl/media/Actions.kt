package com.vknxworks.galleryctrl.media

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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
    fun share(context: Context, items: List<MediaItem>, stripLocation: Boolean) {
        val uris = if (stripLocation) items.mapNotNull { strippedCopy(context, it) } else items.map { it.uri }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Copies a photo into the share cache and removes GPS tags; videos and other formats are shared as they are. */
    private fun strippedCopy(context: Context, item: MediaItem): Uri? {
        if (item.mime !in setOf("image/jpeg", "image/webp", "image/png", "image/heic", "image/heif")) return item.uri
        return runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val out = File(dir, item.name.ifBlank { "photo.jpg" })
            context.contentResolver.openInputStream(item.uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            runCatching {
                val exif = ExifInterface(out)
                LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
            FileProvider.getUriForFile(context, context.packageName + ".files", out)
        }.getOrElse { item.uri }
    }

    private val LOCATION_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_IMG_DIRECTION,
    )

    fun clearCache(context: Context) {
        File(context.cacheDir, "share").deleteRecursively()
    }
}

@Serializable
data class VaultEntry(val id: String, val name: String, val mime: String, val date: Long, val size: Long, val width: Int, val height: Int)

/**
 * The private vault: photos and videos moved here are encrypted (AES-256-GCM, key in the phone's secure
 * hardware) inside GalleryCtrl's own storage, and the originals are deleted from the library (Android asks
 * you to confirm). Opening the vault needs the app PIN when the app lock is on.
 */
class Vault(private val context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    init {
        _entries.value = runCatching { json.decodeFromString<List<VaultEntry>>(index.readText()) }.getOrDefault(emptyList())
    }

    private fun save(list: List<VaultEntry>) {
        _entries.value = list
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun file(id: String) = File(dir, "$id.bin")

    /** Encrypts copies of [items] into the vault. Returns the items that were stored (delete those originals next). */
    suspend fun add(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        val done = ArrayList<MediaItem>()
        val list = _entries.value.toMutableList()
        for (item in items) {
            runCatching {
                val id = UUID.randomUUID().toString()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
                file(id).outputStream().use { raw ->
                    raw.write(cipher.iv.size)
                    raw.write(cipher.iv)
                    CipherOutputStream(raw, cipher).use { enc ->
                        context.contentResolver.openInputStream(item.uri)!!.use { it.copyTo(enc) }
                    }
                }
                list += VaultEntry(id, item.name, item.mime, item.date, item.size, item.width, item.height)
                done += item
            }
        }
        save(list)
        done
    }

    /** Decrypts an entry into a temporary file in the private cache (for viewing or playing); deleted when the vault closes. */
    suspend fun open(entry: VaultEntry): File? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(File(context.cacheDir, "vault").apply { mkdirs() }, entry.id + "_" + entry.name)
            if (out.exists()) return@runCatching out
            file(entry.id).inputStream().use { raw ->
                val ivLen = raw.read()
                val iv = ByteArray(ivLen).also { raw.read(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                CipherInputStream(raw, cipher).use { dec -> out.outputStream().use { dec.copyTo(it) } }
            }
            out
        }.getOrNull()
    }

    suspend fun thumbnail(entry: VaultEntry, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (entry.mime.startsWith("video/")) return@withContext null
        val f = open(entry) ?: return@withContext null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= px && opts.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Puts an entry back in the library (Pictures/GalleryCtrl or Movies/GalleryCtrl) and removes it from the vault. */
    suspend fun restore(entry: VaultEntry): Boolean = withContext(Dispatchers.IO) {
        val f = open(entry) ?: return@withContext false
        val video = entry.mime.startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/GalleryCtrl")
            put(MediaStore.MediaColumns.DATE_TAKEN, entry.date)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(coll, values) ?: return@withContext false
        val ok = runCatching { context.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }.isSuccess
        if (!ok) { context.contentResolver.delete(uri, null, null); return@withContext false }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        remove(entry)
        true
    }

    fun remove(entry: VaultEntry) {
        file(entry.id).delete()
        File(File(context.cacheDir, "vault"), entry.id + "_" + entry.name).delete()
        save(_entries.value.filterNot { it.id == entry.id })
    }

    /** Deletes the decrypted temporary copies. */
    fun closeCache() {
        File(context.cacheDir, "vault").deleteRecursively()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        closeCache()
        _entries.value = emptyList()
    }

    companion object {
        private const val ALIAS = "galleryctrl_vault"
    }
}
