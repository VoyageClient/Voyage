/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.createJSonViewerStyleProvider
import im.vector.app.core.utils.openExternalLinkWithConfirmation
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.action.EventSharedAction
import org.billcarsonfr.jsonviewer.JSonViewerDialog
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import java.io.File

internal class TimelineArchiveActions(
        private val activity: FragmentActivity,
        private val manager: FragmentManager,
        private val snapshot: () -> TimelineExportSnapshot?,
        private val directory: () -> File,
        private val profile: (String) -> Unit,
        private val jump: (String) -> Unit,
        private val save: (ExportEvent) -> Unit,
) : TimelineEventController.UrlClickCallback {
    fun handle(action: EventSharedAction) {
        when (action) {
            is EventSharedAction.Copy -> copyToClipboard(activity, action.content)
            is EventSharedAction.CopyEventId -> copyToClipboard(activity, action.eventId)
            is EventSharedAction.OpenUserProfile -> profile(action.userId)
            is EventSharedAction.JumpToRelation -> jump(action.targetEventId)
            is EventSharedAction.ViewSource -> JSonViewerDialog.newInstance(action.content, -1, createJSonViewerStyleProvider(ColorProvider(activity))).show(manager, "JSON_VIEWER")
            is EventSharedAction.Share -> event(action.eventId, action.messageContent.archivePath())?.let { event ->
                event.attachment?.let { shareMedia(activity, File(directory(), it), event.attachmentMime) }
            }
            is EventSharedAction.Save -> event(action.eventId, action.messageContent.archivePath())?.let(save)
            is EventSharedAction.OnUrlClicked -> onUrlClicked(action.url, action.title)
            is EventSharedAction.OnUrlLongClicked -> onUrlLongClicked(action.url)
            else -> Unit
        }
    }

    private fun event(id: String, path: String?) = snapshot()?.events?.firstOrNull { it.id == id }?.withAttachment(path)

    override fun onUrlClicked(url: String, title: String): Boolean {
        when (val link = runCatching { PermalinkParser.parse(url) }.getOrNull()) {
            is PermalinkData.UserLink -> profile(link.userId)
            is PermalinkData.RoomLink -> {
                val target = link.eventId
                if (target != null && snapshot()?.events?.any { it.id == target } == true) jump(target) else openExternalLinkWithConfirmation(activity, url, title)
            }
            else -> openExternalLinkWithConfirmation(activity, url, title)
        }
        return true
    }

    override fun onUrlLongClicked(url: String): Boolean { copyToClipboard(activity, url); return true }
}
