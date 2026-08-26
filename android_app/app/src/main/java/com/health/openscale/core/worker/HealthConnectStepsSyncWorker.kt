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
package com.health.openscale.core.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.health.openscale.core.data.HealthConnectStepsError
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.usecase.HealthConnectStepsSync
import com.health.openscale.core.usecase.HealthConnectStepsSyncOutcome
import com.health.openscale.core.utils.LogManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic worker mirroring [BackupWorker]'s own shape exactly: check the enabled flag first,
 * bail to [Result.success] if disabled (there is nothing wrong with "off"), otherwise do the real
 * work and persist the outcome through [SettingsFacade] so the settings screen can render it.
 *
 * [healthConnectStepsSync] is the [HealthConnectStepsSync] INTERFACE, never the concrete
 * [com.health.openscale.core.usecase.HealthConnectStepsSyncUseCase] -- see that interface's own
 * doc comment for why, and [HealthConnectStepsSyncWorkerTest] for the no-op double this makes
 * possible.
 */
@HiltWorker
class HealthConnectStepsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsFacade: SettingsFacade,
    private val healthConnectStepsSync: HealthConnectStepsSync,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "HealthConnectStepsSyncWorker"
        const val WORK_NAME = "com.health.openscale.HEALTH_CONNECT_STEPS_SYNC"
    }

    override suspend fun doWork(): Result {
        LogManager.i(TAG, "Health Connect steps sync worker started.")

        val isEnabled = settingsFacade.healthConnectStepsEnabled.first()
        if (!isEnabled) {
            LogManager.i(TAG, "Health Connect steps sync is disabled. Worker finishing.")
            return Result.success()
        }

        return when (val outcome = healthConnectStepsSync.syncNow()) {
            is HealthConnectStepsSyncOutcome.Success -> {
                LogManager.i(TAG, "Health Connect steps sync succeeded: steps=${outcome.steps}")
                settingsFacade.setHealthConnectStepsLastSuccessfulTimestamp(System.currentTimeMillis())
                settingsFacade.setHealthConnectStepsLastError(null)
                Result.success()
            }
            // NOT CONFIGURED IS NOT A FAULT -- see HealthConnectStepsSyncOutcome.NotConfigured's
            // own doc comment. No error is recorded; this run simply had nothing to do.
            HealthConnectStepsSyncOutcome.NotConfigured -> {
                LogManager.d(TAG, "trackapp sync not configured; nothing to do.")
                Result.success()
            }
            HealthConnectStepsSyncOutcome.HealthConnectUnavailable -> {
                settingsFacade.setHealthConnectStepsLastError(HealthConnectStepsError.HEALTH_CONNECT_UNAVAILABLE)
                Result.failure()
            }
            HealthConnectStepsSyncOutcome.PermissionsNotGranted -> {
                settingsFacade.setHealthConnectStepsLastError(HealthConnectStepsError.PERMISSIONS_NOT_GRANTED)
                Result.failure()
            }
            HealthConnectStepsSyncOutcome.PushFailed -> {
                settingsFacade.setHealthConnectStepsLastError(HealthConnectStepsError.PUSH_FAILED)
                // PLAIN Result.failure(), NO RETRY -- matches BackupWorker's own convention:
                // rely on the next periodic tick rather than a new retry policy nothing else in
                // this app uses.
                Result.failure()
            }
        }
    }
}
