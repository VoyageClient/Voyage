/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.annotation.StringRes
import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.media.isMediaHiddenInRoom
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import javax.inject.Inject

/**
 * Renders a reaction key for display. Shared so every "Reacted with …" surface resolves custom
 * (`mxc://`) emotes to the same inline image rather than a placeholder.
 */
class ReactionFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val htmlRenderer: Lazy<EventHtmlRenderer>,
        private val imagePackProvider: Lazy<ImagePackProvider>,
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val vectorPreferences: VectorPreferences,
) {

    /**
     * A unicode key renders as the emoji; a custom emote renders as its inline image (resolved by mxc
     * whether or not it's in a known pack), falling back to ❓ when the image can't be resolved or —
     * matching the timeline — when media is hidden for the room and the reaction wasn't sent by us.
     */
    fun renderKey(roomId: String?, key: String, reactionSenderId: String?): CharSequence {
        if (!key.isMxcUrl()) return key.prepareForDisplay()
        val session = activeSessionHolder.get().getSafeActiveSession()
        val addedByMe = reactionSenderId != null && reactionSenderId == session?.myUserId
        val blockMedia = session == null || (!addedByMe && isMediaHiddenInRoom(roomId, session, vectorPreferences))
        if (blockMedia) return QUESTION_MARK_EMOJI.prepareForDisplay()
        val shortcode = roomId?.let { id -> imagePackProvider.get().getEmoticons(id).firstOrNull { it.mxcUrl == key } }?.shortcode.orEmpty()
        val html = "<img data-mx-emoticon src=\"$key\" alt=\":$shortcode:\" title=\"$shortcode\" height=\"32\"/>"
        val rendered = htmlRenderer.get().render(html)
                .takeIf { (it as? Spanned)?.getSpans(0, it.length, EmoteImageSpan::class.java)?.isNotEmpty() == true }
        return rendered ?: QUESTION_MARK_EMOJI.prepareForDisplay()
    }

    /** Insert a rendered key (which may carry emote image spans) into a single-placeholder template. */
    fun applyTemplate(@StringRes templateRes: Int, display: CharSequence): CharSequence {
        val marker = "\u0001"
        val template = stringProvider.getString(templateRes, marker)
        val idx = template.indexOf(marker)
        if (idx < 0) return display
        return SpannableStringBuilder()
                .append(template.subSequence(0, idx))
                .append(display)
                .append(template.subSequence(idx + marker.length, template.length))
    }

    fun format(@StringRes templateRes: Int, roomId: String?, key: String, reactionSenderId: String?): CharSequence =
            applyTemplate(templateRes, renderKey(roomId, key, reactionSenderId))

    companion object {
        private const val QUESTION_MARK_EMOJI = "❓"
    }
}
