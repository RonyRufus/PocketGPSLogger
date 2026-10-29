package com.pocketgps.logger.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.pocketgps.logger.audio.TriggerMatcher
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.ui.common.SectionCard
import java.util.Locale

@Composable
fun SetupScreen(
    onDone: () -> Unit,
    viewModel: SetupViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val modelOp by viewModel.modelOp.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val sessionActive = engineState.state != com.pocketgps.logger.engine.ListenerState.DISABLED &&
        engineState.state != com.pocketgps.logger.engine.ListenerState.ERROR

    var hasMic by remember { mutableStateOf(viewModel.hasMicrophonePermission()) }
    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var hasNotification by remember { mutableStateOf(viewModel.hasNotificationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        hasMic = viewModel.hasMicrophonePermission()
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importModel) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshModel()
        hasMic = viewModel.hasMicrophonePermission()
        hasLocation = viewModel.hasLocationPermission()
        hasNotification = viewModel.hasNotificationPermission()
        onPauseOrDispose { }
    }

    val modelReady = modelStatus is ModelManager.Status.Ready
    val readyToContinue = modelReady && hasMic && hasLocation

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
            "The app listens for a phrase you choose and saves your GPS position when it hears it — " +
                "even with the screen off and the phone in your pocket. Recognition and saving are offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------------------------------------------- model
        SectionCard(title = "1. Offline speech model") {
            Text(
                "The Vosk English model is not bundled in the APK. Install it once; it stays in " +
                    "app-private storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (val status = modelStatus) {
                is ModelManager.Status.Ready -> StatusLine(
                    "Model installed (${formatBytes(storageBytes)})", true,
                )

                is ModelManager.Status.Missing -> StatusLine("Model not installed", false)
                is ModelManager.Status.Corrupt -> StatusLine(status.reason, false)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*"),
                        )
                    },
                    enabled = !sessionActive,
                ) { Text("Import .zip") }
                Button(onClick = viewModel::downloadModel, enabled = !sessionActive) {
                    Text("Download model")
                }
            }
            if (sessionActive) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Stop hands-free listening before changing the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when (val op = modelOp) {
                is SetupViewModel.ModelOp.Working -> {
                    Spacer(Modifier.height(8.dp))
                    Text(op.message, style = MaterialTheme.typography.bodySmall)
                    val progress = op.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is SetupViewModel.ModelOp.Done -> {
                    Spacer(Modifier.height(4.dp))
                    Text(op.message, color = MaterialTheme.colorScheme.primary)
                }

                is SetupViewModel.ModelOp.Failed -> {
                    Spacer(Modifier.height(4.dp))
                    Text(op.message, color = MaterialTheme.colorScheme.error)
                }

                SetupViewModel.ModelOp.Idle -> Unit
            }
        }

        // ---------------------------------------------------------------- permissions
        SectionCard(title = "2. Permissions") {
            Text(
                "Microphone is needed for recognition. Precise location is needed to save an " +
                    "accurate spot. Notifications show the listening indicator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PermissionRow("Microphone", hasMic) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
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
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    permissionLauncher.launch(
                        buildList {
                            if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
                            if (!hasLocation) {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray(),
                    )
                },
                enabled = !(hasMic && hasLocation && hasNotification),
            ) { Text("Grant remaining permissions") }
        }

        // ---------------------------------------------------------------- trigger phrase
        TriggerPhraseEditor(
            current = settings.triggerPhrase,
            onSave = viewModel::setTriggerPhrase,
        )

        // ---------------------------------------------------------------- test
        SectionCard(title = "3. Test your phrase") {
            Text(
                "Put the phone where you will use it, then say “${settings.triggerPhrase}”. " +
                    "The test listens for 8 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.runPhraseTest(settings.triggerPhrase) },
                enabled = modelReady && hasMic && !sessionActive &&
                    testState !is SetupViewModel.TestState.Listening,
            ) {
                Text(
                    if (testState is SetupViewModel.TestState.Listening) "Listening…"
                    else "Test phrase",
                )
            }
            if (sessionActive) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "The microphone test is unavailable while hands-free mode is listening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val state = testState) {
                SetupViewModel.TestState.Idle -> Unit
                SetupViewModel.TestState.Listening -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is SetupViewModel.TestState.Partial -> {
                    Spacer(Modifier.height(8.dp))
                    Text("Heard: “${state.text}”", style = MaterialTheme.typography.bodyMedium)
                }

                is SetupViewModel.TestState.Finished -> {
                    Spacer(Modifier.height(8.dp))
                    StatusLine(state.message, state.matched)
                }
            }
        }

        Button(
            onClick = onDone,
            enabled = readyToContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Continue to hands-free mode")
        }
        if (!readyToContinue) {
            Text(
                "Install the model and grant microphone + location to continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TriggerPhraseEditor(current: String, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    val valid = TriggerMatcher.isValidTrigger(text)
    SectionCard(title = "Trigger phrase") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = !valid,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trigger phrase") },
            supportingText = {
                Text(
                    if (valid) "The complete phrase must be spoken"
                    else "Use at least two words",
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(text) },
            enabled = valid && text.trim().lowercase() != current,
        ) { Text("Save phrase") }
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
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
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
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
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

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
