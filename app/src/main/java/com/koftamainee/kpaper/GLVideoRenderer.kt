package com.koftamainee.kpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

class GLVideoRenderer(
    private val context: Context,
    private val holder: SurfaceHolder,
    private val uri: Uri,
    @Volatile private var viewportW: Int,
    @Volatile private var viewportH: Int,
) : Thread("kpaper-gl") {

    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var stopRequested = false

    @Volatile
    private var shouldPlay = false

    @Volatile
    private var prepared = false

    @Volatile
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var scaleHandle = 0
    private var offsetHandle = 0
    private var stMatrixHandle = 0
    private var textureId = 0

    @Volatile
    private var tiltEnabled = false

    @Volatile
    private var tiltPercent = 100

    @Volatile
    private var tiltX = 0f

    @Volatile
    private var tiltY = 0f

    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var player: MediaPlayer? = null

    private var videoW = 0
    private var videoH = 0
    private var hasFrame = false
    private val stMatrix = FloatArray(16)

    private val positionBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val texCoordBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    override fun run() {
        try {
            Looper.prepare()
            handler = Handler(Looper.myLooper()!!)
            readyLatch.countDown()

            if (stopRequested) return
            if (!initEgl()) return
            initGl()
            readVideoSize()
            if (stopRequested) return

            val st = SurfaceTexture(textureId)
            surfaceTexture = st
            if (videoW > 0 && videoH > 0) {
                st.setDefaultBufferSize(videoW, videoH)
            }
            videoSurface = Surface(st)
            st.setOnFrameAvailableListener({ hasFrame = true; drawFrame() }, handler!!)

            val p = MediaPlayer().apply {
                setDataSource(context, uri)
                setSurface(videoSurface)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { mp ->
                    if (videoW == 0) {
                        videoW = mp.videoWidth
                        videoH = mp.videoHeight
                    }
                    prepared = true
                    if (shouldPlay) {
                        try {
                            mp.start()
                        } catch (_: Exception) {
                        }
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    handler?.post {
                        try {
                            mp.release()
                        } catch (_: Exception) {
                        }
                        if (player === mp) {
                            player = null
                            prepared = false
                        }
                    }
                    true
                }
                prepareAsync()
            }
            player = p
            if (stopRequested) {
                try {
                    p.release()
                } catch (_: Exception) {
                }
                return
            }
            if (shouldPlay) {
                handler?.post { applyVisibility() }
            }

            Looper.loop()
        } catch (_: Throwable) {
        } finally {
            readyLatch.countDown()
            releaseAll()
        }
    }

    fun setVisible(visible: Boolean) {
        shouldPlay = visible
        handler?.post { applyVisibility() }
    }

    fun setViewport(w: Int, h: Int) {
        viewportW = w
        viewportH = h
        handler?.post { drawFrame() }
    }

    fun stopAndJoin(timeoutMs: Long = 2000) {
        stopRequested = true
        if (handler == null) {
            readyLatch.await()
        }
        handler?.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
            } catch (_: Exception) {
            }
            drawBlack()
            Looper.myLooper()?.quit()
        }
        var elapsed = 0L
        while (isAlive && elapsed < timeoutMs) {
            join(100)
            elapsed += 100
        }
    }

    private fun applyVisibility() {
        val p = player ?: return
        if (stopRequested || !prepared) return
        try {
            if (shouldPlay) {
                if (!p.isPlaying) p.start()
            } else if (p.isPlaying) {
                p.pause()
                p.seekTo(0)
            }
        } catch (_: Exception) {
        }
    }

    private fun readVideoSize() {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0) {
                if (rot % 180 == 90) {
                    videoW = h
                    videoH = w
                } else {
                    videoW = w
                    videoH = h
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun initEgl(): Boolean {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return false

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0)) return false
        if (numConfigs[0] <= 0) return false
        val config = configs[0] ?: return false

        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return false

        val eglSurf = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurf == null || eglSurf == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(display, eglSurf, eglSurf, context)) return false

        eglDisplay = display
        eglContext = context
        eglSurface = eglSurf
        return true
    }

    private fun initGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        positionBuffer.put(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f
            )
        )
        positionBuffer.position(0)
        texCoordBuffer.put(
            floatArrayOf(
                0f, 0f,
                1f, 0f,
                0f, 1f,
                1f, 1f
            )
        )
        texCoordBuffer.position(0)
    }

    private fun drawFrame() {
        if (stopRequested || !hasFrame) return
        val display = eglDisplay ?: return
        val eglSurf = eglSurface ?: return
        if (!EGL14.eglMakeCurrent(display, eglSurf, eglSurf, eglContext)) return

        try {
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
        } catch (_: Exception) {
            return
        }

        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (videoW > 0 && videoH > 0 && viewportW > 0 && viewportH > 0) {
            val scale = minOf(
                viewportW.toFloat() / videoW,
                viewportH.toFloat() / videoH
            )
            val scaleX = videoW * scale / viewportW
            val scaleY = videoH * scale / viewportH

            GLES20.glUseProgram(program)
            GLES20.glUniform2f(scaleHandle, scaleX, scaleY)
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        if (!EGL14.eglSwapBuffers(display, eglSurf)) {
            stopRequested = true
            handler?.post { Looper.myLooper()?.quit() }
        }
    }

    private fun drawBlack() {
        try {
            val display = eglDisplay ?: return
            val eglSurf = eglSurface ?: return
            val surface = holder.surface
            if (surface == null || !surface.isValid) return
            if (!EGL14.eglMakeCurrent(display, eglSurf, eglSurf, eglContext)) return
            GLES20.glViewport(0, 0, viewportW, viewportH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, eglSurf)
        } catch (_: Exception) {
        }
    }

    private fun releaseAll() {
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            videoSurface?.release()
        } catch (_: Exception) {
        }
        videoSurface = null
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        surfaceTexture = null

        val display = eglDisplay ?: return
        try {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            eglSurface?.let { EGL14.eglDestroySurface(display, it) }
            eglContext?.let { EGL14.eglDestroyContext(display, it) }
            EGL14.eglTerminate(display)
        } catch (_: Exception) {
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale + uOffset, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
