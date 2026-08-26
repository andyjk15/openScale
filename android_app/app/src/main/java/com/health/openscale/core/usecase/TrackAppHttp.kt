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

import com.health.openscale.core.utils.LogManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE ONE bearer-token JSON POST TO THE OWNER'S trackapp INSTANCE, extracted out of
 * [TrackAppSyncUseCase.postWeight] the moment a second caller
 * ([HealthConnectStepsSyncUseCase]) needed the identical block byte-for-byte. Duplicating an
 * `HttpURLConnection` POST verbatim a second time is exactly the drift risk this codebase already
 * avoids elsewhere (see `toPlanBody`'s own single-implementation reasoning on the trackapp side of
 * this integration) -- a low-risk, mechanical extraction of already-working code, not a rewrite.
 */
object TrackAppHttp {
    /**
     * POSTs [body] as JSON to `${baseUrl}${path}`, bearer-authenticated with [token]. Returns
     * true on any 2xx response, false on anything else OR any exception -- this never throws, so
     * a caller on a tight [kotlinx.coroutines.withTimeoutOrNull] budget never needs its own
     * try/catch around it.
     */
    fun postJson(
        baseUrl: String,
        path: String,
        token: String,
        body: JSONObject,
        timeoutMs: Long,
        tag: String,
    ): Boolean {
        return try {
            val url = URL("${baseUrl.trimEnd('/')}$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                LogManager.w(tag, "trackapp responded $status: $errorBody")
            }
            connection.disconnect()
            status in 200..299
        } catch (e: Exception) {
            LogManager.w(tag, "trackapp push failed: ${e.message}")
            false
        }
    }
}
