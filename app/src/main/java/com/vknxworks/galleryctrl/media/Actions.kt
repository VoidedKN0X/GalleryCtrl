package com.vknxworks.galleryctrl.media

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile

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

