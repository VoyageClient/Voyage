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
import java.util.TimeZone

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
    fun parsesTimeAsExtraDateFields() {
        parse("2026-11-16-19-00") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("2026-11-16-19-00-30") shouldBeEqualTo at(2026, 11, 16, 19, 0, 30)
        parse("2026-11-16-19") shouldBeEqualTo at(2026, 11, 16, 19)
        parse("2026/11/16/19/05") shouldBeEqualTo at(2026, 11, 16, 19, 5)
        parse("2026.11.16.19.05") shouldBeEqualTo at(2026, 11, 16, 19, 5)
        parse("202611161900") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("20261116190030") shouldBeEqualTo at(2026, 11, 16, 19, 0, 30)
        parse("2026-11-16-24-00").shouldBeNull()
        parse("2026-11-16-19-00T20:00").shouldBeNull()
    }

    @Test
    fun parsesOtherDateTimeSeparators() {
        parse("2026-11-16_19:00") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("2026-11-16@19:00") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("2026-11-16,19:00:05") shouldBeEqualTo at(2026, 11, 16, 19, 0, 5)
        parse("2026-11-16T19") shouldBeEqualTo at(2026, 11, 16, 19)
        parse("2026-11-16T19.30") shouldBeEqualTo at(2026, 11, 16, 19, 30)
        parse("20261116T1900") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("20261116T190005") shouldBeEqualTo at(2026, 11, 16, 19, 0, 5)
    }

    @Test
    fun parsesIsoTimestamps() {
        parse("2026-11-16T19:00:00.123") shouldBeEqualTo at(2026, 11, 16, 19, 0) + 123
        parse("2026-11-16T19:00:00Z") shouldBeEqualTo utc(2026, 11, 16, 19, 0)
        parse("2026-11-16T19:00:00.5z") shouldBeEqualTo utc(2026, 11, 16, 19, 0) + 500
        parse("2026-11-16T19:00+01:00") shouldBeEqualTo utc(2026, 11, 16, 18, 0)
        parse("2026-11-16T19:00-0530") shouldBeEqualTo utc(2026, 11, 17, 0, 30)
        parse("2026-11-16T19:00+02") shouldBeEqualTo utc(2026, 11, 16, 17, 0)
    }

    @Test
    fun parsesTwelveHourTimes() {
        parse("2026-11-16@7pm") shouldBeEqualTo at(2026, 11, 16, 19, 0)
        parse("2026-11-16@7:30pm") shouldBeEqualTo at(2026, 11, 16, 19, 30)
        parse("2026-11-16T12am") shouldBeEqualTo at(2026, 11, 16, 0, 0)
        parse("2026-11-16T12pm") shouldBeEqualTo at(2026, 11, 16, 12, 0)
        parse("2026-11-16@13pm").shouldBeNull()
    }

    @Test
    fun timeAloneMeansItsMostRecentOccurrence() {
        parse("09:15") shouldBeEqualTo at(2026, 6, 15, 9, 15)
        parse("19:00") shouldBeEqualTo at(2026, 6, 14, 19, 0)
        parse("7pm") shouldBeEqualTo at(2026, 6, 14, 19, 0)
        parse("t08:00") shouldBeEqualTo at(2026, 6, 15, 8, 0)
    }

    @Test
    fun parsesDayFirstAndUnambiguousOrders() {
        parse("16.11.2026") shouldBeEqualTo at(2026, 11, 16)
        parse("1.2.2026") shouldBeEqualTo at(2026, 2, 1)
        parse("16/11/2026") shouldBeEqualTo at(2026, 11, 16)
        parse("11/16/2026") shouldBeEqualTo at(2026, 11, 16)
        parse("16-11-2026") shouldBeEqualTo at(2026, 11, 16)
        parse("05/05/2026") shouldBeEqualTo at(2026, 5, 5)
        parse("05/06/2026").shouldBeNull()
        parse("16.11.2026_19:00") shouldBeEqualTo at(2026, 11, 16, 19, 0)
    }

    @Test
    fun yearLessDatesMeanTheirMostRecentOccurrence() {
        parse("06-14") shouldBeEqualTo at(2026, 6, 14)
        parse("6-20") shouldBeEqualTo at(2025, 6, 20)
        parse("05-06").shouldBeNull()
        parse("3-14") shouldBeEqualTo at(2026, 3, 14)
        parse("11-16") shouldBeEqualTo at(2025, 11, 16)
        parse("16.11") shouldBeEqualTo at(2025, 11, 16)
        parse("1.6") shouldBeEqualTo at(2026, 6, 1)
        parse("11-16T19:00") shouldBeEqualTo at(2025, 11, 16, 19, 0)
        parse("29.2") shouldBeEqualTo at(2024, 2, 29)
    }

    @Test
    fun rejectsSpacesAndMonthNames() {
        parse("2026-11-16 19:00").shouldBeNull()
        parse("today 8am").shouldBeNull()
        parse("7 pm").shouldBeNull()
        parse("16nov2026").shouldBeNull()
        parse("2026-nov-16").shouldBeNull()
        parse("may").shouldBeNull()
        // A comma separates a time, so it can't also start a fraction.
        parse("2026-11-16T19:00:00,5").shouldBeNull()
    }

    @Test
    fun parsesRelativeDaysWithTimes() {
        parse("tomorrow") shouldBeEqualTo at(2026, 6, 16)
        parse("yesterday@19:00") shouldBeEqualTo at(2026, 6, 14, 19, 0)
        parse("today_8am") shouldBeEqualTo at(2026, 6, 15, 8, 0)
    }

    @Test
    fun weekdaysMeanTheirMostRecentOccurrence() {
        // The fixed now is a Monday.
        parse("monday") shouldBeEqualTo at(2026, 6, 15)
        parse("Mon_13:00") shouldBeEqualTo at(2026, 6, 8, 13, 0)
        parse("sunday") shouldBeEqualTo at(2026, 6, 14)
        parse("tue") shouldBeEqualTo at(2026, 6, 9)
        parse("thurs") shouldBeEqualTo at(2026, 6, 11)
    }

    @Test
    fun parsesCompoundOffsets() {
        parse("1d12h") shouldBeEqualTo at(2026, 6, 14, 0, 30, 45)
        parse("90s") shouldBeEqualTo at(2026, 6, 15, 12, 29, 15)
        parse("1y2mo") shouldBeEqualTo at(2025, 4, 15, 12, 30, 45)
        parse("1d-2h").shouldBeNull()
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

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis
}
