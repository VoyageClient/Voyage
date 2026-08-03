/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.widget.EditText
import com.otaliastudios.autocomplete.Autocomplete
import com.otaliastudios.autocomplete.AutocompleteCallback
import com.otaliastudios.autocomplete.CharPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GlideRequests
import im.vector.app.features.autocomplete.command.AutocompleteCommandPresenter
import im.vector.app.features.autocomplete.command.CommandAutocompletePolicy
import im.vector.app.features.autocomplete.emoji.AutocompleteEmojiData
import im.vector.app.features.autocomplete.emoji.AutocompleteEmojiPresenter
import im.vector.app.features.autocomplete.member.AutocompleteMemberItem
import im.vector.app.features.autocomplete.member.AutocompleteMemberPresenter
import im.vector.app.features.autocomplete.member.MentionFrequencyDataSource
import im.vector.app.features.autocomplete.room.AutocompleteRoomPresenter
import im.vector.app.features.command.Command
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.setPillSpan
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toEveryoneInRoomMatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.api.util.toRoomAliasMatrixItem

class AutoCompleter @AssistedInject constructor(
        @Assisted val roomId: String,
        @Assisted val isInThreadTimeline: Boolean,
        private val avatarRenderer: AvatarRenderer,
        private val commandAutocompletePolicy: CommandAutocompletePolicy,
        autocompleteCommandPresenterFactory: AutocompleteCommandPresenter.Factory,
        private val autocompleteMemberPresenterFactory: AutocompleteMemberPresenter.Factory,
        private val autocompleteRoomPresenter: AutocompleteRoomPresenter,
        private val autocompleteEmojiPresenter: AutocompleteEmojiPresenter,
        private val vectorPreferences: VectorPreferences,
        private val imagePackProvider: ImagePackProvider,
        private val mentionFrequencyDataSource: MentionFrequencyDataSource,
) {

    private lateinit var autocompleteMemberPresenter: AutocompleteMemberPresenter
    private val emoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @AssistedFactory
    interface Factory {
        fun create(roomId: String, isInThreadTimeline: Boolean): AutoCompleter
    }

    private val autocompleteCommandPresenter: AutocompleteCommandPresenter by lazy {
        autocompleteCommandPresenterFactory.create(isInThreadTimeline)
    }

    private var editText: EditText? = null

    fun enterSpecialMode(allowCommands: Boolean = false) {
        commandAutocompletePolicy.enabled = allowCommands
    }

    fun exitSpecialMode() {
        commandAutocompletePolicy.enabled = true
    }

    private lateinit var glideRequests: GlideRequests
    private val autocompletes: MutableSet<Autocomplete<*>> = hashSetOf()

    private val emojiCharPolicy = CharPolicy(TRIGGER_AUTO_COMPLETE_EMOJIS, true)
    private var emojiAutocomplete: Autocomplete<AutocompleteEmojiData>? = null

    fun setup(editText: EditText) {
        this.editText = editText
        glideRequests = GlideApp.with(editText)
        val backgroundDrawable = ColorDrawable(ThemeUtils.getColor(editText.context, android.R.attr.colorBackground))
        setupCommands(backgroundDrawable, editText)
        setupMembers(backgroundDrawable, editText)
        setupEmojis(backgroundDrawable, editText)
        setupRooms(backgroundDrawable, editText)
    }

    fun setEnabled(isEnabled: Boolean) =
        autocompletes.forEach {
            if (!isEnabled) { it.dismissPopup() }
            it.setEnabled(isEnabled)
        }

    fun clear() {
        this.editText = null
        emoteScope.coroutineContext.cancelChildren()
        autocompleteEmojiPresenter.clear()
        autocompleteRoomPresenter.clear()
        autocompleteCommandPresenter.clear()
        autocompleteMemberPresenter.clear()
        autocompletes.forEach {
            it.setEnabled(false)
            it.dismissPopup()
        }
        autocompletes.clear()
        emojiAutocomplete = null
    }

    private fun setupCommands(backgroundDrawable: Drawable, editText: EditText) {
        autocompletes += Autocomplete.on<Command>(editText)
                .with(commandAutocompletePolicy)
                .with(autocompleteCommandPresenter)
                .with(ELEVATION_DP)
                .with(backgroundDrawable)
                .with(object : AutocompleteCallback<Command> {
                    override fun onPopupItemClicked(editable: Editable, item: Command): Boolean {
                        editable.clear()
                        editable
                                .append(item.command)
                                .append(" ")
                        return true
                    }

                    override fun onPopupVisibilityChanged(shown: Boolean) {
                    }
                })
                .build()
    }

    private fun setupMembers(backgroundDrawable: ColorDrawable, editText: EditText) {
        autocompleteMemberPresenter = autocompleteMemberPresenterFactory.create(roomId)
        autocompletes += Autocomplete.on<AutocompleteMemberItem>(editText)
                .with(CharPolicy(TRIGGER_AUTO_COMPLETE_MEMBERS, true))
                .with(autocompleteMemberPresenter)
                .with(ELEVATION_DP)
                .with(backgroundDrawable)
                .with(object : AutocompleteCallback<AutocompleteMemberItem> {
                    override fun onPopupItemClicked(editable: Editable, item: AutocompleteMemberItem): Boolean {
                        val matrixItem = when (item) {
                            is AutocompleteMemberItem.Header -> null // do nothing header is not clickable
                            is AutocompleteMemberItem.RoomMember -> item.roomMemberSummary.toMatrixItem()
                                    .also { mentionFrequencyDataSource.record(roomId, item.roomMemberSummary.userId) }
                            is AutocompleteMemberItem.Everyone -> item.roomSummary.toEveryoneInRoomMatrixItem()
                        } ?: return false

                        insertMatrixItem(editText, editable, TRIGGER_AUTO_COMPLETE_MEMBERS, matrixItem)

                        return true
                    }

                    override fun onPopupVisibilityChanged(shown: Boolean) {
                    }
                })
                .build()
    }

    private fun setupRooms(backgroundDrawable: ColorDrawable, editText: EditText) {
        autocompletes += Autocomplete.on<RoomSummary>(editText)
                .with(CharPolicy(TRIGGER_AUTO_COMPLETE_ROOMS, true))
                .with(autocompleteRoomPresenter)
                .with(ELEVATION_DP)
                .with(backgroundDrawable)
                .with(object : AutocompleteCallback<RoomSummary> {
                    override fun onPopupItemClicked(editable: Editable, item: RoomSummary): Boolean {
                        insertMatrixItem(editText, editable, TRIGGER_AUTO_COMPLETE_ROOMS, item.toRoomAliasMatrixItem())
                        return true
                    }

                    override fun onPopupVisibilityChanged(shown: Boolean) {
                    }
                })
                .build()
    }

    private fun setupEmojis(backgroundDrawable: Drawable, editText: EditText) {
        if (!vectorPreferences.isEmojiAutocompleteEnabled()) return

        // Emotes load asynchronously; if they arrive after the popup was dismissed for lack of matches, re-show it.
        autocompleteEmojiPresenter.onEmotesArrived = { reshowEmojiPopupIfNeeded() }

        // Prime from cache so emotes suggest immediately on room open, before the live flow's first emission.
        imagePackProvider.cachedEmoticons(roomId).takeIf { it.isNotEmpty() }
                ?.let { autocompleteEmojiPresenter.updateCustomEmotes(it) }
        imagePackProvider.getEmoticonsLive(roomId)
                .onEach { autocompleteEmojiPresenter.updateCustomEmotes(it) }
                .launchIn(emoteScope)

        emojiAutocomplete = Autocomplete.on<AutocompleteEmojiData>(editText)
                // needSpaceBefore = true: only trigger when `:` starts a word, so things like
                // `https://`, `host:port`, `12:30` don't pop the emoji picker per keystroke.
                .with(emojiCharPolicy)
                .with(autocompleteEmojiPresenter)
                .with(ELEVATION_DP)
                .with(backgroundDrawable)
                .with(object : AutocompleteCallback<AutocompleteEmojiData> {
                    override fun onPopupItemClicked(editable: Editable, item: AutocompleteEmojiData): Boolean {
                        // Infer that the last ":" before the current cursor position is the original popup trigger
                        var startIndex = editable.subSequence(0, editText.selectionStart).lastIndexOf(":")
                        if (startIndex == -1) {
                            startIndex = 0
                        }

                        // Detect next word separator
                        var endIndex = editable.indexOf(" ", startIndex)
                        if (endIndex == -1) {
                            endIndex = editable.length
                        }

                        when (item) {
                            is AutocompleteEmojiData.Emoji -> {
                                // Replace the word by its unicode completion
                                editable.delete(startIndex, endIndex)
                                editable.insert(startIndex, item.emojiItem.emoji)
                            }
                            is AutocompleteEmojiData.Emote -> {
                                // Discord-style: just fill in the `:shortcode:` text. The literal shortcode is
                                // converted to the actual emote on send (see EmoteShortcodeProcessor), so the
                                // composer stays plain text instead of showing an inline image.
                                editable.replace(startIndex, endIndex, ":${item.image.shortcode}:")
                            }
                        }
                        return true
                    }

                    override fun onPopupVisibilityChanged(shown: Boolean) {
                    }
                })
                .build()
                .also { autocompletes += it }
    }

    private fun reshowEmojiPopupIfNeeded() {
        val editText = editText ?: return
        val autocomplete = emojiAutocomplete ?: return
        if (autocomplete.isPopupShowing()) return
        val text = editText.text
        val cursor = editText.selectionStart
        if (cursor >= 0 && emojiCharPolicy.shouldShowPopup(text, cursor)) {
            autocomplete.showPopup(emojiCharPolicy.getQuery(text))
        }
    }

    private fun insertMatrixItem(editText: EditText, editable: Editable, firstChar: Char, matrixItem: MatrixItem) =
            insertMatrixItemIntoEditable(editText, editable, firstChar, matrixItem)

    private fun insertMatrixItemIntoEditable(editText: EditText, editable: Editable, firstChar: Char, matrixItem: MatrixItem) {
        // Detect last firstChar and remove it
        var startIndex = editable.lastIndexOf(firstChar)
        if (startIndex == -1) {
            startIndex = 0
        }

        // Detect next word separator
        var endIndex = editable.indexOf(" ", startIndex)
        if (endIndex == -1) {
            endIndex = editable.length
        }

        // Replace the word by its completion
        val displayName = matrixItem.getBestName()

        editable.replace(startIndex, endIndex, "$displayName ")

        // Add the span
        val span = PillImageSpan(
                glideRequests,
                avatarRenderer,
                editText.context,
                matrixItem
        )
        span.bind(editText)

        editable.setPillSpan(span, startIndex, startIndex + displayName.length)
    }

    companion object {
        private const val ELEVATION_DP = 6f
        private const val TRIGGER_AUTO_COMPLETE_MEMBERS = '@'
        private const val TRIGGER_AUTO_COMPLETE_ROOMS = '#'
        private const val TRIGGER_AUTO_COMPLETE_EMOJIS = ':'
    }
}
