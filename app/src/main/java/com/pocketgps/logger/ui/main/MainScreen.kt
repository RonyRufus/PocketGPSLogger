package com.pocketgps.logger.ui.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgps.logger.engine.ServiceBus
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
    val status by viewModel.serviceStatus.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val count by viewModel.savedCount.collectAsStateWithLifecycle()
    val saveOp by viewModel.saveOp.collectAsStateWithLifecycle()

    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var hasNotification by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    var gpsReady by remember { mutableStateOf(viewModel.isGpsReady()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        gpsReady = viewModel.isGpsReady()
    }

    LifecycleResumeEffect(Unit) {
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        gpsReady = viewModel.isGpsReady()
        onPauseOrDispose { }
    }

    val context = LocalContext.current
    val running = status.isRunning
    val missingPermissions = buildList {
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

        StatusBanner(status)

        if (missingPermissions.isNotEmpty()) {
            SectionCard(title = "Permissions needed") {
                Text(
                    "Location permission is needed to save spots; the notification keeps tap " +
                        "logging alive while the phone is locked.",
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
                    "Saving a spot needs device location to be switched on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text("Open location settings") }
            }
        }

        Button(
            onClick = {
                when {
                    running -> viewModel.stopTapLogging()
                    missingPermissions.isNotEmpty() ->
                        permissionLauncher.launch(missingPermissions.toTypedArray())
                    !gpsReady -> runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }

                    else -> viewModel.startTapLogging()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                imageVector = if (running) AppIcons.Stop else AppIcons.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = if (running) "Stop tap logging" else "Start tap logging",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        OutlinedButton(
            onClick = viewModel::saveSpotNow,
            enabled = hasLocation && gpsReady && saveOp != MainViewModel.SaveOp.Working,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (saveOp == MainViewModel.SaveOp.Working) {
                CircularProgressIndicator(
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
                Text("Save spot now")
            }
        }

        when (val op = saveOp) {
            is MainViewModel.SaveOp.Done -> SectionCard(title = "Spot saved") {
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
                Row {
                    TextButton(onClick = viewModel::clearSaveOp) { Text("Dismiss") }
                    TextButton(onClick = {
                        viewModel.undoLastSave()
                        viewModel.clearSaveOp()
                    }) { Text("Undo") }
                }
            }

            is MainViewModel.SaveOp.Failed -> SectionCard(title = "Could not save") {
                Text(
                    op.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = viewModel::clearSaveOp) { Text("Dismiss") }
            }

            else -> Unit
        }

        SectionCard(title = "Status") {
            LabeledValue("Tap logging", if (running) "Active" else "Off")
            LabeledValue(
                "Sensors",
                when {
                    !running -> "Idle"
                    status.motionGated -> "Parked (battery saver)"
                    else -> "Listening for taps"
                },
            )
            LabeledValue("Raw spikes this session", status.tapsDetected.toString())
            LabeledValue("Saves this session", status.savesThisSession.toString())
            LabeledValue("Location permission", if (hasLocation) "Granted" else "Missing")
            LabeledValue("GPS enabled", if (gpsReady) "Yes" else "No")
            LabeledValue("Saved records", count.toString())
            TextButton(onClick = onOpenSetup) { Text("Open setup & tap test") }
        }

        SectionCard(title = "Last saved location") {
            val record = latest
            if (record == null) {
                Text(
                    "Nothing saved yet. Start tap logging and double-tap the phone in your " +
                        "pocket — or press “Save spot now”.",
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
                            Formatters.timestamp(record.triggerTimestamp) + "  •  " +
                                record.triggerPhrase,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LabeledValue("Accuracy", Formatters.accuracy(record.accuracyMeters))
                LabeledValue(
                    "Source",
                    record.locationSource.replaceFirstChar { it.uppercase() },
                )
                record.locationAgeSeconds?.let { LabeledValue("Fix age", Formatters.age(it)) }
                record.notes?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(
                        onClick = {
                            Formatters.openInMap(
                                context, record.latitude, record.longitude, "Saved spot",
                            )
                        },
                    ) { Text("Open in map") }
                    TextButton(onClick = { viewModel.undoLastSave() }) { Text("Undo last") }
                }
            }
        }

        if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextButton(
                onClick = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                },
            ) { Text("Allow notifications for the tap-logging indicator") }
        }
    }
}

@Composable
private fun StatusBanner(status: ServiceBus.Status) {
    val text = when (status.state) {
        ServiceBus.State.OFF -> "Tap logging is off"
        ServiceBus.State.STARTING -> "Starting tap logging…"
        ServiceBus.State.ARMED -> if (status.motionGated) {
            "Parked — move the phone to re-arm tap detection"
        } else {
            "Double-tap your pocket to save a spot"
        }

        ServiceBus.State.CAPTURING -> "Tap detected — getting location…"
        ServiceBus.State.SAVING -> "Saving location…"
        ServiceBus.State.ERROR -> status.errorMessage ?: "Tap logging error"
    }
    SectionCard(title = "Tap logging") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = status.isRunning && status.state != ServiceBus.State.ERROR)
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (status.state == ServiceBus.State.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val message = status.message
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
