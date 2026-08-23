/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.SpannableString
import android.text.Spanned
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.withState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.bodyName
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.attachments.toContentAttachmentData
import im.vector.app.features.command.Command
import im.vector.app.features.command.CommandParser
import im.vector.app.features.command.ParsedCommand
import im.vector.app.features.home.room.detail.composer.rainbow.RainbowGenerator
import im.vector.app.features.home.room.detail.composer.voice.VoiceMessageRecorderView
import im.vector.app.features.home.room.list.watched.WatchedRooms
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.media.domain.usecase.DownloadMediaUseCase
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpRoomEncryptor
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.translation.OutgoingMessageTranslator
import im.vector.app.features.translation.TranslationLanguages
import im.vector.app.features.translation.TranslationSettings
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.RoomEncryptionAlgorithm
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.WatchedRoomInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFormattedCaption
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.model.relation.shouldRenderInThread
import org.matrix.android.sdk.api.session.room.model.tombstone.RoomTombstoneContent
import org.matrix.android.sdk.api.session.room.peeking.PeekResult
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.api.session.room.timeline.getTextEditableContent
import org.matrix.android.sdk.api.session.space.CreateSpaceParams
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap
import timber.log.Timber

class MessageComposerViewModel @AssistedInject constructor(
        @Assisted initialState: MessageComposerViewState,
        private val session: Session,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val commandParser: CommandParser,
        private val rainbowGenerator: RainbowGenerator,
        private val audioMessageHelper: AudioMessageHelper,
        private val clock: Clock,
        private val pgpKeyStore: PgpKeyStore,
        private val pgpRoomEncryptor: PgpRoomEncryptor,
        private val pgpDecryptor: PgpDecryptor,
        private val translationSettings: TranslationSettings,
        private val outgoingMessageTranslator: OutgoingMessageTranslator,
        private val emoteShortcodeProcessor: EmoteShortcodeProcessor,
        private val downloadMediaUseCase: DownloadMediaUseCase,
) : VectorViewModel<MessageComposerViewState, MessageComposerAction, MessageComposerViewEvents>(initialState) {

    private val room = session.getRoom(initialState.roomId)

    // Keep it out of state to avoid invalidate being called
    private var currentComposerText: CharSequence = ""

    // What was being typed when an edit took the composer over, put back once the edit ends. Editing is
    // the only mode which replaces the composer rather than keeping what is in it. Tracked next to the
    // state rather than read from it: the stash is taken and given back as the action is handled, while
    // a state read would settle a turn later, by which point the composer holds the edit.
    private var editOwnsComposer = false
    private var textStashedForEdit: CharSequence? = null

    // Links are read and their thumbnails uploaded while the message is still being typed, so that sending
    // it does not wait for a page fetch and an upload (MSC4095 carries them in the event).
    private val textPendingLinkPreview = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val sendPreparationLane = Dispatchers.Default.limitedParallelism(1)

    init {
        if (room != null) {
            loadDraftIfAny(room)
            observePowerLevelAndEncryption(room)
            subscribeToStateInternal(room)
            observeTypedLinks(room)
        } else {
            onRoomError()
        }
    }

    // Long enough that a link is not fetched letter by letter as it is pasted or typed out.
    private fun observeTypedLinks(room: Room) {
        textPendingLinkPreview
                .debounce(LINK_PREVIEW_TYPING_DEBOUNCE_MS)
                .onEach { room.sendService().prefetchLinkPreviews(it) }
                .launchIn(viewModelScope)
    }

    override fun handle(action: MessageComposerAction) {
        val room = this.room ?: return
        when (action) {
            is MessageComposerAction.EnterEditMode -> handleEnterEditMode(room, action)
            is MessageComposerAction.EnterQuoteMode -> handleEnterQuoteMode(room, action)
            is MessageComposerAction.EnterRegularMode -> handleEnterRegularMode(action)
            is MessageComposerAction.EnterReplyMode -> handleEnterReplyMode(room, action)
            is MessageComposerAction.SendMessage -> handleSendMessage(room, action)
            is MessageComposerAction.UserIsTyping -> handleUserIsTyping(room, action)
            is MessageComposerAction.OnTextChanged -> handleOnTextChanged(action)
            is MessageComposerAction.OnVoiceRecordingUiStateChanged -> handleOnVoiceRecordingUiStateChanged(action)
            is MessageComposerAction.StartRecordingVoiceMessage -> handleStartRecordingVoiceMessage(room)
            is MessageComposerAction.EndRecordingVoiceMessage -> handleEndRecordingVoiceMessage(room, action.isCancelled, action.rootThreadEventId)
            is MessageComposerAction.PlayOrPauseVoicePlayback -> handlePlayOrPauseVoicePlayback(action)
            MessageComposerAction.PauseRecordingVoiceMessage -> handlePauseRecordingVoiceMessage()
            MessageComposerAction.PlayOrPauseRecordingPlayback -> handlePlayOrPauseRecordingPlayback()
            is MessageComposerAction.InitializeVoiceRecorder -> handleInitializeVoiceRecorder(room, action.attachmentData)
            is MessageComposerAction.OnEntersBackground -> handleEntersBackground(room, action.composerText)
            is MessageComposerAction.VoiceWaveformTouchedUp -> handleVoiceWaveformTouchedUp(action)
            is MessageComposerAction.VoiceWaveformMovedTo -> handleVoiceWaveformMovedTo(action)
            is MessageComposerAction.AudioSeekBarMovedTo -> handleAudioSeekBarMovedTo(action)
            is MessageComposerAction.SlashCommandConfirmed -> handleSlashCommandConfirmed(room, action)
            is MessageComposerAction.InsertUserDisplayName -> handleInsertUserDisplayName(action)
            is MessageComposerAction.SetFullScreen -> handleSetFullScreen(action)
            MessageComposerAction.OnAttachmentsSent -> handleOnAttachmentsSent(room)
            is MessageComposerAction.SendSticker -> handleSendSticker(room, action)
            is MessageComposerAction.SendSedReplacement -> handleSendSedReplacement(room, action)
        }
    }

    private fun handleOnAttachmentsSent(room: Room) = withState { state ->
        currentComposerText = ""
        popDraft(room, state.sendMode)
    }

    private fun handleSendSedReplacement(room: Room, action: MessageComposerAction.SendSedReplacement) = withState { state ->
        // Re-resolve: the target may have finished sending since the fragment picked it out of the timeline.
        val targetEvent = room.getTimelineEvent(action.targetEventId)
        if (targetEvent == null) {
            _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.sed_error_target_gone)))
            return@withState
        }
        if (targetEvent.root.senderId == session.myUserId) {
            val messageContent = targetEvent.getVectorLastMessageContent()
            val inReplyTo = targetEvent.getRelationContent()?.inReplyTo?.eventId
            offloadSend {
                // The substitution runs on the editable text, so re-run the send-time preparation on
                // the result the way the original send did — mention tagging, emote shortcode tagging,
                // markdown, greentext — or a formatted message would come back as plain, its custom
                // emotes as literal :shortcode: text and its mention pills as bare names.
                val autoMarkdown = vectorPreferences.isMarkdownEnabled()
                // Drop the legacy <mx-reply> fallback: it is not part of the message, and splicing mentions
                // or quote runs out of it would pull the replied-to message's markup into the correction.
                val oldFormatted = ((messageContent as? MessageContentWithFormattedBody)?.matrixFormattedBody
                        ?: (messageContent as? MessageWithAttachmentContent)?.getFormattedCaption())
                        ?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
                val withMentions = spliceMentionSpans(action.newBody, oldFormatted) { userId ->
                    val member = room.membershipService().getRoomMember(userId)
                    listOfNotNull(member?.bodyName(), member?.displayName).distinct()
                }
                val newText = emoteShortcodeProcessor.process(room.roomId, withMentions)
                val quote = maybeBuildQuoteRunsForEdit(newText, null, oldFormatted)
                val repliedTo = inReplyTo?.let { room.getTimelineEvent(it) }
                if (messageContent is MessageWithAttachmentContent) {
                    room.relationService().editMediaCaption(
                            targetEvent,
                            quote?.first ?: newText,
                            quote?.second ?: room.sendService().computeFormattedHtml(newText, autoMarkdown)
                    )
                } else if (repliedTo != null) {
                    room.relationService().editReply(
                            targetEvent,
                            repliedTo,
                            quote?.first ?: newText,
                            quote?.second ?: room.sendService().computeFormattedHtml(newText, autoMarkdown)
                    )
                } else {
                    room.relationService().editTextMessage(
                            targetEvent,
                            messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                            quote?.first ?: newText,
                            quote?.second,
                            quote == null && autoMarkdown
                    )
                }
            }
        } else {
            val showInThread = targetEvent.root.isThread() && state.rootThreadEventId == null
            val rootThreadEventId = if (showInThread) targetEvent.root.getRootThreadEventId() else null
            offloadSend {
                state.rootThreadEventId?.let {
                    room.relationService().replyInThread(
                            rootThreadEventId = it,
                            replyInThreadText = action.newBody,
                            msgType = MessageType.MSGTYPE_NOTICE,
                            autoMarkdown = false,
                            formattedText = action.formattedBody,
                            eventReplied = targetEvent
                    )
                } ?: room.relationService().replyToMessage(
                        eventReplied = targetEvent,
                        replyText = action.newBody,
                        replyFormattedText = action.formattedBody,
                        autoMarkdown = false,
                        showInThread = showInThread,
                        rootThreadEventId = rootThreadEventId,
                        msgType = MessageType.MSGTYPE_NOTICE
                )
            }
        }
        _viewEvents.post(MessageComposerViewEvents.MessageSent)
        popDraft(room, state.sendMode)
    }

    private fun handleSendSticker(room: Room, action: MessageComposerAction.SendSticker) = withState { state ->
        (state.sendMode as? SendMode.Edit)?.timelineEvent?.let { edited ->
            // Picking a sticker while editing puts it in place of the message, as picking a file does.
            // It is uploaded already, so this replaces the content outright rather than going through
            // the upload pipeline.
            val target = room.getTimelineEvent(edited.eventId) ?: edited
            room.relationService().editMediaContent(target, action.content.forEventType(target.root.getClearType()))
            popDraft(room, state.sendMode)
            return@withState
        }
        val replyTo = (state.sendMode as? SendMode.Reply)?.timelineEvent
        val rootThreadEventId = state.rootThreadEventId
        val relatesTo = when {
            replyTo != null -> RelationDefaultContent(
                    type = rootThreadEventId?.let { RelationType.THREAD },
                    eventId = rootThreadEventId,
                    // Only meaningful on thread relations (MSC3440); a plain reply must not carry it
                    isFallingBack = rootThreadEventId?.let { false },
                    inReplyTo = ReplyToContent(eventId = replyTo.eventId),
            )
            rootThreadEventId != null -> RelationDefaultContent(
                    type = RelationType.THREAD,
                    eventId = rootThreadEventId,
                    isFallingBack = true,
            )
            else -> action.content.relatesTo
        }
        room.sendService().sendEvent(EventType.STICKER, action.content.copy(relatesTo = relatesTo).toContent())
        // A sticker's body IS its name — composer text is never sent as a caption, so keep it in the
        // box; only the consumed reply target is cleared.
        if (replyTo != null) {
            setState { copy(sendMode = SendMode.Regular(currentComposerText, fromSharing = false)) }
        }
    }

    /**
     * A sticker as the given event type would carry it: a sticker event holds it as it is, while a
     * message event has to say it is an image, an edit not being allowed to change the type.
     */
    private fun MessageStickerContent.forEventType(eventType: String): Content {
        if (eventType == EventType.STICKER) return copy(relatesTo = null).toContent()
        return MessageImageContent(
                msgType = MessageType.MSGTYPE_IMAGE,
                body = body,
                info = info,
                url = url,
                encryptedFileInfo = encryptedFileInfo,
        ).toContent()
    }

    private fun handleOnVoiceRecordingUiStateChanged(action: MessageComposerAction.OnVoiceRecordingUiStateChanged) {
        setState { copy(voiceRecordingUiState = action.uiState) }
        updateIsSendButtonVisibility(triggerAnimation = true)
    }

    /**
     * Run a fire-and-forget send/relation call on a background dispatcher so the UI thread
     * isn't blocked by markdown parsing, the synchronous Realm read inside `createLocalEcho`,
     * and the local-echo listener fanout. The local echo still appears at the same wall-clock
     * time as before — we just stop freezing the composer while it's being prepared.
     */
    private inline fun offloadSend(crossinline block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            im.vector.app.core.utils.PerfTrace.time("send.dispatch") { block() }
        }
    }

    // Encrypts [text] for the room members who actually have a PGP key (members without one are
    // silently skipped) plus our own key, then sends the armored block as a plain text message. The
    // room stays unencrypted at the Matrix level. Errors out if nobody else has a key.
    /**
     * Encrypts [text] for the room members who actually have a PGP key (others are silently
     * skipped) plus our own key, then hands the armored body + its formatted form to [send] (which
     * does the actual sendText / replyInThread / replyToMessage). The room stays unencrypted at the
     * Matrix level; errors out if nobody else has a key.
     */
    /**
     * The formatted body to PGP-encrypt alongside the plain body. An explicit formatted body (e.g.
     * from the rich composer) wins; otherwise we run markdown ourselves so the encrypted
     * formatted_body carries the rendered HTML — the SDK can't markdown an armored block after the
     * fact. Returns null when the message is plain (so we don't set format/formatted_body needlessly).
     */
    private fun pgpFormattedFor(room: Room, message: CharSequence, explicitFormatted: String?, autoMarkdown: Boolean): String? =
            explicitFormatted ?: room.sendService().computeFormattedHtml(message, autoMarkdown)

    private fun handlePgpSend(room: Room, text: CharSequence, formattedText: String?, consumedMode: SendMode?, send: suspend (armoredBody: String, armoredFormatted: String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (val outcome = pgpRoomEncryptor.encryptForRoom(room, text, formattedText)) {
                    is PgpRoomEncryptor.Outcome.Encrypted -> {
                        send(outcome.armoredBody, outcome.armoredFormatted)
                        _viewEvents.post(MessageComposerViewEvents.MessageSent)
                        popDraft(room, consumedMode)
                    }
                    PgpRoomEncryptor.Outcome.NotConfigured ->
                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_key_configured)))
                    PgpRoomEncryptor.Outcome.NoRecipients ->
                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_recipient_keys)))
                    is PgpRoomEncryptor.Outcome.NeedsInteraction -> {
                        // Sign-key passphrase: let OpenKeychain cache it, then the user resends.
                        _viewEvents.post(MessageComposerViewEvents.LaunchPgpInteraction(outcome.pendingIntent))
                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_interaction_required)))
                    }
                    is PgpRoomEncryptor.Outcome.Error ->
                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_encrypt_failed, outcome.message)))
                }
            }.onFailure { failure ->
                Timber.w(failure, "PGP send failed")
                _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_encrypt_failed, failure.localizedMessage.orEmpty())))
            }
        }
    }

    private fun handleToggleAutoTranslate(room: Room, targetLanguage: String?) {
        // An explicit language always turns auto-translation on (or retargets it); bare /translate toggles.
        val enable = targetLanguage != null || !translationSettings.isRoomAutoTranslateEnabled(room.roomId)
        translationSettings.setRoomAutoTranslate(room.roomId, if (enable) targetLanguage ?: TranslationLanguages.APP else null)
        val message = stringProvider.getString(if (enable) CommonStrings.translation_auto_on else CommonStrings.translation_auto_off)
        _viewEvents.post(MessageComposerViewEvents.ShowMessage(message))
    }

    /**
     * Translation takes seconds, so the composer is cleared up-front like any other send; on failure
     * [restoreMode] puts the user's original input back so nothing is lost.
     */
    private fun handleTranslatedSend(
            room: Room,
            message: CharSequence,
            targetLanguage: String?,
            consumedMode: SendMode?,
            restoreMode: SendMode?,
            send: suspend (text: String, formatted: String?) -> Unit,
    ) {
        _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(ParsedCommand.SendTranslated(message, targetLanguage)))
        popDraft(room, consumedMode)
        viewModelScope.launch(Dispatchers.IO) {
            when (val outcome = outgoingMessageTranslator.translate(message, targetLanguage)) {
                is OutgoingMessageTranslator.Outcome.Failed -> {
                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(outcome.message))
                    // Give the user their input back, unless they already started typing something else
                    // (typing updates currentComposerText, not sendMode) or switched modes.
                    restoreMode?.let { mode ->
                        withState { state ->
                            val untouched = currentComposerText.isBlank() &&
                                    state.sendMode.let { it is SendMode.Regular && it.text.isBlank() }
                            if (untouched) setState { copy(sendMode = mode) }
                        }
                    }
                }
                OutgoingMessageTranslator.Outcome.Unchanged -> {
                    send(message.toString(), null)
                    _viewEvents.post(MessageComposerViewEvents.MessageSent)
                }
                is OutgoingMessageTranslator.Outcome.Translated -> {
                    send(outcome.text, outcome.formatted)
                    _viewEvents.post(MessageComposerViewEvents.MessageSent)
                }
            }
        }
    }

    private fun handleOnTextChanged(action: MessageComposerAction.OnTextChanged) {
        val needsSendButtonVisibilityUpdate = currentComposerText.isBlank() != action.text.isBlank()
        currentComposerText = SpannableString(action.text)
        if (needsSendButtonVisibilityUpdate) {
            updateIsSendButtonVisibility(true)
        }
        if (action.text.contains("http")) {
            textPendingLinkPreview.tryEmit(action.text.toString())
        }
    }

    private fun subscribeToStateInternal(room: Room) {
        onEach(MessageComposerViewState::sendMode, MessageComposerViewState::canSendMessage, MessageComposerViewState::isVoiceRecording) { _, _, _ ->
            updateIsSendButtonVisibility(false)
        }
        onEach(MessageComposerViewState::sendMode) { mode ->
            observeReplyTarget(room, (mode as? SendMode.Reply)?.timelineEvent?.eventId)
        }
    }

    private var replyTargetJob: Job? = null
    private var replyTargetEventId: String? = null

    // The preview renders a snapshot of the replied-to event, so it has to follow edits (and redactions)
    // landing while the reply is still being composed.
    private fun observeReplyTarget(room: Room, eventId: String?) {
        if (eventId == replyTargetEventId) return
        replyTargetEventId = eventId
        replyTargetJob?.cancel()
        replyTargetJob = eventId?.let {
            // Edits land in the annotations summary, which the timeline-event flow doesn't watch, so
            // both have to be followed and the event re-read to pick the new content up.
            combine(room.flow().liveTimelineEvent(it), room.flow().liveAnnotationSummary(it)) { _, _ -> }
                    .map { withContext(Dispatchers.IO) { room.getTimelineEvent(eventId) } }
                    .filterNotNull()
                    // The table-level query re-fires on every sync; only content changes warrant a re-render.
                    .distinctUntilChangedBy { it.root to it.annotations?.editSummary }
                    .onEach { updated ->
                        setState {
                            if (sendMode is SendMode.Reply && sendMode.timelineEvent.eventId == updated.eventId) {
                                // Carry what is in the box: the re-render writes sendMode.text back into
                                // the composer, and the stale entry-time text would wipe the reply.
                                copy(sendMode = sendMode.copy(timelineEvent = updated, text = currentComposerText))
                            } else {
                                this
                            }
                        }
                    }
                    .launchIn(viewModelScope)
        }
    }

    private fun updateIsSendButtonVisibility(triggerAnimation: Boolean) = setState {
        val isSendButtonVisible = isComposerVisible && (
                isVoiceRecording ||
                        currentComposerText.isNotBlank() ||
                        sendMode is SendMode.Edit ||
                        sendMode is SendMode.Quote
                )
        if (this.isSendButtonVisible != isSendButtonVisible && triggerAnimation) {
            _viewEvents.post(MessageComposerViewEvents.AnimateSendButtonVisibility(isSendButtonVisible))
        }
        copy(isSendButtonVisible = isSendButtonVisible)
    }

    private fun handleEnterRegularMode(action: MessageComposerAction.EnterRegularMode) {
        val text = textAfterEditing()
        setState { copy(sendMode = SendMode.Regular(text, action.fromSharing)) }
    }

    private fun handleEnterEditMode(room: Room, action: MessageComposerAction.EnterEditMode) {
        room.getTimelineEvent(action.eventId)?.let { timelineEvent ->
            val prefill = computeEditablePrefill(room, timelineEvent)
            // Switching straight from one edit to another keeps what the first one stashed.
            if (!editOwnsComposer) {
                textStashedForEdit = currentComposerText.takeIf { it.isNotBlank() }
                editOwnsComposer = true
            }
            setState { copy(sendMode = SendMode.Edit(timelineEvent, prefill)) }
        }
    }

    /** Takes back what an edit stashed, if one owns the composer; releases it either way. */
    private fun consumeTextStashedForEdit(): CharSequence? {
        val stashed = textStashedForEdit.takeIf { editOwnsComposer }
        editOwnsComposer = false
        textStashedForEdit = null
        return stashed
    }

    /**
     * The text the composer goes back to once an edit lets go of it: what was being typed before the edit
     * took over, or else whatever is in the composer now.
     */
    private fun textAfterEditing(): CharSequence {
        // An edit which took over an empty composer stashed nothing, and giving it back still means
        // emptying the composer — not leaving the message that was being edited in it.
        val owned = editOwnsComposer
        val stashed = consumeTextStashedForEdit()
        if (!owned) return currentComposerText
        val restored = stashed ?: ""
        // Drop the edit from storage now rather than at the next draft save: the message being given back
        // has to survive the app dying in between.
        room?.let { room ->
            session.coroutineScope.launch {
                if (restored.isBlank()) {
                    room.draftService().deleteDraft()
                } else {
                    room.draftService().saveDrafts(listOf(UserDraft.Regular(restored.toString())))
                }
            }
        }
        return restored
    }

    /**
     * [computeEditableContent] with the mentions restored as markdown links, which the composer turns
     * back into pills. The plain body carries a mention as a bare name, so editing against it alone
     * would silently flatten every pill in the message to text.
     */
    private fun computeEditablePrefill(room: Room, timelineEvent: TimelineEvent): CharSequence {
        val body = computeEditableContent(timelineEvent)
        val formatted = (timelineEvent.getVectorLastMessageContent() as? MessageContentWithFormattedBody)?.matrixFormattedBody
        return spliceMentionLinks(body.toString(), formatted) { userId ->
            val member = room.membershipService().getRoomMember(userId)
            // Both, since the body may predate a local override being set or cleared.
            listOfNotNull(member?.bodyName(), member?.displayName).distinct()
        }
    }

    /**
     * The text we pre-fill the composer with when editing [timelineEvent].
     */
    private fun computeEditableContent(timelineEvent: TimelineEvent): CharSequence {
        // PGP: edit against the decrypted plaintext (body or caption), not the armored block. The
        // edited message is on-screen so it's already decrypted in the cache.
        val pgpSource = (timelineEvent.getVectorLastMessageContent() as? MessageTextContent)?.body
                ?: (timelineEvent.getVectorLastMessageContent() as? MessageWithAttachmentContent)?.getCaption()
        pgpSource?.let { pgpDecryptor.peekDecryptedBody(it) }?.let { return it }

        val richContent = timelineEvent.getTextEditableContent(formatted = false)
        // For our own greentext-formatted output, the HTML body would put a green span in
        // the editor — visually inconsistent with the rest of the composer. Fall back to
        // the plain body (the user's `>line`-shaped text) so editing happens against the
        // source they actually typed.
        return if (looksLikeGreentextHtml(richContent)) {
            timelineEvent.getTextEditableContent(formatted = false)
        } else {
            richContent
        }
    }

    private fun looksLikeGreentextHtml(text: String): Boolean =
            text.contains("<font color=\"#789922\">", ignoreCase = true)

    private fun handleSetFullScreen(action: MessageComposerAction.SetFullScreen) {
        setState { copy(isFullScreen = action.isFullScreen) }
    }

    private fun observePowerLevelAndEncryption(room: Room) {
        combine(
                room.flow().liveRoomPowerLevels(),
                room.flow().liveRoomSummary().unwrap()
        ) { pl, sum ->
            val canSendMessage = pl.isUserAllowedToSend(session.myUserId, false, EventType.MESSAGE)
            if (canSendMessage) {
                val isE2E = sum.isEncrypted
                if (isE2E) {
                    val roomEncryptionAlgorithm = sum.roomEncryptionAlgorithm
                    if (roomEncryptionAlgorithm is RoomEncryptionAlgorithm.UnsupportedAlgorithm) {
                        CanSendStatus.UnSupportedE2eAlgorithm(roomEncryptionAlgorithm.name)
                    } else {
                        CanSendStatus.Allowed
                    }
                } else {
                    CanSendStatus.Allowed
                }
            } else {
                CanSendStatus.NoPermission
            }
        }.setOnEach {
            copy(canSendMessage = it)
        }
    }

    private fun handleEnterQuoteMode(room: Room, action: MessageComposerAction.EnterQuoteMode) {
        room.getTimelineEvent(action.eventId)?.let { timelineEvent ->
            val text = textAfterEditing()
            setState { copy(sendMode = SendMode.Quote(timelineEvent, text)) }
        }
    }

    private fun handleEnterReplyMode(room: Room, action: MessageComposerAction.EnterReplyMode) {
        room.getTimelineEvent(action.eventId)?.let { timelineEvent ->
            val text = textAfterEditing()
            setState { copy(sendMode = SendMode.Reply(timelineEvent, text)) }
        }
    }

    // Reply target to restore after a slash command is run from the reply composer, so the command
    // executes and clears the text without dropping the reply (nothing was actually sent as a reply).
    private var replyTargetToRestoreAfterCommand: TimelineEvent? = null

    @Suppress("NAME_SHADOWING")
    private fun handleSendMessage(room: Room, action: MessageComposerAction.SendMessage) {
        im.vector.app.core.utils.PerfTrace.report("send.handle", 0)
        withState { state ->
            // Preparation (emote shortcode tagging, mention resolution, slash-command parsing) reads
            // prefs/DB and took ~150ms on the Mavericks state thread, delaying the local echo. Run it on
            // a single-parallelism lane so rapid consecutive sends keep their order.
            viewModelScope.launch(sendPreparationLane) {
            im.vector.app.core.utils.PerfTrace.report("send.withState", 0)
            setState { copy(startsThread = false) }
            // Tag literal `:shortcode:` text as custom emotes for every send mode (regular, reply, quote, edit).
            // The rich-text path already carries its own formatted body, so only touch the plain-text path.
            val action = if (action.formattedText == null) {
                action.copy(text = emoteShortcodeProcessor.process(room.roomId, action.text))
            } else {
                action
            }
            when (state.sendMode) {
                is SendMode.Regular -> {
                    when (val parsedCommand = commandParser.parseSlashCommand(
                            textMessage = resolveComposerMentions(action.text),
                            formattedMessage = action.formattedText,
                            isInThreadTimeline = state.isInThreadTimeline()
                    )) {
                        is ParsedCommand.ErrorNotACommand -> {
                            val roomPgpOn = pgpKeyStore.isEnabled && pgpKeyStore.isRoomPgpEnabled(room.roomId) && !room.roomCryptoService().isEncrypted()
                            val prefixSend = if (roomPgpOn) null else TranslationLanguages.sendPrefix(action.text)
                            val autoTarget = if (roomPgpOn) null else translationSettings.roomAutoTranslateTarget(room.roomId)
                            if (prefixSend != null || autoTarget != null) {
                                handleTranslatedSend(
                                        room, prefixSend?.second ?: action.text, prefixSend?.first ?: autoTarget, state.sendMode,
                                        restoreMode = SendMode.Regular(action.text, fromSharing = false),
                                ) { text, formatted ->
                                    if (state.rootThreadEventId != null) {
                                        room.relationService().replyInThread(
                                                rootThreadEventId = state.rootThreadEventId,
                                                replyInThreadText = text,
                                                formattedText = formatted,
                                                autoMarkdown = false,
                                        )
                                    } else if (formatted != null) {
                                        room.sendService().sendFormattedTextMessage(text, formatted)
                                    } else {
                                        room.sendService().sendTextMessage(text, autoMarkdown = false)
                                    }
                                }
                                return@launch
                            }
                            if (roomPgpOn) {
                                // Room is in PGP mode: encrypt the body (and the formatted body, if any,
                                // separately) — each field carries its own armored block.
                                handlePgpSend(room, action.text, pgpFormattedFor(room, action.text, action.formattedText, action.autoMarkdown), state.sendMode) { armoredBody, armoredFormatted ->
                                    if (state.rootThreadEventId != null) {
                                        room.relationService().replyInThread(
                                                rootThreadEventId = state.rootThreadEventId,
                                                replyInThreadText = armoredBody,
                                                formattedText = armoredFormatted,
                                                autoMarkdown = false,
                                        )
                                    } else if (armoredFormatted != null) {
                                        room.sendService().sendFormattedTextMessage(armoredBody, armoredFormatted)
                                    } else {
                                        room.sendService().sendTextMessage(armoredBody, autoMarkdown = false)
                                    }
                                }
                            } else {
                                val messageText = action.text
                                val greentext = maybeBuildGreentextRuns(messageText, action.formattedText)
                                // The send path runs markdown parsing, a Realm read for the local echo,
                                // and the synchronous local-echo listener notification — all on the main
                                // thread by default. Offload to background so the composer feels
                                // instant; the local echo / timeline update still happens just as fast,
                                // we just don't block the UI thread while it's being prepared.
                                offloadSend {
                                    if (state.rootThreadEventId != null) {
                                        room.relationService().replyInThread(
                                                rootThreadEventId = state.rootThreadEventId,
                                                replyInThreadText = greentext?.first ?: messageText,
                                                formattedText = greentext?.second ?: action.formattedText,
                                                autoMarkdown = greentext == null && action.autoMarkdown,
                                        )
                                    } else if (greentext != null) {
                                        room.sendService().sendFormattedTextMessage(greentext.first, greentext.second)
                                    } else if (action.formattedText != null) {
                                        room.sendService().sendFormattedTextMessage(action.text.toString(), action.formattedText)
                                    } else {
                                        room.sendService().sendTextMessage(messageText, autoMarkdown = action.autoMarkdown)
                                    }
                                }

                                _viewEvents.post(MessageComposerViewEvents.MessageSent)
                                popDraft(room, state.sendMode)
                            }
                        }
                        is ParsedCommand.TogglePgpMode -> {
                            popDraft(room, state.sendMode)
                            when {
                                room.roomCryptoService().isEncrypted() ->
                                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_not_in_encrypted_room)))
                                !pgpKeyStore.isEnabled ->
                                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_disabled)))
                                !pgpKeyStore.hasMyKey() ->
                                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_key_configured)))
                                pgpKeyStore.isRoomPgpEnabled(room.roomId) -> {
                                    // Turning OFF is always allowed.
                                    pgpKeyStore.setRoomPgpEnabled(room.roomId, false)
                                    pgpKeyStore.clearRoomRecipientKeyIds(room.roomId)
                                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_mode_off)))
                                }
                                else -> {
                                    // Turning ON: only allow it if at least one other member has a key.
                                    pgpKeyStore.clearRoomRecipientKeyIds(room.roomId)
                                    viewModelScope.launch(Dispatchers.IO) {
                                        runCatching { pgpRoomEncryptor.resolveRoomRecipients(room) }
                                                .onSuccess { others ->
                                                    if (others == null) {
                                                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_recipient_keys)))
                                                    } else {
                                                        pgpKeyStore.setRoomPgpEnabled(room.roomId, true)
                                                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_mode_on)))
                                                    }
                                                }
                                                .onFailure {
                                                    _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_encrypt_failed, it.localizedMessage.orEmpty())))
                                                }
                                    }
                                }
                            }
                        }
                        is ParsedCommand.DownloadFile -> {
                            handleDownloadSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ToggleAutoTranslate -> {
                            popDraft(room, state.sendMode)
                            handleToggleAutoTranslate(room, parsedCommand.targetLanguage)
                        }
                        is ParsedCommand.SendTranslated -> {
                            handleTranslatedSend(
                                    room, parsedCommand.message, parsedCommand.targetLanguage, state.sendMode,
                                    restoreMode = SendMode.Regular(action.text, fromSharing = false),
                            ) { text, formatted ->
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = text,
                                            formattedText = formatted,
                                            autoMarkdown = false,
                                    )
                                } else if (formatted != null) {
                                    room.sendService().sendFormattedTextMessage(text, formatted)
                                } else {
                                    room.sendService().sendTextMessage(text, autoMarkdown = false)
                                }
                            }
                        }
                        is ParsedCommand.SendPgpEncrypted -> {
                            if (!pgpKeyStore.isEnabled) {
                                popDraft(room, state.sendMode)
                                _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_disabled)))
                                return@launch
                            }
                            handlePgpSend(room, parsedCommand.message, pgpFormattedFor(room, parsedCommand.message, null, action.autoMarkdown), state.sendMode) { armoredBody, armoredFormatted ->
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = armoredBody,
                                            formattedText = armoredFormatted,
                                            autoMarkdown = false,
                                    )
                                } else if (armoredFormatted != null) {
                                    room.sendService().sendFormattedTextMessage(armoredBody, armoredFormatted)
                                } else {
                                    room.sendService().sendTextMessage(armoredBody, autoMarkdown = false)
                                }
                            }
                        }
                        is ParsedCommand.ErrorSyntax -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandError(parsedCommand.command))
                        }
                        is ParsedCommand.ErrorEmptySlashCommand -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandUnknown("/"))
                        }
                        is ParsedCommand.ErrorUnknownSlashCommand -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandUnknown(parsedCommand.slashCommand))
                        }
                        is ParsedCommand.ErrorCommandNotSupportedInThreads -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandNotSupportedInThreads(parsedCommand.command))
                        }
                        is ParsedCommand.SendPlainText -> {
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = parsedCommand.message,
                                            autoMarkdown = false
                                    )
                                } else {
                                    room.sendService().sendTextMessage(parsedCommand.message, autoMarkdown = false)
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.MessageSent)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendFormattedText -> {
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = parsedCommand.message,
                                            formattedText = parsedCommand.formattedMessage,
                                            autoMarkdown = false
                                    )
                                } else {
                                    room.sendService().sendFormattedTextMessage(
                                            text = parsedCommand.message.toString(),
                                            formattedText = parsedCommand.formattedMessage
                                    )
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.MessageSent)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendGreentext -> {
                            val (plain, formatted) = buildGreentext(parsedCommand.message)
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = plain,
                                            formattedText = formatted,
                                            autoMarkdown = false
                                    )
                                } else {
                                    room.sendService().sendFormattedTextMessage(
                                            text = plain,
                                            formattedText = formatted
                                    )
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.MessageSent)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendBlockquote -> {
                            val (plain, formatted) = buildBlockquote(parsedCommand.message)
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = plain,
                                            formattedText = formatted,
                                            autoMarkdown = false
                                    )
                                } else {
                                    room.sendService().sendFormattedTextMessage(
                                            text = plain,
                                            formattedText = formatted
                                    )
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.MessageSent)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.ChangeRoomName -> {
                            handleChangeRoomNameSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.Invite -> {
                            handleInviteSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.Invite3Pid -> {
                            handleInvite3pidSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.SetUserPowerLevel -> {
                            handleSetUserPowerLevel(room, parsedCommand)
                        }
                        is ParsedCommand.DevTools,
                        is ParsedCommand.SendCustomEvent,
                        is ParsedCommand.SendCustomStateEvent -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.ClearScalarToken -> {
                            // TODO
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SetMarkdown -> {
                            vectorPreferences.setMarkdownEnabled(parsedCommand.enable)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.BanUser -> {
                            handleBanSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.UnbanUser -> {
                            handleUnbanSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.IgnoreUser -> {
                            handleIgnoreSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.UnignoreUser -> {
                            handleUnignoreSlashCommand(parsedCommand)
                        }
                        is ParsedCommand.KickUser -> {
                            handleKickSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.MassRedact -> {
                            val displayName = room.membershipService().getRoomMember(parsedCommand.userId)?.displayName ?: parsedCommand.userId
                            _viewEvents.post(
                                    MessageComposerViewEvents.ShowMassRedactConfirmation(
                                            parsedCommand.userId, displayName, parsedCommand.delayMs ?: 0L, parsedCommand.range
                                    )
                            )
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.JoinRoom -> {
                            handleJoinToAnotherRoomSlashCommand(parsedCommand)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.WatchRoom -> {
                            handleWatchRoomSlashCommand(parsedCommand)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.UnwatchRoom -> {
                            handleUnwatchRoomSlashCommand(parsedCommand)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.PartRoom -> {
                            handlePartSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.SendEmote -> {
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = parsedCommand.message,
                                            msgType = MessageType.MSGTYPE_EMOTE,
                                            autoMarkdown = action.autoMarkdown
                                    )
                                } else {
                                    room.sendService().sendTextMessage(
                                            text = parsedCommand.message,
                                            msgType = MessageType.MSGTYPE_EMOTE,
                                            autoMarkdown = action.autoMarkdown
                                    )
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendNotice -> {
                            // sendTextMessage only builds a formatted body for m.text/m.emote, so an
                            // m.notice with mention pills would send plain. Build the HTML ourselves.
                            val noticeFormatted = if (containsMentionPills(parsedCommand.message)) {
                                mentionsToHtml(parsedCommand.message)
                            } else {
                                null
                            }
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = parsedCommand.message,
                                            msgType = MessageType.MSGTYPE_NOTICE,
                                            autoMarkdown = action.autoMarkdown
                                    )
                                } else if (noticeFormatted != null) {
                                    room.sendService().sendFormattedTextMessage(
                                            text = parsedCommand.message.toString(),
                                            formattedText = noticeFormatted,
                                            msgType = MessageType.MSGTYPE_NOTICE
                                    )
                                } else {
                                    room.sendService().sendTextMessage(
                                            text = parsedCommand.message,
                                            msgType = MessageType.MSGTYPE_NOTICE,
                                            autoMarkdown = action.autoMarkdown
                                    )
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendRainbow -> {
                            sendColored(room, state.rootThreadEventId, parsedCommand.message, rainbowWithMentions(parsedCommand.message))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendRainbowEmote -> {
                            sendColored(room, state.rootThreadEventId, parsedCommand.message, rainbowWithMentions(parsedCommand.message), MessageType.MSGTYPE_EMOTE)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendTrans -> {
                            sendColored(room, state.rootThreadEventId, parsedCommand.message, transWithMentions(parsedCommand.message))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendTransEmote -> {
                            sendColored(room, state.rootThreadEventId, parsedCommand.message, transWithMentions(parsedCommand.message), MessageType.MSGTYPE_EMOTE)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendSpoiler -> {
                            val text = "[${stringProvider.getString(CommonStrings.spoiler)}](${parsedCommand.message})"
                            val formattedText = "<span data-mx-spoiler>${mentionsToHtml(parsedCommand.message)}</span>"
                            if (state.rootThreadEventId != null) {
                                room.relationService().replyInThread(
                                        rootThreadEventId = state.rootThreadEventId,
                                        replyInThreadText = text,
                                        formattedText = formattedText
                                )
                            } else {
                                room.sendService().sendFormattedTextMessage(
                                        text,
                                        formattedText
                                )
                            }
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendShrug -> {
                            sendPrefixedMessage(room, "¯\\_(ツ)_/¯", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendLenny -> {
                            sendPrefixedMessage(room, "( ͡° ͜ʖ ͡°)", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.SendTableFlip -> {
                            sendPrefixedMessage(room, "(╯°□°）╯︵ ┻━┻", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.ChangeTopic -> {
                            handleChangeTopicSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ChangeDisplayName -> {
                            handleChangeDisplayNameSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ChangeDisplayNameForRoom -> {
                            handleChangeDisplayNameForRoomSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ChangeRoomAvatar -> {
                            handleChangeRoomAvatarSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ChangeAvatarForRoom -> {
                            handleChangeAvatarForRoomSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ShowUser -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            handleWhoisSlashCommand(parsedCommand)
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.DiscardSession -> {
                            if (room.roomCryptoService().isEncrypted()) {
                                session.cryptoService().discardOutboundSession(room.roomId)
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                popDraft(room, state.sendMode)
                            } else {
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                _viewEvents.post(
                                        MessageComposerViewEvents
                                                .ShowMessage(stringProvider.getString(CommonStrings.command_description_discard_session_not_handled))
                                )
                            }
                        }
                        is ParsedCommand.CreateSpace -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandLoading)
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val params = CreateSpaceParams().apply {
                                        name = parsedCommand.name
                                        invitedUserIds.addAll(parsedCommand.invitees)
                                    }
                                    val spaceId = session.spaceService().createSpace(params)
                                    session.spaceService().getSpace(spaceId)
                                            ?.addChildren(
                                                    state.roomId,
                                                    null,
                                                    null,
                                                    true
                                            )
                                    popDraft(room, state.sendMode)
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                } catch (failure: Throwable) {
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            Unit
                        }
                        is ParsedCommand.AddToSpace -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandLoading)
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.spaceService().getSpace(parsedCommand.spaceId)
                                            ?.addChildren(
                                                    room.roomId,
                                                    null,
                                                    null,
                                                    false
                                            )
                                    popDraft(room, state.sendMode)
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                } catch (failure: Throwable) {
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            Unit
                        }
                        is ParsedCommand.JoinSpace -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandLoading)
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.spaceService().joinSpace(parsedCommand.spaceIdOrAlias)
                                    popDraft(room, state.sendMode)
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                } catch (failure: Throwable) {
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            Unit
                        }
                        is ParsedCommand.LeaveRoom -> {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    session.roomService().leaveRoom(parsedCommand.roomId)
                                    popDraft(room, state.sendMode)
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                } catch (failure: Throwable) {
                                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                                }
                            }
                            Unit
                        }
                        is ParsedCommand.UpgradeRoom -> {
                            _viewEvents.post(
                                    MessageComposerViewEvents.ShowRoomUpgradeDialog(
                                            parsedCommand.newVersion,
                                            room.roomSummary()?.isPublic ?: false
                                    )
                            )
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.ConvertToDm -> {
                            handleConvertToDmSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ConvertToRoom -> {
                            handleConvertToRoomSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.JumpToStart -> {
                            // Only a hint: the timeline fetches the start itself when this event isn't loaded.
                            val createEventId = room.stateService()
                                    .getStateEvent(EventType.STATE_ROOM_CREATE, QueryStringValue.IsEmpty)
                                    ?.eventId
                            _viewEvents.post(MessageComposerViewEvents.JumpToEvent(eventId = createEventId, toRoomStart = true))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.JumpToEvent -> {
                            _viewEvents.post(MessageComposerViewEvents.JumpToEvent(eventId = parsedCommand.eventId))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.JumpToPermalink -> {
                            _viewEvents.post(MessageComposerViewEvents.JumpToPermalink(link = parsedCommand.link))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room, state.sendMode)
                        }
                        is ParsedCommand.Tombstone -> {
                            handleTombstoneSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.JumpToDate -> {
                            val timestamp = parseJumpToDate(parsedCommand.date)
                            if (timestamp == null) {
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandError(Command.JUMP_TO_DATE))
                                popDraft(room, state.sendMode)
                            } else {
                                viewModelScope.launch(Dispatchers.IO) {
                                    val eventId = room.timelineService().fetchEventIdForTimestamp(timestamp, forward = true)
                                    if (eventId != null) {
                                        _viewEvents.post(MessageComposerViewEvents.JumpToEvent(eventId = eventId))
                                        _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                    } else {
                                        _viewEvents.post(
                                                MessageComposerViewEvents.JumpToEvent(
                                                        eventId = null,
                                                        notFoundMessage = stringProvider.getString(CommonStrings.command_jump_to_date_no_event, parsedCommand.date),
                                                )
                                        )
                                    }
                                    popDraft(room, state.sendMode)
                                }
                            }
                            Unit
                        }
                    }
                }
                is SendMode.Edit -> {
                    // Re-resolve the snapshot taken when edit mode was entered: the message may have
                    // finished sending since (local echo swapped for the remote event).
                    val targetEvent = room.getTimelineEvent(state.sendMode.timelineEvent.eventId)
                            ?: state.sendMode.timelineEvent
                    // is original event a reply?
                    val relationContent = targetEvent.getRelationContent()
                    val inReplyTo = if (state.rootThreadEventId != null) {
                        // Thread event
                        if (relationContent?.shouldRenderInThread() == true) {
                            // Reply within a thread event
                            relationContent.inReplyTo?.eventId
                        } else {
                            // Normal thread event
                            null
                        }
                    } else {
                        // Normal event
                        relationContent?.inReplyTo?.eventId
                    }

                    val targetContent = targetEvent.getVectorLastMessageContent()
                    val targetFormatted = (targetContent as? MessageContentWithFormattedBody)?.matrixFormattedBody
                            ?: (targetContent as? MessageWithAttachmentContent)?.getFormattedCaption()
                    val greentext = maybeBuildQuoteRunsForEdit(action.text, action.formattedText, targetFormatted)
                    val editText = greentext?.first ?: action.text
                    val editFormatted = greentext?.second ?: action.formattedText
                    val editAutoMarkdown = greentext == null && action.autoMarkdown
                    if (inReplyTo != null) {
                        // TODO check if same content?
                        room.getTimelineEvent(inReplyTo)?.let {
                            room.relationService().editReply(targetEvent, it, editText, editFormatted)
                        }
                    } else {
                        val messageContent = targetContent
                        if (messageContent is MessageWithAttachmentContent) {
                            // Media event: edit/add/remove its caption. Empty text removes it.
                            val existingCaption = if (editFormatted != null) {
                                messageContent.getFormattedCaption().orEmpty()
                            } else {
                                messageContent.getCaption().orEmpty()
                            }
                            val newCaption = (editFormatted ?: editText).toString()
                            val editedEvent = targetEvent
                            if (existingCaption != newCaption) {
                                if (pgpRoomEncryptor.isRoomPgpActive(room) && editText.toString().isNotBlank()) {
                                    // Encrypt the edited caption rather than leaking it as plaintext.
                                    val pgpFormatted = pgpFormattedFor(room, editText, editFormatted?.toString(), editAutoMarkdown)
                                    viewModelScope.launch(Dispatchers.IO) {
                                        when (val outcome = pgpRoomEncryptor.encryptForRoom(room, editText, pgpFormatted)) {
                                            is PgpRoomEncryptor.Outcome.Encrypted ->
                                                room.relationService().editMediaCaption(editedEvent, outcome.armoredBody, outcome.armoredFormatted)
                                            else ->
                                                _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_recipient_keys)))
                                        }
                                    }
                                } else {
                                    room.relationService().editMediaCaption(
                                            editedEvent,
                                            editText,
                                            editFormatted?.toString(),
                                    )
                                }
                            } else {
                                Timber.w("Same caption, do not send edition")
                            }
                        } else if (action.text.toString() == computeEditableContent(state.sendMode.timelineEvent).toString()) {
                            // Preserve-if-untouched: the editable text is unchanged from what we loaded, so keep the
                            // original (possibly richer) formatted body rather than re-send a lossy plain version.
                            Timber.w("Edit content untouched, preserving original message")
                        } else if (pgpRoomEncryptor.isRoomPgpActive(room) && editText.toString().isNotBlank()) {
                            // Re-encrypt the edited body rather than re-sending it as plaintext.
                            val pgpFormatted = pgpFormattedFor(room, editText, editFormatted, editAutoMarkdown)
                            viewModelScope.launch(Dispatchers.IO) {
                                when (val outcome = pgpRoomEncryptor.encryptForRoom(room, editText, pgpFormatted)) {
                                    is PgpRoomEncryptor.Outcome.Encrypted ->
                                        room.relationService().editTextMessage(
                                                targetEvent,
                                                messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                                                outcome.armoredBody,
                                                outcome.armoredFormatted,
                                                false,
                                        )
                                    else ->
                                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_recipient_keys)))
                                }
                            }
                        } else {
                            val existingBody: String
                            val needsEdit = if (messageContent is MessageContentWithFormattedBody) {
                                existingBody = messageContent.formattedBody ?: ""
                                existingBody != editFormatted
                            } else {
                                existingBody = messageContent?.body ?: ""
                                existingBody != editText
                            }
                            if (needsEdit) {
                                room.relationService().editTextMessage(
                                        targetEvent,
                                        messageContent?.msgType ?: MessageType.MSGTYPE_TEXT,
                                        editText,
                                        editFormatted,
                                        editAutoMarkdown
                                )
                            } else {
                                Timber.w("Same message content, do not send edition")
                            }
                        }
                    }
                    _viewEvents.post(MessageComposerViewEvents.MessageSent)
                    popDraft(room, state.sendMode)
                }
                is SendMode.Quote -> {
                    room.sendService().sendQuotedTextMessage(
                            quotedEvent = state.sendMode.timelineEvent,
                            text = action.text.toString(),
                            formattedText = action.formattedText,
                            autoMarkdown = action.autoMarkdown,
                            rootThreadEventId = state.rootThreadEventId
                    )
                    _viewEvents.post(MessageComposerViewEvents.MessageSent)
                    popDraft(room, state.sendMode)
                }
                is SendMode.Reply -> {
                    val timelineEvent = state.sendMode.timelineEvent
                    val showInThread = timelineEvent.root.isThread() && state.rootThreadEventId == null
                    // If threads are disabled this will make the fallback replies visible to clients with threads enabled
                    val rootThreadEventId = if (showInThread) timelineEvent.root.getRootThreadEventId() else null

                    val parsedCommand = commandParser.parseSlashCommand(
                            textMessage = resolveComposerMentions(action.text),
                            formattedMessage = action.formattedText,
                            isInThreadTimeline = state.isInThreadTimeline()
                    )
                    val handledAsCommand = handleReplyCommand(
                            room = room,
                            parsedCommand = parsedCommand,
                            originalText = action.text,
                            eventReplied = timelineEvent,
                            threadRootEventId = state.rootThreadEventId,
                            showInThread = showInThread,
                            replyRootThreadEventId = rootThreadEventId,
                            autoMarkdown = action.autoMarkdown,
                            consumedMode = state.sendMode,
                    )
                    if (!handledAsCommand && parsedCommand !is ParsedCommand.ErrorNotACommand) {
                        // Action command (e.g. /myroomnick, /kick) typed in the reply composer: run it via
                        // the Regular path (which executes it without sending any text) and keep the reply
                        // target so the user can still reply — only the composer text is cleared.
                        replyTargetToRestoreAfterCommand = timelineEvent
                        setState { copy(sendMode = SendMode.Regular(action.text, fromSharing = false)) }
                        handleSendMessage(room, action)
                        // Restore the reply header immediately instead of waiting for the async command's
                        // popDraft. This withState is queued after the dispatch's, so the command still
                        // sees Regular mode; the user just never sees the "replying to" preview disappear.
                        withState { setState { copy(sendMode = SendMode.Reply(timelineEvent, "")) } }
                    } else if (!handledAsCommand &&
                            pgpKeyStore.isEnabled && pgpKeyStore.isRoomPgpEnabled(room.roomId) && !room.roomCryptoService().isEncrypted()) {
                        // PGP-mode reply: encrypt the body, keep the m.relates_to so it still threads/replies.
                        handlePgpSend(room, action.text, pgpFormattedFor(room, action.text, action.formattedText, action.autoMarkdown), state.sendMode) { armoredBody, armoredFormatted ->
                            state.rootThreadEventId?.let {
                                room.relationService().replyInThread(
                                        rootThreadEventId = it,
                                        replyInThreadText = armoredBody,
                                        autoMarkdown = false,
                                        formattedText = armoredFormatted,
                                        eventReplied = timelineEvent
                                )
                            } ?: room.relationService().replyToMessage(
                                    eventReplied = timelineEvent,
                                    replyText = armoredBody,
                                    replyFormattedText = armoredFormatted,
                                    autoMarkdown = false,
                                    showInThread = showInThread,
                                    rootThreadEventId = rootThreadEventId
                            )
                        }
                    } else if (!handledAsCommand &&
                            (translationSettings.isRoomAutoTranslateEnabled(room.roomId) || TranslationLanguages.sendPrefix(action.text) != null)) {
                        val prefixSend = TranslationLanguages.sendPrefix(action.text)
                        val autoTarget = translationSettings.roomAutoTranslateTarget(room.roomId)
                        handleTranslatedSend(
                                room, prefixSend?.second ?: action.text, prefixSend?.first ?: autoTarget, state.sendMode,
                                restoreMode = SendMode.Reply(timelineEvent, action.text),
                        ) { text, formatted ->
                            state.rootThreadEventId?.let {
                                room.relationService().replyInThread(
                                        rootThreadEventId = it,
                                        replyInThreadText = text,
                                        autoMarkdown = false,
                                        formattedText = formatted,
                                        eventReplied = timelineEvent
                                )
                            } ?: room.relationService().replyToMessage(
                                    eventReplied = timelineEvent,
                                    replyText = text,
                                    replyFormattedText = formatted,
                                    autoMarkdown = false,
                                    showInThread = showInThread,
                                    rootThreadEventId = rootThreadEventId
                            )
                        }
                    } else if (!handledAsCommand) {
                        val greentext = maybeBuildGreentextRuns(action.text, action.formattedText)
                        val replyText = greentext?.first ?: action.text
                        val replyFormatted = greentext?.second ?: action.formattedText
                        val autoMarkdown = greentext == null && action.autoMarkdown
                        state.rootThreadEventId?.let {
                            room.relationService().replyInThread(
                                    rootThreadEventId = it,
                                    replyInThreadText = replyText,
                                    autoMarkdown = autoMarkdown,
                                    formattedText = replyFormatted,
                                    eventReplied = timelineEvent
                            )
                        } ?: room.relationService().replyToMessage(
                                eventReplied = timelineEvent,
                                replyText = replyText,
                                replyFormattedText = replyFormatted,
                                autoMarkdown = autoMarkdown,
                                showInThread = showInThread,
                                rootThreadEventId = rootThreadEventId
                        )

                        _viewEvents.post(MessageComposerViewEvents.MessageSent)
                        popDraft(room, state.sendMode)
                    }
                }
                is SendMode.Voice -> {
                    // do nothing
                }
            }
            }
        }
    }

    // Identifies which composer mode a send consumed, so a completed send only clears its own mode.
    private fun SendMode.modeKey(): String = when (this) {
        is SendMode.Regular -> "regular"
        is SendMode.Reply -> "reply:${timelineEvent.eventId}"
        is SendMode.Edit -> "edit:${timelineEvent.eventId}"
        is SendMode.Quote -> "quote:${timelineEvent.eventId}"
        is SendMode.Voice -> "voice"
    }

    private fun popDraft(room: Room, consumedMode: SendMode? = null) = withState {
        replyTargetToRestoreAfterCommand?.let { replyTarget ->
            // A slash command was just run from the reply composer: keep replying, clear only the text.
            replyTargetToRestoreAfterCommand = null
            setState { copy(sendMode = SendMode.Reply(replyTarget, "")) }
            viewModelScope.launch { room.draftService().deleteDraft() }
            return@withState
        }
        if (consumedMode != null && consumedMode.modeKey() != it.sendMode.modeKey()) {
            // The user switched modes while this send was in flight (e.g. tapped reply during the
            // async dispatch): the sent draft is consumed, but the new mode isn't this send's to clear.
            viewModelScope.launch { room.draftService().deleteDraft() }
            return@withState
        }
        if (it.sendMode is SendMode.Regular && it.sendMode.fromSharing) {
            // If we were sharing, we want to get back our last value from draft
            loadDraftIfAny(room)
        } else {
            // Otherwise we clear the composer and remove the draft from db — except after an edit, which
            // gives back the message that was being written when it started.
            val restored = consumeTextStashedForEdit() ?: ""
            setState { copy(sendMode = SendMode.Regular(restored, false)) }
            viewModelScope.launch {
                if (restored.isBlank()) {
                    room.draftService().deleteDraft()
                } else {
                    room.draftService().saveDrafts(listOf(UserDraft.Regular(restored.toString())))
                }
            }
        }
    }

    private fun loadDraftIfAny(room: Room) {
        val drafts = room.draftService().getDrafts()
        val currentDraft = drafts.lastOrNull()
        if (currentDraft is UserDraft.Edit) {
            // An edit interrupted mid-room keeps the message that was being written underneath it.
            editOwnsComposer = true
            textStashedForEdit = (drafts.getOrNull(drafts.lastIndex - 1) as? UserDraft.Regular)
                    ?.content
                    ?.takeIf { it.isNotBlank() }
        }
        // Drop a stale voice draft before render so the recorder doesn't flicker through Draft state.
        if (currentDraft is UserDraft.Voice && !voiceDraftFileExists(room, currentDraft.content)) {
            viewModelScope.launch { room.draftService().deleteDraft() }
            setState { copy(sendMode = SendMode.Regular("", fromSharing = false)) }
            return
        }
        setState {
            copy(
                    sendMode = when (currentDraft) {
                        is UserDraft.Regular -> SendMode.Regular(currentDraft.content, false)
                        is UserDraft.Quote -> {
                            room.getTimelineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.Quote(timelineEvent, currentDraft.content)
                            }
                        }
                        is UserDraft.Reply -> {
                            room.getTimelineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.Reply(timelineEvent, currentDraft.content)
                            }
                        }
                        is UserDraft.Edit -> {
                            room.getTimelineEvent(currentDraft.linkedEventId)?.let { timelineEvent ->
                                SendMode.Edit(timelineEvent, currentDraft.content)
                            }
                        }
                        is UserDraft.Voice -> SendMode.Voice(currentDraft.content)
                        else -> null
                    } ?: SendMode.Regular("", fromSharing = false)
            )
        }
    }

    private fun voiceDraftFileExists(room: Room, content: String): Boolean {
        val attachmentData = tryOrNull { ContentAttachmentData.fromJsonString(content) } ?: return false
        audioMessageHelper.initializeRecorder(room.roomId, attachmentData)
        val file = audioMessageHelper.getCurrentVoiceFile()
        return file != null && file.exists() && file.length() > 0L
    }

    private fun handleUserIsTyping(room: Room, action: MessageComposerAction.UserIsTyping) {
        if (vectorPreferences.sendTypingNotifs()) {
            if (action.isTyping) {
                room.typingService().userIsTyping()
            } else {
                room.typingService().userStopsTyping()
            }
        }
    }

    private fun handleWatchRoomSlashCommand(command: ParsedCommand.WatchRoom) {
        viewModelScope.launch {
            val peek = try {
                session.roomService().peekRoom(command.roomAlias)
            } catch (failure: Throwable) {
                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                return@launch
            }
            if (peek is PeekResult.Success && peek.worldReadable == true) {
                WatchedRooms.add(
                        session,
                        WatchedRoomInfo(
                                roomId = peek.roomId,
                                viaServers = peek.viaServers.take(WatchedRooms.MAX_VIA_SERVERS),
                                name = peek.name,
                                avatarUrl = peek.avatarUrl,
                                topic = peek.topic,
                                alias = peek.alias,
                        )
                )
                _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.command_watch_success)))
            } else {
                _viewEvents.post(
                        MessageComposerViewEvents.SlashCommandResultError(
                                IllegalArgumentException(stringProvider.getString(CommonStrings.command_watch_not_previewable))
                        )
                )
            }
        }
    }

    private fun handleUnwatchRoomSlashCommand(command: ParsedCommand.UnwatchRoom) {
        viewModelScope.launch {
            // The argument may be an alias while the registry keys on room id (or vice versa).
            val resolved = tryOrNull { session.roomService().getRoomSummary(command.roomAlias)?.roomId }
            val removed = WatchedRooms.remove(session, resolved ?: command.roomAlias) ||
                    (resolved != null && WatchedRooms.remove(session, command.roomAlias))
            if (!removed) {
                _viewEvents.post(
                        MessageComposerViewEvents.SlashCommandResultError(
                                IllegalArgumentException(stringProvider.getString(CommonStrings.command_unwatch_not_watched))
                        )
                )
            }
        }
    }

    private fun handleJoinToAnotherRoomSlashCommand(command: ParsedCommand.JoinRoom) {
        // The join is a network round trip that ends in a navigation, so report the command as
        // accepted up front: the composer is what the user types their next message into, and it
        // stays locked until an event arrives. Success and failure still navigate below.
        _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(command))
        viewModelScope.launch {
            try {
                session.roomService().joinRoom(command.roomAlias, command.reason, emptyList())
            } catch (failure: Throwable) {
                // Couldn't join directly (e.g. invite-only or knock/ask-to-join): open the room's
                // matrix.to sheet, which offers Join / Ask to join as appropriate.
                val link = tryOrNull { session.permalinkService().createPermalink(command.roomAlias) }
                if (link != null) {
                    _viewEvents.post(MessageComposerViewEvents.OpenRoomLink(link))
                } else {
                    _viewEvents.post(MessageComposerViewEvents.SlashCommandResultError(failure))
                }
                return@launch
            }
            val joinedRoomId = session.getRoomSummary(command.roomAlias)?.roomId
            if (joinedRoomId != null) {
                _viewEvents.post(MessageComposerViewEvents.JoinRoomCommandSuccess(joinedRoomId))
            } else {
                // Joined, but the summary hasn't synced yet: report the command as done anyway, else
                // the composer waits forever for a navigation that will never come.
                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(command))
            }
        }
    }

    private fun legacyRiotQuoteText(quotedText: String?, myText: String): String {
        val messageParagraphs = quotedText?.split("\n\n".toRegex())?.dropLastWhile { it.isEmpty() }?.toTypedArray()
        return buildString {
            if (messageParagraphs != null) {
                for (i in messageParagraphs.indices) {
                    if (messageParagraphs[i].isNotBlank()) {
                        append("> ")
                        append(messageParagraphs[i])
                    }

                    if (i != messageParagraphs.lastIndex) {
                        append("\n\n")
                    }
                }
            }
            append("\n\n")
            append(myText)
        }
    }

    private fun handleChangeTopicSlashCommand(room: Room, changeTopic: ParsedCommand.ChangeTopic) {
        launchSlashCommandFlowSuspendable(room, changeTopic) {
            room.stateService().updateTopic(changeTopic.topic)
        }
    }

    private fun handleInviteSlashCommand(room: Room, invite: ParsedCommand.Invite) {
        launchSlashCommandFlowSuspendable(room, invite) {
            room.membershipService().invite(invite.userId, invite.reason)
        }
    }

    private fun handleInvite3pidSlashCommand(room: Room, invite: ParsedCommand.Invite3Pid) {
        launchSlashCommandFlowSuspendable(room, invite) {
            room.membershipService().invite3pid(invite.threePid)
        }
    }

    private fun handleSetUserPowerLevel(room: Room, setUserPowerLevel: ParsedCommand.SetUserPowerLevel) {
        val newPowerLevelsContent = room.getStateEvent(EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.IsEmpty)
                ?.content
                ?.toModel<PowerLevelsContent>()
                ?.setUserPowerLevel(setUserPowerLevel.userId, setUserPowerLevel.powerLevel)
                ?.toContent()
                ?: return

        launchSlashCommandFlowSuspendable(room, setUserPowerLevel) {
            room.stateService().sendStateEvent(EventType.STATE_ROOM_POWER_LEVELS, stateKey = "", newPowerLevelsContent)
        }
    }

    private fun handleChangeDisplayNameSlashCommand(room: Room, changeDisplayName: ParsedCommand.ChangeDisplayName) {
        launchSlashCommandFlowSuspendable(room, changeDisplayName) {
            session.profileService().setDisplayName(session.myUserId, changeDisplayName.displayName)
        }
    }

    private fun handlePartSlashCommand(room: Room, command: ParsedCommand.PartRoom) {
        launchSlashCommandFlowSuspendable(room, command) {
            if (command.roomAlias == null) {
                // Leave the current room
                room
            } else {
                session.getRoomSummary(roomIdOrAlias = command.roomAlias)
                        ?.roomId
                        ?.let { session.getRoom(it) }
            }
                    ?.let {
                        session.roomService().leaveRoom(it.roomId)
                    }
        }
    }

    private fun handleKickSlashCommand(room: Room, kickUser: ParsedCommand.KickUser) {
        launchSlashCommandFlowSuspendable(room, kickUser) {
            room.membershipService().kick(kickUser.userId, kickUser.reason)
        }
    }

    private fun handleBanSlashCommand(room: Room, ban: ParsedCommand.BanUser) {
        launchSlashCommandFlowSuspendable(room, ban) {
            room.membershipService().ban(ban.userId, ban.reason)
        }
    }

    private fun handleUnbanSlashCommand(room: Room, unban: ParsedCommand.UnbanUser) {
        launchSlashCommandFlowSuspendable(room, unban) {
            room.membershipService().unban(unban.userId, unban.reason)
        }
    }

    private fun handleConvertToDmSlashCommand(room: Room, command: ParsedCommand.ConvertToDm) {
        launchSlashCommandFlowSuspendable(room, command) {
            updateDirectAccountData(room, targetUserId = command.targetUserId ?: guessDmTargetId(room))
        }
    }

    private fun handleConvertToRoomSlashCommand(room: Room, command: ParsedCommand) {
        launchSlashCommandFlowSuspendable(room, command) {
            updateDirectAccountData(room, targetUserId = null)
        }
    }

    // Drop this room from every user's list, then (if target is set) add it to that user's.
    private suspend fun updateDirectAccountData(room: Room, targetUserId: String?) {
        val currentContent = session.accountDataService()
                .getUserAccountDataEvent(UserAccountDataTypes.TYPE_DIRECT_MESSAGES)
                ?.content
                .orEmpty()
        val current = currentContent.mapValues { (_, v) ->
            (v as? List<*>)?.filterIsInstance<String>().orEmpty()
        }
        val cleared = current.mapValues { (_, rooms) -> rooms.filter { it != room.roomId } }
        val updated = if (targetUserId != null) {
            cleared.toMutableMap().also { it[targetUserId] = (cleared[targetUserId].orEmpty() + room.roomId) }
        } else {
            cleared
        }.filterValues { it.isNotEmpty() }
        session.accountDataService().updateUserAccountData(
                UserAccountDataTypes.TYPE_DIRECT_MESSAGES,
                updated,
        )
    }

    // First non-self joined member, then first non-self in any state, then fall back to self.
    private fun guessDmTargetId(room: Room): String {
        val myUserId = session.myUserId
        val joined = room.membershipService().getRoomMembers(
                roomMemberQueryParams { memberships = listOf(Membership.JOIN) }
        ).firstOrNull { it.userId != myUserId }
        if (joined != null) return joined.userId
        val any = room.membershipService().getRoomMembers(roomMemberQueryParams { })
                .firstOrNull { it.userId != myUserId }
        return any?.userId ?: myUserId
    }

    private fun handleChangeRoomNameSlashCommand(room: Room, changeRoomName: ParsedCommand.ChangeRoomName) {
        launchSlashCommandFlowSuspendable(room, changeRoomName) {
            room.stateService().updateName(changeRoomName.name)
        }
    }

    private fun handleTombstoneSlashCommand(room: Room, tombstone: ParsedCommand.Tombstone) {
        launchSlashCommandFlowSuspendable(room, tombstone) {
            room.stateService().sendStateEvent(
                    eventType = EventType.STATE_ROOM_TOMBSTONE,
                    stateKey = "",
                    body = RoomTombstoneContent(
                            body = tombstone.body,
                            replacementRoomId = tombstone.replacementRoomId
                    ).toContent()
            )
        }
    }

    private fun getMyRoomMemberContent(room: Room): RoomMemberContent? {
        return room.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(session.myUserId))
                ?.content
                ?.toModel<RoomMemberContent>()
    }

    private fun handleChangeDisplayNameForRoomSlashCommand(room: Room, changeDisplayName: ParsedCommand.ChangeDisplayNameForRoom) {
        launchSlashCommandFlowSuspendable(room, changeDisplayName) {
            getMyRoomMemberContent(room)
                    ?.copy(displayName = changeDisplayName.displayName)
                    ?.toContent()
                    ?.let {
                        room.stateService().sendStateEvent(EventType.STATE_ROOM_MEMBER, session.myUserId, it)
                    }
        }
    }

    private fun handleChangeRoomAvatarSlashCommand(room: Room, changeAvatar: ParsedCommand.ChangeRoomAvatar) {
        launchSlashCommandFlowSuspendable(room, changeAvatar) {
            room.stateService().sendStateEvent(EventType.STATE_ROOM_AVATAR, stateKey = "", RoomAvatarContent(changeAvatar.url).toContent())
        }
    }

    private fun handleChangeAvatarForRoomSlashCommand(room: Room, changeAvatar: ParsedCommand.ChangeAvatarForRoom) {
        launchSlashCommandFlowSuspendable(room, changeAvatar) {
            getMyRoomMemberContent(room)
                    ?.copy(avatarUrl = changeAvatar.url)
                    ?.toContent()
                    ?.let {
                        room.stateService().sendStateEvent(EventType.STATE_ROOM_MEMBER, session.myUserId, it)
                    }
        }
    }

    private fun handleIgnoreSlashCommand(room: Room, ignore: ParsedCommand.IgnoreUser) {
        launchSlashCommandFlowSuspendable(room, ignore) {
            session.userService().ignoreUserIds(listOf(ignore.userId))
        }
    }

    private fun handleUnignoreSlashCommand(unignore: ParsedCommand.UnignoreUser) {
        _viewEvents.post(MessageComposerViewEvents.SlashCommandConfirmationRequest(unignore))
    }

    private fun handleSlashCommandConfirmed(room: Room, action: MessageComposerAction.SlashCommandConfirmed) {
        when (action.parsedCommand) {
            is ParsedCommand.UnignoreUser -> handleUnignoreSlashCommandConfirmed(room, action.parsedCommand)
            else -> TODO("Not handled yet")
        }
    }

    private fun handleUnignoreSlashCommandConfirmed(room: Room, unignore: ParsedCommand.UnignoreUser) {
        launchSlashCommandFlowSuspendable(room, unignore) {
            session.userService().unIgnoreUserIds(listOf(unignore.userId))
        }
    }

    private fun handleWhoisSlashCommand(whois: ParsedCommand.ShowUser) {
        _viewEvents.post(MessageComposerViewEvents.OpenRoomMemberProfile(whois.userId))
    }

    /**
     * Text-producing slash commands sent while in reply mode get attached to the original event
     * via m.in_reply_to (regular relation or thread relation, matching the non-command path).
     * Action commands (/invite, /ban, etc.) and parse errors return false so the caller can fall
     * back to the normal Reply send path or surface the error.
     */
    private fun handleReplyCommand(
            room: Room,
            parsedCommand: ParsedCommand,
            originalText: CharSequence,
            eventReplied: TimelineEvent,
            threadRootEventId: String?,
            showInThread: Boolean,
            replyRootThreadEventId: String?,
            autoMarkdown: Boolean,
            consumedMode: SendMode?,
    ): Boolean {
        fun reply(text: CharSequence, formatted: String? = null, msgType: String = MessageType.MSGTYPE_TEXT) {
            offloadSend {
                if (threadRootEventId != null) {
                    room.relationService().replyInThread(
                            rootThreadEventId = threadRootEventId,
                            replyInThreadText = text,
                            msgType = msgType,
                            autoMarkdown = autoMarkdown,
                            formattedText = formatted,
                            eventReplied = eventReplied,
                    )
                } else {
                    room.relationService().replyToMessage(
                            eventReplied = eventReplied,
                            replyText = text,
                            replyFormattedText = formatted,
                            autoMarkdown = autoMarkdown,
                            showInThread = showInThread,
                            rootThreadEventId = replyRootThreadEventId,
                            msgType = msgType,
                    )
                }
            }
        }

        fun finish() {
            _viewEvents.post(MessageComposerViewEvents.MessageSent)
            popDraft(room, consumedMode)
        }

        return when (parsedCommand) {
            is ParsedCommand.SendPlainText -> {
                reply(parsedCommand.message)
                finish()
                true
            }
            is ParsedCommand.SendFormattedText -> {
                reply(parsedCommand.message, parsedCommand.formattedMessage)
                finish()
                true
            }
            is ParsedCommand.SendEmote -> {
                reply(parsedCommand.message, msgType = MessageType.MSGTYPE_EMOTE)
                finish()
                true
            }
            is ParsedCommand.SendNotice -> {
                reply(parsedCommand.message, msgType = MessageType.MSGTYPE_NOTICE)
                finish()
                true
            }
            is ParsedCommand.SendGreentext -> {
                val (plain, formatted) = buildGreentext(parsedCommand.message)
                reply(plain, formatted)
                finish()
                true
            }
            is ParsedCommand.SendBlockquote -> {
                val (plain, formatted) = buildBlockquote(parsedCommand.message)
                reply(plain, formatted)
                finish()
                true
            }
            is ParsedCommand.SendRainbow -> {
                reply(parsedCommand.message.toString(), rainbowWithMentions(parsedCommand.message))
                finish()
                true
            }
            is ParsedCommand.SendRainbowEmote -> {
                reply(parsedCommand.message.toString(), rainbowWithMentions(parsedCommand.message), MessageType.MSGTYPE_EMOTE)
                finish()
                true
            }
            is ParsedCommand.SendTrans -> {
                reply(parsedCommand.message.toString(), transWithMentions(parsedCommand.message))
                finish()
                true
            }
            is ParsedCommand.SendTransEmote -> {
                reply(parsedCommand.message.toString(), transWithMentions(parsedCommand.message), MessageType.MSGTYPE_EMOTE)
                finish()
                true
            }
            is ParsedCommand.SendSpoiler -> {
                reply(
                        text = "[${stringProvider.getString(CommonStrings.spoiler)}](${parsedCommand.message})",
                        formatted = "<span data-mx-spoiler>${mentionsToHtml(parsedCommand.message)}</span>",
                )
                finish()
                true
            }
            is ParsedCommand.SendTranslated -> {
                handleTranslatedSend(
                        room, parsedCommand.message, parsedCommand.targetLanguage, consumedMode,
                        restoreMode = SendMode.Reply(eventReplied, originalText),
                ) { text, formatted ->
                    reply(text, formatted)
                }
                true
            }
            is ParsedCommand.ToggleAutoTranslate -> {
                handleToggleAutoTranslate(room, parsedCommand.targetLanguage)
                finish()
                true
            }
            is ParsedCommand.SendShrug -> {
                replyPrefixed("¯\\_(ツ)_/¯", parsedCommand.message, ::reply)
                finish()
                true
            }
            is ParsedCommand.SendLenny -> {
                replyPrefixed("( ͡° ͜ʖ ͡°)", parsedCommand.message, ::reply)
                finish()
                true
            }
            is ParsedCommand.SendTableFlip -> {
                replyPrefixed("(╯°□°）╯︵ ┻━┻", parsedCommand.message, ::reply)
                finish()
                true
            }
            is ParsedCommand.ErrorSyntax -> {
                _viewEvents.post(MessageComposerViewEvents.SlashCommandError(parsedCommand.command))
                true
            }
            is ParsedCommand.ErrorEmptySlashCommand -> {
                _viewEvents.post(MessageComposerViewEvents.SlashCommandUnknown("/"))
                true
            }
            is ParsedCommand.ErrorUnknownSlashCommand -> {
                _viewEvents.post(MessageComposerViewEvents.SlashCommandUnknown(parsedCommand.slashCommand))
                true
            }
            is ParsedCommand.ErrorCommandNotSupportedInThreads -> {
                _viewEvents.post(MessageComposerViewEvents.SlashCommandNotSupportedInThreads(parsedCommand.command))
                true
            }
            // Not a slash command — caller will send the typed text as a plain reply.
            // Action commands (e.g. /invite, /ban) aren't valid as replies and fall through here too.
            else -> false
        }
    }

    /**
     * Resolve a media caption that may itself be a slash command. Message-producing commands
     * (/plain, /html, /rainbow, /shrug, greentext, …) rewrite the caption body/formatted text that
     * gets attached to the media; action commands (/kick, /ban, …) are executed here (via the normal
     * send path, which runs them without sending any text) and [CaptionCommandResolution.CommandExecuted]
     * is returned so the media is sent with no caption. A caption that isn't a command is returned
     * unchanged. Only meaningful in the Regular send mode; callers keep the caption literal otherwise.
     */
    fun resolveCaptionCommand(caption: CharSequence, formatted: String?, isInThread: Boolean): CaptionCommandResolution {
        fun literal() = CaptionCommandResolution.Caption(caption, formatted, formatted == null && vectorPreferences.isMarkdownEnabled())
        fun captionPrefixed(prefix: String, message: CharSequence): CaptionCommandResolution.Caption {
            val plain = prefixed(prefix, message)
            val formattedText = if (containsMentionPills(message)) prefixed(prefix, mentionsToHtml(message)) else null
            return CaptionCommandResolution.Caption(plain, formattedText, false)
        }
        return when (val parsed = commandParser.parseSlashCommand(
                textMessage = resolveComposerMentions(caption),
                formattedMessage = formatted,
                isInThreadTimeline = isInThread,
        )) {
            is ParsedCommand.ErrorNotACommand -> literal()
            is ParsedCommand.SendPlainText -> CaptionCommandResolution.Caption(parsed.message, null, false)
            is ParsedCommand.SendFormattedText -> CaptionCommandResolution.Caption(parsed.message, parsed.formattedMessage, false)
            // A caption carries no msgtype, so emote/notice fall back to their plain text.
            is ParsedCommand.SendEmote -> CaptionCommandResolution.Caption(parsed.message, null, false)
            is ParsedCommand.SendNotice -> CaptionCommandResolution.Caption(parsed.message, null, false)
            is ParsedCommand.SendRainbow -> CaptionCommandResolution.Caption(parsed.message.toString(), rainbowWithMentions(parsed.message), false)
            is ParsedCommand.SendRainbowEmote -> CaptionCommandResolution.Caption(parsed.message.toString(), rainbowWithMentions(parsed.message), false)
            is ParsedCommand.SendTrans -> CaptionCommandResolution.Caption(parsed.message.toString(), transWithMentions(parsed.message), false)
            is ParsedCommand.SendTransEmote -> CaptionCommandResolution.Caption(parsed.message.toString(), transWithMentions(parsed.message), false)
            is ParsedCommand.SendSpoiler -> CaptionCommandResolution.Caption(
                    "[${stringProvider.getString(CommonStrings.spoiler)}](${parsed.message})",
                    "<span data-mx-spoiler>${mentionsToHtml(parsed.message)}</span>",
                    false,
            )
            is ParsedCommand.SendShrug -> captionPrefixed("¯\\_(ツ)_/¯", parsed.message)
            is ParsedCommand.SendLenny -> captionPrefixed("( ͡° ͜ʖ ͡°)", parsed.message)
            is ParsedCommand.SendTableFlip -> captionPrefixed("(╯°□°）╯︵ ┻━┻", parsed.message)
            is ParsedCommand.SendGreentext -> buildGreentext(parsed.message).let { (p, f) -> CaptionCommandResolution.Caption(p, f, false) }
            is ParsedCommand.SendBlockquote -> buildBlockquote(parsed.message).let { (p, f) -> CaptionCommandResolution.Caption(p, f, false) }
            else -> {
                handle(MessageComposerAction.SendMessage(caption, formattedText = null, autoMarkdown = false))
                CaptionCommandResolution.CommandExecuted
            }
        }
    }

    /**
     * Splits the input at the first occurrence of two consecutive newlines: everything before is
     * the styled segment, everything after is appended verbatim. Returns the head lines and the
     * trailing remainder (which may be empty and may itself contain further line breaks).
     */
    private fun splitStyledSegment(message: CharSequence): Pair<List<CharSequence>, CharSequence> {
        val text = message.toString()
        val breakIndex = text.indexOf("\n\n")
        return if (breakIndex < 0) {
            message.splitPreservingSpans('\n') to ""
        } else {
            message.subSequence(0, breakIndex).splitPreservingSpans('\n') to message.subSequence(breakIndex, message.length)
        }
    }

    private fun buildGreentext(message: CharSequence): Pair<String, String> {
        val (lines, rest) = splitStyledSegment(message)
        val head = lines.ifEmpty { listOf<CharSequence>("") }
        val plain = head.joinToString("\n") { ">$it" } + rest
        val styledHtml = "<font color=\"#789922\">" + head.joinToString("<br />") { "&gt;${mentionsToHtml(it)}" } + "</font>"
        val formatted = styledHtml + mentionsToHtml(rest).replace("\n", "<br />")
        return plain to formatted
    }

    private fun buildBlockquote(message: CharSequence): Pair<String, String> {
        val (lines, rest) = splitStyledSegment(message)
        val head = lines.ifEmpty { listOf<CharSequence>("") }
        val plain = head.joinToString("\n") { "> $it" } + rest
        val styledHtml = "<blockquote>\n<p>" + head.joinToString("<br />") { mentionsToHtml(it) } + "</p>\n</blockquote>"
        val formatted = styledHtml + mentionsToHtml(rest).replace("\n", "<br />")
        return plain to formatted
    }

    private fun CharSequence.splitPreservingSpans(delimiter: Char): List<CharSequence> {
        val result = mutableListOf<CharSequence>()
        val asString = toString()
        var start = 0
        var idx = asString.indexOf(delimiter)
        while (idx >= 0) {
            result.add(subSequence(start, idx))
            start = idx + 1
            idx = asString.indexOf(delimiter, start)
        }
        result.add(subSequence(start, length))
        return result
    }

    // Programmatic commands target a user or room, so each mention pill resolves to its matrix id;
    // everything else keeps the pill spans so display commands can render them as proper mention links.
    private fun resolveComposerMentions(text: CharSequence): CharSequence {
        return if (commandParser.getCommand(text) in mentionIdCommands) {
            mapPillSegments(text, onText = { it }, onPill = { id, _ -> id })
        } else {
            text
        }
    }

    private fun mentionsToHtml(message: CharSequence): String =
            mapPillSegments(message, onText = { it }, onPill = { id, name -> mentionLink(id, name) })

    private fun rainbowWithMentions(message: CharSequence): String =
            mapPillSegments(message, onText = { rainbowGenerator.generate(it) }, onPill = { id, name -> mentionLink(id, name) })

    private fun transWithMentions(message: CharSequence): String =
            mapPillSegments(message, onText = { rainbowGenerator.generateTrans(it) }, onPill = { id, name -> mentionLink(id, name) })

    private fun sendColored(room: Room, rootThreadEventId: String?, message: CharSequence, formatted: String, msgType: String = MessageType.MSGTYPE_TEXT) {
        offloadSend {
            if (rootThreadEventId != null) {
                room.relationService().replyInThread(
                        rootThreadEventId = rootThreadEventId,
                        replyInThreadText = message,
                        msgType = msgType,
                        formattedText = formatted
                )
            } else {
                room.sendService().sendFormattedTextMessage(message.toString(), formatted, msgType)
            }
        }
    }

    private fun mentionLink(id: String, name: String) = "<a href=\"https://matrix.to/#/$id\">$name</a>"

    private fun containsMentionPills(text: CharSequence) =
            (text as? Spanned)?.getSpans(0, text.length, MatrixItemSpan::class.java)?.isNotEmpty() == true

    private inline fun mapPillSegments(
            message: CharSequence,
            onText: (String) -> String,
            onPill: (id: String, name: String) -> String,
    ): String {
        val spanned = message as? Spanned
        val spans = spanned?.getSpans(0, message.length, MatrixItemSpan::class.java)
                ?.sortedBy { spanned.getSpanStart(it) }
                .orEmpty()
        if (spans.isEmpty()) return onText(message.toString())
        return buildString {
            var index = 0
            spans.forEach { span ->
                val start = spanned!!.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < index) return@forEach
                if (start > index) append(onText(message.subSequence(index, start).toString()))
                append(onPill(span.matrixItem.id, message.subSequence(start, end).toString()))
                index = end
            }
            if (index < message.length) append(onText(message.subSequence(index, message.length).toString()))
        }
    }

    private val mentionIdCommands = setOf(
            Command.KICK_USER,
            Command.CONVERT_TO_DM,
            Command.BAN_USER,
            Command.UNBAN_USER,
            Command.IGNORE_USER,
            Command.UNIGNORE_USER,
            Command.SET_USER_POWER_LEVEL,
            Command.RESET_USER_POWER_LEVEL,
            Command.INVITE,
            Command.CREATE_SPACE,
            Command.WHOIS,
            Command.JOIN_ROOM,
            Command.PART,
            Command.LEAVE_ROOM,
            Command.TOMBSTONE,
            Command.ADD_TO_SPACE,
            Command.JOIN_SPACE,
    )

    /**
     * Toggle on + the user typed `>`-prefixed lines anywhere in the composer → rewrite the
     * formatted body so each contiguous run of `>` lines becomes one
     * `<font color="#789922">…</font>` block (lines joined with `<br />`). Non-`>` lines pass
     * through verbatim. Every original newline becomes a `<br />` so blank lines survive.
     * Plain body is the user-typed text unchanged. Returns null when off, when there are no `>`
     * lines, or when a formatted body was already supplied.
     */
    private fun maybeBuildGreentextRuns(text: CharSequence, formattedText: String?): Pair<String, String>? {
        if (!vectorPreferences.renderBlockquotesAsGreentext()) return null
        return buildQuoteRuns(text, formattedText, greentext = true)
    }

    /**
     * [maybeBuildGreentextRuns] for edits: the quote style is whatever the message already uses, not
     * what the toggle says now — a /greentext message stays greentext with the toggle off, an explicit
     * /blockquote one stays a blockquote with it on. Only a message with neither falls back to the
     * toggle.
     */
    private fun maybeBuildQuoteRunsForEdit(text: CharSequence, formattedText: String?, oldFormatted: String?): Pair<String, String>? {
        val html = oldFormatted?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
        return when {
            html != null && looksLikeGreentextHtml(html) -> buildQuoteRuns(text, formattedText, greentext = true)
            html != null && html.contains("<blockquote", ignoreCase = true) ->
                // With markdown on the `>` lines re-render as a blockquote by themselves, keeping any
                // markdown inside the quote; only markdown-off needs the explicit rebuild.
                if (vectorPreferences.isMarkdownEnabled()) null else buildQuoteRuns(text, formattedText, greentext = false)
            else -> maybeBuildGreentextRuns(text, formattedText)
        }
    }

    private fun buildQuoteRuns(text: CharSequence, formattedText: String?, greentext: Boolean): Pair<String, String>? {
        if (formattedText != null) return null
        val raw = text.toString()
        val lines = raw.split('\n')
        if (lines.none { it.startsWith(">") }) return null

        val out = StringBuilder()
        var i = 0
        var first = true
        while (i < lines.size) {
            if (!first) out.append("<br />")
            first = false
            if (lines[i].startsWith(">")) {
                val run = StringBuilder()
                var runFirst = true
                while (i < lines.size && lines[i].startsWith(">")) {
                    if (!runFirst) run.append("<br />")
                    runFirst = false
                    val line = if (greentext) lines[i] else lines[i].removePrefix(">").removePrefix(" ")
                    run.append(escapeHtml(line))
                    i++
                }
                if (greentext) {
                    out.append("<font color=\"#789922\">").append(run).append("</font>")
                } else {
                    out.append("<blockquote>\n<p>").append(run).append("</p>\n</blockquote>")
                }
            } else {
                out.append(escapeHtml(lines[i]))
                i++
            }
        }
        return raw to out.toString()
    }

    private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun prefixed(prefix: String, message: CharSequence): String = buildString {
        append(prefix)
        if (message.isNotEmpty()) {
            append(" ")
            append(message)
        }
    }

    private fun replyPrefixed(prefix: String, message: CharSequence, reply: (CharSequence, String?, String) -> Unit) {
        val plain = prefixed(prefix, message)
        val formatted = if (containsMentionPills(message)) prefixed(prefix, mentionsToHtml(message)) else null
        reply(plain, formatted, MessageType.MSGTYPE_TEXT)
    }

    private fun sendPrefixedMessage(room: Room, prefix: String, message: CharSequence, rootThreadEventId: String?) {
        val sequence = prefixed(prefix, message)
        if (containsMentionPills(message)) {
            val formatted = prefixed(prefix, mentionsToHtml(message))
            rootThreadEventId?.let {
                room.relationService().replyInThread(rootThreadEventId = it, replyInThreadText = sequence, formattedText = formatted)
            } ?: room.sendService().sendFormattedTextMessage(sequence, formatted)
        } else {
            rootThreadEventId?.let {
                room.relationService().replyInThread(it, sequence)
            } ?: room.sendService().sendTextMessage(sequence)
        }
    }

    /**
     * Convert a send mode to a draft and save the draft.
     */
    private fun handleSaveTextDraft(room: Room, draft: String) = withState {
        session.coroutineScope.launch {
            when {
                it.sendMode is SendMode.Regular && !it.sendMode.fromSharing -> {
                    setState { copy(sendMode = it.sendMode.copy(text = draft)) }
                    room.draftService().saveDraft(UserDraft.Regular(draft))
                }
                it.sendMode is SendMode.Reply -> {
                    setState { copy(sendMode = it.sendMode.copy(text = draft)) }
                    room.draftService().saveDraft(UserDraft.Reply(it.sendMode.timelineEvent.root.eventId!!, draft))
                }
                it.sendMode is SendMode.Quote -> {
                    setState { copy(sendMode = it.sendMode.copy(text = draft)) }
                    room.draftService().saveDraft(UserDraft.Quote(it.sendMode.timelineEvent.root.eventId!!, draft))
                }
                it.sendMode is SendMode.Edit -> {
                    setState { copy(sendMode = it.sendMode.copy(text = draft)) }
                    val edit = UserDraft.Edit(it.sendMode.timelineEvent.root.eventId!!, draft)
                    // Store the interrupted message under the edit, so leaving the room does not lose it.
                    val stashed = textStashedForEdit?.toString()?.takeIf { text -> text.isNotBlank() }
                    if (stashed == null) {
                        room.draftService().saveDraft(edit)
                    } else {
                        room.draftService().saveDrafts(listOf(UserDraft.Regular(stashed), edit))
                    }
                }
            }
        }
    }

    private fun handleStartRecordingVoiceMessage(room: Room) {
        run {
            try {
                audioMessageHelper.startRecording(room.roomId)
                setState {
                    copy(
                            voiceRecordingUiState = VoiceMessageRecorderView.RecordingUiState.Recording(clock.epochMillis()),
                            sendMode = sendMode.withSyncedText(currentComposerText),
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(MessageComposerViewEvents.VoicePlaybackOrRecordingFailure(failure))
            }
        }
    }

    private fun SendMode.withSyncedText(text: CharSequence): SendMode = when (this) {
        is SendMode.Regular -> copy(text = text)
        is SendMode.Reply -> copy(text = text)
        is SendMode.Quote -> copy(text = text)
        is SendMode.Edit -> copy(text = text)
        is SendMode.Voice -> this
    }

    private fun handleEndRecordingVoiceMessage(room: Room, isCancelled: Boolean, rootThreadEventId: String? = null) {
        audioMessageHelper.stopPlayback()
        if (isCancelled) {
            audioMessageHelper.deleteRecording()
            finishVoiceDraft(room, resetSendMode = false)
            return
        }
        val audioType = audioMessageHelper.stopRecording()
        if (audioType == null || audioType.duration <= 1000) {
            audioMessageHelper.deleteRecording()
            finishVoiceDraft(room, resetSendMode = false)
            return
        }
        val replyTo = withState(this) { (it.sendMode as? SendMode.Reply)?.timelineEvent }
        val caption = currentComposerText.toString().takeIf { it.isNotBlank() }
        val attachment = audioType.toContentAttachmentData(isVoiceMessage = true)
        val sendVoice = { captionText: CharSequence?, captionFormatted: String? ->
            room.sendService().sendMedia(
                    attachment = attachment,
                    compressBeforeSending = false,
                    roomIds = emptySet(),
                    rootThreadEventId = rootThreadEventId,
                    replyToEvent = replyTo,
                    captionText = captionText,
                    captionFormattedText = captionFormatted,
            )
        }
        if (caption != null && pgpRoomEncryptor.isRoomPgpActive(room)) {
            viewModelScope.launch(Dispatchers.IO) {
                when (val outcome = pgpRoomEncryptor.encryptForRoom(room, caption)) {
                    is PgpRoomEncryptor.Outcome.Encrypted -> sendVoice(outcome.armoredBody, outcome.armoredFormatted)
                    else -> {
                        _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_no_recipient_keys)))
                        sendVoice(null, null)
                    }
                }
            }
        } else {
            sendVoice(caption, null)
        }
        currentComposerText = ""
        finishVoiceDraft(room, resetSendMode = true)
    }

    private fun finishVoiceDraft(room: Room, resetSendMode: Boolean) {
        setState {
            val nextSendMode = when {
                resetSendMode -> SendMode.Regular("", false)
                sendMode is SendMode.Voice -> SendMode.Regular(currentComposerText, false)
                else -> sendMode.withSyncedText(currentComposerText)
            }
            copy(
                    voiceRecordingUiState = VoiceMessageRecorderView.RecordingUiState.Idle,
                    sendMode = nextSendMode,
            )
        }
        updateIsSendButtonVisibility(triggerAnimation = false)
        viewModelScope.launch {
            if (room.draftService().getDraft() is UserDraft.Voice) {
                room.draftService().deleteDraft()
            }
        }
    }

    private val pendingPlaybackLoads = mutableMapOf<String, Job>()

    private fun handlePlayOrPauseVoicePlayback(action: MessageComposerAction.PlayOrPauseVoicePlayback) {
        synchronized(pendingPlaybackLoads) {
            // A second tap while the file is still being fetched cancels the pending load
            // rather than spawning a parallel download+MediaPlayer that the next tap can't reach.
            pendingPlaybackLoads.remove(action.eventId)?.let {
                it.cancel()
                return
            }
            val job = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val audioFile = audioMessageHelper.resolveLocalFile(action.messageAudioContent.url)
                            ?: session.fileService().downloadFile(action.messageAudioContent)
                    audioMessageHelper.startOrPausePlayback(action.eventId, audioFile)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    _viewEvents.post(MessageComposerViewEvents.VoicePlaybackOrRecordingFailure(failure))
                } finally {
                    synchronized(pendingPlaybackLoads) { pendingPlaybackLoads.remove(action.eventId) }
                }
            }
            pendingPlaybackLoads[action.eventId] = job
        }
    }

    private fun handlePlayOrPauseRecordingPlayback() {
        try {
            audioMessageHelper.startOrPauseRecordingPlayback()
        } catch (failure: Throwable) {
            _viewEvents.post(MessageComposerViewEvents.VoicePlaybackOrRecordingFailure(failure))
        }
    }

    fun endAllVoiceActions(deleteRecord: Boolean = true) {
        audioMessageHelper.resetPlaybackStates()
        audioMessageHelper.stopAllVoiceActions(deleteRecord)
    }

    private fun handleInitializeVoiceRecorder(room: Room, attachmentData: ContentAttachmentData) {
        audioMessageHelper.initializeRecorder(room.roomId, attachmentData)
        val file = audioMessageHelper.getCurrentVoiceFile()
        if (file == null || !file.exists() || file.length() == 0L) {
            finishVoiceDraft(room, resetSendMode = true)
            return
        }
        setState { copy(voiceRecordingUiState = VoiceMessageRecorderView.RecordingUiState.Draft) }
    }

    private fun handlePauseRecordingVoiceMessage() {
        audioMessageHelper.pauseRecording()
    }

    private fun handleVoiceWaveformTouchedUp(action: MessageComposerAction.VoiceWaveformTouchedUp) {
        audioMessageHelper.movePlaybackTo(action.eventId, action.percentage, action.duration)
    }

    private fun handleVoiceWaveformMovedTo(action: MessageComposerAction.VoiceWaveformMovedTo) {
        audioMessageHelper.movePlaybackTo(action.eventId, action.percentage, action.duration)
    }

    private fun handleAudioSeekBarMovedTo(action: MessageComposerAction.AudioSeekBarMovedTo) {
        audioMessageHelper.movePlaybackTo(action.eventId, action.percentage, action.duration)
    }

    private fun handleEntersBackground(room: Room, composerText: String) {
        // Always stop all voice actions. It may be playing in timeline or active recording
        val playingAudioContent = audioMessageHelper.stopAllVoiceActions(deleteRecord = false)

        val isVoiceRecording = com.airbnb.mvrx.withState(this) { it.isVoiceRecording }
        if (isVoiceRecording) {
            viewModelScope.launch {
                playingAudioContent?.toContentAttachmentData()?.let { voiceDraft ->
                    val content = voiceDraft.toJsonString()
                    room.draftService().saveDraft(UserDraft.Voice(content))
                    setState { copy(sendMode = SendMode.Voice(content)) }
                }
            }
        } else {
            handleSaveTextDraft(room = room, draft = composerText)
        }
    }

    private fun handleInsertUserDisplayName(action: MessageComposerAction.InsertUserDisplayName) {
        _viewEvents.post(MessageComposerViewEvents.InsertUserDisplayName(action.userId))
    }

    private fun handleDownloadSlashCommand(room: Room, parsedCommand: ParsedCommand.DownloadFile) {
        launchSlashCommandFlowSuspendable(room, parsedCommand) {
            val file = session.fileService().downloadFile(
                    fileName = parsedCommand.mxcUrl.substringAfterLast('/'),
                    mimeType = null,
                    url = parsedCommand.mxcUrl,
                    elementToDecrypt = null,
            )
            // Name the saved copy after the server's Content-Disposition; without one, "file" plus
            // saveMedia's timestamp and sniffed extension yields file_<timestamp>.<ext>.
            val title = session.fileService().getServerFileName(parsedCommand.mxcUrl) ?: "file"
            // Sorts into Pictures/Movies/Music/Downloads by sniffed type and posts the DL notification.
            downloadMediaUseCase.execute(file, title = title).getOrThrow()
            // The notification can be suppressed (no notification permission) — confirm in-app too.
            _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.file_has_been_downloaded)))
        }
    }

    private fun launchSlashCommandFlowSuspendable(room: Room, parsedCommand: ParsedCommand, block: suspend () -> Unit) {
        _viewEvents.post(MessageComposerViewEvents.SlashCommandLoading)
        viewModelScope.launch {
            val event = try {
                block()
                popDraft(room)
                MessageComposerViewEvents.SlashCommandResultOk(parsedCommand)
            } catch (failure: Throwable) {
                replyTargetToRestoreAfterCommand = null
                MessageComposerViewEvents.SlashCommandResultError(failure)
            }
            _viewEvents.post(event)
        }
    }

    private fun onRoomError() = setState {
        copy(isRoomError = true)
    }

    /**
     * Parse `YYYY-M[M]-D[D]` into midnight-local-time epoch millis. 1970 floor matches the
     * unix epoch — `origin_server_ts` can't address anything earlier.
     */
    private fun parseJumpToDate(raw: String): Long? {
        val parts = raw.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 1970 || year > 9999) return null
        if (month !in 1..12) return null
        if (day !in 1..31) return null
        val cal = java.util.Calendar.getInstance()
        cal.isLenient = false
        return try {
            cal.clear()
            cal.set(year, month - 1, day, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis.takeIf { it >= 0 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MessageComposerViewModel, MessageComposerViewState> {
        override fun create(initialState: MessageComposerViewState): MessageComposerViewModel
    }

    companion object : MavericksViewModelFactory<MessageComposerViewModel, MessageComposerViewState> by hiltMavericksViewModelFactory() {
        private const val LINK_PREVIEW_TYPING_DEBOUNCE_MS = 800L
    }
}

sealed interface CaptionCommandResolution {
    /** Caption text/formatted to attach to the media (unchanged input, or a message command's output). */
    data class Caption(val text: CharSequence?, val formatted: String?, val autoMarkdown: Boolean) : CaptionCommandResolution

    /** The caption was an action command (e.g. /kick) that has been executed; send the media with no caption. */
    object CommandExecuted : CaptionCommandResolution
}
