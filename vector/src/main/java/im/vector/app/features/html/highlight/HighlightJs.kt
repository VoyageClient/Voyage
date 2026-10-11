/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern

/** A scoped run of the highlighted text. Nested scopes follow their parent in the list. */
data class HljsToken(val start: Int, val end: Int, val scope: String)

data class HljsResult(
        /** Name of the language used, or null when nothing beat plain text. */
        val language: String?,
        val relevance: Double,
        val illegal: Boolean,
        val tokens: List<HljsToken>,
)

/**
 * A Kotlin port of highlight.js 11.11.1's parser (`_highlight` and `highlightAuto`), running the grammars
 * exported by tools/highlightjs/export.js, so scopes match Element Web's.
 *
 * [openFile] opens a file of the export by name; each grammar is loaded the first time it's needed.
 *
 * Not thread-safe, as compiled modes carry matcher state the way highlight.js's do: callers serialize access.
 */
class HighlightJs(private val openFile: (String) -> InputStream) {

    private val index = openFile(INDEX).use { HljsGrammarLoader.loadIndex(it) }
    private val entries = LinkedHashMap<String, HljsIndexEntry>().apply { index.forEach { put(it.name, it) } }
    private val aliases = HashMap<String, String>().apply {
        index.forEach { entry -> entry.aliases.forEach { put(it.lowercase(), entry.name) } }
    }
    private val loaded = HashMap<String, HljsLanguage>()

    fun listLanguages(): List<String> = entries.keys.toList()

    fun hasLanguage(name: String): Boolean = entryOf(name) != null

    private fun entryOf(name: String): HljsIndexEntry? {
        val key = name.lowercase()
        return entries[key] ?: aliases[key]?.let { entries[it] }
    }

    private fun getLanguage(name: String): HljsLanguage? {
        val entry = entryOf(name) ?: return null
        return loaded.getOrPut(entry.name) { openFile("${entry.name}.json").use { HljsGrammarLoader.loadLanguage(it) } }
    }

    /** highlight.js's `highlight(code, { language, ignoreIllegals })`, where an omitted ignoreIllegals means true. */
    fun highlight(code: String, language: String, ignoreIllegals: Boolean = true): HljsResult? {
        if (entryOf(language) == null) return null
        return highlightInternal(language, code, ignoreIllegals, null).toResult()
    }

    /** highlight.js's `highlightAuto(code, languageSubset)`, which grammars use for embedded code. */
    internal fun highlightAuto(code: String, languageSubset: List<String>? = null): HljsResult = highlightAutoInternal(code, languageSubset).toResult()

    private class Frame(val mode: HljsMode, val parent: Frame?)

    private class InternalResult(
            val language: String?,
            val relevance: Double,
            val illegal: Boolean,
            val emitter: Emitter,
            val top: Frame?,
    ) {
        fun toResult() = HljsResult(language, relevance, illegal, if (language == null) emptyList() else emitter.tokens())
    }

    private class IllegalLexemeException : RuntimeException()

    private fun highlightAutoInternal(code: String, languageSubset: List<String>?): InternalResult {
        val subset = languageSubset ?: entries.keys.toList()
        val plaintext = InternalResult(null, 0.0, false, Emitter().apply { addText(code) }, null)
        val results = ArrayList<InternalResult>()
        results += plaintext
        subset.filter { name -> entryOf(name)?.disableAutodetect == false }
                .forEach { results += highlightInternal(it, code, ignoreIllegals = false, continuation = null) }
        // Stable, like the V8 sort highlight.js relies on: earlier languages win ties.
        val sorted = results.sortedWith { a, b ->
            when {
                a.relevance != b.relevance -> b.relevance.compareTo(a.relevance)
                a.language != null && b.language != null -> when {
                    entryOf(a.language)?.supersetOf == b.language -> 1
                    entryOf(b.language)?.supersetOf == a.language -> -1
                    else -> 0
                }
                else -> 0
            }
        }
        return sorted[0]
    }

