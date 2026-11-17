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

    private val prefs = context.getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
    private var isPocketCovered = false
    private var hasHardwareGyro = false

    // Gravity low-pass filter
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    // 1. Chop-Chop Peak Inflection State
    private var chopState = 0
    private var firstChopSign = 0
    private var firstChopPeakTime = 0L
    private var lastChopTriggerTime = 0L
    private var prevLx = 0f
    private var risingX = false

    // 2. Twist-Twist State
    private var twistCount = 0
    private var lastTwistDir = 0
    private var lastTwistPeakTime = 0L
    private var lastTwistTriggerTime = 0L
    private var prevRollAngle = 0f

    // 3. Pick-Up State
    private var wasFlatOnTable = false
    private var flatSinceTime = 0L
    private var lastPickupTriggerTime = 0L

    // 4. Flip for DND State
    private var isFaceDownDndActive = false
    private var faceDownSinceTime = 0L

    // Dynamic Thresholds mapped from UI sliders (0..100)
    // Progress 100 (Very Sensitive) -> 13.5 m/s² | Progress 55 (Moto Default) -> 18.8 m/s² | Progress 0 (Hard) -> 27.0 m/s²
    private fun getChopThreshold(): Float {
        val progress = prefs.getInt("chop_sensitivity", 55)
        return 27.0f - (progress / 100f) * 13.5f
    }

    // Progress 100 (Very Sensitive) -> 2.3 rad/s | Progress 55 (Moto Default) -> 3.4 rad/s | Progress 0 (Hard) -> 5.2 rad/s
    private fun getTwistGyroThreshold(): Float {
        val progress = prefs.getInt("twist_sensitivity", 55)
        return 5.2f - (progress / 100f) * 2.9f
    }

    private fun getTwistRollFallbackThreshold(): Float {
        val progress = prefs.getInt("twist_sensitivity", 55)
        return 48.0f - (progress / 100f) * 20.0f
    }

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
                    val alpha = 0.80f
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

    // --- 1. EXACT MOTOROLA CHOP-CHOP ---
    private fun handleMotorolaChopChop(lx: Float, ly: Float, lz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastChopTriggerTime < 800L) return

        val absX = abs(lx)
        val absPrevX = abs(prevLx)

        if (absX > absPrevX) {
            risingX = true
        } else if (risingX && absX < absPrevX) {
            risingX = false

            val threshold = getChopThreshold()
            val isDominantLateral = absPrevX >= threshold &&
                    absPrevX > abs(lz) * 1.1f &&
                    absPrevX > abs(ly) * 0.8f

            if (isDominantLateral) {
                val peakSign = if (prevLx > 0) 1 else -1

                if (chopState == 0) {
                    chopState = 1
                    firstChopSign = peakSign
                    firstChopPeakTime = now
                } else if (chopState == 1) {
                    val dt = now - firstChopPeakTime
                    if (dt > 550L) {
                        firstChopSign = peakSign
                        firstChopPeakTime = now
                    } else if (dt >= 95L) {
                        // Opposing lateral snap (+X <-> -X) or sharp consecutive chop
                        if (peakSign != firstChopSign || dt >= 165L) {
                            lastChopTriggerTime = now
                            resetChop()
                            GestureActionExecutor.execute(context, "chop")
                        }
                    }
                }
            }
        }

        if (chopState == 1 && now - firstChopPeakTime > 550L) {
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

    // --- 2. EXACT MOTOROLA TWIST-TWIST ---
    private fun handleMotorolaTwistTwist(gx: Float, gy: Float, gz: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastTwistTriggerTime < 950L) return

        val threshold = getTwistGyroThreshold()
        val absY = abs(gy)
        val absX = abs(gx)
        val absZ = abs(gz)

        if (absY >= threshold && absY > absX * 1.2f && absY > absZ * 1.05f) {
            val dir = if (gy > 0) 1 else -1

            if (twistCount == 0) {
                twistCount = 1
                lastTwistDir = dir
                lastTwistPeakTime = now
            } else {
                val dt = now - lastTwistPeakTime
                if (dt > 650L) {
                    twistCount = 1
                    lastTwistDir = dir
                    lastTwistPeakTime = now
                } else if (dt >= 95L && dir != lastTwistDir) {
                    twistCount++
                    lastTwistDir = dir
                    lastTwistPeakTime = now

                    if (twistCount >= 2) {
                        lastTwistTriggerTime = now
                        resetTwist()
                        GestureActionExecutor.execute(context, "twist")
                    }
                }
            }
        }

        if (twistCount > 0 && now - lastTwistPeakTime > 650L) {
            resetTwist()
        }
    }

    private fun handleAccelRollTwist(ax: Float, ay: Float, az: Float, now: Long) {
        if (isPocketCovered) return
        if (now - lastTwistTriggerTime < 950L) return

        val roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
        val deltaRoll = roll - prevRollAngle
        prevRollAngle = roll

        if (abs(deltaRoll) > getTwistRollFallbackThreshold() && abs(ay) < 8.5f) {
            val dir = if (deltaRoll > 0) 1 else -1
            if (twistCount == 0 || now - lastTwistPeakTime > 650L) {
                twistCount = 1
                lastTwistDir = dir
                lastTwistPeakTime = now
            } else if (now - lastTwistPeakTime >= 95L && dir != lastTwistDir) {
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

    // --- 3. MOTOROLA PICK UP GESTURE ---
    private fun handlePickUpGesture(ax: Float, ay: Float, az: Float, now: Long) {
        if (isPocketCovered) return

        val totalG = sqrt(ax * ax + ay * ay + az * az)
        val isFlatFaceUp = az > 8.9f && abs(ax) < 1.8f && abs(ay) < 1.8f && abs(totalG - 9.81f) < 1.2f
        if (isFlatFaceUp) {
            if (!wasFlatOnTable) {
                wasFlatOnTable = true
                flatSinceTime = now
            }
            return
        }

        val isLiftedUpright = ay > 5.2f && az > 1.5f && az < 8.2f && abs(ax) < 3.5f
        if (wasFlatOnTable && isLiftedUpright) {
            val restedDuration = now - flatSinceTime
            wasFlatOnTable = false
            if (restedDuration >= 1100L && now - lastPickupTriggerTime > 2000L) {
                lastPickupTriggerTime = now
                GestureActionExecutor.execute(context, "pickup")
            }
        } else if (az < 0.5f) {
            wasFlatOnTable = false
        }
    }

    // --- 4. MOTOROLA FLIP FOR DO NOT DISTURB ---
    private fun handleFlipForDnd(ax: Float, ay: Float, az: Float, now: Long) {
        val isFaceDown = az < -8.9f && abs(ax) < 2.0f && abs(ay) < 2.0f

        if (isFaceDown) {
            if (faceDownSinceTime == 0L) {
                faceDownSinceTime = now
            } else if (!isFaceDownDndActive && now - faceDownSinceTime >= 850L) {
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
