package com.vknxworks.galleryctrl

import android.content.Context
import com.vknxworks.galleryctrl.data.lock.LockVault
import com.vknxworks.galleryctrl.data.settings.SettingsRepository
import com.vknxworks.galleryctrl.media.MediaItem
import com.vknxworks.galleryctrl.media.MediaRepository
import com.vknxworks.galleryctrl.media.Sharing
import com.vknxworks.galleryctrl.media.Vault
import com.vknxworks.galleryctrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Which tab to open. */
    val openTab = MutableStateFlow<Int?>(null)

    /** The list the viewer pages through (the grid it was opened from) and where to start. */
    val viewerList = MutableStateFlow<List<MediaItem>>(emptyList())
    val viewerStart = MutableStateFlow(0)
    val viewerSlideshow = MutableStateFlow(false)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "galleryctrl")

    init {
        AppLock.onLocked = { onLocked() }
        // Leftover share copies from last time, off the main thread.
        appScope.launch(Dispatchers.IO) { Sharing.clearCache(context) }
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
        photoVault.deleteAll()
        settings.clear()
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        photoVault.closeCache()
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as GalleryCtrlApp).container
