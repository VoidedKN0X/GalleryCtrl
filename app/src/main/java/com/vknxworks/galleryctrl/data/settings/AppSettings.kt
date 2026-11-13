package com.vknxworks.galleryctrl.data.settings

import android.content.Context
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
    /** Remove GPS location from photos you share (the original keeps it). */
    val stripLocation: Boolean = true,
    val onThisDay: Boolean = true,
    val slideInterval: SlideInterval = SlideInterval.S4,
    /** The vault asks for the app PIN (when the app lock is on). */
    val lockVault: Boolean = true,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val hideScreenshots: Boolean = false,
    val hideRecents: Boolean = false,
    /** The Mapsforge .map file picked for the photo map (a content:// URI kept with a persisted grant), or "". */
    val mapFile: String = "",
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val columns = intPreferencesKey("columns")
        val stripLocation = booleanPreferencesKey("strip_location")
        val onThisDay = booleanPreferencesKey("on_this_day")
        val slideInterval = stringPreferencesKey("slide_interval")
        val lockVault = booleanPreferencesKey("lock_vault")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
        val mapFile = stringPreferencesKey("map_file")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        columns = (this[K.columns] ?: 4).coerceIn(2, 6),
        stripLocation = this[K.stripLocation] ?: true,
        onThisDay = this[K.onThisDay] ?: true,
        slideInterval = enumOr(this[K.slideInterval], SlideInterval.S4),
        lockVault = this[K.lockVault] ?: true,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
        mapFile = this[K.mapFile] ?: "",
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.columns] = s.columns
                p[K.stripLocation] = s.stripLocation
                p[K.onThisDay] = s.onThisDay
                p[K.slideInterval] = s.slideInterval.name
                p[K.lockVault] = s.lockVault
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
                p[K.mapFile] = s.mapFile
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
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
