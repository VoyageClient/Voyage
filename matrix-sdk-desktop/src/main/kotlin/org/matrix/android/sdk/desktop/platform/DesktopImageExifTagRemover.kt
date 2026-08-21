/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.platform

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * Rewrites JPEG and PNG containers without their metadata: JPEG APP1 (EXIF/XMP) and APP13 (IPTC)
 * segments and PNG text/EXIF chunks are dropped, everything else is copied byte for byte so the
 * pixels are never re-encoded. Other formats report "can't strip in place" so the caller re-encodes.
 */
internal class DesktopImageExifTagRemover @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : ImageExifTagRemover {

    override suspend fun stripImageMetadata(imageFile: File): File? = withContext(coroutineDispatchers.io) {
        val header = imageFile.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        try {
            when {
                isJpeg(header) -> rewrite(imageFile) { input, output -> stripJpeg(input, output) }
                isPng(header) -> rewrite(imageFile) { input, output -> stripPng(input, output) }
                else -> null
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to strip image metadata, sending as-is")
            imageFile
        }
    }

    private suspend fun rewrite(source: File, block: (InputStream, OutputStream) -> Unit): File {
        val target = temporaryFileCreator.create()
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output -> block(input, output) }
        }
        return target
    }

    private fun isJpeg(header: ByteArray) = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()

    private fun isPng(header: ByteArray) = header.contentEquals(PNG_SIGNATURE)

    private fun stripJpeg(input: InputStream, output: OutputStream) {
        val data = DataInputStream(input)
        output.write(0xFF)
        output.write(0xD8)
        data.skipBytes(2)
        while (true) {
            var marker = data.read()
            if (marker != 0xFF) throw EOFException("Lost JPEG marker sync")
            while (marker == 0xFF) marker = data.read()
            when (marker) {
                // Start of scan: the rest of the file is entropy-coded image data.
                0xDA -> {
                    output.write(0xFF)
                    output.write(marker)
                    data.copyTo(output)
                    return
                }
                0xD9 -> {
                    output.write(0xFF)
                    output.write(marker)
                    return
                }
                in 0xD0..0xD7, 0x01 -> {
                    output.write(0xFF)
                    output.write(marker)
                }
                else -> {
                    val length = data.readUnsignedShort()
                    val payload = ByteArray(length - 2).also { data.readFully(it) }
                    if (marker != 0xE1 && marker != 0xED) {
                        output.write(0xFF)
                        output.write(marker)
                        output.write(length shr 8)
                        output.write(length and 0xFF)
                        output.write(payload)
                    }
                }
            }
        }
    }

    private fun stripPng(input: InputStream, output: OutputStream) {
        val data = DataInputStream(input)
        data.skipBytes(PNG_SIGNATURE.size)
        output.write(PNG_SIGNATURE)
        while (true) {
            val length = data.readInt()
            val type = ByteArray(4).also { data.readFully(it) }
            val body = ByteArray(length).also { data.readFully(it) }
            val crc = ByteArray(4).also { data.readFully(it) }
            val name = String(type, Charsets.US_ASCII)
            if (name !in DROPPED_PNG_CHUNKS) {
                output.write(length ushr 24)
                output.write(length ushr 16)
                output.write(length ushr 8)
                output.write(length)
                output.write(type)
                output.write(body)
                output.write(crc)
            }
            if (name == "IEND") return
        }
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val DROPPED_PNG_CHUNKS = setOf("tEXt", "iTXt", "zTXt", "eXIf")
    }
}
