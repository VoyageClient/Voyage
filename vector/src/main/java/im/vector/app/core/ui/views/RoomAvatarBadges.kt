/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.view.View
import androidx.core.view.isVisible
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomBadgeConfig(
        val showEncryption: Boolean,
        val showPublic: Boolean,
) {
    fun showsPublic(isPublic: Boolean, isDirect: Boolean) = showPublic && isPublic && !isDirect

    companion object {
        val NONE = RoomBadgeConfig(showEncryption = false, showPublic = false)
    }
}

// The SDK only computes trust once it knows every member, i.e. for larger rooms after they are first opened.
fun RoomSummary.badgeTrustLevel(): RoomEncryptionTrustLevel? =
        roomEncryptionTrustLevel ?: RoomEncryptionTrustLevel.Default.takeIf { isEncrypted }

// The globe wins the avatar corner; room info shows the encryption state anyway.
fun shouldShowRoomShield(config: RoomBadgeConfig, hasShield: Boolean, publicShown: Boolean) =
        config.showEncryption && hasShield && !publicShown

/** For toolbars without a globe of their own, which still drop the shield wherever the globe would win. */
fun ShieldImageView.renderRoomShieldBadge(config: RoomBadgeConfig, summary: RoomSummary) {
    val publicShown = config.showsPublic(summary.isPublic, summary.isDirect)
    render(summary.badgeTrustLevel().takeIf { shouldShowRoomShield(config, it != null, publicShown) })
}

/** Returns whether the shield is shown. */
fun renderRoomAvatarBadges(
        config: RoomBadgeConfig,
        trustLevel: RoomEncryptionTrustLevel?,
        isPgp: Boolean,
        isPublic: Boolean,
        isDirect: Boolean,
        avatarShield: ShieldImageView,
        publicBadge: View,
): Boolean {
    val publicShown = config.showsPublic(isPublic, isDirect)
    publicBadge.isVisible = publicShown
    val shieldShown = shouldShowRoomShield(config, trustLevel != null || isPgp, publicShown)
    avatarShield.renderRoomShield(trustLevel.takeIf { shieldShown }, isPgp && shieldShown)
    return shieldShown
}
