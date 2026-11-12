/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.matrix.android.sdk.api.extensions.tryOrNull

/** Formatting for the SYSBARDBG traces; only ever called from inside a DebugLog lambda. */
internal object SystemBarDebug {

    fun id(any: Any?): String = if (any == null) "null" else Integer.toHexString(System.identityHashCode(any))

    fun color(color: Int?): String = if (color == null) "null" else "#" + Integer.toHexString(color)

    fun insets(insets: WindowInsetsCompat): String {
        fun Insets.text() = "$left,$top,$right,$bottom"
        val ime = WindowInsetsCompat.Type.ime()
        return "status=${insets.getInsets(WindowInsetsCompat.Type.statusBars()).text()} " +
                "nav=${insets.getInsets(WindowInsetsCompat.Type.navigationBars()).text()} " +
                "cutout=${insets.getInsets(WindowInsetsCompat.Type.displayCutout()).text()} " +
                "ime=${insets.getInsets(ime).text()}(visible=${insets.isVisible(ime)}) " +
                "statusVisible=${insets.isVisible(WindowInsetsCompat.Type.statusBars())}"
    }

    fun drawable(drawable: Drawable?): String = when (drawable) {
        null -> "null"
        is ColorDrawable -> "Color@${id(drawable)}(${color(drawable.color)} a=${drawable.alpha} ${drawable.bounds.toShortString()})"
        else -> "${drawable.javaClass.simpleName}@${id(drawable)}(${drawable.bounds.toShortString()})"
    }

    fun view(view: View?): String {
        view ?: return "null"
        val location = IntArray(2).also { view.getLocationInWindow(it) }
        val name = if (view.id == View.NO_ID) "" else tryOrNull { view.resources.getResourceEntryName(view.id) } ?: view.id.toString()
        return "${view.javaClass.simpleName}#$name(y=${location[1]} ${view.width}x${view.height} shown=${view.isShown} " +
                "alpha=${view.alpha} ty=${view.translationY} sy=${view.scaleY} bg=${drawable(view.background)})"
    }

    /** The window's own bar colors and the decor views that paint them, which a scene transition can leave altered. */
    @Suppress("DEPRECATION")
    fun windowBars(window: Window): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return "windowBars=n/a"
        val decor = window.decorView as? ViewGroup
        return "statusColor=${color(window.statusBarColor)} navColor=${color(window.navigationBarColor)} " +
                "statusBg=${view(decor?.directChild(android.R.id.statusBarBackground))} " +
                "navBg=${view(decor?.directChild(android.R.id.navigationBarBackground))}"
    }

    // findViewById would walk the whole hierarchy every frame on windows without these views
    private fun ViewGroup.directChild(id: Int): View? = (0 until childCount).map(::getChildAt).firstOrNull { it.id == id }
}
