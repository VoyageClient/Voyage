/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.api.session.events.model.membershipAtEvent
import javax.inject.Inject
import javax.inject.Provider

/** Why an event couldn't be decrypted, classified the way Element Web does. */
enum class DecryptionFailureReason {
    /** Not attempted yet, or still in progress. */
    PENDING,
    WITHHELD_FOR_UNVERIFIED_DEVICE,
    HISTORICAL_NO_KEY_BACKUP,
    HISTORICAL_BACKUP_UNCONFIGURED,
    HISTORICAL_USER_NOT_JOINED,
    UNABLE_TO_DECRYPT,
}

class DecryptionFailureFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        // Provider: ActiveSessionHolder -> notifications -> DisplayableEventFormatter -> here is a cycle.
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {

    private fun reason(event: Event): DecryptionFailureReason {
        val crypto = activeSessionHolder.get().getSafeActiveSession()?.cryptoService()
        return classify(
                cryptoError = event.mCryptoError,
                cryptoErrorReason = event.mCryptoErrorReason,
                membershipAtEvent = event.unsignedData.membershipAtEvent(),
                originServerTs = event.originServerTs,
                deviceCreationTs = crypto?.getDeviceCreationTs(),
                backupState = crypto?.keysBackupService()?.getState(),
        )
    }

    /** The placeholder shown in place of the message body. */
    fun format(event: Event): String = text(reason(event))

    private fun text(reason: DecryptionFailureReason): String = stringProvider.getString(
            when (reason) {
                DecryptionFailureReason.PENDING -> CommonStrings.encrypted_message
                DecryptionFailureReason.WITHHELD_FOR_UNVERIFIED_DEVICE -> CommonStrings.decryption_failure_blocked
                DecryptionFailureReason.HISTORICAL_NO_KEY_BACKUP -> CommonStrings.decryption_failure_historical_no_key_backup
                DecryptionFailureReason.HISTORICAL_BACKUP_UNCONFIGURED -> CommonStrings.decryption_failure_historical_unverified_device
                DecryptionFailureReason.HISTORICAL_USER_NOT_JOINED -> CommonStrings.decryption_failure_user_not_joined
                DecryptionFailureReason.UNABLE_TO_DECRYPT -> CommonStrings.decryption_failure_unable_to_decrypt
            }
    )

    /** A longer explanation, for when the user taps the placeholder. */
    fun explanation(event: Event): String {
        val reason = reason(event)
        val withHeldCode = WithHeldCode.fromCode(event.mCryptoErrorReason).takeIf { event.mCryptoError == MXCryptoError.ErrorType.KEYS_WITHHELD }
        return stringProvider.getString(
                when {
                    reason == DecryptionFailureReason.HISTORICAL_NO_KEY_BACKUP -> CommonStrings.decryption_failure_historical_no_key_backup_desc
                    reason == DecryptionFailureReason.HISTORICAL_BACKUP_UNCONFIGURED -> CommonStrings.decryption_failure_historical_unverified_device_desc
                    reason == DecryptionFailureReason.HISTORICAL_USER_NOT_JOINED -> CommonStrings.decryption_failure_user_not_joined_desc
                    withHeldCode == WithHeldCode.UNVERIFIED -> CommonStrings.crypto_error_withheld_unverified
                    withHeldCode == WithHeldCode.BLACKLISTED -> CommonStrings.crypto_error_withheld_blacklisted
                    withHeldCode == WithHeldCode.UNAUTHORISED || withHeldCode == WithHeldCode.UNAVAILABLE -> CommonStrings.crypto_error_withheld_generic
                    else -> CommonStrings.notice_crypto_unable_to_decrypt_friendly_desc
                }
        )
    }

    /** The raw error, for developer mode. */
    fun technicalDetails(event: Event): String? {
        val error = event.mCryptoError ?: return null
        return listOfNotNull(error.name, event.mCryptoErrorReason?.takeIf { it.isNotBlank() }).joinToString(": ")
    }

    /** The body placeholder as the timeline shows it: secondary color, italic. */
    fun formatStyled(event: Event): CharSequence = span(format(event)) {
        textColor = colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        textStyle = "italic"
    }

    companion object {
        /**
         * Mirrors matrix-js-sdk's `onMegolmDecryptionError`: missing-key failures are first explained by the
         * user's membership when the event was sent, then by whether the event predates this device.
         */
        fun classify(
                cryptoError: MXCryptoError.ErrorType?,
                cryptoErrorReason: String?,
                membershipAtEvent: String?,
                originServerTs: Long?,
                deviceCreationTs: Long?,
                backupState: KeysBackupState?,
        ): DecryptionFailureReason {
            cryptoError ?: return DecryptionFailureReason.PENDING
            val missingKey = cryptoError == MXCryptoError.ErrorType.UNKNOWN_INBOUND_SESSION_ID ||
                    cryptoError == MXCryptoError.ErrorType.UNKNOWN_MESSAGE_INDEX ||
                    cryptoError == MXCryptoError.ErrorType.KEYS_WITHHELD
            if (missingKey) {
                if (membershipAtEvent != null && membershipAtEvent != "join" && membershipAtEvent != "invite") {
                    return DecryptionFailureReason.HISTORICAL_USER_NOT_JOINED
                }
                if (originServerTs != null && deviceCreationTs != null && originServerTs <= deviceCreationTs) {
                    when (backupState) {
                        KeysBackupState.Disabled -> return DecryptionFailureReason.HISTORICAL_NO_KEY_BACKUP
                        // A backup this device can't use: with legacy crypto that means it isn't verified yet,
                        // which is the only case Element Web words differently.
                        KeysBackupState.NotTrusted -> return DecryptionFailureReason.HISTORICAL_BACKUP_UNCONFIGURED
                        else -> Unit
                    }
                }
            }
            if (cryptoError == MXCryptoError.ErrorType.KEYS_WITHHELD && WithHeldCode.fromCode(cryptoErrorReason) == WithHeldCode.UNVERIFIED) {
                return DecryptionFailureReason.WITHHELD_FOR_UNVERIFIED_DEVICE
            }
            return DecryptionFailureReason.UNABLE_TO_DECRYPT
        }
    }
}
