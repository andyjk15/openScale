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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.testutil.RoomTestSupport
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

/**
 * Runs [HealthConnectStepsSyncWorker.doWork] on the JVM (Robolectric + WorkManager test harness),
 * mirroring [BackupWorkerTest]'s own shape. The dependency under test is
 * [RoomTestSupport.NoOpHealthConnectStepsSync] throughout -- this proves the worker's OWN
 * enabled-check and outcome-mapping wiring works without ever reaching Health Connect or the
 * network, which is exactly what a JVM unit test can and cannot prove: the wiring, not a real
 * device's Health Connect data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectStepsSyncWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun doWork_returnsSuccess_whenDisabled() = runBlocking {
        val settings = newSettings() // healthConnectStepsEnabled defaults to false

        val result = newWorker(settings).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun doWork_returnsSuccess_whenEnabledWithTheNoOpDouble() = runBlocking {
        val settings = newSettings()
        settings.setHealthConnectStepsEnabled(true)

        // NoOpHealthConnectStepsSync.syncNow() returns NotConfigured, which the worker treats as
        // "nothing to do" rather than a failure -- see HealthConnectStepsSyncOutcome.NotConfigured
        // and HealthConnectStepsSyncWorker's own doWork(). Reaching Result.success() here proves
        // the enabled path is wired correctly, with no real Health Connect read or network call.
        val result = newWorker(settings).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(settings.healthConnectStepsLastSuccessfulTimestamp.first()).isEqualTo(0L)
    }

    private fun newSettings(): SettingsFacadeImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.cacheDir, "hcsw-${System.nanoTime()}.preferences_pb") },
        )
        return SettingsFacadeImpl(dataStore)
    }

    private fun newWorker(settings: SettingsFacadeImpl): HealthConnectStepsSyncWorker {
        return TestListenableWorkerBuilder<HealthConnectStepsSyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = HealthConnectStepsSyncWorker(
                    appContext,
                    workerParameters,
                    settings,
                    RoomTestSupport.NoOpHealthConnectStepsSync,
                )
            })
            .build()
    }
}
