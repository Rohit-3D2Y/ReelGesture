package com.inhouse.reelgesture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var cameraStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var startStopButton: Button

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraStatus = findViewById(R.id.cameraStatus)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        serviceStatus = findViewById(R.id.serviceStatus)
        startStopButton = findViewById(R.id.startStopButton)

        findViewById<Button>(R.id.grantCameraButton).setOnClickListener { requestPermissions() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.openInstagramButton).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(Prefs.INSTAGRAM_PACKAGE)
            if (launch == null) toast("Instagram is not installed") else safeStart(launch)
        }
        startStopButton.setOnClickListener { toggleService() }

        findViewById<SwitchCompat>(R.id.instagramOnlySwitch).apply {
            isChecked = Prefs.instagramOnly(context)
            setOnCheckedChangeListener { _, checked -> Prefs.setInstagramOnly(context, checked) }
        }
        findViewById<SwitchCompat>(R.id.allowPreviousSwitch).apply {
            isChecked = Prefs.allowPrevious(context)
            setOnCheckedChangeListener { _, checked -> Prefs.setAllowPrevious(context, checked) }
        }
        findViewById<SeekBar>(R.id.sensitivitySeek).apply {
            progress = Prefs.sensitivity(context)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) Prefs.setSensitivity(context, value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun toggleService() {
        if (GestureCameraService.isRunning) {
            GestureCameraService.stop(this)
        } else {
            // Only start when the service can legally go foreground; otherwise Android
            // would kill the app for not calling startForeground in time.
            if (!hasCamera()) {
                toast("Grant camera permission first")
                requestPermissions()
                return
            }
            if (ReelScrollService.instance == null) {
                toast("Enable the accessibility service first")
                return
            }
            try {
                GestureCameraService.start(this)
            } catch (e: Exception) {
                toast("Could not start: ${e.message}")
            }
        }
        refreshHandler.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        cameraStatus.text = if (hasCamera()) "✅ Camera permission granted" else "❌ Camera permission needed"
        accessibilityStatus.text = if (ReelScrollService.instance != null) {
            "✅ Accessibility service enabled"
        } else {
            "❌ Accessibility service off"
        }
        serviceStatus.text = "Status: ${GestureCameraService.status}"
        startStopButton.text = if (GestureCameraService.isRunning) "Stop gesture control" else "Start gesture control"
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Not available on this device")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
