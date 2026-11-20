package com.vknxworks.galleryctrl.data.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.galleryctrl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "galleryctrl")

/** How long GalleryCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

enum class SlideInterval(@StringRes val label: Int, val seconds: Int) {
    S2(R.string.slide_2, 2), S4(R.string.slide_4, 4), S8(R.string.slide_8, 8),
}

data class AppSettings(
    val columns: Int = 4,
    val onThisDay: Boolean = true,
    val slideInterval: SlideInterval = SlideInterval.S4,
    /** The vault asks for the app PIN (when the app lock is on). */
    val lockVault: Boolean = true,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val hideScreenshots: Boolean = false,
    val hideRecents: Boolean = false,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val columns = intPreferencesKey("columns")
        val onThisDay = booleanPreferencesKey("on_this_day")
        val slideInterval = stringPreferencesKey("slide_interval")
        val lockVault = booleanPreferencesKey("lock_vault")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        /** Left by versions that had the photo map; only [removeMapLeftovers] touches it. */
        val legacyMapFile = stringPreferencesKey("map_file")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        columns = (this[K.columns] ?: 4).coerceIn(2, 6),
        onThisDay = this[K.onThisDay] ?: true,
        slideInterval = enumOr(this[K.slideInterval], SlideInterval.S4),
        lockVault = this[K.lockVault] ?: true,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.columns] = s.columns
                p[K.onThisDay] = s.onThisDay
                p[K.slideInterval] = s.slideInterval.name
                p[K.lockVault] = s.lockVault
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }

    /**
     * One-time clean-up after the photo map was removed. Older versions saved the picked map file under "map_file"
     * and kept a persisted read grant for it; that was the only persisted URI grant GalleryCtrl ever took. If the
     * old setting is still there, every persisted grant is given back and the setting deleted, so this does nothing
     * on later starts. Call off the main thread.
     */
    suspend fun removeMapLeftovers() {
        try {
            if (!context.appDataStore.data.first().contains(K.legacyMapFile)) return
            runCatching {
                val resolver = context.contentResolver
                for (grant in resolver.persistedUriPermissions) {
                    runCatching { resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
            context.appDataStore.edit { it.remove(K.legacyMapFile) }
        } catch (_: IOException) {
            // Storage failed: tried again at the next start.
        }
    }

    suspend fun clear() {
        try {
            context.appDataStore.edit { it.clear() }
        } catch (_: IOException) {
        }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
