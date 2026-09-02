/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import java.util.Calendar

class DateArgumentParserTest {

    private val now = at(2026, 6, 15, 12, 30, 45)

    @Test
    fun parsesSeparatedDates() {
        parse("2026-01-02") shouldBeEqualTo at(2026, 1, 2)
        parse("2026-1-2") shouldBeEqualTo at(2026, 1, 2)
        parse("2026/01/02") shouldBeEqualTo at(2026, 1, 2)
        parse("2026.01.02") shouldBeEqualTo at(2026, 1, 2)
    }

    @Test
    fun parsesCompactDates() {
        parse("20260102") shouldBeEqualTo at(2026, 1, 2)
        parse("19700101") shouldBeEqualTo at(1970, 1, 1)
    }

    @Test
    fun parsesDatesWithTime() {
        parse("2026-01-02T15:04") shouldBeEqualTo at(2026, 1, 2, 15, 4)
        parse("2026-01-02T15:04:05") shouldBeEqualTo at(2026, 1, 2, 15, 4, 5)
        parse("20260102t15:04") shouldBeEqualTo at(2026, 1, 2, 15, 4)
    }

    @Test
    fun parsesEpochs() {
        // Seconds are scaled up, millis are passed through.
        parse("1700000000") shouldBeEqualTo 1700000000_000L
        parse("1700000000000") shouldBeEqualTo 1700000000000L
    }

    @Test
    fun parsesRelativeWords() {
        parse("now") shouldBeEqualTo now
        parse("today") shouldBeEqualTo at(2026, 6, 15)
        parse("Yesterday") shouldBeEqualTo at(2026, 6, 14)
    }

    @Test
    fun parsesRelativeOffsets() {
        parse("30m") shouldBeEqualTo at(2026, 6, 15, 12, 0, 45)
        parse("2h") shouldBeEqualTo at(2026, 6, 15, 10, 30, 45)
        parse("7d") shouldBeEqualTo at(2026, 6, 8, 12, 30, 45)
        parse("2w") shouldBeEqualTo at(2026, 6, 1, 12, 30, 45)
        parse("6mo") shouldBeEqualTo at(2025, 12, 15, 12, 30, 45)
        parse("1y") shouldBeEqualTo at(2025, 6, 15, 12, 30, 45)
        parse("0d") shouldBeEqualTo now
    }

    @Test
    fun parsesPartialDates() {
        // A missing day or month means the start of that month or year.
        parse("2026-01") shouldBeEqualTo at(2026, 1, 1)
        parse("2026-03") shouldBeEqualTo at(2026, 3, 1)
        parse("202603") shouldBeEqualTo at(2026, 3, 1)
        parse("2026/03") shouldBeEqualTo at(2026, 3, 1)
        parse("2026") shouldBeEqualTo at(2026, 1, 1)
        parse("2026T06:30") shouldBeEqualTo at(2026, 1, 1, 6, 30)
    }

    @Test
    fun rejectsUnparseableValues() {
        parse("not-a-date").shouldBeNull()
        parse("").shouldBeNull()
        parse("5x").shouldBeNull()
        parse("-3d").shouldBeNull()
        // Five digits is neither a date shape nor a plausible epoch.
        parse("20260").shouldBeNull()
    }

    @Test
    fun rejectsImpossibleDates() {
        parse("2026-02-31").shouldBeNull()
        parse("20260231").shouldBeNull()
        parse("2026-13-01").shouldBeNull()
        parse("202613").shouldBeNull()
        parse("1969-12-31").shouldBeNull()
        parse("1969").shouldBeNull()
        parse("2026-01-02T25:00").shouldBeNull()
    }

    private fun parse(value: String) = DateArgumentParser.parse(value, now)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long =
            Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, second) }.timeInMillis
}
