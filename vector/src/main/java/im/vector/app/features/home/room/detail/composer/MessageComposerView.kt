/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Editable
import android.widget.EditText
import android.widget.ImageButton

interface MessageComposerView {

    companion object {
        const val MAX_LINES_WHEN_COLLAPSED = 10
    }

    val text: Editable?
    val formattedText: String?
    val editText: EditText
    val emojiButton: ImageButton?
    val sendButton: ImageButton
    val attachmentButton: ImageButton

    var callback: Callback?

    fun setTextIfDifferent(text: CharSequence?): Boolean
    fun renderComposerMode(mode: MessageComposerMode)

    /**
     * The composer content to persist as a draft. The rich editor returns its HTML; the plain editor
     * returns its text with mention pills serialised as matrix.to markdown links so they survive the
     * String round-trip (they are reconstructed as pills when the draft is restored).
     */
    fun getDraftContent(): CharSequence = formattedText ?: text?.toString().orEmpty()

    /** Re-render the replied-to/related-message media preview in place (e.g. after the user reveals
     *  hidden media elsewhere), without touching the composer's typed text. */
    fun refreshRelatedMessageMedia() = Unit
}

interface Callback : ComposerEditText.Callback {
    fun onCloseRelatedMessage()
    fun onSendMessage(text: CharSequence)
    fun onAddAttachment()
    fun onExpandOrCompactChange()
    fun onFullScreenModeChanged()
    fun onSetLink(isTextSupported: Boolean, initialLink: String?)
}
