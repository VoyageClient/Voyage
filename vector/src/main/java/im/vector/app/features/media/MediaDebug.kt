/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.ImageViewTarget
import com.bumptech.glide.request.target.Target
import java.io.File

/** Formatting for the MEDIADBG traces; only ever called from inside a DebugLog lambda. */
object MediaDebug {

    private val SKIPPED_FRAMES = listOf(
            MediaDebug::class.java.name,
            ImageContentRenderer::class.java.name,
            "im.vector.app.core.ui.views.RoundedCornerImageView",
            "im.vector.app.core.glide.AnimatedContentImageViewTarget",
            "android.widget.ImageView",
            "androidx.appcompat.widget.AppCompatImageView",
            "java.",
            "dalvik.",
            "kotlin.",
    )

    fun id(any: Any?): String = if (any == null) "null" else Integer.toHexString(System.identityHashCode(any))

    fun drawable(drawable: Drawable?): String = when (drawable) {
        null -> "null"
        is MediaPlaceholderDrawable -> "Placeholder@${id(drawable)}(failed=${drawable.isFailed()})"
        else -> "${drawable.javaClass.simpleName}@${id(drawable)}(${drawable.intrinsicWidth}x${drawable.intrinsicHeight})"
    }

    fun request(view: View): String {
        val request = view.getTag(com.bumptech.glide.R.id.glide_custom_view_target_tag) as? Request ?: return "none"
        return "${request.javaClass.simpleName}@${id(request)}(running=${request.isRunning} complete=${request.isComplete} cleared=${request.isCleared})"
    }

    fun target(target: Target<*>?): String {
        val view: View = when (target) {
            is ImageViewTarget<*> -> target.view
            is CustomViewTarget<*, *> -> target.view
            else -> null
        } ?: return target?.javaClass?.simpleName ?: "null"
        return "${target?.javaClass?.simpleName}(view=${id(view)} shown=${(view as? ImageView)?.drawable?.let(::drawable)})"
    }

    fun model(model: Any?): String = when (model) {
        null -> "null"
        is String -> model
        is File -> "file:${model.path}(exists=${model.exists()} ${model.length()}b)"
        is ImageContentRenderer.Data -> "Data(url=${model.url} encrypted=${model.elementToDecrypt != null} allowNonMxc=${model.allowNonMxcUrls})"
        else -> "${model.javaClass.simpleName}:$model"
    }

    fun failure(e: Throwable?): String {
        e ?: return "null"
        val causes = (e as? GlideException)?.rootCauses ?: listOf(e)
        return causes.joinToString(prefix = "[", postfix = "]") { cause ->
            val status = (cause as? HttpException)?.statusCode?.let { " http=$it" }.orEmpty()
            "${cause.javaClass.simpleName}$status:${cause.message}"
        }
    }

    /** The nearest frames outside the rendering plumbing, to tell which view or code path asked. */
    fun callers(depth: Int = 1): String = Throwable().stackTrace
            .filter { frame -> SKIPPED_FRAMES.none { frame.className.startsWith(it) } }
            .take(depth)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .ifEmpty { "?" }
}
