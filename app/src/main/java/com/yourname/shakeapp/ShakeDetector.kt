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
    
    // Variables for Double-Chop Detection
    private var chopCount = 0
    private var lastChopTime: Long = 0
    private var isRecovering = false 
    
    // Tuning the motion sensitivity
    private val CHOP_THRESHOLD = 3.0F // The high G-force required for a deliberate chop
    private val RECOVERY_THRESHOLD = 1.5F // G-force must drop below this to separate chop 1 from chop 2
    private val MAX_TIME_BETWEEN_CHOPS = 600 // You have 600 milliseconds to perform the second chop
    private val COOLDOWN_AFTER_TOGGLE = 1000 // Prevents accidental double-toggles

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        // 1. Reset the sequence if you took too long to do the second chop
        if (chopCount > 0 && (now - lastChopTime) > MAX_TIME_BETWEEN_CHOPS) {
            chopCount = 0
            isRecovering = false
        }

        // 2. Ignore all movement while the flashlight is in its cooldown phase
        if (now - lastChopTime < COOLDOWN_AFTER_TOGGLE && chopCount == 0) {
            return
        }

        // 3. Detect the Chop
        if (!isRecovering && gForce > CHOP_THRESHOLD) {
            chopCount++
            lastChopTime = now
            isRecovering = true // Forces the phone to wait for your hand to stop before counting chop 2

            if (chopCount == 2) {
                toggleFlashlight()
                chopCount = 0 // Reset the counter
                isRecovering = false
            }
        } 
        // 4. Detect the "Stop" between chops
        else if (isRecovering && gForce < RECOVERY_THRESHOLD) {
            // Your hand has slowed down enough; the system is now ready for chop 2
            isRecovering = false
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
