/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_OLM
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.crosssigning.CrossSigningOlm
import org.matrix.android.sdk.internal.crypto.dehydration.model.DehydratedDeviceData
import org.matrix.android.sdk.internal.crypto.dehydration.model.PutDehydratedDeviceBody
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceKeys
import org.matrix.android.sdk.internal.crypto.model.toRest
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.util.JsonCanonicalizer
import org.matrix.olm.OlmAccount
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("DehydratedDeviceCreator", LoggerTag.CRYPTO)

/**
 * Builds the upload body for a fresh dehydrated device: a brand new Olm account, its keys signed by
 * itself and cross signed by us, plus the account sealed with the dehydration key.
 */
internal class DehydratedDeviceCreator @Inject constructor(
        @UserId private val userId: String,
        private val crossSigningOlm: CrossSigningOlm,
        private val cryptoStore: IMXCryptoStore,
) {

    fun create(dehydrationKey: ByteArray, displayName: String): PutDehydratedDeviceBody {
        val account = OlmAccount()
        try {
            account.generateOneTimeKeys(account.maxOneTimeKeys().toInt() / 2)
            // MSC3814 requires a fallback key, so senders can still reach the device once the
            // one-time keys run out.
            account.generateFallbackKey()

            val identityKeys = account.identityKeys()
            val curve25519Key = identityKeys[OlmAccount.JSON_KEY_IDENTITY_KEY]
                    ?: error("Dehydrated account has no curve25519 key")
            val ed25519Key = identityKeys[OlmAccount.JSON_KEY_FINGER_PRINT_KEY]
                    ?: error("Dehydrated account has no ed25519 key")

            // MSC3814: the device id is the device's own curve25519 key.
            val deviceId = curve25519Key

            return PutDehydratedDeviceBody(
                    deviceId = deviceId,
                    deviceData = seal(account, dehydrationKey),
                    initialDeviceDisplayName = displayName,
                    deviceKeys = signedDeviceKeys(account, deviceId, curve25519Key, ed25519Key),
                    oneTimeKeys = signedOneTimeKeys(account, deviceId),
                    fallbackKeys = signedFallbackKeys(account, deviceId)
            )
        } finally {
            account.releaseAccount()
        }
    }

    private fun seal(account: OlmAccount, dehydrationKey: ByteArray): DehydratedDeviceData {
        val dehydrated = account.dehydrate(dehydrationKey)
        return DehydratedDeviceData(
                algorithm = DehydratedDeviceConstants.ALGORITHM_TO_WRITE,
                devicePickle = String(dehydrated.device, Charsets.US_ASCII),
                nonce = String(dehydrated.nonce, Charsets.US_ASCII)
        )
    }

    private fun signedDeviceKeys(
            account: OlmAccount,
            deviceId: String,
            curve25519Key: String,
            ed25519Key: String
    ): DeviceKeys {
        val device = CryptoDeviceInfo(
                deviceId = deviceId,
                userId = userId,
                algorithms = listOf(MXCRYPTO_ALGORITHM_OLM, MXCRYPTO_ALGORITHM_MEGOLM),
                keys = mapOf(
                        "curve25519:$deviceId" to curve25519Key,
                        "ed25519:$deviceId" to ed25519Key
                )
        )
        val canonicalJson = JsonCanonicalizer.getCanonicalJson(Map::class.java, device.signalableJSONDictionary())

        val signatures = mutableMapOf("ed25519:$deviceId" to account.signMessage(canonicalJson))
        crossSignature(canonicalJson)?.let { (keyId, signature) -> signatures[keyId] = signature }

        return device.toRest().copy(signatures = mapOf(userId to signatures))
    }

    /** MSC3814 requires the dehydrated device to be cross signed. */
    private fun crossSignature(canonicalJson: String): Pair<String, String>? {
        val selfSigningKey = cryptoStore.getMyCrossSigningInfo()?.selfSigningKey()?.unpaddedBase64PublicKey
        val signing = crossSigningOlm.selfSigningPkSigning
        if (selfSigningKey == null || signing == null) {
            Timber.tag(loggerTag.value).w("No self signing key available, the dehydrated device won't be cross signed")
            return null
        }
        return "ed25519:$selfSigningKey" to signing.sign(canonicalJson)
    }

    private fun signedOneTimeKeys(account: OlmAccount, deviceId: String): JsonDict? {
        val keys = account.oneTimeKeys()[OlmAccount.JSON_KEY_ONE_TIME_KEY].orEmpty()
        if (keys.isEmpty()) return null
        return keys.entries.associate { (keyId, key) ->
            "signed_curve25519:$keyId" to signedKey(account, deviceId, key, fallback = false)
        }
    }

    private fun signedFallbackKeys(account: OlmAccount, deviceId: String): JsonDict? {
        val keys = account.fallbackKey()[OlmAccount.JSON_KEY_ONE_TIME_KEY].orEmpty()
        if (keys.isEmpty()) return null
        return keys.entries.associate { (keyId, key) ->
            "signed_curve25519:$keyId" to signedKey(account, deviceId, key, fallback = true)
        }
    }

    private fun signedKey(account: OlmAccount, deviceId: String, key: String, fallback: Boolean): JsonDict {
        val signable = mutableMapOf<String, Any>("key" to key)
        if (fallback) {
            signable["fallback"] = true
        }
        val canonicalJson = JsonCanonicalizer.getCanonicalJson(Map::class.java, signable)
        return signable + mapOf(
                "signatures" to mapOf(userId to mapOf("ed25519:$deviceId" to account.signMessage(canonicalJson)))
        )
    }
}
