/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Spanned
import android.text.SpannableString
import androidx.lifecycle.asFlow
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.withState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.attachments.toContentAttachmentData
import im.vector.app.features.command.CommandParser
import im.vector.app.features.command.Command
import im.vector.app.features.command.ParsedCommand
import im.vector.app.features.home.room.detail.composer.rainbow.RainbowGenerator
import im.vector.app.features.home.room.detail.composer.voice.VoiceMessageRecorderView
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.voice.VoiceFailure
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpRoomEncryptor
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.EventType
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
import org.matrix.android.sdk.api.session.room.model.tombstone.RoomTombstoneContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFormattedCaption
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.model.relation.shouldRenderInThread
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getRelationContent
import org.matrix.android.sdk.api.session.room.timeline.getTextEditableContent
import org.matrix.android.sdk.api.session.space.CreateSpaceParams
import org.matrix.android.sdk.api.util.Optional
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
        private val emoteShortcodeProcessor: EmoteShortcodeProcessor,
) : VectorViewModel<MessageComposerViewState, MessageComposerAction, MessageComposerViewEvents>(initialState) {

    private val room = session.getRoom(initialState.roomId)

    // Keep it out of state to avoid invalidate being called
    private var currentComposerText: CharSequence = ""

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val sendPreparationLane = Dispatchers.Default.limitedParallelism(1)

    init {
        if (room != null) {
            loadDraftIfAny(room)
            observePowerLevelAndEncryption(room)
            subscribeToStateInternal()
        } else {
            onRoomError()
        }
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
        }
    }

    private fun handleOnAttachmentsSent(room: Room) {
        currentComposerText = ""
        popDraft(room)
    }

    private fun handleSendSticker(room: Room, action: MessageComposerAction.SendSticker) = withState { state ->
        val replyTo = (state.sendMode as? SendMode.Reply)?.timelineEvent
        val rootThreadEventId = state.rootThreadEventId
        val captionText = currentComposerText.toString().takeIf { it.isNotBlank() }
        val originalBody = action.content.body
        val relatesTo = when {
            replyTo != null -> RelationDefaultContent(
                    type = rootThreadEventId?.let { RelationType.THREAD },
                    eventId = rootThreadEventId,
                    isFallingBack = false,
                    inReplyTo = ReplyToContent(eventId = replyTo.eventId),
            )
            rootThreadEventId != null -> RelationDefaultContent(
                    type = RelationType.THREAD,
                    eventId = rootThreadEventId,
                    isFallingBack = true,
            )
            else -> action.content.relatesTo
        }
        val content = if (captionText != null) {
            action.content.copy(
                    body = captionText,
                    filename = action.content.filename ?: originalBody.takeIf { it.isNotBlank() },
                    relatesTo = relatesTo,
            )
        } else {
            action.content.copy(relatesTo = relatesTo)
        }
        room.sendService().sendEvent(EventType.STICKER, content.toContent())
        currentComposerText = ""
        popDraft(room)
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

    private fun handlePgpSend(room: Room, text: CharSequence, formattedText: String?, send: suspend (armoredBody: String, armoredFormatted: String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when (val outcome = pgpRoomEncryptor.encryptForRoom(room, text, formattedText)) {
                    is PgpRoomEncryptor.Outcome.Encrypted -> {
                        send(outcome.armoredBody, outcome.armoredFormatted)
                        _viewEvents.post(MessageComposerViewEvents.MessageSent)
                        popDraft(room)
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

    private fun handleOnTextChanged(action: MessageComposerAction.OnTextChanged) {
        val needsSendButtonVisibilityUpdate = currentComposerText.isBlank() != action.text.isBlank()
        currentComposerText = SpannableString(action.text)
        if (needsSendButtonVisibilityUpdate) {
            updateIsSendButtonVisibility(true)
        }
    }

    private fun subscribeToStateInternal() {
        onEach(MessageComposerViewState::sendMode, MessageComposerViewState::canSendMessage, MessageComposerViewState::isVoiceRecording) { _, _, _ ->
            updateIsSendButtonVisibility(false)
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

    private fun handleEnterRegularMode(action: MessageComposerAction.EnterRegularMode) = setState {
        copy(sendMode = SendMode.Regular(currentComposerText, action.fromSharing))
    }

    private fun handleEnterEditMode(room: Room, action: MessageComposerAction.EnterEditMode) {
        room.getTimelineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.Edit(timelineEvent, computeEditableContent(timelineEvent))) }
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
            setState { copy(sendMode = SendMode.Quote(timelineEvent, currentComposerText)) }
        }
    }

    private fun handleEnterReplyMode(room: Room, action: MessageComposerAction.EnterReplyMode) {
        room.getTimelineEvent(action.eventId)?.let { timelineEvent ->
            setState { copy(sendMode = SendMode.Reply(timelineEvent, currentComposerText)) }
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
                            if (roomPgpOn) {
                                // Room is in PGP mode: encrypt the body (and the formatted body, if any,
                                // separately) — each field carries its own armored block.
                                handlePgpSend(room, action.text, pgpFormattedFor(room, action.text, action.formattedText, action.autoMarkdown)) { armoredBody, armoredFormatted ->
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
                                popDraft(room)
                            }
                        }
                        is ParsedCommand.TogglePgpMode -> {
                            popDraft(room)
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
                        is ParsedCommand.SendPgpEncrypted -> {
                            if (!pgpKeyStore.isEnabled) {
                                popDraft(room)
                                _viewEvents.post(MessageComposerViewEvents.ShowMessage(stringProvider.getString(CommonStrings.pgp_disabled)))
                                return@launch
                            }
                            handlePgpSend(room, parsedCommand.message, pgpFormattedFor(room, parsedCommand.message, null, action.autoMarkdown)) { armoredBody, armoredFormatted ->
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
                            popDraft(room)
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
                            popDraft(room)
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
                            popDraft(room)
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
                            popDraft(room)
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
                        is ParsedCommand.DevTools -> {
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
                        }
                        is ParsedCommand.ClearScalarToken -> {
                            // TODO
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandNotImplemented)
                        }
                        is ParsedCommand.SetMarkdown -> {
                            vectorPreferences.setMarkdownEnabled(parsedCommand.enable)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
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
                        is ParsedCommand.JoinRoom -> {
                            handleJoinToAnotherRoomSlashCommand(parsedCommand)
                            popDraft(room)
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
                            popDraft(room)
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
                            popDraft(room)
                        }
                        is ParsedCommand.SendRainbow -> {
                            val message = parsedCommand.message.toString()
                            val formatted = rainbowWithMentions(parsedCommand.message)
                            offloadSend {
                                if (state.rootThreadEventId != null) {
                                    room.relationService().replyInThread(
                                            rootThreadEventId = state.rootThreadEventId,
                                            replyInThreadText = parsedCommand.message,
                                            formattedText = formatted
                                    )
                                } else {
                                    room.sendService().sendFormattedTextMessage(message, formatted)
                                }
                            }
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
                        }
                        is ParsedCommand.SendRainbowEmote -> {
                            val message = parsedCommand.message.toString()
                            val formatted = rainbowWithMentions(parsedCommand.message)
                            if (state.rootThreadEventId != null) {
                                room.relationService().replyInThread(
                                        rootThreadEventId = state.rootThreadEventId,
                                        replyInThreadText = parsedCommand.message,
                                        msgType = MessageType.MSGTYPE_EMOTE,
                                        formattedText = formatted
                                )
                            } else {
                                room.sendService().sendFormattedTextMessage(message, formatted, MessageType.MSGTYPE_EMOTE)
                            }

                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
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
                            popDraft(room)
                        }
                        is ParsedCommand.SendShrug -> {
                            sendPrefixedMessage(room, "¯\\_(ツ)_/¯", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
                        }
                        is ParsedCommand.SendLenny -> {
                            sendPrefixedMessage(room, "( ͡° ͜ʖ ͡°)", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
                        }
                        is ParsedCommand.SendTableFlip -> {
                            sendPrefixedMessage(room, "(╯°□°）╯︵ ┻━┻", parsedCommand.message, state.rootThreadEventId)
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
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
                            popDraft(room)
                        }
                        is ParsedCommand.DiscardSession -> {
                            if (room.roomCryptoService().isEncrypted()) {
                                session.cryptoService().discardOutboundSession(room.roomId)
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                popDraft(room)
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
                                    popDraft(room)
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
                                    popDraft(room)
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
                                    popDraft(room)
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
                                    popDraft(room)
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
                            popDraft(room)
                        }
                        is ParsedCommand.ConvertToDm -> {
                            handleConvertToDmSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.ConvertToRoom -> {
                            handleConvertToRoomSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.JumpToStart -> {
                            val createEventId = room.stateService()
                                    .getStateEvent(EventType.STATE_ROOM_CREATE, QueryStringValue.IsEmpty)
                                    ?.eventId
                            if (createEventId != null) {
                                _viewEvents.post(MessageComposerViewEvents.JumpToEvent(eventId = createEventId))
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                                popDraft(room)
                            } else {
                                _viewEvents.post(
                                        MessageComposerViewEvents.JumpToEvent(
                                                eventId = null,
                                                notFoundMessage = stringProvider.getString(CommonStrings.command_jump_to_start_unavailable),
                                        )
                                )
                            }
                            Unit
                        }
                        is ParsedCommand.JumpToEvent -> {
                            _viewEvents.post(MessageComposerViewEvents.JumpToEvent(eventId = parsedCommand.eventId))
                            _viewEvents.post(MessageComposerViewEvents.SlashCommandResultOk(parsedCommand))
                            popDraft(room)
                        }
                        is ParsedCommand.Tombstone -> {
                            handleTombstoneSlashCommand(room, parsedCommand)
                        }
                        is ParsedCommand.JumpToDate -> {
                            val timestamp = parseJumpToDate(parsedCommand.date)
                            if (timestamp == null) {
                                _viewEvents.post(MessageComposerViewEvents.SlashCommandError(Command.JUMP_TO_DATE))
                                popDraft(room)
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
                                    popDraft(room)
                                }
                            }
                            Unit
                        }
                    }
                }
                is SendMode.Edit -> {
                    // is original event a reply?
                    val relationContent = state.sendMode.timelineEvent.getRelationContent()
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

                    val greentext = maybeBuildGreentextRuns(action.text, action.formattedText)
                    val editText = greentext?.first ?: action.text
                    val editFormatted = greentext?.second ?: action.formattedText
                    val editAutoMarkdown = greentext == null && action.autoMarkdown
                    if (inReplyTo != null) {
                        // TODO check if same content?
                        room.getTimelineEvent(inReplyTo)?.let {
                            room.relationService().editReply(state.sendMode.timelineEvent, it, editText, editFormatted)
                        }
                    } else {
                        val messageContent = state.sendMode.timelineEvent.getVectorLastMessageContent()
                        if (messageContent is MessageWithAttachmentContent) {
                            // Media event: edit/add/remove its caption. Empty text removes it.
                            val existingCaption = if (editFormatted != null) {
                                messageContent.getFormattedCaption().orEmpty()
                            } else {
                                messageContent.getCaption().orEmpty()
                            }
                            val newCaption = (editFormatted ?: editText).toString()
                            val editedEvent = state.sendMode.timelineEvent
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
                                                state.sendMode.timelineEvent,
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
                                        state.sendMode.timelineEvent,
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
                    popDraft(room)
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
                    popDraft(room)
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
                            eventReplied = timelineEvent,
                            threadRootEventId = state.rootThreadEventId,
                            showInThread = showInThread,
                            replyRootThreadEventId = rootThreadEventId,
                            autoMarkdown = action.autoMarkdown,
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
                        handlePgpSend(room, action.text, pgpFormattedFor(room, action.text, action.formattedText, action.autoMarkdown)) { armoredBody, armoredFormatted ->
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
                        popDraft(room)
                    }
                }
                is SendMode.Voice -> {
                    // do nothing
                }
            }
            }
        }
    }

    private fun popDraft(room: Room) = withState {
        replyTargetToRestoreAfterCommand?.let { replyTarget ->
            // A slash command was just run from the reply composer: keep replying, clear only the text.
            replyTargetToRestoreAfterCommand = null
            setState { copy(sendMode = SendMode.Reply(replyTarget, "")) }
            viewModelScope.launch { room.draftService().deleteDraft() }
            return@withState
        }
        if (it.sendMode is SendMode.Regular && it.sendMode.fromSharing) {
            // If we were sharing, we want to get back our last value from draft
            loadDraftIfAny(room)
        } else {
            // Otherwise we clear the composer and remove the draft from db
            setState { copy(sendMode = SendMode.Regular("", false)) }
            viewModelScope.launch {
                room.draftService().deleteDraft()
            }
        }
    }

    private fun loadDraftIfAny(room: Room) {
        val currentDraft = room.draftService().getDraft()
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

    private fun handleJoinToAnotherRoomSlashCommand(command: ParsedCommand.JoinRoom) {
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
            session.getRoomSummary(command.roomAlias)
                    ?.roomId
                    ?.let {
                        _viewEvents.post(MessageComposerViewEvents.JoinRoomCommandSuccess(it))
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
            room.membershipService().remove(kickUser.userId, kickUser.reason)
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
            eventReplied: TimelineEvent,
            threadRootEventId: String?,
            showInThread: Boolean,
            replyRootThreadEventId: String?,
            autoMarkdown: Boolean,
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
            popDraft(room)
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
            is ParsedCommand.SendSpoiler -> {
                reply(
                        text = "[${stringProvider.getString(CommonStrings.spoiler)}](${parsedCommand.message})",
                        formatted = "<span data-mx-spoiler>${mentionsToHtml(parsedCommand.message)}</span>",
                )
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
                    run.append(escapeHtml(lines[i]))
                    i++
                }
                out.append("<font color=\"#789922\">").append(run).append("</font>")
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
                    room.draftService().saveDraft(UserDraft.Edit(it.sendMode.timelineEvent.root.eventId!!, draft))
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
        audioMessageHelper.stopTracking()
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

    companion object : MavericksViewModelFactory<MessageComposerViewModel, MessageComposerViewState> by hiltMavericksViewModelFactory()
}

sealed interface CaptionCommandResolution {
    /** Caption text/formatted to attach to the media (unchanged input, or a message command's output). */
    data class Caption(val text: CharSequence?, val formatted: String?, val autoMarkdown: Boolean) : CaptionCommandResolution

    /** The caption was an action command (e.g. /kick) that has been executed; send the media with no caption. */
    object CommandExecuted : CaptionCommandResolution
}
