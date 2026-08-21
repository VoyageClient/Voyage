/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.platform

import org.matrix.android.sdk.api.securestorage.SecureStorageService
import org.matrix.android.sdk.internal.platform.SecureStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream

/** Java-serialises the object and wraps it with the platform [SecureStorage] (length-prefixed). */
internal class DesktopSecureStorageService(private val secureStorage: SecureStorage) : SecureStorageService {

    override fun securelyStoreObject(any: Any, keyAlias: String, outputStream: OutputStream) {
        val plain = ByteArrayOutputStream().also { buffer -> ObjectOutputStream(buffer).use { it.writeObject(any) } }.toByteArray()
        val encrypted = secureStorage.encryptBytes(plain, keyAlias)
        DataOutputStream(outputStream).apply {
            writeInt(encrypted.size)
            write(encrypted)
            flush()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> loadSecureSecret(inputStream: InputStream, keyAlias: String): T? {
        val input = DataInputStream(inputStream)
        val encrypted = ByteArray(input.readInt()).also { input.readFully(it) }
        val plain = secureStorage.decryptBytes(encrypted, keyAlias)
        return ObjectInputStream(ByteArrayInputStream(plain)).use { it.readObject() as T }
    }
}
