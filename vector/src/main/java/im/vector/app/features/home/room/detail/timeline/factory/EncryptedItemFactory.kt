/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.features.home.room.detail.timeline.format.DecryptionFailureFormatter
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.MessageInformationDataFactory
import im.vector.app.features.home.room.detail.timeline.helper.MessageItemAttributesFactory
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem_
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import javax.inject.Inject

// This class handles timeline events who haven't been successfully decrypted
class EncryptedItemFactory @Inject constructor(
        private val messageInformationDataFactory: MessageInformationDataFactory,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val attributesFactory: MessageItemAttributesFactory,
        private val decryptionFailureFormatter: DecryptionFailureFormatter,
) {

    fun create(params: TimelineItemFactoryParams): VectorEpoxyModel<*>? {
        val event = params.event
        event.root.eventId ?: return null

        return when {
            EventType.ENCRYPTED == event.root.getClearType() -> {
                val spannableStr = decryptionFailureFormatter.formatStyled(event.root)

                val informationData = messageInformationDataFactory.create(params)
                val threadDetails = if (params.isFromThreadTimeline()) null else event.root.threadDetails
                val attributes = attributesFactory.create(
                        messageContent = event.root.content.toModel<EncryptedEventContent>(),
                        informationData = informationData,
                        callback = params.callback,
                        threadDetails = threadDetails,
                        reactionsSummaryEvents = params.reactionsSummaryEvents
                )
                return MessageTextItem_()
                        .layout(informationData.messageLayout.layoutRes)
                        .leftGuideline(avatarSizeProvider.leftGuideline)
                        .highlighted(params.isHighlighted)
                        .attributes(attributes)
                        .message(spannableStr.toEpoxyCharSequence())
                        .movementMethod(createLinkMovementMethod(params.callback))
            }
            else -> null
        }
    }
}
