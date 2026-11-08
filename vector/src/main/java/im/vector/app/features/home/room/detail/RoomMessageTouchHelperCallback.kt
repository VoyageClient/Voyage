/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyTouchHelperCallback
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.timer.Clock
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.min

class RoomMessageTouchHelperCallback(
        private val context: Context,
        @DrawableRes replyIcon: Int,
        @DrawableRes editIcon: Int,
        @DrawableRes selectIcon: Int,
        private val handler: QuickReplayHandler,
        private val clock: Clock,
) : EpoxyTouchHelperCallback() {

    interface QuickReplayHandler {
        fun performQuickReplyOnHolder(model: EpoxyModel<*>)
        fun performQuickEditOnHolder(model: EpoxyModel<*>)
        fun performSelectOnHolder(model: EpoxyModel<*>)
        fun canSwipeModel(model: EpoxyModel<*>): Boolean
        fun canEditModel(model: EpoxyModel<*>): Boolean
        fun canSelectModel(model: EpoxyModel<*>): Boolean

        /** The swipe has taken over the gesture; other touch listeners won't see the rest of it. */
        fun onSwipeStarted()
    }

    override fun onSelectedChanged(viewHolder: EpoxyViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ACTION_STATE_SWIPE) handler.onSwipeStarted()
    }

    private enum class SwipeAction { NONE, REPLY, EDIT, SELECT }

    private var activeDx = 0f
    private var startTracking = false
    private var hapticAction = SwipeAction.NONE

    private var swipedModel: EpoxyModel<*>? = null
    private var swipedModelEditable = false
    private var swipeAction = SwipeAction.NONE
    private var touchListenerAttached = false

    private var replyButtonProgress: Float = 0F
    private var lastReplyButtonAnimationTime: Long = 0
    private var drawnIcon: Drawable? = null

    private val replyDrawable = tintedIcon(replyIcon)
    private val editDrawable = tintedIcon(editIcon)
    private val selectDrawable = tintedIcon(selectIcon)

    private val triggerDistance = convertToPx(100)
    private val editTriggerDistance = convertToPx(180)
    private val minShowDistance = convertToPx(20)
    private val triggerDelta = convertToPx(20)

    // Leftward swipes reach the edit threshold; rightward ones only ever select.
    private fun maxTranslation(dX: Float): Float =
            (if (dX < 0 && swipedModelEditable) editTriggerDistance else triggerDistance) + triggerDelta

    override fun onSwiped(viewHolder: EpoxyViewHolder, direction: Int) {
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder, target: EpoxyViewHolder): Boolean {
        return false
    }

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder): Int {
        val model = viewHolder.model
        var flags = 0
        if (handler.canSwipeModel(model)) flags = flags or ItemTouchHelper.START
        if (handler.canSelectModel(model)) flags = flags or ItemTouchHelper.END
        return ItemTouchHelper.Callback.makeMovementFlags(0, flags)
    }

    // Unreachable thresholds: a released item must always animate back, never fly out.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = Float.MAX_VALUE

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        endSwipe()
        swipedModelEditable = false
        startTracking = false
        hapticAction = SwipeAction.NONE
        replyButtonProgress = 0f
        drawnIcon = null
    }

    override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: EpoxyViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
    ) {
        if (actionState == ACTION_STATE_SWIPE) {
            attachTouchListener(recyclerView)
            if (isCurrentlyActive) {
                val model = try {
                    viewHolder.model
                } catch (e: IllegalStateException) {
                    Timber.e(e)
                    null
                }
                if (model !== swipedModel) {
                    swipedModelEditable = model != null && handler.canEditModel(model)
                }
                swipedModel = model
                swipeAction = actionFor(dX)
            }
        }
        val drawDx = if (isCurrentlyActive) {
            activeDx = dX
            startTracking = true
            val max = maxTranslation(dX)
            dX.coerceIn(-max, max)
        } else {
            // Scale the release animation down to the clamped position the item was left at.
            val overshoot = abs(activeDx)
            val max = maxTranslation(activeDx)
            if (overshoot > max) dX * (max / overshoot) else dX
        }
        super.onChildDraw(c, recyclerView, viewHolder, drawDx, dY, actionState, isCurrentlyActive)
        drawReplyButton(c, viewHolder.itemView, isCurrentlyActive)
    }

    private fun actionFor(dX: Float): SwipeAction = when {
        dX > 0 -> if (dX >= triggerDistance) SwipeAction.SELECT else SwipeAction.NONE
        swipedModelEditable && -dX >= editTriggerDistance -> SwipeAction.EDIT
        -dX >= triggerDistance -> SwipeAction.REPLY
        else -> SwipeAction.NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener(recyclerView: RecyclerView) {
        if (touchListenerAttached) return
        touchListenerAttached = true
        recyclerView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    swipedModel?.let {
                        when (swipeAction) {
                            SwipeAction.REPLY -> handler.performQuickReplyOnHolder(it)
                            SwipeAction.EDIT -> handler.performQuickEditOnHolder(it)
                            SwipeAction.SELECT -> handler.performSelectOnHolder(it)
                            SwipeAction.NONE -> Unit
                        }
                    }
                    endSwipe()
                }
                MotionEvent.ACTION_CANCEL -> endSwipe()
            }
            false
        }
    }

    private fun endSwipe() {
        swipedModel = null
        swipeAction = SwipeAction.NONE
    }

    private fun drawReplyButton(canvas: Canvas, itemView: View, isCurrentlyActive: Boolean) {
        val signedTranslationX = itemView.translationX
        // A released select swipe hands that side to the selection gutter's check circles.
        if (!isCurrentlyActive && signedTranslationX > 0) return
        val translationX = abs(signedTranslationX)
        val newTime = clock.epochMillis()
        val dt = min(17, newTime - lastReplyButtonAnimationTime)
        lastReplyButtonAnimationTime = newTime
        val showing = translationX >= minShowDistance
        // Once released, keep the chosen action's icon while the item slides back past the thresholds.
        val icon = when {
            !isCurrentlyActive -> drawnIcon ?: if (signedTranslationX > 0) selectDrawable else replyDrawable
            signedTranslationX > 0 -> selectDrawable
            actionFor(signedTranslationX) == SwipeAction.EDIT -> editDrawable
            else -> replyDrawable
        }
        if (showing && drawnIcon != null && icon !== drawnIcon) {
            // Replay the pop-in so the switch to the other action is noticeable.
            replyButtonProgress = 0f
        }
        drawnIcon = icon
        if (showing) {
            if (replyButtonProgress < 1.0f) {
                replyButtonProgress += dt / 180.0f
                if (replyButtonProgress > 1.0f) {
                    replyButtonProgress = 1.0f
                } else {
                    itemView.invalidate()
                }
            }
        } else if (translationX <= 0.0f) {
            replyButtonProgress = 0f
            startTracking = false
            hapticAction = SwipeAction.NONE
        } else {
            if (replyButtonProgress > 0.0f) {
                replyButtonProgress -= dt / 180.0f
                if (replyButtonProgress < 0.1f) {
                    replyButtonProgress = 0f
                } else {
                    itemView.invalidate()
                }
            }
        }
        val alpha: Int
        val scale: Float
        if (showing) {
            scale = if (replyButtonProgress <= 0.8f) {
                1.2f * (replyButtonProgress / 0.8f)
            } else {
                1.2f - 0.2f * ((replyButtonProgress - 0.8f) / 0.2f)
            }
            alpha = min(255f, 255 * (replyButtonProgress / 0.8f)).toInt()
        } else {
            scale = replyButtonProgress
            alpha = min(255f, 255 * replyButtonProgress).toInt()
        }

        icon.alpha = alpha
        if (startTracking && isCurrentlyActive) {
            val action = actionFor(signedTranslationX)
            if (action != hapticAction) {
                if (action != SwipeAction.NONE) {
                    itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                hapticAction = action
            }
        }

        val x: Int = if (signedTranslationX > 0) (translationX / 2).toInt() else itemView.width - (translationX / 2).toInt()
        val y = (itemView.top + itemView.measuredHeight / 2).toFloat()
        val hw = icon.intrinsicWidth / 2f
        val hh = icon.intrinsicHeight / 2f
        icon.setBounds(
                (x - hw * scale).toInt(),
                (y - hh * scale).toInt(),
                (x + hw * scale).toInt(),
                (y + hh * scale).toInt()
        )
        icon.draw(canvas)
        icon.alpha = 255
    }

    // AppCompatResources, not ContextCompat: both icons are <vector>, which the framework can't inflate before API 21.
    private fun tintedIcon(@DrawableRes res: Int): Drawable =
            DrawableCompat.wrap(AppCompatResources.getDrawable(context, res)!!.mutate()).also {
                DrawableCompat.setTint(it, ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary))
            }

    private fun convertToPx(dp: Int): Float {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                context.resources.displayMetrics
        )
    }
}
