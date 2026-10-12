/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.iconPopupMenu
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.home.room.detail.TimelineFragment
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.share.ForwardPayloadHolder
import im.vector.app.features.share.IncomingShareActivity
import im.vector.app.features.share.isDirectRoom
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.message.ForwardedInfo
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.toForwardPayload
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

class AudioPlaybackActions @Inject constructor(
        private val sessionHolder: ActiveSessionHolder,
        private val navigator: Navigator,
        private val notificationUtils: NotificationUtils,
        private val clock: Clock,
        private val errorFormatter: ErrorFormatter,
) {
    fun show(context: Context, anchor: View, track: AudioPlaybackController.Track, dismiss: () -> Unit) {
        val activity = findActivity(context) ?: return
        val popup = iconPopupMenu(context, anchor, R.menu.menu_audio_player_track)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.audioTrackSave -> saveWithPermission(activity, anchor, track)
                R.id.audioTrackForward -> execute(activity, anchor) {
                    val session = requireNotNull(sessionHolder.getSafeActiveSession()) { "No active session for audio forwarding" }
                    val cached = session.getRoom(track.roomId)?.timelineService()?.getTimelineEvent(track.eventId)?.root
                            ?: session.eventService().getEventFromCache(track.roomId, track.eventId)
                    val event = cached?.takeIf { it.getClearType() == EventType.MESSAGE } ?: Event(
                            type = EventType.MESSAGE,
                            eventId = track.eventId,
                            roomId = track.roomId,
                            senderId = cached?.senderId,
                            originServerTs = cached?.originServerTs,
                            content = track.content.toContent(),
                    )
                    val payload = event.copyAll(roomId = event.roomId ?: track.roomId)
                            .toForwardPayload(track.content.toContent(), includeSourceLink = !session.isDirectRoom(track.roomId))
                    val audioPayload = if (track.galleryIndex != null) {
                        val forwarded = requireNotNull(payload[ForwardedInfo.STABLE_KEY] as? Map<*, *>)
                        mapOf(ForwardedInfo.STABLE_KEY to (forwarded + ("content" to track.content.toContent())))
                    } else payload
                    val payloadId = ForwardPayloadHolder.put(audioPayload)
                    activity.startActivity(IncomingShareActivity.forwardIntent(activity, EventType.MESSAGE, payloadId))
                    dismiss()
                }
                R.id.audioTrackShare -> execute(activity, anchor) {
                    val file = download(activity, track)
                    shareMedia(activity, file, track.content.mimeType ?: "audio/*")
                }
                R.id.audioTrackShowInChat -> {
                    dismiss()
                    if (!showInOpenRoom(activity.supportFragmentManager, track)) {
                        navigator.openRoom(activity, track.roomId, track.eventId)
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun showInOpenRoom(manager: FragmentManager, track: AudioPlaybackController.Track): Boolean {
        for (fragment in manager.fragments.asReversed()) {
            if (!fragment.isVisible) continue
            if (showInOpenRoom(fragment.childFragmentManager, track)) return true
            if (fragment is TimelineFragment && fragment.showEventInChat(track.roomId, track.eventId)) return true
        }
        return false
    }

    private fun saveWithPermission(activity: FragmentActivity, anchor: View, track: AudioPlaybackController.Track) {
        fun save() = execute(activity, anchor) {
            val file = download(activity, track)
            saveMedia(activity, file, track.content.getFileName(), track.content.mimeType ?: "audio/*", notificationUtils, clock.epochMillis())
        }
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29 ||
                ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            save()
            return
        }
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register("audio-save-${UUID.randomUUID()}", ActivityResultContracts.RequestPermission()) { granted ->
            launcher.unregister()
            if (granted) save() else anchor.showOptimizedSnackbar(activity.getString(CommonStrings.denied_permission_generic))
        }
        launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private suspend fun download(context: Context, track: AudioPlaybackController.Track): File = withContext(Dispatchers.IO) {
        val url = track.content.getFileUrl()
        if (url?.startsWith("file://") == true || url?.startsWith("content://") == true) {
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(url))).use { input ->
                File.createTempFile("audio-share-", null, context.cacheDir).also { file -> file.outputStream().use { input.copyTo(it) } }
            }
        } else {
            requireNotNull(sessionHolder.getSafeActiveSession()).fileService().downloadFile(track.content)
        }
    }

    private fun execute(activity: FragmentActivity, anchor: View, action: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.w(failure, "Audio track action failed")
                anchor.showOptimizedSnackbar(errorFormatter.toHumanReadable(failure))
            }
        }
    }

    private fun findActivity(context: Context): FragmentActivity? = when (context) {
        is FragmentActivity -> context
        is ContextWrapper -> findActivity(context.baseContext)
        else -> null
    }
}
