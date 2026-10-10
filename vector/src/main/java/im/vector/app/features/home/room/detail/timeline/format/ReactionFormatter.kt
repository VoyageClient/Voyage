/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.text.SpannableStringBuilder
import androidx.annotation.StringRes
import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.media.isMediaHiddenInRoom
import im.vector.app.features.reactions.ReactionText
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject

/**
 * Renders a reaction key for display. Shared so every "Reacted with …" surface resolves custom
 * (`mxc://`) emotes to the same inline image rather than a placeholder.
 */
class ReactionFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val vectorPreferences: VectorPreferences,
) {

    fun renderKey(roomId: String?, key: String, reactionSenderId: String?): CharSequence {
        if (!ReactionText.containsImages(key)) return key.prepareForDisplay()
        val session = activeSessionHolder.get().getSafeActiveSession()
        val addedByMe = reactionSenderId != null && reactionSenderId == session?.myUserId
        val blockMedia = session == null || (!addedByMe && isMediaHiddenInRoom(roomId, session, vectorPreferences))
        return ReactionText.renderImages(key, blockMedia) { session?.contentUrlResolver()?.resolveFullSize(it) }.prepareForDisplay()
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
}
