/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.gl

import android.opengl.GLES20
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * A framebuffer to draw into instead of the encoder, so a frame can be read back and kept. Used by
 * the reversing export, which has to hold frames before it can hand them over in the other order.
 */
@RequiresApi(17)
internal class OffscreenTarget(private val width: Int, private val height: Int) {

    private var framebuffer = 0
    private var texture = 0

    fun setup() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val buffers = IntArray(1)
        GLES20.glGenFramebuffers(1, buffers, 0)
        framebuffer = buffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw GlException("Incomplete framebuffer: $status")
        unbind()
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
    }

    fun unbind() = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

    /** Reads what was last drawn, bottom row first — the same way a texture is uploaded back. */
    fun readInto(pixels: ByteBuffer) {
        pixels.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GlUtil.checkGlError("glReadPixels")
        pixels.rewind()
    }

    fun release() {
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            framebuffer = 0
        }
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
    }
}
