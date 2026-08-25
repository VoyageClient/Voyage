/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.accountdata

import org.matrix.android.sdk.api.session.events.model.Content

/**
 * MSC4483 encrypted account data. Account data content is encrypted with the "account data key"
 * (ADK), a symmetric secret shared between clients through secret storage (SSSS) under
 * [ADK_SECRET_NAME]. Payloads use m.secret_storage.v1.aes-hmac-sha2 with the account data event
 * type as the HKDF info, and take the form {"encrypted": {"iv", "ciphertext", "mac"}}.
 *
 * This service only handles the crypto and the locally cached copy of the ADK; fetching the ADK
 * from (or storing it into) SSSS requires the user's recovery key and is driven by the app.
 */
interface EncryptedAccountDataService {

    /** True when the ADK is cached locally and encrypt/decrypt can proceed. */
    fun hasAccountDataKey(): Boolean

    /** The locally cached ADK, base64 encoded, or null when none is cached. */
    fun getAccountDataKey(): String?

    /** Caches the ADK locally, e.g. after it was read from (or written to) secret storage. */
    fun setAccountDataKey(adkBase64: String)

    fun clearAccountDataKey()

    /** Generates a fresh random ADK without caching it; returns it base64 encoded. */
    fun generateAccountDataKey(): String

    /**
     * Makes the ADK available without user interaction: it is already cached, readable from
     * secret storage with the locally cached 4S key, or — when no ADK exists anywhere — freshly
     * created (device-local at first, uploaded to secret storage once the recovery key is next
     * entered). Returns true when the ADK is now cached; the single false case is an ADK stored
     * in secret storage that can only be read with the user's recovery key.
     */
    suspend fun ensureAccountDataKey(): Boolean

    /** True when [content] is an MSC4483 encrypted payload. */
    fun isEncrypted(content: Content?): Boolean

    /**
     * Encrypts [clearContent] with the cached ADK into an MSC4483 payload for account data
     * event [type].
     */
    fun encrypt(type: String, clearContent: Content): Content

    /**
     * Decrypts an MSC4483 payload for account data event [type] with the cached ADK.
     * Throws when no ADK is cached, the MAC does not match or the payload is malformed.
     */
    fun decrypt(type: String, encryptedContent: Content): Content

    fun decryptOrNull(type: String, encryptedContent: Content): Content?

    companion object {
        const val ADK_SECRET_NAME = "m.account_data.key"
        const val ADK_SECRET_NAME_UNSTABLE = "dev.zirco.msc4483.account_data.key"
        val ADK_SECRET_NAMES = listOf(ADK_SECRET_NAME, ADK_SECRET_NAME_UNSTABLE)
    }
}
