/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import java.io.InputStream

internal class HljsKeyword(val scope: String, val relevance: Double)

internal class HljsScope(val wrap: String?, val multi: Map<Int, String>, val emit: Set<Int>)

/** A compiled highlight.js mode, as exported by tools/highlightjs/export.js. */
internal class HljsMode {
    var scope: String? = null
    var beginScope: HljsScope? = null
    var endScope: HljsScope? = null
    var begin: String? = null
    var beginGroups = 0
    var end: String? = null
    var terminatorEnd: String? = null
    var terminatorEndGroups = 0
    var illegal: String? = null
    var illegalGroups = 0
    var keywords: Map<String, HljsKeyword>? = null
    var keywordPattern: String? = null
    var relevance = 1.0
    var excludeBegin = false
    var excludeEnd = false
    var returnBegin = false
    var returnEnd = false
    var endsParent = false
    var endsWithParent = false
    var skip = false
    var hasSubLanguage = false
    var subLanguageName: String? = null
    var subLanguageList: List<String> = emptyList()
    var beforeBegin: String? = null
    var onBegin: String? = null
    var onEnd: String? = null
    var contains: List<HljsMode> = emptyList()
    var starts: HljsMode? = null

    // Runtime state, owned by the (single-threaded) engine like the compiled mode objects in highlight.js.
    var matcher: HighlightJs.ResumableMultiRegex? = null
    var endMatcher: java.util.regex.Pattern? = null
    var keywordMatcher: java.util.regex.Pattern? = null
    var data: MutableMap<String, String?>? = null
}

internal class HljsLanguage(
        val name: String,
        val caseInsensitive: Boolean,
        val unicodeRegex: Boolean,
        val classNameAliases: Map<String, String>,
        val root: HljsMode,
        // Only Mathematica's callback needs these.
        val systemSymbols: Set<String>,
)

/** A registered language, from index.json, in highlight.js's registration order. */
internal class HljsIndexEntry(val name: String, val aliases: List<String>, val disableAutodetect: Boolean, val supersetOf: String?)

internal object HljsGrammarLoader {

