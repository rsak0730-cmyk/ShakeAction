package com.yourname.shakeapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.abs

class ShakeDetector(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val cameraId: String,
    private val usesRawAccelerometer: Boolean = false
) : SensorEventListener {

    private var isFlashlightOn = false
    private var isPocketCovered = false

    private var chopCount = 0
    private var lastChopTime: Long = 0
    private var lastToggleTime: Long = 0

    private val gravity = FloatArray(3)

    // Motorola Chop-Chop exact feel tuning (~24 m/s² lateral linear force)
    private val CHOP_ACCEL_THRESHOLD = 24.0f
    private val MIN_TIME_BETWEEN_CHOPS_MS = 140L
    private val MAX_TIME_BETWEEN_CHOPS_MS = 550L
    private val COOLDOWN_AFTER_TOGGLE_MS = 900L

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                isFlashlightOn = enabled
            }
        }
    }

    init {
        cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
    }

    fun cleanup() {
        cameraManager.unregisterTorchCallback(torchCallback)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val maxRange = event.sensor.maximumRange
            isPocketCovered = event.values[0] < maxRange && event.values[0] < 3.0f
            return
        }

        if (isPocketCovered) {
            chopCount = 0
            return
        }

        val lx: Float
        val ly: Float
        val lz: Float

        if (usesRawAccelerometer) {
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            lx = event.values[0] - gravity[0]
            ly = event.values[1] - gravity[1]
            lz = event.values[2] - gravity[2]
        } else {
            lx = event.values[0]
            ly = event.values[1]
            lz = event.values[2]
        }

        val now = System.currentTimeMillis()
        if (now - lastToggleTime < COOLDOWN_AFTER_TOGGLE_MS) return

        val absX = abs(lx)
        val absY = abs(ly)
        val absZ = abs(lz)

        // Lateral wrist chop (X-axis dominant), ignoring vertical steps and flat drops
        val isLateralChop = absX > CHOP_ACCEL_THRESHOLD && absX > absZ * 1.2f && absX > absY * 0.9f

        if (isLateralChop) {
            if (now - lastChopTime > MAX_TIME_BETWEEN_CHOPS_MS) {
                chopCount = 1
                lastChopTime = now
                return
            }

            if (now - lastChopTime >= MIN_TIME_BETWEEN_CHOPS_MS) {
                chopCount++
                lastChopTime = now

                if (chopCount >= 2) {
                    toggleFlashlight()
                    lastToggleTime = now
                    chopCount = 0
                }
            }
        }
    }

    private fun toggleFlashlight() {
        val targetState = !isFlashlightOn
        try {
            cameraManager.setTorchMode(cameraId, targetState)
            isFlashlightOn = targetState
            triggerMotorolaHaptic()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerMotorolaHaptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 35, 50, 35)
            val amplitudes = intArrayOf(0, 220, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 35, 50, 35), -1)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
