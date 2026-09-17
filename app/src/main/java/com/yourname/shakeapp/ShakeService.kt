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
    private var sensor: Sensor? = null
    private lateinit var shakeDetector: ShakeDetector
    private val CHANNEL_ID = "ShakeServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // This notification prevents Android from killing the app
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shake Flashlight Active")
            .setContentText("Listening for shakes in the background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Default android icon
            .build()
        
        startForeground(1, notification)

        // Set up the flashlight and sensor
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // 0 is usually the back camera

        shakeDetector = ShakeDetector(cameraManager, cameraId)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        sensor?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Tells Android to restart this if it crashes
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop listening to save battery when service is stopped
        sensorManager.unregisterListener(shakeDetector)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Shake Flashlight Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}

