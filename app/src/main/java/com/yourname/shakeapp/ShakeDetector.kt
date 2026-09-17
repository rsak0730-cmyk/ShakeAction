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
    private var lastShakeTime: Long = 0
    private val SHAKE_THRESHOLD_GRAVITY = 2.7F 
    private val SHAKE_COOLDOWN = 1000 

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            val now = System.currentTimeMillis()
            if (lastShakeTime + SHAKE_COOLDOWN > now) {
                return
            }
            lastShakeTime = now
            toggleFlashlight()
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

