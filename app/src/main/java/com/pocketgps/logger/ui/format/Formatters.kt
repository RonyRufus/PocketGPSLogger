package com.pocketgps.logger.ui.format

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared formatting helpers for coordinates and timestamps. */
object Formatters {

    private val dateTime = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
    private val dateOnly = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeOnly = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun timestamp(ms: Long): String = dateTime.format(Date(ms))

    fun date(ms: Long): String = dateOnly.format(Date(ms))

    fun time(ms: Long): String = timeOnly.format(Date(ms))

    /** Six decimal places ≈ 0.1 m, plenty for a saved spot. */
    fun coordinates(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.6f, %.6f", lat, lon)

    fun accuracy(meters: Float?): String =
        meters?.let { "±${it.toInt()} m" } ?: "accuracy unknown"

    fun age(seconds: Int?): String = when {
        seconds == null -> "age unknown"
        seconds < 60 -> "${seconds}s old"
        seconds < 3600 -> "${seconds / 60}m old"
        else -> "${seconds / 3600}h old"
    }

    /** Build and launch a geo: intent, falling back to a web map if no handler exists. */
    fun openInMap(context: Context, lat: Double, lon: Double, label: String) {
        val geoUri = Uri.Builder()
            .scheme("geo")
            .opaquePart("$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
            .build()
        val intent = Intent(Intent.ACTION_VIEW, geoUri)
        runCatching { context.startActivity(intent) }
            .onFailure {
                val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, web))
                }
            }
    }

    /** Copy "lat, lon" to the clipboard; returns true when the write succeeded. */
    fun copyCoordinates(context: Context, lat: Double, lon: Double): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return runCatching {
            clipboard.setPrimaryClip(
                ClipData(
                    ClipDescription("GPS coordinates", arrayOf("text/plain")),
                    ClipData.Item("$lat, $lon"),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Open the system share sheet with a readable description of one saved spot. */
    fun shareSpot(context: Context, lat: Double, lon: Double, timestamp: Long) {
        val text = buildString {
            append("Saved spot: ").append(coordinates(lat, lon))
            append(" (").append(timestamp(timestamp)).append(")")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "Saved GPS spot")
        }
        val chooser = Intent.createChooser(send, "Share spot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