    private fun highlightInternal(languageName: String, code: String, ignoreIllegals: Boolean, continuation: Frame?): InternalResult {
        val language = getLanguage(languageName)!!
        val run = Run(language, languageName, code, ignoreIllegals, continuation)
        return run.execute()
    }

    private inner class Run(
            private val language: HljsLanguage,
            private val languageName: String,
            private val code: String,
            private val ignoreIllegals: Boolean,
            continuation: Frame?,
    ) {
        private val emitter = Emitter()
        private val keywordHits = HashMap<String, Int>()
        private val continuations = HashMap<String, Frame?>()
        private val rootFrame = Frame(language.root, null)
        private var top: Frame = continuation ?: rootFrame
        private var modeBuffer = StringBuilder()
        private var relevance = 0.0
        private var index = 0
        private var iterations = 0
        private var resumeScanAtSamePosition = false
        private var lastMatch: MatchResult? = null

        fun execute(): InternalResult {
            processContinuations()
            try {
                matcherOf(top.mode).considerAll()
                while (true) {
                    iterations++
                    val matcher = matcherOf(top.mode)
                    if (resumeScanAtSamePosition) {
                        resumeScanAtSamePosition = false
                    } else {
                        matcher.considerAll()
                    }
                    matcher.lastIndex = index
                    val match = matcher.exec(code) ?: break
                    val beforeMatch = code.substring(index, match.index)
                    val processedCount = processLexeme(beforeMatch, match)
                    index = match.index + processedCount
                }
                processLexeme(code.substring(index), null)
                emitter.finalize()
                return InternalResult(languageName, relevance, false, emitter, top)
            } catch (e: IllegalLexemeException) {
                return InternalResult(languageName, 0.0, true, Emitter().apply { addText(code) }, null)
            } catch (e: RuntimeException) {
                // highlight.js's SAFE_MODE: any other failure leaves the code unhighlighted.
                return InternalResult(languageName, 0.0, false, Emitter().apply { addText(code) }, top)
            } catch (e: StackOverflowError) {
                return InternalResult(languageName, 0.0, false, Emitter().apply { addText(code) }, top)
            }
        }

        private fun matcherOf(mode: HljsMode): ResumableMultiRegex {
            mode.matcher?.let { return it }
            val built = ResumableMultiRegex(language.caseInsensitive, language.unicodeRegex)
            mode.contains.forEach { built.addRule(it.begin ?: "\\B|\\b", it.beginGroups, RuleInfo(RuleType.BEGIN, it)) }
            mode.terminatorEnd?.let { built.addRule(it, mode.terminatorEndGroups, RuleInfo(RuleType.END, null)) }
            mode.illegal?.let { built.addRule(it, mode.illegalGroups, RuleInfo(RuleType.ILLEGAL, null)) }
            mode.matcher = built
            return built
        }

        private fun alias(scope: String) = language.classNameAliases[scope] ?: scope

        private fun processKeywords() {
            val keywords = top.mode.keywords
            if (keywords == null) {
                emitter.addText(modeBuffer)
                return
            }
            val pattern = top.mode.keywordMatcher ?: JsRegex.compile(top.mode.keywordPattern ?: "\\w+", language.caseInsensitive, language.unicodeRegex)
                    .also { top.mode.keywordMatcher = it }
            val buffer = modeBuffer.toString()
            val m = pattern.matcher(buffer)
            var lastIndex = 0
            val buf = StringBuilder()
            while (m.find()) {
                buf.append(buffer, lastIndex, m.start())
                val text = m.group()
                val word = if (language.caseInsensitive) text.lowercase() else text
                val data = keywords[word]
                if (data != null) {
                    emitter.addText(buf)
                    buf.setLength(0)
                    val hits = (keywordHits[word] ?: 0) + 1
                    keywordHits[word] = hits
                    if (hits <= MAX_KEYWORD_HITS) relevance += data.relevance
                    if (data.scope.startsWith("_")) {
                        buf.append(text)
                    } else {
                        emitKeyword(text, alias(data.scope))
                    }
                } else {
                    buf.append(text)
                }
                lastIndex = m.end()
                if (m.end() == m.start()) break
            }
            buf.append(buffer, lastIndex, buffer.length)
            emitter.addText(buf)
        }

        private fun processSubLanguage() {
            if (modeBuffer.isEmpty()) return
            val mode = top.mode
            val buffer = modeBuffer.toString()
            val result: InternalResult
            val name = mode.subLanguageName
            if (name != null) {
                if (!entries.containsKey(name)) {
                    emitter.addText(buffer)
                    return
                }
                result = highlightInternal(name, buffer, true, continuations[name])
                continuations[name] = result.top
            } else {
                result = highlightAutoInternal(buffer, mode.subLanguageList.takeIf { it.isNotEmpty() })
            }
            if (mode.relevance > 0) relevance += result.relevance
            emitter.addSublanguage(result.emitter, result.language)
        }

        private fun processBuffer() {
            if (top.mode.hasSubLanguage) processSubLanguage() else processKeywords()
            modeBuffer.setLength(0)
        }

        private fun emitKeyword(keyword: CharSequence, scope: String) {
            if (keyword.isEmpty()) return
            emitter.openNode(scope)
            emitter.addText(keyword)
            emitter.closeNode()
        }

        private fun emitMultiClass(scope: HljsScope, match: MatchResult) {
            for (i in 1 until match.groups.size) {
                if (i !in scope.emit) continue
                val klass = scope.multi[i]?.let { alias(it) }
                val text = match.groups[i].orEmpty()
                if (klass != null) {
                    emitKeyword(text, klass)
                } else {
                    modeBuffer.setLength(0)
                    modeBuffer.append(text)
                    processKeywords()
                    modeBuffer.setLength(0)
                }
            }
        }

        private fun startNewMode(mode: HljsMode, match: MatchResult): Frame {
            mode.scope?.let { emitter.openNode(alias(it)) }
            mode.beginScope?.let { beginScope ->
                if (beginScope.wrap != null) {
                    emitKeyword(modeBuffer, alias(beginScope.wrap))
                    modeBuffer.setLength(0)
                } else if (beginScope.multi.isNotEmpty() || beginScope.emit.isNotEmpty()) {
                    emitMultiClass(beginScope, match)
                    modeBuffer.setLength(0)
                }
            }
            top = Frame(mode, top)
            return top
        }

        private fun endOfMode(frame: Frame, match: MatchResult, matchIndex: Int): Frame? {
            var matched = startsWith(frame.mode, matchIndex)
            if (matched) {
                frame.mode.onEnd?.let { callback ->
                    if (runCallback(callback, frame.mode, match)) matched = false
                }
                if (matched) {
                    var mode = frame
                    while (mode.mode.endsParent && mode.parent != null) mode = mode.parent!!
                    return mode
                }
            }
            if (frame.mode.endsWithParent) return frame.parent?.let { endOfMode(it, match, matchIndex) }
            return null
        }

        // `startsWith(mode.endRe, code.substring(index))`: the end pattern must match right at the index,
        // seeing nothing before it.
        private fun startsWith(mode: HljsMode, index: Int): Boolean {
            val source = mode.end ?: return false
            val pattern = mode.endMatcher ?: JsRegex.compile(source, language.caseInsensitive, language.unicodeRegex).also { mode.endMatcher = it }
            val m = pattern.matcher(code)
            m.region(index, code.length)
            m.useTransparentBounds(false)
            m.useAnchoringBounds(true)
            return m.lookingAt()
        }

        private fun doIgnore(lexeme: String): Int {
            return if (matcherOf(top.mode).regexIndex == 0) {
                modeBuffer.append(lexeme[0])
                1
            } else {
                resumeScanAtSamePosition = true
                0
            }
        }

        private fun doBeginMatch(match: MatchResult): Int {
            val lexeme = match.groups[0]!!
            val newMode = match.rule.mode!!
            for (callback in listOf(newMode.beforeBegin, newMode.onBegin)) {
                if (callback == null) continue
                if (runCallback(callback, newMode, match)) return doIgnore(lexeme)
            }
            if (newMode.skip) {
                modeBuffer.append(lexeme)
            } else {
                if (newMode.excludeBegin) modeBuffer.append(lexeme)
                processBuffer()
                if (!newMode.returnBegin && !newMode.excludeBegin) {
                    modeBuffer.setLength(0)
                    modeBuffer.append(lexeme)
                }
            }
            startNewMode(newMode, match)
            return if (newMode.returnBegin) 0 else lexeme.length
        }

        private fun doEndMatch(match: MatchResult): Int {
            val lexeme = match.groups[0]!!
            val endMode = endOfMode(top, match, match.index) ?: return NO_MATCH
            val origin = top
            val endScope = top.mode.endScope
            when {
                endScope?.wrap != null -> {
                    processBuffer()
                    // highlight.js doesn't apply classNameAliases here.
                    emitKeyword(lexeme, endScope.wrap)
                }
                endScope != null && (endScope.multi.isNotEmpty() || endScope.emit.isNotEmpty()) -> {
                    processBuffer()
                    emitMultiClass(endScope, match)
                }
                origin.mode.skip -> modeBuffer.append(lexeme)
                else -> {
                    if (!(origin.mode.returnEnd || origin.mode.excludeEnd)) modeBuffer.append(lexeme)
                    processBuffer()
                    if (origin.mode.excludeEnd) {
                        modeBuffer.setLength(0)
                        modeBuffer.append(lexeme)
                    }
                }
            }
            do {
                if (top.mode.scope != null) emitter.closeNode()
                if (!top.mode.skip && !top.mode.hasSubLanguage) relevance += top.mode.relevance
                top = top.parent!!
            } while (top !== endMode.parent)
            endMode.mode.starts?.let { startNewMode(it, match) }
            return if (origin.mode.returnEnd) 0 else lexeme.length
        }

        private fun processContinuations() {
            val scopes = ArrayList<String>()
            var current: Frame? = top
            while (current != null && current !== rootFrame && current.mode !== language.root) {
                current.mode.scope?.let { scopes.add(0, it) }
                current = current.parent
            }
            scopes.forEach { emitter.openNode(it) }
        }

        private fun processLexeme(textBeforeMatch: String, match: MatchResult?): Int {
            modeBuffer.append(textBeforeMatch)
            if (match == null) {
                processBuffer()
                return 0
            }
            val lexeme = match.groups[0]!!
            val previous = lastMatch
            if (previous != null && previous.rule.type == RuleType.BEGIN && match.rule.type == RuleType.END &&
                    previous.index == match.index && lexeme.isEmpty()) {
                modeBuffer.append(code, match.index, minOf(match.index + 1, code.length))
                return 1
            }
            lastMatch = match
            when {
                match.rule.type == RuleType.BEGIN -> return doBeginMatch(match)
                match.rule.type == RuleType.ILLEGAL && !ignoreIllegals -> throw IllegalLexemeException()
                match.rule.type == RuleType.END -> {
                    val processed = doEndMatch(match)
                    if (processed != NO_MATCH) return processed
                }
            }
            if (match.rule.type == RuleType.ILLEGAL && lexeme.isEmpty()) {
                modeBuffer.append('\n')
                return 1
            }
            if (iterations > 100000 && iterations > match.index * 3) {
                throw IllegalStateException("potential infinite loop, way more iterations than matches")
            }
            modeBuffer.append(lexeme)
            return lexeme.length
        }

        /** Runs a grammar callback; returns true when it ignores the match. */
        private fun runCallback(name: String, mode: HljsMode, match: MatchResult): Boolean {
            val data = mode.data ?: HashMap<String, String?>().also { mode.data = it }
            return when (name) {
                "skipIfHasPrecedingDot" -> match.index > 0 && code[match.index - 1] == '.'
                "shebangAtStart" -> match.index != 0
                "endSameAsBeginBegin" -> {
                    data[BEGIN_MATCH] = match.groups.getOrNull(1)
                    false
                }
                "phpHeredocBegin" -> {
                    data[BEGIN_MATCH] = match.groups.getOrNull(1) ?: match.groups.getOrNull(2)
                    false
                }
                "endSameAsBeginEnd" -> data[BEGIN_MATCH] != match.groups.getOrNull(1)
                "jsxIsTrulyOpeningTag" -> jsxIsNotOpeningTag(match)
                "gcodeLetterBoundary" -> {
                    if (match.index == 0) return false
                    val before = code[match.index - 1]
                    !(before in '0'..'9' || before == '_')
                }
                "mathematicaSystemSymbol" -> match.groups[0] !in language.systemSymbols
                else -> false
            }
        }

        private fun jsxIsNotOpeningTag(match: MatchResult): Boolean {
            val lexeme = match.groups[0]!!
            val afterMatchIndex = lexeme.length + match.index
            val nextChar = code.getOrNull(afterMatchIndex)
            if (nextChar == '<' || nextChar == ',') return true
            if (nextChar == '>') {
                val tag = "</" + lexeme.substring(1)
                if (code.indexOf(tag, afterMatchIndex) == -1) return true
            }
            val afterMatch = code.substring(afterMatchIndex)
            if (JSX_ASSIGNMENT.matcher(afterMatch).lookingAt()) return true
            if (JSX_EXTENDS.matcher(afterMatch).lookingAt()) return true
            return false
        }
    }

