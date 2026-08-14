/*
 * Copyright 2020-2024 New Vector Ltd.
 * Copyright 2018 stfalcon.com
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.viewpager2.widget.ViewPager2
import im.vector.lib.attachmentviewer.databinding.ActivityAttachmentViewerBinding
import im.vector.lib.ui.styles.R
import java.lang.ref.WeakReference
import kotlin.math.abs

abstract class AttachmentViewerActivity : AppCompatActivity(), AttachmentEventListener {

    private companion object {
        const val OVERLAY_FADE_MS = 150L

        /** Telegram's PhotoViewer hides its chrome after the same three seconds. */
        const val AUTO_HIDE_DELAY_MS = 3000L
    }

    /** Whether videos restart from the top when they finish, read as each page is selected. */
    protected open val loopVideos: Boolean = false

    /** True while the chrome is being held open by the user — a scrub in progress, an open menu. */
    protected open fun isOverlayInteractionInProgress(): Boolean = false

    private var videoIsPlaying = false

    private val autoHideRunnable = Runnable {
        if (systemUiVisibility && videoIsPlaying && !isOverlayInteractionInProgress()) {
            toggleOverlayViewVisibility()
        }
    }

    private fun scheduleAutoHide() {
        views.rootContainer.removeCallbacks(autoHideRunnable)
        if (videoIsPlaying && systemUiVisibility && !isTouchExplorationEnabled()) {
            views.rootContainer.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun cancelAutoHide() {
        views.rootContainer.removeCallbacks(autoHideRunnable)
    }

    /** For interactions ending in windows of their own (menus, sheets), which this activity never sees a touch from. */
    protected fun restartAutoHideCountdown() = scheduleAutoHide()

    private fun isTouchExplorationEnabled(): Boolean = runCatching {
        (getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager)
                ?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
    }.getOrDefault(false)

    protected val rootView: View
        get() = views.rootContainer
    protected val pager2: ViewPager2
        get() = views.attachmentPager
    protected val imageTransitionView: ImageView
        get() = views.transitionImageView
    protected val transitionImageContainer: ViewGroup
        get() = views.transitionImageContainer

    private var topInset = 0
    private var bottomInset = 0
    private var systemUiVisibility = true

    private var overlayView: View? = null
        set(value) {
            if (value == overlayView) return
            overlayView?.let { views.rootContainer.removeView(it) }
            views.rootContainer.addView(value)
            value?.updatePadding(top = topInset, bottom = bottomInset)
            field = value
        }

    private lateinit var views: ActivityAttachmentViewerBinding

    private lateinit var swipeDismissHandler: SwipeToDismissHandler
    private lateinit var directionDetector: SwipeDirectionDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    var currentPosition = 0
        private set

    private var swipeDirection: SwipeDirection? = null

    private fun isScaled() = attachmentsAdapter.isScaled(currentPosition)

    private val attachmentsAdapter = AttachmentsAdapter()

    private var wasScaled: Boolean = false
    private var isSwipeToDismissAllowed: Boolean = true
    private var isOverlayWasClicked = false

//    private val shouldDismissToBottom: Boolean
//        get() = e == null
//                || !externalTransitionImageView.isRectVisible
//                || !isAtStartPosition

    private var isImagePagerIdle = true

    fun setSourceProvider(sourceProvider: AttachmentSourceProvider) {
        attachmentsAdapter.attachmentSourceProvider = sourceProvider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setDecorViewFullScreen()

        views = ActivityAttachmentViewerBinding.inflate(layoutInflater)
        setContentView(views.root)
        views.attachmentPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        views.attachmentPager.adapter = attachmentsAdapter
        directionDetector = createSwipeDirectionDetector()
        gestureDetector = createGestureDetector()

        views.attachmentPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                isImagePagerIdle = state == ViewPager2.SCROLL_STATE_IDLE
            }

            override fun onPageSelected(position: Int) {
                onSelectedPositionChanged(position)
            }
        })

        swipeDismissHandler = createSwipeToDismissHandler()
        views.rootContainer.setOnTouchListener(swipeDismissHandler)
        views.rootContainer.viewTreeObserver.addOnGlobalLayoutListener { swipeDismissHandler.translationLimit = views.dismissContainer.height / 4 }

        scaleDetector = createScaleGestureDetector()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ViewCompat.setOnApplyWindowInsetsListener(views.rootContainer) { _, insets ->
                val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                overlayView?.updatePadding(top = systemBarsInsets.top, bottom = systemBarsInsets.bottom)
                topInset = systemBarsInsets.top
                bottomInset = systemBarsInsets.bottom
                insets
            }
        } else {
            // Pre-21 has no window-insets dispatch, so derive the system bar heights from platform resources;
            // otherwise the overlay (title/actions bar) renders under the translucent status bar.
            topInset = getSystemBarHeightPx("status_bar_height")
            // Reserve nav-bar space only with an on-screen nav bar: hardware-key devices still report a
            // navigation_bar_height but show no bar, leaving a gap below the playbar.
            val hasOnScreenNavBar = !ViewConfiguration.get(this).hasPermanentMenuKey()
            bottomInset = if (hasOnScreenNavBar) getSystemBarHeightPx("navigation_bar_height") else 0
            overlayView?.updatePadding(top = topInset, bottom = bottomInset)
        }
    }

    private fun getSystemBarHeightPx(resName: String): Int {
        val resId = resources.getIdentifier(resName, "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun setDecorViewFullScreen() {
        // This is important for the dispatchTouchEvent, if not we must correct
        // the touch coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            // New API instead of SYSTEM_UI_FLAG_IMMERSIVE
            window.decorView.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // New API instead of FLAG_TRANSLUCENT_STATUS
            @Suppress("DEPRECATION")
            window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
            // new API instead of FLAG_TRANSLUCENT_NAVIGATION
            @Suppress("DEPRECATION")
            window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE)
            @Suppress("DEPRECATION")
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS, WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            @Suppress("DEPRECATION")
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
    }

    open fun onSelectedPositionChanged(position: Int) {
        val previous = attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? BaseViewHolder
        previous?.onSelected(false)
        // The overlay belongs to whichever page is showing: a page left holding the listener keeps
        // writing its own position and duration into the controls of the one that is.
        (previous as? VideoViewHolder)?.eventListener = null
        val selected = attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(position) as? BaseViewHolder
        // Properties first: onSelected may start playback, which reads them.
        if (selected is VideoViewHolder) {
            selected.eventListener = WeakReference(this)
            selected.loopEnabled = loopVideos
        }
        selected?.onSelected(true)
        currentPosition = position
        videoIsPlaying = false
        cancelAutoHide()
        overlayView = attachmentsAdapter.attachmentSourceProvider?.overlayViewAtPosition(this@AttachmentViewerActivity, position)
        // The overlay comes back blank for the new page, and its first report is a tick away.
        selected?.publishState()
    }

    override fun onPause() {
        attachmentsAdapter.onPause(currentPosition)
        // The immersive hide must not outlive this screen: left hidden here, the bar can stay
        // gone in the rest of the app until it is next backgrounded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.show(WindowInsets.Type.navigationBars())
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        attachmentsAdapter.onResume(currentPosition)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Any touch anywhere holds the chrome; the countdown restarts once the finger lifts.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> cancelAutoHide()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleAutoHide()
        }

        // The zoomable view is configured to disallow interception when image is zoomed

        // Check if the overlay is visible, and wants to handle the click
        if (overlayView?.isVisible == true && overlayView?.dispatchTouchEvent(ev) == true) {
            return true
        }

        // Log.v("ATTACHEMENTS", "================\ndispatchTouchEvent $ev")
        handleUpDownEvent(ev)

        // Log.v("ATTACHEMENTS", "scaleDetector is in progress ${scaleDetector.isInProgress}")
        // Log.v("ATTACHEMENTS", "pointerCount ${ev.pointerCount}")
        // Log.v("ATTACHEMENTS", "wasScaled $wasScaled")
        if (swipeDirection == null && (scaleDetector.isInProgress || ev.pointerCount > 1 || wasScaled)) {
            wasScaled = true
//            Log.v("ATTACHEMENTS", "dispatch to pager")
            return views.attachmentPager.dispatchTouchEvent(ev)
        }

        // Log.v("ATTACHEMENTS", "is current item scaled ${isScaled()}")
        return (if (isScaled()) super.dispatchTouchEvent(ev) else handleTouchIfNotScaled(ev)).also {
//            Log.v("ATTACHEMENTS", "\n================")
        }
    }

    private fun handleUpDownEvent(event: MotionEvent) {
        // Log.v("ATTACHEMENTS", "handleUpDownEvent $event")
        if (event.action == MotionEvent.ACTION_UP) {
            handleEventActionUp(event)
        }

        if (event.action == MotionEvent.ACTION_DOWN) {
            handleEventActionDown(event)
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
    }

    private fun handleEventActionDown(event: MotionEvent) {
        swipeDirection = null
        wasScaled = false
        views.attachmentPager.dispatchTouchEvent(event)

        swipeDismissHandler.onTouch(views.rootContainer, event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleEventActionUp(event: MotionEvent) {
//        wasDoubleTapped = false
        swipeDismissHandler.onTouch(views.rootContainer, event)
        views.attachmentPager.dispatchTouchEvent(event)
        isOverlayWasClicked = dispatchOverlayTouch(event)
    }

    private fun handleSingleTap(event: MotionEvent, isOverlayWasClicked: Boolean) {
        if (isOverlayWasClicked) return
        // TODO if there is no overlay, we should at least toggle system bars?
        if (overlayView != null) {
            toggleOverlayViewVisibility()
            super.dispatchTouchEvent(event)
        }
    }

    private val deferredBarsHide = Runnable { if (!systemUiVisibility) hideSystemUI() }

    private fun toggleOverlayViewVisibility() {
        // The quick fade rather than a TransitionManager pass: the 300ms auto-transition reads
        // as lag when the chrome pops up in reaction to playback ending.
        if (systemUiVisibility) {
            // we hide — the system bars only after the fade: hiding them shifts the insets
            // padding, and the chrome would visibly drop before it has faded out.
            systemUiVisibility = false
            cancelAutoHide()
            overlayView?.let { fadeOverlay(it, toVisible = false) }
            views.rootContainer.postDelayed(deferredBarsHide, OVERLAY_FADE_MS)
        } else {
            // we show
            views.rootContainer.removeCallbacks(deferredBarsHide)
            showSystemUI()
            overlayView?.let { fadeOverlay(it, toVisible = true) }
            scheduleAutoHide()
        }
    }

    private fun handleTouchIfNotScaled(event: MotionEvent): Boolean {
//        Log.v("ATTACHEMENTS", "handleTouchIfNotScaled $event")
        directionDetector.handleTouchEvent(event)

        return when (swipeDirection) {
            SwipeDirection.Up, SwipeDirection.Down -> {
                if (isSwipeToDismissAllowed && !wasScaled && isImagePagerIdle) {
                    swipeDismissHandler.onTouch(views.rootContainer, event)
                } else true
            }
            SwipeDirection.Left, SwipeDirection.Right -> {
                views.attachmentPager.dispatchTouchEvent(event)
            }
            else -> true
        }
    }

    private var overlayHiddenForDrag = false

    private fun handleSwipeViewMove(translationY: Float, translationLimit: Int) {
        val alpha = calculateTranslationAlpha(translationY, translationLimit)
        views.backgroundView.alpha = alpha
        views.dismissContainer.alpha = alpha
        // The chrome fades away as soon as the dismiss drag starts, and only comes back — with
        // the spring-back to zero — when the drag is abandoned.
        val overlay = overlayView ?: return
        if (!systemUiVisibility) return
        if (translationY != 0f && !overlayHiddenForDrag) {
            overlayHiddenForDrag = true
            fadeOverlay(overlay, toVisible = false)
        } else if (translationY == 0f && overlayHiddenForDrag) {
            overlayHiddenForDrag = false
            fadeOverlay(overlay, toVisible = true)
        }
    }

    private fun fadeOverlay(overlay: View, toVisible: Boolean) {
        overlay.animate().cancel()
        if (toVisible) {
            overlay.isVisible = true
            overlay.animate().alpha(1f).setDuration(OVERLAY_FADE_MS).setListener(null).start()
        } else {
            overlay.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.animate().setListener(null)
                    // onAnimationEnd also fires on cancel, where a re-show is underway.
                    if (overlay.alpha == 0f) overlay.isVisible = false
                }
            }).start()
        }
    }

    private fun dispatchOverlayTouch(event: MotionEvent): Boolean =
            overlayView
                    ?.let { it.isVisible && it.dispatchTouchEvent(event) }
                    ?: false

    private fun calculateTranslationAlpha(translationY: Float, translationLimit: Int): Float =
            1.0f - 1.0f / translationLimit.toFloat() / 4f * abs(translationY)

    private fun createSwipeToDismissHandler(): SwipeToDismissHandler =
            SwipeToDismissHandler(
                    swipeView = views.dismissContainer,
                    shouldAnimateDismiss = { shouldAnimateDismiss() },
                    onDismiss = { animateClose() },
                    onSwipeViewMove = ::handleSwipeViewMove
            )

    private fun createSwipeDirectionDetector() =
            SwipeDirectionDetector(this) { swipeDirection = it }

    private fun createScaleGestureDetector() =
            ScaleGestureDetector(this, ScaleGestureDetector.SimpleOnScaleGestureListener())

    private fun createGestureDetector() =
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                // Where a double tap means nothing, the chrome toggles on the raw UP: waiting
                // out the double-tap window there makes fast hide/reveal taps feel swallowed.
                private var handledOnUp = false

                override fun onDown(e: MotionEvent): Boolean {
                    handledOnUp = false
                    return false
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val holder = currentViewHolder()
                    val width = views.rootContainer.width
                    if (holder is VideoViewHolder && width > 0 && !holder.handlesDoubleTapAt(e.x / width)) {
                        handledOnUp = true
                        if (isImagePagerIdle) handleSingleTap(e, isOverlayWasClicked)
                    }
                    return false
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (!handledOnUp && isImagePagerIdle) {
                        handleSingleTap(e, isOverlayWasClicked)
                    }
                    return false
                }

                // Act on the second tap's UP, not onDoubleTap's DOWN, so quick-scale
                // (double-tap + drag) doesn't also trigger a seek.
                override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        handleDoubleTap(e)
                    }
                    return false
                }
            })

    private fun currentViewHolder() =
            attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? BaseViewHolder

    private fun handleDoubleTap(event: MotionEvent) {
        if (!isImagePagerIdle || isOverlayWasClicked || isScaled()) return
        val holder = currentViewHolder() ?: return
        val width = views.rootContainer.width
        if (width <= 0) return
        val consumed = holder.onDoubleTapped(event.x / width)
        // A double tap with nothing to do on a video is two quick chrome toggles (hide, reveal).
        // Images keep their double tap: it belongs to the zoom.
        if (!consumed && holder is VideoViewHolder) {
            handleSingleTap(event, isOverlayWasClicked)
        }
    }

    override fun onEvent(event: AttachmentEvents) {
        if (event is AttachmentEvents.VideoEvent && event.isPlaying != videoIsPlaying) {
            videoIsPlaying = event.isPlaying
            // A paused video keeps its chrome; playback starting arms the countdown.
            if (videoIsPlaying) {
                scheduleAutoHide()
            } else {
                cancelAutoHide()
                // Whatever paused it — the end of the clip included — the controls come back.
                if (!systemUiVisibility) toggleOverlayViewVisibility()
            }
        }
        if (overlayView is AttachmentEventListener) {
            (overlayView as? AttachmentEventListener)?.onEvent(event)
        }
    }

    protected open fun shouldAnimateDismiss(): Boolean = true

    /** finishAfterTransition holds onPause() back until the exit animation ends, so playback has to be stopped by hand. */
    protected fun pausePlayback() {
        attachmentsAdapter.onPause(currentPosition)
    }

    protected open fun animateClose() {
        pausePlayback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
        }
        finish()
    }

    fun handle(commands: AttachmentCommands) {
        (attachmentsAdapter.recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? BaseViewHolder)
                ?.handleCommand(commands)
    }

    private fun hideSystemUI() {
        // Enables regular immersive mode.
        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            // new API instead of SYSTEM_UI_FLAG_HIDE_NAVIGATION
            window.decorView.windowInsetsController?.hide(WindowInsets.Type.navigationBars())
            // New API instead of SYSTEM_UI_FLAG_IMMERSIVE
            window.decorView.windowInsetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // New API instead of FLAG_TRANSLUCENT_STATUS
            @Suppress("DEPRECATION")
            window.statusBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
            // New API instead of FLAG_TRANSLUCENT_NAVIGATION
            @Suppress("DEPRECATION")
            window.navigationBarColor = ContextCompat.getColor(this, R.color.half_transparent_status_bar)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    // Shows the system bars by removing all the flags
// except for the ones that make the content appear under the system bars.
    private fun showSystemUI() {
        systemUiVisibility = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // New API instead of SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN and SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            // The hide() in hideSystemUI is not undone by anything else on R+; without this the
            // navigation bar stays gone for good once the chrome has been hidden once.
            window.decorView.windowInsetsController?.show(WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
