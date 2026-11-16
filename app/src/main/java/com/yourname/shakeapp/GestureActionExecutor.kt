package com.yourname.shakeapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.KeyEvent

object GestureActionExecutor {

    const val ACTION_NONE = 0
    const val ACTION_FLASHLIGHT = 1
    const val ACTION_CAMERA = 2
    const val ACTION_PLAY_PAUSE = 3
    const val ACTION_NEXT_TRACK = 4
    const val ACTION_SILENT_VIBRATE = 5
    const val ACTION_DND_TOGGLE = 6
    const val ACTION_LAUNCH_APP = 7

    val ACTION_LABELS = arrayOf(
        "None (Disabled)",
        "Toggle Flashlight",
        "Open Camera (Quick Capture)",
        "Play / Pause Media",
        "Next Music Track",
        "Toggle Silent / Vibrate",
        "Toggle Do Not Disturb",
        "Launch Custom App"
    )

    @Volatile
    private var isFlashlightOn = false
    private var torchCallbackRegistered = false

    fun initTorchSync(context: Context) {
        if (torchCallbackRegistered) return
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val backId = findBackFlashCameraId(cameraManager) ?: return
        cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == backId) {
                    isFlashlightOn = enabled
                }
            }
        }, Handler(Looper.getMainLooper()))
        torchCallbackRegistered = true
    }

    fun execute(context: Context, gestureKey: String) {
        val prefs = context.getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
        val actionType = prefs.getInt("${gestureKey}_action", getDefaultAction(gestureKey))
        if (actionType == ACTION_NONE) return

        triggerMotorolaHaptic(context)

        when (actionType) {
            ACTION_FLASHLIGHT -> toggleFlashlight(context)
            ACTION_CAMERA -> openCamera(context)
            ACTION_PLAY_PAUSE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_NEXT_TRACK -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_SILENT_VIBRATE -> toggleRingerMode(context)
            ACTION_DND_TOGGLE -> toggleDnd(context)
            ACTION_LAUNCH_APP -> {
                val pkg = prefs.getString("${gestureKey}_pkg", null)
                if (!pkg.isNullOrEmpty()) {
                    launchApp(context, pkg)
                }
            }
        }
    }

    fun getDefaultAction(gestureKey: String): Int {
        return when (gestureKey) {
            "chop" -> ACTION_FLASHLIGHT
            "twist" -> ACTION_CAMERA
            "pickup" -> ACTION_SILENT_VIBRATE
            else -> ACTION_NONE
        }
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val cameraId = findBackFlashCameraId(cameraManager) ?: return
            val target = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, target)
            isFlashlightOn = target
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun findBackFlashCameraId(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun openCamera(context: Context) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun sendMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun toggleRingerMode(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            AudioManager.RINGER_MODE_VIBRATE
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }
    }

    private fun toggleDnd(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val current = nm.currentInterruptionFilter
        if (current == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    fun setFlipDndState(context: Context, enableDnd: Boolean) {
        val prefs = context.getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("flip_dnd_enabled", true)) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return

        val current = nm.currentInterruptionFilter
        if (enableDnd && current == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            triggerMotorolaHaptic(context)
        } else if (!enableDnd && current != NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerMotorolaHaptic(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 35, 50, 35)
            val amplitudes = intArrayOf(0, 220, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 35, 50, 35), -1)
        }
    }
}