    fun loadIndex(input: InputStream): List<HljsIndexEntry> {
        val entries = ArrayList<HljsIndexEntry>()
        JsonReader.of(input.source().buffer()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "languages") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    var name = ""
                    var aliases = emptyList<String>()
                    var disableAutodetect = false
                    var supersetOf: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> name = reader.nextString()
                            "aliases" -> aliases = readStrings(reader)
                            "disableAutodetect" -> disableAutodetect = reader.nextBoolean()
                            "supersetOf" -> supersetOf = reader.nextString()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    entries += HljsIndexEntry(name, aliases, disableAutodetect, supersetOf)
                }
                reader.endArray()
            }
            reader.endObject()
        }
        return entries
    }

    fun loadLanguage(input: InputStream): HljsLanguage = JsonReader.of(input.source().buffer()).use { readLanguage(it) }

    private fun readLanguage(reader: JsonReader): HljsLanguage {
        var name = ""
        var caseInsensitive = false
        var unicodeRegex = false
        var systemSymbols = emptySet<String>()
        var classNameAliases = emptyMap<String, String>()
        var keywordTables = emptyList<Map<String, HljsKeyword>>()
        val rawModes = ArrayList<RawMode>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "caseInsensitive" -> caseInsensitive = reader.nextBoolean()
                "unicodeRegex" -> unicodeRegex = reader.nextBoolean()
                "systemSymbols" -> systemSymbols = readStrings(reader).toHashSet()
                "classNameAliases" -> classNameAliases = readStringMap(reader)
                "keywordTables" -> keywordTables = readKeywordTables(reader)
                "modes" -> {
                    reader.beginArray()
                    while (reader.hasNext()) rawModes += readMode(reader)
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val modes = rawModes.map { it.mode }
        rawModes.forEachIndexed { index, raw ->
            val mode = modes[index]
            mode.contains = raw.contains.map { modes[it] }
            mode.starts = raw.starts?.let { modes[it] }
            mode.keywords = raw.keywords.takeIf { it >= 0 }?.let { keywordTables[it] }
        }
        return HljsLanguage(name, caseInsensitive, unicodeRegex, classNameAliases, modes[0], systemSymbols)
    }

    private class RawMode(val mode: HljsMode, val contains: List<Int>, val starts: Int?, val keywords: Int)

    private fun readMode(reader: JsonReader): RawMode {
        val mode = HljsMode()
        var contains = emptyList<Int>()
        var starts: Int? = null
        var keywords = -1
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "scope" -> mode.scope = reader.nextString()
                "beginScope" -> mode.beginScope = readScope(reader)
                "endScope" -> mode.endScope = readScope(reader)
                "begin" -> mode.begin = reader.nextString()
                "beginGroups" -> mode.beginGroups = reader.nextInt()
                "end" -> mode.end = reader.nextString()
                "terminatorEnd" -> mode.terminatorEnd = reader.nextString()
                "terminatorEndGroups" -> mode.terminatorEndGroups = reader.nextInt()
                "illegal" -> mode.illegal = reader.nextString()
                "illegalGroups" -> mode.illegalGroups = reader.nextInt()
                "keywords" -> keywords = reader.nextInt()
                "keywordPattern" -> mode.keywordPattern = reader.nextString()
                "relevance" -> mode.relevance = reader.nextDouble()
                "excludeBegin" -> mode.excludeBegin = reader.nextBoolean()
                "excludeEnd" -> mode.excludeEnd = reader.nextBoolean()
                "returnBegin" -> mode.returnBegin = reader.nextBoolean()
                "returnEnd" -> mode.returnEnd = reader.nextBoolean()
                "endsParent" -> mode.endsParent = reader.nextBoolean()
                "endsWithParent" -> mode.endsWithParent = reader.nextBoolean()
                "skip" -> mode.skip = reader.nextBoolean()
                "subLanguage" -> {
                    mode.hasSubLanguage = true
                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        mode.subLanguageList = readStrings(reader)
                    } else {
                        mode.subLanguageName = reader.nextString()
                    }
                }
                "beforeBegin" -> mode.beforeBegin = reader.nextString()
                "onBegin" -> mode.onBegin = reader.nextString()
                "onEnd" -> mode.onEnd = reader.nextString()
                "contains" -> contains = readInts(reader)
                "starts" -> starts = reader.nextInt()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RawMode(mode, contains, starts, keywords)
    }

    private fun readScope(reader: JsonReader): HljsScope {
        var wrap: String? = null
        var multi = emptyMap<Int, String>()
        var emit = emptySet<Int>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "wrap" -> wrap = reader.nextString()
                "multi" -> multi = readStringMap(reader).mapKeys { it.key.toInt() }
                "emit" -> emit = readInts(reader).toSet()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return HljsScope(wrap, multi, emit)
    }

    private fun readKeywordTables(reader: JsonReader): List<Map<String, HljsKeyword>> {
        val tables = ArrayList<Map<String, HljsKeyword>>()
        val scopes = HashMap<String, String>()
        reader.beginArray()
        while (reader.hasNext()) {
            val table = HashMap<String, HljsKeyword>()
            reader.beginObject()
            while (reader.hasNext()) {
                val word = reader.nextName()
                reader.beginArray()
                val scope = reader.nextString().let { scopes.getOrPut(it) { it } }
                // JSON has no NaN: hy's `|=` keyword splits into an empty word scored Number("=").
                val relevance = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull<Any>().let { Double.NaN } else reader.nextDouble()
                reader.endArray()
                table[word] = HljsKeyword(scope, relevance)
            }
            reader.endObject()
            tables += table
        }
        reader.endArray()
        return tables
    }

    private fun readStrings(reader: JsonReader): List<String> {
        val out = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) out += reader.nextString()
        reader.endArray()
        return out
    }

    private fun readInts(reader: JsonReader): List<Int> {
        val out = ArrayList<Int>()
        reader.beginArray()
        while (reader.hasNext()) out += reader.nextInt()
        reader.endArray()
        return out
    }

    private fun readStringMap(reader: JsonReader): Map<String, String> {
        val out = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (reader.peek() == JsonReader.Token.NULL) {
                reader.skipValue()
            } else {
                out[key] = reader.nextString()
            }
        }
        reader.endObject()
        return out
    }
}
