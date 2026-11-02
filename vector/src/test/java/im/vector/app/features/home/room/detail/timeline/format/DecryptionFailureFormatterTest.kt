/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import im.vector.app.features.home.room.detail.timeline.format.DecryptionFailureFormatter.Companion.classify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.crypto.MXCryptoError.ErrorType
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState

class DecryptionFailureFormatterTest {

    private fun reason(
            error: ErrorType? = ErrorType.UNKNOWN_INBOUND_SESSION_ID,
            errorReason: String? = null,
            membership: String? = null,
            ts: Long = 2_000,
            deviceCreationTs: Long? = 1_000,
            backupState: KeysBackupState? = KeysBackupState.ReadyToBackUp,
    ) = classify(error, errorReason, membership, ts, deviceCreationTs, backupState)

    @Test
    fun `no error is pending`() {
        reason(error = null) shouldBeEqualTo DecryptionFailureReason.PENDING
    }

    @Test
    fun `missing key after device creation is a generic failure`() {
        reason() shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
    }

    @Test
    fun `key withheld for unverified device`() {
        reason(error = ErrorType.KEYS_WITHHELD, errorReason = "m.unverified") shouldBeEqualTo DecryptionFailureReason.WITHHELD_FOR_UNVERIFIED_DEVICE
    }

    @Test
    fun `other withheld codes are a generic failure`() {
        reason(error = ErrorType.KEYS_WITHHELD, errorReason = "m.blacklisted") shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
    }

    @Test
    fun `sent while not joined`() {
        reason(membership = "leave") shouldBeEqualTo DecryptionFailureReason.HISTORICAL_USER_NOT_JOINED
        reason(error = ErrorType.KEYS_WITHHELD, errorReason = "m.unverified", membership = "ban") shouldBeEqualTo
                DecryptionFailureReason.HISTORICAL_USER_NOT_JOINED
    }

    @Test
    fun `sent while invited or joined is not historical`() {
        reason(membership = "invite") shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
        reason(membership = "join") shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
    }

    @Test
    fun `before device creation depends on backup`() {
        reason(ts = 500, backupState = KeysBackupState.Disabled) shouldBeEqualTo DecryptionFailureReason.HISTORICAL_NO_KEY_BACKUP
        reason(ts = 500, backupState = KeysBackupState.NotTrusted) shouldBeEqualTo DecryptionFailureReason.HISTORICAL_BACKUP_UNCONFIGURED
        reason(ts = 500, backupState = KeysBackupState.ReadyToBackUp) shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
    }

    @Test
    fun `unknown device creation time skips historical classification`() {
        reason(ts = 500, deviceCreationTs = null, backupState = KeysBackupState.Disabled) shouldBeEqualTo DecryptionFailureReason.UNABLE_TO_DECRYPT
    }

    @Test
    fun `non key errors are never historical`() {
        reason(error = ErrorType.BAD_ENCRYPTED_MESSAGE, membership = "leave", ts = 500, backupState = KeysBackupState.Disabled) shouldBeEqualTo
                DecryptionFailureReason.UNABLE_TO_DECRYPT
    }
}
