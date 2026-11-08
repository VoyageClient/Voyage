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
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpResult
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.share.isDirectRoom
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.toForwardPayload
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.util.ContentUtils
import javax.inject.Inject

/** Which of copy / forward / redact apply to an event, and what each one carries; shared by the actions sheet and multi-select. */
class MessageActionEligibility @Inject constructor(
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        private val checkIfCanRedactEventUseCase: CheckIfCanRedactEventUseCase,
) {

    fun canCopy(msgType: String?, messageContent: MessageContent? = null): Boolean {
        // Text-shaped messages: always copyable (body is the user-typed text).
        when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> return true
        }
        // Media with an MSC2530 caption: body is the caption — copyable.
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

    fun canRedact(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean =
            checkIfCanRedactEventUseCase.execute(event, actionPermissions)

    fun forwardPayload(event: TimelineEvent): Map<String, Any?> =
            event.root.toForwardPayload(event.getLastEditNewContent(), includeSourceLink = !session.isDirectRoom(event.roomId))

    // Decrypted plaintext for a PGP-over-plaintext message, for the long-press preview; null when
    // not a PGP message, PGP disabled, or it can't be decrypted (then the raw body is shown).
    suspend fun computePgpDecryptedBody(timelineEvent: TimelineEvent): CharSequence? {
        if (!pgpKeyStore.isEnabled || timelineEvent.isEncrypted()) return null
        val body = (timelineEvent.getVectorLastMessageContent() as? MessageTextContent)?.body ?: return null
        if (!PgpUtils.bodyContainsPgp(body)) return null
        val armored = PgpUtils.extractArmoredBlock(body) ?: return null
        return (pgpServiceManager.decrypt(armored) as? PgpResult.Success)?.data
    }

    // Text put on the clipboard by "Copy": the decrypted plaintext for a PGP message, otherwise the
    // real message with any legacy reply fallback stripped — so a (plaintext or encrypted) reply
    // copies what's shown, not the quoted "> <@user> …" original (which for a reply to an encrypted
    // message is a whole armored PGP block).
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
        // Mentions read as names in the body; the clipboard carries the ids instead, which a composer
        // pills back into real mentions.
        return if (mentionsAsIds) spliceMentionIds(body, formatted?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }) else body
    }

    /** The sender name the timeline shows for [event], honoring per-message profiles. */
    fun senderDisplayName(event: TimelineEvent): String =
            event.renderPerMessageProfile(
                    event.senderInfo.disambiguatedDisplayName,
                    vectorPreferences.arePerMessageProfilesEnabled()
            ).senderName
}
