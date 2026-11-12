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
import im.vector.app.R
import im.vector.app.features.media.MediaDebug
import org.matrix.android.sdk.api.debug.DebugLog

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
    private fun trace(event: String) {
        if (DebugLog.enabled && view.getTag(R.id.image_renderer_render_token) != null) {
            DebugLog.i { "MEDIADBG target ${MediaDebug.id(this)} view=${MediaDebug.id(view)} $event request=${MediaDebug.request(view)}" }
        }
    }

    override fun onLoadStarted(placeholder: Drawable?) {
        trace("onLoadStarted placeholder=${MediaDebug.drawable(placeholder)}")
        super.onLoadStarted(placeholder)
    }

    override fun onLoadFailed(errorDrawable: Drawable?) {
        trace("onLoadFailed error=${MediaDebug.drawable(errorDrawable)}")
        super.onLoadFailed(errorDrawable)
    }

    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
        trace("onResourceReady resource=${MediaDebug.drawable(resource)} transition=${transition?.javaClass?.simpleName}")
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
        trace("onLoadCleared placeholder=${MediaDebug.drawable(placeholder)}")
        releaseResource()
        super.onLoadCleared(placeholder)
    }

    override fun onStart() {
        if (animate) super.onStart()
    }
}
