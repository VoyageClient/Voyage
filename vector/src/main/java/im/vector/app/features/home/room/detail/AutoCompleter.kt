/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.otaliastudios.autocomplete.Autocomplete
import com.otaliastudios.autocomplete.AutocompleteCallback
import com.otaliastudios.autocomplete.AutocompletePolicy
import com.otaliastudios.autocomplete.CharPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.extensions.bodyName
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
import im.vector.lib.ui.styles.R
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

    /**
     * Gate on the inline emoji keyboard: it searches the same emoji and emotes itself, and its panel only
     * stays up while the soft keyboard is open behind it — so a `:` popup appearing over it drags that
     * keyboard back to the front.
     */
    var isEmojiAutocompleteSuppressed: () -> Boolean = { false }

    /** Raised while a suggestion popup covers the top of the composer. */
    var onSuggestionsVisibilityChanged: (Boolean) -> Unit = {}

    // needSpaceBefore = true: only trigger when `:` starts a word, so things like `https://`, `host:port`
    // and `12:30` don't pop the emoji picker per keystroke.
    private val emojiCharPolicy = object : AutocompletePolicy {
        private val delegate = CharPolicy(TRIGGER_AUTO_COMPLETE_EMOJIS, true)

        override fun getQuery(text: Spannable): CharSequence = delegate.getQuery(text)

        override fun onDismiss(text: Spannable) = delegate.onDismiss(text)

        override fun shouldShowPopup(text: Spannable, cursorPos: Int): Boolean =
                !isEmojiAutocompleteSuppressed() && delegate.shouldShowPopup(text, cursorPos)

        override fun shouldDismissPopup(text: Spannable, cursorPos: Int): Boolean =
                isEmojiAutocompleteSuppressed() || delegate.shouldDismissPopup(text, cursorPos)
    }
    private var emojiAutocomplete: Autocomplete<AutocompleteEmojiData>? = null

    /** Close the `:` popup now, e.g. as the inline emoji keyboard opens over it. */
    fun dismissEmojiPopup() {
        emojiAutocomplete?.dismissPopup()
    }

    /**
     * [suggestionsContainer] hosts the suggestion lists. It lives in the timeline's layout directly above
     * the composer, so the lists are laid out with everything else. [composerSurface] is only read for its
     * background color, which the lists match; the classic composer sets that at runtime, so the view is
     * the only reliable source for it.
     */
    fun setup(editText: EditText, suggestionsContainer: ViewGroup, composerSurface: View) {
        this.editText = editText
        glideRequests = GlideApp.with(editText)
        autocompleteMemberPresenter = autocompleteMemberPresenterFactory.create(roomId)
        val background = {
            (composerSurface.background as? ColorDrawable)?.color
                    ?: ThemeUtils.getColor(editText.context, R.attr.vctr_toolbar_background)
        }
        val divider = { ThemeUtils.getColor(editText.context, R.attr.vctr_list_separator) }
        listOf(
                autocompleteCommandPresenter,
                autocompleteMemberPresenter,
                autocompleteRoomPresenter,
                autocompleteEmojiPresenter,
        ).forEach {
            it.popupBackgroundColor = background
            it.popupDividerColor = divider
            it.onContentVisibilityChanged = { shown -> onSuggestionsVisibilityChanged(shown) }
        }
        setupCommands(editText, suggestionsContainer)
        setupMembers(editText, suggestionsContainer)
        setupRooms(editText, suggestionsContainer)
        setupEmojis(editText, suggestionsContainer)
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

    private fun setupCommands(editText: EditText, host: ViewGroup) {
        autocompletes += Autocomplete.on<Command>(editText)
                .with(commandAutocompletePolicy)
                .with(autocompleteCommandPresenter)
                .withHostContainer(host)
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

    private fun setupMembers(editText: EditText, host: ViewGroup) {
        autocompletes += Autocomplete.on<AutocompleteMemberItem>(editText)
                .with(CharPolicy(TRIGGER_AUTO_COMPLETE_MEMBERS, true))
                .with(autocompleteMemberPresenter)
                .withHostContainer(host)
                .with(object : AutocompleteCallback<AutocompleteMemberItem> {
                    override fun onPopupItemClicked(editable: Editable, item: AutocompleteMemberItem): Boolean {
                        val matrixItem = when (item) {
                            is AutocompleteMemberItem.RoomMember -> item.roomMemberSummary.toMatrixItem()
                                    .also { mentionFrequencyDataSource.record(roomId, item.roomMemberSummary.userId) }
                            is AutocompleteMemberItem.Everyone -> item.roomSummary.toEveryoneInRoomMatrixItem()
                        }
                        val bodyName = (item as? AutocompleteMemberItem.RoomMember)?.roomMemberSummary?.bodyName()

                        insertMatrixItem(editText, editable, TRIGGER_AUTO_COMPLETE_MEMBERS, matrixItem, bodyName)

                        return true
                    }

                    override fun onPopupVisibilityChanged(shown: Boolean) {
                    }
                })
                .build()
    }

    private fun setupRooms(editText: EditText, host: ViewGroup) {
        autocompletes += Autocomplete.on<RoomSummary>(editText)
                .with(CharPolicy(TRIGGER_AUTO_COMPLETE_ROOMS, true))
                .with(autocompleteRoomPresenter)
                .withHostContainer(host)
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

    private fun setupEmojis(editText: EditText, host: ViewGroup) {
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
                .with(emojiCharPolicy)
                .with(autocompleteEmojiPresenter)
                .withHostContainer(host)
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

    private fun insertMatrixItem(editText: EditText, editable: Editable, firstChar: Char, matrixItem: MatrixItem, bodyName: String? = null) {
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

        // The pill draws over this text, so it is only what the plain-text body will carry.
        val displayName = bodyName ?: matrixItem.getBestName()

        editable.replace(startIndex, endIndex, "$displayName ")

        // Add the span
        val span = PillImageSpan(
                glideRequests,
                avatarRenderer,
                editText.context,
                matrixItem,
                bodyText = displayName
        )
        span.bind(editText)

        editable.setPillSpan(span, startIndex, startIndex + displayName.length)
    }

    companion object {
        private const val TRIGGER_AUTO_COMPLETE_MEMBERS = '@'
        private const val TRIGGER_AUTO_COMPLETE_ROOMS = '#'
        private const val TRIGGER_AUTO_COMPLETE_EMOJIS = ':'
    }
}
