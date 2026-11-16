package com.yourname.shakeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ShakeService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val cameraManager = getSystemService(CameraManager::class.java)
        val backFlashCameraId = findBackFlashCameraId(cameraManager)

        if (backFlashCameraId == null) {
            stopSelf()
            return
        }

        sensorManager = getSystemService(SensorManager::class.java)

        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rawAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val motionSensor = linearAccel ?: rawAccel

        if (motionSensor == null) {
            stopSelf()
            return
        }

        val usesRaw = (linearAccel == null)
        shakeDetector = ShakeDetector(this, cameraManager, backFlashCameraId, usesRaw)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ShakeAction::ChopSensorWakeLock"
        ).apply {
            acquire()
        }

        sensorManager.registerListener(
            shakeDetector,
            motionSensor,
            SensorManager.SENSOR_DELAY_GAME
        )

        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { proximitySensor ->
            sensorManager.registerListener(
                shakeDetector,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    private fun findBackFlashCameraId(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            if (hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (::sensorManager.isInitialized && ::shakeDetector.isInitialized) {
            sensorManager.unregisterListener(shakeDetector)
            shakeDetector.cleanup()
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chop-Chop Flashlight Active")
            .setContentText("Chop twice to toggle flashlight")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chop-Chop Gesture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "shake_detection"
        private const val NOTIFICATION_ID = 1001
    }
}