    internal enum class RuleType { BEGIN, END, ILLEGAL }

    internal class RuleInfo(val type: RuleType, val mode: HljsMode?) {
        var position = 0
    }

    internal class MatchResult(val index: Int, val groups: List<String?>, val rule: RuleInfo)

    /** highlight.js's MultiRegex: every rule joined into one alternation, the matched group telling which rule hit. */
    internal class MultiRegex(private val caseInsensitive: Boolean, private val unicode: Boolean) {
        private val rules = ArrayList<Pair<String, Int>>()
        private val matchIndexes = LinkedHashMap<Int, RuleInfo>()
        private var matchAt = 1
        private var position = 0
        private var pattern: Pattern? = null
        private var unsetGroups: Set<Int> = emptySet()
        private var matcher: Matcher? = null
        private var matcherInput: String? = null
        var lastIndex = 0

        // The info is shared with the other matchers built from the same rules, and each build renumbers its
        // position, exactly as highlight.js mutates its shared `opts`.
        fun addRule(source: String, groups: Int, info: RuleInfo) {
            info.position = position++
            matchIndexes[matchAt] = info
            rules += source to groups
            matchAt += groups + 1
        }

        fun compile() {
            if (rules.isEmpty()) return
            val source = rewriteBackreferences(rules.map { it.first }, "|")
            unsetGroups = JsRegex.negativeLookaroundGroups(source)
            pattern = JsRegex.compile(source, caseInsensitive, unicode)
        }

