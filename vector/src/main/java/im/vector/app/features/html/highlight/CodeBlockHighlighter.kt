/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.features.html.BodySegment
import im.vector.app.features.html.HtmlBodySegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Highlights code blocks the way Element Web's CodeBlock does: a block over [MAX_HIGHLIGHT_LENGTH] stays plain,
 * and the first `language-` class highlight.js knows decides the language. Blocks naming none stay plain.
 *
 * Parsing never runs on the main thread and is serialized (the engine isn't thread-safe); results are cached,
 * and [prepare] fills the cache while the timeline/room list models are built, so blocks draw colored at once.
 */
@Singleton
class CodeBlockHighlighter @Inject constructor(
        @ApplicationContext private val context: Context,
) {

    data class Request(val code: String, val languageClasses: List<String>)

    private val engine by lazy { HighlightJs { name -> context.assets.open("$GRAMMARS/$name") } }
    private val themes by lazy { context.assets.open(THEMES).use { CodeThemes.load(it) } }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "CodeHighlighter")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<Request, HljsResult>(CACHE_SIZE)
    private val pending = HashMap<Request, MutableList<(HljsResult) -> Unit>>()

    private val engineLock = Any()

    private val _paletteChanges = MutableStateFlow(0L)

    /** Bumped when the code palette is changed, so lists can redraw while still in the background. */
    val paletteChanges: StateFlow<Long> = _paletteChanges.asStateFlow()

    fun notifyPaletteChanged() {
        _paletteChanges.value++
    }

    fun requestFor(segment: BodySegment.Code): Request? {
        if (segment.source.length > MAX_HIGHLIGHT_LENGTH || segment.languageClasses.isEmpty()) return null
        return Request(segment.source, segment.languageClasses)
    }

    /**
     * Highlights the code blocks of [segments] ahead of their views, so they draw colored on their first frame:
     * right away on a background caller (timeline and room list models are built off the main thread), else queued.
     */
    fun prepare(segments: List<BodySegment>) {
        val requests = segments.filterIsInstance<BodySegment.Code>().mapNotNull { requestFor(it) }.filter { cache.get(it) == null }
        if (requests.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requests.forEach { highlight(it) {} }
        } else {
            requests.forEach { cache.put(it, computeLocked(it)) }
        }
    }

    /**
     * A grammar's first use is slow (its file is parsed and its patterns compiled), so the common ones are run
     * once, in the background, a little after startup, before any code block asks for them.
     */
    fun warmUp() {
        mainHandler.postDelayed({
            executor.execute {
                themes
                WARM_UP_LANGUAGES.forEach { language -> computeLocked(Request(WARM_UP_SAMPLE, listOf(language))) }
            }
        }, WARM_UP_DELAY_MS)
    }

    /** Highlights [request] and loads the palettes in the background, ready for a screen about to show it. */
    fun prefetch(request: Request) {
        executor.execute { themes }
        highlight(request) {}
    }

    /** [prepare] for a formatted body, e.g. the latest message a room list row previews. */
    fun prepareHtml(html: String) {
        if (html.contains("<pre", ignoreCase = true)) prepare(HtmlBodySegmenter.segment(html))
    }

    /** Delivers the result on the main thread, immediately when it's cached. */
    fun highlight(request: Request, callback: (HljsResult) -> Unit) {
        cache.get(request)?.let {
            callback(it)
            return
        }
        synchronized(pending) {
            pending[request]?.let {
                it += callback
                return
            }
            pending[request] = mutableListOf(callback)
        }
        executor.execute {
            val result = computeLocked(request)
            cache.put(request, result)
            mainHandler.post {
                val callbacks = synchronized(pending) { pending.remove(request) }.orEmpty()
                callbacks.forEach { it(result) }
            }
        }
    }

    private fun computeLocked(request: Request): HljsResult = synchronized(engineLock) {
        cache.get(request)?.let { return it }
        try {
            compute(request)
        } catch (e: Throwable) {
            Timber.w(e, "Code highlighting failed")
            PLAIN
        }
    }

    private fun compute(request: Request): HljsResult {
        val code = request.code
        if (code.length > MAX_HIGHLIGHT_LENGTH) return PLAIN
        val advertised = request.languageClasses.firstOrNull { engine.hasLanguage(it) } ?: return PLAIN
        return engine.highlight(code, advertised) ?: PLAIN
    }

    fun theme(palette: CodePalette, light: Boolean): CodeTheme? = themes.themeFor(palette, light)

    /** [text] (a prefix of the highlighted code) with [result]'s scopes styled by [palette]. */
    fun styled(text: String, result: HljsResult, palette: CodePalette, light: Boolean): CharSequence {
        if (result.tokens.isEmpty()) return text
        val theme = themes.themeFor(palette, light) ?: return text
        val spannable = SpannableString(text)
        val open = ArrayList<HljsToken>()
        val scopes = ArrayList<String>()
        result.tokens.forEach { token ->
            while (open.isNotEmpty() && open.last().end <= token.start) {
                open.removeAt(open.size - 1)
                scopes.removeAt(scopes.size - 1)
            }
            open += token
            scopes += token.scope
            val start = token.start
            val end = minOf(token.end, text.length)
            if (end <= start) return@forEach
            val style = theme.styleFor(scopes.toList())
            if (style.isEmpty) return@forEach
            style.color?.let { spannable.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            style.background?.let { spannable.setSpan(BackgroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            val typefaceStyle = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
            if (typefaceStyle != 0) spannable.setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (style.underline) spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    companion object {
        // Element Web's limit: bigger blocks aren't highlighted at all.
        const val MAX_HIGHLIGHT_LENGTH = 4096
        private const val CACHE_SIZE = 256
        private const val WARM_UP_DELAY_MS = 10_000L

        // What chats paste most, most likely first so those are ready soonest.
        private val WARM_UP_LANGUAGES = listOf(
                "bash", "json", "python", "javascript", "typescript", "kotlin", "java", "c", "cpp", "rust", "go", "xml",
                "css", "yaml", "sql", "diff", "csharp", "php", "ruby", "lua", "swift", "dockerfile", "markdown", "ini",
        )

        // Touches the modes most code uses: comments, strings, numbers, calls and a tag.
        private const val WARM_UP_SAMPLE = "// note\n# note\n/* note */\nvalue = call(\"text\", 'c', 42)\n<tag key=\"v\">x</tag>\n"
        private const val GRAMMARS = "highlightjs/languages"
        private const val THEMES = "highlightjs/themes.json"
        private val PLAIN = HljsResult(null, 0.0, false, emptyList())

        /** Element Web reads `language-objective-c` as `objective`: it keeps what follows the first dash, up to the next. */
        fun languageClasses(classNames: Collection<String>): List<String> = classNames
                .filter { it.startsWith("language-") }
                .map { it.split('-')[1] }
    }
}
