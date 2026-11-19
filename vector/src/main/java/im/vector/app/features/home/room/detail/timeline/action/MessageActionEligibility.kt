/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.action

import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.features.home.room.detail.composer.spliceMentionIds
import im.vector.app.features.home.room.detail.timeline.helper.renderPerMessageProfile
import im.vector.app.features.home.room.detail.timeline.helper.withoutPerMessageProfileFallback
import im.vector.app.features.media.FailedMediaTracker
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpResult
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.share.forwardPayload
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.app.features.translation.TranslationSettings
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.session.room.timeline.isPoll
import org.matrix.android.sdk.api.session.room.timeline.isSticker
import org.matrix.android.sdk.api.util.ContentUtils
import javax.inject.Inject

class MessageActionEligibility @Inject constructor(
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        private val checkIfCanRedactEventUseCase: CheckIfCanRedactEventUseCase,
        private val translationSettings: TranslationSettings,
        private val messageTranslationStore: MessageTranslationStore,
        private val failedMediaTracker: FailedMediaTracker,
        private val checkIfCanReplyEventUseCase: CheckIfCanReplyEventUseCase,
) {

    fun roomPermissions(roomId: String, powerLevels: RoomPowerLevels? = null): ActionPermissions {
        val room = session.getRoom(roomId) ?: return ActionPermissions()
        if (room.roomSummary()?.membership != Membership.JOIN) return ActionPermissions()
        val levels = powerLevels ?: room.stateService().getRoomPowerLevels()
        return ActionPermissions(
                canSendMessage = levels.isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE),
                canReact = levels.isUserAllowedToSend(session.myUserId, false, EventType.REACTION),
                canRedact = levels.isUserAbleToRedact(session.myUserId),
                canPinUnpin = levels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_PINNED_EVENT),
                canSendRedaction = levels.isUserAllowedToSend(session.myUserId, false, EventType.REDACTION),
        )
    }

    fun canReply(event: TimelineEvent, messageContent: MessageContent?, permissions: ActionPermissions): Boolean =
            checkIfCanReplyEventUseCase.execute(event, messageContent, permissions)

    fun canCopy(msgType: String?, messageContent: MessageContent? = null): Boolean {
        when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> return true
        }
        if (messageContent is MessageGalleryContent) return messageContent.galleryCaption() != null
        return (messageContent as? MessageWithAttachmentContent)?.getCaption() != null
    }

    fun canCopy(event: TimelineEvent): Boolean {
        val content = event.getVectorLastMessageContent()
        return canCopy(content?.msgType, content)
    }

    fun canForward(event: TimelineEvent, msgType: String? = event.getVectorLastMessageContent()?.msgType): Boolean {
        if (event.root.getClearType() == EventType.STICKER) return true
        if (event.root.getClearType() != EventType.MESSAGE) return false
        return when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    fun canReplyInThread(
            event: TimelineEvent,
            messageContent: MessageContent?,
            canSendMessage: Boolean,
            isFromThreadTimeline: Boolean,
    ): Boolean {
        if (isFromThreadTimeline || event.root.isRedacted()) return false
        if (event.root.isThread()) return false
        if (event.root.getClearType() != EventType.MESSAGE &&
                !event.isSticker() && !event.isPoll()) return false
        if (!canSendMessage) return false
        // Disabled threads still offer the opt-in prompt when the server supports them.
        if (!vectorPreferences.areThreadMessagesEnabled() &&
                !session.homeServerCapabilitiesService().getHomeServerCapabilities().canUseThreading) return false
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_GALLERY,
            MessageType.MSGTYPE_GALLERY_STABLE,
            MessageType.MSGTYPE_LOCATION,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    fun canShare(msgType: String?): Boolean {
        return when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    // A decoding failure still leaves a downloaded file that can be shared or saved.
    fun isFailedMedia(messageContent: MessageContent?): Boolean {
        val content = messageContent as? MessageWithAttachmentContent ?: return false
        if (!failedMediaTracker.isFailed(content.getFileUrl())) return false
        val downloaded = session.fileService().getLocalFileFor(
                mxcUrl = content.getFileUrl(),
                fileName = content.body,
                mimeType = content.mimeType,
                isEncrypted = content.encryptedFileInfo != null
        )
        return downloaded == null
    }

    fun canPin(event: TimelineEvent): Boolean {
        if (event.root.isRedacted()) return false
        return event.root.getClearType() in listOf(EventType.MESSAGE, EventType.STICKER) + EventType.POLL_START.values
    }

    fun pinAction(roomId: String, eventId: String): EventSharedAction {
        val pinned = session.getRoom(roomId)?.stateService()
                ?.getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                ?.content.toModel<RoomPinnedEventsContent>()?.pinned.orEmpty()
        return if (eventId in pinned) EventSharedAction.Unpin(eventId) else EventSharedAction.Pin(eventId)
    }

    fun canTranslate(messageContent: MessageContent?): Boolean =
            (translationSettings.engine != null || translationSettings.backupEngine != null) &&
                    messageContent?.msgType != MessageType.MSGTYPE_LOCATION &&
                    canCopy(messageContent?.msgType, messageContent) && messageContent?.body?.isNotBlank() == true

    fun canTranslate(event: TimelineEvent): Boolean =
            canTranslate(event.getVectorLastMessageContent()) &&
                    (messageTranslationStore.isTranslated(event) || !messageTranslationStore.isTranslating(event.eventId))

    suspend fun translationAction(event: TimelineEvent): EventSharedAction? {
        val content = event.getVectorLastMessageContent() ?: return null
        if (!canTranslate(content)) return null
        if (messageTranslationStore.isTranslated(event)) return EventSharedAction.Untranslate(event.eventId)
        if (messageTranslationStore.isTranslating(event.eventId)) return null
        return EventSharedAction.Translate(
                event.eventId,
                MessageTranslationStore.sourceOf(event),
                copyBody(event, content),
                translatableFormattedBody(event, content),
        )
    }

    // PGP markup describes the encrypted payload; translate its decrypted plain text.
    private suspend fun translatableFormattedBody(timelineEvent: TimelineEvent, messageContent: MessageContent): String? {
        if (computePgpDecryptedBody(timelineEvent) != null) return null
        val profile = timelineEvent.renderPerMessageProfile(
                timelineEvent.senderInfo.disambiguatedDisplayName,
                vectorPreferences.arePerMessageProfilesEnabled()
        )
        val formatted = (messageContent as? MessageContentWithFormattedBody)
                ?.matrixFormattedBody
                ?.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
                ?: return null
        return if (messageContent.relatesTo?.inReplyTo?.eventId != null) {
            ContentUtils.extractUsefulTextFromHtmlReply(formatted)
        } else {
            formatted
        }
    }

    fun canRedact(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean =
            actionPermissions.canSendRedaction && checkIfCanRedactEventUseCase.execute(event, actionPermissions)

    fun forwardPayload(event: TimelineEvent): Map<String, Any?> =
            session.forwardPayload(event.root, event.getLastEditNewContent())

    suspend fun computePgpDecryptedBody(timelineEvent: TimelineEvent): CharSequence? {
        if (!pgpKeyStore.isEnabled || timelineEvent.isEncrypted()) return null
        val body = (timelineEvent.getVectorLastMessageContent() as? MessageTextContent)?.body ?: return null
        if (!PgpUtils.bodyContainsPgp(body)) return null
        val armored = PgpUtils.extractArmoredBlock(body) ?: return null
        return (pgpServiceManager.decrypt(armored) as? PgpResult.Success)?.data
    }

    // Raw bodies can contain quoted replies and PGP armor rather than the text being copied.
    suspend fun copyBody(timelineEvent: TimelineEvent, messageContent: MessageContent, mentionsAsIds: Boolean = false): String {
        computePgpDecryptedBody(timelineEvent)?.let { return it.toString() }
        val profile = timelineEvent.renderPerMessageProfile(
                timelineEvent.senderInfo.disambiguatedDisplayName,
                vectorPreferences.arePerMessageProfilesEnabled()
        )
        val formatted = (messageContent as? MessageContentWithFormattedBody)
                ?.matrixFormattedBody
                ?.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
        val rawBody = messageContent.body.withoutPerMessageProfileFallback(profile.fallbackDisplayName)
        val body = if (messageContent.relatesTo?.inReplyTo?.eventId != null) {
            ContentUtils.extractUsefulTextFromReply(rawBody, formatted)
        } else {
            rawBody
        }
        // Keep mention IDs so a pasted message can recreate mention pills.
        return if (mentionsAsIds) spliceMentionIds(body, formatted?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }) else body
    }

    /** The sender name the timeline shows for [event], honoring per-message profiles. */
    fun senderDisplayName(event: TimelineEvent): String =
            event.renderPerMessageProfile(
                    event.senderInfo.disambiguatedDisplayName,
                    vectorPreferences.arePerMessageProfilesEnabled()
            ).senderName
}
