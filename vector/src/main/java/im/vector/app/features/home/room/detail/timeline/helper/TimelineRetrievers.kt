/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.home.room.detail.timeline.pgp.PgpDecryptionRetriever
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.RichMessageBodyRenderer
import im.vector.app.features.home.room.detail.timeline.reply.ReplyPreviewRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.SpanUtils
import im.vector.app.features.html.VectorHtmlCompressor
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.pgp.PgpDecryptor
import im.vector.app.features.pgp.PgpKeyStore
import im.vector.app.features.pgp.PgpServiceManager
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import org.matrix.android.sdk.api.session.Session
import javax.inject.Inject

/**
 * The per-room async renderers that timeline items pull through
 * [im.vector.app.features.home.room.detail.timeline.TimelineEventController.Callback]. Bundled so
 * every host of timeline items (room timeline, search results…) builds them the same way.
 */
data class TimelineRetrievers(
        val previewUrlRetriever: PreviewUrlRetriever,
        val pgpDecryptionRetriever: PgpDecryptionRetriever,
        val replyPreviewRetriever: ReplyPreviewRetriever,
)

class TimelineRetrieversFactory @Inject constructor(
        private val session: Session,
        private val buildMeta: BuildMeta,
        private val vectorPreferences: VectorPreferences,
        private val displayableEventFormatter: DisplayableEventFormatter,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val textRendererFactory: EventTextRenderer.Factory,
        private val mediaContentRevealManager: MediaContentRevealManager,
        private val messageColorProvider: MessageColorProvider,
        private val htmlCompressor: VectorHtmlCompressor,
        private val htmlRenderer: EventHtmlRenderer,
        private val spanUtils: SpanUtils,
        private val imageContentRenderer: ImageContentRenderer,
        private val richMessageBodyRenderer: RichMessageBodyRenderer,
        private val pgpDecryptor: PgpDecryptor,
        private val messageTranslationStore: im.vector.app.features.translation.MessageTranslationStore,
        private val pgpServiceManager: PgpServiceManager,
        private val pgpKeyStore: PgpKeyStore,
        private val redactedContentRestorer: RedactedContentRestorer,
) {

    fun create(roomId: String, coroutineScope: CoroutineScope): TimelineRetrievers {
        return TimelineRetrievers(
                previewUrlRetriever = PreviewUrlRetriever(session, coroutineScope, buildMeta),
                pgpDecryptionRetriever = PgpDecryptionRetriever(coroutineScope, pgpServiceManager, pgpKeyStore),
                replyPreviewRetriever = ReplyPreviewRetriever(
                        vectorPreferences,
                        roomId,
                        session,
                        coroutineScope,
                        displayableEventFormatter,
                        pillsPostProcessorFactory,
                        textRendererFactory,
                        mediaContentRevealManager,
                        messageColorProvider,
                        htmlCompressor,
                        htmlRenderer,
                        spanUtils,
                        imageContentRenderer,
                        richMessageBodyRenderer,
                        pgpDecryptor,
                        messageTranslationStore,
                        redactedContentRestorer,
                ),
        )
    }
}
