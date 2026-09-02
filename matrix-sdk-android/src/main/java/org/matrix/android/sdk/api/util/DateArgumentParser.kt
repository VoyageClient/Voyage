/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import java.util.Calendar

/**
 * Parses the date arguments users type into search's `after:`/`before:` filters, `/massredact`'s bounds
 * and `/jumptodate`, into epoch millis in the device's local time zone.
 *
 * Accepted: `YYYY-M-D`, `YYYY/M/D`, `YYYY.M.D`, `YYYYMMDD`, and the same forms with the day or both the
 * month and day left off (`YYYY-MM`, `YYYYMM`, `YYYY`), meaning the start of that month or year; any of
 * those suffixed with `THH:MM[:SS]`; a unix epoch in seconds or millis; `now`, `today`, `yesterday`, and
 * `<n><m|h|d|w|mo|y>` ago.
 */
object DateArgumentParser {

    fun parse(value: String, now: Long = System.currentTimeMillis()): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return parseRelative(raw.lowercase(), now) ?: parseAbsolute(raw)
    }

    private fun parseRelative(value: String, now: Long): Long? {
        return when (value) {
            "now" -> now
            "today" -> startOfDay(now, daysBack = 0)
            "yesterday" -> startOfDay(now, daysBack = 1)
            else -> parseOffset(value, now)
        }
    }

    /** `<n><unit>` ago. "mo" is matched before "m" so months don't read as minutes. */
    private fun parseOffset(value: String, now: Long): Long? {
        val offset = OFFSETS.firstOrNull { value.endsWith(it.suffix) } ?: return null
        val amount = value.dropLast(offset.suffix.length).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Calendar.getInstance().apply {
            timeInMillis = now
            add(offset.field, -amount)
        }.timeInMillis.takeIf { it >= 0 }
    }

    private fun startOfDay(now: Long, daysBack: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, -daysBack)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun parseAbsolute(value: String): Long? {
        val separatorIndex = value.indexOfFirst { it == 'T' || it == 't' }
        val datePart = if (separatorIndex == -1) value else value.substring(0, separatorIndex)
        val timePart = if (separatorIndex == -1) null else value.substring(separatorIndex + 1)
        val ymd = parseYmd(datePart) ?: return if (timePart == null) parseEpoch(value) else null
        val hms = if (timePart == null) Hms(0, 0, 0) else parseHms(timePart) ?: return null
        return toMillis(ymd, hms)
    }

    private fun parseEpoch(value: String): Long? {
        val epoch = value.toLongOrNull() ?: return null
        // Anything shorter than 9 digits is a typo rather than a real timestamp; the date shapes above
        // have already claimed the 4-, 6- and 8-digit forms.
        if (epoch < EPOCH_MIN_SECONDS) return null
        return if (epoch < EPOCH_MILLIS_THRESHOLD) epoch * 1000 else epoch
    }

    /** A missing month or day means the start of that year or month. */
    private fun parseYmd(value: String): Ymd? {
        // Tried before the epoch branch: 8 digits max out below EPOCH_MIN_SECONDS, so there is no overlap.
        if (value.all { it.isDigit() }) {
            return when (value.length) {
                4 -> Ymd(value.toInt(), 1, 1)
                6 -> Ymd(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), 1)
                8 -> Ymd(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
                else -> null
            }
        }
        val separator = SEPARATORS.firstOrNull { value.contains(it) } ?: return null
        val parts = value.split(separator)
        if (parts.size !in 2..3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 1
        return Ymd(year, month, day)
    }

    private fun parseHms(value: String): Hms? {
        val parts = value.split(':')
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
        return Hms(hour, minute, second)
    }

    private fun toMillis(ymd: Ymd, hms: Hms): Long? {
        if (ymd.year < 1970 || ymd.year > 9999) return null
        // Non-lenient so an impossible date (e.g. 2026-02-31) is rejected rather than rolled over.
        return runCatching {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(ymd.year, ymd.month - 1, ymd.day, hms.hour, hms.minute, hms.second)
            }.timeInMillis
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private data class Ymd(val year: Int, val month: Int, val day: Int)

    private data class Hms(val hour: Int, val minute: Int, val second: Int)

    private data class Offset(val suffix: String, val field: Int)

    private val OFFSETS = listOf(
            Offset("mo", Calendar.MONTH),
            Offset("m", Calendar.MINUTE),
            Offset("h", Calendar.HOUR_OF_DAY),
            Offset("d", Calendar.DAY_OF_MONTH),
            Offset("w", Calendar.WEEK_OF_YEAR),
            Offset("y", Calendar.YEAR),
    )

    private val SEPARATORS = listOf('-', '/', '.')

    // 1e11 ms is 1973; above this an epoch is already in ms, below it is seconds.
    private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L

    // 1e8 s ≈ March 1973; anything smaller is more likely a typo'd date than a real timestamp.
    private const val EPOCH_MIN_SECONDS = 100_000_000L
}
