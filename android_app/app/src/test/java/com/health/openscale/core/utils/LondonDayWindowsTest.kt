/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T1 (TA-356, W1): the 7 London-day windows the steps sync re-reads on every run, and the
 * backfill's full-day windows. Pure java.time on the plain JVM -- no Android, no Health Connect.
 *
 * API under test (to be added to [LondonTime]):
 *  - `data class DayWindow(day: LocalDate, start: Instant, end: Instant, isToday: Boolean)`
 *  - `LondonTime.rollingWindows(clock, days = 7)`: oldest first, last element is today with
 *    end = clock.instant(); every earlier day ends at the NEXT London midnight (exclusive).
 *  - `LondonTime.fullDayWindows(from, toInclusive)`: oldest first, every window is a whole
 *    London day, isToday = false.
 */
class LondonDayWindowsTest {

    private fun clockAt(s: String): Clock = Clock.fixed(Instant.parse(s), ZoneOffset.UTC)

    @Test
    fun rollingWindows_atTheOctober26Clock_areExactlySevenDaysWithTheDstDayIs25Hours() {
        val w = LondonTime.rollingWindows(clockAt("2026-10-26T21:00:00Z"))

        assertThat(w).hasSize(7)
        assertThat(w.map { it.day }).containsExactly(
            LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"),
            LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-23"),
            LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"),
            LocalDate.parse("2026-10-26"),
        ).inOrder()

        // 10-20: still BST, 24 h, [2026-10-19T23:00Z, 2026-10-20T23:00Z)
        assertThat(w[0].start).isEqualTo(Instant.parse("2026-10-19T23:00:00Z"))
        assertThat(w[0].end).isEqualTo(Instant.parse("2026-10-20T23:00:00Z"))

        // 10-25: clocks go back, the day is 25 h, [2026-10-24T23:00Z, 2026-10-26T00:00Z)
        assertThat(w[5].start).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"))
        assertThat(w[5].end).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"))
        assertThat(Duration.between(w[5].start, w[5].end)).isEqualTo(Duration.ofHours(25))

        // today: [London midnight, now]
        assertThat(w[6].start).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"))
        assertThat(w[6].end).isEqualTo(Instant.parse("2026-10-26T21:00:00Z"))

        assertThat(w.map { it.isToday }).containsExactly(false, false, false, false, false, false, true).inOrder()
    }

    @Test
    fun rollingWindows_areContiguous_eachPastDayEndsWhereTheNextBegins() {
        val w = LondonTime.rollingWindows(clockAt("2026-10-26T21:00:00Z"))
        for (i in 0 until w.size - 1) {
            assertThat(w[i].end).isEqualTo(w[i + 1].start)
        }
    }

    @Test
    fun rollingWindows_midBst_areSevenTwentyFourHourDaysAnHourOffUtc() {
        // 2026-08-15T13:00Z is 14:00 BST on the 15th.
        val w = LondonTime.rollingWindows(clockAt("2026-08-15T13:00:00Z"))

        assertThat(w).hasSize(7)
        assertThat(w.first().day).isEqualTo(LocalDate.parse("2026-08-09"))
        assertThat(w.first().start).isEqualTo(Instant.parse("2026-08-08T23:00:00Z"))
        assertThat(w.last().day).isEqualTo(LocalDate.parse("2026-08-15"))
        assertThat(w.last().start).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
        assertThat(w.last().end).isEqualTo(Instant.parse("2026-08-15T13:00:00Z"))
        w.dropLast(1).forEach { assertThat(Duration.between(it.start, it.end)).isEqualTo(Duration.ofHours(24)) }
    }

    @Test
    fun rollingWindows_springForwardDay_2026_03_29_is23Hours() {
        // 2026-03-31T12:00Z is 13:00 BST on the 31st; the 7 days are 03-25 .. 03-31.
        val w = LondonTime.rollingWindows(clockAt("2026-03-31T12:00:00Z"))

        assertThat(w.first().day).isEqualTo(LocalDate.parse("2026-03-25"))
        val spring = w.single { it.day == LocalDate.parse("2026-03-29") }
        assertThat(spring.start).isEqualTo(Instant.parse("2026-03-29T00:00:00Z"))
        assertThat(spring.end).isEqualTo(Instant.parse("2026-03-29T23:00:00Z"))
        assertThat(Duration.between(spring.start, spring.end)).isEqualTo(Duration.ofHours(23))
    }

    @Test
    fun rollingWindows_justAfterLondonMidnightBst_todayIsFiveMinutesLong_notUtcDay() {
        // 2026-08-14T23:05Z == 00:05 BST on the 15th: UTC is still the 14th.
        val w = LondonTime.rollingWindows(clockAt("2026-08-14T23:05:00Z"))
        assertThat(w.last().day).isEqualTo(LocalDate.parse("2026-08-15"))
        assertThat(w.last().start).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
        assertThat(w.last().end).isEqualTo(Instant.parse("2026-08-14T23:05:00Z"))
    }

    @Test
    fun fullDayWindows_26Aug_through_19Oct_is55WholeDays() {
        val w = LondonTime.fullDayWindows(LocalDate.parse("2026-08-26"), LocalDate.parse("2026-10-19"))

        assertThat(w).hasSize(55)
        assertThat(w.first().day).isEqualTo(LocalDate.parse("2026-08-26"))
        assertThat(w.first().start).isEqualTo(Instant.parse("2026-08-25T23:00:00Z"))
        assertThat(w.first().end).isEqualTo(Instant.parse("2026-08-26T23:00:00Z"))
        assertThat(w.last().day).isEqualTo(LocalDate.parse("2026-10-19"))
        assertThat(w.last().start).isEqualTo(Instant.parse("2026-10-18T23:00:00Z"))
        assertThat(w.last().end).isEqualTo(Instant.parse("2026-10-19T23:00:00Z"))
        assertThat(w.none { it.isToday }).isTrue()
    }

    @Test
    fun fullDayWindows_emptyWhenFromIsAfterTo() {
        val w = LondonTime.fullDayWindows(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-19"))
        assertThat(w).isEmpty()
    }
}
