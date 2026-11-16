package com.yourname.shakeapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.abs

class ShakeDetector(
    private val context: Context
) : SensorEventListener {

    private var isPocketCovered = false

    // 1. Chop-Chop state
    private var chopCount = 0
    private var lastChopTime = 0L
    private var lastChopTriggerTime = 0L

    // 2. Twist-Twist state (Gyroscope Z-axis)
    private var twistCount = 0
    private var lastTwistTime = 0L
    private var lastTwistDirection = 0
    private var lastTwistTriggerTime = 0L

    // 3. Pick-Up state
    private var wasFlatOnTable = false
    private var flatSinceTime = 0L
    private var lastPickupTriggerTime = 0L

    // 4. Flip for DND state
    private var isFaceDownDndActive = false
    private var faceDownSinceTime = 0L

    // Gravity low-pass filter
    private val gravity = FloatArray(3)

    // Thresholds tuned to match Motorola Moto Actions
    private val CHOP_ACCEL_THRESHOLD = 23.5f
    private val TWIST_GYRO_THRESHOLD = 4.8f // rad/s rapid wrist rotation

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = event.sensor.maximumRange
                isPocketCovered = event.values[0] < maxRange && event.values[0] < 3.0f
                if (isPocketCovered) {
                    chopCount = 0
                    twistCount = 0
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // Separate gravity from linear acceleration
                val alpha = 0.8f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * ax
                gravity[1] = alpha * gravity[1] + (1 - alpha) * ay
                gravity[2] = alpha * gravity[2] + (1 - alpha) * az

                val lx = ax - gravity[0]
                val ly = ay - gravity[1]
                val lz = az - gravity[2]

                handleFlipForDnd(ax, ay, az, now)
                handlePickUpGesture(ax, ay, az, now)
                handleChopChopGesture(lx, ly, lz, now)
            }

            Sensor.TYPE_GYROSCOPE -> {
                handleTwistTwistGesture(event.values[0], event.values[1], event.values[2], now)
            }
        }
    }

    // --- 1. MOTOROLA CHOP-CHOP ---
    private fun handleChopChopGesture(lx: Float, ly: Float, lz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastChopTriggerTime < 900L) return

        val absX = abs(lx)
        val absY = abs(ly)
        val absZ = abs(lz)

        val isLateralChop = absX > CHOP_ACCEL_THRESHOLD && absX > absZ * 1.15f && absX > absY * 0.9f
        if (isLateralChop) {
            if (now - lastChopTime > 550L) {
                chopCount = 1
                lastChopTime = now
                return
            }
            if (now - lastChopTime >= 140L) {
                chopCount++
                lastChopTime = now
                if (chopCount >= 2) {
                    lastChopTriggerTime = now
                    chopCount = 0
                    GestureActionExecutor.execute(context, "chop")
                }
            }
        }
    }

    // --- 2. MOTOROLA TWIST-TWIST (QUICK CAPTURE) ---
    private fun handleTwistTwistGesture(gx: Float, gy: Float, gz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastTwistTriggerTime < 1100L) return

        // Wrist twist rotates primarily around phone's longitudinal Y-axis or Z-axis
        val dominantTwist = if (abs(gy) > abs(gz)) gy else gz
        val absTwist = abs(dominantTwist)

        if (absTwist > TWIST_GYRO_THRESHOLD && absTwist > abs(gx) * 1.3f) {
            val dir = if (dominantTwist > 0) 1 else -1

            if (now - lastTwistTime > 650L) {
                twistCount = 1
                lastTwistDirection = dir
                lastTwistTime = now
                return
            }

            // Second twist in opposite or rapid follow-up wrist snap
            if (now - lastTwistTime >= 130L && dir != lastTwistDirection) {
                twistCount++
                lastTwistDirection = dir
                lastTwistTime = now

                if (twistCount >= 2) {
                    lastTwistTriggerTime = now
                    twistCount = 0
                    lastTwistDirection = 0
                    GestureActionExecutor.execute(context, "twist")
                }
            }
        }
    }

    // --- 3. MOTOROLA PICK UP GESTURE ---
    private fun handlePickUpGesture(ax: Float, ay: Float, az: Float, now: Long) {
        if (isPocketCovered) return

        // Phone lying flat face-up on table
        val isFlatFaceUp = az > 8.8f && abs(ax) < 2.0f && abs(ay) < 2.0f
        if (isFlatFaceUp) {
            if (!wasFlatOnTable) {
                wasFlatOnTable = true
                flatSinceTime = now
            }
            return
        }

        // Phone lifted to upright viewing angle (~45° to 85°) after resting flat for at least 1.5s
        val isLiftedUpright = ay > 5.5f && az > 1.5f && az < 8.0f && abs(ax) < 3.5f
        if (wasFlatOnTable && isLiftedUpright) {
            val restedDuration = now - flatSinceTime
            wasFlatOnTable = false
            if (restedDuration >= 1500L && now - lastPickupTriggerTime > 2000L) {
                lastPickupTriggerTime = now
                GestureActionExecutor.execute(context, "pickup")
            }
        } else if (az < 1.0f) {
            wasFlatOnTable = false
        }
    }

    // --- 4. MOTOROLA FLIP FOR DO NOT DISTURB ---
    private fun handleFlipForDnd(ax: Float, ay: Float, az: Float, now: Long) {
        // Phone placed face-down on flat surface
        val isFaceDown = az < -8.8f && abs(ax) < 2.2f && abs(ay) < 2.2f

        if (isFaceDown) {
            if (faceDownSinceTime == 0L) {
                faceDownSinceTime = now
            } else if (!isFaceDownDndActive && now - faceDownSinceTime >= 1000L) {
                isFaceDownDndActive = true
                GestureActionExecutor.setFlipDndState(context, true)
            }
        } else {
            faceDownSinceTime = 0L
            if (isFaceDownDndActive && az > -3.0f) {
                isFaceDownDndActive = false
                GestureActionExecutor.setFlipDndState(context, false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
