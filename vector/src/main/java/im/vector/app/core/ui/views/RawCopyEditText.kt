/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.textfield.TextInputEditText
import im.vector.lib.core.utils.text.copyRawSelection

// TextView's copy/cut operate on the transformed text; route them through the raw Editable so
// DirectionOverridesTransformation stays purely visual (see copyRawSelection).

class RawCopyEditText @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {

    override fun onTextContextMenuItem(id: Int) = copyRawSelection(id) || super.onTextContextMenuItem(id)
}

class RawCopyTextInputEditText @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : TextInputEditText(context, attrs) {

    override fun onTextContextMenuItem(id: Int) = copyRawSelection(id) || super.onTextContextMenuItem(id)
}
