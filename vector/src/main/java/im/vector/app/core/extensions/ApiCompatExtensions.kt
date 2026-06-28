/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import java.util.Locale

// Layout/View APIs added after API 14 (our floor), wrapped via androidx *Compat shims (or a guarded
// fallback) so the same call sites keep working on ICS. Property forms let call sites be swapped
// mechanically (e.g. `view.background = d` -> `view.backgroundCompat = d`).

// RelativeLayout.LayoutParams.removeRule is API 17+; pre-17 the same effect is addRule(verb, 0).
fun RelativeLayout.LayoutParams.removeRuleCompat(verb: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        removeRule(verb)
    } else {
        @Suppress("DEPRECATION")
        addRule(verb, 0)
    }
}

// View.setPaddingRelative is API 16+/17+; ViewCompat backports it.
fun View.setPaddingRelativeCompat(start: Int, top: Int, end: Int, bottom: Int) =
        ViewCompat.setPaddingRelative(this, start, top, end, bottom)

// View.setBackground is API 16+.
var View.backgroundCompat: Drawable?
    get() = background
    set(value) {
        ViewCompat.setBackground(this, value)
    }

// View.set/getLayoutDirection is API 17+.
var View.layoutDirectionCompat: Int
    get() = ViewCompat.getLayoutDirection(this)
    set(value) {
        ViewCompat.setLayoutDirection(this, value)
    }

// View.isAttachedToWindow is API 19+.
val View.isAttachedToWindowCompat: Boolean
    get() = ViewCompat.isAttachedToWindow(this)

// View.set/getImportantForAccessibility is API 16+.
var View.importantForAccessibilityCompat: Int
    get() = ViewCompat.getImportantForAccessibility(this)
    set(value) {
        ViewCompat.setImportantForAccessibility(this, value)
    }

// MarginLayoutParams.set/getMarginStart/End is API 17+.
var ViewGroup.MarginLayoutParams.marginStartCompat: Int
    get() = MarginLayoutParamsCompat.getMarginStart(this)
    set(value) {
        MarginLayoutParamsCompat.setMarginStart(this, value)
    }

var ViewGroup.MarginLayoutParams.marginEndCompat: Int
    get() = MarginLayoutParamsCompat.getMarginEnd(this)
    set(value) {
        MarginLayoutParamsCompat.setMarginEnd(this, value)
    }

// View.textAlignment is API 17+; pre-17 alignment is gravity-based so set is a no-op and get is INHERIT.
var View.textAlignmentCompat: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) textAlignment else View.TEXT_ALIGNMENT_INHERIT
    set(value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) textAlignment = value
    }

// TextView.setCompoundDrawablesRelativeWithIntrinsicBounds is API 17+; TextViewCompat backports it.
fun TextView.setCompoundDrawablesRelativeWithIntrinsicBoundsCompat(start: Int, top: Int, end: Int, bottom: Int) =
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(this, start, top, end, bottom)

// DateFormat.getBestDateTimePattern is API 18+; pre-18 fall back to the skeleton itself.
fun getBestDateTimePatternCompat(locale: Locale, skeleton: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            DateFormat.getBestDateTimePattern(locale, skeleton)
        } else {
            skeleton
        }
