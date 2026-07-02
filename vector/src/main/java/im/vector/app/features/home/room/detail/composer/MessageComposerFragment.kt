/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.SpannableString
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.features.reactions.EmojiKeyboardController
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.error.fatalError
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.extensions.orEmpty
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.showKeyboard
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.lifecycleAwareLazy
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.ExpandingBottomSheetBehavior
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.onPermissionDeniedDialog
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.databinding.FragmentComposerBinding
import im.vector.app.features.VectorFeatures
import im.vector.app.features.attachments.AttachmentType
import im.vector.app.features.attachments.AttachmentTypeSelectorSharedAction
import im.vector.app.features.attachments.AttachmentTypeSelectorSharedActionViewModel
import im.vector.app.features.attachments.AttachmentTypeSelectorView
import im.vector.app.features.attachments.AttachmentTypeSelectorViewModel
import im.vector.app.features.attachments.AttachmentsHelper
import im.vector.app.features.imagepack.picker.StickerPickerBottomSheet
import im.vector.app.features.attachments.ShareIntentHandler
import im.vector.app.features.attachments.preview.AttachmentsPreviewActivity
import im.vector.app.features.attachments.preview.AttachmentsPreviewArgs
import im.vector.app.features.attachments.toGroupedContentAttachmentData
import im.vector.app.features.command.Command
import im.vector.app.features.command.ParsedCommand
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.matrixto.OriginOfMatrixTo
import im.vector.app.features.home.room.detail.AutoCompleter
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.TimelineViewModel
import im.vector.app.features.home.room.detail.composer.link.SetLinkFragment
import im.vector.app.features.home.room.detail.composer.voice.VoiceMessageRecorderView
import im.vector.app.features.home.room.detail.timeline.action.MessageSharedActionViewModel
import im.vector.app.features.home.room.detail.upgrade.MigrateRoomBottomSheet
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.expandPillSpans
import im.vector.app.features.html.setPillSpan
import im.vector.app.features.location.LocationSharingMode
import im.vector.app.features.poll.PollMode
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.share.SharedData
import im.vector.app.features.voice.VoiceFailure
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import im.vector.app.features.media.MediaContentRevealManager
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.util.MatrixItem
import reactivecircus.flowbinding.android.view.focusChanges
import reactivecircus.flowbinding.android.widget.textChanges
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MessageComposerFragment : VectorBaseFragment<FragmentComposerBinding>(), AttachmentsHelper.Callback, AttachmentTypeSelectorView.Callback {

    companion object {
        private const val ircPattern = " (IRC)"
    }

    @Inject lateinit var autoCompleterFactory: AutoCompleter.Factory
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var shareIntentHandler: ShareIntentHandler
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var vectorFeatures: VectorFeatures
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var session: Session
    @Inject lateinit var emoteShortcodeProcessor: im.vector.app.features.imagepack.EmoteShortcodeProcessor
    @Inject lateinit var emojiPickerSectionFactory: im.vector.app.features.reactions.EmojiPickerSectionFactory
    @Inject lateinit var mediaContentRevealManager: MediaContentRevealManager

    private val roomId: String get() = withState(timelineViewModel) { it.roomId }

    private val autoCompleters: MutableMap<EditText, AutoCompleter> = hashMapOf()

    // Custom inline emoji+emote keyboard, works on all API levels (the old vanniktech popup needed API 21+).
    private var emojiKeyboardController: EmojiKeyboardController? = null

    private val glideRequests by lazy {
        GlideApp.with(this)
    }

    private val isEmojiKeyboardVisible: Boolean
        get() = vectorPreferences.showEmojiKeyboard()

    private var lockSendButton = false

    // Set while clearing the composer for a media send, so the stale sendMode (still holding the sent
    // caption until popDraft resets it) isn't re-applied over the cleared box. See renderRegularMode.
    private var suppressStaleComposerRender = false

    private lateinit var attachmentsHelper: AttachmentsHelper
    private lateinit var attachmentTypeSelector: AttachmentTypeSelectorView
    private var bottomSheetBehavior: ExpandingBottomSheetBehavior<View>? = null

    private val timelineViewModel: TimelineViewModel by parentFragmentViewModel()
    private val messageComposerViewModel: MessageComposerViewModel by parentFragmentViewModel()
    private lateinit var sharedActionViewModel: MessageSharedActionViewModel
    private val attachmentViewModel: AttachmentTypeSelectorViewModel by fragmentViewModel()
    private val attachmentActionsViewModel: AttachmentTypeSelectorSharedActionViewModel by viewModels()

    private val composer: MessageComposerView get() = views.composerLayout

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentComposerBinding {
        return FragmentComposerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedActionViewModel = activityViewModelProvider.get(MessageSharedActionViewModel::class.java)

        attachmentsHelper = AttachmentsHelper(requireContext(), this, buildMeta).register()

        setupBottomSheet()
        setupComposer()
        setupEmojiButton()
        setupStickerPicker()

        // Avoid a one-frame flash before renderRegularMode runs.
        if (!vectorPreferences.isVoiceMessageButtonEnabled()) {
            composer.sendButton.visibility = View.GONE
        }

        views.composerLayout.isVisible = true

        messageComposerViewModel.observeViewEvents {
            when (it) {
                is MessageComposerViewEvents.JoinRoomCommandSuccess -> handleJoinedToAnotherRoom(it)
                is MessageComposerViewEvents.SlashCommandConfirmationRequest -> handleSlashCommandConfirmationRequest(it)
                is MessageComposerViewEvents.SendMessageResult -> renderSendMessageResult(it)
                is MessageComposerViewEvents.ShowMessage -> {
                    // ShowMessage is terminal feedback for a send/command that went through
                    // sendTextMessage (which locked the button); release it (e.g. PGP toggle/errors).
                    lockSendButton = false
                    showSnackWithMessage(it.message)
                }
                is MessageComposerViewEvents.ShowRoomUpgradeDialog -> handleShowRoomUpgradeDialog(it)
                is MessageComposerViewEvents.AnimateSendButtonVisibility -> handleSendButtonVisibilityChanged(it)
                is MessageComposerViewEvents.OpenRoomMemberProfile -> openRoomMemberProfile(it.userId)
                is MessageComposerViewEvents.OpenRoomLink -> navigator.openMatrixToBottomSheet(requireActivity(), it.link, OriginOfMatrixTo.LINK)
                is MessageComposerViewEvents.VoicePlaybackOrRecordingFailure -> {
                    if (it.throwable is VoiceFailure.UnableToRecord) {
                        onCannotRecord()
                    }
                    showErrorInSnackbar(it.throwable)
                }
                is MessageComposerViewEvents.InsertUserDisplayName -> insertUserDisplayNameInTextEditor(it.userId)
                is MessageComposerViewEvents.JumpToEvent -> handleJumpToEvent(it)
                is MessageComposerViewEvents.LaunchPgpInteraction -> {
                    lockSendButton = false
                    launchPgpInteraction(it.pendingIntent)
                }
            }
        }

        messageComposerViewModel.onEach(MessageComposerViewState::sendMode, MessageComposerViewState::canSendMessage) { mode, canSend ->
            if (!canSend.boolean()) {
                return@onEach
            }
            when (mode) {
                is SendMode.Regular -> renderRegularMode(mode.text)
                is SendMode.Edit -> renderSpecialMode(MessageComposerMode.Edit(mode.timelineEvent, mode.text))
                is SendMode.Quote -> renderSpecialMode(MessageComposerMode.Quote(mode.timelineEvent, mode.text))
                is SendMode.Reply -> renderSpecialMode(MessageComposerMode.Reply(mode.timelineEvent, mode.text))
                is SendMode.Voice -> renderVoiceMessageMode(mode.text)
            }
        }

        // Re-render the reply preview's media when the user reveals hidden media elsewhere (e.g. in
        // the timeline) so it updates in place rather than only after re-opening the reply.
        mediaContentRevealManager.revealedEvents
                .onEach { composer.refreshRelatedMessageMedia() }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        attachmentActionsViewModel.stream()
                .filterIsInstance<AttachmentTypeSelectorSharedAction.SelectAttachmentTypeAction>()
                .onEach { onTypeSelected(it.attachmentType) }
                .launchIn(lifecycleScope)

        messageComposerViewModel.stateFlow.map { it.isFullScreen }
                .distinctUntilChanged()
                .onEach { isFullScreen ->
                    val state = if (isFullScreen) ExpandingBottomSheetBehavior.State.Expanded else ExpandingBottomSheetBehavior.State.Collapsed
                    bottomSheetBehavior?.setState(state)
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        if (savedInstanceState == null) {
            handleShareData()
        }
    }

    override fun onPause() {
        super.onPause()

        withState(messageComposerViewModel) {
            when {
                it.isVoiceRecording && requireActivity().isChangingConfigurations -> {
                    // we're rotating, maintain any active recordings
                }
                else -> {
                    messageComposerViewModel.handle(MessageComposerAction.OnEntersBackground(composer.getDraftContent().toString()))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        emojiKeyboardController?.destroy()
        emojiKeyboardController = null
        autoCompleters.values.forEach(AutoCompleter::clear)
        autoCompleters.clear()
        messageComposerViewModel.endAllVoiceActions()
    }

    override fun invalidate() = withState(
            timelineViewModel, messageComposerViewModel, attachmentViewModel
    ) { _, messageComposerState, _ ->
        (composer as? View)?.isVisible = messageComposerState.isComposerVisible
        val recorderClaimsSlot = vectorPreferences.isVoiceMessageButtonEnabled() &&
                messageComposerState.voiceRecordingUiState !is VoiceMessageRecorderView.RecordingUiState.Recording
        composer.sendButton.visibility = when {
            messageComposerState.isSendButtonVisible -> View.VISIBLE
            recorderClaimsSlot -> View.INVISIBLE
            else -> View.GONE
        }
    }

    private fun setupStickerPicker() {
        childFragmentManager.setFragmentResultListener(StickerPickerBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            val url = bundle.getString(StickerPickerBottomSheet.BUNDLE_URL) ?: return@setFragmentResultListener
            val width = bundle.getInt(StickerPickerBottomSheet.BUNDLE_WIDTH)
            val height = bundle.getInt(StickerPickerBottomSheet.BUNDLE_HEIGHT)
            val size = bundle.getLong(StickerPickerBottomSheet.BUNDLE_SIZE)
            // Only send info when we actually know the dimensions; otherwise omit it rather than send zeros.
            val info = if (width > 0 && height > 0) {
                ImageInfo(
                        mimeType = bundle.getString(StickerPickerBottomSheet.BUNDLE_MIME),
                        width = width,
                        height = height,
                        size = size,
                )
            } else {
                null
            }
            val content = MessageStickerContent(
                    body = bundle.getString(StickerPickerBottomSheet.BUNDLE_BODY) ?: "",
                    info = info,
                    url = url,
            )
            messageComposerViewModel.handle(MessageComposerAction.SendSticker(content))
        }
    }

    private fun setupBottomSheet() {
        val parentView = view?.parent as? View ?: return
        bottomSheetBehavior = ExpandingBottomSheetBehavior.from(parentView)?.apply {
            applyInsetsToContentViewWhenCollapsed = true
            topOffset = 22
            useScrimView = true
            scrimViewTranslationZ = 8
            minCollapsedHeight = { -1 }
            isDraggable = false
            callback = object : ExpandingBottomSheetBehavior.Callback {
                override fun onStateChanged(state: ExpandingBottomSheetBehavior.State) {
                    // Dragging is disabled while the composer is collapsed
                    bottomSheetBehavior?.isDraggable = state != ExpandingBottomSheetBehavior.State.Collapsed

                    val setFullScreen = when (state) {
                        ExpandingBottomSheetBehavior.State.Collapsed -> false
                        ExpandingBottomSheetBehavior.State.Expanded -> true
                        else -> return
                    }

                    messageComposerViewModel.handle(MessageComposerAction.SetFullScreen(setFullScreen))
                }

                override fun onSlidePositionChanged(view: View, yPosition: Float) = Unit
            }
        }
    }

    private fun setupComposer() {
        val composerEditText = composer.editText
        composerEditText.setHint(CommonStrings.room_message_placeholder)

        initAutoCompleter(composer.editText)

        observerUserTyping()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            composerEditText.setUseIncognitoKeyboard(vectorPreferences.useIncognitoKeyboard())
        }
        composerEditText.setSendMessageWithEnter(vectorPreferences.sendMessageWithEnter())

        composerEditText.setOnEditorActionListener { v, actionId, keyEvent ->
            val imeActionId = actionId and EditorInfo.IME_MASK_ACTION
            val isSendAction = EditorInfo.IME_ACTION_DONE == imeActionId || EditorInfo.IME_ACTION_SEND == imeActionId
            // Add external keyboard functionality (to send messages)
            val externalKeyboardPressedEnter = null != keyEvent &&
                    !keyEvent.isShiftPressed &&
                    keyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                    resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
            val sendMessageWithEnter = externalKeyboardPressedEnter && vectorPreferences.sendMessageWithEnter()
            val result = if (isSendAction || sendMessageWithEnter) {
                sendTextMessage(v.text.expandPillSpans())
                true
            } else false
            result
        }

        composer.emojiButton?.isVisible = vectorPreferences.showEmojiKeyboard()

        val showKeyboard = withState(timelineViewModel) { it.showKeyboardWhenPresented }
        if (isThreadTimeLine() && showKeyboard) {
            // Show keyboard when the user started a thread
            composerEditText.showKeyboard(andRequestFocus = true)
        }
        composer.callback = object : Callback {
            override fun onAddAttachment() {
                if (!::attachmentTypeSelector.isInitialized) {
                    attachmentTypeSelector = AttachmentTypeSelectorView(vectorBaseActivity, vectorBaseActivity.layoutInflater, this@MessageComposerFragment)
                    attachmentTypeSelector.setAttachmentVisibility(
                            AttachmentType.LOCATION,
                            vectorFeatures.isLocationSharingEnabled(),
                    )
                    // No maplibre below API 21: keep the entry visible but dimmed and inert.
                    attachmentTypeSelector.setAttachmentEnabled(
                            AttachmentType.LOCATION,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP,
                    )
                    attachmentTypeSelector.setAttachmentVisibility(
                            AttachmentType.POLL, !isThreadTimeLine()
                    )
                }
                attachmentTypeSelector.show(composer.attachmentButton)
            }

            override fun onExpandOrCompactChange() {
                composer.emojiButton?.isVisible = isEmojiKeyboardVisible
            }

            override fun onSendMessage(text: CharSequence) = withState(messageComposerViewModel) { state ->
                if (state.isVoiceRecording) {
                    messageComposerViewModel.handle(
                            MessageComposerAction.EndRecordingVoiceMessage(isCancelled = false, rootThreadEventId = state.rootThreadEventId)
                    )
                } else {
                    sendTextMessage(text, composer.formattedText)
                    if (state.isFullScreen) {
                        messageComposerViewModel.handle(MessageComposerAction.SetFullScreen(false))
                    }
                }
            }

            override fun onCloseRelatedMessage() {
                messageComposerViewModel.handle(MessageComposerAction.EnterRegularMode(false))
            }

            override fun onRichContentSelected(contentUri: Uri): Boolean {
                return sendUri(contentUri)
            }

            override fun onTextChanged(text: CharSequence) {
                messageComposerViewModel.handle(MessageComposerAction.OnTextChanged(composer.formattedText ?: text))
            }

            override fun onFullScreenModeChanged() = withState(messageComposerViewModel) { state ->
                messageComposerViewModel.handle(MessageComposerAction.SetFullScreen(!state.isFullScreen))
            }

            override fun onSetLink(isTextSupported: Boolean, initialLink: String?) {
                SetLinkFragment.show(isTextSupported, initialLink, childFragmentManager)
            }
        }
    }

    private fun initAutoCompleter(editText: EditText) {
        if (autoCompleters.containsKey(editText)) return

        autoCompleters[editText] =
                autoCompleterFactory.create(roomId, isThreadTimeLine())
                        .also { it.setup(editText) }
    }

    private fun sendTextMessage(text: CharSequence, formattedText: String? = null) {
        if (lockSendButton) {
            Timber.w("Send button is locked")
            return
        }
        // Empty submissions are normally ignored, but editing a media caption to empty is how the
        // caption is removed, so allow it in that single case.
        val isMediaCaptionEdit = withState(messageComposerViewModel) {
            (it.sendMode as? SendMode.Edit)?.timelineEvent?.getVectorLastMessageContent() is MessageWithAttachmentContent
        }
        if (text.isNotBlank() || isMediaCaptionEdit) {
            im.vector.app.core.utils.PerfTrace.report("send.tap", 0)
            composer.renderComposerMode(MessageComposerMode.Normal(""))
            lockSendButton = true
            if (formattedText != null) {
                messageComposerViewModel.handle(MessageComposerAction.SendMessage(text, formattedText, false))
            } else {
                messageComposerViewModel.handle(MessageComposerAction.SendMessage(text, null, vectorPreferences.isMarkdownEnabled()))
            }
            emojiKeyboardController?.dismiss()
            if (vectorPreferences.jumpToBottomOnSend()) {
                timelineViewModel.handle(RoomDetailAction.JumpToBottom)
            }
        }
    }

    private fun sendUri(uri: Uri): Boolean {
        val shareIntent = Intent(Intent.ACTION_SEND, uri)
        val isHandled = shareIntentHandler.handleIncomingShareIntent(shareIntent, ::onContentAttachmentsReady, onPlainText = {
            fatalError("Should not happen as we're generating a File based share Intent", vectorPreferences.failFast())
        })
        if (!isHandled) {
            Toast.makeText(requireContext(), CommonStrings.error_handling_incoming_share, Toast.LENGTH_SHORT).show()
        }
        return isHandled
    }

    private fun renderRegularMode(content: CharSequence) {
        // After a media send we clear the box, but sendMode still holds the just-sent caption until
        // popDraft resets it — an intermediate state emission would otherwise re-apply it over the
        // cleared composer. Swallow that one stale non-empty render, then stop once sendMode settles to "".
        if (suppressStaleComposerRender) {
            if (content.isBlank()) suppressStaleComposerRender = false else return
        }
        // After sending, popDraft emits Regular("") here; on a slow device the user may have already typed
        // into the cleared composer, so don't re-apply "" over it. (sendTextMessage does the real clear.)
        if (content.isBlank() && !composer.text.isNullOrBlank()) return
        autoCompleters.values.forEach(AutoCompleter::exitSpecialMode)
        composer.renderComposerMode(MessageComposerMode.Normal(content))
    }

    private fun renderSpecialMode(mode: MessageComposerMode.Special) {
        // Entering reply/edit/quote means we're past any send-clear; don't keep swallowing renders.
        suppressStaleComposerRender = false
        val allowCommands = mode is MessageComposerMode.Reply
        autoCompleters.values.forEach { it.enterSpecialMode(allowCommands) }
        composer.renderComposerMode(mode)
    }

    private fun observerUserTyping() {
        if (isThreadTimeLine()) return
        composer.editText.textChanges()
                .skipInitialValue()
                .debounce(300)
                .map { it.isNotEmpty() }
                .onEach {
                    Timber.d("Typing: User is typing: $it")
                    messageComposerViewModel.handle(MessageComposerAction.UserIsTyping(it))
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        composer.editText.focusChanges()
                .onEach {
                    timelineViewModel.handle(RoomDetailAction.ComposerFocusChange(it))
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun focusComposerAndShowKeyboard() {
        if ((composer as? View)?.isVisible == true) {
            composer.editText.showKeyboard(andRequestFocus = true)
        }
    }

    private fun handleSendButtonVisibilityChanged(event: MessageComposerViewEvents.AnimateSendButtonVisibility) {
        if (event.isVisible) {
            composer.sendButton.alpha = 0f
            composer.sendButton.isVisible = true
            composer.sendButton.animate().alpha(1f).setDuration(150).start()
        } else {
            val isRecording = withState(messageComposerViewModel) {
                it.voiceRecordingUiState is VoiceMessageRecorderView.RecordingUiState.Recording
            }
            val recorderClaimsSlot = vectorPreferences.isVoiceMessageButtonEnabled() && !isRecording
            composer.sendButton.visibility = if (recorderClaimsSlot) View.INVISIBLE else View.GONE
        }
    }

    private fun renderVoiceMessageMode(content: String) {
        ContentAttachmentData.fromJsonString(content)?.let { audioAttachmentData ->
            messageComposerViewModel.handle(MessageComposerAction.InitializeVoiceRecorder(audioAttachmentData))
        }
    }

    private fun emojiKeyboard(): EmojiKeyboardController {
        return emojiKeyboardController ?: EmojiKeyboardController(
                activity = requireActivity(),
                rootView = views.root,
                editText = composer.editText,
                roomId = roomId,
                sectionFactory = emojiPickerSectionFactory,
                scope = viewLifecycleOwner.lifecycleScope,
                onVisibilityChanged = { visible ->
                    composer.emojiButton?.apply {
                        contentDescription = getString(if (visible) CommonStrings.a11y_close_emoji_picker else CommonStrings.a11y_open_emoji_picker)
                        setImageResource(if (visible) R.drawable.ic_keyboard else R.drawable.ic_insert_emoji)
                    }
                },
        ).also { emojiKeyboardController = it }
    }

    private fun setupEmojiButton() {
        composer.emojiButton?.debouncedClicks {
            emojiKeyboard().toggle()
        }
        // Tapping the input returns to normal keyboard typing: dismiss the emoji panel (the soft
        // keyboard is still open behind it).
        composer.editText.setOnTouchListener { _, _ ->
            if (emojiKeyboardController?.isShowing == true) emojiKeyboardController?.dismiss()
            false
        }
    }

    private fun onCannotRecord() {
        // Update the UI, cancel the animation
        messageComposerViewModel.handle(MessageComposerAction.OnVoiceRecordingUiStateChanged(VoiceMessageRecorderView.RecordingUiState.Idle))
    }

    private fun handleJoinedToAnotherRoom(action: MessageComposerViewEvents.JoinRoomCommandSuccess) {
        composer.setTextIfDifferent("")
        lockSendButton = false
        navigator.openRoom(vectorBaseActivity, action.roomId)
    }

    private fun handleSlashCommandConfirmationRequest(action: MessageComposerViewEvents.SlashCommandConfirmationRequest) {
        when (action.parsedCommand) {
            is ParsedCommand.UnignoreUser -> promptUnignoreUser(action.parsedCommand)
            else -> TODO("Add case for ${action.parsedCommand.javaClass.simpleName}")
        }
        lockSendButton = false
    }

    private fun promptUnignoreUser(command: ParsedCommand.UnignoreUser) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.room_participants_action_unignore_title)
                .setMessage(getString(CommonStrings.settings_unignore_user, command.userId))
                .setPositiveButton(CommonStrings.unignore) { _, _ ->
                    messageComposerViewModel.handle(MessageComposerAction.SlashCommandConfirmed(command))
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun renderSendMessageResult(sendMessageResult: MessageComposerViewEvents.SendMessageResult) {
        when (sendMessageResult) {
            is MessageComposerViewEvents.SlashCommandLoading -> {
                showLoading(null)
            }
            is MessageComposerViewEvents.SlashCommandError -> {
                displayCommandError(getString(CommonStrings.command_problem_with_parameters, sendMessageResult.command.command))
            }
            is MessageComposerViewEvents.SlashCommandUnknown -> {
                displayCommandError(getString(CommonStrings.unrecognized_command, sendMessageResult.command))
            }
            is MessageComposerViewEvents.SlashCommandResultOk -> {
                handleSlashCommandResultOk(sendMessageResult.parsedCommand)
            }
            is MessageComposerViewEvents.SlashCommandResultError -> {
                dismissLoadingDialog()
                displayCommandError(errorFormatter.toHumanReadable(sendMessageResult.throwable))
            }
            is MessageComposerViewEvents.SlashCommandNotImplemented -> {
                displayCommandError(getString(CommonStrings.not_implemented))
            }
            is MessageComposerViewEvents.SlashCommandNotSupportedInThreads -> {
                displayCommandError(getString(CommonStrings.command_not_supported_in_threads, sendMessageResult.command.command))
            }
        }

        lockSendButton = false
    }

    private fun handleSlashCommandResultOk(parsedCommand: ParsedCommand) {
        dismissLoadingDialog()
        composer.setTextIfDifferent("")
        when (parsedCommand) {
            is ParsedCommand.DevTools -> {
                navigator.openDevTools(requireContext(), roomId)
            }
            is ParsedCommand.SetMarkdown -> {
                showSnackWithMessage(getString(if (parsedCommand.enable) CommonStrings.markdown_has_been_enabled else CommonStrings.markdown_has_been_disabled))
            }
            else -> Unit
        }
    }

    private fun displayCommandError(message: String) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.command_error)
                .setMessage(message)
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    private fun showSnackWithMessage(message: String) {
        // Anchor above the composer so PGP/command feedback sits at the bottom and tracks the keyboard.
        view?.showOptimizedSnackbar(message, anchorView = views.composerLayout.takeIf { it.isVisible })
    }

    private fun handleShowRoomUpgradeDialog(roomDetailViewEvents: MessageComposerViewEvents.ShowRoomUpgradeDialog) {
        val tag = MigrateRoomBottomSheet::javaClass.name
        val roomId = withState(timelineViewModel) { it.roomId }
        MigrateRoomBottomSheet.newInstance(roomId, roomDetailViewEvents.newVersion)
                .show(parentFragmentManager, tag)
    }

    private fun openRoomMemberProfile(userId: String) {
        navigator.openRoomMemberProfile(userId = userId, roomId = roomId, context = requireActivity())
    }

    private fun handleJumpToEvent(event: MessageComposerViewEvents.JumpToEvent) {
        // JumpToEvent isn't a SendMessageResult, so renderSendMessageResult's unlock never
        // runs for /jumpto* commands. Without this, a failed jump leaves the send button
        // permanently locked.
        lockSendButton = false
        val eventId = event.eventId
        if (eventId == null) {
            event.notFoundMessage?.let { showSnackWithMessage(it) }
            return
        }
        timelineViewModel.handle(RoomDetailAction.NavigateToEvent(eventId, highlight = true))
    }

    private val contentAttachmentActivityResultLauncher = registerStartForActivityResult { activityResult ->
        val data = activityResult.data ?: return@registerStartForActivityResult
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val sendData = AttachmentsPreviewActivity.getOutput(data)
            val keepOriginalSize = AttachmentsPreviewActivity.getKeepOriginalSize(data)
            dispatchSendMedia(sendData, !keepOriginalSize)
        }
    }

    // Used for the sign-key passphrase prompt; OpenKeychain caches it, then the user resends.
    private val pgpInteractionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // No-op: OpenKeychain has cached the passphrase; the user re-triggers the send.
    }

    private fun launchPgpInteraction(pendingIntent: PendingIntent) {
        try {
            pgpInteractionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (failure: Throwable) {
            Timber.w(failure, "Failed to launch OpenKeychain PendingIntent")
        }
    }

    // AttachmentsHelper.Callback
    override fun onContentAttachmentsReady(attachments: List<ContentAttachmentData>) {
        val grouped = attachments.toGroupedContentAttachmentData()
        if (grouped.notPreviewables.isNotEmpty()) {
            // Send the not previewable attachments right now (?)
            dispatchSendMedia(grouped.notPreviewables, false)
        }
        if (grouped.previewables.isNotEmpty()) {
            val intent = AttachmentsPreviewActivity.newIntent(requireContext(), AttachmentsPreviewArgs(grouped.previewables))
            contentAttachmentActivityResultLauncher.launch(intent)
        }
    }

    private fun dispatchSendMedia(attachments: List<ContentAttachmentData>, compressBeforeSending: Boolean) = withState(messageComposerViewModel) { state ->
        val replyToEvent = (state.sendMode as? SendMode.Reply)?.timelineEvent
        // Keep the caption as a spanned CharSequence (and tag literal :shortcode: emotes) so custom
        // emotes survive into the formatted caption body, instead of stringifying it here.
        val rawCaption = composer.text ?: ""
        val captionText: CharSequence? = rawCaption.toString().takeIf { it.isNotBlank() }
                ?.let { SpannableString(emoteShortcodeProcessor.process(roomId, rawCaption)) }
        val richHtml = composer.formattedText
        val captionFormattedText = richHtml?.takeIf { captionText != null }
        val autoMarkdown = captionText != null && captionFormattedText == null && vectorPreferences.isMarkdownEnabled()
        val resolved = resolveCaptionCommand(state, captionText, captionFormattedText, autoMarkdown)
        timelineViewModel.handle(
                RoomDetailAction.SendMedia(
                        attachments = attachments,
                        compressBeforeSending = compressBeforeSending,
                        replyToEvent = replyToEvent,
                        captionText = resolved.text,
                        captionFormattedText = resolved.formatted,
                        autoMarkdown = resolved.autoMarkdown,
                )
        )
        if (replyToEvent != null || captionText != null) {
            suppressStaleComposerRender = true
            composer.setTextIfDifferent("")
            composer.renderComposerMode(MessageComposerMode.Normal(""))
            messageComposerViewModel.handle(MessageComposerAction.OnAttachmentsSent)
        }
    }

    override fun onAttachmentError(throwable: Throwable) {
        showFailure(throwable)
    }

    // AttachmentTypeSelectorView.Callback
    private val typeSelectedActivityResultLauncher = registerForPermissionsResult { allGranted, deniedPermanently ->
        if (allGranted) {
            val pendingType = attachmentsHelper.pendingType
            if (pendingType != null) {
                attachmentsHelper.pendingType = null
                launchAttachmentProcess(pendingType)
            }
        } else {
            if (deniedPermanently) {
                activity?.onPermissionDeniedDialog(CommonStrings.denied_permission_generic)
            }
            cleanUpAfterPermissionNotGranted()
        }
    }

    private fun launchAttachmentProcess(type: AttachmentType) {
        when (type) {
            AttachmentType.CAMERA -> attachmentsHelper.openCamera(
                    activity = requireActivity(),
                    vectorPreferences = vectorPreferences,
                    cameraActivityResultLauncher = attachmentCameraActivityResultLauncher,
                    cameraVideoActivityResultLauncher = attachmentCameraVideoActivityResultLauncher
            )
            AttachmentType.FILE -> attachmentsHelper.selectFile(attachmentFileActivityResultLauncher)
            AttachmentType.GALLERY -> attachmentsHelper.selectGallery(attachmentMediaActivityResultLauncher)
            AttachmentType.VOICE_FILE -> attachmentsHelper.selectVoiceFile(attachmentVoiceFileActivityResultLauncher)
            AttachmentType.STICKER -> timelineViewModel.handle(RoomDetailAction.SelectStickerAttachment)
            AttachmentType.STICKER_LOCAL -> StickerPickerBottomSheet.show(childFragmentManager, roomId)
            AttachmentType.POLL -> navigator.openCreatePoll(requireContext(), roomId, null, PollMode.CREATE)
            AttachmentType.LOCATION -> {
                navigator
                        .openLocationSharing(
                                context = requireContext(),
                                roomId = roomId,
                                mode = LocationSharingMode.STATIC_SHARING,
                                initialLocationData = null,
                                locationOwnerId = session.myUserId
                        )
            }
        }
    }

    override fun onTypeSelected(type: AttachmentType) {
        if (checkPermissions(type.permissions, requireActivity(), typeSelectedActivityResultLauncher)) {
            launchAttachmentProcess(type)
        } else {
            attachmentsHelper.pendingType = type
        }
    }

    private val attachmentFileActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onFileResult(it.data)
        }
    }

    private val attachmentVoiceFileActivityResultLauncher = registerStartForActivityResult { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerStartForActivityResult
        val data = result.data ?: return@registerStartForActivityResult

        val composerState = withState(messageComposerViewModel) { it }
        val pendingReplyToEvent = (composerState.sendMode as? SendMode.Reply)?.timelineEvent
        val pendingCaption = composer.text?.toString().orEmpty().takeIf { it.isNotBlank() }
        val pendingFormatted = composer.formattedText?.takeIf { pendingCaption != null }
        val pendingAutoMarkdown = pendingCaption != null && pendingFormatted == null && vectorPreferences.isMarkdownEnabled()
        val resolved = resolveCaptionCommand(composerState, pendingCaption, pendingFormatted, pendingAutoMarkdown)
        if (pendingReplyToEvent != null || pendingCaption != null) {
            suppressStaleComposerRender = true
            composer.setTextIfDifferent("")
            composer.renderComposerMode(MessageComposerMode.Normal(""))
            messageComposerViewModel.handle(MessageComposerAction.OnAttachmentsSent)
        }

        val attachments = attachmentsHelper.processVoiceFileResult(data)
        if (attachments.isEmpty()) return@registerStartForActivityResult
        timelineViewModel.handle(
                RoomDetailAction.SendMedia(
                        attachments = attachments,
                        compressBeforeSending = false,
                        replyToEvent = pendingReplyToEvent,
                        captionText = resolved.text,
                        captionFormattedText = resolved.formatted,
                        autoMarkdown = resolved.autoMarkdown,
                )
        )
    }

    /**
     * In Regular send mode, route a media caption through the slash-command parser: message commands
     * rewrite the caption, action commands (e.g. /kick) run and drop the caption. Other send modes
     * (reply/edit/quote) keep the caption literal.
     */
    private fun resolveCaptionCommand(
            state: MessageComposerViewState,
            caption: CharSequence?,
            formatted: String?,
            autoMarkdown: Boolean,
    ): CaptionCommandResolution.Caption {
        if (caption == null || state.sendMode !is SendMode.Regular) {
            return CaptionCommandResolution.Caption(caption, formatted, autoMarkdown)
        }
        return when (val outcome = messageComposerViewModel.resolveCaptionCommand(caption, formatted, state.isInThreadTimeline())) {
            is CaptionCommandResolution.Caption -> outcome
            CaptionCommandResolution.CommandExecuted -> CaptionCommandResolution.Caption(null, null, false)
        }
    }

    private val attachmentMediaActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onMediaResult(it.data)
        }
    }

    private val attachmentCameraActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onCameraResult()
        }
    }

    private val attachmentCameraVideoActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            attachmentsHelper.onCameraVideoResult()
        }
    }

    private fun cleanUpAfterPermissionNotGranted() {
        // Reset all pending data
        timelineViewModel.pendingAction = null
        attachmentsHelper.pendingType = null
    }

    private fun handleShareData() {
        when (val sharedData = withState(timelineViewModel) { it.sharedData }) {
            is SharedData.Text -> {
                messageComposerViewModel.handle(MessageComposerAction.OnTextChanged(sharedData.text))
                messageComposerViewModel.handle(MessageComposerAction.EnterRegularMode(fromSharing = true))
            }
            is SharedData.Attachments -> {
                // open share edition
                onContentAttachmentsReady(sharedData.attachmentData)
            }
            // Forwards are sent directly from the room picker and never reach the composer.
            is SharedData.Forward -> Unit
            null -> Timber.v("No share data to process")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun insertUserDisplayNameInTextEditor(userId: String) {
        val startToCompose = composer.text.isNullOrBlank()

        if (startToCompose &&
                userId == session.myUserId) {
            // Empty composer, current user: start an emote
            composer.editText.setText("${Command.EMOTE.command} ")
            composer.editText.setSelection(Command.EMOTE.command.length + 1)
        } else {
            val roomMember = timelineViewModel.getMember(userId)
            val displayName = sanitizeDisplayName(roomMember?.displayName ?: userId)
            val span = PillImageSpan(
                    glideRequests,
                    avatarRenderer,
                    requireContext(),
                    MatrixItem.UserItem(userId, displayName, roomMember?.avatarUrl),
            ).also { it.bind(composer.editText) }
            val pill = buildSpannedString {
                append(displayName)
                setPillSpan(span, 0, displayName.length)
                append(" ")
            }
            if (startToCompose && displayName.startsWith("/")) {
                // Ensure displayName will not be interpreted as a Slash command
                composer.editText.append("\\")
            }
            // Always use EditText.getText().insert for adding pills as TextView.append doesn't appear
            // to upgrade to BufferType.Spannable as hinted at in the docs:
            // https://developer.android.com/reference/android/widget/TextView#append(java.lang.CharSequence)
            composer.editText.text.insert(composer.editText.selectionStart, pill)
        }
        focusComposerAndShowKeyboard()
    }

    /**
     * Sanitize the display name.
     *
     * @param displayName the display name to sanitize
     * @return the sanitized display name
     */
    private fun sanitizeDisplayName(displayName: String): String {
        if (displayName.endsWith(ircPattern)) {
            return displayName.substring(0, displayName.length - ircPattern.length)
        }

        return displayName
    }

    /**
     * Returns true if the current room is a Thread room, false otherwise.
     */
    private fun isThreadTimeLine(): Boolean = withState(timelineViewModel) { it.isThreadTimeline() }

    /** Set whether the keyboard should disable personalized learning. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun EditText.setUseIncognitoKeyboard(useIncognitoKeyboard: Boolean) {
        imeOptions = if (useIncognitoKeyboard) {
            imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }

    /** Set whether enter should send the message or add a new line. */
    private fun EditText.setSendMessageWithEnter(sendMessageWithEnter: Boolean) {
        if (sendMessageWithEnter) {
            inputType = inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE.inv()
            imeOptions = imeOptions or EditorInfo.IME_ACTION_SEND
        } else {
            inputType = inputType or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = imeOptions and EditorInfo.IME_ACTION_SEND.inv()
        }
    }
}
