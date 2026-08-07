/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.action

import android.content.Context
import android.text.format.DateUtils
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.R
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.platform.EmptyAction
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.render.ProcessBodyOfReplyToEventUseCase
import im.vector.app.features.home.room.detail.timeline.tools.attachmentPreviewText
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ImagePackUsageFilter
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpResult
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.pgp.PgpUtils
import im.vector.app.features.reactions.data.QuickReactionsDataSource
import im.vector.app.features.redaction.preservation.RedactedContentRepository
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.redaction.preservation.RedactedContentRevealManager
import im.vector.app.features.redaction.preservation.RedactionPreservationSettings
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.isTextMessage
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVerificationRequestContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.relation.ReactionContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import org.matrix.android.sdk.api.session.room.timeline.hasBeenEdited
import org.matrix.android.sdk.api.session.room.timeline.isPoll
import org.matrix.android.sdk.api.session.room.timeline.isRootThread
import org.matrix.android.sdk.api.session.room.timeline.isSticker
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap
import timber.log.Timber

/**
 * Information related to an event and used to display preview in contextual bottom sheet.
 */
class MessageActionsViewModel @AssistedInject constructor(
        @Assisted private val initialState: MessageActionState,
        @ApplicationContext private val context: Context,
        private val eventHtmlRenderer: Lazy<EventHtmlRenderer>,
        private val htmlCompressor: VectorHtmlCompressor,
        private val session: Session,
        private val noticeEventFormatter: NoticeEventFormatter,
        private val errorFormatter: ErrorFormatter,
        private val stringProvider: StringProvider,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val textRendererFactory: im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer.Factory,
        private val vectorPreferences: VectorPreferences,
        private val checkIfCanReplyEventUseCase: CheckIfCanReplyEventUseCase,
        private val checkIfCanRedactEventUseCase: CheckIfCanRedactEventUseCase,
        private val processBodyOfReplyToEventUseCase: ProcessBodyOfReplyToEventUseCase,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        private val imagePackProvider: ImagePackProvider,
        private val quickReactionsDataSource: QuickReactionsDataSource,
        private val redactionSettings: RedactionPreservationSettings,
        private val redactedContentRevealManager: RedactedContentRevealManager,
        private val redactedContentRepository: RedactedContentRepository,
        private val redactedContentRestorer: RedactedContentRestorer,
        private val serverAdminStatusDataSource: ServerAdminStatusDataSource,
) : VectorViewModel<MessageActionState, EmptyAction, EmptyViewEvents>(initialState) {

    private val informationData = initialState.informationData
    private val room = session.getRoom(initialState.roomId)
    private val pillsPostProcessor by lazy {
        pillsPostProcessorFactory.create(initialState.roomId)
    }
    private val textRenderer by lazy {
        textRendererFactory.create(initialState.roomId)
    }

    private val eventIdFlow = MutableStateFlow(initialState.eventId)

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MessageActionsViewModel, MessageActionState> {
        override fun create(initialState: MessageActionState): MessageActionsViewModel
    }

    companion object : MavericksViewModelFactory<MessageActionsViewModel, MessageActionState> by hiltMavericksViewModelFactory()

    init {
        PerfTrace.time("longpress.vm.init") {
            // Seed the timeline event synchronously so the sheet opens with the full action
            // set on the first frame. Without this seed, the sheet starts with an empty
            // actions list (defaults to emptyList()) and the sender preview can briefly show
            // "-" until liveTimelineEvent's LiveData round-trips through the main thread.
            if (room != null) {
                room.getTimelineEvent(initialState.eventId)?.let { event ->
                    setState { copy(timelineEvent = Success(event)) }
                }
            }

            // Seed action permissions synchronously so reactions / edit / redact / etc. are present in
            // the very first state, instead of the sheet first rendering with the all-false defaults
            // (no react/edit) and the real permissions popping in a beat later. getRoomPowerLevels() is
            // a synchronous in-memory read once the room state is loaded — which it is by the time a
            // message can be long-pressed — so this doesn't block on I/O in practice.
            if (room != null) {
                val initial = room.stateService().getRoomPowerLevels()
                val permissions = ActionPermissions(
                        canSendMessage = initial.isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE),
                        canReact = initial.isUserAllowedToSend(session.myUserId, false, EventType.REACTION),
                        canRedact = initial.isUserAbleToRedact(session.myUserId),
                        canPinUnpin = initial.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_PINNED_EVENT),
                )
                setState { copy(actionPermissions = permissions) }
            }

            initialState.informationData.sharedByUserId?.let { sharedBy ->
                val displayName = room?.membershipService()?.getRoomMember(sharedBy)?.displayName
                setState { copy(sharedByDisplayName = displayName?.takeIf { it.isNotBlank() } ?: sharedBy) }
            }

            observeEvent()
            observeReactions()
            observePowerLevel()
            observeTimelineEventState()
        }
    }

    override fun handle(action: EmptyAction) = Unit

    private fun observePowerLevel() {
        if (room == null) {
            return
        }
        room.flow().liveRoomPowerLevels()
                .onEach { roomPowerLevels ->
                    val canReact = roomPowerLevels.isUserAllowedToSend(session.myUserId, false, EventType.REACTION)
                    val canRedact = roomPowerLevels.isUserAbleToRedact(session.myUserId)
                    val canSendMessage = roomPowerLevels.isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE)
                    val canPinUnpin = roomPowerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_PINNED_EVENT)
                    val permissions = ActionPermissions(
                            canSendMessage = canSendMessage,
                            canRedact = canRedact,
                            canReact = canReact,
                            canPinUnpin = canPinUnpin
                    )
                    setState {
                        copy(actionPermissions = permissions)
                    }
                }.launchIn(viewModelScope)
    }

    private fun observeEvent() {
        if (room == null) return
        room.flow()
                .liveTimelineEvent(initialState.eventId)
                .unwrap()
                .execute {
                    copy(timelineEvent = it)
                }
    }

    private fun observeReactions() {
        if (room == null) return
        val quickReactions = pruneDeletedEmotes(quickReactionsDataSource.getQuickReactions())
        eventIdFlow
                .flatMapLatest { eventId ->
                    room.flow()
                            .liveAnnotationSummary(eventId)
                            .map { annotations ->
                                quickReactions.map { emoji ->
                                    ToggleState(emoji, annotations.getOrNull()?.reactionsSummary?.firstOrNull { it.key == emoji }?.addedByMe ?: false)
                                }
                            }
                }
                .execute {
                    copy(quickStates = it)
                }
    }

    // Forget quick-reaction emotes whose image pack no longer has them (they'd render blank / send empty ::).
    private fun pruneDeletedEmotes(quickReactions: List<String>): List<String> {
        if (quickReactions.none { it.isMxcUrl() }) return quickReactions
        val validMxcs = ImagePackUsageFilter.emoticonPacks(imagePackProvider.getEnabledImagePacks(initialState.roomId))
                .flatMap { it.images }
                .mapTo(HashSet()) { it.mxcUrl }
        val pruned = quickReactions.filter { !it.isMxcUrl() || it in validMxcs }
        if (pruned.size != quickReactions.size) {
            quickReactionsDataSource.setQuickReactions(pruned)
        }
        return pruned
    }

    private fun observeTimelineEventState() {
        onEach(MessageActionState::timelineEvent, MessageActionState::actionPermissions) { timelineEvent, permissions ->
            val nonNullTimelineEvent = timelineEvent() ?: return@onEach
            eventIdFlow.tryEmit(nonNullTimelineEvent.eventId)
            // computeMessageBody runs the Markwon HTML render (tens to hundreds of ms on a slow device);
            // keep it off the main thread so the sheet opens/animates immediately instead of holding the
            // "-" preview placeholder while the main thread blocks on the render.
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val restored = redactedContentRestorer.restoreEvent(nonNullTimelineEvent)
                    // Actions read the raw event: which of Reveal/Hide/Edit/Redact apply depends on the
                    // message still being redacted. Everything that renders content reads the restored one.
                    val events = actionsForEvent(nonNullTimelineEvent, permissions)
                    val body = computeMessageBody(restored ?: nonNullTimelineEvent)
                    setState {
                        copy(
                                eventId = nonNullTimelineEvent.eventId,
                                messageBody = body,
                                restoredEvent = restored,
                                actions = events
                        )
                    }
                } catch (failure: Throwable) {
                    // A throw here would otherwise die silently and leave the sheet empty.
                    Timber.e(failure, "Failed to compute message actions for ${nonNullTimelineEvent.eventId}")
                }
            }
        }
    }

    private suspend fun computeMessageBody(timelineEvent: TimelineEvent): CharSequence {
        return try {
            if (timelineEvent.root.isRedacted()) {
                noticeEventFormatter.formatRedactedEvent(timelineEvent.root)
            } else {
                computePgpDecryptedBody(timelineEvent) ?: when (timelineEvent.root.getClearType()) {
                    EventType.MESSAGE,
                    EventType.STICKER -> {
                        val messageContent: MessageContent? = timelineEvent.getVectorLastMessageContent()
                        val isReply = messageContent?.relatesTo?.inReplyTo?.eventId != null
                        if (messageContent is MessageTextContent && messageContent.format == MessageFormat.FORMAT_MATRIX_HTML) {
                            // Strip the legacy reply fallback ("In reply to" / "> <@user> …") that
                            // outdated clients embed in the body, so the preview shows only the message.
                            val html = messageContent.formattedBody
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { processBodyOfReplyToEventUseCase.stripExistingMxReply(it) }
                                    ?.let { htmlCompressor.compress(it) }
                                    ?: messageContent.body.let { if (isReply) ContentUtils.extractUsefulTextFromReply(it) else it }

                            textRenderer.render(eventHtmlRenderer.get().render(html, pillsPostProcessor))
                        } else if (messageContent is MessageVerificationRequestContent) {
                            stringProvider.getString(CommonStrings.verification_request_room_list_preview)
                        } else if (messageContent is MessageFileContent) {
                            attachmentPreviewText(context, R.drawable.ic_paperclip, messageContent.getFileName().orEmpty())
                        } else if (messageContent is MessageAudioContent) {
                            val formattedDuration = DateUtils.formatElapsedTime(((messageContent.audioInfo?.duration ?: 0) / 1000).toLong())
                            if (messageContent.voiceMessageIndicator != null) {
                                attachmentPreviewText(context, R.drawable.ic_microphone, stringProvider.getString(CommonStrings.voice_message_reply_content, formattedDuration))
                            } else {
                                attachmentPreviewText(context, R.drawable.ic_attachment_voice_file, messageContent.getFileName().orEmpty())
                            }
                        } else if (messageContent is MessageWithAttachmentContent) {
                            // Image/video: the thumbnail is shown separately, so just the filename here.
                            messageContent.getFileName()
                        } else if (messageContent?.msgType == MessageType.MSGTYPE_LOCATION) {
                            // The text representation of a location is the same on every API; only the
                            // long-press preview's map (buildLocationUiData) is gated to Lollipop+.
                            noticeEventFormatter.formatLocationNotice(timelineEvent.root, timelineEvent.senderInfo.disambiguatedDisplayName)
                        } else if (messageContent == null) {
                            // Same placeholder the timeline shows for an unparseable message, instead
                            // of a blank preview.
                            stringProvider.getString(CommonStrings.malformed_message)
                        } else {
                            // Run the text renderer so bare permalinks / @room in a plain body pill too.
                            messageContent.body
                                    .let { if (isReply) ContentUtils.extractUsefulTextFromReply(it) else it }
                                    .let { textRenderer.render(it) }
                        }
                    }
                    EventType.STATE_ROOM_NAME,
                    EventType.STATE_ROOM_TOPIC,
                    EventType.STATE_ROOM_AVATAR,
                    EventType.STATE_ROOM_MEMBER,
                    EventType.STATE_ROOM_ALIASES,
                    EventType.STATE_ROOM_CANONICAL_ALIAS,
                    EventType.STATE_ROOM_HISTORY_VISIBILITY,
                    EventType.STATE_ROOM_SERVER_ACL,
                    EventType.CALL_INVITE,
                    EventType.CALL_CANDIDATES,
                    EventType.CALL_HANGUP,
                    EventType.CALL_ANSWER -> {
                        // A null notice (e.g. a repeated knock that is hidden as a debug event) falls back
                        // to the debug line so the long-press / reply preview is not blank.
                        noticeEventFormatter.format(timelineEvent, room?.roomSummary()?.isDirect.orFalse())
                                ?: noticeEventFormatter.formatDebugOrUnhandled(timelineEvent.root)
                    }
                    in EventType.POLL_START.values -> {
                        (timelineEvent.getVectorLastMessageContent() as? MessagePollContent)?.getBestPollCreationInfo()?.question?.getBestQuestion()
                                ?: stringProvider.getString(CommonStrings.message_reply_to_poll_preview)
                    }
                    in EventType.POLL_END.values -> {
                        stringProvider.getString(CommonStrings.message_reply_to_ended_poll_preview)
                    }
                    in EventType.ELEMENT_CALL_NOTIFY.values -> {
                        stringProvider.getString(CommonStrings.call_unsupported)
                    }
                    else -> {
                        // Reactions, redactions, verification, … — show the same notice text the
                        // timeline uses. Anything unrenderable falls back to a debug line (known type)
                        // or the accent "not handled" notice (unknown type), matching the timeline.
                        noticeEventFormatter.format(timelineEvent, room?.roomSummary()?.isDirect.orFalse())
                                ?: noticeEventFormatter.formatDebugOrUnhandled(timelineEvent.root)
                    }
                }
            }
        } catch (failure: Throwable) {
            errorFormatter.toHumanReadable(failure)
        }
    }

    private suspend fun actionsForEvent(timelineEvent: TimelineEvent, actionPermissions: ActionPermissions): List<EventSharedAction> {
        val messageContent = timelineEvent.getVectorLastMessageContent()
        val msgType = messageContent?.msgType

        return arrayListOf<EventSharedAction>().apply {
            val eventId = timelineEvent.eventId
            // Recovery actions for local echoes that haven't synced (or have failed) yet. No Remove or
            // Cancel: Redact is added below for every event and covers both — TimelineFragment routes it
            // to a local removal for a failed echo, and the SDK cancels a still-unsent one outright.
            if (timelineEvent.root.sendState.hasFailed() && canRetry(timelineEvent, actionPermissions)) {
                add(EventSharedAction.Resend(eventId))
            }
            // Then the full action set, regardless of sync/redaction state.
            addActionsForSyncedState(timelineEvent, actionPermissions, messageContent, msgType)
        }
    }

    // Decrypted plaintext for a PGP-over-plaintext message, for the long-press preview; null when
    // not a PGP message, PGP disabled, or it can't be decrypted (then the raw body is shown).
    private suspend fun computePgpDecryptedBody(timelineEvent: TimelineEvent): CharSequence? {
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
    private suspend fun pgpCopyBody(timelineEvent: TimelineEvent, messageContent: MessageContent): String {
        computePgpDecryptedBody(timelineEvent)?.let { return it.toString() }
        val body = messageContent.body
        return if (messageContent.relatesTo?.inReplyTo?.eventId != null) {
            ContentUtils.extractUsefulTextFromReply(body, (messageContent as? MessageContentWithFormattedBody)?.matrixFormattedBody)
        } else {
            body
        }
    }

    private suspend fun ArrayList<EventSharedAction>.addViewSourceItems(timelineEvent: TimelineEvent) {
        add(EventSharedAction.ViewSource(timelineEvent.root.toContentStringWithIndent()))
        if (timelineEvent.isEncrypted() && timelineEvent.root.mxDecryptionResult != null) {
            val decryptedContent = timelineEvent.root.toClearContentStringWithIndent()
                    ?: stringProvider.getString(CommonStrings.encryption_information_decryption_error)
            add(EventSharedAction.ViewDecryptedSource(decryptedContent))
        } else {
            // PGP: "View decrypted source" = the event content with body/formatted_body rewritten
            // to the decrypted plaintext, shown in the same JSON viewer olm events use.
            pgpDecryptedContentJson(timelineEvent)?.let { add(EventSharedAction.ViewDecryptedSource(it)) }
        }
    }

    // Full event JSON (matching "View source") but with content.body/formatted_body rewritten to the
    // decrypted plaintext, mirroring how olm's "View decrypted source" shows the whole clear event.
    @Suppress("UNCHECKED_CAST")
    private suspend fun pgpDecryptedContentJson(timelineEvent: TimelineEvent): String? {
        val plain = computePgpDecryptedBody(timelineEvent)?.toString() ?: return null
        val eventMap = timelineEvent.root.toContent().toMutableMap()
        val contentMap = (eventMap["content"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        contentMap["body"] = plain
        (contentMap["formatted_body"] as? String)?.let { formatted ->
            // formatted_body carries its own armored block (the decrypted HTML), not a copy of body.
            val decryptedFormatted = PgpUtils.extractArmoredBlock(formatted)
                    ?.let { (pgpServiceManager.decrypt(it) as? PgpResult.Success)?.data }
            contentMap["formatted_body"] = decryptedFormatted ?: plain
        }
        eventMap["content"] = contentMap
        return JSONObject(eventMap as Map<*, *>).toString(4)
    }

    private suspend fun ArrayList<EventSharedAction>.addActionsForSyncedState(
            timelineEvent: TimelineEvent,
            actionPermissions: ActionPermissions,
            messageContent: MessageContent?,
            msgType: String?
    ) {
        val eventId = timelineEvent.eventId
        // Reply / react / view-reactions are allowed even on redacted events.
        if (canReply(timelineEvent, messageContent, actionPermissions)) {
            add(EventSharedAction.Reply(eventId))
        }
        if (actionPermissions.canReact) {
            add(EventSharedAction.AddReaction(eventId))
        }
        if (canViewReactions(timelineEvent)) {
            add(EventSharedAction.ViewReactions(informationData))
        }
        if (timelineEvent.root.isRedacted()) {
            // Hide applies to what the timeline is actually rendering; Reveal to what it could render.
            val isShowingContent = redactedContentRestorer.isShowingRestoredContent(timelineEvent)
            when {
                isShowingContent -> add(EventSharedAction.HideRedacted(eventId))
                canRestoreRedactedContent(timelineEvent) -> add(EventSharedAction.RevealRedacted(eventId))
                else -> Unit
            }
        } else {
            if (canReplyInThread(timelineEvent, messageContent, actionPermissions)) {
                add(EventSharedAction.ReplyInThread(eventId, !timelineEvent.isRootThread()))
            }

            if (canViewInRoom(timelineEvent, messageContent, actionPermissions)) {
                add(EventSharedAction.ViewInRoom)
            }

            if (canEndPoll(timelineEvent, actionPermissions)) {
                add(EventSharedAction.EndPoll(timelineEvent.eventId))
            }

            if (canEdit(timelineEvent, session.myUserId, actionPermissions)) {
                add(EventSharedAction.Edit(eventId, timelineEvent.root.getClearType()))
            }

            if (canCopy(msgType, messageContent)) {
                // TODO copy images? html? see ClipBoard
                add(EventSharedAction.Copy(pgpCopyBody(timelineEvent, messageContent!!)))
            }

            if (timelineEvent.hasBeenEdited()) {
                add(EventSharedAction.ViewEditHistory(informationData))
            }

            if (canSave(msgType) && messageContent is MessageWithAttachmentContent) {
                add(EventSharedAction.Save(timelineEvent.eventId, messageContent))
            }

            if (canForward(timelineEvent, msgType)) {
                val baseContent = timelineEvent.getLastEditNewContent()
                        ?: timelineEvent.root.getClearContent().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val forwardContent = coerceWholeDoublesToLongs(baseContent - "m.relates_to") as Map<String, Any?>
                add(
                        EventSharedAction.Forward(
                                eventId = timelineEvent.eventId,
                                eventType = timelineEvent.root.getClearType(),
                                content = forwardContent
                        )
                )
            }

            if (canShare(msgType)) {
                add(EventSharedAction.Share(timelineEvent.eventId, messageContent!!))
            }

            if (actionPermissions.canPinUnpin && canPin(timelineEvent)) {
                if (eventId in getPinnedEventIds()) {
                    add(EventSharedAction.Unpin(eventId))
                } else {
                    add(EventSharedAction.Pin(eventId))
                }
            }

            if (canRedact(timelineEvent, actionPermissions)) {
                if (timelineEvent.root.getClearType() in EventType.POLL_START.values) {
                    add(
                            EventSharedAction.Redact(
                                    eventId,
                                    askForReason = informationData.senderId != session.myUserId,
                                    dialogTitleRes = CommonStrings.delete_poll_dialog_title,
                                    dialogDescriptionRes = CommonStrings.delete_poll_dialog_content
                            )
                    )
                } else {
                    add(
                            EventSharedAction.Redact(
                                    eventId,
                                    askForReason = informationData.senderId != session.myUserId,
                                    dialogTitleRes = CommonStrings.redact_event_dialog_title,
                                    dialogDescriptionRes = CommonStrings.redact_event_dialog_content
                            )
                    )
                }
            }
        }

        if (vectorPreferences.developerMode()) {
            add(EventSharedAction.CopyEventId(eventId))
            relatedEventId(timelineEvent)?.let { add(EventSharedAction.JumpToRelation(eventId, it)) }
            if (timelineEvent.isEncrypted() && timelineEvent.root.mCryptoError != null) {
                val keysBackupService = session.cryptoService().keysBackupService()
                if (keysBackupService.getState() == KeysBackupState.NotTrusted ||
                        (keysBackupService.getState() == KeysBackupState.ReadyToBackUp &&
                                keysBackupService.canRestoreKeys())
                ) {
                    add(EventSharedAction.UseKeyBackup)
                }
                if (session.cryptoService().getCryptoDeviceInfoList(session.myUserId).size > 1 ||
                        timelineEvent.senderInfo.userId != session.myUserId) {
                    add(EventSharedAction.ReRequestKey(timelineEvent.eventId))
                }
            }
            addViewSourceItems(timelineEvent)
        }
        add(EventSharedAction.CopyPermalink(eventId))
        if (session.myUserId != timelineEvent.root.senderId) {
            add(EventSharedAction.Separator)
            add(EventSharedAction.IgnoreUser(timelineEvent.root.senderId))
        }
    }

    // Redactions and reactions only surface in the timeline with hidden events shown, itself a developer-mode
    // setting, so the jump to the event they act on lives with the other developer actions.
    private fun relatedEventId(timelineEvent: TimelineEvent): String? {
        return when (timelineEvent.root.getClearType()) {
            // Room v11 (MSC2174) moved `redacts` from the event into its content.
            EventType.REDACTION -> timelineEvent.root.redacts ?: timelineEvent.root.content?.get("redacts") as? String
            EventType.REACTION -> timelineEvent.root.getClearContent().toModel<ReactionContent>()?.relatesTo?.eventId
            else -> null
        }
    }

    private fun canReply(event: TimelineEvent, messageContent: MessageContent?, actionPermissions: ActionPermissions): Boolean {
        return checkIfCanReplyEventUseCase.execute(event, messageContent, actionPermissions)
    }

    /**
     * Determine whether or not the Reply In Thread bottom sheet action will be visible
     * to the user.
     */
    private fun canReplyInThread(
            event: TimelineEvent,
            messageContent: MessageContent?,
            actionPermissions: ActionPermissions
    ): Boolean {
        // We let reply in thread visible even if threads are not enabled, with an enhanced flow to attract users
//        if (!vectorPreferences.areThreadMessagesEnabled()) return false
        // Disable beta prompt if the homeserver do not support threads
        if (!vectorPreferences.areThreadMessagesEnabled() &&
                !session.homeServerCapabilitiesService().getHomeServerCapabilities().canUseThreading) return false

        if (initialState.isFromThreadTimeline) return false
        if (event.root.isThread()) return false
        if (event.root.getClearType() != EventType.MESSAGE &&
                !event.isSticker() && !event.isPoll()) return false
        if (!actionPermissions.canSendMessage) return false
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    /**
     * Determine whether or not the view in room action will be available for the current event.
     */
    private fun canViewInRoom(
            event: TimelineEvent,
            messageContent: MessageContent?,
            actionPermissions: ActionPermissions
    ): Boolean {
        if (!vectorPreferences.areThreadMessagesEnabled()) return false
        if (!initialState.isFromThreadTimeline) return false
        if (event.root.getClearType() != EventType.MESSAGE &&
                !event.isSticker() && !event.isPoll()) return false
        if (!actionPermissions.canSendMessage) return false

        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_POLL_START,
            MessageType.MSGTYPE_POLL_END,
            MessageType.MSGTYPE_STICKER_LOCAL -> event.root.threadDetails?.isRootThread ?: false
            else -> false
        }
    }

    private fun canQuote(event: TimelineEvent, messageContent: MessageContent?, actionPermissions: ActionPermissions): Boolean {
        // Only event of type EventType.MESSAGE are supported for the moment
        if (event.root.getClearType() != EventType.MESSAGE) return false
        if (!actionPermissions.canSendMessage) return false
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> {
                true
            }
            else -> false
        }
    }

    private fun canRedact(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return checkIfCanRedactEventUseCase.execute(event, actionPermissions)
    }

    /**
     * Offered only for redacted messages we could actually show: either a local copy was captured
     * before the redaction, or the server implements MSC2815 and we are privileged enough to ask.
     */
    private suspend fun canRestoreRedactedContent(event: TimelineEvent): Boolean {
        if (event.root.isStateEvent()) return false
        // An already-preserved copy needs no permission and no server support.
        if (redactedContentRepository.hasPreservedContent(event.eventId)) return true
        if (!session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent) return false
        val powerLevels = room?.stateService()?.getRoomPowerLevels() ?: return false
        // mayBeAdmin, not isAdmin: MSC2815 is not Synapse-only, but the admin probe is, so anywhere it
        // can't answer the capability flag decides and a refusal comes back as the usual snackbar.
        return powerLevels.isUserAbleToRedact(session.myUserId) || serverAdminStatusDataSource.cachedStatus().mayBeAdmin
    }

    private fun canRetry(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return event.root.sendState.hasFailed() &&
                actionPermissions.canSendMessage &&
                (event.root.isAttachmentMessage() || event.root.isTextMessage())
    }

    private fun canViewReactions(event: TimelineEvent): Boolean {
        // Only event of type EventType.MESSAGE, EventType.STICKER, EventType.POLL_START, EventType.POLL_END are supported for the moment
        if (event.root.getClearType() !in listOf(EventType.MESSAGE, EventType.STICKER) + EventType.POLL_START.values + EventType.POLL_END.values) return false
        return event.annotations?.reactionsSummary?.isNotEmpty() ?: false
    }

    private fun canEdit(event: TimelineEvent, myUserId: String, actionPermissions: ActionPermissions): Boolean {
        if (!actionPermissions.canSendMessage) return false
        // TODO if user is admin or moderator
        if (event.root.senderId != myUserId) return false
        // Stickers: edit/add a caption.
        if (event.root.getClearType() == EventType.STICKER) return true
        // Only event of type EventType.MESSAGE and EventType.POLL_START are supported for the moment
        if (event.root.getClearType() !in listOf(EventType.MESSAGE) + EventType.POLL_START.values) return false
        val messageContent = event.root.getClearContent().toModel<MessageContent>()
        return when (messageContent?.msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_EMOTE,
            // Media: edit/add a caption.
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_AUDIO -> true
            else -> canEditPoll(event)
        }
    }

    private fun canCopy(msgType: String?, messageContent: MessageContent? = null): Boolean {
        // Text-shaped messages: always copyable (body is the user-typed text).
        when (msgType) {
            MessageType.MSGTYPE_TEXT,
            MessageType.MSGTYPE_NOTICE,
            MessageType.MSGTYPE_EMOTE,
            MessageType.MSGTYPE_LOCATION -> return true
        }
        // Media with an MSC2530 caption: body is the caption — copyable.
        return (messageContent as? MessageWithAttachmentContent)?.getCaption() != null
    }

    // Decrypted event content goes through Moshi's Any adapter, which parses every JSON number
    // as Double. Re-serializing would emit "w":1080.0 — Synapse strictly rejects that
    // (M_BAD_JSON "Bad JSON value: float"). Round-trip whole-number Doubles back to Long.
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }

    private fun canForward(event: TimelineEvent, msgType: String?): Boolean {
        if (event.root.getClearType() == EventType.STICKER) return true
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

    private fun canShare(msgType: String?): Boolean {
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

    private fun canSave(msgType: String?): Boolean {
        return when (msgType) {
            MessageType.MSGTYPE_IMAGE,
            MessageType.MSGTYPE_AUDIO,
            MessageType.MSGTYPE_VIDEO,
            MessageType.MSGTYPE_FILE,
            MessageType.MSGTYPE_STICKER_LOCAL -> true
            else -> false
        }
    }

    private fun canPin(event: TimelineEvent): Boolean {
        if (event.root.isRedacted()) return false
        return event.root.getClearType() in listOf(EventType.MESSAGE, EventType.STICKER) + EventType.POLL_START.values
    }

    private fun getPinnedEventIds(): List<String> {
        return room?.stateService()
                ?.getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                ?.content
                .toModel<RoomPinnedEventsContent>()
                ?.pinned
                .orEmpty()
    }

    private fun canEndPoll(event: TimelineEvent, actionPermissions: ActionPermissions): Boolean {
        return event.root.getClearType() in EventType.POLL_START.values &&
                canRedact(event, actionPermissions) &&
                event.annotations?.pollResponseSummary?.closedTime == null
    }

    private fun canEditPoll(event: TimelineEvent): Boolean {
        return event.root.getClearType() in EventType.POLL_START.values &&
                event.annotations?.pollResponseSummary?.closedTime == null &&
                (event.annotations?.pollResponseSummary?.aggregatedContent?.totalVotes ?: 0) == 0
    }
}
