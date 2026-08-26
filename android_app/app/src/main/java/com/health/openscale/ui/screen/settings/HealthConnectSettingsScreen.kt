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

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.health.openscale.R
import com.health.openscale.core.data.HealthConnectSyncInterval
import com.health.openscale.ui.shared.SharedViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val READ_STEPS_PERMISSION = HealthPermission.getReadPermission(StepsRecord::class)
private val BACKGROUND_READ_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/**
 * ITS OWN SETTINGS SCREEN, NOT A SECTION INSIDE [DataManagementSettingsScreen] -- Health Connect
 * is an EXTERNAL INTEGRATION, the same category Bluetooth already gets [BluetoothScreen] for,
 * not a knob on the owner's own data (export/import/backup/delete) the way
 * [DataManagementSettingsScreen] is scoped. Reuses that screen's [SettingsCardItem] and
 * [SelectionDialogEnum] composables as-is (same package, no changes needed to either) for the
 * toggle/interval-picker/status-text shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectSettingsScreen(
    sharedViewModel: SharedViewModel,
    healthConnectViewModel: HealthConnectViewModel,
) {
    val screenTitle = stringResource(R.string.settings_item_health_connect)
    LaunchedEffect(Unit) {
        sharedViewModel.setTopBarTitle(screenTitle)
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val resources = context.resources

    val enabled by healthConnectViewModel.enabled.collectAsState()
    val interval by healthConnectViewModel.interval.collectAsState()
    val lastSuccessfulTimestamp by healthConnectViewModel.lastSuccessfulTimestamp.collectAsState()
    val lastError by healthConnectViewModel.lastError.collectAsState()
    val lastErrorTimestamp by healthConnectViewModel.lastErrorTimestamp.collectAsState()
    val nextSyncAtMillis by healthConnectViewModel.nextSyncAtMillis.collectAsStateWithLifecycle(null)

    val healthConnectAvailable = remember {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
    val healthConnectClient = remember(healthConnectAvailable) {
        if (healthConnectAvailable) HealthConnectClient.getOrCreate(context) else null
    }

    var permissionsGranted by remember { mutableStateOf(false) }
    var backgroundPermissionGranted by remember { mutableStateOf(false) }

    suspend fun refreshPermissionState() {
        val granted = healthConnectClient?.permissionController?.getGrantedPermissions() ?: emptySet()
        permissionsGranted = READ_STEPS_PERMISSION in granted
        backgroundPermissionGranted = BACKGROUND_READ_PERMISSION in granted
    }

    LaunchedEffect(healthConnectClient) {
        refreshPermissionState()
    }

    // TWO SEPARATE GRANTS, REQUESTED AS TWO SEPARATE LAUNCHES -- Health Connect's own docs do not
    // make it unambiguous whether background read can be bundled into the same permission set as
    // the foreground read; the safer, verified-elsewhere pattern (a "Request Background Read" as
    // its OWN step, only once the foreground grant is already held) is what this follows.
    // VERIFY ON-DEVICE whether this two-step shape is actually necessary on the owner's own
    // Health Connect version -- if a single combined request also works there, this can collapse
    // to one launcher.
    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) {
        coroutineScope.launch {
            refreshPermissionState()
            if (permissionsGranted && backgroundPermissionGranted) {
                healthConnectViewModel.setEnabled(true)
            }
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        coroutineScope.launch {
            refreshPermissionState()
            if (READ_STEPS_PERMISSION in granted) {
                backgroundPermissionLauncher.launch(setOf(BACKGROUND_READ_PERMISSION))
            }
        }
    }

    val installHealthConnectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Returning from the Play Store -- nothing actionable until the owner re-opens this
        // screen; getSdkStatus() is only read once per composition (see healthConnectAvailable
        // above), matching how a fresh HealthConnectClient is only ever needed after a real
        // process restart in practice.
    }

    fun requestEnable() {
        when {
            !healthConnectAvailable -> {
                val uri = Uri.parse(
                    "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
                )
                installHealthConnectLauncher.launch(
                    Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")
                )
            }
            permissionsGranted && backgroundPermissionGranted -> {
                coroutineScope.launch { healthConnectViewModel.setEnabled(true) }
            }
            permissionsGranted -> backgroundPermissionLauncher.launch(setOf(BACKGROUND_READ_PERMISSION))
            else -> permissionsLauncher.launch(setOf(READ_STEPS_PERMISSION))
        }
    }

    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    }
    val hasCurrentError = lastError != null && lastErrorTimestamp >= lastSuccessfulTimestamp
    val statusText = when {
        !healthConnectAvailable -> resources.getString(R.string.hc_status_not_installed)
        !enabled -> resources.getString(R.string.hc_status_disabled)
        hasCurrentError -> resources.getString(
            R.string.hc_status_failed,
            dateFormat.format(Date(lastErrorTimestamp)),
            lastError!!.getDisplayName(context)
        )
        lastSuccessfulTimestamp > 0L -> resources.getString(
            R.string.hc_status_last_synced,
            dateFormat.format(Date(lastSuccessfulTimestamp))
        )
        else -> resources.getString(R.string.hc_status_never_synced)
    }
    val nextSyncText = nextSyncAtMillis?.takeIf { enabled }?.let {
        resources.getString(R.string.hc_status_next_sync, dateFormat.format(Date(it)))
    }

    Column(modifier = Modifier
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.hc_intro_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SettingsCardItem(
            label = stringResource(R.string.hc_toggle_label),
            supportingText = if (!healthConnectAvailable) {
                stringResource(R.string.hc_status_not_installed)
            } else null,
            onClick = { requestEnable() },
            customLeadingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = stringResource(R.string.hc_toggle_label)
                )
            },
            trailingContent = {
                if (!healthConnectAvailable) {
                    Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.hc_status_not_installed))
                } else {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) requestEnable()
                            else coroutineScope.launch { healthConnectViewModel.setEnabled(false) }
                        }
                    )
                }
            }
        )

        if (enabled) {
            var showIntervalDialog by remember { mutableStateOf(false) }

            SettingsCardItem(
                label = stringResource(R.string.hc_sync_interval_label),
                supportingText = interval.getDisplayName(context),
                onClick = { showIntervalDialog = true },
                customLeadingContent = { Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.hc_sync_interval_label)) },
                trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
            )

            SettingsCardItem(
                label = stringResource(R.string.hc_status_label),
                supportingText = listOfNotNull(statusText, nextSyncText).joinToString("\n"),
                onClick = { },
                customLeadingContent = {
                    if (hasCurrentError) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = stringResource(R.string.hc_status_label), tint = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.hc_status_label))
                    }
                }
            )

            if (showIntervalDialog) {
                val intervalValues = remember { HealthConnectSyncInterval.entries.toList() }
                SelectionDialogEnum(
                    title = stringResource(R.string.hc_dialog_title_select_interval),
                    options = intervalValues,
                    selectedOption = interval,
                    onOptionSelected = { selected ->
                        coroutineScope.launch { healthConnectViewModel.setInterval(selected) }
                    },
                    optionToDisplayName = { it.getDisplayName(context) },
                    onDismissRequest = { showIntervalDialog = false }
                )
            }
        }
    }
}
