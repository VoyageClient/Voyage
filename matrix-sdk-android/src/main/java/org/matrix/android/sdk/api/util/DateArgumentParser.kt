/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the date arguments users type into search's `after:`/`before:` filters, `/massredact`'s bounds
 * and `/jumptodate`, into epoch millis in the device's local time zone unless the value names an offset.
 *
 * Dates: `YYYY-M-D` (also `/` or `.` separated), `YYYYMMDD`, `YYYY-MM`, `YYYYMM`, `YYYY`; time appended as
 * more fields (`YYYY-MM-DD-HH[-MM[-SS]]`, `YYYYMMDDHHMM[SS]`); day-first `D.M.YYYY`, `D/M/YYYY` or `M/D/YYYY`
 * when the order is unambiguous; a year-less `M-D`, which means its most recent occurrence; `today`,
 * `yesterday`, `tomorrow` and weekday names.
 *
 * Times follow a date after `T`, `_`, `@` or `,`: `HH[:MM[:SS[.fff]]]`, `HH.MM`, `HHMM[SS]`, `7pm`, `7:30pm`,
 * optionally with `Z` or a `±HH[:MM]` offset. A time alone (`19:00`, `7pm`) means its most recent occurrence.
 *
 * Also: a unix epoch in seconds or millis, `now`, and offsets ago such as `7d`, `6mo` or `1d12h`
 * (units y, mo, w, d, h, m, s).
 *
 * No form holds a space: search splits its filters on spaces, and so do the slash commands' arguments.
 */
object DateArgumentParser {

    fun parse(value: String, now: Long = System.currentTimeMillis()): Long? {
        val raw = value.trim().lowercase()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        if (raw == "now") return now
        return parseOffset(raw, now) ?: parseAbsolute(raw, now)
    }

    private fun parseOffset(value: String, now: Long): Long? {
        if (!OFFSET_REGEX.matches(value)) return null
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        OFFSET_PART_REGEX.findAll(value).forEach { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            calendar.add(OFFSET_FIELDS.getValue(match.groupValues[2]), -amount)
        }
        return calendar.timeInMillis.takeIf { it >= 0 }
    }

    private fun parseAbsolute(value: String, now: Long): Long? {
        val (datePart, timePart) = splitDateTime(value)
        if (timePart == null && looksLikeTime(datePart)) {
            val time = parseTime(datePart) ?: return null
            return mostRecent(now, time, maxDaysBack = 1) { true }
        }
        val time = timePart?.let { parseTime(it) ?: return null }
        if (datePart.isEmpty()) return time?.let { mostRecent(now, it, maxDaysBack = 1) { true } }
        val resolved = when (val spec = parseDate(datePart)) {
            null -> null
            is DateSpec.Exact -> when {
                spec.time != null && time != null -> return null
                else -> toMillis(spec.ymd, spec.time ?: time ?: Time.MIDNIGHT)
            }
            is DateSpec.YearLess -> {
                val t = time ?: Time.MIDNIGHT
                val thisYear = Calendar.getInstance(t.zone).apply { timeInMillis = now }.get(Calendar.YEAR)
                // Back up to eight years so Feb 29 lands on a leap year.
                (thisYear downTo thisYear - 8).asSequence()
                        .mapNotNull { year -> toMillis(Ymd(year, spec.month, spec.day), t) }
                        .firstOrNull { it <= now }
            }
            is DateSpec.RelativeDay -> toMillis(dayOf(now, spec.daysBack, (time ?: Time.MIDNIGHT).zone), time ?: Time.MIDNIGHT)
            is DateSpec.Weekday -> mostRecent(now, time ?: Time.MIDNIGHT, maxDaysBack = 7) { it == spec.dayOfWeek }
        }
        if (resolved != null) return resolved
        return if (time == null && datePart.all { it.isDigit() }) parseEpoch(datePart) else null
    }

    /** Splits at the first `@`/`_`/`,`, or at an ISO `T` (also a leading one before a bare time). */
    private fun splitDateTime(value: String): Pair<String, String?> {
        val explicit = value.indexOfFirst { it == '@' || it == '_' || it == ',' }
        if (explicit >= 0) return value.substring(0, explicit) to value.substring(explicit + 1)
        for (i in value.indices) {
            if (value[i] == 't' && (i == 0 || value[i - 1].isDigit()) && value.getOrNull(i + 1)?.isDigit() == true) {
                return value.substring(0, i) to value.substring(i + 1)
            }
        }
        return value to null
    }

    private fun looksLikeTime(value: String) = ':' in value || value.endsWith("am") || value.endsWith("pm")

