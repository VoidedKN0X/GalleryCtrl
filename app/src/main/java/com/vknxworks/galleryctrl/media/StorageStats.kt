package com.vknxworks.galleryctrl.media

/** Space taken by the library, worked out from the sizes MediaStore already has (pure, unit-tested). */
data class StorageStats(
    val totalCount: Int,
    val totalBytes: Long,
    val photoCount: Int,
    val photoBytes: Long,
    val videoCount: Int,
    val videoBytes: Long,
    /** Folders, biggest first. */
    val folders: List<Share>,
    /** File types ("JPEG", "MP4", …), biggest first. */
    val types: List<Share>,
    /** The largest files, biggest first. */
    val largest: List<MediaItem>,
) {
    data class Share(val name: String, val count: Int, val bytes: Long)

    companion object {
        const val LARGEST = 20

        fun of(items: List<MediaItem>): StorageStats {
            var photoCount = 0
            var photoBytes = 0L
            var videoCount = 0
            var videoBytes = 0L
            val folders = HashMap<Long, Triple<String, Int, Long>>()
            val types = HashMap<String, Pair<Int, Long>>()
            for (item in items) {
                val size = item.size.coerceAtLeast(0)
                if (item.isVideo) { videoCount++; videoBytes += size } else { photoCount++; photoBytes += size }
                val f = folders[item.albumId]
                folders[item.albumId] = if (f == null) Triple(item.album, 1, size) else Triple(f.first, f.second + 1, f.third + size)
                val type = typeName(item)
                val t = types[type]
                types[type] = if (t == null) 1 to size else (t.first + 1) to (t.second + size)
            }
            return StorageStats(
                totalCount = items.size,
                totalBytes = photoBytes + videoBytes,
                photoCount = photoCount,
                photoBytes = photoBytes,
                videoCount = videoCount,
                videoBytes = videoBytes,
                folders = folders.values.map { Share(it.first, it.second, it.third) }.sortedWith(compareByDescending<Share> { it.bytes }.thenBy { it.name }),
                types = types.map { (k, v) -> Share(k, v.first, v.second) }.sortedWith(compareByDescending<Share> { it.bytes }.thenBy { it.name }),
                largest = items.sortedWith(compareByDescending<MediaItem> { it.size }.thenBy { it.id }).take(LARGEST),
            )
        }

        /** "JPEG", "HEIC", "MP4": the part of the MIME type after the slash, or the file extension when that's vague. */
        fun typeName(item: MediaItem): String {
            val sub = item.mime.substringAfter('/', "").lowercase()
            val name = when (sub) {
                "", "*", "octet-stream" -> item.name.substringAfterLast('.', "").lowercase()
                "jpeg", "jpg" -> "jpeg"
                "quicktime" -> "mov"
                "x-matroska" -> "mkv"
                "3gpp" -> "3gp"
                else -> sub.removePrefix("x-")
            }
            return name.uppercase().ifBlank { "?" }
        }
    }
}
