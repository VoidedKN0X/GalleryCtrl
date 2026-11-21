package com.vknxworks.galleryctrl.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    @Test fun roundTrip() {
        val s = AppSettings(columns = 5, onThisDay = false, slideInterval = SlideInterval.S8, hiddenAlbums = setOf(3L, 1L), pinnedAlbums = listOf(9L, 2L))
        val text = SettingsBackups.export(s, { "f$it" }, "1.0", "2026-11-21T10:00:00Z")
        val back = SettingsBackups.apply(SettingsBackups.parse(text), AppSettings())
        assertEquals(5, back.columns)
        assertEquals(false, back.onThisDay)
        assertEquals(SlideInterval.S8, back.slideInterval)
        assertEquals(setOf(1L, 3L), back.hiddenAlbums)
        assertEquals(listOf(9L, 2L), back.pinnedAlbums)
    }

    @Test fun neverWeakensPrivacy() {
        val cur = AppSettings(lockVault = true, hideScreenshots = true, hideRecents = true, lockTimeout = LockTimeout.MINUTE_1)
        val file = SettingsBackup(lockVault = false, hideScreenshots = false, hideRecents = false, lockTimeout = LockTimeout.RESTART.name)
        val r = SettingsBackups.apply(file, cur)
        assertTrue(r.lockVault)
        assertTrue(r.hideScreenshots)
        assertTrue(r.hideRecents)
        assertEquals(LockTimeout.MINUTE_1, r.lockTimeout)
        val stricter = SettingsBackups.apply(SettingsBackup(lockTimeout = LockTimeout.IMMEDIATELY.name, hideRecents = true), AppSettings(lockTimeout = LockTimeout.HOUR_1))
        assertEquals(LockTimeout.IMMEDIATELY, stricter.lockTimeout)
        assertTrue(stricter.hideRecents)
    }

    @Test(expected = BackupFormatException::class) fun rejectsOtherFiles() {
        SettingsBackups.parse("{\"format\":\"printctrl-settings\",\"version\":1}")
    }

    @Test fun rejectsNewer() {
        try {
            SettingsBackups.parse("{\"format\":\"galleryctrl-settings\",\"version\":99}")
            throw AssertionError("accepted")
        } catch (e: BackupFormatException) {
            assertTrue(e.newer)
        }
    }

    @Test fun badValuesKeepCurrent() {
        val r = SettingsBackups.apply(SettingsBackup(columns = 40, slideInterval = "NOPE"), AppSettings(columns = 3))
        assertEquals(3, r.columns)
        assertEquals(SlideInterval.S4, r.slideInterval)
    }
}
