/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.identity.FoundThreePid
import org.matrix.android.sdk.api.session.identity.IdentityService
import org.matrix.android.sdk.api.session.identity.IdentityServiceListener
import org.matrix.android.sdk.api.session.identity.SharedState
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.identity.model.SignInvitationResult
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerConfig
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.widgets.WidgetService
import org.matrix.android.sdk.api.session.widgets.WidgetURLFormatter
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.internal.session.content.ContentUriResolver
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.media.LinkPreviewPrefetcher
import org.matrix.android.sdk.internal.session.media.WebUrlPattern
import org.matrix.android.sdk.internal.session.room.send.VideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import java.io.File
import javax.inject.Inject

// Desktop has no media stack, no widgets and no identity server yet. These keep the session graph
// complete: the ones the send path actually calls degrade quietly, the rest fail loudly if reached.

internal class DesktopThumbnailExtractor @Inject constructor() : ThumbnailExtractor {
    override fun extractThumbnail(attachment: ContentAttachmentData, withBlurHash: Boolean): ThumbnailExtractor.ThumbnailData? = null
    override fun extractVideoThumbnailFromFile(file: File): ThumbnailExtractor.ThumbnailData? = null
}

internal class DesktopImageExifTagRemover @Inject constructor() : ImageExifTagRemover {
    override suspend fun stripImageMetadata(imageFile: File): File = imageFile
}

internal class DesktopContentUriResolver @Inject constructor() : ContentUriResolver {
    override suspend fun copyToTempFile(uriString: String): File = TODO("not supported on desktop")
}

internal class DesktopVideoMetadataExtractor @Inject constructor() : VideoMetadataExtractor {
    override fun getVideoSize(attachment: ContentAttachmentData): Pair<Int, Int> = TODO("not supported on desktop")
}

internal class DesktopTextPillsUtils @Inject constructor() : TextPillsUtils {
    override fun processSpecialSpansToHtml(text: CharSequence): String? = null
    override fun processSpecialSpansToMarkdown(text: CharSequence): String? = null
}

internal class DesktopLinkPreviewPrefetcher @Inject constructor() : LinkPreviewPrefetcher {
    override suspend fun prefetch(roomId: String, text: CharSequence, encrypt: Boolean) = Unit
    override suspend fun bundleUrlPreviews(event: Event, encrypt: Boolean): Event = event
}

internal class DesktopWebUrlPattern @Inject constructor() : WebUrlPattern {

    // Deliberately simpler than android's Patterns.WEB_URL: enough to find the links in a message.
    override val regex = Regex("""https?://\S+""")
}

internal class DesktopWorkManagerConfig @Inject constructor() : WorkManagerConfig {
    override fun withNetworkConstraint(): Boolean = false
}

internal class DesktopWidgetService @Inject constructor() : WidgetService {

    override fun getRoomWidgets(
            roomId: String,
            widgetId: QueryStateEventValue,
            widgetTypes: Set<String>?,
            excludedTypes: Set<String>?
    ): List<Widget> = emptyList()

    override fun getRoomWidgetsFlow(
            roomId: String,
            widgetId: QueryStateEventValue,
            widgetTypes: Set<String>?,
            excludedTypes: Set<String>?
    ): Flow<List<Widget>> = emptyFlow()

    override fun getUserWidgets(widgetTypes: Set<String>?, excludedTypes: Set<String>?): List<Widget> = emptyList()

    override fun getUserWidgetsFlow(widgetTypes: Set<String>?, excludedTypes: Set<String>?): Flow<List<Widget>> = emptyFlow()
    override fun getWidgetURLFormatter(): WidgetURLFormatter = TODO("not supported on desktop")

    override fun getWidgetComputedUrl(widget: Widget, isLightTheme: Boolean, themeName: String?): String? = TODO("not supported on desktop")

    override suspend fun createRoomWidget(roomId: String, widgetId: String, content: Content): Widget = TODO("not supported on desktop")
    override suspend fun destroyRoomWidget(roomId: String, widgetId: String) { TODO("not supported on desktop") }
    override fun hasPermissionsToHandleWidgets(roomId: String): Boolean = TODO("not supported on desktop")
}

internal class DesktopWidgetURLFormatter @Inject constructor() : WidgetURLFormatter {

    override suspend fun format(
            baseUrl: String,
            params: Map<String, String>,
            forceFetchScalarToken: Boolean,
            bypassWhitelist: Boolean
    ): String = TODO("not supported on desktop")
}

internal class DesktopIdentityService @Inject constructor() : IdentityService {
    override fun getDefaultIdentityServer(): String? = TODO("not supported on desktop")
    override fun getCurrentIdentityServerUrl(): String? = TODO("not supported on desktop")
    override suspend fun isValidIdentityServer(url: String) { TODO("not supported on desktop") }
    override suspend fun setNewIdentityServer(url: String): String = TODO("not supported on desktop")
    override suspend fun disconnect() { TODO("not supported on desktop") }
    override suspend fun startBindThreePid(threePid: ThreePid) { TODO("not supported on desktop") }
    override suspend fun cancelBindThreePid(threePid: ThreePid) { TODO("not supported on desktop") }
    override suspend fun sendAgainValidationCode(threePid: ThreePid) { TODO("not supported on desktop") }
    override suspend fun submitValidationToken(threePid: ThreePid, code: String) { TODO("not supported on desktop") }
    override suspend fun finalizeBindThreePid(threePid: ThreePid) { TODO("not supported on desktop") }
    override suspend fun unbindThreePid(threePid: ThreePid) { TODO("not supported on desktop") }
    override suspend fun lookUp(threePids: List<ThreePid>): List<FoundThreePid> = TODO("not supported on desktop")
    override fun getUserConsent(): Boolean = TODO("not supported on desktop")
    override fun setUserConsent(newValue: Boolean) { TODO("not supported on desktop") }
    override suspend fun getShareStatus(threePids: List<ThreePid>): Map<ThreePid, SharedState> = TODO("not supported on desktop")
    override suspend fun sign3pidInvitation(identiyServer: String, token: String, secret: String): SignInvitationResult = TODO("not supported on desktop")
    override fun addListener(listener: IdentityServiceListener) { TODO("not supported on desktop") }
    override fun removeListener(listener: IdentityServiceListener) { TODO("not supported on desktop") }
}

internal class DesktopIntegrationManagerService @Inject constructor() : IntegrationManagerService {
    override fun addListener(listener: IntegrationManagerService.Listener) = Unit
    override fun removeListener(listener: IntegrationManagerService.Listener) = Unit
    override fun getOrderedConfigs(): List<IntegrationManagerConfig> = TODO("not supported on desktop")
    override fun getPreferredConfig(): IntegrationManagerConfig = TODO("not supported on desktop")
    override fun isIntegrationEnabled(): Boolean = TODO("not supported on desktop")
    override suspend fun setIntegrationEnabled(enable: Boolean) { TODO("not supported on desktop") }
    override suspend fun setWidgetAllowed(stateEventId: String, allowed: Boolean) { TODO("not supported on desktop") }
    override fun isWidgetAllowed(stateEventId: String): Boolean = TODO("not supported on desktop")
    override suspend fun setNativeWidgetDomainAllowed(widgetType: String, domain: String, allowed: Boolean) { TODO("not supported on desktop") }
    override fun isNativeWidgetDomainAllowed(widgetType: String, domain: String): Boolean = TODO("not supported on desktop")
}