        fun exec(s: String): MatchResult? {
            val pattern = pattern ?: return null
            if (lastIndex > s.length) return null
            val m = if (matcherInput === s) matcher!! else pattern.matcher(s).also {
                matcher = it
                matcherInput = s
            }
            if (!m.find(lastIndex)) return null
            // The first participating rule group; only these can be the first defined group in JS, where inner
            // groups of a failed alternative or a negative lookaround are always unset.
            val (i, info) = matchIndexes.entries.firstOrNull { m.group(it.key) != null }?.toPair() ?: return null
            val groups = ArrayList<String?>(m.groupCount() - i + 1)
            for (g in i..m.groupCount()) groups += if (g in unsetGroups) null else m.group(g)
            return MatchResult(m.start(i), groups, info)
        }
    }

    /** highlight.js's ResumableMultiRegex, including its quirks, which decide which rule wins at a position. */
    internal class ResumableMultiRegex(private val caseInsensitive: Boolean, private val unicode: Boolean) {
        private val rules = ArrayList<Triple<String, Int, RuleInfo>>()
        private val multiRegexes = HashMap<Int, MultiRegex>()
        private var count = 0
        var lastIndex = 0
        var regexIndex = 0

        private fun getMatcher(index: Int): MultiRegex = multiRegexes.getOrPut(index) {
            MultiRegex(caseInsensitive, unicode).apply {
                rules.drop(index).forEach { (source, groups, info) -> addRule(source, groups, info) }
                compile()
            }
        }

