/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.widget.ImageView
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.highlight.CodePalette
import im.vector.app.features.media.ImageContentRenderer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asContextElement
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.util.MatrixItem

interface StaticTimelineHost {
    val lightTheme: Boolean
    val messageTextColor: Int
    val uncoloredNames: Boolean
    val codePalette: CodePalette? get() = null
    fun resolveColor(attribute: Int): Int
    fun identityColor(item: MatrixItem): Int?
    fun hasImage(url: String): Boolean
    fun resolveMedia(data: ImageContentRenderer.Data, view: ImageView): ImageContentRenderer.Data
    fun mediaStarted(view: ImageView, completion: Deferred<Unit>)
    fun renderText(text: CharSequence): CharSequence
    fun renderEmote(shortcode: String, source: String, body: String?): EmoteImageSpan
    fun resolvePill(link: PermalinkData, label: String?): MatrixItem?
    fun pillAvatar(item: MatrixItem): Drawable
    fun bindPill(span: PillImageSpan, item: MatrixItem)
    fun renderAvatar(item: MatrixItem, view: ImageView)
    fun renderImage(url: String?, view: ImageView, hidden: Boolean = false, eventId: String? = null)
}

fun Context.staticTimelineHost(): StaticTimelineHost? {
    var current: Context = this
    while (true) {
        if (current is StaticTimelineHost) return current
        val base = (current as? ContextWrapper)?.baseContext ?: return null
        if (base === current) return null
        current = base
    }
}

// Injected timeline renderers can resolve colors through the application context.
object TimelineRenderingScope {
    private val host = ThreadLocal<StaticTimelineHost?>()
    private val manualDrawing = ThreadLocal<Boolean>()
    fun isManualDrawing(): Boolean = manualDrawing.get() == true
    fun <T> draw(value: StaticTimelineHost, block: () -> T): T = render(value) {
        val previous = manualDrawing.get()
        manualDrawing.set(true)
        try { block() } finally { manualDrawing.set(previous) }
    }
    fun current(): StaticTimelineHost? = host.get()
    fun element(value: StaticTimelineHost) = host.asContextElement(value)
    fun <T> render(value: StaticTimelineHost, block: () -> T): T {
        val previous = host.get()
        host.set(value)
        return try { block() } finally { host.set(previous) }
    }
}
