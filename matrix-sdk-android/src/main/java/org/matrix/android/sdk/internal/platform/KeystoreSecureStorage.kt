/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import org.matrix.android.sdk.api.securestorage.SecretStoringUtils
import javax.inject.Inject

internal class KeystoreSecureStorage @Inject constructor(
        private val secretStoringUtils: SecretStoringUtils,
) : SecureStorage {

    override fun encryptBytes(bytes: ByteArray, alias: String): ByteArray {
        return secretStoringUtils.securelyStoreBytes(bytes, alias)
    }

    override fun decryptBytes(encrypted: ByteArray, alias: String): ByteArray {
        return secretStoringUtils.loadSecureSecretBytes(encrypted, alias)
    }

    override fun deleteKey(alias: String) {
        secretStoringUtils.safeDeleteKey(alias)
    }
}
