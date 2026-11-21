package com.vknxworks.galleryctrl.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vknxworks.galleryctrl.BuildConfig
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.data.settings.BackupFormatException
import com.vknxworks.galleryctrl.data.settings.SettingsBackup
import com.vknxworks.galleryctrl.data.settings.SettingsBackups
import com.vknxworks.galleryctrl.ui.components.CtrlAlertDialog
import com.vknxworks.galleryctrl.ui.components.Group
import com.vknxworks.galleryctrl.ui.components.GroupRow
import com.vknxworks.galleryctrl.ui.lock.AppLock
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Settings > Back up and restore: GalleryCtrl's settings as a JSON file in a place you pick. Only switches and
 * choices (grid, On this day, slideshow, privacy options, hidden and pinned folders): never the PIN, keys, the
 * vault or photos. Restoring shows what will be replaced and asks first, and never makes privacy settings weaker.
 */
@Composable
fun BackupGroup() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<SettingsBackup?>(null) }

    fun folderName(id: Long): String? = c.media.items.value.firstOrNull { it.albumId == id }?.album

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val names = withContext(Dispatchers.Default) {
                    val map = HashMap<Long, String>()
                    for (item in c.media.items.value) if (item.albumId !in map) map[item.albumId] = item.album
                    map
                }
                val text = SettingsBackups.export(c.settings.settings.value, { names[it] }, BuildConfig.VERSION_NAME, OffsetDateTime.now().toString())
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // A settings file is a few kilobytes; anything far bigger isn't one.
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) throw BackupFormatException(newer = false)
                        }
                        out.toString(Charsets.UTF_8.name())
                    } ?: throw IOException("no stream")
                }
                SettingsBackups.parse(text)
            }.onSuccess { pending = it }.onFailure { e ->
                val msg = when {
                    e is BackupFormatException && e.newer -> R.string.backup_too_new
                    e is BackupFormatException -> R.string.backup_not_valid
                    else -> R.string.backup_read_failed
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    Group(stringResource(R.string.backup_title), footer = stringResource(R.string.backup_footer)) {
        GroupRow(
            stringResource(R.string.backup_save), stringResource(R.string.backup_save_sub), Icons.Outlined.FileDownload,
            onClick = { AppLock.expectReturn(); save.launch("galleryctrl-settings-${LocalDate.now()}.json") },
        )
        GroupRow(
            stringResource(R.string.backup_restore), stringResource(R.string.backup_restore_sub), Icons.Outlined.FileUpload,
            onClick = { AppLock.expectReturn(); open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    pending?.let { b ->
        val none = stringResource(R.string.backup_none)
        fun names(list: List<com.vknxworks.galleryctrl.data.settings.BackupFolder>?): String =
            list?.takeIf { it.isNotEmpty() }?.joinToString(", ") { f -> f.name.ifBlank { folderName(f.id) ?: "#${f.id}" } } ?: none
        val body = stringResource(
            R.string.backup_restore_body,
            b.createdAt?.take(10) ?: "?",
            names(b.hiddenFolders),
            names(b.pinnedFolders),
        )
        CtrlAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        c.settings.update { cur -> SettingsBackups.apply(b, cur) }
                        Toast.makeText(context, R.string.backup_restored, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.backup_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
        )
    }
}

private const val MAX_BYTES = 256 * 1024
