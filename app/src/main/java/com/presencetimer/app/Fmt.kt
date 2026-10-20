package com.presencetimer.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Time / date helpers (ported from formatTime, localDateKey, etc. in index.html). */
object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val clockFormat = DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.getDefault())
    private val prettyFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", Locale.getDefault())

    fun time(ms: Long): String {
        val total = maxOf(0L, ms) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    fun dateKey(ts: Long): String = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toString()

    fun monthKey(ts: Long): String = dateKey(ts).substring(0, 7)

    fun nextMidnight(ts: Long): Long =
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** Countdown to the next local midnight, as HH:MM:SS. */
    fun dayLeft(now: Long): String {
        val totalSeconds = maxOf(0L, ceil((nextMidnight(now) - now) / 1000.0).toLong())
        return String.format(
            Locale.US, "%02d:%02d:%02d",
            totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60
        )
    }

    fun clock(now: Long): String = clockFormat.format(Instant.ofEpochMilli(now).atZone(zone))

    fun prettyDate(key: String): String = try {
        LocalDate.parse(key).format(prettyFormat)
    } catch (e: Exception) {
        key
    }

    fun dateTime(ts: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ts).atZone(zone))
}
