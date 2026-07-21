/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ReplacementTransformationMethod
import android.widget.EditText

// U+202A..U+202E (LRE, RLE, PDF, LRO, RLO): a hostile RLO/LRO in a name or message renders the rest
// of the line backwards, and embeddings reorder runs around neutrals (URL/domain spoofing). Show them
// as an unsupported-glyph box instead of letting them take effect. Direction marks (U+200E/U+200F)
// and isolates (U+2066..U+2069) stay: they can't reverse text.
private val DIRECTION_OVERRIDE_CHARS = '\u202A'..'\u202E'

// U+FDD0 is a Unicode noncharacter: permanently unassigned, no font maps it, so the device renders its
// native missing-glyph tofu. Display-only substitution like this is the sanctioned use of noncharacters,
// and U+FDD0..U+FDEF is carved out of the Arabic block's RTL bidi default (it is strong LTR).
private const val UNSUPPORTED_GLYPH = '\uFDD0'
private const val UNSUPPORTED_GLYPH_STR = "\uFDD0"

/** Render-side only: never feed the result back into stored, copied, or outgoing text. */
fun CharSequence.neutralizeDirectionOverrides(): CharSequence {
    if (none { it in DIRECTION_OVERRIDE_CHARS }) return this
    if (this is Spanned) {
        val result = SpannableStringBuilder(this)
        for (i in 0 until result.length) {
            if (result[i] in DIRECTION_OVERRIDE_CHARS) result.replace(i, i + 1, UNSUPPORTED_GLYPH_STR)
        }
        return result
    }
    return buildString(length) {
        for (c in this@neutralizeDirectionOverrides) append(if (c in DIRECTION_OVERRIDE_CHARS) UNSUPPORTED_GLYPH else c)
    }
}

fun String.neutralizeDirectionOverrides(): String = (this as CharSequence).neutralizeDirectionOverrides().toString()

/** Render-side neutralization for EditTexts: layout and drawing see the substituted characters (so
 *  overrides can't flip the field) while the Editable — and anything copied or sent from it — keeps
 *  the originals. */
object DirectionOverridesTransformation : ReplacementTransformationMethod() {
    private val ORIGINAL = charArrayOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E')
    private val REPLACEMENT = CharArray(ORIGINAL.size) { UNSUPPORTED_GLYPH }

    override fun getOriginal() = ORIGINAL

    override fun getReplacement() = REPLACEMENT
}

/** TextView's built-in copy/cut put the TRANSFORMED text on the clipboard, which would leak the
 *  substitute glyph instead of the real characters. EditTexts using [DirectionOverridesTransformation]
 *  call this from onTextContextMenuItem so the clipboard receives the raw Editable slice. */
fun EditText.copyRawSelection(menuId: Int): Boolean {
    if (menuId != android.R.id.copy && menuId != android.R.id.cut) return false
    if (transformationMethod !is DirectionOverridesTransformation) return false
    val editable = text ?: return false
    val min = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
    val max = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
    if (min >= max) return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(null, editable.subSequence(min, max)))
    if (menuId == android.R.id.cut) {
        editable.delete(min, max)
    } else {
        Selection.setSelection(editable, max)
    }
    return true
}
