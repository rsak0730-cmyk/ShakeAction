package com.yourname.shakeapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class ShakeDetector(
    private val context: Context
) : SensorEventListener {

    private var isPocketCovered = false
    private var hasHardwareGyro = false

    // Gravity filter state
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    // --- 1. CHOP-CHOP STATE (Peak & Opposite Sign Detection) ---
    private var chopState = 0 // 0 = idle, 1 = first chop peak confirmed
    private var firstChopSign = 0
    private var firstChopPeakTime = 0L
    private var lastChopTriggerTime = 0L
    private var prevLx = 0f
    private var risingX = false

    // --- 2. TWIST-TWIST STATE ---
    private var twistCount = 0
    private var lastTwistDir = 0
    private var lastTwistPeakTime = 0L
    private var lastTwistTriggerTime = 0L
    private var prevRollAngle = 0f

    // --- 3. PICK-UP STATE ---
    private var wasFlatOnTable = false
    private var flatSinceTime = 0L
    private var lastPickupTriggerTime = 0L

    // --- 4. FLIP FOR DND STATE ---
    private var isFaceDownDndActive = false
    private var faceDownSinceTime = 0L

    // --- TUNED MOTOROLA THRESHOLDS ---
    // 19.5 m/s² (~2.0G) peak inflection feels effortless like real Moto Chop-Chop
    private val CHOP_PEAK_THRESHOLD = 19.5f
    private val CHOP_MIN_GAP_MS = 110L
    private val CHOP_MAX_GAP_MS = 520L

    // 3.6 rad/s is optimal for Samsung A14 5G & mid-range gyros
    private val TWIST_GYRO_THRESHOLD = 3.6f
    private val TWIST_MIN_GAP_MS = 110L
    private val TWIST_MAX_GAP_MS = 600L

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = event.sensor.maximumRange
                isPocketCovered = event.values[0] < maxRange && event.values[0] < 3.0f
                if (isPocketCovered) {
                    resetChop()
                    resetTwist()
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                if (!gravityInitialized) {
                    gravity[0] = ax
                    gravity[1] = ay
                    gravity[2] = az
                    gravityInitialized = true
                } else {
                    val alpha = 0.82f
                    gravity[0] = alpha * gravity[0] + (1 - alpha) * ax
                    gravity[1] = alpha * gravity[1] + (1 - alpha) * ay
                    gravity[2] = alpha * gravity[2] + (1 - alpha) * az
                }

                val lx = ax - gravity[0]
                val ly = ay - gravity[1]
                val lz = az - gravity[2]

                handleFlipForDnd(ax, ay, az, now)
                handlePickUpGesture(ax, ay, az, now)
                handleMotorolaChopChop(lx, ly, lz, now)

                // Fallback Twist detection if device lacks hardware gyroscope
                if (!hasHardwareGyro) {
                    handleAccelRollTwist(ax, ay, az, now)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                hasHardwareGyro = true
                handleMotorolaTwistTwist(event.values[0], event.values[1], event.values[2], now)
            }
        }
    }

    // =========================================================================
    // 1. EXACT MOTOROLA CHOP-CHOP (Peak Inflection + Opposite Wrist Snap)
    // =========================================================================
    private fun handleMotorolaChopChop(lx: Float, ly: Float, lz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastChopTriggerTime < 850L) return

        val absX = abs(lx)
        val absPrevX = abs(prevLx)

        // Detect true local peak (acceleration was rising, now starts dropping)
        if (absX > absPrevX) {
            risingX = true
        } else if (risingX && absX < absPrevX) {
            risingX = false

            // Evaluate the peak that just occurred (prevLx)
            val isDominantLateral = absPrevX >= CHOP_PEAK_THRESHOLD &&
                    absPrevX > abs(lz) * 1.15f &&
                    absPrevX > abs(ly) * 0.85f

            if (isDominantLateral) {
                val peakSign = if (prevLx > 0) 1 else -1

                if (chopState == 0) {
                    chopState = 1
                    firstChopSign = peakSign
                    firstChopPeakTime = now
                } else if (chopState == 1) {
                    val dt = now - firstChopPeakTime
                    if (dt > CHOP_MAX_GAP_MS) {
                        // Too slow — treat this chop as the new first chop
                        firstChopSign = peakSign
                        firstChopPeakTime = now
                    } else if (dt >= CHOP_MIN_GAP_MS) {
                        // Motorola signature: either opposing snap (+X then -X) or sharp second chop
                        if (peakSign != firstChopSign || dt >= 180L) {
                            lastChopTriggerTime = now
                            resetChop()
                            GestureActionExecutor.execute(context, "chop")
                        }
                    }
                }
            }
        }

        // Timeout reset if second chop never came
        if (chopState == 1 && now - firstChopPeakTime > CHOP_MAX_GAP_MS) {
            resetChop()
        }

        prevLx = lx
    }

    private fun resetChop() {
        chopState = 0
        firstChopSign = 0
        firstChopPeakTime = 0L
        risingX = false
    }

    // =========================================================================
    // 2. EXACT MOTOROLA TWIST-TWIST (Quick Capture)
    // =========================================================================
    private fun handleMotorolaTwistTwist(gx: Float, gy: Float, gz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastTwistTriggerTime < 1000L) return

        // Wrist twist rotates around phone's longitudinal Y-axis (portrait)
        val absY = abs(gy)
        val absX = abs(gx)
        val absZ = abs(gz)

        if (absY >= TWIST_GYRO_THRESHOLD && absY > absX * 1.25f && absY > absZ * 1.1f) {
            val dir = if (gy > 0) 1 else -1

            if (twistCount == 0) {
                twistCount = 1
                lastTwistDir = dir
                lastTwistPeakTime = now
            } else {
                val dt = now - lastTwistPeakTime
                if (dt > TWIST_MAX_GAP_MS) {
                    twistCount = 1
                    lastTwistDir = dir
                    lastTwistPeakTime = now
                } else if (dt >= TWIST_MIN_GAP_MS && dir != lastTwistDir) {
                    twistCount++
                    lastTwistDir = dir
                    lastTwistPeakTime = now

                    // 2 opposing wrist twists (turn in -> snap back) = instant trigger
                    if (twistCount >= 2) {
                        lastTwistTriggerTime = now
                        resetTwist()
                        GestureActionExecutor.execute(context, "twist")
                    }
                }
            }
        }

        if (twistCount > 0 && now - lastTwistPeakTime > TWIST_MAX_GAP_MS) {
            resetTwist()
        }
    }

    // Accelerometer roll-angle fallback for devices without hardware Gyroscope
    private fun handleAccelRollTwist(ax: Float, ay: Float, az: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastTwistTriggerTime < 1000L) return

        val roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
        val deltaRoll = roll - prevRollAngle
        prevRollAngle = roll

        if (abs(deltaRoll) > 38f && abs(ay) < 8.5f) {
            val dir = if (deltaRoll > 0) 1 else -1
            if (twistCount == 0 || now - lastTwistPeakTime > TWIST_MAX_GAP_MS) {
                twistCount = 1
                lastTwistDir = dir
                lastTwistPeakTime = now
            } else if (now - lastTwistPeakTime >= TWIST_MIN_GAP_MS && dir != lastTwistDir) {
                lastTwistTriggerTime = now
                resetTwist()
                GestureActionExecutor.execute(context, "twist")
            }
        }
    }

    private fun resetTwist() {
        twistCount = 0
        lastTwistDir = 0
        lastTwistPeakTime = 0L
    }

    // =========================================================================
    // 3. MOTOROLA PICK UP GESTURE
    // =========================================================================
    private fun handlePickUpGesture(ax: Float, ay: Float, az: Float, now: Long) {
        if (isPocketCovered) return

        val totalG = sqrt(ax * ax + ay * ay + az * az)
        // Phone resting stationary and flat face-up
        val isFlatFaceUp = az > 8.9f && abs(ax) < 1.8f && abs(ay) < 1.8f && abs(totalG - 9.81f) < 1.2f
        if (isFlatFaceUp) {
            if (!wasFlatOnTable) {
                wasFlatOnTable = true
                flatSinceTime = now
            }
            return
        }

        // Lifted to natural viewing tilt
        val isLiftedUpright = ay > 5.2f && az > 1.5f && az < 8.2f && abs(ax) < 3.5f
        if (wasFlatOnTable && isLiftedUpright) {
            val restedDuration = now - flatSinceTime
            wasFlatOnTable = false
            if (restedDuration >= 1200L && now - lastPickupTriggerTime > 2000L) {
                lastPickupTriggerTime = now
                GestureActionExecutor.execute(context, "pickup")
            }
        } else if (az < 0.5f) {
            wasFlatOnTable = false
        }
    }

    // =========================================================================
    // 4. MOTOROLA FLIP FOR DO NOT DISTURB
    // =========================================================================
    private fun handleFlipForDnd(ax: Float, ay: Float, az: Float, now: Long) {
        val isFaceDown = az < -8.9f && abs(ax) < 2.0f && abs(ay) < 2.0f

        if (isFaceDown) {
            if (faceDownSinceTime == 0L) {
                faceDownSinceTime = now
            } else if (!isFaceDownDndActive && now - faceDownSinceTime >= 900L) {
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
