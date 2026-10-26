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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.health.openscale.BuildConfig
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.json.JSONObject
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What [HealthConnectStepsSyncUseCase.syncNow] actually did, for
 * [com.health.openscale.core.worker.HealthConnectStepsSyncWorker] to turn into a `Result` and a
 * persisted [com.health.openscale.core.data.HealthConnectStepsError] -- see that worker's own
 * header for why the mapping lives there rather than here (this class stays free of
 * WorkManager/SettingsFacade concerns, mirroring [TrackAppSync]'s own lean shape).
 */
sealed interface HealthConnectStepsSyncOutcome {
    /** Pushed successfully; [steps] is today's running total as Health Connect reported it. */
    data class Success(val steps: Int) : HealthConnectStepsSyncOutcome

    /** `trackapp.properties` has no baseUrl/token -- same "not configured, skip silently" state
     *  [TrackAppSyncUseCase] already has, and for the identical reason: a normal upstream
     *  build/PR must be unaffected. NOT an error -- nothing for the owner to fix. */
    data object NotConfigured : HealthConnectStepsSyncOutcome

    /** [HealthConnectClient.getSdkStatus] said unavailable, or the aggregate read itself threw. */
    data object HealthConnectUnavailable : HealthConnectStepsSyncOutcome

    /** The app does not currently hold Health Connect's own READ_STEPS grant. */
    data object PermissionsNotGranted : HealthConnectStepsSyncOutcome

    /** Steps were read fine; the POST to trackapp's own `/api/device/steps` failed. */
    data object PushFailed : HealthConnectStepsSyncOutcome
}

/**
 * Fire-and-await entry point [HealthConnectStepsSyncWorker] depends on, so a unit test's DI
 * wiring (see testutil/RoomTestSupport.kt) can substitute a no-op double instead of the real
 * [HealthConnectStepsSyncUseCase] -- which makes a genuine Health Connect read AND a genuine
 * network call. Identical reasoning to [TrackAppSync]'s own doc comment: that interface exists
 * because a unit test once POSTed a live fixture into the owner's production trackapp database
 * before it did, and nothing here is entitled to assume that mistake cannot repeat itself for a
 * second endpoint.
 */
interface HealthConnectStepsSync {
    suspend fun syncNow(): HealthConnectStepsSyncOutcome
}

/**
 * Reads today's running step total from Android Health Connect and pushes it to the owner's own
 * trackapp instance (`POST /api/device/steps`, bearer-token authenticated -- see that route's own
 * header in the trackapp repo). A PERSONAL integration, not an openScale feature, on the identical
 * `trackapp.properties` config [TrackAppSyncUseCase] already reads -- see that class's own header
 * for why those two `BuildConfig` fields are never committed to source.
 *
 * `aggregate()`, NEVER `readRecords()` -- the documented way to avoid double-counting when more
 * than one source writes StepsRecord rows into Health Connect. Today Garmin Connect is the only
 * source syncing steps on the owner's phone; if the phone's own step counter is ever found to
 * ALSO write to Health Connect independently (verify this empirically on-device -- Health
 * Connect's own system UI lists every connected app under Data sources), scope the read with
 * `dataOriginFilter = setOf(DataOrigin("com.garmin.android.apps.connectmobile"))` on the
 * [AggregateRequest] below rather than guessing it is needed up front.
 *
 * WINDOW: each run re-reads today ([London midnight, now], via [com.health.openscale.core.utils.LondonTime],
 * NOT the device's timezone) plus the 6 previous full London days, one aggregate + one POST per
 * day (7 + <=7). Trackapp's `/api/device/steps` upserts on (source, day), so re-pushing is safe
 * and late-syncing Garmin data corrects earlier days. A one-time backfill from 2026-08-26
 * (see [RollingStepsSync]) adds 55 aggregates + <=55 POSTs.
 */
@Singleton
class HealthConnectStepsSyncUseCase @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val clock: Clock,
    private val settings: SettingsFacade,
) : HealthConnectStepsSync {
    private val TAG = "HealthConnectStepsSyncUseCase"

    override suspend fun syncNow(): HealthConnectStepsSyncOutcome {
        val baseUrl = BuildConfig.TRACKAPP_BASE_URL
        val token = BuildConfig.TRACKAPP_DEVICE_TOKEN
        if (baseUrl.isBlank() || token.isBlank()) {
            LogManager.d(TAG, "trackapp sync not configured (baseUrl/token empty); skipping")
            return HealthConnectStepsSyncOutcome.NotConfigured
        }

        if (HealthConnectClient.getSdkStatus(appContext) != HealthConnectClient.SDK_AVAILABLE) {
            LogManager.w(TAG, "Health Connect is not available on this device")
            return HealthConnectStepsSyncOutcome.HealthConnectUnavailable
        }
        val client = HealthConnectClient.getOrCreate(appContext)

        val granted = client.permissionController.getGrantedPermissions()
        if (READ_STEPS_PERMISSION !in granted) {
            LogManager.w(TAG, "READ_STEPS permission not granted; skipping")
            return HealthConnectStepsSyncOutcome.PermissionsNotGranted
        }

        val reader = StepsDayReader { start, end ->
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    // dataOriginFilter = setOf(DataOrigin("com.garmin.android.apps.connectmobile")),
                )
            )
            response[StepsRecord.COUNT_TOTAL]
        }
        val pusher = StepsPusher { start, end, steps ->
            val body = JSONObject().apply {
                put("windowStart", start.toString()) // Instant.toString() is already ISO-8601 'Z'
                put("windowEnd", end.toString())
                put("steps", steps)
            }
            val ok = TrackAppHttp.postJson(baseUrl, "/api/device/steps", token, body, SYNC_TIMEOUT_MS, TAG)
            LogManager.i(TAG, "trackapp steps push ${if (ok) "succeeded" else "failed"}: $start steps=$steps")
            ok
        }
        return RollingStepsSync(reader, pusher, settings, clock).run()
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 8_000L
        private val READ_STEPS_PERMISSION = HealthPermission.getReadPermission(StepsRecord::class)
    }
}

/** Maps [HealthConnectStepsSync] to the real implementation for production -- see that
 *  interface's own doc comment for why [HealthConnectStepsSyncWorker] depends on it rather than
 *  [HealthConnectStepsSyncUseCase] directly. */
@Module
@InstallIn(SingletonComponent::class)
interface HealthConnectStepsSyncBindsModule {
    @Binds
    fun bindHealthConnectStepsSync(impl: HealthConnectStepsSyncUseCase): HealthConnectStepsSync
}
