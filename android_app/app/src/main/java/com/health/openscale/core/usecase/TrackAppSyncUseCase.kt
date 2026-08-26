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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.health.openscale.BuildConfig
import com.health.openscale.MainActivity
import com.health.openscale.R
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.di.ApplicationScope
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget entry point [MeasurementCrudUseCases] depends on, so a unit test's DI wiring
 * (see testutil/RoomTestSupport.kt) can substitute a no-op double instead of the real
 * [TrackAppSyncUseCase] — which makes a genuine network call. That distinction matters
 * concretely, not hypothetically: before this interface existed, RoomTestSupport constructed
 * the real class directly, `trackapp.properties` (gitignored, personal, never empty on this
 * machine) meant `BuildConfig.TRACKAPP_BASE_URL`/`TRACKAPP_DEVICE_TOKEN` were never blank even
 * under `testDebugUnitTest`, and a single full test-suite run POSTed a test fixture's
 * `massG=70000`/epoch timestamp into the owner's REAL production trackapp database. "Fails
 * silently closed when unconfigured" is not a substitute for "unit tests cannot reach the
 * network at all" — this interface is what actually guarantees the latter.
 */
interface TrackAppSync {
    fun pushAsync(measurement: Measurement, values: List<MeasurementValue>)
}

/**
 * Pushes a saved [Measurement] to the owner's own trackapp instance (`POST /api/device/weight`,
 * bearer-token authenticated — see that route's own header in the trackapp repo). This is a
 * PERSONAL integration, not an openScale feature: it exists purely because this fork's owner
 * wants their weigh-ins to land in their own dashboard without a manual step, and it fails
 * silently closed (does nothing) on any build that doesn't carry the two `BuildConfig` values
 * below — see app/build.gradle.kts's own comment on `trackapp.properties` for why those are
 * never committed to source.
 *
 * Weight, body fat %, and lean mass are sent — `WeightEntry` has columns for exactly those
 * three (`massG`/`bodyFatBp`/`leanMassG`). Water %, muscle %, bone, and BMR aren't sent:
 * trackapp has no columns for them today, so there's nowhere for them to land yet.
 *
 * Called from [MeasurementCrudUseCases.saveMeasurement], right alongside the existing
 * [SyncUseCases] Intent-based sync-app pushes, on a short timeout so a bad/absent network
 * connection at weigh-in time can never hang or fail the save itself — a failed push is only
 * ever reported via [showResultNotification], never by throwing back into the caller.
 *
 * NOT injected directly anywhere — [MeasurementCrudUseCases] depends on [TrackAppSync], and
 * [TrackAppSyncBindsModule] is what maps that interface to this implementation in the real app.
 * See [TrackAppSync]'s own doc comment for why that indirection exists at all.
 */
