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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ShakeService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private var cpuWakeLock: PowerManager.WakeLock? = null

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

        GestureActionExecutor.initTorchSync(this)

        sensorManager = getSystemService(SensorManager::class.java)
        shakeDetector = ShakeDetector(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ShakeAction::MotoUltraLowLatencyLock"
        ).apply {
            acquire()
        }

        // SENSOR_DELAY_FASTEST with 0 batching latency for authentic Moto Actions feel
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_FASTEST, 0)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (::sensorManager.isInitialized && ::shakeDetector.isInitialized) {
            sensorManager.unregisterListener(shakeDetector)
        }
        cpuWakeLock?.let {
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
            .setContentTitle("Moto Actions Pro Active")
            .setContentText("Chop, Twist, Pick Up & Flip gestures running")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Moto Motion Gestures",
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