    private fun parseTime(value: String): Time? {
        var body = value
        var zone = TimeZone.getDefault()
        if (body.endsWith("z")) {
            zone = TimeZone.getTimeZone("UTC")
            body = body.dropLast(1)
        } else {
            ZONE_REGEX.find(body)?.let { match ->
                val hours = match.groupValues[2].toInt()
                val minutes = match.groupValues[3].ifEmpty { "0" }.toInt()
                if (hours > 23 || minutes > 59) return null
                zone = TimeZone.getTimeZone(String.format(Locale.ROOT, "GMT%s%02d:%02d", match.groupValues[1], hours, minutes))
                body = body.substring(0, match.range.first)
            }
        }
        val meridiem = when {
            body.endsWith("am") -> false
            body.endsWith("pm") -> true
            else -> null
        }
        if (meridiem != null) body = body.dropLast(2).trimEnd()
        // hour, minute, second, fraction
        val fields = SEPARATED_TIME_REGEX.matchEntire(body)?.groupValues?.let { listOf(it[1], it[3], it[4], it[5]) }
                ?: COMPACT_TIME_REGEX.matchEntire(body)?.groupValues?.drop(1)
                ?: return null
        val rawHour = fields[0].toInt()
        val minute = fields[1].ifEmpty { "0" }.toInt()
        val second = fields[2].ifEmpty { "0" }.toInt()
        val millis = fields[3].take(3).padEnd(3, '0').toInt()
        val hour = when (meridiem) {
            null -> rawHour.takeIf { it <= 23 }
            else -> rawHour.takeIf { it in 1..12 }?.let { it % 12 + if (meridiem) 12 else 0 }
        } ?: return null
        if (minute > 59 || second > 59) return null
        return Time(hour, minute, second, millis, zone)
    }

    private fun parseDate(value: String): DateSpec? {
        when (value) {
            "today" -> return DateSpec.RelativeDay(0)
            "yesterday" -> return DateSpec.RelativeDay(1)
            "tomorrow" -> return DateSpec.RelativeDay(-1)
        }
        if (value.all { it.isDigit() }) return parseCompactDate(value)
        NUMERIC_DATE_REGEX.matchEntire(value)?.let { match ->
            val separator = match.groupValues[1][0]
            val parts = value.split(separator).map { (it.toIntOrNull() ?: return null) to it.length }
            return parseNumericDate(parts, dayFirst = separator == '.')
        }
        return weekdayNames()[value]?.let { DateSpec.Weekday(it) }
    }

    private fun parseCompactDate(value: String): DateSpec? {
        fun field(start: Int, end: Int) = value.substring(start, end).toInt()
        return when (value.length) {
            4 -> DateSpec.Exact(Ymd(field(0, 4), 1, 1))
            6 -> DateSpec.Exact(Ymd(field(0, 4), field(4, 6), 1))
            8 -> DateSpec.Exact(Ymd(field(0, 4), field(4, 6), field(6, 8)))
            12 -> DateSpec.Exact(Ymd(field(0, 4), field(4, 6), field(6, 8)), Time.local(field(8, 10), field(10, 12), 0) ?: return null)
            14 -> DateSpec.Exact(
                    Ymd(field(0, 4), field(4, 6), field(6, 8)),
                    Time.local(field(8, 10), field(10, 12), field(12, 14)) ?: return null,
            )
            else -> null
        }
    }

    /** [parts] are (value, digit count) pairs of a date that used one separator throughout. */
    private fun parseNumericDate(parts: List<Pair<Int, Int>>, dayFirst: Boolean): DateSpec? {
        val values = parts.map { it.first }
        return when {
            parts[0].second == 4 && parts.size in 2..6 -> {
                val ymd = Ymd(values[0], values[1], values.getOrElse(2) { 1 })
                if (parts.size <= 3) {
                    DateSpec.Exact(ymd)
                } else {
                    DateSpec.Exact(ymd, Time.local(values[3], values.getOrElse(4) { 0 }, values.getOrElse(5) { 0 }) ?: return null)
                }
            }
            parts.size == 3 && parts[2].second == 4 -> {
                val (day, month) = dayMonthOrder(values[0], values[1], dayFirst) ?: return null
                DateSpec.Exact(Ymd(values[2], month, day))
            }
            parts.size == 2 && parts.all { it.second <= 2 } -> {
                val (day, month) = dayMonthOrder(values[0], values[1], dayFirst) ?: return null
                DateSpec.YearLess(month, day)
            }
            else -> null
        }
    }

    /** (day, month). Dotted dates read day first; otherwise the order must follow from a value above 12. */
    private fun dayMonthOrder(first: Int, second: Int, dayFirst: Boolean): Pair<Int, Int>? = when {
        dayFirst || first > 12 || first == second -> first to second
        second > 12 -> second to first
        else -> null
    }

