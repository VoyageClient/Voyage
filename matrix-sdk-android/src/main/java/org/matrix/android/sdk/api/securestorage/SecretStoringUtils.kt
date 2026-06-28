/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

@file:Suppress("DEPRECATION")

package org.matrix.android.sdk.api.securestorage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import org.matrix.android.sdk.api.util.BuildVersionSdkIntProvider
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.security.auth.x500.X500Principal

/**
 * Offers simple methods to securely store secrets in an Android Application.
 * The encryption keys are randomly generated and securely managed by the key store, thus your secrets
 * are safe. You only need to remember a key alias to perform encrypt/decrypt operations.
 *
 * <b>Android M++</b>
 * On android M+, the keystore can generates and store AES keys via API. But below API M this functionality
 * is not available.
 *
 * <b>Android [K-M[</b>
 * For android >=L and <M, we use the keystore to generate and store a private/public key pair. Then for each secret, a
 * random secret key in generated to perform encryption.
 * This secret key is encrypted with the public RSA key and stored with the encrypted secret.
 * In order to decrypt the encrypted secret key will be retrieved then decrypted with the RSA private key.
 *
 * Sample usage:
 * <code>
 *     val secret = "The answer is 42"
 *     val KEncrypted = SecretStoringUtils.securelyStoreString(secret, "myAlias")
 *     //This can be stored anywhere e.g. encoded in b64 and stored in preference for example
 *
 *     //to get back the secret, just call
 *     val kDecrypted = SecretStoringUtils.loadSecureSecret(KEncrypted, "myAlias")
 * </code>
 *
 * You can also just use this utility to store a secret key, and use any encryption algorithm that you want.
 *
 * Important: Keys stored in the keystore can be wiped out (depends of the OS version, like for example if you
 * add a pin or change the schema); So you might and with a useless pile of bytes.
 */
