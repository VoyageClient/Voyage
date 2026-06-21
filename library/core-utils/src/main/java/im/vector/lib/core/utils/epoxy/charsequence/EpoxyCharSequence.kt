/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.epoxy.charsequence

import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan

/**
 * Wrapper for a CharSequence, which support mutation of the CharSequence, which can happen during rendering.
 */
class EpoxyCharSequence(val charSequence: CharSequence) {
    // Span attributes (e.g. theme-dependent text colors) must factor into equality, otherwise Epoxy
    // skips rebinding rows whose text is unchanged but whose colors changed on a live theme swap.
    private val hash = computeHash(charSequence)

    override fun hashCode() = hash
    override fun equals(other: Any?) = other is EpoxyCharSequence && other.hash == hash

    private companion object {
        fun computeHash(charSequence: CharSequence): Int {
            var result = charSequence.toString().hashCode()
            if (charSequence is Spanned) {
                val spans = charSequence.getSpans(0, charSequence.length, Any::class.java)
                for (span in spans) {
                    result = 31 * result + spanHash(charSequence, span)
                }
            }
            return result
        }

        fun spanHash(spanned: Spanned, span: Any): Int {
            var result = spanned.getSpanStart(span)
            result = 31 * result + spanned.getSpanEnd(span)
            result = 31 * result + spanned.getSpanFlags(span)
            // Span classes don't override equals/toString, so identity-based hashes would differ on every
            // bind. Read the theme-dependent color out of the spans that carry one so equality tracks value.
            result = 31 * result + span.javaClass.name.hashCode()
            result = 31 * result + when (span) {
                is ForegroundColorSpan -> span.foregroundColor
                is BackgroundColorSpan -> span.backgroundColor
                else -> 0
            }
            return result
        }
    }
}
