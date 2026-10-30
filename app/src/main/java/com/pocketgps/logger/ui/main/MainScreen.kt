package com.pocketgps.logger.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgps.logger.engine.ListenerState
import com.pocketgps.logger.engine.ListenerUiState
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.ui.common.LabeledValue
import com.pocketgps.logger.ui.common.SectionCard
import com.pocketgps.logger.ui.common.StatusDot
import com.pocketgps.logger.ui.format.Formatters
import com.pocketgps.logger.ui.icons.AppIcons

@Composable
fun MainScreen(
    onOpenSetup: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val count by viewModel.savedCount.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val manualSave by viewModel.manualSave.collectAsStateWithLifecycle()

    var hasMic by remember { mutableStateOf(viewModel.hasMicrophonePermission()) }
    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var hasNotification by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    var gpsReady by remember { mutableStateOf(viewModel.isGpsReady()) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasMic = viewModel.hasMicrophonePermission()
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        gpsReady = viewModel.isGpsReady()
        viewModel.refresh()
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        hasMic = viewModel.hasMicrophonePermission()
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        gpsReady = viewModel.isGpsReady()
        onPauseOrDispose { }
    }

    val modelReady = modelStatus is ModelManager.Status.Ready
    val context = androidx.compose.ui.platform.LocalContext.current
    val canStart = modelReady && hasMic && hasLocation && gpsReady

    val missingPermissions = buildList {
        if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
        if (!hasLocation) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pocket GPS Logger",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        StatusBanner(engineState)

        if (!modelReady) {
            SectionCard(title = "Speech model required") {
                Text(
                    "Install the offline Vosk model to start hands-free listening.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSetup) { Text("Open setup") }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            SectionCard(title = "Permissions needed") {
                Text(
                    "Microphone and precise location are required for hands-free saving.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                ) { Text("Grant permissions") }
            }
        }

        if (hasLocation && !gpsReady) {
            SectionCard(title = "Location services are off") {
                Text(
                    "Hands-free saving needs device location to be switched on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                ) { Text("Open location settings") }
            }
        }

        Button(
            onClick = {
                if (engineState.isListening) {
                    viewModel.stopListening()
                } else if (!canStart) {
                    when {
                        !modelReady -> onOpenSetup()
                        missingPermissions.isNotEmpty() ->
                            permissionLauncher.launch(missingPermissions.toTypedArray())
                        !gpsReady -> runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }

                        else -> Unit
                    }
                } else {
                    viewModel.startListening()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (engineState.isListening) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
        ) {
            Icon(
                imageVector = if (engineState.isListening) AppIcons.Stop else AppIcons.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = if (engineState.isListening) "Stop Listening" else "Start Hands-Free Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedButton(
            onClick = viewModel::saveSpotNow,
            enabled = hasLocation && gpsReady && manualSave != MainViewModel.ManualSaveOp.Working,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (manualSave == MainViewModel.ManualSaveOp.Working) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(12.dp))
                Text("Saving spot…")
            } else {
                Icon(
                    AppIcons.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text("Save spot now (no voice)")
            }
        }

        when (val op = manualSave) {
            is MainViewModel.ManualSaveOp.Done -> SectionCard(title = "Spot saved") {
                Text(
                    Formatters.coordinates(op.record.latitude, op.record.longitude),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    Formatters.timestamp(op.record.triggerTimestamp) +
                        "  •  " + Formatters.accuracy(op.record.accuracyMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::clearManualSaveOp) { Text("Dismiss") }
            }

            is MainViewModel.ManualSaveOp.Failed -> SectionCard(title = "Could not save") {
                Text(
                    op.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::clearManualSaveOp) { Text("Dismiss") }
            }

            else -> Unit
        }

        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "Hide device diagnostics" else "Device diagnostics")
        }

        if (showDiagnostics) {
            SectionCard(title = "Device diagnostics") {
                LabeledValue("Listening state", stateLabel(engineState.state))
                LabeledValue("Microphone permission", if (hasMic) "Granted" else "Missing")
                LabeledValue(
                    "Location permission",
                    if (hasLocation) "Granted" else "Missing",
                )
                LabeledValue("GPS enabled", if (gpsReady) "Yes" else "No")
                LabeledValue("Model", if (modelReady) "Installed" else "Missing")
                LabeledValue(
                    "Earphone",
                    if (engineState.headsetConnected) "Connected" else "Not detected",
                )
                engineState.inputRoute?.let { LabeledValue("Mic route", it) }
                LabeledValue("Saved records", count.toString())
            }
        }

        SectionCard(title = "Last saved record") {
            val record = latest
            if (record == null) {
                Text(
                    "No locations saved yet. Say \"${engineState.triggerPhrase}\" while listening.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            Formatters.coordinates(record.latitude, record.longitude),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            Formatters.timestamp(record.triggerTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                record.observation?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                LabeledValue("Accuracy", Formatters.accuracy(record.accuracyMeters))
                LabeledValue(
                    "Source",
                    record.locationSource.replaceFirstChar { it.uppercase() },
                )
                record.locationAgeSeconds?.let {
                    LabeledValue("Fix age", Formatters.age(it))
                }
                record.notes?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        Formatters.openInMap(
                            context, record.latitude, record.longitude,
                            "Saved spot",
                        )
                    },
                ) { Text("Open in map") }
            }
        }

        if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextButton(
                onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            ) { Text("Allow notifications for the listening indicator") }
        }
    }
}

@Composable
private fun StatusBanner(ui: ListenerUiState) {
    val text = when (ui.state) {
        ListenerState.DISABLED -> "Hands-free mode is off"
        ListenerState.INITIALIZING -> "Starting hands-free mode…"
        ListenerState.LISTENING -> "Listening for \"${ui.triggerPhrase}\""
        ListenerState.CAPTURING -> "Trigger recognised — getting location…"
        ListenerState.SAVING -> "Saving location…"
        ListenerState.RECOVERING -> ui.statusMessage ?: "Recovering audio…"
        ListenerState.ERROR -> ui.errorMessage ?: "Listening error"
    }
    SectionCard(title = "Hands-free mode") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = ui.isListening)
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (ui.state == ListenerState.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val message = ui.statusMessage
        if (message != null && ui.state != ListenerState.RECOVERING && ui.state != ListenerState.ERROR) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stateLabel(state: ListenerState): String = when (state) {
    ListenerState.DISABLED -> "Off"
    ListenerState.INITIALIZING -> "Starting"
    ListenerState.LISTENING -> "Listening"
    ListenerState.CAPTURING -> "Getting location"
    ListenerState.SAVING -> "Saving"
    ListenerState.RECOVERING -> "Recovering"
    ListenerState.ERROR -> "Error"
}
