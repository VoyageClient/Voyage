/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import androidx.lifecycle.ViewModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import java.io.File

/**
 * Holds the in-progress edit state so it survives configuration changes (rotation). The fragment reads/writes
 * these directly; [loaded] gates the one-time load from the repository so edits aren't reverted on recreation.
 */
class ImagePackEditViewModel : ViewModel() {
    val images = mutableListOf<EditableImage>()
    var packName: String? = null
    var packAvatarUrl: String? = null

    // A picked or imported avatar not uploaded yet; wins over [packAvatarUrl].
    var packAvatarDraft: DraftImage? = null
    var packExists = false
    var packUsage: List<String>? = null
    var forceLegacy = false
    var initialContent: ImagePackContent? = null
    var telegramSet: String? = null
    var telegramStickers: Map<String, String> = emptyMap()
    var loaded = false

    // Local copies of everything added in this editor, deleted once it closes.
    val draftDirs = mutableListOf<File>()

    // Where picked images and avatars are copied.
    var localDir: File? = null

    override fun onCleared() {
        draftDirs.forEach { runCatching { it.deleteRecursively() } }
        super.onCleared()
    }
}
