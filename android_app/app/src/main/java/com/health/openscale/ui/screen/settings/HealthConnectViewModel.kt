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
package com.health.openscale.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.core.data.HealthConnectStepsError
import com.health.openscale.core.data.HealthConnectSyncInterval
import com.health.openscale.core.usecase.HealthConnectStepsUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel bridging [HealthConnectStepsUseCases] and [HealthConnectSettingsScreen] -- its OWN
 * ViewModel, mirroring [BluetoothViewModel]'s own precedent for an external integration, rather
 * than folded into the giant [SettingsViewModel] -- see that screen's own header for the full
 * "why its own screen" argument, which applies identically here to "why its own ViewModel".
 *
 * Permission grants themselves are NOT this ViewModel's job -- [PermissionController]'s launcher
 * contract needs an Activity-scoped `rememberLauncherForActivityResult`, which a ViewModel cannot
 * hold. [HealthConnectSettingsScreen] owns those launchers directly, the same split
 * [BluetoothActionButton] already uses for Bluetooth's own permission/enable launchers.
 */
@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val healthConnectStepsUseCases: HealthConnectStepsUseCases,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = healthConnectStepsUseCases.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val interval: StateFlow<HealthConnectSyncInterval> = healthConnectStepsUseCases.interval
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthConnectSyncInterval.EVERY_4_HOURS)

    val lastSuccessfulTimestamp: StateFlow<Long> = healthConnectStepsUseCases.lastSuccessfulTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastError: StateFlow<HealthConnectStepsError?> = healthConnectStepsUseCases.lastError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastErrorTimestamp: StateFlow<Long> = healthConnectStepsUseCases.lastErrorTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** When the periodic worker is due next, or null if nothing is queued -- see
     *  [HealthConnectStepsUseCases.nextSyncAtMillis]'s own doc comment. */
    val nextSyncAtMillis: StateFlow<Long?> = healthConnectStepsUseCases.nextSyncAtMillis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Enable/disable and refresh scheduling. The screen calls this only AFTER the foreground
     *  permission is actually granted -- see [HealthConnectSettingsScreen]'s own permission
     *  launcher callback. */
    fun setEnabled(value: Boolean) {
        viewModelScope.launch { healthConnectStepsUseCases.setEnabled(value) }
    }

    fun setInterval(value: HealthConnectSyncInterval) {
        viewModelScope.launch { healthConnectStepsUseCases.setInterval(value) }
    }
}
