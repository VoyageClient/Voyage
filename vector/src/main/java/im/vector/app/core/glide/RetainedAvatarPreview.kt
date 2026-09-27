/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class RetainedAvatarPreview(private val view: ImageView) {
    private var current: PreviewTarget? = null
    private var pending: PreviewTarget? = null

    fun load(key: Any, delegate: AnimatedContentImageViewTarget, showFallback: Boolean = false, start: (CustomTarget<Drawable>) -> Unit) {
        if (pending?.key == key || (pending == null && current?.key == key)) return
        pending?.let { GlideApp.with(view.context.applicationContext).clear(it) }
        val size = (view.layoutParams?.width ?: 0).takeIf { it > 0 } ?: 128
        val target = PreviewTarget(key, delegate, size, showFallback)
        pending = target
        start(target)
    }

    fun clear() {
        pending?.let { GlideApp.with(view.context.applicationContext).clear(it) }
        current?.let { GlideApp.with(view.context.applicationContext).clear(it) }
        pending = null
        current = null
        view.setImageDrawable(null)
    }

    private inner class PreviewTarget(
            val key: Any,
            val delegate: AnimatedContentImageViewTarget,
            size: Int,
            val showFallback: Boolean,
    ) : CustomTarget<Drawable>(size, size) {
        override fun onLoadStarted(placeholder: Drawable?) {
            if (current == null && pending === this) delegate.onLoadStarted(placeholder)
        }

        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
            if (pending !== this) return
            val previous = current
            // The displayed request keeps ownership of its drawable until the replacement is ready.
            previous?.delegate?.releaseResource()
            current = this
            pending = null
            delegate.onResourceReady(resource, null)
            previous?.let { GlideApp.with(view.context.applicationContext).clear(it) }
        }

        override fun onLoadFailed(errorDrawable: Drawable?) {
            if (pending !== this) return
            pending = null
            if (current == null || showFallback) {
                val previous = current
                previous?.delegate?.releaseResource()
                current = this
                delegate.onLoadFailed(errorDrawable)
                previous?.let { GlideApp.with(view.context.applicationContext).clear(it) }
            } else {
                pending = this
            }
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            if (pending === this) pending = null
            if (current === this) {
                current = null
                delegate.onLoadCleared(placeholder)
            } else {
                delegate.releaseResource()
            }
        }

        override fun onStart() {
            if (current === this) delegate.onStart()
        }

        override fun onStop() {
            if (current === this) delegate.onStop()
        }
    }
}
