/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WrongPassphraseException : IOException("Wrong passphrase")

class CorruptedBackupException : IOException("Backup is damaged or was modified")

/**
 * Passphrase encryption for database backups: AES-256-CBC, then HMAC-SHA256 over the header and ciphertext,
 * with keys derived by PBKDF2-HMAC-SHA256. Only primitives present since API 1 are used, so it runs on ICS.
 *
 * Layout: magic, version, iterations, salt, IV, passphrase verifier, ciphertext, MAC.
 */
object BackupEncryption {

    private val MAGIC = "VOYAGEBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val ITERATIONS = 100_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 16
    private const val VERIFIER_SIZE = 16
    private const val MAC_SIZE = 32
    private const val HMAC = "HmacSHA256"
    private val HEADER_SIZE = MAGIC.size + 1 + 4 + SALT_SIZE + IV_SIZE + VERIFIER_SIZE

    fun hasEncryptedHeader(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    val headerPeekSize: Int get() = MAGIC.size

    fun encrypt(output: OutputStream, passphrase: String, iterations: Int = ITERATIONS): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val keys = Keys.derive(passphrase, salt, iterations)

        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeInt(iterations)
            write(salt)
            write(iv)
            write(keys.verifier)
        }
        val mac = keys.newMac()
        val headerBytes = header.toByteArray()
        output.write(headerBytes)
        mac.update(headerBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
        }
        return CipherOutputStream(MacAppendingOutputStream(output, mac), cipher)
    }

    /** @throws WrongPassphraseException straight away, before any content is read. */
    fun decrypt(input: InputStream, passphrase: String): InputStream {
        val headerBytes = ByteArray(HEADER_SIZE).also { DataInputStream(input).readFully(it) }
        val header = DataInputStream(ByteArrayInputStream(headerBytes))
        val magic = ByteArray(MAGIC.size).also { header.readFully(it) }
        if (!magic.contentEquals(MAGIC) || header.readUnsignedByte() != VERSION) throw CorruptedBackupException()
        val iterations = header.readInt()
        if (iterations <= 0) throw CorruptedBackupException()
        val salt = ByteArray(SALT_SIZE).also { header.readFully(it) }
        val iv = ByteArray(IV_SIZE).also { header.readFully(it) }
        val verifier = ByteArray(VERIFIER_SIZE).also { header.readFully(it) }

        val keys = Keys.derive(passphrase, salt, iterations)
        if (!MessageDigest.isEqual(verifier, keys.verifier)) throw WrongPassphraseException()

        val mac = keys.newMac()
        mac.update(headerBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encryption, "AES"), IvParameterSpec(iv))
        }
        return CipherInputStream(MacVerifyingInputStream(input, mac), cipher)
    }

    private class Keys(val encryption: ByteArray, private val authentication: ByteArray, val verifier: ByteArray) {
        fun newMac(): Mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(authentication, HMAC)) }

        companion object {
            fun derive(passphrase: String, salt: ByteArray, iterations: Int): Keys {
                val master = pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations)
                fun expand(label: String) = Mac.getInstance(HMAC).run {
                    init(SecretKeySpec(master, HMAC))
                    doFinal(label.toByteArray(Charsets.US_ASCII))
                }
                return Keys(expand("encryption"), expand("authentication"), expand("verifier").copyOf(VERIFIER_SIZE))
            }

            // PBKDF2WithHmacSHA256 only reached the platform at API 26; one 32-byte block by hand instead.
            private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
                val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(password, HMAC)) }
                mac.update(salt)
                var block = mac.doFinal(byteArrayOf(0, 0, 0, 1))
                val result = block.copyOf()
                repeat(iterations - 1) {
                    block = mac.doFinal(block)
                    for (i in result.indices) result[i] = (result[i].toInt() xor block[i].toInt()).toByte()
                }
                return result
            }
        }
    }

    /** Passes ciphertext through to [output] while MACing it, and appends the MAC on close. */
    private class MacAppendingOutputStream(private val output: OutputStream, private val mac: Mac) : OutputStream() {
        override fun write(b: Int) {
            mac.update(b.toByte())
            output.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            mac.update(b, off, len)
            output.write(b, off, len)
        }

        override fun flush() = output.flush()

        override fun close() {
            output.use { it.write(mac.doFinal()) }
        }
    }

    /** Releases all but the trailing MAC, and checks it once the ciphertext ends. */
    private class MacVerifyingInputStream(private val input: InputStream, private val mac: Mac) : InputStream() {
        private val held = ByteArray(MAC_SIZE)
        private var buffer = ByteArray(0)
        private var heldCount = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            if (len == 0) return 0
            if (buffer.size < len + MAC_SIZE) buffer = ByteArray(len + MAC_SIZE)
            System.arraycopy(held, 0, buffer, 0, heldCount)
            var total = heldCount
            while (total <= MAC_SIZE) {
                val read = input.read(buffer, total, len + MAC_SIZE - total)
                if (read < 0) {
                    finished = true
                    val expected = mac.doFinal()
                    if (total != MAC_SIZE || !MessageDigest.isEqual(expected, buffer.copyOf(MAC_SIZE))) throw CorruptedBackupException()
                    return -1
                }
                total += read
            }
            val released = total - MAC_SIZE
            System.arraycopy(buffer, 0, b, off, released)
            mac.update(buffer, 0, released)
            System.arraycopy(buffer, released, held, 0, MAC_SIZE)
            heldCount = MAC_SIZE
            return released
        }

        override fun close() = input.close()
    }
}
