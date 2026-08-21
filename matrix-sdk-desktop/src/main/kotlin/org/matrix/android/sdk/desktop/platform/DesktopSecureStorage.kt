/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.desktop.platform

import org.matrix.android.sdk.internal.platform.SecureStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * File-backed [SecureStorage] using AES/GCM. A single master key is generated once and persisted to
 * [keyFile] (owner-only permissions); the alias is bound in as GCM associated-data so bytes encrypted
 * under one alias can only be decrypted with that same alias. Android uses the hardware Keystore;
 * the desktop equivalent (OS keyring) is left to the consumer.
 */
internal class DesktopSecureStorage(keyFile: File) : SecureStorage {

    private val masterKey: SecretKeySpec = run {
        val raw = if (keyFile.exists()) {
            keyFile.readBytes()
        } else {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded.also { generated ->
                keyFile.parentFile?.mkdirs()
                createOwnerOnly(keyFile)
                keyFile.writeBytes(generated)
            }
        }
        SecretKeySpec(raw, "AES")
    }

    override fun encryptBytes(bytes: ByteArray, alias: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        cipher.updateAAD(alias.toByteArray())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(bytes)
        // Prefix the 12-byte GCM IV so decrypt can recover it.
        return iv + encrypted
    }

    override fun decryptBytes(encrypted: ByteArray, alias: String): ByteArray {
        val iv = encrypted.copyOfRange(0, GCM_IV_LENGTH)
        val body = encrypted.copyOfRange(GCM_IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(alias.toByteArray())
        return cipher.doFinal(body)
    }

    override fun deleteKey(alias: String) {
        // Single master key model: nothing per-alias to delete.
    }

    private fun createOwnerOnly(file: File) {
        val path = file.toPath()
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (e: UnsupportedOperationException) {
            Files.createFile(path)
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    companion object {
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
