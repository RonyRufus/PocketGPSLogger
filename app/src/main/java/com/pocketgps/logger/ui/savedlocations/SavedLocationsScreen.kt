package com.pocketgps.logger.ui.savedlocations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.ui.common.LabeledValue
import com.pocketgps.logger.ui.common.SectionCard
import com.pocketgps.logger.ui.format.Formatters
import com.pocketgps.logger.ui.icons.AppIcons

@Composable
fun SavedLocationsScreen(viewModel: SavedLocationsViewModel = viewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val exportOp by viewModel.exportOp.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    // SAF create-document launchers keep the app free of storage permissions entirely.
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let { viewModel.export(it, gpx = true) } }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.export(it, gpx = false) } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all locations?") },
            text = { Text("This removes every saved spot from this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export all locations") },
            text = {
                Text(
                    "GPX opens in OsmAnd, Organic Maps, Google Earth and Garmin tools. " +
                        "CSV opens in any spreadsheet. Neither format sends data anywhere — " +
                        "the file is written where you choose.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    gpxLauncher.launch("pocket-gps-spots.gpx")
                }) { Text("GPX") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    csvLauncher.launch("pocket-gps-spots.csv")
                }) { Text("CSV") }
            },
        )
    }

    exportOp.let { op ->
        when (op) {
            is SavedLocationsViewModel.ExportOp.Done,
            is SavedLocationsViewModel.ExportOp.Failed,
            -> {
                val message = (op as? SavedLocationsViewModel.ExportOp.Done)?.message
                    ?: (op as SavedLocationsViewModel.ExportOp.Failed).message
                val isError = op is SavedLocationsViewModel.ExportOp.Failed
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearExportOp() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Saved Locations (${records.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row {
                if (records.isNotEmpty()) {
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(AppIcons.FileDownload, contentDescription = "Export to GPX or CSV")
                    }
                    TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                }
            }
        }

        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    AppIcons.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "No locations saved yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Start hands-free mode and say your trigger phrase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionCard(title = "Summary") {
                        LabeledValue("Total spots", stats.total.toString())
                        LabeledValue("Saved today", stats.today.toString())
                        LabeledValue("Last 7 days", stats.last7Days.toString())
                    }
                }
                items(records, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        onOpenMap = {
                            Formatters.openInMap(
                                context = context,
                                lat = record.latitude,
                                lon = record.longitude,
                                label = "Saved spot",
                            )
                        },
                        onCopy = {
                            Formatters.copyCoordinates(context, record.latitude, record.longitude)
                        },
                        onShare = {
                            Formatters.shareSpot(
                                context, record.latitude, record.longitude, record.triggerTimestamp,
                            )
                        },
                        onDelete = { viewModel.delete(record.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(
    record: LocationRecord,
    onOpenMap: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenMap),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Formatters.coordinates(record.latitude, record.longitude),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        Formatters.timestamp(record.triggerTimestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(6.dp))
            Text(
                buildString {
                    append("Accuracy: ${Formatters.accuracy(record.accuracyMeters)}")
                    append("  •  ${record.locationSource.replaceFirstChar { it.uppercase() }}")
                    record.locationAgeSeconds?.let { append("  •  ${Formatters.age(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record.notes?.let {
                Spacer(Modifier.size(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onCopy) {
                    Icon(AppIcons.ContentCopy, contentDescription = "Copy coordinates")
                }
                IconButton(onClick = onShare) {
                    Icon(AppIcons.Share, contentDescription = "Share spot")
                }
                IconButton(onClick = onOpenMap) {
                    Icon(AppIcons.Map, contentDescription = "Open in map")
                }
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, contentDescription = "Delete record")
                }
            }
        }
    }
}
