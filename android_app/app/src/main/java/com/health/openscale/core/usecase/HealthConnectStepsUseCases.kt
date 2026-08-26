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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.health.openscale.core.data.HealthConnectStepsError
import com.health.openscale.core.data.HealthConnectSyncInterval
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.worker.HealthConnectStepsSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates the Health Connect steps sync settings and its WorkManager scheduling, mirroring
 * [AutoBackupUseCases]'s own shape exactly.
 *
 * Responsibilities:
 * - Expose/modify the sync preferences via [SettingsFacade].
 * - (Re)Schedule or cancel the periodic [HealthConnectStepsSyncWorker] based on them.
 * - Offer a single entry point ([refreshSchedule]) to recompute scheduling after any change --
 *   called by the settings screen after every mutation and by [BootReceiver] after a reboot.
 */
@Singleton
class HealthConnectStepsUseCases @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settings: SettingsFacade
) {
    private val TAG = "HealthConnectStepsUseCases"

    // --- Observables (pass-through) ---
    val enabled: Flow<Boolean> get() = settings.healthConnectStepsEnabled
    val interval: Flow<HealthConnectSyncInterval> get() = settings.healthConnectStepsInterval
    val lastSuccessfulTimestamp: Flow<Long> get() = settings.healthConnectStepsLastSuccessfulTimestamp
    val lastError: Flow<HealthConnectStepsError?> get() = settings.healthConnectStepsLastError
    val lastErrorTimestamp: Flow<Long> get() = settings.healthConnectStepsLastErrorTimestamp

    /** When the periodic worker is due next, or null if nothing is queued -- see
     *  [AutoBackupUseCases.nextBackupAtMillis]'s own doc comment for why this distinction (vs.
     *  "Last synced: Never") matters, and why [Long.MAX_VALUE] is filtered out here too. */
    val nextSyncAtMillis: Flow<Long?>
        get() = WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWorkFlow(HealthConnectStepsSyncWorker.WORK_NAME)
            .map { infos ->
                infos.firstOrNull()?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE }
            }
            .distinctUntilChanged()

    // --- Mutations ---

    /** Enable/disable the sync and refresh scheduling. */
    suspend fun setEnabled(value: Boolean) {
        settings.setHealthConnectStepsEnabled(value)
        refreshSchedule()
    }

    /** Update interval and refresh scheduling. */
    suspend fun setInterval(value: HealthConnectSyncInterval) {
        settings.setHealthConnectStepsInterval(value)
        refreshSchedule()
    }

    /**
     * Recompute WorkManager scheduling according to current preferences.
     * Cancels the worker if disabled.
     */
    suspend fun refreshSchedule() {
        val isEnabled = enabled.first()
        val wm = WorkManager.getInstance(appContext)

        if (!isEnabled) {
            wm.cancelUniqueWork(HealthConnectStepsSyncWorker.WORK_NAME)
            LogManager.i(TAG, "Health Connect steps sync disabled; worker cancelled.")
            return
        }

        val chosen = interval.first()
        val repeatMillis = intervalMillis(chosen)
        // CONNECTED, unlike AutoBackupUseCases' NOT_REQUIRED -- this worker's whole job is a
        // network push to trackapp, so a run with no network can only fail.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<HealthConnectStepsSyncWorker>(
            repeatMillis, TimeUnit.MILLISECONDS
        ).setConstraints(constraints)
            .addTag(HealthConnectStepsSyncWorker.TAG)
            .build()

        // UPDATE, not REPLACE -- same reasoning as AutoBackupUseCases.refreshSchedule: REPLACE
        // discards the elapsed period, so every settings change and every reboot would restart
        // the interval from zero.
        wm.enqueueUniquePeriodicWork(
            HealthConnectStepsSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
        LogManager.i(TAG, "Health Connect steps sync scheduled: interval=$chosen repeat=${repeatMillis}ms")
    }

    private fun intervalMillis(interval: HealthConnectSyncInterval): Long = when (interval) {
        HealthConnectSyncInterval.HOURLY -> 1L * 60 * 60 * 1000L
        HealthConnectSyncInterval.EVERY_2_HOURS -> 2L * 60 * 60 * 1000L
        HealthConnectSyncInterval.EVERY_4_HOURS -> 4L * 60 * 60 * 1000L
        HealthConnectSyncInterval.EVERY_6_HOURS -> 6L * 60 * 60 * 1000L
        HealthConnectSyncInterval.DAILY -> 24L * 60 * 60 * 1000L
    }
}
