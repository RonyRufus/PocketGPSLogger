package com.pocketgps.logger.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.pocketgps.logger.sensor.TapSensitivity
import com.pocketgps.logger.ui.common.SectionCard
import com.pocketgps.logger.ui.icons.AppIcons

@Composable
fun SetupScreen(
    onDone: () -> Unit,
    viewModel: SetupViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tapTest by viewModel.tapTest.collectAsStateWithLifecycle()

    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var hasNotification by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    var ignoringBattery by remember { mutableStateOf(viewModel.isIgnoringBatteryOptimizations()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
    }

    LifecycleResumeEffect(Unit) {
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        ignoringBattery = viewModel.isIgnoringBatteryOptimizations()
        onPauseOrDispose { }
    }

    // The tap test runs its own short-lived sensor listener; stop it when leaving the screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopTapTest() }
    }

    val context = LocalContext.current
    val ready = hasLocation

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Set up Pocket GPS Logger",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "v2 saves a GPS spot when you double-tap the phone through your pocket — even " +
                "locked, screen-off. No microphone, no voice model, almost no battery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------------------------------------------- permissions
        SectionCard(title = "1. Permissions") {
            Text(
                "Location saves the spot. Notifications keep the tap listener alive while the " +
                    "phone is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow("Location", hasLocation) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
            PermissionRow("Notifications", hasNotification) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }

        // ---------------------------------------------------------------- battery
        SectionCard(title = "2. Battery optimisation") {
            Text(
                "Some phones kill background services. Exempting the app from battery " +
                    "optimisation keeps tap detection reliable while costing almost nothing — " +
                    "the app already parks its sensors when you are stationary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(
                if (ignoringBattery) "Already exempt" else "Not exempt (may be killed)",
                ignoringBattery,
            )
            if (!ignoringBattery) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:" + context.packageName)),
                            )
                        }
                    },
                ) { Text("Request exemption") }
            }
        }

        // ---------------------------------------------------------------- tap test
        SectionCard(title = "3. Test tap detection") {
            Text(
                "Run the test, put the phone where you normally carry it, then double-tap it " +
                    "through the fabric like you would in real use. Counters below react live. " +
                    "If taps are not detected, lower the sensitivity in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sensitivity: ${settings.tapSensitivity.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (tapTest.running) viewModel.stopTapTest() else viewModel.startTapTest()
                    },
                    enabled = viewModel.hasAccelerometer(),
                ) {
                    Text(if (tapTest.running) "Stop test" else "Start test")
                }
                OutlinedButton(
                    onClick = viewModel::resetTapTest,
                    enabled = tapTest.running,
                ) { Text("Reset") }
            }
            if (!viewModel.hasAccelerometer()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This device has no accelerometer — tap detection is unavailable.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Raw spikes: ${tapTest.spikes}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Double taps detected: ${tapTest.doubleTaps}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (tapTest.doubleTaps > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        Button(
            onClick = onDone,
            enabled = ready,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Continue")
        }
        if (!ready) {
            Text(
                "Grant location to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!granted) {
            OutlinedButton(onClick = onRequest) { Text("Grant") }
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) AppIcons.CheckCircle else AppIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
    }
}
