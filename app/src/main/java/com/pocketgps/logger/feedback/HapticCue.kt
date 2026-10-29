package com.pocketgps.logger.feedback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

/**
 * Short haptic acknowledgements for save success / failure.
 *
 * On a bike or with earphones on, a spoken cue is easy to miss — a vibration pulse in the
 * pocket is not. One short pulse means the spot was committed to storage; a double pulse
 * means the save failed (no fix, DB error). Respects the user's haptic-feedback setting; the
 * caller checks it.
 */
class HapticCue(private val context: Context) {

    fun success() = vibrate(SUCCESS_PATTERN)

    fun failure() = vibrate(FAILURE_PATTERN)

    private fun vibrate(pattern: LongArray) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val vibrator: Vibrator? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager =
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    companion object {
        /** One crisp pulse: committed. */
        private val SUCCESS_PATTERN = longArrayOf(0, 45)

        /** Double pulse: something went wrong. */
        private val FAILURE_PATTERN = longArrayOf(0, 45, 90, 45)
    }
}
