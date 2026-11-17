package com.yourname.shakeapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestRuntimePermissions()

        val prefs = getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)

        val btnStart = findViewById<Button>(R.id.btnStartService)
        val btnStop = findViewById<Button>(R.id.btnStopService)
        val btnGrantPermissions = findViewById<Button>(R.id.btnGrantPermissions)

        setupGestureCard("chop", findViewById(R.id.spinnerChopAction), findViewById(R.id.btnPickChopApp))
        setupGestureCard("twist", findViewById(R.id.spinnerTwistAction), findViewById(R.id.btnPickTwistApp))
        setupGestureCard("pickup", findViewById(R.id.spinnerPickupAction), findViewById(R.id.btnPickPickupApp))

        setupSensitivitySlider(
            prefKey = "chop_sensitivity",
            labelPrefix = "Chop Sensitivity",
            seekBar = findViewById(R.id.seekChopSensitivity),
            labelView = findViewById(R.id.tvChopSensitivity)
        )

        setupSensitivitySlider(
            prefKey = "twist_sensitivity",
            labelPrefix = "Twist Sensitivity",
            seekBar = findViewById(R.id.seekTwistSensitivity),
            labelView = findViewById(R.id.tvTwistSensitivity)
        )

        val switchFlipDnd = findViewById<Switch>(R.id.switchFlipDnd)
        switchFlipDnd.isChecked = prefs.getBoolean("flip_dnd_enabled", true)
        switchFlipDnd.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("flip_dnd_enabled", isChecked).apply()
        }

        btnGrantPermissions.setOnClickListener {
            showPermissionChecklistDialog()
        }

        btnStart.setOnClickListener {
            requestIgnoreBatteryOptimizationsSafe()
            prefs.edit().putBoolean("service_enabled", true).apply()

            val intent = Intent(this, ShakeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                updateStatusText(true)
                Toast.makeText(this, "Moto Actions Engine Active", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnStop.setOnClickListener {
            prefs.edit().putBoolean("service_enabled", false).apply()
            stopService(Intent(this, ShakeService::class.java))
            updateStatusText(false)
            Toast.makeText(this, "Moto Actions Engine Stopped", Toast.LENGTH_SHORT).show()
        }

        updateStatusText(prefs.getBoolean("service_enabled", false))
    }

    private fun showPermissionChecklistDialog() {
        val nm = getSystemService(NotificationManager::class.java)
        val dndStatus = if (nm.isNotificationPolicyAccessGranted) "✅ Granted" else "❌ Tap to Grant"
        val overlayStatus = if (Settings.canDrawOverlays(this)) "✅ Granted" else "❌ Tap to Grant"

        val pm = getSystemService(PowerManager::class.java)
        val batteryStatus = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅ Unrestricted" else "❌ Tap to Set"

        val items = arrayOf(
            "1. Do Not Disturb Access: $dndStatus",
            "2. Display Over Other Apps: $overlayStatus",
            "3. Battery Unrestricted: $batteryStatus",
            "4. Open App Info (For Restricted Settings)"
        )

        AlertDialog.Builder(this)
            .setTitle("Permissions Setup")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openDndSettingsSafe()
                    1 -> openOverlaySettingsSafe()
                    2 -> requestIgnoreBatteryOptimizationsSafe()
                    3 -> openAppDetailsSettings()
                }
            }
            .show()
    }

    private fun openDndSettingsSafe() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (e: Exception) {
            openAppDetailsSettings()
            Toast.makeText(this, "Search 'Do Not Disturb' in Settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettingsSafe() {
        try {
            // Try direct package URI first
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                // Fallback to general overlay list (prevents Samsung One UI crash)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ex: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizationsSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    openAppDetailsSettings()
                }
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            Toast.makeText(
                this,
                "Tap 3-dots (top right) -> 'Allow restricted settings' if grayed out",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupSensitivitySlider(
        prefKey: String,
        labelPrefix: String,
        seekBar: SeekBar,
        labelView: TextView
    ) {
        val prefs = getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getInt(prefKey, 55)
        seekBar.progress = saved
        labelView.text = "$labelPrefix: ${getSensitivityTag(saved)} ($saved%)"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt(prefKey, progress).apply()
                labelView.text = "$labelPrefix: ${getSensitivityTag(progress)} ($progress%)"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun getSensitivityTag(value: Int): String {
        return when {
            value >= 80 -> "Ultra Light"
            value >= 65 -> "High"
            value >= 45 -> "Moto Default"
            value >= 25 -> "Firm"
            else -> "Hard Shake"
        }
    }

    private fun setupGestureCard(gestureKey: String, spinner: Spinner, pickAppBtn: Button) {
        val prefs = getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            GestureActionExecutor.ACTION_LABELS
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter

        val savedAction = prefs.getInt(
            "${gestureKey}_action",
            GestureActionExecutor.getDefaultAction(gestureKey)
        )
        spinner.setSelection(savedAction)
        updateAppButtonLabel(gestureKey, pickAppBtn)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("${gestureKey}_action", position).apply()
                pickAppBtn.visibility = if (position == GestureActionExecutor.ACTION_LAUNCH_APP) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        pickAppBtn.setOnClickListener {
            showInstalledAppsDialog(gestureKey, pickAppBtn)
        }
    }

    private fun updateAppButtonLabel(gestureKey: String, button: Button) {
        val prefs = getSharedPreferences("shake_prefs", Context.MODE_PRIVATE)
        val appName = prefs.getString("${gestureKey}_app_name", null)
        button.text = if (appName != null) "Selected App: $appName" else "Select Installed App"
    }

    private fun showInstalledAppsDialog(gestureKey: String, button: Button) {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                val pkg = resolveInfo.activityInfo.packageName
                Pair(label, pkg)
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        val labels = apps.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose App to Launch")
            .setItems(labels) { _, which ->
                val selected = apps[which]
                getSharedPreferences("shake_prefs", Context.MODE_PRIVATE).edit()
                    .putString("${gestureKey}_app_name", selected.first)
                    .putString("${gestureKey}_pkg", selected.second)
                    .apply()
                updateAppButtonLabel(gestureKey, button)
            }
            .show()
    }

    private fun updateStatusText(running: Boolean) {
        tvServiceStatus.text = if (running) "Status: Active (Ultra-Low Latency Running)" else "Status: Stopped"
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}
