package com.vknxworks.galleryctrl.media

/**
 * Which photos and videos another app asked for when it opened GalleryCtrl to pick (pure, unit-tested). Built from
 * the intent's type (all images, "image/jpeg", anything), the extra MIME types it may list, and the old-style
 * "vnd.android.cursor.dir/image" types of ACTION_PICK.
 */
class MimeFilter(patterns: List<String>) {
    private val patterns: List<String> = patterns.map { normalise(it) }.filter { it.isNotEmpty() }.ifEmpty { listOf("image/*", "video/*") }

    fun accepts(mime: String): Boolean {
        val m = mime.lowercase()
        return patterns.any { p ->
            when {
                p == "*/*" || p == "*" -> true
                p.endsWith("/*") -> m.startsWith(p.dropLast(1))
                else -> m == p
            }
        }
    }

    /** Whether photos can be picked at all (to word the title). */
    val photos: Boolean get() = patterns.any { it == "*/*" || it == "*" || it.startsWith("image/") }
    val videos: Boolean get() = patterns.any { it == "*/*" || it == "*" || it.startsWith("video/") }

    companion object {
        private fun normalise(type: String): String {
            val t = type.trim().lowercase().substringBefore(';').trim()
            return when (t) {
                "vnd.android.cursor.dir/image" -> "image/*"
                "vnd.android.cursor.dir/video" -> "video/*"
                else -> t
            }
        }

        /** [type] is the intent's type; [extra] the EXTRA_MIME_TYPES, which narrow a wide type such as "anything". */
        fun of(type: String?, extra: Array<String>?): MimeFilter {
            val list = extra?.filter { it.isNotBlank() }.orEmpty()
            return MimeFilter(if (list.isNotEmpty()) list else listOfNotNull(type))
        }
    }
}
