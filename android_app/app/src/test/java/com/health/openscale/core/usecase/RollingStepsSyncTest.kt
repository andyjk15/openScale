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

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.facade.SettingsFacadeImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * TA-356: drives [RollingStepsSync.run] -- the engine [HealthConnectStepsSyncUseCase.syncNow]
 * delegates to once config/availability/permission are checked -- entirely through fakes.
 *
 * NOTHING HERE REACHES HEALTH CONNECT OR THE NETWORK. [StepsDayReader] and [StepsPusher] are the
 * seam; both are fakes defined in this file. There is no TrackAppHttp, no BuildConfig token, no
 * HealthConnectClient anywhere in this test. (A unit test once POSTed into the owner's production
 * trackapp DB -- see [HealthConnectStepsSync]'s doc comment -- do not repeat that.)
 *
 * API under test (new file RollingStepsSync.kt, package com.health.openscale.core.usecase):
 *  - `fun interface StepsDayReader { suspend fun read(windowStart: Instant, windowEnd: Instant): Long? }`
 *    null = Health Connect has no records in the window; throws = the aggregate failed.
 *  - `fun interface StepsPusher { suspend fun push(windowStart: Instant, windowEnd: Instant, steps: Int): Boolean }`
 *  - `class RollingStepsSync(reader, pusher, settings: SettingsFacade, clock: Clock) { suspend fun run(): HealthConnectStepsSyncOutcome }`
 *  - SettingsFacade: `healthConnectStepsBackfillDone: Flow<Boolean>` + `setHealthConnectStepsBackfillDone(Boolean)`,
 *    `healthConnectStepsBackfillAttempts: Flow<Int>` + `setHealthConnectStepsBackfillAttempts(Int)`.
 *
 * Fixed clock throughout: 2026-10-26T21:00Z. Rolling 7 = London days 10-20 .. 10-26. Backfill =
 * 2026-08-26 .. 2026-10-19 = 55 days. Push ORDER is oldest window first, today last, with the
 * backfill days (when due) before the rolling days.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollingStepsSyncTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val now = Instant.parse("2026-10-26T21:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    // Rolling 7 window starts, oldest first (10-25 is the 25 h day, 10-26 is today).
    private val rollingStarts = listOf(
        "2026-10-19T23:00:00Z", "2026-10-20T23:00:00Z", "2026-10-21T23:00:00Z",
        "2026-10-22T23:00:00Z", "2026-10-23T23:00:00Z", "2026-10-24T23:00:00Z",
        "2026-10-26T00:00:00Z",
    ).map(Instant::parse)

    private val todayStart = Instant.parse("2026-10-26T00:00:00Z")
    private val sep15Start = Instant.parse("2026-09-14T23:00:00Z") // London 2026-09-15, a backfill day

    data class Win(val start: Instant, val end: Instant)
    data class Push(val start: Instant, val end: Instant, val steps: Int)

    private class FakeReader(val answer: (Instant, Instant) -> Long?) : StepsDayReader {
        val calls = mutableListOf<Win>()
        override suspend fun read(windowStart: Instant, windowEnd: Instant): Long? {
            calls += Win(windowStart, windowEnd)
            return answer(windowStart, windowEnd)
        }
    }

    private class FakePusher(val ok: (Instant) -> Boolean = { true }) : StepsPusher {
        val pushes = mutableListOf<Push>()
        override suspend fun push(windowStart: Instant, windowEnd: Instant, steps: Int): Boolean {
            pushes += Push(windowStart, windowEnd, steps)
            return ok(windowStart)
        }
    }

    private fun newSettings(): SettingsFacadeImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "rss-${System.nanoTime()}.preferences_pb") },
        )
        return SettingsFacadeImpl(dataStore)
    }

    /** A settings facade whose backfill is already finished, so a test sees only the rolling 7. */
    private suspend fun settingsWithBackfillDone(): SettingsFacadeImpl =
        newSettings().also { it.setHealthConnectStepsBackfillDone(true) }

    private fun engine(r: StepsDayReader, p: StepsPusher, s: SettingsFacadeImpl) =
        RollingStepsSync(r, p, s, clock)

    // ---------------------------------------------------------------- W1

    @Test
    fun w1_everyRun_readsExactlySevenWindows_oneReadPerLondonDay() = runBlocking {
        val reader = FakeReader { start, _ -> 1000L + start.epochSecond % 7 }
        val pusher = FakePusher()

        engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(reader.calls.map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(reader.calls).hasSize(7) // 7 aggregates per run, never more
        // the 25 h DST day and the 24 h day before it, plus today's [midnight, now]
        assertThat(reader.calls[5]).isEqualTo(
            Win(Instant.parse("2026-10-24T23:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"))
        )
        assertThat(reader.calls[0]).isEqualTo(
            Win(Instant.parse("2026-10-19T23:00:00Z"), Instant.parse("2026-10-20T23:00:00Z"))
        )
        assertThat(reader.calls[6]).isEqualTo(Win(todayStart, now))
    }

    // ---------------------------------------------------------------- W2

    @Test
    fun w2_eachDayIsItsOwnPush_pastDayWindowEndsAtNextLondonMidnight() = runBlocking {
        val reader = FakeReader { start, _ -> (start.epochSecond / 3600 % 5000) }
        val pusher = FakePusher()

        engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(pusher.pushes).hasSize(7)
        // the DST day: filed under its own 25 h window, windowEnd = next London midnight
        val dst = pusher.pushes[5]
        assertThat(dst.start).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"))
        assertThat(dst.end).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"))
        // each push carries what its own read returned
        pusher.pushes.forEach { p ->
            assertThat(p.steps.toLong()).isEqualTo(p.start.epochSecond / 3600 % 5000)
        }
        // today keeps its current shape: [London midnight, now]
        assertThat(pusher.pushes[6].start).isEqualTo(todayStart)
        assertThat(pusher.pushes[6].end).isEqualTo(now)
    }

    // ---------------------------------------------------------------- W3

    @Test
    fun w3_pastDayWithNullAggregate_isNotPushed() = runBlocking {
        val nullStart = Instant.parse("2026-10-22T23:00:00Z") // London 10-23
        val reader = FakeReader { start, _ -> if (start == nullStart) null else 4321L }
        val pusher = FakePusher()

        val out = engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(pusher.pushes.map { it.start }).doesNotContain(nullStart)
        assertThat(pusher.pushes).hasSize(6)
        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(4321))
    }

    @Test
    fun w3_todayWithNullAggregate_isPushedAsZero() = runBlocking {
        val reader = FakeReader { start, _ -> if (start == todayStart) null else 4321L }
        val pusher = FakePusher()

        val out = engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(pusher.pushes.last()).isEqualTo(Push(todayStart, now, 0))
        assertThat(pusher.pushes).hasSize(7)
        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(0))
    }

    @Test
    fun w3_throwingPastDay_isUnavailable_andThatDayIsNotPushedAsZero() = runBlocking {
        val boom = Instant.parse("2026-10-21T23:00:00Z") // London 10-22
        val reader = FakeReader { start, _ ->
            if (start == boom) throw IllegalStateException("HC aggregate failed") else 4321L
        }
        val pusher = FakePusher()

        val out = engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.HealthConnectUnavailable)
        assertThat(pusher.pushes.map { it.start }).doesNotContain(boom)
        // the six healthy days are still filed -- one bad read must not withhold good data
        assertThat(pusher.pushes).hasSize(6)
    }

    @Test
    fun w3_throwingToday_isUnavailable_andNoZeroIsPushedForToday() = runBlocking {
        val reader = FakeReader { start, _ ->
            if (start == todayStart) throw IllegalStateException("HC aggregate failed") else 4321L
        }
        val pusher = FakePusher()

        val out = engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.HealthConnectUnavailable)
        assertThat(pusher.pushes.map { it.start }).doesNotContain(todayStart)
    }

    // ---------------------------------------------------------------- W4

    @Test
    fun w4_firstRun_backfillsFrom26AugThroughTodayMinus7_thenTheRollingSeven() = runBlocking {
        val settings = newSettings()
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher()

        val out = engine(reader, pusher, settings).run()

        // 55 backfill days + 7 rolling
        assertThat(pusher.pushes).hasSize(62)
        assertThat(reader.calls).hasSize(62)
        // first = London 2026-08-26 (BST, so 25 Aug 23:00Z); full day, ends next London midnight
        assertThat(pusher.pushes.first().start).isEqualTo(Instant.parse("2026-08-25T23:00:00Z"))
        assertThat(pusher.pushes.first().end).isEqualTo(Instant.parse("2026-08-26T23:00:00Z"))
        // last backfill day = London 2026-10-19 (today - 7), right before the rolling 7 begins
        assertThat(pusher.pushes[54].start).isEqualTo(Instant.parse("2026-10-18T23:00:00Z"))
        assertThat(pusher.pushes[54].end).isEqualTo(Instant.parse("2026-10-19T23:00:00Z"))
        assertThat(pusher.pushes[55].start).isEqualTo(rollingStarts.first())
        // last push of the run is today
        assertThat(pusher.pushes.last()).isEqualTo(Push(todayStart, now, 5000))
        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
        assertThat(settings.healthConnectStepsBackfillDone.first()).isTrue()
    }

    @Test
    fun w4_secondRun_afterASuccessfulBackfill_pushesOnlyTheRollingSeven() = runBlocking {
        val settings = newSettings()
        engine(FakeReader { _, _ -> 5000L }, FakePusher(), settings).run() // first run, backfills

        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher()
        engine(reader, pusher, settings).run()

        assertThat(pusher.pushes.map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(reader.calls).hasSize(7)
    }

    @Test
    fun w4_backfillDayWithNullAggregate_isSkippedButStillCountsAsAClean_backfill() = runBlocking {
        val settings = newSettings()
        val reader = FakeReader { start, _ -> if (start == sep15Start) null else 5000L }
        val pusher = FakePusher()

        engine(reader, pusher, settings).run()

        assertThat(pusher.pushes).hasSize(61) // 62 minus the empty day
        assertThat(pusher.pushes.map { it.start }).doesNotContain(sep15Start)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isTrue()
    }

    @Test
    fun w4_aBackfillDayThatThrows_leavesTheFlagUnset_countsTheAttempt_andRetriesNextRun() = runBlocking {
        val settings = newSettings()
        var healthy = false
        val reader = FakeReader { start, _ ->
            if (start == sep15Start && !healthy) throw IllegalStateException("history read refused") else 5000L
        }
        val pusher = FakePusher()

        val first = engine(reader, pusher, settings).run()

        // the worker must not crash and the rolling 7 still ran: outcome comes from the rolling 7
        assertThat(first).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
        assertThat(pusher.pushes.map { it.start }).doesNotContain(sep15Start)
        assertThat(pusher.pushes).hasSize(61) // every other backfill day + rolling 7
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(1)

        // next run: the day reads fine now -> it is retried, flag set
        healthy = true
        val reader2 = FakeReader { _, _ -> 5000L }
        val pusher2 = FakePusher()
        engine(reader2, pusher2, settings).run()

        assertThat(reader2.calls.map { it.start }).contains(sep15Start)
        assertThat(pusher2.pushes.map { it.start }).contains(sep15Start)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isTrue()
    }

    @Test
    fun w4_aFailedBackfillPush_leavesTheFlagUnset_andIsPushFailed() = runBlocking {
        val settings = newSettings()
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher { start -> start != sep15Start }

        val out = engine(reader, pusher, settings).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.PushFailed)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(1)
    }

    @Test
    fun w4_afterThreeFailedAttempts_itStopsTryingTheBackfill() = runBlocking {
        val settings = newSettings()
        val alwaysThrowsOnSep15 = { start: Instant, _: Instant ->
            if (start == sep15Start) throw IllegalStateException("history read refused") else 5000L
        }

        repeat(3) { engine(FakeReader(alwaysThrowsOnSep15), FakePusher(), settings).run() }
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(3)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()

        // 4th run: backfill is NOT attempted -- only the rolling 7 are read and pushed,
        // even though every read would now succeed.
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher()
        engine(reader, pusher, settings).run()

        assertThat(reader.calls.map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(pusher.pushes).hasSize(7)
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(3)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
    }

    // ---------------------------------------------------------------- W5

    @Test
    fun w5_oneFailedPush_isPushFailed_andTheRemainingDaysAreStillAttempted() = runBlocking {
        val failing = Instant.parse("2026-10-22T23:00:00Z") // London 10-23
        val reader = FakeReader { _, _ -> 4321L }
        val pusher = FakePusher { start -> start != failing }

        val out = engine(reader, pusher, settingsWithBackfillDone()).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.PushFailed)
        assertThat(pusher.pushes).hasSize(7) // all 7 attempted; the next tick re-sends all 7
    }

    @Test
    fun w5_failedPushOfToday_isPushFailed() = runBlocking {
        val out = engine(
            FakeReader { _, _ -> 4321L },
            FakePusher { start -> start != todayStart },
            settingsWithBackfillDone(),
        ).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.PushFailed)
    }

    @Test
    fun w5_allPushesSucceed_isSuccessWithTodaysSteps() = runBlocking {
        val reader = FakeReader { start, _ -> if (start == todayStart) 980L else 4321L }

        val out = engine(reader, FakePusher(), settingsWithBackfillDone()).run()

        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(980))
    }
}
