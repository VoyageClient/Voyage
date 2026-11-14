/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import org.matrix.android.sdk.api.debug.DebugLog
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext

internal class VideoPreviewRenderer(
        private val output: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private var resolution: Pair<Int, Int>?,
        private val onReady: (Surface) -> Unit,
        private val onFailure: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("UploadVideoPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val released = AtomicBoolean()
    @Volatile private var failed = false
    private val egl = EGLContext.getEGL() as EGL10
    private var display = EGL10.EGL_NO_DISPLAY
    private var context = EGL10.EGL_NO_CONTEXT
    private var window = EGL10.EGL_NO_SURFACE
    private var outputSurface: Surface? = null
    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var sourceTexture = 0
    private var scaledTexture = 0
    private var framebuffer = 0
    private var allocatedSize: Pair<Int, Int>? = null
    private var externalProgram = 0
    private var bitmapProgram = 0
    private var maxTextureSize = 0
    private var hasFrame = false
    private val sourceMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val positions = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = buffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        submit {
            setup()
            inputTexture = SurfaceTexture(sourceTexture).apply {
                setOnFrameAvailableListener {
                    submit {
                        updateTexImage()
                        getTransformMatrix(sourceMatrix)
                        hasFrame = true
                        draw()
                    }
                }
            }
            val surface = Surface(inputTexture).also { inputSurface = it }
            DebugLog.i { "UPLOADPREVIEW video renderer ready viewport=${width}x$height resolution=$resolution" }
            mainHandler.post { if (!released.get() && !failed) onReady(surface) }
        }
    }

    fun resize(width: Int, height: Int) = submit {
        this.width = width
        this.height = height
        draw()
    }

    fun setResolution(value: Pair<Int, Int>?) = submit {
        DebugLog.i { "UPLOADPREVIEW video resolution $resolution->$value hasFrame=$hasFrame" }
        resolution = value
        if (value == null && allocatedSize != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scaledTexture)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            allocatedSize = null
        }
        draw()
    }

    // The TextureView transfers ownership on destruction, so EGL must detach before this is freed.
    fun release() {
        if (released.getAndSet(true)) return
        DebugLog.i { "UPLOADPREVIEW video renderer release" }
        handler.post {
            try {
                cleanup()
            } catch (failure: Throwable) {
                Timber.w(failure, "Failed to release video preview renderer")
            } finally {
                runCatching { output.release() }.onFailure { Timber.w(it, "Failed to release video preview output") }
                thread.quit()
            }
        }
    }

    private fun submit(block: () -> Unit) {
        handler.post {
            if (!released.get() && !failed) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failed = true
                    runCatching { cleanup() }.onFailure { Timber.w(it, "Failed to clean up video preview renderer") }
                    mainHandler.post { if (!released.get()) onFailure(failure) }
                }
            }
        }
    }

    private fun setup() {
        // EGL14 requires API 17. EGL10 supports the same GLES2 rendering on API 14.
        display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        check(egl.eglInitialize(display, IntArray(2))) { "Cannot initialize preview EGL" }
        val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE,
        )
        check(egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) { "No preview EGL config" }
        context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE))
        check(context != EGL10.EGL_NO_CONTEXT) { "Cannot create preview EGL context" }
        outputSurface = Surface(output)
        window = egl.eglCreateWindowSurface(display, configs[0], outputSurface, intArrayOf(EGL10.EGL_NONE))
        check(window != EGL10.EGL_NO_SURFACE && egl.eglMakeCurrent(display, window, window, context)) { "Cannot attach preview EGL surface" }
        externalProgram = program(EXTERNAL_FRAGMENT)
        bitmapProgram = program(BITMAP_FRAGMENT)
        sourceTexture = texture(GL_TEXTURE_EXTERNAL_OES)
        scaledTexture = texture(GLES20.GL_TEXTURE_2D)
        val names = IntArray(1)
        GLES20.glGenFramebuffers(1, names, 0)
        framebuffer = names[0]
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, names, 0)
        maxTextureSize = names[0]
        checkGl()
    }

    private fun draw() {
        if (!hasFrame || width <= 0 || height <= 0) return
        val size = resolution?.takeIf { it.first > 0 && it.second > 0 }
                ?.let { minOf(it.first, maxTextureSize) to minOf(it.second, maxTextureSize) }
        if (size != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scaledTexture)
            if (allocatedSize != size) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size.first, size.second, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                allocatedSize = size
                DebugLog.i { "UPLOADPREVIEW video buffer=${size.first}x${size.second} requested=$resolution viewport=${width}x$height" }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, scaledTexture, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Incomplete preview framebuffer" }
            GLES20.glViewport(0, 0, size.first, size.second)
            drawTexture(externalProgram, GL_TEXTURE_EXTERNAL_OES, sourceTexture, sourceMatrix)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        if (size == null) {
            drawTexture(externalProgram, GL_TEXTURE_EXTERNAL_OES, sourceTexture, sourceMatrix)
        } else {
            drawTexture(bitmapProgram, GLES20.GL_TEXTURE_2D, scaledTexture, identityMatrix)
        }
        checkGl()
        check(egl.eglSwapBuffers(display, window)) { "Cannot display preview frame" }
    }

    private fun drawTexture(program: Int, target: Int, texture: Int, matrix: FloatArray) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        val position = GLES20.glGetAttribLocation(program, "position")
        val coordinate = GLES20.glGetAttribLocation(program, "coordinate")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false, matrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
        GLES20.glBindTexture(target, 0)
    }

    private fun cleanup() {
        inputSurface?.release()
        inputSurface = null
        inputTexture?.setOnFrameAvailableListener(null)
        inputTexture?.release()
        inputTexture = null
        if (display != EGL10.EGL_NO_DISPLAY) {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            if (window != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, window)
            if (context != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
        }
        window = EGL10.EGL_NO_SURFACE
        context = EGL10.EGL_NO_CONTEXT
        display = EGL10.EGL_NO_DISPLAY
        outputSurface?.release()
        outputSurface = null
    }

    private fun texture(target: Int): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        GLES20.glBindTexture(target, names[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return names[0]
    }

    private fun program(fragment: String): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX)
        val pixel = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, pixel)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(pixel)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun checkGl() {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "Preview GL error 0x${error.toString(16)}" }
    }

    private companion object {
        // SurfaceTexture supports this target on API 14; its Java constant was added at 15.
        const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
        const val EGL_RENDERABLE_TYPE = 0x3040
        const val EGL_OPENGL_ES2_BIT = 4
        const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        const val VERTEX = """
            attribute vec4 position;
            attribute vec4 coordinate;
            uniform mat4 textureMatrix;
            varying vec2 uv;
            void main() {
                gl_Position = position;
                uv = (textureMatrix * coordinate).xy;
            }
        """
        const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES image;
            varying vec2 uv;
            void main() { gl_FragColor = texture2D(image, uv); }
        """
        const val BITMAP_FRAGMENT = """
            precision mediump float;
            uniform sampler2D image;
            varying vec2 uv;
            void main() { gl_FragColor = texture2D(image, uv); }
        """

        fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
