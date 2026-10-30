package com.pocketgps.logger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgps.logger.sensor.TapSensitivity
import com.pocketgps.logger.settings.AppSettings
import com.pocketgps.logger.settings.CachedLocationPolicy
import com.pocketgps.logger.ui.common.LabeledValue
import com.pocketgps.logger.ui.common.SectionCard

private const val STEP_TIMEOUT = 5
private const val STEP_ACCURACY = 25f

@Composable
fun SettingsScreen(
    onOpenSetup: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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

        // ---------------------------------------------------------------- tap input
        SectionCard(title = "Tap detection") {
            Text(
                "How hard a tap must be to register. GENTLE catches light taps through thick " +
                    "fabric; FIRM nearly eliminates false triggers from walking or riding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TapSensitivity.values().forEach { sensitivity ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.tapSensitivity == sensitivity,
                        onClick = { viewModel.setTapSensitivity(sensitivity) },
                    )
                    Text(
                        text = sensitivity.name.lowercase()
                            .replaceFirstChar { it.uppercase() } + when (sensitivity) {
                            TapSensitivity.GENTLE -> "  (most sensitive)"
                            TapSensitivity.NORMAL -> "  (recommended)"
                            TapSensitivity.FIRM -> "  (fewest false triggers)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ToggleRow(
                title = "Listen only while moving",
                subtitle = "Parks sensors, GPS pre-warm and wake lock after 10 minutes " +
                    "stationary; re-arms automatically from the hardware motion sensor.",
                checked = settings.onlyWhileMoving,
                onChange = viewModel::setOnlyWhileMoving,
            )
            ToggleRow(
                title = "Haptic feedback",
                subtitle = "One pulse = saved, double pulse = failed. Works locked and pocketed.",
                checked = settings.hapticFeedback,
                onChange = viewModel::setHapticFeedback,
            )
        }

        // ---------------------------------------------------------------- GPS budget
        SectionCard(title = "Location") {
            ToggleRow(
                title = "GPS pre-warm",
                subtitle = "Keeps a cheap low-power fix warm while tap logging is active, so a " +
                    "tap saves in about a second instead of a cold search.",
                checked = settings.gpsPreWarm,
                onChange = viewModel::setGpsPreWarm,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            StepperRow(
                title = "GPS timeout",
                value = "${settings.gpsTimeoutSeconds} s",
                subtitle = "How long a tap waits for a fresh fix before falling back.",
                onDecrement = {
                    viewModel.setGpsTimeout(settings.gpsTimeoutSeconds - STEP_TIMEOUT)
                },
                onIncrement = {
                    viewModel.setGpsTimeout(settings.gpsTimeoutSeconds + STEP_TIMEOUT)
                },
                canDecrement = settings.gpsTimeoutSeconds > AppSettings.MIN_GPS_TIMEOUT_SECONDS,
                canIncrement = settings.gpsTimeoutSeconds < AppSettings.MAX_GPS_TIMEOUT_SECONDS,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Cached fix policy", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            CachedLocationPolicy.values().forEach { policy ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = settings.cachedLocationPolicy == policy,
                        onClick = { viewModel.setCachedPolicy(policy) },
                    )
                    Text(
                        text = when (policy) {
                            CachedLocationPolicy.PREFER_RECENT ->
                                "Prefer recent (fastest, uses warm fixes)"
                            CachedLocationPolicy.FRESH_ONLY ->
                                "Fresh only (most accurate, slowest)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            StepperRow(
                title = "Max acceptable accuracy",
                value = "${settings.maxAccuracyMeters.toInt()} m",
                subtitle = "Fixes worse than this get a warning attached (and are only used " +
                    "when nothing better exists).",
                onDecrement = {
                    viewModel.setMaxAccuracyMeters(settings.maxAccuracyMeters - STEP_ACCURACY)
                },
                onIncrement = {
                    viewModel.setMaxAccuracyMeters(settings.maxAccuracyMeters + STEP_ACCURACY)
                },
                canDecrement = settings.maxAccuracyMeters > STEP_ACCURACY,
                canIncrement = true,
            )
        }

        SectionCard(title = "Diagnostics") {
            LabeledValue("GPS available", if (viewModel.isGpsReady()) "Yes" else "No")
            LabeledValue(
                "Location permission",
                if (viewModel.hasLocationPermission()) "Granted" else "Missing",
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(4.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: String,
    subtitle: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDecrement, enabled = canDecrement) { Text("−") }
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            OutlinedButton(onClick = onIncrement, enabled = canIncrement) { Text("+") }
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
