/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.LayoutInflaterCompat
import androidx.preference.PreferenceManager
import com.google.android.material.theme.MaterialComponentsViewInflater
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.avatar.DefaultAvatarStyle
import im.vector.app.features.home.avatar.effect.AvatarEffect
import im.vector.app.features.home.room.detail.timeline.render.StaticTimelineHost
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.highlight.CodePalette
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaPlaceholderDrawable
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.BubbleThemeUtils
import im.vector.app.features.themes.ThemeUtils
import kotlinx.coroutines.Deferred
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import java.util.WeakHashMap

class TimelineExportContext(
        base: Context,
        val options: TimelineExportOptions,
        private val people: Map<String, ExportPerson>,
        private val events: Map<String, ExportEvent>,
        private val bitmap: (String?) -> Bitmap?,
        private val avatars: AvatarRenderer,
        private val directory: java.io.File,
        private val assetPaths: Set<String> = emptySet(),

) : ContextThemeWrapper(base, ExportTheme.style(options.theme, options.accentName)), StaticTimelineHost {
    @Suppress("DEPRECATION")
    private val scopedResources: Resources = run {
        val config = Configuration(base.resources.configuration).apply { screenWidthDp = options.layoutWidth; fontScale = options.fontScale }
        // Configuration contexts retain the inflater's class loader; ICS needs the Resources fallback.
        if (Build.VERSION.SDK_INT >= 17) base.createConfigurationContext(config).resources else {
            Resources(base.assets, DisplayMetrics().apply {
                setTo(base.resources.displayMetrics)
                widthPixels = (options.layoutWidth * density).toInt()
            }, config)
        }
    }

    override fun getResources(): Resources = scopedResources

    // ContextThemeWrapper copies the base context's theme first; previews, the worker and the HTML exporter
    // have different bases, and attributes the export theme leaves unset would leak through.
    override fun onApplyThemeResource(theme: Resources.Theme, resid: Int, first: Boolean) {
        if (first) theme.setTo(scopedResources.newTheme())
        super.onApplyThemeResource(theme, resid, first)
    }
    override val lightTheme get() = ExportTheme.light(options.theme)
    override val codePalette get() = CodePalette.of(options.codePalette)
    override val uncoloredNames: Boolean get() {
        val palette = options.peoplePalette.takeUnless { it == "CURRENT" }
                ?: PreferenceManager.getDefaultSharedPreferences(baseContext)
                        .getString(VectorPreferences.SETTINGS_PEOPLE_COLOR_PALETTE_KEY, null)
        return palette == "NONE"
    }
    override val messageTextColor get() = resolveColor(ThemeUtils.messageTextAttr(uncoloredNames))

    private var exportInflater: LayoutInflater? = null
    override fun getSystemService(name: String): Any? {
        if (name != Context.LAYOUT_INFLATER_SERVICE) return super.getSystemService(name)
        exportInflater?.let { return it }
        return (super.getSystemService(name) as LayoutInflater).cloneInContext(this).also { inflater ->
            if (inflater.factory2 == null) LayoutInflaterCompat.setFactory2(inflater, StaticViewInflater())
            exportInflater = inflater
        }
    }

    private class StaticViewInflater : MaterialComponentsViewInflater(), LayoutInflater.Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? = onCreateView(name, context, attrs)
        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = when (name) {
            "TextView" -> createTextView(context, attrs)
            "ImageView" -> createImageView(context, attrs)
            "ImageButton" -> createImageButton(context, attrs)
            "Button" -> createButton(context, attrs)
            "EditText" -> createEditText(context, attrs)
            "CheckBox" -> createCheckBox(context, attrs)
            "RadioButton" -> createRadioButton(context, attrs)
            "SeekBar" -> createSeekBar(context, attrs)
            "Spinner" -> createSpinner(context, attrs)
            else -> null
        }
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val source = super.getSharedPreferences(name, mode)
        return object : SharedPreferences by source {
            override fun getString(key: String?, default: String?): String? = when (key) {
                BubbleThemeUtils.BUBBLE_STYLE_KEY -> options.bubbleStyle()
                BubbleThemeUtils.BUBBLE_ROUNDNESS_KEY -> options.bubbleCorners
                else -> source.getString(key, default)
            }

            override fun getBoolean(key: String?, default: Boolean): Boolean = when (key) {
                BubbleThemeUtils.BUBBLE_TAIL_KEY -> options.bubbleTail
                BubbleThemeUtils.BUBBLE_TINT_KEY -> options.bubbleTint
                else -> source.getBoolean(key, default)
            }
        }
    }

    override fun identityColor(item: MatrixItem): Int? = people[item.id]?.color

    override fun resolveColor(attribute: Int): Int {
        val custom = when (attribute) {
            // The header background stays the theme's: a jumped-to row's highlight is drawn in it, over the page.
            android.R.attr.colorBackground, im.vector.lib.ui.styles.R.attr.vctr_toolbar_background -> options.background
            im.vector.lib.ui.styles.R.attr.vctr_content_primary -> options.foreground
            im.vector.lib.ui.styles.R.attr.sc_message_bg_incoming -> options.incoming
            im.vector.lib.ui.styles.R.attr.sc_message_bg_outgoing -> options.outgoing
            com.google.android.material.R.attr.colorAccent, com.google.android.material.R.attr.colorPrimary -> options.accent
            else -> null
        }
        if (custom != null) return Color.parseColor(TimelineExportHtml.color(custom, "#eeeeee"))
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        @Suppress("DEPRECATION")
        return if (value.resourceId != 0) resources.getColor(value.resourceId) else value.data
    }

    var onResourceChanged: (() -> Unit)? = null
    private val mediaCompletions = WeakHashMap<ImageView, Deferred<Unit>>()
    private val mediaFrames = WeakHashMap<ImageView, String>()
    override fun resolveMedia(data: ImageContentRenderer.Data, view: ImageView): ImageContentRenderer.Data {
        val event = events[data.eventId]
        val path = data.url?.let { assetsByUrl[it] ?: it }?.takeIf { it.startsWith("assets/") }
                ?: data.galleryIndex?.let { event?.extraAttachments?.getOrNull(it)?.path } ?: event?.attachment ?: event?.mapImage
        if (path != null) mediaFrames[view] = path
        val file = java.io.File(directory, path?.takeIf { it.startsWith("assets/") && !it.contains("..") } ?: "assets/unavailable.bin")
        return data.copy(preservedFile = file, elementToDecrypt = null)
    }

    override fun mediaStarted(view: ImageView, completion: Deferred<Unit>) {
        mediaCompletions[view] = completion
        completion.invokeOnCompletion {
            Handler(Looper.getMainLooper()).post {
                Timber.d("ExportTrace native media completed")
                onResourceChanged?.invoke()
            }
        }
    }

    suspend fun awaitMedia(views: List<View>) {
        fun belongsTo(view: View): Boolean {
            var current: ViewParent? = view.parent
            while (current is View) {
                if (current in views) return true
                current = (current as View).parent
            }
            return view in views
        }
        val waiting = mediaCompletions.entries.filter { belongsTo(it.key) }.map { it.value }
        val startedAt = SystemClock.elapsedRealtime()
        val completed = kotlinx.coroutines.withTimeoutOrNull(30_000) { waiting.forEach { it.join() }; true } ?: false
        Timber.d("ExportTrace native media awaited count=%d cancelled=%d completed=%s elapsedMs=%d", waiting.size, waiting.count { it.isCancelled }, completed, SystemClock.elapsedRealtime() - startedAt)
    }

    private val assetsByUrl = events.values.flatMap { event -> event.reactions.flatMap { it.images.entries } }.associate { it.toPair() }
    private val eventImages = events.values.flatMapTo(HashSet()) { listOfNotNull(it.attachment, it.mapImage) }
    override fun hasImage(url: String): Boolean = url in assetPaths || assetsByUrl.containsKey(url) || url in eventImages
    override fun renderEmote(shortcode: String, source: String, body: String?) = EmoteImageSpan(shortcode, source, body, null) {
        bitmap(assetsByUrl[source] ?: source)
    }
    override fun renderText(text: CharSequence): CharSequence = SpannableStringBuilder(text).apply {
        getSpans(0, length, EmoteImageSpan::class.java).forEach { span ->
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            removeSpan(span)
            setSpan(renderEmote(span.shortcode, span.mxcUrl, span.body), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }.let { it.prepareForDisplay() }

    var animationTimeMs: Int = 0
    private val renderedAvatars = WeakHashMap<ImageView, Triple<MatrixItem, Bitmap?, Int>>()
    private val avatarTargets = WeakHashMap<ImageView, MatrixItem>()
    private val imageTargets = WeakHashMap<ImageView, Triple<String?, Boolean, String?>>()
    fun imagePath(view: ImageView): String? {
        avatarTargets[view]?.let { item -> return people[item.id]?.avatar ?: item.avatarUrl }
        mediaFrames[view]?.let { return it }
        return imageTargets[view]?.takeUnless { it.second }?.first?.let { assetsByUrl[it] ?: it }
    }
    fun isAvatar(view: ImageView) = avatarTargets.containsKey(view)
    fun forgetViews(root: View) {
        if (root is TextView) (root.text as? Spanned)?.let { text ->
            text.getSpans(0, text.length, PillImageSpan::class.java).forEach { pillTargets.remove(it); pillBitmaps.remove(it) }
        }
        if (root is ImageView) {
            renderedAvatars.remove(root); avatarTargets.remove(root); imageTargets.remove(root); mediaFrames.remove(root)
            mediaCompletions.remove(root)?.cancel()
        }
        if (root is ViewGroup) for (index in 0 until root.childCount) forgetViews(root.getChildAt(index))
    }
    fun refreshImages() {
        pillTargets.entries.toList().forEach { bindPill(it.key, it.value) }
        mediaFrames.entries.toList().forEach { (view, path) -> bitmap(path)?.let { image ->
            if ((view.drawable as? BitmapDrawable)?.bitmap !== image) view.setImageBitmap(image)
        } }
        avatarTargets.entries.toList().forEach { renderAvatar(it.value, it.key) }
        imageTargets.entries.toList().forEach { renderImage(it.value.first, it.key, it.value.second, it.value.third) }
    }

    private val pillTargets = WeakHashMap<PillImageSpan, MatrixItem>()
    private val pillBitmaps = WeakHashMap<PillImageSpan, Bitmap?>()
    override fun resolvePill(link: PermalinkData, label: String?): MatrixItem? {
        val id = when (link) {
            is PermalinkData.UserLink -> link.userId
            is PermalinkData.RoomLink -> link.roomIdOrAlias.takeIf { link.eventId == null }
            else -> null
        } ?: return null
        val person = people[id]
        Timber.d("ExportTrace pill resolved known=%s avatar=%s user=%s", person != null, person?.avatar != null, link is PermalinkData.UserLink)
        val name = person?.name ?: label ?: id
        return when (link) {
            is PermalinkData.UserLink -> MatrixItem.UserItem(id, name, person?.avatar)
            is PermalinkData.RoomLink -> if (link.isRoomAlias) MatrixItem.RoomAliasItem(id, name, person?.avatar) else MatrixItem.RoomItem(id, name, person?.avatar)
            else -> null
        }
    }
    override fun pillAvatar(item: MatrixItem): Drawable {
        val person = people[item.id]
        val styleName = if (item is MatrixItem.UserItem) options.peopleAvatarStyle else options.roomAvatarStyle
        val style = DefaultAvatarStyle.values().firstOrNull { it.name == styleName }
        return avatars.staticDrawable(item, person?.color ?: Color.GRAY, bitmap(person?.avatar ?: item.avatarUrl), style, AvatarShape.CIRCLE)
    }
    override fun bindPill(span: PillImageSpan, item: MatrixItem) {
        pillTargets[span] = item
        val image = bitmap(people[item.id]?.avatar ?: item.avatarUrl)
        if (pillBitmaps.containsKey(span) && pillBitmaps[span] === image) return
        pillBitmaps[span] = image
        span.updateAvatarDrawable(pillAvatar(item))
    }

    override fun renderAvatar(item: MatrixItem, view: ImageView) {
        avatarTargets[view] = item
        val person = people[item.id]
        val image = bitmap(person?.avatar ?: item.avatarUrl)
        val shape = AvatarShape.values().firstOrNull { it.name == options.avatarShape }
        val frame = if (shape?.isAnimated == true) (animationTimeMs / AvatarEffect.FRAME_DELAY_MS).toInt() else 0
        val key = Triple(item, image, frame)
        if (renderedAvatars[view] == key) return
        renderedAvatars[view] = key
        val color = if (person?.sharedAvatar == true) Color.GRAY else person?.color ?: Color.GRAY
        val label = if (person?.sharedAvatar == true) "?" else person?.name ?: item.displayName ?: item.id
        val displayed = if (item is MatrixItem.UserItem) MatrixItem.UserItem(item.id, label) else MatrixItem.RoomItem(item.id, label)
        val styleName = if (item is MatrixItem.UserItem) options.peopleAvatarStyle else options.roomAvatarStyle
        val style = DefaultAvatarStyle.values().firstOrNull { it.name == styleName }
        avatars.renderStatic(displayed, color, image, view, style, shape, frame.takeIf { animationTimeMs > 0 })
    }

    override fun renderImage(url: String?, view: ImageView, hidden: Boolean, eventId: String?) {
        imageTargets[view] = Triple(url, hidden, eventId)
        val image = if (!hidden) bitmap(assetsByUrl[url] ?: url) else null
        if (image != null) {
            if ((view.drawable as? BitmapDrawable)?.bitmap !== image) view.setImageBitmap(image)
        } else view.setImageDrawable(MediaPlaceholderDrawable(this, showGlyph = !hidden, hasNoSource = true).apply { setFailed(true) })
    }
}
