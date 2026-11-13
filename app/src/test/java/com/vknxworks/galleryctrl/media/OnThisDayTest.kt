package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class OnThisDayTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private val newYork = ZoneId.of("America/New_York")

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun sameDateEarlierYearsOnly() {
        val today = LocalDate.of(2026, 11, 13)
        assertTrue(OnThisDay.matches(LocalDate.of(2025, 11, 13), today))
        assertTrue(OnThisDay.matches(LocalDate.of(2001, 11, 13), today))
        assertFalse(OnThisDay.matches(LocalDate.of(2026, 11, 13), today)) // Today itself isn't a memory.
        assertFalse(OnThisDay.matches(LocalDate.of(2027, 11, 13), today)) // A wrong camera clock in the future.
        assertFalse(OnThisDay.matches(LocalDate.of(2025, 11, 12), today))
        assertFalse(OnThisDay.matches(LocalDate.of(2025, 10, 13), today))
        assertEquals(3, OnThisDay.yearsAgo(LocalDate.of(2023, 11, 13), today))
    }

    @Test fun leapDays() {
        val feb29 = LocalDate.of(2024, 2, 29)
        // In a year without 29 February, those photos show on the 28th...
        assertTrue(OnThisDay.matches(feb29, LocalDate.of(2025, 2, 28)))
        assertTrue(OnThisDay.matches(LocalDate.of(2023, 2, 28), LocalDate.of(2025, 2, 28)))
        // ...but in a leap year they wait for the 29th, and the 28th shows only the 28th.
        assertFalse(OnThisDay.matches(LocalDate.of(2020, 2, 29), LocalDate.of(2028, 2, 28)))
        assertTrue(OnThisDay.matches(LocalDate.of(2020, 2, 29), LocalDate.of(2028, 2, 29)))
        assertTrue(OnThisDay.matches(LocalDate.of(2021, 2, 28), LocalDate.of(2028, 2, 28)))
        assertFalse(OnThisDay.matches(LocalDate.of(2021, 2, 28), LocalDate.of(2028, 2, 29)))
        // Never on 1 March.
        assertFalse(OnThisDay.matches(feb29, LocalDate.of(2025, 3, 1)))
    }

    @Test fun exifCameraClockWins() {
        // 23:30 in New York on 12 November = 04:30 UTC on the 13th = 05:30 in Brussels on the 13th.
        val taken = utc(2020, 11, 13, 4, 30)
        assertEquals(LocalDate.of(2020, 11, 12), OnThisDay.localDate(taken, "2020:11:12 23:30:00", "-05:00", brussels))
        // Without the camera time, the EXIF offset gives the same day.
        assertEquals(LocalDate.of(2020, 11, 12), OnThisDay.localDate(taken, null, "-05:00", brussels))
        // Without either (a video): the phone's zone decides.
        assertEquals(LocalDate.of(2020, 11, 13), OnThisDay.localDate(taken, null, null, brussels))
        assertEquals(LocalDate.of(2020, 11, 12), OnThisDay.localDate(taken, null, null, newYork))
    }

    @Test fun exifParsing() {
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59), OnThisDay.parseExifDateTime("2024:02:29 23:59:59"))
        assertNull(OnThisDay.parseExifDateTime("2023:02:29 10:00:00")) // Not a real date.
        assertNull(OnThisDay.parseExifDateTime("0000:00:00 00:00:00"))
        assertNull(OnThisDay.parseExifDateTime("    :  :     :  :  "))
        assertNull(OnThisDay.parseExifDateTime(null))
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), OnThisDay.parseOffset("+05:30"))
        assertEquals(ZoneOffset.ofHours(-8), OnThisDay.parseOffset("-08:00"))
        assertNull(OnThisDay.parseOffset("garbage"))
        assertNull(OnThisDay.parseOffset(""))
    }

    @Test fun selectUsesEachPhotosOwnDay() {
        data class P(val name: String, val utc: Long, val exif: String?, val offset: String?)
        val today = LocalDate.of(2026, 11, 13)
        val items = listOf(
            // In Brussels' calendar this is the 14th, but it was taken on the evening of the 13th in New York.
            P("ny-evening", utc(2019, 11, 14, 3, 0), "2019:11:13 22:00:00", "-05:00"),
            // In Brussels' calendar the 13th, but taken on the morning of the 14th in Tokyo.
            P("tokyo-morning", utc(2018, 11, 13, 22, 0), null, "+09:00"),
            P("plain", utc(2021, 11, 13, 12, 0), null, null),
            P("this-year", utc(2026, 11, 13, 9, 0), null, null),
            P("other-day", utc(2022, 6, 1, 12, 0), null, null),
        )
        val got = OnThisDay.select(items, { it.utc }, today, brussels) { OnThisDay.localDate(it.utc, it.exif, it.offset, brussels) }
        // Newest year first.
        assertEquals(listOf("plain", "ny-evening"), got.map { it.first.name })
    }

    @Test fun candidatesCoverEveryTimeZone() {
        val today = LocalDate.of(2026, 11, 13)
        // Up to a day off in the phone's zone is still a candidate; further isn't.
        assertTrue(OnThisDay.mightMatch(utc(2020, 11, 14, 3, 0), today, brussels))
        assertTrue(OnThisDay.mightMatch(utc(2020, 11, 12, 20, 0), today, brussels))
        assertFalse(OnThisDay.mightMatch(utc(2020, 11, 16, 12, 0), today, brussels))
        // New Year: a photo from the evening of 31 December 2025 (local) is last year's.
        assertTrue(OnThisDay.mightMatch(utc(2026, 1, 1, 2, 0), LocalDate.of(2026, 12, 31), newYork))
    }
}
