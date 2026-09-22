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

/**
 * @param placeholder the request's own placeholder, used as the outgoing layer when the view has
 * nothing on it: a memory hit completes inside Glide's begin(), which never puts the placeholder up.
 */
class BlurFadeOutTransitionFactory(
        private val durationMs: Int,
        private val placeholder: Drawable? = null,
) : TransitionFactory<Drawable> {

    override fun build(dataSource: DataSource, isFirstResource: Boolean): Transition<Drawable> =
            Transition { current, adapter ->
                val shown = adapter.currentDrawable
                val previous = (if (shown == null || shown === current) placeholder else shown)?.takeIf { it !== current }
                DebugLog.i { "MEDIADBG fade from=$dataSource first=$isFirstResource shown=${shown?.javaClass?.simpleName}" +
                                " previous=${previous?.javaClass?.simpleName} current=${current.javaClass.simpleName} ms=$durationMs" }
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
