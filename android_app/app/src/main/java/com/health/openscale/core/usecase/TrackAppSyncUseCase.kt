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
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a saved [Measurement] to the owner's own trackapp instance (`POST /api/device/weight`,
 * bearer-token authenticated — see that route's own header in the trackapp repo). This is a
 * PERSONAL integration, not an openScale feature: it exists purely because this fork's owner
 * wants their weigh-ins to land in their own dashboard without a manual step, and it fails
 * silently closed (does nothing) on any build that doesn't carry the two `BuildConfig` values
 * below — see app/build.gradle.kts's own comment on `trackapp.properties` for why those are
 * never committed to source.
 *
 * Only weight and body fat % are sent — trackapp's `WeightEntry` table has no columns for
 * water/muscle/bone/BMR today, so there's nowhere for them to land yet.
 *
 * Called from [MeasurementCrudUseCases.saveMeasurement], right alongside the existing
 * [SyncUseCases] Intent-based sync-app pushes, on a short timeout so a bad/absent network
 * connection at weigh-in time can never hang or fail the save itself — a failed push is only
 * ever reported via [showResultNotification], never by throwing back into the caller.
 */
@Singleton
class TrackAppSyncUseCase @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val measurementTypeUseCases: MeasurementTypeCrudUseCases,
) {
    private val TAG = "TrackAppSyncUseCase"

    /** This use case's own scope, not the caller's: [MeasurementCrudUseCases.saveMeasurement]
     *  must return as soon as the measurement is actually saved, the same way it already does
     *  for [SyncUseCases]'s Intent-based pushes — a slow/absent network at weigh-in time (a
     *  bathroom, typically the worst WiFi spot in a home) must never add up to [SYNC_TIMEOUT_MS]
     *  of latency to that save. [pushAsync] fires this and returns immediately; the result only
     *  ever surfaces via [showResultNotification]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun pushAsync(measurement: Measurement, values: List<MeasurementValue>) {
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

        val massG = Math.round(weightKg * 1000f)
        val bodyFatBp = fatPercent?.let { Math.round(it * 100f) }
        val measuredAt = isoInstant(Date(measurement.timestamp))

        val body = JSONObject().apply {
            put("measuredAt", measuredAt)
            put("massG", massG)
            if (bodyFatBp != null) put("bodyFatBp", bodyFatBp) else put("bodyFatBp", JSONObject.NULL)
        }

        val success = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { postWeight(baseUrl, token, body) }
        } ?: false

        LogManager.i(TAG, "trackapp sync ${if (success) "succeeded" else "failed"}: massG=$massG bodyFatBp=$bodyFatBp")
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

    private fun postWeight(baseUrl: String, token: String, body: JSONObject): Boolean {
        return try {
            val url = URL("${baseUrl.trimEnd('/')}/api/device/weight")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = SYNC_TIMEOUT_MS.toInt()
                readTimeout = SYNC_TIMEOUT_MS.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                LogManager.w(TAG, "trackapp responded $status: $errorBody")
            }
            connection.disconnect()
            status in 200..299
        } catch (e: Exception) {
            LogManager.w(TAG, "trackapp push failed: ${e.message}")
            false
        }
    }

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
