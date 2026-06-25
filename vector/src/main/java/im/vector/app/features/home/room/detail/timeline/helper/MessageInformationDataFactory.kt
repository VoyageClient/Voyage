/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.extensions.localDateTime
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReferencesInfoData
import im.vector.app.features.home.room.detail.timeline.item.SendStateDecoration
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayoutFactory
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.settings.MediaPreviewMode
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.BubbleThemeUtils
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.model.MessageVerificationState
import org.matrix.android.sdk.api.session.crypto.verification.VerificationState
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getMsgType
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.events.model.toValidDecryptedEvent
import org.matrix.android.sdk.api.session.room.model.ReferencesAggregatedContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.hasBeenEdited
import javax.inject.Inject

private val PRIVATE_JOIN_RULES = setOf(
        RoomJoinRules.INVITE,
        RoomJoinRules.KNOCK,
        RoomJoinRules.RESTRICTED,
        RoomJoinRules.PRIVATE,
)

/**
 * This class is responsible of building extra information data associated to a given event.
 */
class MessageInformationDataFactory @Inject constructor(
        private val session: Session,
        private val dateFormatter: VectorDateFormatter,
        private val messageLayoutFactory: TimelineMessageLayoutFactory,
        private val reactionsSummaryFactory: ReactionsSummaryFactory,
        private val pollResponseDataFactory: PollResponseDataFactory,
        private val vectorPreferences: VectorPreferences,
        private val pgpKeyStore: PgpKeyStore,
) {

    fun create(params: TimelineItemFactoryParams): MessageInformationData {
        val event = params.event
        val nextDisplayableEvent = params.nextDisplayableEvent
        val prevDisplayableEvent = params.prevDisplayableEvent
        val eventId = event.eventId
        val isSentByMe = event.root.senderId == session.myUserId
        val roomSummary = params.partialState.roomSummary

        val date = event.root.localDateTime()
        val nextDate = nextDisplayableEvent?.root?.localDateTime()
        val addDaySeparator = date.toLocalDate() != nextDate?.toLocalDate()

        val isFirstFromThisSender = nextDisplayableEvent?.root?.senderId != event.root.senderId || addDaySeparator
        val isLastFromThisSender = prevDisplayableEvent?.root?.senderId != event.root.senderId ||
                prevDisplayableEvent?.root?.localDateTime()?.toLocalDate() != date.toLocalDate()

        val time = dateFormatter.format(event.root.originServerTs, DateFormatKind.MESSAGE_SIMPLE)
        val e2eDecoration = getE2EDecorationV2(roomSummary, params.lastEdit ?: event.root)
        // PGP-over-plaintext: a non-Matrix-encrypted text body carrying an armored block. Drives
        // the lock indicator; deliberately not tied to e2eDecoration / room encryption.
        val pgpContent = event.getVectorLastMessageContent()
        val pgpText = (pgpContent as? MessageTextContent)?.body
        // Captioned media: only the caption is PGP, never the media itself.
        val pgpCaption = (pgpContent as? MessageWithAttachmentContent)?.getCaption()
        val isPgp = pgpKeyStore.isEnabled &&
                !event.isEncrypted() &&
                (PgpUtils.bodyContainsPgp(pgpText) || PgpUtils.bodyContainsPgp(pgpCaption))
        // this is claimed data or not depending on the e2e decoration
        val senderId = event.senderInfo.userId

        // Sender name/avatar are denormalized at sync time and can be missing when the event was
        // received while the room was only listed (members not yet loaded). Fall back to the current
        // member state so they resolve once members are known, without rewriting stored events.
        val fallbackMember = if (event.senderInfo.displayName.isNullOrBlank() || event.senderInfo.avatarUrl == null) {
            event.root.roomId?.let { session.roomService().getRoom(it)?.membershipService()?.getRoomMember(senderId) }
        } else {
            null
        }
        val senderName = if (!event.senderInfo.displayName.isNullOrBlank()) {
            event.senderInfo.disambiguatedDisplayName
        } else {
            fallbackMember?.displayName?.takeUnless { it.isBlank() } ?: event.senderInfo.disambiguatedDisplayName
        }
        val senderAvatar = event.senderInfo.avatarUrl ?: fallbackMember?.avatarUrl

        // Determine DM partner so dual-side bubbles can hide both avatars in direct chats.
        val isEffectivelyDirect = roomSummary?.isDirect ?: false
        val dmOtherMemberId = if (roomSummary?.isDirect == true && event.root.roomId != null) {
            directMessagePartner(event.root.roomId!!)
        } else {
            null
        }

        // SendState Decoration
        val sendStateDecoration = if (isSentByMe) {
            getSendStateDecoration(
                    event = event,
                    lastSentEventWithoutReadReceipts = params.lastSentEventIdWithoutReadReceipts,
                    isMedia = event.root.isAttachmentMessage()
            )
        } else {
            SendStateDecoration.NONE
        }

        val messageLayout = messageLayoutFactory.create(params)

        val mediaHiddenInRoom = when (vectorPreferences.getMediaPreviewMode()) {
            MediaPreviewMode.ALWAYS_SHOW -> false
            MediaPreviewMode.ALWAYS_HIDE -> true
            MediaPreviewMode.PRIVATE -> roomSummary?.joinRules !in PRIVATE_JOIN_RULES
            MediaPreviewMode.DIRECT -> roomSummary?.isDirect != true
        }
        val hideMediaReactions = mediaHiddenInRoom
        val hideAvatars = mediaHiddenInRoom && vectorPreferences.hideAvatarsInHiddenMediaRooms() && !isSentByMe

        return MessageInformationData(
                eventId = eventId,
                senderId = senderId,
                sendState = event.root.sendState,
                time = time,
                ageLocalTS = event.root.ageLocalTs,
                avatarUrl = senderAvatar,
                memberName = senderName,
                messageLayout = messageLayout,
                reactionsSummary = reactionsSummaryFactory.create(event),
                pollResponseAggregatedSummary = pollResponseDataFactory.create(event),
                hasBeenEdited = event.hasBeenEdited(),
                hasPendingEdits = event.annotations?.editSummary?.localEchos?.any() ?: false,
                referencesInfoData = event.annotations?.referencesAggregatedSummary?.let { referencesAggregatedSummary ->
                    val verificationState = referencesAggregatedSummary.content.toModel<ReferencesAggregatedContent>()?.verificationState
                            ?: VerificationState.REQUEST
                    ReferencesInfoData(verificationState)
                },
                sentByMe = isSentByMe,
                readReceiptAnonymous = BubbleThemeUtils.anonymousReadReceiptForEvent(event),
                isDirect = isEffectivelyDirect,
                dmChatPartnerId = dmOtherMemberId,
                hideMediaReactions = hideMediaReactions,
                hideAvatars = hideAvatars,
                isFirstFromThisSender = isFirstFromThisSender,
                isLastFromThisSender = isLastFromThisSender,
                e2eDecoration = e2eDecoration,
                isPgp = isPgp,
                sendStateDecoration = sendStateDecoration,
                messageType = if (event.root.isSticker()) {
                    MessageType.MSGTYPE_STICKER_LOCAL
                } else {
                    event.root.getMsgType()
                }
        )
    }

    // Memoize per room: resolving the DM partner hits Realm, and it's identical for every event in the room.
    private var cachedDmRoomId: String? = null
    private var cachedDmPartnerId: String? = null

    private fun directMessagePartner(roomId: String): String? {
        if (roomId == cachedDmRoomId) return cachedDmPartnerId
        val members = session.roomService().getRoom(roomId)
                ?.membershipService()
                ?.getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
                ?.map { it.userId }
                .orEmpty()
                .toSet()
        val partner = if (members.size == 2) members.firstOrNull { it != session.myUserId } else null
        cachedDmRoomId = roomId
        cachedDmPartnerId = partner
        return partner
    }

    private suspend fun getSenderId(event: TimelineEvent) = if (event.isEncrypted()) {
        event.root.toValidDecryptedEvent()?.let {
            session.cryptoService().deviceWithIdentityKey(event.senderInfo.userId, it.cryptoSenderKey, it.algorithm)?.userId
        } ?: event.root.senderId.orEmpty()
    } else {
        event.root.senderId.orEmpty()
    }

    private fun getSendStateDecoration(
            event: TimelineEvent,
            lastSentEventWithoutReadReceipts: String?,
            isMedia: Boolean
    ): SendStateDecoration {
        val eventSendState = event.root.sendState
        return if (eventSendState.isSending()) {
            if (isMedia) SendStateDecoration.SENDING_MEDIA else SendStateDecoration.SENDING_NON_MEDIA
        } else if (eventSendState.hasFailed()) {
            SendStateDecoration.FAILED
        } else if (lastSentEventWithoutReadReceipts == event.eventId) {
            SendStateDecoration.SENT
        } else {
            SendStateDecoration.NONE
        }
    }

    private fun getE2EDecorationV2(roomSummary: RoomSummary?, event: Event): E2EDecoration {
        if (roomSummary?.isEncrypted != true) {
            // No decoration for clear room
            // Questionable? what if the event is E2E?
            return E2EDecoration.NONE
        }
        if (event.sendState != SendState.SYNCED) {
            // we don't display e2e decoration if event not synced back
            return E2EDecoration.NONE
        }

        return when (event.mxDecryptionResult?.verificationState) {
            MessageVerificationState.VERIFIED -> E2EDecoration.NONE
            MessageVerificationState.SIGNED_DEVICE_OF_UNVERIFIED_USER -> E2EDecoration.NONE
            MessageVerificationState.UN_SIGNED_DEVICE_OF_VERIFIED_USER -> E2EDecoration.WARN_SENT_BY_UNVERIFIED
            // We neither verified this user so not interesting in that warning?
            MessageVerificationState.UN_SIGNED_DEVICE ->  E2EDecoration.NONE
            MessageVerificationState.UNKNOWN_DEVICE -> E2EDecoration.WARN_SENT_BY_DELETED_SESSION
            MessageVerificationState.UNSAFE_SOURCE -> E2EDecoration.WARN_UNSAFE_KEY
            null -> {
                // No verification state.
                // So could be a clear event, or a legacy decryption, or an UTD event
                if (!event.isEncrypted()) {
                    e2EDecorationForClearEventInE2ERoom(event, roomSummary)
                } else if (event.mxDecryptionResult != null) {
                    // No verification state, so could be a migrated old decryption?
                    if (event.mxDecryptionResult?.isSafe == true) {
                        // for past legacy decryption let's not decorate
                        E2EDecoration.NONE
                    } else {
                        E2EDecoration.WARN_UNSAFE_KEY
                    }
                } else {
                    // Undecrypted event
                    E2EDecoration.NONE
                }
            }
        }
    }

    private suspend fun getE2EDecoration(roomSummary: RoomSummary?, event: Event): E2EDecoration {
        if (roomSummary?.isEncrypted != true) {
            // No decoration for clear room
            // Questionable? what if the event is E2E?
            return E2EDecoration.NONE
        }
        if (event.sendState != SendState.SYNCED) {
            // we don't display e2e decoration if event not synced back
            return E2EDecoration.NONE
        }
        val userCrossSigningInfo = session.cryptoService()
                .crossSigningService()
                .getUserCrossSigningKeys(event.senderId.orEmpty())

        if (userCrossSigningInfo?.isTrusted() == true) {
            return if (event.isEncrypted()) {
                // Do not decorate failed to decrypt, or redaction (we lost sender device info)
                if (event.getClearType() == EventType.ENCRYPTED || event.isRedacted()) {
                    E2EDecoration.NONE
                } else {
                    val sendingDevice = event.getSenderKey()
                            ?.let {
                                session.cryptoService().deviceWithIdentityKey(
                                        event.senderId.orEmpty(),
                                        it,
                                        event.content?.get("algorithm") as? String ?: ""
                                )
                            }
                    if (event.mxDecryptionResult?.isSafe == false) {
                        E2EDecoration.WARN_UNSAFE_KEY
                    } else {
                        when {
                            sendingDevice == null -> {
                                // For now do not decorate this with warning
                                // maybe it's a deleted session
                                E2EDecoration.WARN_SENT_BY_DELETED_SESSION
                            }
                            sendingDevice.trustLevel == null -> {
                                E2EDecoration.WARN_SENT_BY_UNKNOWN
                            }
                            sendingDevice.trustLevel?.isVerified().orFalse() -> {
                                E2EDecoration.NONE
                            }
                            else -> {
                                E2EDecoration.WARN_SENT_BY_UNVERIFIED
                            }
                        }
                    }
                }
            } else {
                e2EDecorationForClearEventInE2ERoom(event, roomSummary)
            }
        } else {
            return if (!event.isEncrypted()) {
                e2EDecorationForClearEventInE2ERoom(event, roomSummary)
            } else if (event.mxDecryptionResult != null) {
                if (event.mxDecryptionResult?.isSafe == true) {
                    E2EDecoration.NONE
                } else {
                    E2EDecoration.WARN_UNSAFE_KEY
                }
            } else {
                E2EDecoration.NONE
            }
        }
    }

    private fun e2EDecorationForClearEventInE2ERoom(event: Event, roomSummary: RoomSummary) =
            if (event.isStateEvent()) {
                // Do not warn for state event, they are always in clear
                E2EDecoration.NONE
            } else {
                val ts = roomSummary.encryptionEventTs ?: 0
                val eventTs = event.originServerTs ?: 0
                // If event is in clear after the room enabled encryption we should warn
                if (eventTs > ts) E2EDecoration.WARN_IN_CLEAR else E2EDecoration.NONE
            }

    /**
     * Tiles type message never show the sender information (like verification request), so we should repeat it for next message
     * even if same sender.
     */
    private fun isTileTypeMessage(event: TimelineEvent?): Boolean {
        return when (event?.root?.getClearType()) {
            EventType.KEY_VERIFICATION_DONE,
            EventType.KEY_VERIFICATION_CANCEL -> true
            EventType.MESSAGE -> {
                event.getVectorLastMessageContent() is MessageVerificationRequestContent
            }
            else -> false
        }
    }
}
