/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import im.vector.app.features.attachments.editor.image.ImageEditorExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GradientImageSamplerViewModel : ViewModel() {

    sealed interface State {
        object Idle : State
        object Loading : State
        data class Loaded(val bitmap: Bitmap) : State
        object Failed : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    val loadFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    var points: FloatArray? = null
    var selectedPoint = -1

    fun load(context: Context, source: Uri) {
        val appContext = context.applicationContext
        state.value = State.Loading
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { ImageEditorExporter.loadForDisplay(appContext, source) }.getOrNull()
            }
            if (bitmap == null) {
                loadFailures.tryEmit(Unit)
                state.value = State.Failed
            } else {
                state.value = State.Loaded(bitmap)
            }
        }
    }
}
