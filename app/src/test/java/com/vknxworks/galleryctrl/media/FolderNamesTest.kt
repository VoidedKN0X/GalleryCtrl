package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNamesTest {
    @Test fun cleanNames() {
        assertEquals("Holiday", FolderNames.clean("  Holiday "))
        assertNull(FolderNames.clean(""))
        assertNull(FolderNames.clean("   "))
        assertNull(FolderNames.clean("a/b"))
        assertNull(FolderNames.clean("a\\b"))
        assertNull(FolderNames.clean(".."))
        assertNull(FolderNames.clean(".hidden"))
        assertNull(FolderNames.clean("x".repeat(81)))
        assertEquals("x".repeat(80), FolderNames.clean("x".repeat(80)))
    }

    @Test fun paths() {
        assertEquals("Pictures/Holiday/", FolderNames.newFolderPath("Holiday"))
        assertNull(FolderNames.newFolderPath("a/b"))
        assertEquals("DCIM/Camera/", FolderNames.normalisePath("/DCIM/Camera"))
        assertEquals("DCIM/Camera/", FolderNames.normalisePath("DCIM/Camera/"))
        assertEquals("", FolderNames.normalisePath("/"))
    }

    @Test fun extensionIsKept() {
        assertEquals("beach.jpg", FolderNames.withExtension("beach", "IMG_0001.jpg"))
        assertEquals("beach.2024.mp4", FolderNames.withExtension("beach.2024", "VID.mp4"))
        assertEquals("beach", FolderNames.withExtension("beach", "noext"))
        assertNull(FolderNames.withExtension(" ", "a.jpg"))
        assertEquals("IMG_0001", FolderNames.baseName("IMG_0001.jpg"))
        assertEquals(".nomedia", FolderNames.baseName(".nomedia"))
    }
}
