package com.koftamainee.kpaper

import android.service.wallpaper.WallpaperService
import android.service.wallpaper.WallpaperService.Engine
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlin.math.atan2

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    @Suppress("DEPRECATION")
    inner class VideoEngine : Engine(), SensorEventListener {

        private var visible = false
        private var surfaceReady = false
        private var viewportW = 0
        private var viewportH = 0

        private var renderer: GLVideoRenderer? = null
        private var rendererUri: Uri? = null

        private var sensorManager: SensorManager? = null
        private var sensorOn = false
        private var parallaxEnabled = false
        private var tiltPercent = 100
        private var displayRotation = Surface.ROTATION_0
        private var lpX = 0f
        private var lpY = 0f

        private var hiddenAt = 0L
        private var restartOnShow = false
        private var restartThresholdMs = 5000L
        private var lockOnly = false

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> renderer?.pauseAndSeekNow()
                    Intent.ACTION_SCREEN_ON -> if (visible) renderer?.setVisible(true)
                }
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            holder.setFormat(PixelFormat.OPAQUE)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            super.onVisibilityChanged(isVisible)
            if (visible && !isVisible) {
                hiddenAt = SystemClock.elapsedRealtime()
            } else if (!visible && isVisible && hiddenAt != 0L) {
                val sp = getSharedPreferences("kpaper", MODE_PRIVATE)
                val lockOnly = sp.getBoolean("restart_lock_only", false)
                val threshold = sp.getInt("restart_after", 5).coerceIn(0, 30) * 1000L
                if (!lockOnly && SystemClock.elapsedRealtime() - hiddenAt >= threshold) {
                    restartOnShow = true
                }
                hiddenAt = 0L
            }
            visible = isVisible
            updateState()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            updateState()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            viewportW = width
            viewportH = height
            renderer?.setViewport(width, height)
            updateState()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            updateState()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            unregisterReceiver(screenReceiver)
            stopRenderer()
            super.onDestroy()
        }

        private fun updateState() {
            val sp = getSharedPreferences("kpaper", MODE_PRIVATE)
            parallaxEnabled = sp.getBoolean("parallax", true)
            tiltPercent = sp.getInt("tilt_percent", 100).coerceIn(0, 200)
            restartThresholdMs = sp.getInt("restart_after", 5).coerceIn(0, 30) * 1000L
            lockOnly = sp.getBoolean("restart_lock_only", false)
            displayRotation = (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
            val uri: Uri? = sp.getString("uri", null)?.let { Uri.parse(it) }

            val shouldRun =
                surfaceReady && viewportW > 0 && viewportH > 0 && uri != null

            if (!shouldRun) {
                stopRenderer()
                return
            }

            if (renderer != null && rendererUri != uri) {
                stopRenderer()
            }
            if (renderer == null) {
                rendererUri = uri
                renderer = GLVideoRenderer(
                    this@VideoWallpaperService.applicationContext,
                    surfaceHolder,
                    uri,
                    viewportW,
                    viewportH
                ).also { it.start() }
            }
            renderer?.setTiltConfig(parallaxEnabled, tiltPercent)
            renderer?.setRestartThreshold(if (lockOnly) -1L else restartThresholdMs)
            val restart = restartOnShow
            restartOnShow = false
            renderer?.setVisible(visible, restart)
            updateSensor()
        }

        private fun updateSensor() {
            val want = visible && parallaxEnabled && renderer != null
            if (want == sensorOn) return
            if (want) {
                sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    sensorOn = true
                }
            } else {
                unregisterSensor()
            }
        }

        private fun unregisterSensor() {
            sensorManager?.unregisterListener(this)
            sensorOn = false
            lpX = 0f
            lpY = 0f
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!parallaxEnabled || !sensorOn) return
            val g = event.values
            val roll = atan2(g[0].toDouble(), g[2].toDouble()).toFloat()
            val pitch = atan2(g[1].toDouble(), g[2].toDouble()).toFloat()
            val range = 0.5235988f
            val tx = (roll / range).coerceIn(-1f, 1f)
            val ty = (pitch / range).coerceIn(-1f, 1f)
            lpX += (tx - lpX) * 0.15f
            lpY += (ty - lpY) * 0.15f

            val sx: Float
            val sy: Float
            when (displayRotation) {
                Surface.ROTATION_90 -> {
                    sx = lpY
                    sy = -lpX
                }
                Surface.ROTATION_180 -> {
                    sx = -lpX
                    sy = -lpY
                }
                Surface.ROTATION_270 -> {
                    sx = -lpY
                    sy = lpX
                }
                else -> {
                    sx = lpX
                    sy = lpY
                }
            }
            renderer?.setTilt(sx, sy)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        private fun stopRenderer() {
            unregisterSensor()
            val r = renderer ?: return
            renderer = null
            rendererUri = null
            r.stopAndJoin()
        }
    }
}