@SuppressLint("NewApi") // keystore APIs are gated at runtime via buildVersionSdkIntProvider, which lint can't follow
class SecretStoringUtils @Inject constructor(
        private val context: Context,
        private val keyStore: KeyStore,
        private val buildVersionSdkIntProvider: BuildVersionSdkIntProvider,
        private val keyNeedsUserAuthentication: Boolean = false,
) {

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val RSA_MODE = "RSA/ECB/PKCS1Padding"

        // Pre-KitKat software fallback: AndroidKeyStore is API 18+, KeyPairGeneratorSpec API 18 and
        // GCMParameterSpec API 19, so below 19 none of the keystore paths work. We instead keep AES +
        // HMAC key material in app-private storage and use AES/CBC with an encrypt-then-MAC envelope.
        private const val AES_CBC_MODE = "AES/CBC/PKCS5Padding"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val SOFT_KEY_DIR = "ss_software_keys"
        private const val SOFT_AES_KEY_SIZE = 32
        private const val SOFT_MAC_KEY_SIZE = 32

        private const val FORMAT_API_M: Byte = 0
        private const val FORMAT_1: Byte = 1
        private const val FORMAT_SOFTWARE: Byte = 2
    }

    private val secureRandom = SecureRandom()

    /**
     * Allows creation of the crypto keys associated witht he [alias] before encrypting some value with it.
     * @return A [KeyStore.Entry] with the keys.
     */
    @SuppressLint("NewApi")
    fun ensureKey(alias: String): KeyStore.Entry {
        return when {
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.M -> {
                getOrGenerateSymmetricKeyForAliasM(alias)
                keyStore.getEntry(alias, null)
            }
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.KITKAT -> {
                getOrGenerateKeyPairForAlias(alias).privateKey
                keyStore.getEntry(alias, null)
            }
            else -> KeyStore.SecretKeyEntry(getOrCreateSoftwareKeyMaterial(alias).first)
        }
    }

    /**
     * Deletes the key associated with the [keyAlias] and logs any [KeyStoreException] that could happen.
     */
    fun safeDeleteKey(keyAlias: String) {
        try {
            keyStore.deleteEntry(keyAlias)
        } catch (e: KeyStoreException) {
            Timber.e(e)
        }
        // The pre-KitKat software fallback stores its key material on disk rather than in the keystore.
        runCatching { softwareKeyFile(keyAlias).delete() }
    }

    /**
     * Encrypt the given secret using the android Keystore.
     * On android >= M, will directly use the keystore to generate a symmetric key
     * On android >= Lollipop and <M, as symmetric key gen is not available, will use an symmetric key generated
     * in the keystore to encrypted a random symmetric key. The encrypted symmetric key is returned
     * in the bytearray (in can be stored anywhere, it is encrypted)
     *
     * The secret is encrypted using the following method: AES/GCM/NoPadding
     */
    @Throws(Exception::class)
    fun securelyStoreBytes(secret: ByteArray, keyAlias: String): ByteArray {
        return when {
            buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.M) -> encryptBytesM(secret, keyAlias)
            buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.KITKAT) -> encryptBytes(secret, keyAlias)
            else -> encryptBytesSoftware(secret, keyAlias)
        }
    }

    /**
     * Decrypt a secret that was encrypted by [securelyStoreBytes].
     */
    @SuppressLint("NewApi")
    @Throws(Exception::class)
    fun loadSecureSecretBytes(encrypted: ByteArray, keyAlias: String): ByteArray {
        encrypted.inputStream().use { inputStream ->
            // First get the format
            return when (val format = inputStream.read().toByte()) {
                FORMAT_API_M -> decryptBytesM(inputStream, keyAlias)
                FORMAT_1 -> decryptBytes(inputStream, keyAlias)
                FORMAT_SOFTWARE -> decryptBytesSoftware(inputStream, keyAlias)
                else -> throw IllegalArgumentException("Unknown format $format")
            }
        }
    }

    fun securelyStoreObject(any: Any, keyAlias: String, output: OutputStream) {
        when {
            buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.M) -> saveSecureObjectM(keyAlias, output, any)
            buildVersionSdkIntProvider.isAtLeast(Build.VERSION_CODES.KITKAT) -> saveSecureObject(keyAlias, output, any)
            else -> saveSecureObjectSoftware(keyAlias, output, any)
        }
    }

    @SuppressLint("NewApi")
    fun <T> loadSecureSecret(inputStream: InputStream, keyAlias: String): T? {
        // First get the format
        return when (val format = inputStream.read().toByte()) {
            FORMAT_API_M -> loadSecureObjectM(keyAlias, inputStream)
            FORMAT_1 -> loadSecureObject(keyAlias, inputStream)
            FORMAT_SOFTWARE -> loadSecureObjectSoftware(keyAlias, inputStream)
            else -> throw IllegalArgumentException("Unknown format $format")
        }
    }

    fun getEncryptCipher(alias: String): Cipher {
        val key = when (val keyEntry = ensureKey(alias)) {
            is KeyStore.SecretKeyEntry -> keyEntry.secretKey
            is KeyStore.PrivateKeyEntry -> keyEntry.certificate.publicKey
            else -> throw IllegalStateException("Unknown KeyEntry type.")
        }
        val cipherAlgorithm = when {
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.M -> AES_MODE
            buildVersionSdkIntProvider.get() >= Build.VERSION_CODES.KITKAT -> RSA_MODE
            else -> AES_CBC_MODE
        }
        val cipher = Cipher.getInstance(cipherAlgorithm)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getOrGenerateSymmetricKeyForAliasM(alias: String): SecretKey {
        val secretKeyEntry = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey
        if (secretKeyEntry == null) {
            // we generate it
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keyGenSpec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(128)
                    .setUserAuthenticationRequired(keyNeedsUserAuthentication)
                    .apply {
                        if (keyNeedsUserAuthentication) {
                            buildVersionSdkIntProvider.whenAtLeast(Build.VERSION_CODES.N) {
                                setInvalidatedByBiometricEnrollment(true)
                            }
                            buildVersionSdkIntProvider.whenAtLeast(Build.VERSION_CODES.P) {
                                setUnlockedDeviceRequired(true)
                            }
                        }
                    }
                    .build()
            generator.init(keyGenSpec)
            return generator.generateKey()
        }
        return secretKeyEntry
    }

    /*
    Symmetric Key Generation is only available in M, so before M the idea is to:
        - Generate a pair of RSA keys;
        - Generate a random AES key;
        - Encrypt the AES key using the RSA public key;
        - Store the encrypted AES
     Generate a key pair for encryption
     */
    private fun getOrGenerateKeyPairForAlias(alias: String): KeyStore.PrivateKeyEntry {
        val privateKeyEntry = (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)

        if (privateKeyEntry != null) return privateKeyEntry

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        end.add(Calendar.YEAR, 30)

        val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(alias)
                .setSubject(X500Principal("CN=$alias"))
                .setSerialNumber(BigInteger.TEN)
                // .setEncryptionRequired() requires that the phone has a pin/schema
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
        KeyPairGenerator.getInstance("RSA" /*KeyProperties.KEY_ALGORITHM_RSA*/, ANDROID_KEY_STORE).run {
            initialize(spec)
            generateKeyPair()
        }
        return (keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptBytesM(byteArray: ByteArray, keyAlias: String): ByteArray {
        val cipher = getEncryptCipher(keyAlias)
        val iv = cipher.iv
        // we happen the iv to the final result
        val encryptedBytes: ByteArray = cipher.doFinal(byteArray)
        return formatMMake(iv, encryptedBytes)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun decryptBytesM(inputStream: InputStream, keyAlias: String): ByteArray {
        val (iv, encryptedText) = formatMExtract(inputStream)

        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(encryptedText)
    }

    private fun encryptBytes(byteArray: ByteArray, keyAlias: String): ByteArray {
        // we generate a random symmetric key
        val key = ByteArray(16)
        secureRandom.nextBytes(key)
        val sKey = SecretKeySpec(key, "AES")

        // we encrypt this key thanks to the key store
        val encryptedKey = rsaEncrypt(keyAlias, key)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, sKey)
        val iv = cipher.iv
        val encryptedBytes: ByteArray = cipher.doFinal(byteArray)

        return format1Make(encryptedKey, iv, encryptedBytes)
    }

    private fun decryptBytes(inputStream: InputStream, keyAlias: String): ByteArray {
        val (encryptedKey, iv, encrypted) = format1Extract(inputStream)

        // we need to decrypt the key
        val sKeyBytes = rsaDecrypt(keyAlias, ByteArrayInputStream(encryptedKey))
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKeyBytes, "AES"), spec)

        return cipher.doFinal(encrypted)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(IOException::class)
    private fun saveSecureObjectM(keyAlias: String, output: OutputStream, writeObject: Any) {
        val cipher = getEncryptCipher(keyAlias)
        val iv = cipher.iv

        val bos1 = ByteArrayOutputStream()
        ObjectOutputStream(bos1).use {
            it.writeObject(writeObject)
        }
        // Have to do it like that if i encapsulate the output stream, the cipher could fail saying reuse IV
        val doFinal = cipher.doFinal(bos1.toByteArray())
        output.write(FORMAT_API_M.toInt())
        output.write(iv.size)
        output.write(iv)
        output.write(doFinal)
    }

    private fun saveSecureObject(keyAlias: String, output: OutputStream, writeObject: Any) {
        // we generate a random symmetric key
        val key = ByteArray(16)
        secureRandom.nextBytes(key)
        val sKey = SecretKeySpec(key, "AES")

        // we encrypt this key thanks to the key store
        val encryptedKey = rsaEncrypt(keyAlias, key)

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, sKey)
        val iv = cipher.iv

        val bos1 = ByteArrayOutputStream()
        val cos = CipherOutputStream(bos1, cipher)
        ObjectOutputStream(cos).use {
            it.writeObject(writeObject)
        }

        output.write(FORMAT_1.toInt())
        output.write((encryptedKey.size and 0xFF00).shr(8))
        output.write(encryptedKey.size and 0x00FF)
        output.write(encryptedKey)
        output.write(iv.size)
        output.write(iv)
        output.write(bos1.toByteArray())
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @Throws(IOException::class)
    private fun <T> loadSecureObjectM(keyAlias: String, inputStream: InputStream): T? {
        val secretKey = getOrGenerateSymmetricKeyForAliasM(keyAlias)

        val ivSize = inputStream.read()
        val iv = ByteArray(ivSize)
        inputStream.read(iv, 0, ivSize)
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        CipherInputStream(inputStream, cipher).use { cipherInputStream ->
            ObjectInputStream(cipherInputStream).use {
                val readObject = it.readObject()
                @Suppress("UNCHECKED_CAST")
                return readObject as? T
            }
        }
    }

    @Throws(IOException::class)
    private fun <T> loadSecureObject(keyAlias: String, inputStream: InputStream): T? {
        val (encryptedKey, iv, encrypted) = format1Extract(inputStream)

        // we need to decrypt the key
        val sKeyBytes = rsaDecrypt(keyAlias, ByteArrayInputStream(encryptedKey))
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sKeyBytes, "AES"), spec)

        val encIS = ByteArrayInputStream(encrypted)

        CipherInputStream(encIS, cipher).use { cipherInputStream ->
            ObjectInputStream(cipherInputStream).use {
                val readObject = it.readObject()
                @Suppress("UNCHECKED_CAST")
                return readObject as? T
            }
        }
    }

    @Throws(Exception::class)
    private fun rsaEncrypt(alias: String, secret: ByteArray): ByteArray {
        // Encrypt the text
        val inputCipher = getEncryptCipher(alias)

        val outputStream = ByteArrayOutputStream()
        CipherOutputStream(outputStream, inputCipher).use {
            it.write(secret)
        }

        return outputStream.toByteArray()
    }

    @Throws(Exception::class)
    private fun rsaDecrypt(alias: String, encrypted: InputStream): ByteArray {
        val privateKeyEntry = getOrGenerateKeyPairForAlias(alias)
        val output = Cipher.getInstance(RSA_MODE)
        output.init(Cipher.DECRYPT_MODE, privateKeyEntry.privateKey)

        return CipherInputStream(encrypted, output).use { it.readBytes() }
    }

    // --- Pre-KitKat software fallback (no AndroidKeyStore / GCMParameterSpec available) ---

    // AES + HMAC key material kept in app-private storage. No hardware backing is possible below API 18,
    // so this is software-only; the file is readable only by this app's uid (and root).
    private fun getOrCreateSoftwareKeyMaterial(alias: String): Pair<SecretKey, SecretKey> {
        val file = softwareKeyFile(alias)
        val existing = if (file.exists()) file.readBytes() else null
        if (existing != null && existing.size == SOFT_AES_KEY_SIZE + SOFT_MAC_KEY_SIZE) {
            val aes = SecretKeySpec(existing, 0, SOFT_AES_KEY_SIZE, "AES")
            val mac = SecretKeySpec(existing, SOFT_AES_KEY_SIZE, SOFT_MAC_KEY_SIZE, HMAC_ALGORITHM)
            return aes to mac
        }
        val material = ByteArray(SOFT_AES_KEY_SIZE + SOFT_MAC_KEY_SIZE)
        secureRandom.nextBytes(material)
        file.parentFile?.mkdirs()
        file.writeBytes(material)
        val aes = SecretKeySpec(material, 0, SOFT_AES_KEY_SIZE, "AES")
        val mac = SecretKeySpec(material, SOFT_AES_KEY_SIZE, SOFT_MAC_KEY_SIZE, HMAC_ALGORITHM)
        return aes to mac
    }

    private fun softwareKeyFile(alias: String): File {
        val safeAlias = alias.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(File(context.filesDir, SOFT_KEY_DIR), safeAlias)
    }

    private fun encryptBytesSoftware(byteArray: ByteArray, keyAlias: String): ByteArray {
        val (aesKey, macKey) = getOrCreateSoftwareKeyMaterial(keyAlias)
        val cipher = Cipher.getInstance(AES_CBC_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(byteArray)
        val mac = hmac(macKey, iv, encrypted)

        val bos = ByteArrayOutputStream()
        bos.write(FORMAT_SOFTWARE.toInt())
        bos.write(iv.size)
        bos.write(iv)
        bos.write(mac.size)
        bos.write(mac)
        bos.write(encrypted)
        return bos.toByteArray()
    }

    // The leading format byte has already been consumed by the caller.
    private fun decryptBytesSoftware(inputStream: InputStream, keyAlias: String): ByteArray {
        val (aesKey, macKey) = getOrCreateSoftwareKeyMaterial(keyAlias)
        val iv = ByteArray(inputStream.read()).also { readFully(inputStream, it) }
        val mac = ByteArray(inputStream.read()).also { readFully(inputStream, it) }
        val encrypted = inputStream.readBytes()
        if (!MessageDigest.isEqual(mac, hmac(macKey, iv, encrypted))) {
            throw SecurityException("Secure storage MAC mismatch")
        }
        val cipher = Cipher.getInstance(AES_CBC_MODE)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
        return cipher.doFinal(encrypted)
    }

    private fun saveSecureObjectSoftware(keyAlias: String, output: OutputStream, writeObject: Any) {
        val bos = ByteArrayOutputStream()
        ObjectOutputStream(bos).use { it.writeObject(writeObject) }
        output.write(encryptBytesSoftware(bos.toByteArray(), keyAlias))
    }

    private fun <T> loadSecureObjectSoftware(keyAlias: String, inputStream: InputStream): T? {
        val plain = decryptBytesSoftware(inputStream, keyAlias)
        ObjectInputStream(ByteArrayInputStream(plain)).use {
            val readObject = it.readObject()
            @Suppress("UNCHECKED_CAST")
            return readObject as? T
        }
    }

    private fun hmac(macKey: SecretKey, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(macKey)
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw IOException("Unexpected end of stream")
            offset += read
        }
    }

    private fun formatMExtract(bis: InputStream): Pair<ByteArray, ByteArray> {
        val ivSize = bis.read()
        val iv = ByteArray(ivSize)
        bis.read(iv, 0, ivSize)

        val encrypted = bis.readBytes()
        return Pair(iv, encrypted)
    }

    private fun formatMMake(iv: ByteArray, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(2 + iv.size + data.size)
        bos.write(FORMAT_API_M.toInt())
        bos.write(iv.size)
        bos.write(iv)
        bos.write(data)
        return bos.toByteArray()
    }

    private fun format1Extract(bis: InputStream): Triple<ByteArray, ByteArray, ByteArray> {
        val keySizeBig = bis.read()
        val keySizeLow = bis.read()
        val encryptedKeySize = keySizeBig.shl(8) + keySizeLow
        val encryptedKey = ByteArray(encryptedKeySize)
        bis.read(encryptedKey)

        val ivSize = bis.read()
        val iv = ByteArray(ivSize)
        bis.read(iv)

        val encrypted = bis.readBytes()
        return Triple(encryptedKey, iv, encrypted)
    }

    private fun format1Make(encryptedKey: ByteArray, iv: ByteArray, encryptedBytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(4 + encryptedKey.size + iv.size + encryptedBytes.size)
        bos.write(FORMAT_1.toInt())
        bos.write((encryptedKey.size and 0xFF00).shr(8))
        bos.write(encryptedKey.size and 0x00FF)
        bos.write(encryptedKey)
        bos.write(iv.size)
        bos.write(iv)
        bos.write(encryptedBytes)

        return bos.toByteArray()
    }
}
