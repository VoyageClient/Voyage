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
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.utils.PERMISSIONS_FOR_WRITING_FILES
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.onPermissionDeniedDialog
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.shareMedia
import im.vector.app.features.share.ForwardPayloadHolder
import im.vector.app.features.share.IncomingShareActivity
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.attachmentviewer.AttachmentCommands
import im.vector.lib.attachmentviewer.AttachmentViewerActivity
import im.vector.lib.core.utils.compat.getParcelableArrayListExtraCompat
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.getRoom
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
    ) : Parcelable

    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var dataSourceFactory: AttachmentProviderFactory
    @Inject lateinit var imageContentRenderer: ImageContentRenderer

    private val viewModel: VectorAttachmentViewerViewModel by viewModel()
    private val errorFormatter by lazy(LazyThreadSafetyMode.NONE) { singletonEntryPoint().errorFormatter() }
    private var initialIndex = 0
    private var isAnimatingOut = false
    private var currentSourceProvider: BaseAttachmentProvider<*>? = null
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
        val isFirstCreation = savedInstanceState == null
        if (inMemoryData != null) {
            initialIndex = inMemoryData.indexOfFirst { it.eventId == args.eventId }.coerceAtLeast(0)
            installSourceProvider(dataSourceFactory.createProvider(inMemoryData, room, lifecycleScope), setCurrentItem = isFirstCreation)
        } else {
            lifecycleScope.launch {
                val events = withContext(Dispatchers.IO) {
                    room?.timelineService()?.getAttachmentMessages().orEmpty()
                }
                // Also match by transaction id: a just-sent event can get its local-echo id swapped for
                // the server id between the tap and this query, which would wrongly fall through to the
                // show-it-alone branch below.
                val index = events.indexOfFirst {
                    it.eventId == args.eventId || (it.root.unsignedData?.transactionId?.takeIf { tid -> tid.isNotEmpty() } == args.eventId)
                }
                if (index != -1) {
                    initialIndex = index
                    installSourceProvider(dataSourceFactory.createProvider(events, lifecycleScope), setCurrentItem = isFirstCreation)
                } else {
                    // Tapped event missing from the room's media list (e.g. a still-sending echo):
                    // show it alone rather than landing on an unrelated first entry.
                    val tappedItem = intent.getParcelableExtraCompat<Parcelable>(EXTRA_IMAGE_DATA) as? AttachmentData
                    if (tappedItem != null) {
                        initialIndex = 0
                        installSourceProvider(dataSourceFactory.createProvider(listOf(tappedItem), room, lifecycleScope), setCurrentItem = isFirstCreation)
                    }
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

    private fun installSourceProvider(sourceProvider: BaseAttachmentProvider<*>, setCurrentItem: Boolean) {
        sourceProvider.interactionListener = this
        sourceProvider.showOverlayCounter = args()?.standalonePreview != true
        setSourceProvider(sourceProvider)
        currentSourceProvider = sourceProvider
        providerInstalled = true
        if (setCurrentItem) {
            pager2.setCurrentItem(initialIndex, false)
            pager2.post {
                onSelectedPositionChanged(initialIndex)
            }
        }
        if (handoffPending) {
            handoffPending = false
            handOffToPager()
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.i("onResume Activity ${javaClass.simpleName}")
    }

    override fun onPause() {
        super.onPause()
        Timber.i("onPause Activity ${javaClass.simpleName}")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
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
        val forwardContent = coerceWholeDoublesToLongs(baseContent - "m.relates_to") as Map<String, Any?>
        val payloadId = ForwardPayloadHolder.put(forwardContent)
        startActivity(IncomingShareActivity.forwardIntent(this, timelineEvent.root.getClearType(), payloadId))
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
        ) = Intent(context, VectorAttachmentViewerActivity::class.java).also {
            it.putExtra(EXTRA_ARGS, Args(roomId, eventId, sharedTransitionName, transitionCornerRadiusPx, standalonePreview))
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
