package com.yourname.shakeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ShakeService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val cameraManager = getSystemService(CameraManager::class.java)
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        
        if (cameraId == null) {
            stopSelf() // Stop if device has no camera flash
            return
        }

        shakeDetector = ShakeDetector(cameraManager, cameraId)

        sensorManager = getSystemService(SensorManager::class.java)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            stopSelf() // Stop if device lacks an accelerometer
            return
        }

        sensorManager.registerListener(
            shakeDetector,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (::sensorManager.isInitialized && ::shakeDetector.isInitialized) {
            sensorManager.unregisterListener(shakeDetector)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShakeAction")
            .setContentText("Shake detection is active")
            .setSmallIcon(R.drawable.ic_launcher) 
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shake detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "shake_detection"
        private const val NOTIFICATION_ID = 1001
    }
}
