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

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The London calendar day boundary, for the one place this fork needs to agree with trackapp's
 * own day canon (src/lib/day.ts in that repo: every day boundary is Europe/London, never the
 * device's own timezone). HARDCODED TO Europe/London, deliberately not [ZoneId.systemDefault] --
 * a phone travelling outside the UK must still file a steps push under the SAME day trackapp
 * would compute from the push's own `windowStart`, or a push made abroad could straddle midnight
 * twice (once here, once again when trackapp derives `localDate` from it) and land on a day
 * neither side actually meant.
 */
object LondonTime {
    private val LONDON: ZoneId = ZoneId.of("Europe/London")

    /**
     * Midnight, London-local, on the day [clock] currently reads -- as an [Instant], the same
     * currency [HealthConnectStepsSyncUseCase]'s aggregate window and trackapp's own
     * `windowStart` are both expressed in.
     *
     * Takes a [Clock] rather than reading [Instant.now] directly so this is plain-JUnit-testable
     * with no Robolectric -- see [com.health.openscale.core.usecase.TimeModule] for the
     * Hilt-provided singleton this is called with in production.
     */
    fun londonMidnightInstant(clock: Clock): Instant =
        clock.instant().atZone(LONDON).toLocalDate().atStartOfDay(LONDON).toInstant()
}
