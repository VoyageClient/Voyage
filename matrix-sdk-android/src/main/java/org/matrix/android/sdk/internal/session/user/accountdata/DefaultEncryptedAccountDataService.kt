/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import com.squareup.moshi.Types
import dagger.Lazy
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.accountdata.EncryptedAccountDataService
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.securestorage.EncryptedSecretContent
import org.matrix.android.sdk.api.session.securestorage.KeyRef
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageError
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageService
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding
import org.matrix.android.sdk.internal.crypto.secrets.AesHmacSha2
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject

private const val ADK_STORE_KEY = "adk"
private const val NEEDS_4S_UPLOAD_KEY = "adkNeeds4sUpload"

@SessionScope
internal class DefaultEncryptedAccountDataService @Inject constructor(
        @SessionId sessionId: String,
        storeFactory: KeyValueStoreFactory,
        private val accountDataService: Lazy<SessionAccountDataService>,
        private val sharedSecretStorageService: Lazy<SharedSecretStorageService>,
) : EncryptedAccountDataService {

    private val store = storeFactory.create("EncryptedAccountData_$sessionId")

    private val contentAdapter = MoshiProvider.providesMoshi()
            .adapter<Content>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    override fun hasAccountDataKey(): Boolean = getAccountDataKey() != null

    override fun getAccountDataKey(): String? = store.getString(ADK_STORE_KEY, null)

    override fun setAccountDataKey(adkBase64: String) = store.putString(ADK_STORE_KEY, adkBase64)

    override fun clearAccountDataKey() = store.remove(ADK_STORE_KEY)

    override fun generateAccountDataKey(): String {
        return ByteArray(32).also { SecureRandom().nextBytes(it) }.toBase64NoPadding()
    }

    override suspend fun ensureAccountDataKey(): Boolean {
        maybeUploadLocalAdkTo4S()
        if (hasAccountDataKey()) return true
        val cached4sKey = sharedSecretStorageService.get().getCachedKeySpec()
        val existingSecretName = EncryptedAccountDataService.ADK_SECRET_NAMES
                .firstOrNull { accountDataService.get().getUserAccountDataEvent(it) != null }
        return when {
            cached4sKey != null -> try {
                val (keyId, keySpec) = cached4sKey
                if (existingSecretName != null) {
                    setAccountDataKey(sharedSecretStorageService.get().getSecret(existingSecretName, keyId, keySpec))
                } else {
                    val adk = generateAccountDataKey()
                    EncryptedAccountDataService.ADK_SECRET_NAMES.forEach {
                        sharedSecretStorageService.get().storeSecret(it, adk, listOf(KeyRef(keyId, keySpec)))
                    }
                    setAccountDataKey(adk)
                }
                true
            } catch (failure: Throwable) {
                Timber.w(failure, "## MSC4483: failed to silently acquire the ADK")
                false
            }
            // No ADK exists anywhere and 4S cannot be written silently: create a device-local one
            // now, and upload it to secret storage whenever the recovery key is next entered.
            existingSecretName == null -> {
                setAccountDataKey(generateAccountDataKey())
                store.putBoolean(NEEDS_4S_UPLOAD_KEY, true)
                true
            }
            // An ADK is stored in 4S but reading it needs the user's recovery key
            else -> false
        }
    }

    private suspend fun maybeUploadLocalAdkTo4S() {
        if (!store.getBoolean(NEEDS_4S_UPLOAD_KEY, false)) return
        val adk = getAccountDataKey() ?: return
        val (keyId, keySpec) = sharedSecretStorageService.get().getCachedKeySpec() ?: return
        try {
            val existingSecretName = EncryptedAccountDataService.ADK_SECRET_NAMES
                    .firstOrNull { accountDataService.get().getUserAccountDataEvent(it) != null }
            if (existingSecretName == null) {
                EncryptedAccountDataService.ADK_SECRET_NAMES.forEach {
                    sharedSecretStorageService.get().storeSecret(it, adk, listOf(KeyRef(keyId, keySpec)))
                }
            } else {
                Timber.w("## MSC4483: another client stored an ADK first, keeping the local one without uploading")
            }
            store.remove(NEEDS_4S_UPLOAD_KEY)
        } catch (failure: Throwable) {
            Timber.w(failure, "## MSC4483: failed to upload the local ADK to 4S")
        }
    }

    override fun isEncrypted(content: Content?): Boolean {
        // SSSS secrets also live under "encrypted" but are keyed by key id; an MSC4483
        // payload has the cipher fields directly.
        val encrypted = content?.get("encrypted") as? Map<*, *> ?: return false
        return encrypted["ciphertext"] is String && encrypted["mac"] is String && encrypted["iv"] is String
    }

    override fun encrypt(type: String, clearContent: Content): Content {
        val secret = AesHmacSha2.encrypt(requireAdk(), type, contentAdapter.toJson(clearContent))
        return mapOf(
                "encrypted" to mapOf(
                        "iv" to secret.initializationVector,
                        "ciphertext" to secret.ciphertext,
                        "mac" to secret.mac,
                )
        )
    }

    override fun decrypt(type: String, encryptedContent: Content): Content {
        val secret = EncryptedSecretContent.fromJson(encryptedContent["encrypted"])
                ?.takeIf { it.ciphertext != null }
                ?: throw SharedSecretStorageError.ParsingError
        val clearJson = AesHmacSha2.decrypt(requireAdk(), type, secret)
        return contentAdapter.fromJson(clearJson) ?: throw SharedSecretStorageError.ParsingError
    }

    override fun decryptOrNull(type: String, encryptedContent: Content): Content? {
        return tryOrNull { decrypt(type, encryptedContent) }
    }

    private fun requireAdk(): ByteArray {
        return getAccountDataKey()?.fromBase64()
                ?: throw SharedSecretStorageError.UnknownSecret(EncryptedAccountDataService.ADK_SECRET_NAME)
    }
}
