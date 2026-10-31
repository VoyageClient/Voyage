/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import com.squareup.moshi.JsonReader
import okio.Buffer
import okio.buffer
import okio.source
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding
import org.matrix.android.sdk.internal.crypto.tools.HkdfSha256
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val loggerTag = LoggerTag("FluffyChatBackup", LoggerTag.CRYPTO)

/**
 * Reads the megolm sessions out of a FluffyChat `.fluffybackup`: an unencrypted JSON dump of the whole matrix-dart-sdk
 * database, whose inbound group sessions are pickled (vodozemac JSON or legacy libolm binary) with the user ID as key.
 */
internal object FluffyChatBackup {
    private const val CLIENT_BOX = "box_client"
    private const val INBOUND_SESSIONS_BOX = "box_inbound_group_session"
    private const val PICKLE_MAC_LENGTH = 8
    private const val RATCHET_LENGTH = 128
    private const val ED25519_KEY_LENGTH = 32
    private const val EXPORT_FORMAT_VERSION: Byte = 1

    internal class UnpickledSession(val sessionId: String, val exportedKey: String)

    // Megolm key exports start with an ASCII armor header, never with a JSON object.
    fun isFluffyChatBackup(data: ByteArray): Boolean =
            data.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()

    fun readMegolmSessions(data: ByteArray, fallbackUserId: String): List<MegolmSessionData> {
        var dumpUserId: String? = null
        val entries = mutableListOf<Map<*, *>>()
        JsonReader.of(data.inputStream().source().buffer()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    CLIENT_BOX -> dumpUserId = (reader.readJsonValue() as? Map<*, *>)?.get("user_id") as? String
                    INBOUND_SESSIONS_BOX -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            reader.skipName()
                            (reader.readJsonValue() as? Map<*, *>)?.let(entries::add)
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        val userId = dumpUserId ?: fallbackUserId
        val sessions = entries.mapNotNull { toSessionData(it, userId) }
        if (sessions.size != entries.size) {
            Timber.tag(loggerTag.value).w("Skipped ${entries.size - sessions.size} of ${entries.size} sessions")
        }
        return sessions
    }

    private fun toSessionData(entry: Map<*, *>, userId: String): MegolmSessionData? {
        val sessionId = entry["session_id"] as? String ?: return null
        val pickle = entry["pickle"] as? String ?: return null
        val content = jsonObject(entry["content"])
        val algorithm = content["algorithm"] as? String ?: MXCRYPTO_ALGORITHM_MEGOLM
        if (algorithm != MXCRYPTO_ALGORITHM_MEGOLM) return null
        val session = try {
            unpickleInboundGroupSession(pickle, userId)
        } catch (failure: Exception) {
            Timber.tag(loggerTag.value).w(failure, "Unable to unpickle session $sessionId")
            return null
        }
        if (session.sessionId != sessionId) return null
        val claimedKeys = jsonObject(entry["sender_claimed_keys"])
                .entries
                .mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }
                .toMap()
        return MegolmSessionData(
                algorithm = algorithm,
                sessionId = sessionId,
                senderKey = entry["sender_key"] as? String,
                roomId = entry["room_id"] as? String,
                sessionKey = session.exportedKey,
                senderClaimedKeys = claimedKeys,
                senderClaimedEd25519Key = claimedKeys["ed25519"],
                forwardingCurve25519KeyChain = (content["forwarding_curve25519_key_chain"] as? List<*>)?.filterIsInstance<String>(),
                sharedHistory = content["org.matrix.msc3061.shared_history"] == true,
        )
    }

    // The Dart SDK stores these as JSON-encoded strings, but older rows may hold the object itself.
    private fun jsonObject(value: Any?): Map<*, *> = when (value) {
        is Map<*, *> -> value
        is String -> runCatching { JsonReader.of(Buffer().writeUtf8(value)).readJsonValue() as? Map<*, *> }.getOrNull().orEmpty()
        else -> emptyMap<Any, Any>()
    }

