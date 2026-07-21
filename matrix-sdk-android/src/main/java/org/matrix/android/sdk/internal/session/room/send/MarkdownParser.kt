/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.matrix.android.sdk.api.util.TextContent
import org.matrix.android.sdk.internal.session.room.AdvancedCommonmarkParser
import org.matrix.android.sdk.internal.session.room.SimpleCommonmarkParser
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import javax.inject.Inject

/**
 * This class convert a text to an html text
 * This class is tested by [MarkdownParserTest].
 * If any change is required, please add a test covering the problem and make sure all the tests are still passing.
 */
internal class MarkdownParser @Inject constructor(
        @AdvancedCommonmarkParser private val advancedParser: Parser,
        @SimpleCommonmarkParser private val simpleParser: Parser,
        private val htmlRenderer: HtmlRenderer,
        private val textPillsUtils: TextPillsUtils
) {

    private val mdSpecialChars = "[`_\\-*>.\\[\\]#~$^|]".toRegex()

    private companion object {
        const val CUSTOM_EMOTICON_MARKER = "data-mx-emoticon"
        val listItemMarker = Regex("""^( {0,3})((?:[-*+]|\d{1,9}[.)])[ \t]+)""")
        val fenceLine = Regex("""^ {0,3}(```|~~~)""")
    }

    /**
     * Parses some input text and produces html.
     * @param text An input CharSequence to be parsed.
     * @param force Skips the check for detecting if the input contains markdown and always converts to html.
     * @param advanced Whether to use the full markdown support or the simple version.
     * @return TextContent containing the plain text and the formatted html if generated.
     */
    fun parse(text: CharSequence, force: Boolean = false, advanced: Boolean = true): TextContent {
        val source = textPillsUtils.processSpecialSpansToMarkdown(text) ?: text.toString()

        // If no special char are detected, just return plain text
        if (!force && source.contains(mdSpecialChars).not()) {
            return TextContent(source)
        }

        val effectiveSource = if (advanced) preserveExtraBlankLinesBeforeListItems(source) else source
        val document = if (advanced) advancedParser.parse(effectiveSource) else simpleParser.parse(effectiveSource)
        tightenSpuriouslyLooseLists(document)
        val htmlText = htmlRenderer.render(document)

        // Cleanup extra paragraph
        val cleanHtmlText = if (htmlText.lastIndexOf("<p>") == 0) {
            htmlText.removeSurrounding("<p>", "</p>\n")
        } else {
            htmlText
        }

        // Custom emotes are serialized as raw <img data-mx-emoticon> HTML, which commonmark passes through
        // unchanged — so cleanHtmlText == source and the pertinence check below would wrongly conclude no
        // formatting happened, dumping the HTML into the plain body. Force the formatted body in that case,
        // keeping the original :shortcode: text (from the span's backing text) as the plain body.
        val containsCustomEmoticon = source.contains(CUSTOM_EMOTICON_MARKER)

        return if (containsCustomEmoticon || isFormattedTextPertinent(source, cleanHtmlText)) {
            // According to https://matrix.org/docs/spec/client_server/latest#m-room-message-msgtypes:
            // The plain text version of the HTML should be provided in the body.
            // But it caused too many problems so it has been removed in #2002
            // See #739
            TextContent(text.toString(), cleanHtmlText.postTreatment())
        } else {
            TextContent(source)
        }
    }

    // Commonmark records blank lines between list items only as list-wide "looseness" (every item
    // gets a <p>), so which gaps actually had blank lines — and how many — is lost. Encode each
    // typed blank line before a list item as an explicit <br /> block attached to the preceding
    // item (a continuation line indented to the item's content column), so the spacing survives
    // per-gap into the HTML.
    private fun preserveExtraBlankLinesBeforeListItems(source: String): String {
        if (!source.contains("\n\n")) return source
        val lines = source.split("\n")
        val out = ArrayList<String>(lines.size)
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (fenceLine.containsMatchIn(line)) {
                inFence = !inFence
                out.add(line)
                i++
                continue
            }
            if (!inFence && line.isBlank() && i > 0) {
                var j = i
                while (j < lines.size && lines[j].isBlank()) j++
                val blanks = j - i
                val marker = if (j < lines.size) listItemMarker.find(lines[j]) else null
                if (marker != null) {
                    val indent = " ".repeat(marker.groupValues[1].length + marker.groupValues[2].length)
                    out.add("")
                    repeat(blanks) { out.add(indent + "<br />") }
                    out.add("")
                } else {
                    repeat(blanks) { out.add(lines[i + it]) }
                }
                i = j
                continue
            }
            out.add(line)
            i++
        }
        return out.joinToString("\n")
    }

    // Any blank line between items makes commonmark mark the whole list loose, wrapping every item
    // in <p>. The typed blank lines are already carried per-gap by the injected <br /> blocks, so
    // when no item genuinely holds several paragraphs the <p>s say nothing — render tight. A tight
    // item has no </p> supplying a line break before the brs, so each blank-run gets one extra
    // <br /> to keep the rendered spacing identical.
    private fun tightenSpuriouslyLooseLists(node: Node) {
        var child = node.firstChild
        while (child != null) {
            tightenSpuriouslyLooseLists(child)
            child = child.next
        }
        if (node is ListBlock && !node.isTight) {
            val items = generateSequence(node.firstChild) { it.next }.toList()
            val spurious = items.all { item ->
                generateSequence(item.firstChild) { it.next }.count { it is Paragraph } <= 1
            }
            if (spurious) {
                node.isTight = true
                items.forEach { item ->
                    generateSequence(item.firstChild) { it.next }
                            .filterIsInstance<HtmlBlock>()
                            .filter { block -> block.literal.orEmpty().lines().all { it.trim() == "<br />" } }
                            .forEach { block ->
                                // Put the compensating break at the end of the content line it
                                // terminates, not on a line of its own.
                                val paragraph = block.previous as? Paragraph
                                if (paragraph != null) {
                                    paragraph.appendChild(HtmlInline().apply { literal = "<br />" })
                                } else {
                                    block.literal += "\n<br />"
                                }
                            }
                }
            }
        }
    }

    private fun isFormattedTextPertinent(text: String, htmlText: String?) =
            text != htmlText && htmlText != "<p>${text.trim()}</p>\n"

    /**
     * The parser makes some mistakes, so deal with it here.
     */
    private fun String.postTreatment(): String {
        return this
                // Remove extra space before and after the content
                .trim()
        // There is no need to include new line in an html-like source
        // But new line can be in embedded code block, so do not remove them
        // .replace("\n", "")
    }
}