        fun considerAll() {
            regexIndex = 0
        }

        fun addRule(source: String, groups: Int, info: RuleInfo) {
            rules += Triple(source, groups, info)
            if (info.type == RuleType.BEGIN) count++
        }

        fun exec(s: String): MatchResult? {
            val m = getMatcher(regexIndex)
            m.lastIndex = lastIndex
            var result = m.exec(s)
            if (regexIndex != 0) {
                if (!(result != null && result.index == lastIndex)) {
                    val m2 = getMatcher(0)
                    m2.lastIndex = lastIndex + 1
                    result = m2.exec(s)
                }
            }
            if (result != null) {
                regexIndex += result.rule.position + 1
                if (regexIndex == count) considerAll()
            }
            return result
        }
    }

    /** highlight.js's TokenTreeEmitter, keeping offsets instead of text. */
    internal class Emitter {
        private class Node(val scope: String?, val start: Int, val children: MutableList<Any> = ArrayList()) {
            var end = start
        }

        private val root = Node(null, 0)
        private val stack = ArrayList<Node>().apply { add(root) }
        var length = 0
            private set

        fun addText(text: CharSequence) {
            length += text.length
        }

        fun openNode(scope: String) {
            val node = Node(scope, length)
            stack.last().children += node
            stack += node
        }

