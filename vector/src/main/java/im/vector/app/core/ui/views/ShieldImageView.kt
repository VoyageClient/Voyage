/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.crypto.model.UserVerificationLevel

class ShieldImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        if (isInEditMode) {
            render(RoomEncryptionTrustLevel.Trusted)
        }
    }

    /**
     * Renders device shield with the support of unknown shields instead of black shields which is used for rooms.
     * @param roomEncryptionTrustLevel trust level that is usually calculated with [im.vector.app.features.settings.devices.TrustUtils.shieldForTrust]
     * @param borderLess if true then the shield icon with border around is used
     */
    fun renderDeviceShield(roomEncryptionTrustLevel: RoomEncryptionTrustLevel?, borderLess: Boolean = false) {
        when (roomEncryptionTrustLevel) {
            null -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_warning)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_warning_no_border
                        else R.drawable.ic_shield_warning
                )
            }
            RoomEncryptionTrustLevel.Default -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_default)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_unknown_no_border
                        else R.drawable.ic_shield_unknown
                )
            }
            else -> render(roomEncryptionTrustLevel, borderLess)
        }
    }

    fun render(roomEncryptionTrustLevel: RoomEncryptionTrustLevel?, borderLess: Boolean = false) {
        isVisible = roomEncryptionTrustLevel != null

        when (roomEncryptionTrustLevel) {
            RoomEncryptionTrustLevel.Default -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_default)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_black_no_border
                        else R.drawable.ic_shield_black
                )
            }
            RoomEncryptionTrustLevel.Warning -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_warning)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_warning_no_border
                        else R.drawable.ic_shield_warning
                )
            }
            RoomEncryptionTrustLevel.Trusted -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_trusted)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_trusted_no_border
                        else R.drawable.ic_shield_trusted
                )
            }
            RoomEncryptionTrustLevel.E2EWithUnsupportedAlgorithm -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_trusted)
                setImageResource(R.drawable.ic_warning_badge)
            }
            null -> Unit
        }
    }

    /**
     * Room-level decoration: the PGP padlock when the room is in PGP-over-plaintext mode, otherwise
     * the normal encryption shield. Clears any leftover PGP tint first so recycled views (room list)
     * don't keep a tinted shield.
     */
    fun renderRoomShield(roomEncryptionTrustLevel: RoomEncryptionTrustLevel?, isPgp: Boolean) {
        if (isPgp) {
            renderPgpLock()
        } else {
            ImageViewCompat.setImageTintList(this, null)
            render(roomEncryptionTrustLevel)
        }
    }

    fun renderE2EDecoration(decoration: E2EDecoration?) {
        isVisible = true
        // Clear any PGP lock tint left over on a recycled view.
        ImageViewCompat.setImageTintList(this, null)
        when (decoration) {
            E2EDecoration.WARN_IN_CLEAR -> {
                contentDescription = context.getString(CommonStrings.unencrypted)
                setImageResource(R.drawable.ic_shield_warning)
            }
            E2EDecoration.WARN_SENT_BY_UNVERIFIED -> {
                contentDescription = context.getString(CommonStrings.encrypted_unverified)
                setImageResource(R.drawable.ic_shield_warning)
            }
            E2EDecoration.WARN_SENT_BY_UNKNOWN -> {
                contentDescription = context.getString(CommonStrings.encrypted_unverified)
                setImageResource(R.drawable.ic_shield_warning)
            }
            E2EDecoration.WARN_SENT_BY_DELETED_SESSION -> {
                contentDescription = context.getString(CommonStrings.encrypted_unverified)
                setImageResource(R.drawable.ic_shield_warning)
            }
            E2EDecoration.WARN_UNSAFE_KEY -> {
                contentDescription = context.getString(CommonStrings.key_authenticity_not_guaranteed)
                setImageResource(
                        R.drawable.ic_shield_gray
                )
            }
            E2EDecoration.NONE,
            null -> {
                contentDescription = null
                isVisible = false
            }
        }
    }

    /**
     * Shows a padlock in the shield slot for a PGP-over-plaintext message. Tinted at runtime so it
     * follows the theme on every API level (vector theme-attr fillColor isn't reliable on API 19).
     */
    fun renderPgpLock() {
        isVisible = true
        contentDescription = context.getString(CommonStrings.encrypted_message)
        setImageResource(R.drawable.ic_pgp_lock)
        ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
        )
    }

    fun renderUser(userVerificationLevel: UserVerificationLevel?, borderLess: Boolean = false) {
        isVisible = userVerificationLevel != null
        when (userVerificationLevel) {
            UserVerificationLevel.VERIFIED_ALL_DEVICES_TRUSTED -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_trusted)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_trusted_no_border
                        else R.drawable.ic_shield_trusted
                )
            }
            UserVerificationLevel.UNVERIFIED_BUT_WAS_PREVIOUSLY,
            UserVerificationLevel.VERIFIED_WITH_DEVICES_UNTRUSTED -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_warning)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_warning_no_border
                        else R.drawable.ic_shield_warning
                )
            }
            UserVerificationLevel.WAS_NEVER_VERIFIED -> {
                contentDescription = context.getString(CommonStrings.a11y_trust_level_default)
                setImageResource(
                        if (borderLess) R.drawable.ic_shield_black_no_border
                        else R.drawable.ic_shield_black
                )
            }
            null -> Unit
        }
    }
}

@DrawableRes
fun RoomEncryptionTrustLevel.toDrawableRes(): Int {
    return when (this) {
        RoomEncryptionTrustLevel.Default -> R.drawable.ic_shield_black
        RoomEncryptionTrustLevel.Warning -> R.drawable.ic_shield_warning
        RoomEncryptionTrustLevel.Trusted -> R.drawable.ic_shield_trusted
        RoomEncryptionTrustLevel.E2EWithUnsupportedAlgorithm -> R.drawable.ic_warning_badge
    }
}
