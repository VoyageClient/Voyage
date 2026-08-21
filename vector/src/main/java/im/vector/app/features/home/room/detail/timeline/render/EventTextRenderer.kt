/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.URLSpan
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.linkify.NoUnderlineUrlSpan
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.html.HtmlCodeSpan
import im.vector.app.features.html.PILL_PLACEHOLDER
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.setPillSpan
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

class EventTextRenderer @AssistedInject constructor(
        @Assisted private val roomId: String?,
        private val context: Context,
        private val avatarRenderer: AvatarRenderer,
        private val sessionHolder: ActiveSessionHolder,
        private val permalinkEventResolver: PermalinkEventResolver,
) {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String?): EventTextRenderer
    }

    // Cached at instance scope — `render()` runs per timeline text event. Allocating a fresh Regex /
    // String[] per call was a measurable hotspot when scrolling chatty rooms. Matrix permalinks use a
    // `…/#/<id>[/<eventId>]` fragment whose `!`, `$`, `:` chars Patterns.WEB_URL truncates (turning a
    // message link into a room link), so match the whole `#/…` run up to whitespace instead.
    private val permalinkRegex by lazy { Regex("""https?://[^\s/]+/#/\S+""") }
    private val supportedPermalinkHosts: Array<String> by lazy {
        context.resources.getStringArray(im.vector.app.config.R.array.permalink_supported_hosts)
    }

    /**
     * @param text the text to be rendered
     */
    fun render(text: CharSequence): CharSequence = PerfTrace.time("text.render") {
        val formattedText = renderPermalinks(text)
        renderNotifyEveryone(formattedText)
    }

    private fun renderNotifyEveryone(text: CharSequence): CharSequence {
        return if (roomId != null && text.contains(MatrixItem.NOTIFY_EVERYONE)) {
            SpannableStringBuilder(text).apply {
                addNotifyEveryoneSpans(this, roomId)
            }
        } else {
            text
        }
    }

    private fun renderPermalinks(text: CharSequence): CharSequence {
        return if (roomId != null) {
            SpannableStringBuilder(text).apply {
                addPermalinksSpans(this)
            }
        } else {
            text
        }
    }

    private fun addNotifyEveryoneSpans(text: Spannable, roomId: String) {
        val room: RoomSummary? = sessionHolder.getSafeActiveSession()?.roomService()?.getRoomSummary(roomId)
        val matrixItem = MatrixItem.EveryoneInRoomItem(
                id = roomId,
                avatarUrl = room?.avatarUrl,
                roomDisplayName = room?.displayName
        )

        // search for notify everyone text
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
        val codeSpans = text.getSpans(0, text.length, HtmlCodeSpan::class.java)
        val foundIndices = mutableListOf<Int>()
        var foundIndex = text.indexOf(MatrixItem.NOTIFY_EVERYONE, 0)
        while (foundIndex >= 0) {
            val end = foundIndex + MatrixItem.NOTIFY_EVERYONE.length
            val boundaryBefore = foundIndex == 0 || !isWordChar(text[foundIndex - 1])
            val boundaryAfter = end == text.length || !isWordChar(text[end])
            // Leave @room verbatim inside inline code or a code block.
            val inCode = codeSpans.any { text.getSpanStart(it) < end && foundIndex < text.getSpanEnd(it) }
            if (boundaryBefore && boundaryAfter && !inCode) {
                foundIndices.add(foundIndex)
            }
            foundIndex = text.indexOf(MatrixItem.NOTIFY_EVERYONE, end)
        }
        // Apply in reverse so collapsing a pill's backing text doesn't shift the earlier indices.
        foundIndices.asReversed().forEach { index ->
            addPillSpan(text, createPillImageSpan(matrixItem), index, index + MatrixItem.NOTIFY_EVERYONE.length)
        }
    }

    private data class PillPlacement(val item: MatrixItem, val start: Int, val end: Int, val url: String)

    private fun addPermalinksSpans(text: Spannable) {
        val placements = mutableListOf<PillPlacement>()
        // A permalink inside inline code or a code block should stay verbatim, not become a pill.
        val codeSpans = text.getSpans(0, text.length, HtmlCodeSpan::class.java)
        fun inCode(start: Int, end: Int) = codeSpans.any { text.getSpanStart(it) < end && start < text.getSpanEnd(it) }
        // Links carrying an explicit href (mentions/permalinks rendered as <a>), so labelled links —
        // whose visible text isn't the URL, e.g. a "Message in …" permalink — resolve too. LinkSpan
        // extends URLSpan, so this also catches Markwon's links. Skip ranges PillsPostProcessor already
        // turned into pills (user/room mentions) to avoid pilling them twice.
        val existingPills = text.getSpans(0, text.length, PillImageSpan::class.java)
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end < 0) continue
            if (inCode(start, end)) continue
            if (existingPills.any { text.getSpanStart(it) < end && start < text.getSpanEnd(it) }) continue
            val item = permalinkToMatrixItem(span.url) ?: continue
            placements.add(PillPlacement(item, start, end, span.url))
        }
        // Bare permalink URLs that appear as plain visible text (no <a> wrapper), skipping ranges
        // already covered above.
        for (match in permalinkRegex.findAll(text)) {
            // Trim trailing sentence punctuation the greedy match may have swallowed.
            val rawEnd = match.range.last + 1
            val end = trimTrailingUrlPunctuation(text, match.range.first, rawEnd)
            val start = match.range.first
            if (inCode(start, end)) continue
            if (placements.any { it.start < end && start < it.end }) continue
            val url = text.substring(start, end)
            val item = permalinkToMatrixItem(url) ?: continue
            placements.add(PillPlacement(item, start, end, url))
        }
        // Apply in reverse so collapsing a pill's backing text doesn't shift the earlier ranges.
        placements.sortedByDescending { it.start }.forEach {
            addPillSpan(text, createPillImageSpan(it.item), it.start, it.end)
            // setPillSpan collapsed the link text to a single placeholder char, dropping any underlying
            // URLSpan — re-add one over the pill so the movement method still routes taps to onUrlClicked
            // (otherwise a "Message in …" / permalink pill renders but is inert).
            text.setSpan(NoUnderlineUrlSpan(it.url), it.start, it.start + PILL_PLACEHOLDER.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun permalinkToMatrixItem(url: String): MatrixItem? {
        val isPermalinkSupported = sessionHolder.getSafeActiveSession()?.permalinkService()?.isPermalinkSupported(supportedPermalinkHosts, url).orFalse()
        if (!isPermalinkSupported) return null
        return when (val permalinkData = PermalinkParser.parse(url)) {
            is PermalinkData.UserLink -> permalinkData.toMatrixItem()
            is PermalinkData.RoomLink -> permalinkData.toMatrixItem()
            else -> null
        }
    }

    private fun trimTrailingUrlPunctuation(text: CharSequence, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1] in TRAILING_URL_PUNCTUATION) e--
        return e
    }

    private fun createPillImageSpan(matrixItem: MatrixItem) =
            PillImageSpan(GlideApp.with(context), avatarRenderer, context, matrixItem)

    private fun addPillSpan(
            renderedText: Spannable,
            pillSpan: PillImageSpan,
            startSpan: Int,
            endSpan: Int
    ) {
        renderedText.setPillSpan(pillSpan, startSpan, endSpan)
    }

    private fun PermalinkData.UserLink.toMatrixItem(): MatrixItem? =
            roomId?.let { sessionHolder.getSafeActiveSession()?.roomService()?.getRoomMember(userId, it)?.toMatrixItem() }
                    ?: sessionHolder.getSafeActiveSession()?.getUserOrDefault(userId)?.toMatrixItem()

    private fun PermalinkData.RoomLink.toMatrixItem(): MatrixItem {
        val room: RoomSummary? = sessionHolder.getSafeActiveSession()?.getRoomSummary(roomIdOrAlias)
        if (eventId.isNullOrEmpty()) {
            return when {
                isRoomAlias -> MatrixItem.RoomAliasItem(roomIdOrAlias, room?.displayName, room?.avatarUrl)
                room == null -> MatrixItem.RoomItem(roomIdOrAlias, context.getString(CommonStrings.pill_message_unknown_room_or_space))
                room.roomType == RoomType.SPACE -> MatrixItem.SpaceItem(roomIdOrAlias, room.displayName, room.avatarUrl)
                else -> MatrixItem.RoomItem(roomIdOrAlias, room.displayName, room.avatarUrl)
            }
        }
        // Links from other clients often address the room by alias, so compare the resolved room id.
        val targetRoomId = room?.roomId ?: roomIdOrAlias.takeUnless { isRoomAlias }
        return if (targetRoomId != null && targetRoomId == roomId) {
            val sender = permalinkEventResolver.getSender(targetRoomId, eventId!!)
            val senderName = sender?.displayName?.takeIf { it.isNotEmpty() }
            // A user item, so the avatar placeholder gets the sender's colour and initial rather than the room's.
            if (sender != null && senderName != null) {
                MatrixItem.UserItem(
                        sender.userId,
                        context.getString(CommonStrings.pill_message_from_user, senderName),
                        sender.avatarUrl,
                        senderName
                )
            } else {
                MatrixItem.RoomItem(targetRoomId, context.getString(CommonStrings.pill_message_from_unknown_user), sender?.avatarUrl)
            }
        } else {
            when {
                isRoomAlias -> MatrixItem.RoomAliasItem(
                        roomIdOrAlias,
                        context.getString(CommonStrings.pill_message_in_room, room?.displayName ?: roomIdOrAlias),
                        room?.avatarUrl,
                        room?.displayName
                )
                room != null -> MatrixItem.RoomItem(
                        roomIdOrAlias,
                        context.getString(CommonStrings.pill_message_in_room, room.displayName),
                        room.avatarUrl,
                        room.displayName
                )
                else -> MatrixItem.RoomItem(roomIdOrAlias, context.getString(CommonStrings.pill_message_in_unknown_room))
            }
        }
    }

    companion object {
        private const val TRAILING_URL_PUNCTUATION = ".,;:!?)]}>\"'"
    }
}
