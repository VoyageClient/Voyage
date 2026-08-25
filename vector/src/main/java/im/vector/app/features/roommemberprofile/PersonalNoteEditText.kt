/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatEditText

/** An EditText that also reports the IME being dismissed with the back key, so the note can leave edit mode. */
class PersonalNoteEditText @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    var onImeBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onImeBack?.invoke()
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
