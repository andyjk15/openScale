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
package com.health.openscale.core.usecase

import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.utils.DayWindow
import com.health.openscale.core.utils.LondonTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Reads the step total for a window. null = no records; throws = the read failed. */
fun interface StepsDayReader {
    suspend fun read(windowStart: Instant, windowEnd: Instant): Long?
}

/** Pushes one day's steps to trackapp. Returns success. */
fun interface StepsPusher {
    suspend fun push(windowStart: Instant, windowEnd: Instant, steps: Int): Boolean
}

/**
 * Re-reads today plus the 6 previous London days every run, and performs a one-time backfill
 * from [BACKFILL_FROM] (up to [MAX_BACKFILL_ATTEMPTS] tries). Pure seam-driven so it is testable
 * without Health Connect or the network.
 */
class RollingStepsSync(
    private val reader: StepsDayReader,
    private val pusher: StepsPusher,
    private val settings: SettingsFacade,
    private val clock: Clock,
) {
    private val TAG = "RollingStepsSync"

    suspend fun run(): HealthConnectStepsSyncOutcome {
        val rolling = LondonTime.rollingWindows(clock)
        val attempts = settings.healthConnectStepsBackfillAttempts.first()
        val backfillDue = !settings.healthConnectStepsBackfillDone.first() && attempts < MAX_BACKFILL_ATTEMPTS
        val backfill = if (backfillDue) {
            LondonTime.fullDayWindows(BACKFILL_FROM, rolling.first().day.minusDays(1))
        } else emptyList()

        var readFailed = false
        var pushFailed = false
        var backfillClean = true
        var todaySteps = 0

        suspend fun process(w: DayWindow, isBackfill: Boolean) {
            val raw: Long? = try {
                reader.read(w.start, w.end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogManager.w(TAG, "Steps read failed for ${w.day}: ${e.message}")
                if (isBackfill) backfillClean = false else readFailed = true
                return
            }
            if (raw == null && !w.isToday) return
            val steps = (raw ?: 0L).toInt()
            if (w.isToday) todaySteps = steps
            if (!pusher.push(w.start, w.end, steps)) {
                pushFailed = true
                if (isBackfill) backfillClean = false
            }
        }

        backfill.forEach { process(it, true) }
        rolling.forEach { process(it, false) }

        if (backfillDue) {
            if (backfillClean) settings.setHealthConnectStepsBackfillDone(true)
            else settings.setHealthConnectStepsBackfillAttempts(attempts + 1)
        }

        return when {
            readFailed -> HealthConnectStepsSyncOutcome.HealthConnectUnavailable
            pushFailed -> HealthConnectStepsSyncOutcome.PushFailed
            else -> HealthConnectStepsSyncOutcome.Success(todaySteps)
        }
    }

    companion object {
        val BACKFILL_FROM: LocalDate = LocalDate.of(2026, 8, 26)
        const val MAX_BACKFILL_ATTEMPTS = 3
    }
}
