package com.vknxworks.galleryctrl.media

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * "On this day": photos and videos taken on today's date in earlier years. Pure date logic, so it can be tested.
 *
 * The day that counts is the day **where the photo was taken**, not where the phone is now. A photo taken at 23:30
 * in New York is from that evening even when you look at it in Europe, where it was already the next morning:
 * - the camera's own clock (EXIF DateTimeOriginal) is used when the photo has it;
 * - otherwise MediaStore's time (UTC) shifted by the photo's EXIF OffsetTimeOriginal;
 * - otherwise (videos, screenshots) MediaStore's time in the phone's time zone.
 *
 * Photos from 29 February show on 28 February in years without a 29 February.
 */
object OnThisDay {
    // Strict: a "29 February" in a year without one is a broken date, not quietly the 28th.
    private val exifFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    /** "2024:02:29 23:30:00" -> that local date and time; null when missing or not a real date. */
    fun parseExifDateTime(s: String?): LocalDateTime? {
        val t = s?.trim()?.takeIf { it.length >= 19 }?.substring(0, 19) ?: return null
        // Some cameras write blanks or zeros when the clock wasn't set.
        if (t.startsWith("0000") || t.isBlank()) return null
        return try { LocalDateTime.parse(t, exifFormat) } catch (_: DateTimeParseException) { null }
    }

    /** "+02:00", "-05:30" (EXIF OffsetTime*) -> the offset; null when missing or malformed. */
    fun parseOffset(s: String?): ZoneOffset? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try { ZoneOffset.of(t) } catch (_: Exception) { null }
    }

    /** The day [takenUtcMs] (MediaStore's time) was in the place it was taken; see the class comment for the order. */
    fun localDate(takenUtcMs: Long, exifDateTime: String?, exifOffset: String?, phoneZone: ZoneId): LocalDate {
        parseExifDateTime(exifDateTime)?.let { return it.toLocalDate() }
        val instant = Instant.ofEpochMilli(takenUtcMs)
        parseOffset(exifOffset)?.let { return instant.atOffset(it).toLocalDate() }
        return instant.atZone(phoneZone).toLocalDate()
    }

    /** True when [day] is today's date in an earlier year (29 February counts as 28 February in other years). */
    fun matches(day: LocalDate, today: LocalDate): Boolean {
        if (day.year >= today.year) return false
        if (day.monthValue == today.monthValue && day.dayOfMonth == today.dayOfMonth) return true
        return day.monthValue == 2 && day.dayOfMonth == 29 && today.monthValue == 2 && today.dayOfMonth == 28 && !today.isLeapYear
    }

    /**
     * Cheap first pass on MediaStore's time alone: could this item match once its own time zone is known? Any time
     * zone is within a day of the phone's, so the day before and after are kept too.
     */
    fun mightMatch(takenUtcMs: Long, today: LocalDate, phoneZone: ZoneId): Boolean {
        val d = Instant.ofEpochMilli(takenUtcMs).atZone(phoneZone).toLocalDate()
        return matches(d, today) || matches(d.minusDays(1), today) || matches(d.plusDays(1), today)
    }

    fun yearsAgo(day: LocalDate, today: LocalDate): Int = today.year - day.year

    /**
     * The items for [today], newest year first (and in time order within a year). [dayOf] gives each candidate's
     * real local day (reading its EXIF when needed); it's only called for items [mightMatch] lets through.
     */
    fun <T> select(items: List<T>, takenUtcMs: (T) -> Long, today: LocalDate, phoneZone: ZoneId, dayOf: (T) -> LocalDate): List<Pair<T, LocalDate>> =
        items.asSequence()
            .filter { mightMatch(takenUtcMs(it), today, phoneZone) }
            .map { it to dayOf(it) }
            .filter { matches(it.second, today) }
            .sortedWith(compareByDescending<Pair<T, LocalDate>> { it.second.year }.thenBy { takenUtcMs(it.first) })
            .toList()
}
