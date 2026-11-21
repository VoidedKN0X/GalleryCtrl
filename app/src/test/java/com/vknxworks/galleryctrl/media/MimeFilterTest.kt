package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeFilterTest {
    @Test fun wildcardImage() {
        val f = MimeFilter.of("image/*", null)
        assertTrue(f.accepts("image/jpeg"))
        assertTrue(f.accepts("IMAGE/PNG"))
        assertFalse(f.accepts("video/mp4"))
        assertTrue(f.photos)
        assertFalse(f.videos)
    }

    @Test fun exactType() {
        val f = MimeFilter.of("image/png", null)
        assertTrue(f.accepts("image/png"))
        assertFalse(f.accepts("image/jpeg"))
    }

    @Test fun extraNarrowsAnything() {
        val f = MimeFilter.of("*/*", arrayOf("image/jpeg", "video/*"))
        assertTrue(f.accepts("image/jpeg"))
        assertTrue(f.accepts("video/mp4"))
        assertFalse(f.accepts("image/png"))
    }

    @Test fun anything() {
        val f = MimeFilter.of("*/*", null)
        assertTrue(f.accepts("image/heic"))
        assertTrue(f.accepts("video/quicktime"))
    }

    @Test fun oldPickTypesAndNothing() {
        assertTrue(MimeFilter.of("vnd.android.cursor.dir/image", null).accepts("image/gif"))
        assertFalse(MimeFilter.of("vnd.android.cursor.dir/image", null).accepts("video/mp4"))
        assertTrue(MimeFilter.of("vnd.android.cursor.dir/video", null).accepts("video/mp4"))
        val none = MimeFilter.of(null, null)
        assertTrue(none.accepts("image/jpeg"))
        assertTrue(none.accepts("video/mp4"))
    }
}