    private fun weekdayNames(): Map<String, Int> {
        val names = HashMap<String, Int>()
        ENGLISH_WEEKDAYS.forEachIndexed { index, name ->
            names[name] = Calendar.SUNDAY + index
            names[name.take(3)] = Calendar.SUNDAY + index
        }
        names["tues"] = Calendar.TUESDAY
        names["thur"] = Calendar.THURSDAY
        names["thurs"] = Calendar.THURSDAY
        val symbols = DateFormatSymbols.getInstance()
        listOf(symbols.weekdays, symbols.shortWeekdays).forEach { localized ->
            for (day in Calendar.SUNDAY..Calendar.SATURDAY) localized.getOrNull(day)?.let { names.putIfAbsent(normalizeName(it), day) }
        }
        names.remove("")
        return names
    }

    private fun normalizeName(name: String) = name.lowercase().filter { it.isLetter() }

    private fun parseEpoch(value: String): Long? {
        val epoch = value.toLongOrNull() ?: return null
        // Anything shorter than 9 digits is a typo rather than a real timestamp; the date shapes above
        // have already claimed the 4-, 6- and 8-digit forms.
        if (epoch < EPOCH_MIN_SECONDS) return null
        return if (epoch < EPOCH_MILLIS_THRESHOLD) epoch * 1000 else epoch
    }

    /** The latest day at most [maxDaysBack] days ago accepted by [dayFilter] whose [time] is not in the future. */
    private fun mostRecent(now: Long, time: Time, maxDaysBack: Int, dayFilter: (dayOfWeek: Int) -> Boolean): Long? {
        for (daysBack in 0..maxDaysBack) {
            val calendar = Calendar.getInstance(time.zone).apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, -daysBack)
            }
            if (!dayFilter(calendar.get(Calendar.DAY_OF_WEEK))) continue
            val candidate = toMillis(calendar.ymd(), time) ?: continue
            if (candidate <= now) return candidate
        }
        return null
    }

    private fun dayOf(now: Long, daysBack: Int, zone: TimeZone): Ymd = Calendar.getInstance(zone).apply {
        timeInMillis = now
        add(Calendar.DAY_OF_MONTH, -daysBack)
    }.ymd()

    private fun Calendar.ymd() = Ymd(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))

    private fun toMillis(ymd: Ymd, time: Time): Long? {
        if (ymd.year < 1970 || ymd.year > 9999) return null
        // Non-lenient so an impossible date (e.g. 2026-02-31) is rejected rather than rolled over.
        return runCatching {
            Calendar.getInstance(time.zone).apply {
                isLenient = false
                clear()
                set(ymd.year, ymd.month - 1, ymd.day, time.hour, time.minute, time.second)
                set(Calendar.MILLISECOND, time.millis)
            }.timeInMillis
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private sealed class DateSpec {
        /** [time] is set when the date itself carried one (`YYYY-MM-DD-HH-MM`, `YYYYMMDDHHMM`). */
        data class Exact(val ymd: Ymd, val time: Time? = null) : DateSpec()
        data class YearLess(val month: Int, val day: Int) : DateSpec()
        data class RelativeDay(val daysBack: Int) : DateSpec()
        data class Weekday(val dayOfWeek: Int) : DateSpec()
    }

    private data class Ymd(val year: Int, val month: Int, val day: Int)

    private data class Time(val hour: Int, val minute: Int, val second: Int, val millis: Int, val zone: TimeZone) {
        companion object {
            val MIDNIGHT get() = Time(0, 0, 0, 0, TimeZone.getDefault())

            fun local(hour: Int, minute: Int, second: Int): Time? =
                    Time(hour, minute, second, 0, TimeZone.getDefault()).takeIf { hour <= 23 && minute <= 59 && second <= 59 }
        }
    }

    private val OFFSET_REGEX = Regex("""(?:\d+(?:mo|[ywdhms]))+""")
    private val OFFSET_PART_REGEX = Regex("""(\d+)(mo|[ywdhms])""")
    private val OFFSET_FIELDS = mapOf(
            "y" to Calendar.YEAR,
            "mo" to Calendar.MONTH,
            "w" to Calendar.WEEK_OF_YEAR,
            "d" to Calendar.DAY_OF_MONTH,
            "h" to Calendar.HOUR_OF_DAY,
            "m" to Calendar.MINUTE,
            "s" to Calendar.SECOND,
    )

    private val NUMERIC_DATE_REGEX = Regex("""\d+([-/.])\d+(?:\1\d+)*""")
    private val ZONE_REGEX = Regex("""(?<=\d)([+-])(\d{2})(?::?(\d{2}))?$""")
    private val SEPARATED_TIME_REGEX = Regex("""(\d{1,2})(?:([:.])(\d{2})(?:\2(\d{2})(?:\.(\d{1,9}))?)?)?""")
    private val COMPACT_TIME_REGEX = Regex("""(\d{2})(\d{2})(\d{2})?(?:\.(\d{1,9}))?""")

    private val ENGLISH_WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    // 1e11 ms is 1973; above this an epoch is already in ms, below it is seconds.
    private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L

    // 1e8 s ≈ March 1973; anything smaller is more likely a typo'd date than a real timestamp.
    private const val EPOCH_MIN_SECONDS = 100_000_000L
}
