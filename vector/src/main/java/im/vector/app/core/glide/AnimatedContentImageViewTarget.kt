/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Stopping the drawable at the view holds animated content on its first frame whatever decoded it,
 * unlike `dontAnimate()`, which only reaches Glide's own GIF decoder.
 */
open class AnimatedContentImageViewTarget(
        view: ImageView,
        private val animate: Boolean,
) : DrawableImageViewTarget(view) {

    private var resource: Drawable? = null
    private val attachmentListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (animate) (resource as? Animatable)?.start()
        }

        override fun onViewDetachedFromWindow(v: View) {
            (resource as? Animatable)?.stop()
        }
    }

    // Glide starts any Animatable resource itself with no hook to opt out, so stop it right after.
    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
        this.resource = resource
        view.removeOnAttachStateChangeListener(attachmentListener)
        view.addOnAttachStateChangeListener(attachmentListener)
        super.onResourceReady(resource, transition)
        if (!animate) (resource as? Animatable)?.stop()
    }

    open fun releaseResource() {
        onStop()
        view.removeOnAttachStateChangeListener(attachmentListener)
        resource = null
    }

    override fun onLoadCleared(placeholder: Drawable?) {
        releaseResource()
        super.onLoadCleared(placeholder)
    }

    override fun onStart() {
        if (animate) super.onStart()
    }
}
