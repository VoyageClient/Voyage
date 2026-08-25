/*
 * Copyright 2026 New Vector Ltd.
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

package org.matrix.android.sdk.internal.crypto.secrets

import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.securestorage.EncryptedSecretContent
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageError
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding
import org.matrix.android.sdk.internal.crypto.tools.HkdfSha256
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * The m.secret_storage.v1.aes-hmac-sha2 primitive: AES-CTR-256 + HMAC-SHA-256, with the
 * AES and MAC keys derived from the private key via HKDF-SHA-256 using [info] (the secret
 * or account data event name) as the HKDF info. Used for SSSS secrets and for MSC4483
 * encrypted account data.
 */
internal object AesHmacSha2 {

    fun encrypt(privateKey: ByteArray, info: String, clearData: String): EncryptedSecretContent {
        val pseudoRandomKey = HkdfSha256.deriveSecret(
                privateKey,
                ByteArray(32) { 0.toByte() },
                info.toByteArray(),
                64
        )

        // The first 32 bytes are used as the AES key, and the next 32 bytes are used as the MAC key
        val aesKey = pseudoRandomKey.copyOfRange(0, 32)
        val macKey = pseudoRandomKey.copyOfRange(32, 64)

        val secureRandom = SecureRandom()
        val iv = ByteArray(16)
        secureRandom.nextBytes(iv)

        // clear bit 63 of the salt to stop us hitting the 64-bit counter boundary
        // (which would mean we wouldn't be able to decrypt on Android). The loss
        // of a single bit of salt is a price we have to pay.
        iv[9] = iv[9] and 0x7f

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        val secretKeySpec = SecretKeySpec(aesKey, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        // secret are not that big, just do Final
        val cipherBytes = cipher.doFinal(clearData.toByteArray())
        require(cipherBytes.isNotEmpty())

        val macKeySpec = SecretKeySpec(macKey, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKeySpec)
        val digest = mac.doFinal(cipherBytes)

        return EncryptedSecretContent(
                ciphertext = cipherBytes.toBase64NoPadding(),
                initializationVector = iv.toBase64NoPadding(),
                mac = digest.toBase64NoPadding()
        )
    }

    fun decrypt(privateKey: ByteArray, info: String, cipherContent: EncryptedSecretContent): String {
        val pseudoRandomKey = HkdfSha256.deriveSecret(
                privateKey,
                ByteArray(32) { 0.toByte() },
                info.toByteArray(),
                64
        )

        // The first 32 bytes are used as the AES key, and the next 32 bytes are used as the MAC key
        val aesKey = pseudoRandomKey.copyOfRange(0, 32)
        val macKey = pseudoRandomKey.copyOfRange(32, 64)

        val iv = cipherContent.initializationVector?.fromBase64() ?: ByteArray(16)

        val cipherRawBytes = cipherContent.ciphertext?.fromBase64() ?: throw SharedSecretStorageError.BadCipherText

        // Check Signature
        val macKeySpec = SecretKeySpec(macKey, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKeySpec) }
        val digest = mac.doFinal(cipherRawBytes)

        if (!cipherContent.mac?.fromBase64()?.contentEquals(digest).orFalse()) {
            throw SharedSecretStorageError.BadMac
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        val secretKeySpec = SecretKeySpec(aesKey, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
        // secret are not that big, just do Final
        val decryptedSecret = cipher.doFinal(cipherRawBytes)

        require(decryptedSecret.isNotEmpty())

        return String(decryptedSecret, Charsets.UTF_8)
    }
}
