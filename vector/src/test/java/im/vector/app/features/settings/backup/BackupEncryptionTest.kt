/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class BackupEncryptionTest {

    private val plain = Random(42).nextBytes(100_003)

    private fun encrypted(passphrase: String = "correct horse"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupEncryption.encrypt(out, passphrase, iterations = 10).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray, passphrase: String = "correct horse"): ByteArray =
            BackupEncryption.decrypt(ByteArrayInputStream(data), passphrase).use { it.readBytes() }

    @Test
    fun `content survives the round trip`() {
        val data = encrypted()

        BackupEncryption.hasEncryptedHeader(data).shouldBeTrue()
        decrypt(data).contentEquals(plain).shouldBeTrue()
    }

    @Test
    fun `a plain zip is not mistaken for an encrypted backup`() {
        BackupEncryption.hasEncryptedHeader(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0, 0, 0)).shouldBeFalse()
    }

    @Test(expected = WrongPassphraseException::class)
    fun `a wrong passphrase is rejected before decrypting`() {
        BackupEncryption.decrypt(ByteArrayInputStream(encrypted()), "wrong")
    }

    @Test
    fun `modified ciphertext is detected`() {
        val data = encrypted()
        data[data.size / 2] = (data[data.size / 2].toInt() xor 1).toByte()

        expectCorrupted { decrypt(data) }
    }

    @Test
    fun `a truncated backup is detected`() {
        val data = encrypted()

        expectCorrupted { decrypt(data.copyOf(data.size - 10)) }
    }

    private fun expectCorrupted(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        (failure is CorruptedBackupException) shouldBeEqualTo true
    }
}
