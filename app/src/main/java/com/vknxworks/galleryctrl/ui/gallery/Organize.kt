package com.vknxworks.galleryctrl.ui.gallery

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.media.FolderNames
import com.vknxworks.galleryctrl.media.MediaEdits
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MediaRequests
import com.vknxworks.galleryctrl.ui.components.CtrlAlertDialog
import com.vknxworks.galleryctrl.ui.components.CtrlBottomSheet
import com.vknxworks.galleryctrl.ui.components.CtrlProgressBar
import com.vknxworks.galleryctrl.ui.components.CtrlTextField
import com.vknxworks.galleryctrl.ui.components.GroupRow
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import kotlinx.coroutines.launch

/** Move, copy or rename, as asked for by a grid's selection or the viewer. Put [OrganizeDialogs] on the same screen. */
@Stable
class OrganizeState {
    internal var pickFor by mutableStateOf<Pair<Boolean, List<MediaItem>>?>(null) // (move?, items)
    internal var newFolderFor by mutableStateOf<Pair<Boolean, List<MediaItem>>?>(null)
    internal var renaming by mutableStateOf<MediaItem?>(null)
    internal var copying by mutableStateOf<Pair<Int, Int>?>(null) // (done, of)

    fun move(items: List<MediaItem>) { if (items.isNotEmpty()) pickFor = true to items }
    fun copy(items: List<MediaItem>) { if (items.isNotEmpty()) pickFor = false to items }
    fun rename(item: MediaItem) { renaming = item }
}

@Composable
fun rememberOrganize(): OrganizeState = remember { OrganizeState() }

/**
 * The folder sheet, the name dialogs and the copy progress for [state]. Moving and renaming ask Android for permission
 * to change the files first ("Allow GalleryCtrl to modify…"); nothing happens if you say no. [onDone] runs after a
 * move, copy or rename finished (to clear a selection).
 */
@Composable
fun OrganizeDialogs(state: OrganizeState, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val c = context.container
    /** Runs once Android's write confirmation came back with OK. */
    var afterGrant by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val then = afterGrant
        afterGrant = null
        if (res.resultCode == Activity.RESULT_OK && then != null) c.appScope.launch { then() }
    }

    fun askToWrite(items: List<MediaItem>, then: suspend () -> Unit) {
        afterGrant = then
        runCatching { grant.launch(IntentSenderRequest.Builder(MediaRequests.write(context, items).intentSender).build()) }
            .onFailure { afterGrant = null; Toast.makeText(context, R.string.organize_failed, Toast.LENGTH_LONG).show() }
    }

    fun toastCount(done: Int, of: Int, pluralDone: Int) {
        val text = if (done == of) context.resources.getQuantityString(pluralDone, done, done)
        else context.getString(R.string.organize_partly, done, of)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    fun run(move: Boolean, items: List<MediaItem>, path: String) {
        if (move) {
            askToWrite(items) {
                val moved = MediaEdits.move(context, items, path)
                c.media.reload()
                toastCount(moved, items.size, R.plurals.moved_count)
                onDone()
            }
        } else {
            state.copying = 0 to items.size
            c.appScope.launch {
                val copied = try {
                    MediaEdits.copy(context, items, path) { n -> c.appScope.launch { state.copying = n to items.size } }
                } finally {
                    state.copying = null
                }
                c.media.reload()
                toastCount(copied, items.size, R.plurals.copied_count)
                onDone()
            }
        }
    }

    state.pickFor?.let { (move, items) ->
        val all by c.media.items.collectAsStateWithLifecycle()
        val folders = remember(all) {
            all.groupBy { FolderNames.normalisePath(it.relativePath) }.filterKeys { it.isNotEmpty() }
                .map { (path, l) -> Triple(path, l.first().album, l.size) }
                .sortedBy { it.second.lowercase() }
        }
        CtrlBottomSheet(onDismissRequest = { state.pickFor = null }) {
            Text(
                stringResource(if (move) R.string.move_to_title else R.string.copy_to_title),
                style = MaterialTheme.typography.titleLarge, color = CtrlColors.Text,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.navigationBarsPadding()) {
                item(key = "new") {
                    GroupRow(stringResource(R.string.new_folder), stringResource(R.string.new_folder_sub, FolderNames.NEW_FOLDER_PARENT), icon = Icons.Outlined.CreateNewFolder, onClick = {
                        state.pickFor = null
                        state.newFolderFor = move to items
                    })
                }
                items(folders, key = { it.first }) { (path, name, count) ->
                    GroupRow(name.ifBlank { "—" }, path.trimEnd('/'), icon = Icons.Outlined.Folder, value = count.toString(), onClick = {
                        state.pickFor = null
                        run(move, items, path)
                    })
                }
                item(key = "end") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    state.newFolderFor?.let { (move, items) ->
        TextDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            label = stringResource(R.string.folder_name),
            confirm = stringResource(if (move) R.string.move else R.string.copy),
            valid = { FolderNames.newFolderPath(it) != null },
            onDismiss = { state.newFolderFor = null },
        ) { name ->
            state.newFolderFor = null
            FolderNames.newFolderPath(name)?.let { run(move, items, it) }
        }
    }

    state.renaming?.let { item ->
        val ext = item.name.substringAfterLast('.', "").takeIf { item.name.lastIndexOf('.') > 0 }
        TextDialog(
            title = stringResource(R.string.rename),
            initial = FolderNames.baseName(item.name),
            label = if (ext != null) stringResource(R.string.rename_hint, ext) else stringResource(R.string.file_name),
            confirm = stringResource(R.string.rename),
            valid = { FolderNames.withExtension(it, item.name) != null },
            onDismiss = { state.renaming = null },
        ) { base ->
            state.renaming = null
            val newName = FolderNames.withExtension(base, item.name) ?: return@TextDialog
            if (newName == item.name) return@TextDialog
            askToWrite(listOf(item)) {
                val ok = MediaEdits.rename(context, item, newName)
                c.media.reload()
                Toast.makeText(context, if (ok) R.string.renamed else R.string.rename_failed, Toast.LENGTH_LONG).show()
                if (ok) onDone()
            }
        }
    }

    state.copying?.let { (done, of) ->
        CtrlAlertDialog(
            onDismissRequest = {},
            title = { Text(pluralStringResource(R.plurals.copying_count, of, of), style = MaterialTheme.typography.titleLarge) },
            text = { CtrlProgressBar(if (of > 0) done.toFloat() / of else 0f, Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }
}

/** A one-line text dialog (folder name, new file name). */
@Composable
private fun TextDialog(title: String, initial: String, label: String, confirm: String, valid: (String) -> Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    CtrlAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            CtrlTextField(
                value = text,
                onValueChange = { text = it.take(120) },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = valid(text)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CtrlColors.Text) } },
    )
}
