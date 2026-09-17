package com.yourname.shakeapp

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import kotlin.math.sqrt

class ShakeDetector(
    private val cameraManager: CameraManager,
    private val cameraId: String
) : SensorEventListener {

    private var isFlashlightOn = false
    
    private var shakeCount = 0
    private var lastShakeTimestamp: Long = 0
    private var lastToggleTime: Long = 0

    // --- TUNING PARAMETERS ---
    // 2.0F is medium sensitivity. Lower it to 1.5F if it's too hard, raise to 2.5F if it triggers in your pocket.
    private val SHAKE_THRESHOLD_GRAVITY = 2.0F 
    // Minimum time between directional changes to count as a separate shake (filters out sensor noise)
    private val MIN_TIME_BETWEEN_SHAKES_MS = 150 
    // Maximum time allowed between shakes before the sequence resets
    private val MAX_TIME_BETWEEN_SHAKES_MS = 600 
    // How many hard direction changes trigger the light (2 = chop-chop)
    private val REQUIRED_SHAKES = 2 

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // 1. Ignore all movement for 1.5 seconds after the flashlight toggles to prevent flickering
        if (now - lastToggleTime < 1500) {
            return
        }

        // 2. Detect a significant movement
        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            
            // Ignore movements that are too close together (sensor noise)
            if (now - lastShakeTimestamp < MIN_TIME_BETWEEN_SHAKES_MS) {
                return
            }

            // If it has been too long since the last shake, reset the counter
            if (now - lastShakeTimestamp > MAX_TIME_BETWEEN_SHAKES_MS) {
                shakeCount = 0
            }

            // Register the valid shake
            lastShakeTimestamp = now
            shakeCount++

            // 3. Trigger the flashlight if we hit the required count
            if (shakeCount >= REQUIRED_SHAKES) {
                toggleFlashlight()
                lastToggleTime = now
                shakeCount = 0 // Reset so it doesn't trigger again immediately
            }
        }
    }

    private fun toggleFlashlight() {
        isFlashlightOn = !isFlashlightOn
        try {
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
