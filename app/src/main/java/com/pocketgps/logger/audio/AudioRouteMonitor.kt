package com.pocketgps.logger.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Reports the **actual** current audio routes.
 *
 * Connecting an earphone does not guarantee that the microphone input comes from it: on most
 * phones the built-in mic remains the input unless a headset with a mic is routed by the system.
 * Rather than assume, we query [AudioManager.getDevices] and surface the real route.
 */
class AudioRouteMonitor(context: Context) {

    data class Route(
        val headsetOutputConnected: Boolean,
        val inputSummary: String?,
        val outputSummary: String?,
    )

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun currentRoute(): Route {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Route(false, null, null)
        }
        val inputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrDefault(emptyList())
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        val headsetOutput = outputs.any { it.isHeadsetLike() }
        val inputSummary = inputs
            .mapNotNull { it.describeInput() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        val outputSummary = outputs
            .filter { it.isHeadsetLike() }
            .mapNotNull { it.describeOutput() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        return Route(headsetOutput, inputSummary, outputSummary)
    }

    private fun AudioDeviceInfo.isHeadsetLike(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> type == 26 // TYPE_BLE_HEADSET (API 31+), guarded numerically
    }

    private fun AudioDeviceInfo.describeInput(): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset mic"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset mic"
        else -> null
    }

    private fun AudioDeviceInfo.describeOutput(): String? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        else -> null
    }
}
