/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import androidx.annotation.StringRes
import com.squareup.moshi.JsonReader
import im.vector.lib.strings.CommonStrings
import okio.buffer
import okio.source
import java.io.InputStream

/** Code block color palettes; each is a highlight.js theme pair (tools/highlightjs/themes.js) for light and dark. */
enum class CodePalette(@StringRes val titleRes: Int) {
    ELEMENT(CommonStrings.code_palette_element),
    ELEMENT_2015(CommonStrings.code_palette_element_2015),
    SCHILDICHAT(CommonStrings.code_palette_schildichat),
    GITHUB(CommonStrings.code_palette_github),
    VISUAL_STUDIO(CommonStrings.code_palette_visual_studio),
    INTELLIJ(CommonStrings.code_palette_intellij),
    STACK_OVERFLOW(CommonStrings.code_palette_stack_overflow),
    TOKYO_NIGHT(CommonStrings.code_palette_tokyo_night),
    ROSE_PINE(CommonStrings.code_palette_rose_pine),
    SOLARIZED(CommonStrings.code_palette_solarized),
    GRUVBOX(CommonStrings.code_palette_gruvbox),
    CATPPUCCIN(CommonStrings.code_palette_catppuccin),
    NORD(CommonStrings.code_palette_nord),
    NIGHT_OWL(CommonStrings.code_palette_night_owl),
    MONOKAI(CommonStrings.code_palette_monokai),
    DRACULA(CommonStrings.code_palette_dracula),
    XTERM(CommonStrings.code_palette_xterm),
    TELEGRAM(CommonStrings.code_palette_telegram);

    companion object {
        val DEFAULT = ELEMENT

        fun of(name: String?): CodePalette = values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** What a theme sets on one scope; null leaves the property to the enclosing scope (or the block). */
data class CodeStyle(
        val color: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
) {
    val isEmpty get() = color == null && background == null && !bold && !italic && !underline
}

/**
 * One highlight.js CSS theme, resolved the way the browser's cascade would for highlight.js's markup.
 * [textColor], [background] and [border] are the block's own colors, null where the app's should stay.
 */
class CodeTheme internal constructor(
        val textColor: Int?,
        val background: Int?,
        val border: Int?,
        private val rules: List<Rule>,
) {

    internal class Rule(
            val chain: List<Set<String>>,
            val layer: Int,
            val order: Int,
            val color: Int?,
            val inheritColor: Boolean,
            val background: Int?,
            val bold: Boolean?,
            val italic: Boolean?,
            val underline: Boolean?,
    ) {
        val specificity = chain.sumOf { it.size }
    }

    private val cache = HashMap<List<String>, CodeStyle>()

    /** The style of the innermost of [scopes], which are the nested highlight.js scopes outermost first. */
    fun styleFor(scopes: List<String>): CodeStyle = synchronized(cache) {
        cache.getOrPut(scopes) { resolve(scopes.map { cssClasses(it) }) }
    }

    private fun resolve(classChain: List<Set<String>>): CodeStyle {
        val matching = rules.filter { matches(it.chain, classChain) }
                .sortedWith(compareBy<Rule>({ it.layer }, { it.specificity }, { it.order }))
        var color: Int? = null
        var background: Int? = null
        var bold = false
        var italic = false
        var underline = false
        matching.forEach { rule ->
            if (rule.inheritColor) color = null
            rule.color?.let { color = it }
            rule.background?.let { background = it }
            rule.bold?.let { bold = it }
            rule.italic?.let { italic = it }
            rule.underline?.let { underline = it }
        }
        return CodeStyle(color, background, bold, italic, underline)
    }

    // The rule's last compound must match the scope itself, and its earlier compounds its ancestors, in order.
    private fun matches(selector: List<Set<String>>, classChain: List<Set<String>>): Boolean {
        if (classChain.isEmpty() || !classChain.last().containsAll(selector.last())) return false
        var ancestor = classChain.size - 2
        for (i in selector.size - 2 downTo 0) {
            while (ancestor >= 0 && !classChain[ancestor].containsAll(selector[i])) ancestor--
            if (ancestor < 0) return false
            ancestor--
        }
        return true
    }

    companion object {
        /** highlight.js's `scopeToCSSClass`, e.g. `title.class.inherited` → `hljs-title class_ inherited__`. */
        fun cssClasses(scope: String): Set<String> {
            if (scope.startsWith("language:")) return setOf(scope.replace("language:", "language-"))
            val pieces = scope.split('.')
            return pieces.mapIndexedTo(LinkedHashSet()) { i, piece -> if (i == 0) "hljs-$piece" else piece + "_".repeat(i) }
        }
    }
}

class CodeThemes internal constructor(private val palettes: Map<String, Pair<CodeTheme, CodeTheme>>) {

    fun themeFor(palette: CodePalette, light: Boolean): CodeTheme? = palettes[palette.name]?.let { if (light) it.first else it.second }

    companion object {
        fun load(input: InputStream): CodeThemes {
            val reader = JsonReader.of(input.source().buffer())
            val palettes = HashMap<String, Pair<CodeTheme, CodeTheme>>()
            reader.use {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "palettes") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id = ""
                        var light: CodeTheme? = null
                        var dark: CodeTheme? = null
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> id = reader.nextString()
                                "light" -> light = readTheme(reader)
                                "dark" -> dark = readTheme(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        palettes[id] = light!! to dark!!
                    }
                    reader.endArray()
                }
                reader.endObject()
            }
            return CodeThemes(palettes)
        }

        private fun readTheme(reader: JsonReader): CodeTheme {
            var textColor: Int? = null
            var background: Int? = null
            var border: Int? = null
            var rules = emptyList<CodeTheme.Rule>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "base" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "c" -> textColor = reader.nextInt()
                                "b" -> background = reader.nextInt()
                                "o" -> border = reader.nextInt()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    "rules" -> rules = readRules(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return CodeTheme(textColor, background, border, rules)
        }

        private fun readRules(reader: JsonReader): List<CodeTheme.Rule> {
            val rules = ArrayList<CodeTheme.Rule>()
            reader.beginArray()
            while (reader.hasNext()) {
                var chain = emptyList<Set<String>>()
                var layer = 0
                var color: Int? = null
                var inheritColor = false
                var background: Int? = null
                var bold: Boolean? = null
                var italic: Boolean? = null
                var underline: Boolean? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "s" -> {
                            val compounds = ArrayList<Set<String>>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val classes = HashSet<String>()
                                reader.beginArray()
                                while (reader.hasNext()) classes += reader.nextString()
                                reader.endArray()
                                compounds += classes
                            }
                            reader.endArray()
                            chain = compounds
                        }
                        "l" -> layer = reader.nextInt()
                        "c" -> if (reader.peek() == JsonReader.Token.STRING) {
                            reader.nextString()
                            inheritColor = true
                        } else {
                            color = reader.nextInt()
                        }
                        "b" -> background = reader.nextInt()
                        "w" -> bold = reader.nextBoolean()
                        "i" -> italic = reader.nextBoolean()
                        "u" -> underline = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                rules += CodeTheme.Rule(chain, layer, rules.size, color, inheritColor, background, bold, italic, underline)
            }
            reader.endArray()
            return rules
        }
    }
}