@Singleton
class TrackAppSyncUseCase @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val measurementTypeUseCases: MeasurementTypeCrudUseCases,
    @param:ApplicationScope private val scope: CoroutineScope,
) : TrackAppSync {
    private val TAG = "TrackAppSyncUseCase"

    /** Launched on the app's one shared [ApplicationScope], not the caller's:
     *  [MeasurementCrudUseCases.saveMeasurement] must return as soon as the measurement is
     *  actually saved, the same way it already does for [SyncUseCases]'s Intent-based pushes —
     *  a slow/absent network at weigh-in time (a bathroom, typically the worst WiFi spot in a
     *  home) must never add up to [SYNC_TIMEOUT_MS] of latency to that save. [pushAsync] fires
     *  this and returns immediately; the result only ever surfaces via [showResultNotification].
     *  See [com.health.openscale.core.di.CoroutineScopeModule]'s own doc comment for why this
     *  must be the shared scope and not a second one of this class's own. */
    override fun pushAsync(measurement: Measurement, values: List<MeasurementValue>) {
        scope.launch { push(measurement, values) }
    }

    private suspend fun push(measurement: Measurement, values: List<MeasurementValue>) {
        val baseUrl = BuildConfig.TRACKAPP_BASE_URL
        val token = BuildConfig.TRACKAPP_DEVICE_TOKEN
        if (baseUrl.isBlank() || token.isBlank()) {
            LogManager.d(TAG, "trackapp sync not configured (baseUrl/token empty); skipping")
            return
        }

        val types = runCatching { measurementTypeUseCases.getAll() }.getOrNull()
        if (types == null) {
            LogManager.w(TAG, "could not load measurement types; skipping trackapp sync")
            return
        }

        val weightType = types.find { it.key == MeasurementTypeKey.WEIGHT }
        val weightValue = weightType?.let { wt -> values.find { it.typeId == wt.id } }
        val weightKg = weightType?.let { wt -> weightValue?.let { v -> canonicalValue(v, wt.unit) } }
        if (weightKg == null || weightKg <= 0f) {
            LogManager.d(TAG, "no weight in this measurement; skipping trackapp sync")
            return
        }

        val fatType = types.find { it.key == MeasurementTypeKey.BODY_FAT }
        val fatValue = fatType?.let { ft -> values.find { it.typeId == ft.id } }
        val fatPercent = fatType?.let { ft ->
            fatValue?.let { v -> canonicalValue(v, ft.unit) }?.let { canonical ->
                // BODY_FAT's canonical unit stays PERCENT if the user configured it that way;
                // if it's a weight-based unit (KG/LB/ST) instead, it canonicalises to KG (see
                // GenericValueJson.canonicalUnit) and needs converting back to a percentage of
                // this same measurement's weight — mirrors DatabaseProvider.addConvertedValue's
                // own inverse of this exact conversion.
                if (ft.unit == UnitType.PERCENT) canonical else (canonical / weightKg) * 100f
            }
        }

        // LBM's allowed units are only KG/LB/ST (never PERCENT), so canonicalValue already
        // lands it in kg directly — no weight-relative conversion needed, unlike fat above.
        val lbmType = types.find { it.key == MeasurementTypeKey.LBM }
        val lbmValue = lbmType?.let { lt -> values.find { it.typeId == lt.id } }
        val leanMassKg = lbmType?.let { lt -> lbmValue?.let { v -> canonicalValue(v, lt.unit) } }

        val massG = Math.round(weightKg * 1000f)
        val bodyFatBp = fatPercent?.let { Math.round(it * 100f) }
        val leanMassG = leanMassKg?.let { Math.round(it * 1000f) }
        val measuredAt = isoInstant(Date(measurement.timestamp))

        val body = JSONObject().apply {
            put("measuredAt", measuredAt)
            put("massG", massG)
            put("bodyFatBp", bodyFatBp ?: JSONObject.NULL)
            put("leanMassG", leanMassG ?: JSONObject.NULL)
        }

        val success = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { postWeight(baseUrl, token, body) }
        } ?: false

        LogManager.i(TAG, "trackapp sync ${if (success) "succeeded" else "failed"}: massG=$massG bodyFatBp=$bodyFatBp leanMassG=$leanMassG")
        showResultNotification(success)
    }

    /** [MeasurementValue.floatValue]/[MeasurementValue.intValue] converted from [unit] to its
     *  canonical dimension unit (KG/CM stay themselves; LB/ST/INCH convert; PERCENT is already
     *  its own canonical form) — the same normalisation [com.health.openscale.core.usecase.GenericValueJson]
     *  applies for the sync-app Intent payload, kept local here to avoid a JSON round-trip for
     *  the two fields this push actually needs. */
    private fun canonicalValue(value: MeasurementValue, unit: UnitType): Float? {
        val raw = value.floatValue ?: value.intValue?.toFloat() ?: return null
        val canonicalUnit = when (unit) {
            UnitType.LB, UnitType.ST -> UnitType.KG
            UnitType.INCH -> UnitType.CM
            else -> unit
        }
        return ConverterUtils.convertFloatValueUnit(raw, unit, canonicalUnit)
    }

    /** The HTTP mechanics live in [TrackAppHttp.postJson], shared with
     *  [HealthConnectStepsSyncUseCase]'s identical push to a different endpoint. */
    private fun postWeight(baseUrl: String, token: String, body: JSONObject): Boolean =
        TrackAppHttp.postJson(baseUrl, "/api/device/weight", token, body, SYNC_TIMEOUT_MS, TAG)

    private fun isoInstant(date: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(date)
    }

    private fun showResultNotification(success: Boolean) {
        val nm = NotificationManagerCompat.from(appContext)
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.trackapp_sync_channel_name),
            if (success) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val content = appContext.getString(
            if (success) R.string.trackapp_sync_success else R.string.trackapp_sync_failure
        )

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (success) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canNotify) {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val SYNC_TIMEOUT_MS = 8_000L
        const val CHANNEL_ID = "trackapp_sync"
        const val NOTIFICATION_ID = 2001
    }
}

/** Maps [TrackAppSync] to the real, network-calling [TrackAppSyncUseCase] for production —
 *  see [TrackAppSync]'s own doc comment for why [MeasurementCrudUseCases] depends on the
 *  interface rather than this class directly. */
@Module
@InstallIn(SingletonComponent::class)
interface TrackAppSyncBindsModule {
    @Binds
    fun bindTrackAppSync(impl: TrackAppSyncUseCase): TrackAppSync
}
