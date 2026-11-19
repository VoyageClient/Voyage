/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import org.matrix.android.sdk.api.debug.DebugLog

/** KBDDBG: soft keyboard visibility, focus and show/hide requests, with the IME state at each step. */
object KeyboardDebug {

    val enabled: Boolean get() = DebugLog.enabled

    inline fun log(message: () -> String) {
        DebugLog.i { "KBDDBG ${message()}" }
    }

    fun state(view: View?): String {
        if (view == null) return "view=null"
        val imm = view.context.getSystemService<InputMethodManager>()
        val insets = ViewCompat.getRootWindowInsets(view)
        val activity = view.context.findActivity()
        return "ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())}/${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom} " +
                "view=${name(view)} focused=${view.isFocused} windowFocus=${view.hasWindowFocus()} attached=${ViewCompat.isAttachedToWindow(view)} " +
                "immActive=${imm?.isActive(view)} immAnyActive=${imm?.isActive} acceptingText=${imm?.isAcceptingText} " +
                "softInput=${activity?.window?.attributes?.softInputMode?.let(::softInputMode)} " +
                "currentFocus=${name(view.rootView?.findFocus())}"
    }

    fun name(view: View?): String {
        if (view == null) return "null"
        val id = if (view.id != View.NO_ID) runCatching { "#" + view.resources.getResourceEntryName(view.id) }.getOrDefault("") else ""
        return "${view.javaClass.simpleName}$id@${Integer.toHexString(System.identityHashCode(view))}"
    }

    fun softInputMode(mode: Int): String {
        val state = when (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED -> "unchanged"
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN -> "hidden"
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN -> "alwaysHidden"
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE -> "visible"
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE -> "alwaysVisible"
            else -> "unspecified"
        }
        @Suppress("DEPRECATION")
        val adjust = when (mode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE -> "resize"
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN -> "pan"
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING -> "nothing"
            else -> "unspecified"
        }
        return "$state|$adjust"
    }

    fun touchAction(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    fun scrollState(state: Int): String = when (state) {
        RecyclerView.SCROLL_STATE_IDLE -> "IDLE"
        RecyclerView.SCROLL_STATE_DRAGGING -> "DRAGGING"
        RecyclerView.SCROLL_STATE_SETTLING -> "SETTLING"
        else -> state.toString()
    }

    /** The frames above the logging call site, so a show/hide request names who asked for it. */
    fun caller(depth: Int = 6): String = Throwable().stackTrace
            .dropWhile { it.className.startsWith(KeyboardDebug::class.java.name) }
            .drop(1)
            .take(depth)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
