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
import java.time.Instant
import java.time.ZoneOffset

/**
 * [LondonTime.londonMidnightInstant] is the one place a DST-boundary mistake would silently
 * misfile a step push under the wrong day -- see that object's own header. Pure [java.time], no
 * Android/Robolectric dependency, so this runs on the plain JVM.
 */
class LondonTimeTest {

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Test
    fun londonMidnight_duringBST_isOneHourBeforeUtcMidnight() {
        // 2026-08-15T13:00:00Z is 14:00 BST (UTC+1) -- still the 15th in London.
        val clock = clockAt(Instant.parse("2026-08-15T13:00:00Z"))
        val midnight = LondonTime.londonMidnightInstant(clock)
        // London midnight on the 15th, expressed as the UTC instant it actually is.
        assertThat(midnight).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
    }

    @Test
    fun londonMidnight_duringGMT_equalsUtcMidnight() {
        // January is GMT (UTC+0), so the two clocks agree.
        val clock = clockAt(Instant.parse("2026-01-15T13:00:00Z"))
        val midnight = LondonTime.londonMidnightInstant(clock)
        assertThat(midnight).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"))
    }

    @Test
    fun londonMidnight_justAfterMidnightBST_staysOnTheSameLondonDay() {
        // 2026-08-15T00:05:00+01:00 == 2026-08-14T23:05:00Z: five minutes into the London day,
        // still hours before UTC's own midnight. A naive UTC-day computation would answer with
        // the 14th's midnight instead of the 15th's -- exactly the bug this test exists to catch.
        val clock = clockAt(Instant.parse("2026-08-14T23:05:00Z"))
        val midnight = LondonTime.londonMidnightInstant(clock)
        assertThat(midnight).isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
    }

    @Test
    fun londonMidnight_justBeforeMidnightBST_staysOnThePreviousLondonDay() {
        // 2026-08-14T23:55:00+01:00 == 2026-08-14T22:55:00Z: five minutes before London midnight.
        val clock = clockAt(Instant.parse("2026-08-14T22:55:00Z"))
        val midnight = LondonTime.londonMidnightInstant(clock)
        assertThat(midnight).isEqualTo(Instant.parse("2026-08-13T23:00:00Z"))
    }

    @Test
    fun londonMidnight_onTheDayClocksGoBack_stillUsesTheOldBstOffset() {
        // Clocks go back 2026-10-25 02:00 BST -> 01:00 GMT -- MID-MORNING by the clock, not at
        // midnight. At 2026-10-25T10:00:00Z London has already rolled back to GMT, so `now` reads
        // as the 25th -- but 00:00 ON THE 25TH ITSELF occurred hours BEFORE the rollback, while
        // BST (UTC+1) was still in effect. The adversarial case for a LondonTime that assumes
        // "today's date is post-transition, so today's midnight must be too" -- true every other
        // day of the year, false on this one.
        val clock = clockAt(Instant.parse("2026-10-25T10:00:00Z"))
        val midnight = LondonTime.londonMidnightInstant(clock)
        assertThat(midnight).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"))
    }
}
