/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.view.MenuItem

/** A statically tinted toolbar icon does not grey out on its own when disabled. */
fun MenuItem.setEnabledDimmed(enabled: Boolean) {
    isEnabled = enabled
    icon?.mutate()?.alpha = if (enabled) 0xFF else DISABLED_ICON_ALPHA
}

private const val DISABLED_ICON_ALPHA = 0x60
