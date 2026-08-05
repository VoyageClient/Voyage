/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import androidx.annotation.RequiresApi

/**
 * Draws the decoder's external-OES texture onto a full-viewport quad. Crop and rotation arrive
 * already folded into [textureCoords] (see [im.vector.lib.mediatranscode.CropGeometry]), so the
 * geometry never changes and the decoder's own transform matrix still composes on top.
 */
@RequiresApi(17)
internal class CropTextureRenderer(
        textureCoords: FloatArray,
        private val outputWidth: Int,
        private val outputHeight: Int,
) {

    var textureId = 0
        private set

    private var program = 0
    private var stMatrixHandle = 0
    private var positionHandle = 0
    private var textureCoordHandle = 0

    private val positions = GlUtil.floatBuffer(
            floatArrayOf(
                    -1f, -1f, 0f,
                    1f, -1f, 0f,
                    -1f, 1f, 0f,
                    1f, 1f, 0f,
            )
    )

    private val textureCoordBuffer = GlUtil.floatBuffer(textureCoords)

    fun setup() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GlUtil.checkGlError("glBindTexture")
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GlUtil.checkGlError("glTexParameteri")
    }

    fun drawFrame(stMatrix: FloatArray) {
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glVertexAttribPointer(positionHandle, POSITION_COMPONENTS, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(textureCoordHandle, TEXTURE_COMPONENTS, GLES20.GL_FLOAT, false, 0, textureCoordBuffer)
        GLES20.glEnableVertexAttribArray(textureCoordHandle)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    companion object {
        private const val POSITION_COMPONENTS = 3
        private const val TEXTURE_COMPONENTS = 2

        private const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
