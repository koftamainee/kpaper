package com.koftamainee.kpaper

import android.service.wallpaper.WallpaperService
import android.service.wallpaper.WallpaperService.Engine
import android.graphics.PixelFormat
import android.net.Uri
import android.view.SurfaceHolder

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {

        private var visible = false
        private var surfaceReady = false
        private var viewportW = 0
        private var viewportH = 0

        private var renderer: GLVideoRenderer? = null
        private var rendererUri: Uri? = null

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            holder.setFormat(PixelFormat.OPAQUE)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            super.onVisibilityChanged(isVisible)
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
            stopRenderer()
            super.onDestroy()
        }

        private fun updateState() {
            val uri = readVideoUri()
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
            renderer?.setVisible(visible)
        }

        private fun stopRenderer() {
            val r = renderer ?: return
            renderer = null
            rendererUri = null
            r.stopAndJoin()
        }

        private fun readVideoUri(): Uri? = try {
            this@VideoWallpaperService
                .getSharedPreferences("kpaper", MODE_PRIVATE)
                .getString("uri", null)
                ?.let { Uri.parse(it) }
        } catch (_: Exception) {
            null
        }
    }
}
