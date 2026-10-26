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
import kotlinx.coroutines.CancellationException
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
 * 2026-08-26 .. 2026-10-19 = 55 days. Push ORDER (TA-376): the rolling 7 FIRST (oldest window
 * first, today last), then the backfill days (when due), oldest first.
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

        // 7 rolling + 55 backfill days (TA-376: rolling FIRST, backfill after)
        assertThat(pusher.pushes).hasSize(62)
        assertThat(reader.calls).hasSize(62)
        assertThat(pusher.pushes.take(7).map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(pusher.pushes[6]).isEqualTo(Push(todayStart, now, 5000))
        // first backfill push = London 2026-08-26 (BST, so 25 Aug 23:00Z); full day, ends next London midnight
        assertThat(pusher.pushes[7].start).isEqualTo(Instant.parse("2026-08-25T23:00:00Z"))
        assertThat(pusher.pushes[7].end).isEqualTo(Instant.parse("2026-08-26T23:00:00Z"))
        // last backfill day = London 2026-10-19 (today - 7), the last push of the run
        assertThat(pusher.pushes.last().start).isEqualTo(Instant.parse("2026-10-18T23:00:00Z"))
        assertThat(pusher.pushes.last().end).isEqualTo(Instant.parse("2026-10-19T23:00:00Z"))
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
    fun w4_aFailedBackfillPush_leavesTheFlagUnset_butDoesNotChangeTheRollingOutcome() = runBlocking {
        val settings = newSettings()
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher { start -> start != sep15Start }

        val out = engine(reader, pusher, settings).run()

        // TA-376: the outcome is derived from the rolling 7 only; a backfill failure never changes it
        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
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

    // ---------------------------------------------------------------- TA-376 (ARGUS TA-357)
    // A crash/cancel mid-backfill can't loop forever or starve the rolling 7.

    /** Runs the engine and reports whether it propagated a CancellationException (it must). */
    private suspend fun runPropagatesCancellation(e: RollingStepsSync): Boolean =
        try { e.run(); false } catch (c: CancellationException) { true }

    private val cancelledAtSep15Read = { start: Instant, _: Instant ->
        if (start == sep15Start) throw CancellationException("worker stopped") else 5000L
    }

    @Test
    fun ta376_a_readerCancelledDuringBackfill_consumesOneAttempt_flagUnset_rollingAlreadyPushed() = runBlocking {
        val settings = newSettings()
        val pusher = FakePusher()

        val propagated = runPropagatesCancellation(engine(FakeReader(cancelledAtSep15Read), pusher, settings))

        assertThat(propagated).isTrue() // cancellation is rethrown, never swallowed
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(1)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
        // the rolling 7 went out BEFORE the backfill was cut short
        assertThat(pusher.pushes.take(7).map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(pusher.pushes.map { it.start }).doesNotContain(sep15Start)
    }

    @Test
    fun ta376_a_pusherCancelledDuringBackfill_consumesOneAttempt_flagUnset_rollingAlreadyPushed() = runBlocking {
        val settings = newSettings()
        val pusher = object : StepsPusher {
            val pushes = mutableListOf<Push>()
            override suspend fun push(windowStart: Instant, windowEnd: Instant, steps: Int): Boolean {
                if (windowStart == sep15Start) throw CancellationException("worker stopped")
                pushes += Push(windowStart, windowEnd, steps)
                return true
            }
        }

        val propagated = runPropagatesCancellation(engine(FakeReader { _, _ -> 5000L }, pusher, settings))

        assertThat(propagated).isTrue()
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(1)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
        assertThat(pusher.pushes.take(7).map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
    }

    @Test
    fun ta376_a_attemptIsPersistedBeforeTheFirstBackfillRead() = runBlocking {
        val settings = newSettings()
        var attemptsSeenAtFirstBackfillRead: Int? = null
        val reader = FakeReader { start, _ ->
            if (attemptsSeenAtFirstBackfillRead == null && start < rollingStarts.first()) {
                attemptsSeenAtFirstBackfillRead = runBlocking { settings.healthConnectStepsBackfillAttempts.first() }
            }
            5000L
        }

        engine(reader, FakePusher(), settings).run()

        assertThat(attemptsSeenAtFirstBackfillRead).isEqualTo(1) // +1 was already stored when the backfill began
        // a clean finish sets Done and clears nothing: the consumed attempt stays on the books
        assertThat(settings.healthConnectStepsBackfillDone.first()).isTrue()
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(1)
    }

    @Test
    fun ta376_a_cancelledDuringTheRolling7_doesNotConsumeABackfillAttempt() = runBlocking {
        val settings = newSettings()
        val cancelOnToday = FakeReader { start, _ ->
            if (start == todayStart) throw CancellationException("worker stopped") else 5000L
        }

        val propagated = runPropagatesCancellation(engine(cancelOnToday, FakePusher(), settings))

        assertThat(propagated).isTrue()
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(0) // backfill never started
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
    }

    @Test
    fun ta376_b_afterThreeCancelledRuns_theBackfillIsNotAttemptedAgain() = runBlocking {
        val settings = newSettings()

        repeat(3) {
            assertThat(runPropagatesCancellation(engine(FakeReader(cancelledAtSep15Read), FakePusher(), settings))).isTrue()
        }
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(3)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()

        // 4th run: everything would now read fine, but only the rolling 7 is touched
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher()
        val out = engine(reader, pusher, settings).run()

        assertThat(reader.calls.map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(pusher.pushes.map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(out).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
        assertThat(settings.healthConnectStepsBackfillAttempts.first()).isEqualTo(3)
        assertThat(settings.healthConnectStepsBackfillDone.first()).isFalse()
    }

    @Test
    fun ta376_c_rollingReadsAndPushesAllHappenBeforeAnyBackfillRead_orPush() = runBlocking {
        val settings = newSettings()
        val reader = FakeReader { _, _ -> 5000L }
        val pusher = FakePusher()

        engine(reader, pusher, settings).run()

        val rolling = rollingStarts.toSet()
        // pushes: positions 0..6 are the rolling 7, every later push is a backfill day
        assertThat(pusher.pushes.take(7).map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(pusher.pushes.drop(7).map { it.start }.filter { it in rolling }).isEmpty()
        assertThat(pusher.pushes.drop(7)).hasSize(55)
        // reads: same split, so a cancel during any backfill read still leaves the rolling 7 pushed
        assertThat(reader.calls.take(7).map { it.start }).containsExactlyElementsIn(rollingStarts).inOrder()
        assertThat(reader.calls.drop(7).map { it.start }.filter { it in rolling }).isEmpty()
    }

    @Test
    fun ta376_c_aBackfillThatThrowsOrFailsToPush_neverChangesTheRollingOutcome() = runBlocking {
        val throwing = engine(
            FakeReader { start, _ -> if (start == sep15Start) throw IllegalStateException("refused") else 5000L },
            FakePusher(), newSettings(),
        ).run()
        val failedPush = engine(
            FakeReader { _, _ -> 5000L }, FakePusher { start -> start != sep15Start }, newSettings(),
        ).run()

        assertThat(throwing).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
        assertThat(failedPush).isEqualTo(HealthConnectStepsSyncOutcome.Success(5000))
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
