package com.vknxworks.galleryctrl

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vknxworks.galleryctrl.media.MimeFilter
import com.vknxworks.galleryctrl.ui.gallery.ExternalViewer
import com.vknxworks.galleryctrl.ui.gallery.PickerScreen

/**
 * Opens a photo or video another app hands over ("Open with GalleryCtrl"): zoom, animated pictures, the video player.
 * Runs in the other app's task, behind the app lock like everything else, and only shows that one file: GalleryCtrl
 * keeps no copy of it and needs no media permission for it (the other app grants access to that file only).
 */
class ViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri: Uri? = intent?.data
        if (uri == null || uri.scheme != "content") {
            finish()
            return
        }
        val mime = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        setContent { CtrlWindow(this) { ExternalViewer(uri, mime, onClose = ::finish) } }
    }
}

/**
 * GalleryCtrl as a photo picker: another app asks for a photo or video (ACTION_GET_CONTENT or ACTION_PICK), you pick
 * one or several from your library, and that app gets read access to exactly those files. Behind the app lock; the
 * vault is never offered.
 */
class PickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val i = intent
        var type = i?.type
        // Old-style ACTION_PICK names a collection instead of a type.
        if (type == null) {
            val data = i?.data?.toString().orEmpty()
            type = when {
                data.startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) -> "video/*"
                data.startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()) -> "image/*"
                else -> null
            }
        }
        val filter = MimeFilter.of(type, i?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        val multiple = i?.action == Intent.ACTION_GET_CONTENT && i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            CtrlWindow(this) {
                PickerScreen(filter, multiple, onCancel = ::finish) { uris -> deliver(uris) }
            }
        }
    }

    private fun deliver(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
