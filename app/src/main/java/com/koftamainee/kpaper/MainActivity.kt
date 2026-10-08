package com.koftamainee.kpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var parallax: CheckBox
    private lateinit var tiltBar: SeekBar
    private lateinit var tiltValue: TextView
    private lateinit var restartBar: SeekBar
    private lateinit var restartValue: TextView
    private lateinit var restartLockOnly: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        parallax = findViewById(R.id.parallax)
        tiltBar = findViewById(R.id.tilt)
        tiltValue = findViewById(R.id.tilt_value)
        restartBar = findViewById(R.id.restart)
        restartValue = findViewById(R.id.restart_value)
        restartLockOnly = findViewById(R.id.restart_lock_only)
        findViewById<Button>(R.id.pick).setOnClickListener { pickVideo() }

        parallax.isChecked = prefs().getBoolean("parallax", true)
        tiltBar.progress = prefs().getInt("tilt_percent", 100)
        tiltBar.isEnabled = parallax.isChecked
        tiltValue.isEnabled = parallax.isChecked
        updateTiltLabel()

        restartBar.progress = prefs().getInt("restart_after", 5).coerceIn(0, 30)
        updateRestartLabel()
        restartBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs().edit().putInt("restart_after", progress).apply()
                updateRestartLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })

        restartLockOnly.isChecked = prefs().getBoolean("restart_lock_only", false)
        restartBar.isEnabled = !restartLockOnly.isChecked
        restartValue.isEnabled = !restartLockOnly.isChecked
        restartLockOnly.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean("restart_lock_only", checked).apply()
            restartBar.isEnabled = !checked
            restartValue.isEnabled = !checked
        }

        parallax.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean("parallax", checked).apply()
            tiltBar.isEnabled = checked
            tiltValue.isEnabled = checked
        }
        tiltBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs().edit().putInt("tilt_percent", progress).apply()
                updateTiltLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun updateTiltLabel() {
        tiltValue.text = getString(R.string.tilt_value_fmt, tiltBar.progress)
    }

    private fun updateRestartLabel() {
        restartValue.text = getString(R.string.restart_fmt, restartBar.progress)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun prefs() = getSharedPreferences("kpaper", MODE_PRIVATE)

    private fun updateStatus() {
        val name = prefs().getString("name", null)
        status.text = if (name == null) {
            getString(R.string.status_none)
        } else {
            getString(R.string.status_selected, name)
        }
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        prefs().edit()
            .putString("uri", uri.toString())
            .putString("name", queryDisplayName(uri))
            .apply()
        updateStatus()
        openWallpaperChooser()
    }

    private fun queryDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun openWallpaperChooser() {
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, VideoWallpaperService::class.java)
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.cant_open_chooser, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQUEST_PICK = 1001
    }
}