    fun unpickleInboundGroupSession(pickle: String, userId: String): UnpickledSession {
        val decoded = pickle.fromBase64()
        require(decoded.size > PICKLE_MAC_LENGTH) { "Pickle too short" }
        val plaintext = pickleKeyCandidates(userId).firstNotNullOfOrNull { decryptPickle(decoded, it) }
                ?: throw IllegalArgumentException("Pickle MAC mismatch")
        val (counter, ratchet, signingKey) = if (plaintext.first() == '{'.code.toByte()) {
            parseVodozemacPickle(plaintext)
        } else {
            parseLibolmPickle(plaintext)
        }
        val exported = ByteBuffer.allocate(1 + 4 + RATCHET_LENGTH + ED25519_KEY_LENGTH)
                .put(EXPORT_FORMAT_VERSION)
                .putInt(counter)
                .put(ratchet)
                .put(signingKey)
                .array()
        return UnpickledSession(signingKey.toBase64NoPadding(), exported.toBase64NoPadding())
    }

    // vodozemac pickles use Dart's String.toPickleKey() (UTF-16 code units cut to bytes, zero-padded or
    // truncated to 32), legacy libolm pickles the UTF-8 user ID as is.
    private fun pickleKeyCandidates(userId: String): List<ByteArray> {
        val vodozemacKey = ByteArray(32)
        userId.take(32).forEachIndexed { index, char -> vodozemacKey[index] = char.code.toByte() }
        return listOf(vodozemacKey, userId.toByteArray(Charsets.UTF_8))
    }

    private fun decryptPickle(decoded: ByteArray, pickleKey: ByteArray): ByteArray? {
        val keys = HkdfSha256.deriveSecret(pickleKey, null, "Pickle".toByteArray(Charsets.US_ASCII), 80)
        val ciphertext = decoded.copyOfRange(0, decoded.size - PICKLE_MAC_LENGTH)
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keys.copyOfRange(32, 64), "HmacSHA256"))
            doFinal(ciphertext)
        }
        if (!MessageDigest.isEqual(mac.copyOf(PICKLE_MAC_LENGTH), decoded.copyOfRange(ciphertext.size, decoded.size))) return null
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.copyOfRange(0, 32), "AES"), IvParameterSpec(keys.copyOfRange(64, 80)))
            doFinal(ciphertext)
        }
    }

    // InboundGroupSessionPickle: {"initial_ratchet":{"inner":[128 bytes],"counter":n},"signing_key":[32 bytes],...}
    private fun parseVodozemacPickle(plaintext: ByteArray): Triple<Int, ByteArray, ByteArray> {
        val json = JsonReader.of(Buffer().write(plaintext)).readJsonValue() as Map<*, *>
        val ratchet = json["initial_ratchet"] as Map<*, *>
        val counter = (ratchet["counter"] as Number).toLong().toInt()
        return Triple(counter, byteArray(ratchet["inner"], RATCHET_LENGTH), byteArray(json["signing_key"], ED25519_KEY_LENGTH))
    }

    private fun byteArray(value: Any?, length: Int): ByteArray {
        val list = value as List<*>
        require(list.size == length) { "Expected $length bytes, got ${list.size}" }
        return ByteArray(length) { (list[it] as Number).toInt().toByte() }
    }

    // version u32, initial ratchet (128 bytes + u32 counter), latest ratchet (same), ed25519 signing key, [v2] verified flag
    private fun parseLibolmPickle(plaintext: ByteArray): Triple<Int, ByteArray, ByteArray> {
        val buffer = ByteBuffer.wrap(plaintext)
        val version = buffer.int
        require(version == 1 || version == 2) { "Unknown libolm pickle version $version" }
        val ratchet = ByteArray(RATCHET_LENGTH).also { buffer.get(it) }
        val counter = buffer.int
        buffer.position(buffer.position() + RATCHET_LENGTH + 4)
        val signingKey = ByteArray(ED25519_KEY_LENGTH).also { buffer.get(it) }
        return Triple(counter, ratchet, signingKey)
    }
}
