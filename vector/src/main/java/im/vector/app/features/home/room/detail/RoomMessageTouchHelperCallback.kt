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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyTouchHelperCallback
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.features.settings.MessageSwipeBindings
import im.vector.app.features.settings.MessageSwipeSlot
import im.vector.lib.core.utils.timer.Clock
import timber.log.Timber
import java.util.WeakHashMap
import kotlin.math.abs
import im.vector.app.features.settings.MessageSwipeAction as SwipeAction

class RoomMessageTouchHelperCallback(
        context: Context,
        private val handler: Handler,
        private val clock: Clock,
        private val readBindings: () -> MessageSwipeBindings,
) : EpoxyTouchHelperCallback() {

    interface Handler {
        fun performAction(model: EpoxyModel<*>, action: SwipeAction)
        fun availableActions(model: EpoxyModel<*>, candidates: Set<SwipeAction>): Set<SwipeAction>
        fun onSwipeStarted()
    }

    private class SwipeState(
            val model: EpoxyModel<*>,
            val bindings: MessageSwipeBindings,
            val availableActions: Set<SwipeAction>,
    ) {
        var distance = 0f
        var lastTouchX: Float? = null
        var originX: Float? = null
        var action = SwipeAction.NONE
        var releasedAction = SwipeAction.NONE
        var drawnAction = SwipeAction.NONE
        var iconProgress = 0f
        var lastFrameTime = 0L
        var cancelled = false

        fun resolve(distanceDp: Float) = bindings.resolve(distanceDp) { it in availableActions }
    }

    private val swipes = WeakHashMap<View, SwipeState>()
    private var activeHolder: EpoxyViewHolder? = null
    private val icons = SwipeAction.values().associateWith { it.drawable(context) }
    private val density = context.resources.displayMetrics.density
    private val minShowDistance = 20 * density
    private val iconSize = 24 * density
    private val maxTranslation = (MessageSwipeBindings.FARTHER_DISTANCE_DP + 20) * density

    @SuppressLint("ClickableViewAccessibility")
    fun attachToRecyclerView(recyclerView: RecyclerView) {
        recyclerView.setOnTouchListener { _, event ->
            val state = activeHolder?.itemView?.let { swipes[it] }
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> state?.lastTouchX = event.x
                MotionEvent.ACTION_POINTER_DOWN -> state?.cancelled = true
                MotionEvent.ACTION_UP -> finishSwipe(event.x)
                MotionEvent.ACTION_CANCEL -> {
                    state?.cancelled = true
                    activeHolder = null
                }
            }
            false
        }
        ItemTouchHelper(this).attachToRecyclerView(recyclerView)
    }

    override fun onSelectedChanged(viewHolder: EpoxyViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        activeHolder = null
        if (actionState != ACTION_STATE_SWIPE || viewHolder == null) return
        val model = modelOf(viewHolder) ?: return
        swipes[viewHolder.itemView] = newSwipe(model)
        activeHolder = viewHolder
        handler.onSwipeStarted()
    }

    private fun modelOf(holder: EpoxyViewHolder): EpoxyModel<*>? = try {
        holder.model
    } catch (failure: IllegalStateException) {
        Timber.e(failure)
        null
    }

    private fun newSwipe(model: EpoxyModel<*>): SwipeState {
        val bindings = readBindings()
        return SwipeState(model, bindings, availableActionsFor(model, bindings))
    }

    private fun availableActionsFor(model: EpoxyModel<*>, bindings: MessageSwipeBindings): Set<SwipeAction> {
        val candidates = MessageSwipeSlot.values().filter { bindings.isEnabled(it) }.map { bindings[it] }
                .filter { it != SwipeAction.NONE }.toSet()
        return if (candidates.isEmpty()) emptySet() else handler.availableActions(model, candidates)
    }

    override fun onSwiped(viewHolder: EpoxyViewHolder, direction: Int) = Unit

    override fun onMove(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder, target: EpoxyViewHolder) = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: EpoxyViewHolder): Int {
        val model = modelOf(viewHolder) ?: return 0
        val bindings = readBindings()
        val availableActions = availableActionsFor(model, bindings)
        var flags = 0
        if (listOf(MessageSwipeSlot.LEFT, MessageSwipeSlot.LEFT_FAR).any { bindings.isEnabled(it) && bindings[it] in availableActions }) {
            flags = flags or ItemTouchHelper.LEFT
        }
        if (listOf(MessageSwipeSlot.RIGHT, MessageSwipeSlot.RIGHT_FAR).any { bindings.isEnabled(it) && bindings[it] in availableActions }) {
            flags = flags or ItemTouchHelper.RIGHT
        }
        return ItemTouchHelper.Callback.makeMovementFlags(0, flags)
    }

    // Items must animate back rather than being dismissed by ItemTouchHelper.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = Float.MAX_VALUE

    override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        swipes.remove(viewHolder.itemView)
        if (activeHolder === viewHolder) activeHolder = null
    }

    override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: EpoxyViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
    ) {
        val view = viewHolder.itemView
        val state = swipes[view]
        if (actionState != ACTION_STATE_SWIPE || state == null) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            return
        }
        if (isCurrentlyActive) {
            if (modelOf(viewHolder)?.id() != state.model.id()) state.cancelled = true
            state.distance = dX
            if (state.originX == null) state.lastTouchX?.let { state.originX = it - dX }
            val action = if (state.cancelled) SwipeAction.NONE else state.resolve(dX / density)
            if (action != state.action && action != SwipeAction.NONE) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            state.action = action
        }
        val drawDx = if (isCurrentlyActive) {
            dX.coerceIn(-maxTranslation, maxTranslation)
        } else {
            // ItemTouchHelper animates its unclamped distance; preserve the position shown at release.
            val overshoot = abs(state.distance)
            if (overshoot > maxTranslation) dX * (maxTranslation / overshoot) else dX
        }
        super.onChildDraw(c, recyclerView, viewHolder, drawDx, dY, actionState, isCurrentlyActive)
        drawSwipeIcon(c, view, state, isCurrentlyActive)
    }

    private fun finishSwipe(releaseX: Float) {
        val holder = activeHolder ?: return
        activeHolder = null
        val state = swipes[holder.itemView] ?: return
        if (state.cancelled || modelOf(holder)?.id() != state.model.id()) return
        // A release event can arrive before the next draw updates the armed action.
        val distance = releaseX - (state.originX ?: return)
        val available = availableActionsFor(state.model, state.bindings)
        val action = state.bindings.resolve(distance / density) { it in available }
        state.releasedAction = action
        state.drawnAction = action
        if (action != SwipeAction.NONE) handler.performAction(state.model, action)
    }

    private fun drawSwipeIcon(canvas: Canvas, view: View, state: SwipeState, active: Boolean) {
        // Selection replaces the released swipe icon with its check circles.
        if (!active && state.releasedAction == SwipeAction.SELECT) return
        val action = if (active) {
            if (state.cancelled) SwipeAction.NONE else state.bindings.hint(state.distance / density) { it in state.availableActions }
        } else state.drawnAction
        if (action == SwipeAction.NONE) {
            state.drawnAction = SwipeAction.NONE
            state.iconProgress = 0f
            return
        }
        val now = clock.epochMillis()
        val dt = (now - state.lastFrameTime).coerceIn(0L, 17L)
        state.lastFrameTime = now
        val distance = abs(view.translationX)
        val showing = distance >= minShowDistance
        if (showing && state.drawnAction != SwipeAction.NONE && state.drawnAction != action) state.iconProgress = 0f
        state.drawnAction = action
        state.iconProgress = if (showing) {
            (state.iconProgress + dt / 180f).coerceAtMost(1f)
        } else {
            (state.iconProgress - dt / 180f).coerceAtLeast(0f)
        }
        if (state.iconProgress > 0f && state.iconProgress < 1f) view.invalidate()
        val progress = state.iconProgress
        val scale = when {
            !showing -> progress
            progress <= .8f -> 1.2f * progress / .8f
            else -> 1.2f - .2f * (progress - .8f) / .2f
        }
        val icon = icons.getValue(action)
        icon.alpha = (255 * if (showing) (progress / .8f).coerceAtMost(1f) else progress).toInt()
        val x = if (view.translationX > 0) distance / 2 else view.width - distance / 2
        val y = view.top + view.measuredHeight / 2f
        val halfSize = iconSize * scale / 2
        icon.setBounds((x - halfSize).toInt(), (y - halfSize).toInt(), (x + halfSize).toInt(), (y + halfSize).toInt())
        icon.draw(canvas)
        icon.alpha = 255
    }
}
