package com.vknxworks.galleryctrl.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GalleryCtrl's settings as a JSON file you keep (Settings > Back up and restore). Only switches and choices: no PIN,
 * no keys, nothing from the vault and no photos. Every field is optional, so a file from an older or newer version
 * restores what it can.
 */
@Serializable
data class SettingsBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** GalleryCtrl version that wrote the file, for information. */
    val appVersion: String? = null,
    /** ISO time the file was written. */
    val createdAt: String? = null,
    val columns: Int? = null,
    val onThisDay: Boolean? = null,
    val slideInterval: String? = null,
    val lockVault: Boolean? = null,
    val lockTimeout: String? = null,
    val hideScreenshots: Boolean? = null,
    val hideRecents: Boolean? = null,
    val hiddenFolders: List<BackupFolder>? = null,
    val pinnedFolders: List<BackupFolder>? = null,
) {
    companion object {
        const val FORMAT = "galleryctrl-settings"
        const val VERSION = 1
    }
}

/** A folder by its Android media-library id (the same for the same folder path on any phone), with its name to show. */
@Serializable
data class BackupFolder(val id: Long, val name: String = "")

/** Thrown for a file that isn't a GalleryCtrl settings file, or one from a newer, incompatible version. */
class BackupFormatException(val newer: Boolean) : Exception()

object SettingsBackups {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** [names] gives a folder's name for its id, for the file (ids alone mean nothing to a person reading it). */
    fun export(s: AppSettings, names: (Long) -> String?, appVersion: String, now: String): String =
        json.encodeToString(
            SettingsBackup.serializer(),
            SettingsBackup(
                appVersion = appVersion,
                createdAt = now,
                columns = s.columns,
                onThisDay = s.onThisDay,
                slideInterval = s.slideInterval.name,
                lockVault = s.lockVault,
                lockTimeout = s.lockTimeout.name,
                hideScreenshots = s.hideScreenshots,
                hideRecents = s.hideRecents,
                hiddenFolders = s.hiddenAlbums.sorted().map { BackupFolder(it, names(it).orEmpty()) },
                pinnedFolders = s.pinnedAlbums.map { BackupFolder(it, names(it).orEmpty()) },
            ),
        )

    /** Reads and checks a settings file. */
    fun parse(text: String): SettingsBackup {
        val b = runCatching { json.decodeFromString(SettingsBackup.serializer(), text) }.getOrNull()
            ?: throw BackupFormatException(newer = false)
        if (b.format != SettingsBackup.FORMAT) throw BackupFormatException(newer = false)
        if (b.version > SettingsBackup.VERSION) throw BackupFormatException(newer = true)
        return b
    }

    /**
     * The settings after restoring [b] over [cur]. Library settings, hidden and pinned folders are replaced by the
     * file's. Privacy and lock settings can only become stricter: a file never turns off "Ask for the PIN for the
     * vault", Hide in screenshots or Hide in recent apps, and never makes "Lock again" wait longer. The app lock itself
     * (and its PIN) is never part of the file.
     */
    fun apply(b: SettingsBackup, cur: AppSettings): AppSettings {
        val timeout = b.lockTimeout?.let { n -> LockTimeout.entries.firstOrNull { it.name == n } }
        return cur.copy(
            columns = b.columns?.takeIf { it in 2..6 } ?: cur.columns,
            onThisDay = b.onThisDay ?: cur.onThisDay,
            slideInterval = b.slideInterval?.let { n -> SlideInterval.entries.firstOrNull { it.name == n } } ?: cur.slideInterval,
            lockVault = cur.lockVault || b.lockVault == true,
            lockTimeout = if (timeout != null && stricter(timeout, cur.lockTimeout)) timeout else cur.lockTimeout,
            hideScreenshots = cur.hideScreenshots || b.hideScreenshots == true,
            hideRecents = cur.hideRecents || b.hideRecents == true,
            hiddenAlbums = b.hiddenFolders?.map { it.id }?.toSet() ?: cur.hiddenAlbums,
            pinnedAlbums = b.pinnedFolders?.map { it.id }?.distinct() ?: cur.pinnedAlbums,
        )
    }

    /** Whether [a] asks again at least as soon as [b] ("only after a restart" is the longest). */
    fun stricter(a: LockTimeout, b: LockTimeout): Boolean = a.ordinal <= b.ordinal
}
