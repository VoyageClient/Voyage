/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import im.vector.app.R
import im.vector.app.core.platform.VectorBaseActivity

/** Keeps the emoji panel and IME sharing space within the same measurement pass. */
class EmojiPanelHostLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var stripView: ViewGroup? = null
    private var stationaryViews = emptyList<View>()
    private var clippedViews = emptyList<View>()
    private var keyboardTranslationResetPending = false

    internal val canTranslateKeyboard: Boolean get() = !isHeightFrozen && desiredStripHeight == 0

    internal fun finishKeyboardAnimation() {
        keyboardTranslationResetPending = true
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (keyboardTranslationResetPending) {
            keyboardTranslationResetPending = false
            setKeyboardTranslation(0f)
        }
    }

    // Counter-translate headers so they remain anchored below the status bar.
    internal fun setKeyboardTranslation(offset: Float) {
        val shift = offset.coerceAtMost(0f)
        translationY = shift
        stationaryViews.forEach { it.translationY = if (shift == 0f) 0f else -shift }
        clippedViews.forEach { view ->
            ViewCompat.setClipBounds(view, if (shift == 0f) null else Rect(0, (-shift).toInt(), view.width, view.height))
        }
    }

    private var desiredStripHeight = 0
    private var unshrunkHeight = 0
    private var frozenHeight = 0
    private var frozenWidth = 0
    private var windowFocused = true
    private var incomingHeight = 0

    internal var onWindowFocusRestored: (() -> Unit)? = null

    internal val isHeightFrozen: Boolean get() = frozenHeight != 0

    // Releasing the hold must invalidate the cached measurement with requestLayout().
    private val clearFreeze = object : Runnable {
        override fun run() {
            if (frozenHeight != 0 && (hostActivity()?.isImeAnimating == true || hostActivity()?.isRestoringComposerKeyboard == true)) {
                postDelayed(this, 100L)
                return
            }
            frozenHeight = 0
            requestLayout()
        }
    }

    // Dialogs need to reclaim the keyboard space; system overlays should leave it in place.
    internal var shouldHoldHeightOnFocusLoss: () -> Boolean = { hostActivity()?.shouldHoldImeSpaceOnFocusLoss == true }

    /** The strip itself; the emoji panel is parented here. */
    val strip: ViewGroup get() = checkNotNull(stripView) { "emojiPanelContainer missing" }

    override fun onFinishInflate() {
        super.onFinishInflate()
        stripView = findViewById(R.id.emojiPanelContainer)
        stationaryViews = listOf(
                R.id.appBarLayout, R.id.massRedactionBanner, R.id.pinnedMessagesBanner,
                R.id.tombstoneBanner, R.id.userIdentityWarningView, R.id.syncStateView, R.id.liveLocationStatusIndicator,
        ).mapNotNull { findViewById<View>(it) }
        clippedViews = listOf(R.id.rootConstraintLayout, R.id.timelineRecyclerView).mapNotNull { findViewById<View>(it) }
    }

    /** Height the panel wants, as if the keyboard were not taking any of the window. */
    fun setDesiredStripHeight(px: Int) {
        if (desiredStripHeight == px) return
        desiredStripHeight = px
        requestLayout()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        windowFocused = hasWindowFocus
        when {
            hasWindowFocus -> {
                releaseSettledHeightHold()
                unfreezeHeightWhenSettled()
                onWindowFocusRestored?.invoke()
            }
            shouldHoldHeightOnFocusLoss() -> freezeHeight()
        }
    }

    internal fun releaseSettledHeightHold() {
        if (windowFocused && hostActivity()?.isRestoringComposerKeyboard != true && frozenHeight != 0 && incomingHeight == frozenHeight) {
            removeCallbacks(clearFreeze)
            frozenHeight = 0
            requestLayout()
        }
    }

    private fun hostActivity(): VectorBaseActivity<*>? {
        var candidate = context
        while (candidate is ContextWrapper) {
            if (candidate is VectorBaseActivity<*>) return candidate
            candidate = candidate.baseContext
        }
        return null
    }

    private fun freezeHeight() {
        removeCallbacks(clearFreeze)
        if (height > 0) {
            frozenHeight = height
            frozenWidth = width
        }
    }

    private fun unfreezeHeightWhenSettled() {
        if (frozenHeight == 0) return
        removeCallbacks(clearFreeze)
        postDelayed(clearFreeze, FREEZE_GRACE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation or a multi-window resize: the old full height means nothing now.
        if (w != oldw) unshrunkHeight = 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        incomingHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (frozenHeight != 0) {
            val widthChanged = MeasureSpec.getSize(widthMeasureSpec) != frozenWidth
            // Settled: the window really is the height we are holding, so letting go changes nothing on screen.
            if (widthChanged || (windowFocused && MeasureSpec.getSize(heightMeasureSpec) == frozenHeight)) {
                if (widthChanged) frozenHeight = 0
                removeCallbacks(clearFreeze)
                post(clearFreeze)
            }
        }
        val heightSpec = if (frozenHeight != 0) MeasureSpec.makeMeasureSpec(frozenHeight, MeasureSpec.EXACTLY) else heightMeasureSpec
        val available = MeasureSpec.getSize(heightSpec)
        if (available > unshrunkHeight) unshrunkHeight = available
        val takenByKeyboard = (unshrunkHeight - available).coerceAtLeast(0)
        val height = (desiredStripHeight - takenByKeyboard).coerceAtLeast(0)
        // In-place so this measure pass uses it; assigning layoutParams would schedule another one.
        stripView?.layoutParams?.let { if (it.height != height) it.height = height }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    companion object {
        private const val FREEZE_GRACE_MS = 600L
    }
}
