/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

/**
 * Platform seam for encrypting small secrets at rest under a named key alias. Android backs this
 * with the Keystore (via SecretStoringUtils); a desktop implementation can use an OS keyring or a
 * file-based key with an encrypt-then-MAC envelope.
 */
internal interface SecureStorage {

    /** Encrypt [bytes] under the key for [alias], creating the key on first use. */
    fun encryptBytes(bytes: ByteArray, alias: String): ByteArray

    /** Decrypt data previously returned by [encryptBytes] with the same [alias]. */
    fun decryptBytes(encrypted: ByteArray, alias: String): ByteArray

    /** Delete the key for [alias]; previously encrypted data becomes unrecoverable. */
    fun deleteKey(alias: String)
}
