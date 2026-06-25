/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import androidx.lifecycle.ViewModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent

/**
 * Holds the in-progress edit state so it survives configuration changes (rotation). The fragment reads/writes
 * these directly; [loaded] gates the one-time load from the repository so edits aren't reverted on recreation.
 */
class ImagePackEditViewModel : ViewModel() {
    val images = mutableListOf<EditableImage>()
    var packName: String? = null
    var packAvatarUrl: String? = null
    var packExists = false
    var packUsage: List<String>? = null
    var initialContent: ImagePackContent? = null
    var loaded = false
}
