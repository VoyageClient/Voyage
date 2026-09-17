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
        private val sessionHolder: ActiveSessionHolder
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
            val pillSpan = linkSpan.createPillSpan() ?: return@forEach
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

    private fun createPillImageSpan(matrixItem: MatrixItem) =
            PillImageSpan(GlideApp.with(context), avatarRenderer, context, matrixItem)

    private fun LinkSpan.createPillSpan(): PillImageSpan? {
        val supportedHosts = context.resources.getStringArray(im.vector.app.config.R.array.permalink_supported_hosts)
        val isPermalinkSupported = sessionHolder.getSafeActiveSession()?.permalinkService()?.isPermalinkSupported(supportedHosts, url).orFalse()
        if (isPermalinkSupported) {
            val matrixItem = when (val permalinkData = PermalinkParser.parse(url)) {
                is PermalinkData.UserLink -> permalinkData.toMatrixItem()
                is PermalinkData.RoomLink -> permalinkData.toMatrixItem()
                else -> null
            } ?: return null
            return createPillImageSpan(matrixItem)
        } else {
            return null
        }
    }

    private fun PermalinkData.UserLink.toMatrixItem(): MatrixItem? {
        val session = sessionHolder.getSafeActiveSession() ?: return null
        val member = roomId?.let { session.roomService().getRoomMember(userId, it) }
        val known = synchronized(knownSenders) { knownSenders[userId] }
        if (member != null || known != null) {
            return MatrixItem.UserItem(
                    userId,
                    member?.displayName?.takeUnless { it.isBlank() } ?: known?.displayName,
                    member?.avatarUrl?.takeUnless { it.isBlank() } ?: known?.avatarUrl,
            )
        }
        return session.getUser(userId)?.toMatrixItem()
    }

    private fun PermalinkData.RoomLink.toMatrixItem(): MatrixItem? =
            if (eventId == null) {
                val room: RoomSummary? = sessionHolder.getSafeActiveSession()?.getRoomSummary(roomIdOrAlias)
                when {
                    isRoomAlias -> MatrixItem.RoomAliasItem(roomIdOrAlias, room?.displayName, room?.avatarUrl)
                    else -> MatrixItem.RoomItem(roomIdOrAlias, room?.displayName, room?.avatarUrl)
                }
            } else {
                // Exclude event link (used in reply events, we do not want to pill the "in reply to")
                null
            }
}
