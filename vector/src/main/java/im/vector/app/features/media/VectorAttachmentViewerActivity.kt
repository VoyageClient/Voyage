/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.media

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.transition.addListener
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.Transition
import com.airbnb.mvrx.viewModel
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.files.isLocalMediaUri
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.utils.PERMISSIONS_FOR_WRITING_FILES
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.onPermissionDeniedDialog
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.home.room.detail.RoomDetailPendingAction
import im.vector.app.features.home.room.detail.RoomDetailPendingActionStore
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.redaction.preservation.PreservedAttachmentResolver
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.share.ForwardPayloadHolder
import im.vector.app.features.share.IncomingShareActivity
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.AttachmentCommands
import im.vector.lib.attachmentviewer.AttachmentInfo
import im.vector.lib.attachmentviewer.AttachmentViewerActivity
import im.vector.lib.core.utils.compat.getParcelableArrayListExtraCompat
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.message.toForwardedInfoContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastEditNewContent
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VectorAttachmentViewerActivity : AttachmentViewerActivity(), AttachmentInteractionListener {

    @Parcelize
    data class Args(
            val roomId: String?,
            val eventId: String,
            val sharedTransitionName: String?,
            // The shared element has rounded corners with this pixel radius; animate them away during
            // the enter transition and restore them on return, so corners don't snap at either end.
            val transitionCornerRadiusPx: Int = 0,
            // A single avatar/banner opened outside any room-media context: no position counter
            val standalonePreview: Boolean = false,
            // Opened from the room's own timeline, so "Show in chat" returns to it instead of stacking a copy.
            val openedFromTimeline: Boolean = false,
    ) : Parcelable

    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var dataSourceFactory: AttachmentProviderFactory
    @Inject lateinit var imageContentRenderer: ImageContentRenderer
    @Inject lateinit var preservedAttachmentResolver: PreservedAttachmentResolver
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var roomDetailPendingActionStore: RoomDetailPendingActionStore
    @Inject lateinit var vectorPreferences: VectorPreferences

    override val loopVideos: Boolean
        get() = vectorPreferences.loopVideos()

    override fun isOverlayInteractionInProgress(): Boolean =
            infoDialog?.isShowing == true || currentSourceProvider?.isOverlayInteracting() == true

    private val viewModel: VectorAttachmentViewerViewModel by viewModel()
    private val errorFormatter by lazy(LazyThreadSafetyMode.NONE) { singletonEntryPoint().errorFormatter() }
    private var initialIndex = 0

    // Restored from saved state (e.g. after process death) so we reopen on the page the user was viewing, not the first one.
    private var restoredPosition = -1
    private var isAnimatingOut = false
    private var currentSourceProvider: BaseAttachmentProvider<*>? = null
    private var infoDialog: MediaInfoDialog? = null
    private var providerInstalled = false
    private var handoffPending = false

    // Absolute pixel corner radius for rounded shared elements.
    private var transitionCornerPx = 0f
    private var cornerAnimator: android.animation.ValueAnimator? = null
    private val downloadActionResultLauncher = registerForPermissionsResult { allGranted, deniedPermanently ->
        if (allGranted) {
            viewModel.pendingAction?.let {
                viewModel.handle(it)
            }
        } else if (deniedPermanently) {
            onPermissionDeniedDialog(CommonStrings.denied_permission_generic)
        }
        viewModel.pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate Activity ${javaClass.simpleName}")
        ThemeUtils.setActivityTheme(this, getOtherThemes())

        val args = args() ?: throw IllegalArgumentException("Missing arguments")

        restoredPosition = savedInstanceState?.getInt(STATE_CURRENT_POSITION, -1) ?: -1

        // Opened without a shared element (avatar/banner taps): close with the same page cross-fade.
        // Pre-34 this is done with overridePendingTransition right after finishing, see applyPageExitAnimation().
        if (args.sharedTransitionName == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out)
        }

        if (supportsSharedElementTransition && args.transitionCornerRadiusPx > 0) {
            transitionCornerPx = args.transitionCornerRadiusPx.toFloat()
            imageTransitionView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, transitionCornerPx)
                }
            }
            imageTransitionView.clipToOutline = true
        }

        if (savedInstanceState == null && addTransitionListener()) {
            args.sharedTransitionName?.let {
                ViewCompat.setTransitionName(imageTransitionView, it)
                transitionImageContainer.isVisible = true

                // Postpone transaction a bit until thumbnail is loaded
                val mediaData: Parcelable? = intent.getParcelableExtraCompat(EXTRA_IMAGE_DATA)
                if (mediaData is ImageContentRenderer.Data) {
                    pager2.isInvisible = true
                    supportPostponeEnterTransition()
                    schedulePostponedTransitionTimeout(imageTransitionView)
                    imageContentRenderer.renderForSharedElementTransition(mediaData, imageTransitionView) {
                        scheduleStartPostponedTransition(imageTransitionView)
                    }
                } else if (mediaData is VideoContentRenderer.Data) {
                    pager2.isInvisible = true
                    supportPostponeEnterTransition()
                    schedulePostponedTransitionTimeout(imageTransitionView)
                    imageContentRenderer.renderForSharedElementTransition(mediaData.thumbnailMediaData, imageTransitionView) {
                        scheduleStartPostponedTransition(imageTransitionView)
                    }
                }
            }
        }

        val session = activeSessionHolder.getSafeActiveSession() ?: return Unit.also { finish() }

        val room = args.roomId?.let { session.getRoom(it) }

        val inMemoryData = intent.getParcelableArrayListExtraCompat<AttachmentData>(EXTRA_IN_MEMORY_DATA)
        if (inMemoryData != null) {
            // Identity first: a gallery's items all share one eventId, so the eventId match alone
            // would always land on the first tile.
            val tapped = intent.getParcelableExtraCompat<Parcelable>(EXTRA_IMAGE_DATA) as? AttachmentData
            initialIndex = inMemoryData.indexOfFirst { it == tapped }.takeIf { it >= 0 }
                    ?: inMemoryData.indexOfFirst { it.eventId == args.eventId }.coerceAtLeast(0)
            installSourceProvider(dataSourceFactory.createProvider(inMemoryData, room, lifecycleScope))
            // A gallery tile opens on its own items instantly, but from the timeline the viewer should
            // page over the whole room's media — swap the full list in underneath, like the
            // provisional flow below, keeping the page the user is on.
            if (args.openedFromTimeline && !args.standalonePreview && room != null) {
                val tappedOffset = initialIndex
                lifecycleScope.launch {
                    val events = withContext(Dispatchers.IO) { loadRoomAttachmentEvents(room) }
                    val provider = dataSourceFactory.createProvider(events, lifecycleScope)
                    val base = provider.indexForEvent(args.eventId)
                    if (base != -1) {
                        // Gallery pages fan out in the same order as the in-memory list, so the
                        // in-gallery position carries over as an offset — floored at the tapped one,
                        // since currentPosition is only posted.
                        initialIndex = base + maxOf(currentPosition, tappedOffset)
                        installSourceProvider(provider)
                    }
                }
            }
        } else {
            // The room query below is slow on a cold cache, and until a provider is installed
            // there is no overlay at all. The tapped attachment carries everything the first
            // page needs, so it opens alone immediately and the full list swaps in underneath.
            val provisionalItem = intent.getParcelableExtraCompat<Parcelable>(EXTRA_IMAGE_DATA) as? AttachmentData
            if (provisionalItem != null) {
                initialIndex = 0
                installSourceProvider(dataSourceFactory.createProvider(listOf(provisionalItem), room, lifecycleScope))
            }
            lifecycleScope.launch {
                val events = withContext(Dispatchers.IO) { loadRoomAttachmentEvents(room) }
                // Asked of the provider: a gallery event fans out to several pages there.
                val provider = dataSourceFactory.createProvider(events, lifecycleScope)
                val index = provider.indexForEvent(args.eventId)
                if (index != -1) {
                    initialIndex = index
                    installSourceProvider(provider)
                } else if (provisionalItem != null && !providerInstalled) {
                    // Tapped event missing from the room's media list (e.g. a still-sending echo):
                    // show it alone rather than landing on an unrelated first entry.
                    initialIndex = 0
                    installSourceProvider(dataSourceFactory.createProvider(listOf(provisionalItem), room, lifecycleScope))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor = ContextCompat.getColor(this, im.vector.lib.ui.styles.R.color.black_alpha)
            @Suppress("DEPRECATION")
            window.navigationBarColor = ContextCompat.getColor(this, im.vector.lib.ui.styles.R.color.black_alpha)
        }

        observeViewEvents()
    }

    private suspend fun loadRoomAttachmentEvents(room: Room?): List<TimelineEvent> {
        val live = room?.timelineService()?.getAttachmentMessages().orEmpty()
        // Redacted media is gone from the SDK's tables, so a revealed one would otherwise
        // open alone instead of taking its place in the room's media.
        val preserved = args()?.roomId?.let { preservedAttachmentResolver.attachments(it) }.orEmpty()
        return if (preserved.isEmpty()) live else (live + preserved).sortedBy { it.root.originServerTs ?: 0L }
    }

    private fun installSourceProvider(sourceProvider: BaseAttachmentProvider<*>) {
        sourceProvider.interactionListener = this
        sourceProvider.showOverlayInfo = args()?.standalonePreview != true
        setSourceProvider(sourceProvider)
        currentSourceProvider = sourceProvider
        providerInstalled = true
        val lastIndex = (sourceProvider.getItemCount() - 1).coerceAtLeast(0)
        val targetPosition = (if (restoredPosition >= 0) restoredPosition else initialIndex).coerceIn(0, lastIndex)
        pager2.setCurrentItem(targetPosition, false)
        pager2.post {
            onSelectedPositionChanged(targetPosition)
        }
        if (handoffPending) {
            handoffPending = false
            handOffToPager()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_CURRENT_POSITION, currentPosition)
    }

    override fun onResume() {
        super.onResume()
        Timber.i("onResume Activity ${javaClass.simpleName}")
    }

    override fun onPause() {
        super.onPause()
        Timber.i("onPause Activity ${javaClass.simpleName}")
    }

    override fun onDestroy() {
        infoDialog?.dismiss()
        infoDialog = null
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        pausePlayback()
        if (currentPosition == initialIndex) {
            prepareSharedElementReturn()
        }
        isAnimatingOut = true
        @Suppress("DEPRECATION")
        super.onBackPressed()
        applyPageExitAnimation()
    }

    override fun shouldAnimateDismiss(): Boolean {
        return currentPosition != initialIndex
    }

    override fun animateClose() {
        pausePlayback()
        if (currentPosition == initialIndex) {
            prepareSharedElementReturn()
        }
        isAnimatingOut = true
        ActivityCompat.finishAfterTransition(this)
        applyPageExitAnimation()
    }

    private fun applyPageExitAnimation() {
        if (args()?.sharedTransitionName != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    // show back the transition view
    // TODO, we should track and update the mapping
    private fun prepareSharedElementReturn() {
        if (args()?.sharedTransitionName == null) return
        transitionImageContainer.isVisible = true
        roundTransitionCornerForClose()
    }

    // Round the corners back to the shared element's shape as the image shrinks into it.
    private fun roundTransitionCornerForClose() {
        if (!supportsSharedElementTransition) return
        val a = args() ?: return
        if (a.transitionCornerRadiusPx > 0) {
            animateTransitionCornerPx(to = a.transitionCornerRadiusPx.toFloat(), transition = window.sharedElementReturnTransition)
        }
    }

    private fun getOtherThemes() = ActivityOtherThemes.VectorAttachmentsPreview

    // Shared-element transitions, ViewOutlineProvider and clipToOutline are all API 21+; pre-21 the viewer
    // simply opens without the morph animation.
    private val supportsSharedElementTransition get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /**
     * Try and add a [Transition.TransitionListener] to the entering shared element
     * [Transition]. We do this so that we can load the full-size image after the transition
     * has completed.
     *
     * @return true if we were successful in adding a listener to the enter transition
     */
    private fun addTransitionListener(): Boolean {
        if (!supportsSharedElementTransition) return false
        val transition = window.sharedElementEnterTransition

        if (transition != null) {
            // There is an entering shared element transition so add a listener to it
            transition.addListener(
                    onEnd = { handOffToPager() },
                    onCancel = { handOffToPager() }
            )
            return true
        }

        // If we reach here then we have not added a listener
        return false
    }

    // Called at the end of the enter transition (and also on exit; the flag guards that).
    private fun handOffToPager() {
        if (isAnimatingOut) return
        if (!providerInstalled) {
            handoffPending = true
            return
        }
        transitionImageContainer.isVisible = false
        pager2.isInvisible = false
    }

    private fun args() = intent.getParcelableExtraCompat<Args>(EXTRA_ARGS)

    private fun scheduleStartPostponedTransition(sharedElement: View) {
        sharedElement.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        sharedElement.viewTreeObserver.removeOnPreDrawListener(this)
                        supportStartPostponedEnterTransition()
                        val a = args()
                        val enterTransition = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) window.sharedElementEnterTransition else null
                        if ((a?.transitionCornerRadiusPx ?: 0) > 0) {
                            animateTransitionCornerPx(to = 0f, transition = enterTransition)
                        }
                        return true
                    }
                })
    }

    private fun animateTransitionCornerPx(to: Float, transition: android.transition.Transition?) {
        cornerAnimator?.cancel()
        cornerAnimator = android.animation.ValueAnimator.ofFloat(transitionCornerPx, to).apply {
            duration = transition.durationCompat?.takeIf { it >= 0 } ?: DEFAULT_TRANSITION_MS
            addUpdateListener {
                transitionCornerPx = it.animatedValue as Float
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) imageTransitionView.invalidateOutline()
            }
            start()
        }
    }

    /**
     * Cap the postpone-enter-transition wait so the previous activity (timeline) doesn't sit
     * frozen while the full-size image downloads. The transition starts when whichever fires
     * first: the Glide load completes, or this timeout expires.
     * [supportStartPostponedEnterTransition] is a no-op after the first call so this is safe.
     */
    private fun schedulePostponedTransitionTimeout(sharedElement: View) {
        sharedElement.postDelayed({ scheduleStartPostponedTransition(sharedElement) }, POSTPONED_TRANSITION_TIMEOUT_MS)
    }

    private fun observeViewEvents() {
        val tag = this::class.simpleName.toString()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel
                        .viewEvents
                        .stream(tag)
                        .collect(::handleViewEvents)
            }
        }
    }

    private fun handleViewEvents(event: VectorAttachmentViewerViewEvents) {
        when (event) {
            is VectorAttachmentViewerViewEvents.ErrorDownloadingMedia -> showSnackBarError(event.error)
        }
    }

    private fun showSnackBarError(error: Throwable) {
        rootView.showOptimizedSnackbar(errorFormatter.toHumanReadable(error))
    }

    private fun hasWritePermission() =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    checkPermissions(PERMISSIONS_FOR_WRITING_FILES, this, downloadActionResultLauncher)

    override fun onDismiss() {
        animateClose()
    }

    override fun onPlayPause(play: Boolean) {
        handle(if (play) AttachmentCommands.StartVideo else AttachmentCommands.PauseVideo)
    }

    override fun videoSeekTo(percent: Int) {
        handle(AttachmentCommands.SeekTo(percent))
    }

    override fun onVolumeChanged(gain: Float, muted: Boolean) {
        handle(AttachmentCommands.SetVolume(gain, muted))
    }

    override fun onPlaybackSpeedChanged(speed: Float, changePitch: Boolean) {
        handle(AttachmentCommands.SetPlaybackSpeed(speed, changePitch))
    }

    override fun onControlsInteractionEnded() {
        restartAutoHideCountdown()
    }

    override fun onShare() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = currentSourceProvider?.getFileForSharing(currentPosition) ?: return@launch

            withContext(Dispatchers.Main) {
                shareMedia(
                        this@VectorAttachmentViewerActivity,
                        file,
                        getMimeTypeFromUri(this@VectorAttachmentViewerActivity, file.toUri())
                )
            }
        }
    }

    override fun onForward() {
        val timelineEvent = currentSourceProvider?.getTimelineEventAtPosition(currentPosition) ?: return
        val baseContent = timelineEvent.getLastEditNewContent() ?: timelineEvent.root.getClearContent().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val forwardContent = (coerceWholeDoublesToLongs(baseContent - "m.relates_to") as Map<String, Any?>) +
                timelineEvent.toForwardedInfoContent()
        val payloadId = ForwardPayloadHolder.put(forwardContent)
        startActivity(IncomingShareActivity.forwardIntent(this, timelineEvent.root.getClearType(), payloadId))
    }

    override fun onShowInChat() {
        val timelineEvent = currentSourceProvider?.getTimelineEventAtPosition(currentPosition)
        // Search results and preserved redactions may have no timeline row to resolve, so fall back
        // to the ids the viewer was opened with.
        val roomId = timelineEvent?.roomId ?: args()?.roomId ?: return
        val eventId = timelineEvent?.eventId ?: args()?.eventId ?: return
        if (args()?.openedFromTimeline == true) {
            // The room is the screen underneath: stacking another copy of it would leave the user
            // with two, and lose their scroll position in the one they came from.
            roomDetailPendingActionStore.data = RoomDetailPendingAction.JumpToEvent(eventId)
        } else {
            navigator.openRoom(this, roomId, eventId)
        }
        finish()
    }

    override fun onShowInfo() {
        val provider = currentSourceProvider ?: return
        val position = currentPosition
        val data = when (val attachment = provider.getAttachmentInfoAt(position)) {
            is AttachmentInfo.Image -> attachment.data
            is AttachmentInfo.AnimatedImage -> attachment.data
            is AttachmentInfo.Video -> attachment.data
        } as? AttachmentData ?: return

        val dialog = MediaInfoDialog(
                // The viewer's own theme is a bare fullscreen one, so the sheet takes the app's.
                context = ContextThemeWrapper(this, ThemeUtils.getApplicationThemeRes(this)),
                onDismiss = {
                    infoDialog = null
                    restartAutoHideCountdown()
                }
        )
        infoDialog = dialog
        dialog.show(MediaInfoCollector.fromEvent(this, data, provider.getTimelineEventAtPosition(position)))

        lifecycleScope.launch {
            val probed = withContext(Dispatchers.IO) {
                mediaSourceFor(provider, position, data)?.let {
                    MediaInfoCollector.probe(this@VectorAttachmentViewerActivity, it, isVideo = data is VideoContentRenderer.Data)
                }
            }
            if (probed != null && dialog.isShowing) dialog.update(probed)
        }
    }

    private suspend fun mediaSourceFor(provider: BaseAttachmentProvider<*>, position: Int, data: AttachmentData): MediaSource? {
        val preserved = (data as? ImageContentRenderer.Data)?.preservedFile ?: (data as? VideoContentRenderer.Data)?.preservedFile
        if (preserved != null) return MediaSource.LocalFile(preserved)
        // A still-sending attachment has no mxc url to download from, only the picked local one.
        data.url?.takeIf { data.allowNonMxcUrls && it.isLocalMediaUri() }?.let { return MediaSource.ContentUri(it.toUri()) }
        return provider.getFileForSharing(position)?.let { MediaSource.LocalFile(it) }
    }

    // Whole-number numeric fields decode from JSON as Double; re-serializing emits e.g. "w":1080.0
    // which Synapse rejects (M_BAD_JSON). Round-trip them back to Long.
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }

    override fun onDownload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val hasWritePermission = withContext(Dispatchers.Main) {
                hasWritePermission()
            }

            val file = currentSourceProvider?.getFileForSharing(currentPosition) ?: return@launch
            if (hasWritePermission) {
                viewModel.handle(VectorAttachmentViewerAction.DownloadMedia(file))
            } else {
                viewModel.pendingAction = VectorAttachmentViewerAction.DownloadMedia(file)
            }
        }
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"
        private const val EXTRA_IMAGE_DATA = "EXTRA_IMAGE_DATA"
        private const val EXTRA_IN_MEMORY_DATA = "EXTRA_IN_MEMORY_DATA"
        private const val STATE_CURRENT_POSITION = "STATE_CURRENT_POSITION"
        private const val POSTPONED_TRANSITION_TIMEOUT_MS = 150L
        private const val DEFAULT_TRANSITION_MS = 300L

        fun newIntent(
                context: Context,
                mediaData: AttachmentData,
                roomId: String?,
                eventId: String,
                inMemoryData: List<AttachmentData>,
                sharedTransitionName: String?,
                transitionCornerRadiusPx: Int = 0,
                standalonePreview: Boolean = false,
                openedFromTimeline: Boolean = false,
        ) = Intent(context, VectorAttachmentViewerActivity::class.java).also {
            it.putExtra(EXTRA_ARGS, Args(roomId, eventId, sharedTransitionName, transitionCornerRadiusPx, standalonePreview, openedFromTimeline))
            it.putExtra(EXTRA_IMAGE_DATA, mediaData)
            if (inMemoryData.isNotEmpty()) {
                it.putParcelableArrayListExtra(EXTRA_IN_MEMORY_DATA, ArrayList(inMemoryData))
            }
        }
    }
}

// Transition.getDuration (and the whole android.transition package) is API 19+.
private val android.transition.Transition?.durationCompat: Long?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) this?.duration else null
