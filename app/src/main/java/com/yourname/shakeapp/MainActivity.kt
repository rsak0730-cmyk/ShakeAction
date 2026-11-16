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
        val btnGrantDnd = findViewById<Button>(R.id.btnGrantDnd)

        setupGestureCard(
            gestureKey = "chop",
            spinner = findViewById(R.id.spinnerChopAction),
            pickAppBtn = findViewById(R.id.btnPickChopApp)
        )

        setupGestureCard(
            gestureKey = "twist",
            spinner = findViewById(R.id.spinnerTwistAction),
            pickAppBtn = findViewById(R.id.btnPickTwistApp)
        )

        setupGestureCard(
            gestureKey = "pickup",
            spinner = findViewById(R.id.spinnerPickupAction),
            pickAppBtn = findViewById(R.id.btnPickPickupApp)
        )

        val switchFlipDnd = findViewById<Switch>(R.id.switchFlipDnd)
        switchFlipDnd.isChecked = prefs.getBoolean("flip_dnd_enabled", true)
        switchFlipDnd.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("flip_dnd_enabled", isChecked).apply()
        }

        btnGrantDnd.setOnClickListener {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else {
                Toast.makeText(this, "DND & Silent Permission Already Granted", Toast.LENGTH_SHORT).show()
            }
        }

        btnStart.setOnClickListener {
            requestIgnoreBatteryOptimizations()
            prefs.edit().putBoolean("service_enabled", true).apply()
            val intent = Intent(this, ShakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateStatusText(true)
            Toast.makeText(this, "Moto Actions Started", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            prefs.edit().putBoolean("service_enabled", false).apply()
            stopService(Intent(this, ShakeService::class.java))
            updateStatusText(false)
            Toast.makeText(this, "Moto Actions Stopped", Toast.LENGTH_SHORT).show()
        }

        updateStatusText(prefs.getBoolean("service_enabled", false))
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
        tvServiceStatus.text = if (running) "Status: Active (Running in Background)" else "Status: Stopped"
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

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}
