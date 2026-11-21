package com.vknxworks.galleryctrl.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vknxworks.galleryctrl.ui.components.CtrlBottomSheet
import com.vknxworks.galleryctrl.ui.theme.CtrlColors
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vknxworks.galleryctrl.R
import com.vknxworks.galleryctrl.container
import com.vknxworks.galleryctrl.data.lock.LockVault
import com.vknxworks.galleryctrl.data.settings.AppSettings
import com.vknxworks.galleryctrl.data.settings.LockTimeout
import com.vknxworks.galleryctrl.data.settings.SlideInterval
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Slideshow
import com.vknxworks.galleryctrl.ui.components.Group
import com.vknxworks.galleryctrl.ui.components.GroupChoice
import com.vknxworks.galleryctrl.ui.components.GroupRow
import com.vknxworks.galleryctrl.ui.components.GroupSwitch
import com.vknxworks.galleryctrl.ui.components.ScreenTopBar
import com.vknxworks.galleryctrl.ui.lock.AppLock
import com.vknxworks.galleryctrl.ui.lock.PinRequest
import com.vknxworks.galleryctrl.ui.lock.findActivity
import kotlinx.coroutines.launch

/** Settings, opened from the You page (tap the profile picture). GalleryCtrl follows the phone's dark theme. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    fun set(transform: (AppSettings) -> AppSettings) {
        scope.launch { c.settings.update(transform) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(stringResource(R.string.settings))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 4.dp, bottom = 24.dp)) {
            Group(stringResource(R.string.group_library)) {
                GroupChoice(stringResource(R.string.grid_size), (2..6).map { it to it.toString() }, settings.columns, icon = Icons.Outlined.GridView) { v -> set { it.copy(columns = v) } }
                GroupSwitch(stringResource(R.string.on_this_day_setting), stringResource(R.string.on_this_day_sub), settings.onThisDay, Icons.Outlined.History) { on -> set { it.copy(onThisDay = on) } }
                GroupChoice(stringResource(R.string.slideshow_interval), SlideInterval.entries.map { it to stringResource(it.label) }, settings.slideInterval, icon = Icons.Outlined.Slideshow) { v -> set { it.copy(slideInterval = v) } }
                HiddenFoldersRow(settings.hiddenAlbums)
            }
            Group(stringResource(R.string.vault), footer = stringResource(R.string.vault_footer)) {
                GroupSwitch(stringResource(R.string.lock_vault), stringResource(R.string.lock_vault_sub), settings.lockVault, Icons.Outlined.Lock) { on -> set { it.copy(lockVault = on) } }
            }
            PrivacyAndSecurityGroup(settings)
            BackupGroup()
        }
    }
}

/** Privacy and security: the shared Ctrl series app lock, then hiding GalleryCtrl in screenshots. */
@Composable
private fun PrivacyAndSecurityGroup(settings: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val bioAvailable = remember { AppLock.biometricAvailable(context) }
    val lockOn = c.vault.enabled
    Group(stringResource(R.string.group_privacy), footer = stringResource(R.string.app_lock_footer)) {
        GroupSwitch(
            stringResource(R.string.app_lock),
            stringResource(R.string.app_lock_subtitle),
            checked = lockOn,
            icon = Icons.Outlined.Lock,
        ) { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }
        if (lockOn) {
            GroupRow(
                stringResource(R.string.lock_change_pin),
                stringResource(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                icon = Icons.Outlined.Password,
                showChevron = true,
                onClick = { AppLock.request = PinRequest.CHANGE },
            )
            GroupSwitch(
                stringResource(R.string.lock_fingerprint),
                stringResource(if (bioAvailable || c.vault.biometricEnabled) R.string.lock_fingerprint_subtitle else R.string.lock_fingerprint_unavailable),
                checked = c.vault.biometricEnabled,
                icon = Icons.Outlined.Fingerprint,
            ) { on ->
                if (!on) {
                    c.vault.disableBiometric()
                } else if (bioAvailable) {
                    val activity = context.findActivity()
                    val cipher = c.vault.biometricEnrolCipher()
                    if (activity == null || cipher == null) {
                        Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        AppLock.biometricPrompt(
                            activity,
                            context.getString(R.string.lock_fingerprint_enable_title),
                            context.getString(R.string.lock_fingerprint_enable_subtitle),
                            cipher,
                        ) { unlocked ->
                            if (!c.vault.finishBiometricEnrol(unlocked)) {
                                Toast.makeText(context, context.getString(R.string.lock_fingerprint_setup_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            GroupChoice(
                stringResource(R.string.lock_again),
                LockTimeout.entries.map { it to stringResource(it.label) },
                settings.lockTimeout,
                icon = Icons.Outlined.Timer,
            ) { t -> c.appScope.launch { c.settings.update { it.copy(lockTimeout = t) } } }
        }
        GroupSwitch(
            stringResource(R.string.hide_screenshots),
            stringResource(R.string.hide_screenshots_subtitle),
            checked = settings.hideScreenshots,
            icon = Icons.Outlined.VisibilityOff,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideScreenshots = on) } } }
        GroupSwitch(
            stringResource(R.string.hide_recents),
            stringResource(R.string.hide_recents_subtitle),
            checked = settings.hideRecents,
            icon = Icons.Outlined.Layers,
        ) { on -> c.appScope.launch { c.settings.update { it.copy(hideRecents = on) } } }
    }
}

/** Settings > Library > Hidden folders: the folders left out of the grids; tap one to show it again. */
@Composable
private fun HiddenFoldersRow(hidden: Set<Long>) {
    val c = LocalContext.current.container
    var open by remember { mutableStateOf(false) }
    GroupRow(
        stringResource(R.string.hidden_folders),
        stringResource(R.string.hidden_folders_sub),
        icon = Icons.Outlined.VisibilityOff,
        value = hidden.size.toString(),
        showChevron = true,
        onClick = { open = true },
    )
    if (open) {
        val all by c.media.items.collectAsStateWithLifecycle()
        val names = remember(all, hidden) {
            val byId = HashMap<Long, String>()
            for (item in all) if (item.albumId in hidden && item.albumId !in byId) byId[item.albumId] = item.album
            byId
        }
        CtrlBottomSheet(onDismissRequest = { open = false }) {
            Text(stringResource(R.string.hidden_folders), style = MaterialTheme.typography.titleLarge, color = CtrlColors.Text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (hidden.isEmpty()) {
                Text(stringResource(R.string.hidden_folders_empty), style = MaterialTheme.typography.bodyMedium, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            hidden.sortedBy { names[it]?.lowercase() ?: "\uFFFF" }.forEach { id ->
                val name = names[id]
                GroupRow(
                    name?.ifBlank { "—" } ?: stringResource(R.string.hidden_folder_gone),
                    stringResource(if (name != null) R.string.hidden_folder_show else R.string.hidden_folder_forget),
                    icon = if (name != null) Icons.Outlined.Visibility else Icons.Outlined.FolderOff,
                    onClick = { c.appScope.launch { c.settings.update { s -> s.copy(hiddenAlbums = s.hiddenAlbums - id) } } },
                )
            }
            Text(stringResource(R.string.hidden_folders_footer), style = MaterialTheme.typography.bodySmall, color = CtrlColors.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}
