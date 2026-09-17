/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.request.transition.TransitionFactory
import org.matrix.android.sdk.api.debug.DebugLog

class BlurFadeOutTransitionFactory(private val durationMs: Int) : TransitionFactory<Drawable> {

    override fun build(dataSource: DataSource, isFirstResource: Boolean): Transition<Drawable> =
            Transition { current, adapter ->
                val previous = adapter.currentDrawable
                DebugLog.i { "MEDIADBG fade from=$dataSource first=$isFirstResource previous=${previous?.javaClass?.simpleName}" +
                                " current=${current.javaClass.simpleName} ms=$durationMs" }
                if (previous == null) {
                    adapter.setDrawable(current)
                } else {
                    adapter.setDrawable(
                            TransitionDrawable(arrayOf(previous, current)).apply {
                                isCrossFadeEnabled = true
                                startTransition(durationMs)
                            }
                    )
                }
                true
            }
}