        fun closeNode() {
            if (stack.size > 1) stack.removeAt(stack.size - 1).end = length
        }

        fun addSublanguage(emitter: Emitter, name: String?) {
            val node = Node(name?.let { "language:$it" }, length)
            node.children += emitter.root to length
            length += emitter.length
            node.end = length
            stack.last().children += node
        }

        fun finalize() {
            while (stack.size > 1) closeNode()
        }

        fun tokens(): List<HljsToken> {
            val out = ArrayList<HljsToken>()
            collect(root, 0, out)
            return out
        }

        private fun collect(node: Node, offset: Int, out: MutableList<HljsToken>) {
            if (node.scope != null && node.end > node.start) out += HljsToken(node.start + offset, node.end + offset, node.scope)
            node.children.forEach { child ->
                when (child) {
                    is Node -> collect(child, offset, out)
                    is Pair<*, *> -> collect(child.first as Node, offset + child.second as Int, out)
                }
            }
        }
    }

    companion object {
        private const val INDEX = "index.json"
        private const val MAX_KEYWORD_HITS = 7
        private const val NO_MATCH = -1
        private const val BEGIN_MATCH = "_beginMatch"
        private val JSX_ASSIGNMENT = Pattern.compile("^[\\t\\n\\x0B\\f\\r ]*=")
        private val JSX_EXTENDS = Pattern.compile("^[\\t\\n\\x0B\\f\\r ]+extends[\\t\\n\\x0B\\f\\r ]+")

        private val BACKREF_RE = Pattern.compile("\\[(?:[^\\\\\\]]|\\\\.)*\\]|\\(\\??|\\\\([1-9][0-9]*)|\\\\.")

        /** highlight.js's `_rewriteBackreferences`: wraps each regex in a group and renumbers its backreferences. */
        internal fun rewriteBackreferences(regexps: List<String>, joinWith: String): String {
            var numCaptures = 0
            return regexps.joinToString(joinWith) { regex ->
                numCaptures += 1
                val offset = numCaptures
                var re = regex
                val out = StringBuilder()
                while (re.isNotEmpty()) {
                    val m = BACKREF_RE.matcher(re)
                    if (!m.find()) {
                        out.append(re)
                        break
                    }
                    out.append(re, 0, m.start())
                    val matched = m.group()
                    re = re.substring(m.end())
                    if (matched[0] == '\\' && m.group(1) != null) {
                        out.append('\\').append(m.group(1)!!.toInt() + offset)
                    } else {
                        out.append(matched)
                        if (matched == "(") numCaptures++
                    }
                }
                "($out)"
            }
        }
    }
}
