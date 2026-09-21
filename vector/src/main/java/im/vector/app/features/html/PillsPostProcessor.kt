/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.content.Context
import android.text.Spannable
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.home.AvatarRenderer
import io.noties.markwon.core.spans.LinkSpan
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

class PillsPostProcessor @AssistedInject constructor(
        @Assisted private val roomId: String?,
        private val context: Context,
        private val avatarRenderer: AvatarRenderer,
        private val sessionHolder: ActiveSessionHolder,
        private val itemResolver: PillItemResolver,
) :
        EventHtmlRenderer.PostProcessor {

    private val knownSenders = object : LinkedHashMap<String, SenderInfo>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, SenderInfo>): Boolean = size > 128
    }

    /* ==========================================================================================
     * Public api
     * ========================================================================================== */

    @AssistedFactory
    interface Factory {
        fun create(roomId: String?): PillsPostProcessor
    }

    /* ==========================================================================================
     * Specialization
     * ========================================================================================== */

    override fun afterRender(renderedText: Spannable) {
        addPillSpans(renderedText)
    }

    fun rememberSenders(senders: Iterable<SenderInfo>) {
        synchronized(knownSenders) {
            senders.forEach { sender ->
                if (!sender.displayName.isNullOrBlank() || !sender.avatarUrl.isNullOrBlank()) knownSenders[sender.userId] = sender
            }
        }
    }

    /* ==========================================================================================
     * Helper methods
     * ========================================================================================== */

    private fun addPillSpans(renderedText: Spannable) {
        addLinkSpans(renderedText)
    }

    private fun addPillSpan(
            renderedText: Spannable,
            pillSpan: PillImageSpan,
            startSpan: Int,
            endSpan: Int
    ) {
        renderedText.setPillSpan(pillSpan, startSpan, endSpan)
    }

    private fun addLinkSpans(renderedText: Spannable) {
        // We let markdown handle links and then we add PillImageSpan if needed.
        val codeSpans = renderedText.getSpans(0, renderedText.length, HtmlCodeSpan::class.java)
        val linkSpans = renderedText.getSpans(0, renderedText.length, LinkSpan::class.java)
        linkSpans.forEach { linkSpan ->
            val startSpan = renderedText.getSpanStart(linkSpan)
            val endSpan = renderedText.getSpanEnd(linkSpan)
            // A mention/permalink inside inline code or a code block should stay verbatim, not become a pill.
            if (codeSpans.any { renderedText.getSpanStart(it) < endSpan && startSpan < renderedText.getSpanEnd(it) }) return@forEach
            val pillSpan = linkSpan.createPillSpan(renderedText.subSequence(startSpan, endSpan).toString()) ?: return@forEach
            // GlideImagesPlugin causes duplicated pills if we have a nested spans in the pill span,
            // such as images or italic text.
            // Accordingly, it's better to remove all spans that are contained in this span before rendering.
            renderedText.getSpans(startSpan, endSpan, Any::class.java).forEach remove@{
                if (it !is LinkSpan) {
                    // Make sure to only remove spans that are contained in this link, and not are bigger than this link, e.g. like reply-blocks
                    val start = renderedText.getSpanStart(it)
                    if (start < startSpan) return@remove
                    val end = renderedText.getSpanEnd(it)
                    if (end > endSpan) return@remove

                    renderedText.removeSpan(it)
                }
            }
            addPillSpan(renderedText, pillSpan, startSpan, endSpan)
        }
    }

    // Outside a room (a biography, a profile note) nothing local backs the mention, so let the pill
    // look the user or room up. In a room the member event and the room summary are the authority.
    private fun createPillImageSpan(matrixItem: MatrixItem) =
            PillImageSpan(
                    GlideApp.with(context),
                    avatarRenderer,
                    context,
                    matrixItem,
                    itemResolver = itemResolver.takeIf { roomId == null },
            )

    private fun LinkSpan.createPillSpan(linkText: String): PillImageSpan? {
        val supportedHosts = context.resources.getStringArray(im.vector.app.config.R.array.permalink_supported_hosts)
        val isPermalinkSupported = sessionHolder.getSafeActiveSession()?.permalinkService()?.isPermalinkSupported(supportedHosts, url).orFalse()
        if (isPermalinkSupported) {
            // What the mention was written as. A pill falls back to it rather than to a raw id when we
            // don't know the user or room ourselves; a link whose text is just its target says nothing.
            val label = linkText.trim().takeUnless { it.isBlank() || it == url }
            val matrixItem = when (val permalinkData = PermalinkParser.parse(url)) {
                is PermalinkData.UserLink -> permalinkData.toMatrixItem(label)
                is PermalinkData.RoomLink -> permalinkData.toMatrixItem(label)
                else -> null
            } ?: return null
            return createPillImageSpan(matrixItem)
        } else {
            return null
        }
    }

    private fun PermalinkData.UserLink.toMatrixItem(label: String?): MatrixItem? {
        val session = sessionHolder.getSafeActiveSession() ?: return null
        val member = roomId?.let { session.roomService().getRoomMember(userId, it) }
        val known = synchronized(knownSenders) { knownSenders[userId] }
        if (member != null || known != null) {
            return MatrixItem.UserItem(
                    userId,
                    member?.displayName?.takeUnless { it.isBlank() } ?: known?.displayName?.takeUnless { it.isBlank() } ?: label,
                    member?.avatarUrl?.takeUnless { it.isBlank() } ?: known?.avatarUrl,
            )
        }
        // A mention we know nothing about still pills, under the name it was written as — but a link
        // whose text is just its target isn't a mention, and stays a link.
        val user = session.getUser(userId) ?: return label?.let { MatrixItem.UserItem(userId, it) }
        return MatrixItem.UserItem(userId, user.displayName?.takeUnless { it.isBlank() } ?: label, user.avatarUrl)
    }

    private fun PermalinkData.RoomLink.toMatrixItem(label: String?): MatrixItem? =
            if (eventId == null) {
                val room: RoomSummary? = sessionHolder.getSafeActiveSession()?.getRoomSummary(roomIdOrAlias)
                val name = room?.displayName?.takeUnless { it.isBlank() } ?: label
                when {
                    isRoomAlias -> MatrixItem.RoomAliasItem(roomIdOrAlias, name, room?.avatarUrl)
                    else -> MatrixItem.RoomItem(roomIdOrAlias, name, room?.avatarUrl)
                }
            } else {
                // Exclude event link (used in reply events, we do not want to pill the "in reply to")
                null
            }
}
