/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.roommemberprofile.devices

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.R
import im.vector.app.core.epoxy.errorWithRetryItem
import im.vector.app.core.epoxy.loadingItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.ItemStyle
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.list.genericWithValueItem
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.roommemberprofile.MemberSessions
import im.vector.app.features.roommemberprofile.distinctDisplayName
import im.vector.app.features.roommemberprofile.orderMemberSessions
import im.vector.app.features.roommemberprofile.sessionCopyText
import im.vector.app.features.roommemberprofile.sessionDisplayName
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.devices.TrustUtils
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.span
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import javax.inject.Inject

class DeviceListEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val dimensionConverter: DimensionConverter,
        private val vectorPreferences: VectorPreferences
) :
        TypedEpoxyController<DeviceListViewState>() {

    interface InteractionListener {
        fun onDeviceSelected(device: CryptoDeviceInfo)
    }

    var interactionListener: InteractionListener? = null

    /** The fixed heading, or the scrolling session list below it. */
    enum class Part { HEADER, LIST }

    var part: Part = Part.LIST

    override fun buildModels(data: DeviceListViewState?) {
        data ?: return
        val host = this
        when (data.cryptoDevices) {
            Uninitialized -> {
            }
            is Loading -> {
                if (part == Part.LIST) {
                    loadingItem {
                        id("loading")
                        loadingText(host.stringProvider.getString(CommonStrings.loading))
                    }
                }
            }
            is Success -> {
                val trustMSK = data.memberCrossSigningKey?.isTrusted().orFalse()
                val legacyMode = data.memberCrossSigningKey == null
                val sessions = orderMemberSessions(data.cryptoDevices.invoke(), isMine = data.userId == data.myUserId, isUserVerified = trustMSK)
                if (part == Part.HEADER) {
                    buildHeader(data, sessions.sessions, trustMSK, legacyMode)
                } else {
                    buildSessions(data, sessions, trustMSK, legacyMode)
                }
            }
            is Fail -> {
                if (part == Part.LIST) {
                    errorWithRetryItem {
                        id("error")
                        text(host.stringProvider.getString(CommonStrings.room_member_profile_failed_to_get_devices))
                        listener {
                            // TODO
                        }
                    }
                }
            }
        }
    }

    private fun buildHeader(data: DeviceListViewState, deviceList: List<CryptoDeviceInfo>, trustMSK: Boolean, legacyMode: Boolean) {
        val host = this
        val allGreen = deviceList.fold(true) { prev, device ->
            val trustLevel = TrustUtils.shieldForTrust(
                    data.myDeviceId == device.deviceId,
                    trustMSK,
                    legacyMode,
                    device.trustLevel
            )

            prev && trustLevel == RoomEncryptionTrustLevel.Trusted
        }

        // An unverified user's sessions can't be trusted yet, which isn't a warning.
        val (headerIcon, headerTitle) = when {
            allGreen -> R.drawable.ic_shield_trusted to CommonStrings.verification_profile_verified
            !trustMSK -> R.drawable.ic_shield_unknown to CommonStrings.verification_profile_not_verified
            else -> R.drawable.ic_shield_warning to CommonStrings.verification_profile_warning
        }
        genericItem {
            id("title")
            style(ItemStyle.BIG_TEXT)
            titleIconResourceId(headerIcon)
            title(host.stringProvider.getString(headerTitle).toEpoxyCharSequence())
        }

        if (vectorPreferences.developerMode()) {
            // Display the cross signing keys
            addDebugInfo(data)
        }

        genericItem {
            id("sessions")
            style(ItemStyle.BIG_TEXT)
            title(host.stringProvider.getString(CommonStrings.room_member_profile_sessions_section_title).toEpoxyCharSequence())
        }
    }

    private fun buildSessions(data: DeviceListViewState, sessions: MemberSessions, trustMSK: Boolean, legacyMode: Boolean) {
        val host = this
        val deviceList = sessions.sessions
        if (deviceList.isEmpty()) {
            // Can this really happen?
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.search_no_results).toEpoxyCharSequence())
            }
        } else {
            // Build list of device with status
            deviceList.forEach { device ->
                genericWithValueItem {
                    val trustLevel = TrustUtils.shieldForTrust(
                            data.myDeviceId == device.deviceId,
                            trustMSK,
                            legacyMode,
                            device.trustLevel
                    )
                    val shield = when (trustLevel) {
                        RoomEncryptionTrustLevel.Default -> R.drawable.ic_shield_unknown
                        RoomEncryptionTrustLevel.Warning -> R.drawable.ic_shield_warning
                        RoomEncryptionTrustLevel.Trusted -> R.drawable.ic_shield_trusted
                        RoomEncryptionTrustLevel.E2EWithUnsupportedAlgorithm -> R.drawable.ic_warning_badge
                    }

                    id(device.deviceId)
                    titleIconResourceId(shield)
                    apply {
                        val name = device.distinctDisplayName()
                        val title = if (host.vectorPreferences.developerMode() && name != null) {
                            val seq = span {
                                +name
                                +"\n"
                                span {
                                    text = "(${device.deviceId})"
                                    textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                                    textSize = host.dimensionConverter.spToPx(14)
                                }
                            }
                            seq
                        } else {
                            device.sessionDisplayName(sessions.ambiguousNames)
                        }
                        title(title.prepareForDisplay().toEpoxyCharSequence())
                    }
                    value(
                            host.stringProvider.getString(
                                    if (trustLevel == RoomEncryptionTrustLevel.Trusted) CommonStrings.trusted else CommonStrings.not_trusted
                            )
                    )
                    valueColorInt(
                            host.colorProvider.getColorFromAttribute(
                                    if (trustLevel == RoomEncryptionTrustLevel.Trusted) {
                                        com.google.android.material.R.attr.colorPrimary
                                    } else {
                                        com.google.android.material.R.attr.colorError
                                    }
                            )
                    )
                    itemClickAction {
                        host.interactionListener?.onDeviceSelected(device)
                    }
                    // Raw, unlike the title's neutralized direction overrides.
                    copyValue { device.sessionCopyText() }
                }
            }
        }
        if (sessions.hasDehydratedDevice) {
            genericFooterItem {
                id("dehydrated")
                centered(false)
                text(host.stringProvider.getString(CommonStrings.room_member_profile_dehydrated_device_enabled).toEpoxyCharSequence())
            }
        }
    }

    private fun addDebugInfo(data: DeviceListViewState) {
        val host = this
        data.memberCrossSigningKey?.masterKey()?.let {
            genericWithValueItem {
                id("msk")
                titleIconResourceId(R.drawable.key_small)
                titleIconTintColor(host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
                copyValue { it.unpaddedBase64PublicKey }
                title(
                        span {
                            +"Master Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }.toEpoxyCharSequence()
                )
            }
        }
        data.memberCrossSigningKey?.userKey()?.let {
            genericWithValueItem {
                id("usk")
                titleIconResourceId(R.drawable.key_small)
                titleIconTintColor(host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
                copyValue { it.unpaddedBase64PublicKey }
                title(
                        span {
                            +"User Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }.toEpoxyCharSequence()
                )
            }
        }
        data.memberCrossSigningKey?.selfSigningKey()?.let {
            genericWithValueItem {
                id("ssk")
                titleIconResourceId(R.drawable.key_small)
                titleIconTintColor(host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary))
                copyValue { it.unpaddedBase64PublicKey }
                title(
                        span {
                            +"Self Signed Key:\n"
                            span {
                                text = it.unpaddedBase64PublicKey ?: ""
                                textColor = host.colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
                                textSize = host.dimensionConverter.spToPx(12)
                            }
                        }.toEpoxyCharSequence()
                )
            }
        }
    }
}
