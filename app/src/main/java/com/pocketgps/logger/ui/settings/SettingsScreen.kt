package com.pocketgps.logger.ui.settings

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgps.logger.audio.TriggerMatcher
import com.pocketgps.logger.model.ModelManager
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.CachedLocationPolicy
import com.pocketgps.logger.settings.ConfirmationMode
import com.pocketgps.logger.ui.common.LabeledValue
import com.pocketgps.logger.ui.common.SectionCard
import java.util.Locale
import com.pocketgps.logger.ui.icons.AppIcons

@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val modelOp by viewModel.modelOp.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val sessionActive = engineState.state != com.pocketgps.logger.engine.ListenerState.DISABLED &&
        engineState.state != com.pocketgps.logger.engine.ListenerState.ERROR

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importModel)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        TriggerPhraseSection(
            current = settings.triggerPhrases.joinToString("\n"),
            onSave = viewModel::setTriggerPhrase,
        )

        SectionCard(title = "Voice observations") {
            SwitchRow(
                title = "Capture spoken notes",
                subtitle = "Say a trigger followed by a short observation. Turn off for the lowest CPU use and location-only saves.",
                checked = settings.voiceNotes,
                onCheckedChange = viewModel::setVoiceNotes,
            )
        }

        SectionCard(title = "Confirmation") {
            Text(
                "How the app acknowledges a save through the connected earphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            ConfirmationMode.entries.forEach { mode ->
                RadioRow(
                    label = when (mode) {
                        ConfirmationMode.SPEECH -> "Spoken (\"Location saved\")"
                        ConfirmationMode.TONE -> "Quiet tone"
                        ConfirmationMode.SILENT -> "Silent"
                    },
                    selected = settings.confirmationMode == mode,
                    onClick = { viewModel.setConfirmationMode(mode) },
                )
            }
        }

        SectionCard(title = "GPS") {
            StepperRow(
                label = "Fresh-fix timeout",
                value = "${settings.gpsTimeoutSeconds} s",
                onDecrease = { viewModel.setGpsTimeout(settings.gpsTimeoutSeconds - 5) },
                onIncrease = { viewModel.setGpsTimeout(settings.gpsTimeoutSeconds + 5) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Cached location policy",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            CachedLocationPolicy.entries.forEach { policy ->
                RadioRow(
                    label = when (policy) {
                        CachedLocationPolicy.PREFER_RECENT ->
                            "Use a recent cached fix when accurate enough"
                        CachedLocationPolicy.FRESH_ONLY ->
                            "Always request a fresh fix (fallback only after timeout)"
                    },
                    selected = settings.cachedLocationPolicy == policy,
                    onClick = { viewModel.setCachedPolicy(policy) },
                )
            }
            Spacer(Modifier.height(4.dp))
            StepperRow(
                label = "Cached fix max age",
                value = "${settings.cachedMaxAgeSeconds} s",
                onDecrease = {
                    viewModel.setCachedMaxAge((settings.cachedMaxAgeSeconds - 5).coerceAtLeast(5))
                },
                onIncrease = { viewModel.setCachedMaxAge(settings.cachedMaxAgeSeconds + 5) },
            )
            StepperRow(
                label = "Max acceptable accuracy",
                value = "${settings.maxAccuracyMeters.toInt()} m",
                onDecrease = { viewModel.setMaxAccuracy((settings.maxAccuracyMeters - 25f).coerceAtLeast(25f)) },
                onIncrease = { viewModel.setMaxAccuracy(settings.maxAccuracyMeters + 25f) },
            )
            StepperRow(
                label = "Duplicate-trigger cooldown",
                value = "${settings.cooldownSeconds} s",
                onDecrease = { viewModel.setCooldown(settings.cooldownSeconds - 1) },
                onIncrease = { viewModel.setCooldown(settings.cooldownSeconds + 1) },
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Fast saves (GPS pre-warm)",
                subtitle = "While listening, keep a low-power location feed alive so a " +
                    "trigger saves in about a second. Costs a little battery.",
                checked = settings.gpsPreWarm,
                onCheckedChange = viewModel::setGpsPreWarm,
            )
            SwitchRow(
                title = "Vibrate on save",
                subtitle = "One pulse when a spot is saved, two when it fails — useful when " +
                    "you can't hear the confirmation.",
                checked = settings.hapticFeedback,
                onCheckedChange = viewModel::setHapticFeedback,
            )
        }

        SectionCard(title = "Speech model") {
            val ready = modelStatus is ModelManager.Status.Ready
            LabeledValue("Status", if (ready) "Installed" else "Not installed")
            LabeledValue(
                "Storage",
                formatBytes(storageBytes),
            )
            if (modelStatus is ModelManager.Status.Corrupt) {
                Text(
                    (modelStatus as ModelManager.Status.Corrupt).reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                    Text("Download")
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
            if (ready) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::deleteModel, enabled = !sessionActive) {
                    Icon(AppIcons.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete model")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenSetup) { Text("Open setup & microphone test") }
        }

        when (val op = modelOp) {
            is SettingsViewModel.ModelOp.Working -> {
                SectionCard(title = op.message) {
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
            }

            is SettingsViewModel.ModelOp.Done -> InfoBanner(op.message)
            is SettingsViewModel.ModelOp.Failed -> InfoBanner(op.message, isError = true)
            SettingsViewModel.ModelOp.Idle -> Unit
        }

        SectionCard(title = "About") {
            Text(
                "Pocket GPS Logger saves a spot when you speak your trigger phrase. " +
                    "Recognition and storage are fully offline after the model is installed. " +
                    "Listening runs in a microphone foreground service with a visible notification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TriggerPhraseSection(current: String, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    val phrases = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val valid = phrases.isNotEmpty() &&
        phrases.all { TriggerMatcher.isValidTrigger(it) } &&
        phrases.map { it.lowercase() }.distinct().size == phrases.size

    SectionCard(title = "Trigger phrases") {
        Text(
            "Enter one phrase per line. Each must contain at least two words; matching uses whole words, not regex syntax.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            isError = !valid,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Trigger phrases") },
            supportingText = {
                if (!valid) {
                    Text("Each phrase needs at least two words; remove duplicates")
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(text) },
            enabled = valid && text.trim().lowercase() != current,
        ) { Text("Save phrases") }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDecrease) {
            Icon(AppIcons.Remove, contentDescription = "Decrease")
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp),
        )
        IconButton(onClick = onIncrease) {
            Icon(AppIcons.Add, contentDescription = "Increase")
        }
    }
}

@Composable
private fun InfoBanner(message: String, isError: Boolean = false) {
    SectionCard(title = if (isError) "Error" else "Done") {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
