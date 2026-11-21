package com.vknxworks.galleryctrl.media

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Move, copy and rename, all through Android's media library (MediaStore), so GalleryCtrl never needs "all files"
 * access. Moving and renaming change files other apps made, so they run only after Android's own "Allow GalleryCtrl
 * to modify…" confirmation ([MediaRequests.write]). Copying makes new files that belong to GalleryCtrl and needs no
 * confirmation. Nothing is ever deleted here.
 */
object MediaEdits {
    /** Moves [items] into [relativePath] (e.g. "Pictures/Holiday/"). Returns how many were moved. */
    suspend fun move(context: Context, items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        val path = FolderNames.normalisePath(relativePath)
        var moved = 0
        for (item in items) {
            ensureActive()
            if (FolderNames.normalisePath(item.relativePath) == path) { moved++; continue }
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
            val ok = runCatching { context.contentResolver.update(item.uri, values, null, null) > 0 }.getOrDefault(false)
            if (ok) moved++
        }
        moved
    }

    /** Renames one file; [newName] is the full name including the extension. */
    suspend fun rename(context: Context, item: MediaItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        runCatching { context.contentResolver.update(item.uri, values, null, null) > 0 }.getOrDefault(false)
    }

    /**
     * Copies [items] into [relativePath] as new files (Android adds " (1)" when the name is taken). The copy keeps the
     * date taken; it is written as "pending" first, so a failed or cancelled copy never shows up half-written.
     * [progress] gets the number done so far. Returns how many were copied.
     */
    suspend fun copy(context: Context, items: List<MediaItem>, relativePath: String, progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val path = FolderNames.normalisePath(relativePath)
        val resolver = context.contentResolver
        var copied = 0
        items.forEachIndexed { i, item ->
            ensureActive()
            val collection = if (item.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.DATE_TAKEN, item.date)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (uri != null) {
                val ok = runCatching {
                    resolver.openOutputStream(uri)!!.use { out -> resolver.openInputStream(item.uri)!!.use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }.isSuccess
                if (ok) copied++ else runCatching { resolver.delete(uri, null, null) }
            }
            progress(i + 1)
        }
        copied
    }
}

/** Checks for folder and file names typed in GalleryCtrl (pure, unit-tested). */
object FolderNames {
    /** Where new folders go. Android allows both photos and videos under Pictures. */
    const val NEW_FOLDER_PARENT = "Pictures"

    /** A folder or file name the user typed, trimmed; null when it can't be used as one. */
    fun clean(name: String, max: Int = 80): String? {
        val t = name.trim()
        if (t.isEmpty() || t.length > max || t == "." || t == "..") return null
        if (t.any { it == '/' || it == '\\' || it == '\u0000' || it.code < 0x20 }) return null
        if (t.startsWith('.')) return null
        return t
    }

    /** "Pictures/Holiday" for a new folder called "Holiday". */
    fun newFolderPath(name: String): String? = clean(name)?.let { normalisePath("$NEW_FOLDER_PARENT/$it") }

    /** A relative path with one slash at the end and none at the start, as MediaStore keeps them. */
    fun normalisePath(path: String): String = path.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    /** The new full file name: [base] plus the old file's extension (which can't be changed here). */
    fun withExtension(base: String, oldName: String): String? {
        val b = clean(base) ?: return null
        val ext = oldName.substringAfterLast('.', "")
        return if (ext.isEmpty() || oldName.lastIndexOf('.') <= 0) b else "$b.$ext"
    }

    /** The name without its extension, to start editing from. */
    fun baseName(name: String): String = if (name.lastIndexOf('.') > 0) name.substringBeforeLast('.') else name
}
