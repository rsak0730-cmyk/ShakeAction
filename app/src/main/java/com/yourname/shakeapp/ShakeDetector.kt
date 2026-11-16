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

    // --- ANTI-ACCIDENT TUNING ---
    // 4.5G is extreme force. It will completely ignore casual shaking or walking.
    private val CHOP_THRESHOLD_G = 4.5F 
    
    // 150ms minimum ignores the natural rebound of your wrist
    private val MIN_TIME_BETWEEN_CHOPS_MS = 150 
    
    // 450ms maximum forces a strict, rapid "chop-chop" rhythm. 
    private val MAX_TIME_BETWEEN_CHOPS_MS = 450 

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        // Calculate total physical force
        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // 1-second cooldown after turning on/off to prevent immediate flickering
        if (now - lastToggleTime < 1000) return

        if (gForce > CHOP_THRESHOLD_G) {
            
            // If you took too long between chops, reset the sequence to 1.
            // This prevents a bump in your pocket now from combining with a bump later.
            if (now - lastChopTime > MAX_TIME_BETWEEN_CHOPS_MS) {
                chopCount = 1
                lastChopTime = now
                return
            }

            // Register the second fast chop
            if (now - lastChopTime > MIN_TIME_BETWEEN_CHOPS_MS) {
                chopCount++
                lastChopTime = now

                if (chopCount == 2) {
                    toggleFlashlight()
                    lastToggleTime = now
                    chopCount = 0 
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
