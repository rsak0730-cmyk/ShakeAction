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
    private var chopCount = 0
    private var lastChopTime: Long = 0
    private var lastToggleTime: Long = 0

    // --- MOTOROLA CHOP TUNING ---
    // 2.5G requires a firm, deliberate chop. 
    private val CHOP_THRESHOLD_G = 2.5F 
    // 200ms ignores the "rebound" when you pull your hand back up
    private val MIN_TIME_BETWEEN_CHOPS_MS = 200 
    // You have 0.8 seconds to complete the second chop
    private val MAX_TIME_BETWEEN_CHOPS_MS = 800 

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        // Calculate total physical force
        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // 1. Prevent the light from rapidly flickering on/off
        if (now - lastToggleTime < 1000) return

        // 2. Detect a hard spike in force (The Chop)
        if (gForce > CHOP_THRESHOLD_G) {
            
            // If you took too long since the last chop, reset the count to 1
            if (now - lastChopTime > MAX_TIME_BETWEEN_CHOPS_MS) {
                chopCount = 1
                lastChopTime = now
                return
            }

            // If it is a distinct second chop (not just your wrist bouncing back)
            if (now - lastChopTime > MIN_TIME_BETWEEN_CHOPS_MS) {
                chopCount++
                lastChopTime = now

                // 3. Trigger flashlight on exactly 2 chops
                if (chopCount == 2) {
                    toggleFlashlight()
                    lastToggleTime = now
                    chopCount = 0 // Reset for the next time
                }
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
