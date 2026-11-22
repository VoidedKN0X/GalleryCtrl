package com.vknxworks.galleryctrl

import android.content.Context
import android.widget.Toast
import com.vknxworks.galleryctrl.data.lock.LockVault
import com.vknxworks.galleryctrl.data.settings.SettingsRepository
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MediaRepository
import com.vknxworks.galleryctrl.media.Sharing
import com.vknxworks.galleryctrl.media.Vault
import com.vknxworks.galleryctrl.ui.lock.AppLock
import com.vknxworks.galleryctrl.ui.lock.VaultLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)
    val media = MediaRepository(context, appScope)
    /** The encrypted private vault for photos and videos. */
    val photoVault = Vault(context, appScope)
    /** Dates read from the library's photos for On this day (memory only). */
    val photoInfo = com.vknxworks.galleryctrl.media.PhotoInfo(context)

    /**
     * The library without the folders hidden in Settings: what Photos, Albums, search, On this day and the duplicate
     * finder show. Its `loading` stays true until both the library and the saved settings are read, so neither an
     * empty library nor a hidden folder shows for a moment at start. The viewer and the storage page use
     * [MediaRepository.items] (everything).
     */
    val visible: StateFlow<VisibleLibrary> = combine(media.items, media.loading, settings.hiddenAlbums) { items, loading, hidden ->
        VisibleLibrary(if (hidden.isEmpty()) items else items.filterNot { it.albumId in hidden }, loading)
    }.flowOn(Dispatchers.Default).stateIn(appScope, SharingStarted.Eagerly, VisibleLibrary(emptyList(), loading = true))

    /** [MediaRepository.firstRows] without the hidden folders. */
    val visibleFirstRows: StateFlow<List<MediaItem>> = combine(media.firstRows, settings.hiddenAlbums) { rows, hidden ->
        if (hidden.isEmpty()) rows else rows.filterNot { it.albumId in hidden }
    }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /** Which tab to open. */
    val openTab = MutableStateFlow<Int?>(null)

    /** The list the viewer pages through (the grid it was opened from) and where to start. */
    val viewerList = MutableStateFlow<List<MediaItem>>(emptyList())
    val viewerStart = MutableStateFlow(0)
    val viewerSlideshow = MutableStateFlow(false)

    /**
     * Originals that were moved into the vault and still have to be deleted from the library. Android's delete
     * confirmation for them is shown by the main screen as soon as it is on screen (VaultDeletePrompt), also when the
     * screen that started the move was left while it was encrypting.
     */
    val vaultPendingDelete = MutableStateFlow<List<MediaItem>>(emptyList())

    /**
     * Encrypts [items] into the vault in the app's own scope, so it always finishes (leaving the screen used to cut it
     * off before the delete request, leaving the photo both in the vault and in the library). Says so if some couldn't
     * be stored; those stay in the library.
     */
    fun moveToVault(items: List<MediaItem>) {
        if (items.isEmpty()) return
        appScope.launch {
            val stored = runCatching { photoVault.add(items) }.getOrDefault(emptyList())
            if (stored.isNotEmpty()) vaultPendingDelete.value = vaultPendingDelete.value + stored
            val failed = items.size - stored.size
            if (failed > 0) {
                Toast.makeText(context, context.resources.getQuantityString(R.plurals.vault_add_failed, failed, failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "galleryctrl")

    init {
        AppLock.onLocked = { onLocked() }
        // Leftover share copies from last time, off the main thread.
        appScope.launch(Dispatchers.IO) { Sharing.clearCache(context) }
        // The photo map was removed: give back its map file's read grant and forget the setting, once.
        appScope.launch(Dispatchers.IO) { settings.removeMapLeftovers() }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, and skipping it would make the lock pointless, so the vault
     * (the photos it protects) and the settings are deleted and the lock turned off. Your library isn't touched.
     */
    suspend fun forgetEverything() {
        VaultLock.open = false
        vaultPendingDelete.value = emptyList()
        photoVault.deleteAll()
        settings.clear()
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        // The vault asks for its PIN again after the app has locked.
        VaultLock.open = false
        photoVault.closeCache()
        photoInfo.clear()
        com.vknxworks.galleryctrl.media.Duplicates.clear()
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

/** The library as the grids show it (hidden folders left out), and whether it's still being read. */
data class VisibleLibrary(val items: List<MediaItem>, val loading: Boolean)

val Context.container: AppContainer
    get() = (applicationContext as GalleryCtrlApp).container
