/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo

/** Formatting for the E2EDBG traces; only ever called from inside a DebugLog lambda. */
internal object E2eDebug {

    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun device(device: CryptoDeviceInfo): String = "${device.userId}|${device.deviceId}"

    fun devices(devices: Collection<CryptoDeviceInfo>): String =
            if (devices.isEmpty()) "[]" else devices.joinToString(prefix = "[", postfix = "]") { device(it) }

    fun devicesByUser(devicesByUser: Map<String, List<CryptoDeviceInfo>>): String = devices(devicesByUser.values.flatten())

    fun error(throwable: Throwable?): String = when (throwable) {
        null -> "null"
        is MXCryptoError.Base -> "${throwable.errorType}(${throwable.technicalMessage})"
        is MXCryptoError.OlmError -> "OlmError(${throwable.olmException.message})"
        else -> "${throwable.javaClass.simpleName}(${throwable.message})"
    }

    /**
     * The message index a Megolm ciphertext was encrypted at, read from its header (version byte 3, then
     * field 1 as a varint), so a missing-key failure can be told apart from a key that starts too late.
     */
    fun megolmMessageIndex(ciphertext: String?): Long? {
        val bytes = ciphertext?.let { decodeBase64Prefix(it, 16) } ?: return null
        if (bytes.size < 3 || bytes[0] != 3.toByte() || bytes[1] != 0x08.toByte()) return null
        var index = 0L
        var shift = 0
        for (i in 2 until minOf(bytes.size, 7)) {
            val b = bytes[i].toInt() and 0xFF
            index = index or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return index
            shift += 7
        }
        return null
    }

    private fun decodeBase64Prefix(text: String, maxChars: Int): ByteArray? {
        val length = minOf(maxChars, text.length) / 4 * 4
        val out = ByteArray(length / 4 * 3)
        var o = 0
        for (i in 0 until length step 4) {
            var value = 0
            for (j in 0 until 4) {
                val digit = BASE64.indexOf(text[i + j])
                if (digit < 0) return null
                value = (value shl 6) or digit
            }
            out[o++] = (value shr 16).toByte()
            out[o++] = (value shr 8).toByte()
            out[o++] = value.toByte()
        }
        return out
    }
}
