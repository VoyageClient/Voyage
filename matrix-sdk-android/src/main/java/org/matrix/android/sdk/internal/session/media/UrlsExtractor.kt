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

package org.matrix.android.sdk.internal.session.media

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.isReply
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.internal.session.room.AdvancedCommonmarkParser
import javax.inject.Inject

internal class UrlsExtractor @Inject constructor(
        webUrlPattern: WebUrlPattern,
        @AdvancedCommonmarkParser private val markdownParser: Parser,
) {
    // Sadly Patterns.WEB_URL_WITH_PROTOCOL is not public so filter the protocol later
    private val urlRegex = webUrlPattern.regex

    fun extract(event: TimelineEvent): List<String> {
        return event.takeIf { it.root.getClearType() == EventType.MESSAGE }
                ?.getLastMessageContent()
                ?.let { extract(it, event.isReply()) }
                .orEmpty()
    }

    fun extract(content: MessageContent, isReply: Boolean): List<String> {
        val text = content.previewableText(isReply) ?: return emptyList()
        val formattedBody = (content as? MessageContentWithFormattedBody)?.matrixFormattedBody ?: return extract(text)
        val body = Jsoup.parseBodyFragment(formattedBody).body()
        body.select("mx-reply, pre, code").remove()
        val urls = mutableListOf<String>()
        NodeTraversor.traverse({ node, _ ->
            when (node) {
                is Element -> if (node.tagName() == "a") urls += extract(node.attr("href"))
                is TextNode -> urls += extract(node.text())
            }
        }, body)
        return urls.distinct()
    }

    fun extractMarkdown(text: String): List<String> {
        val urls = mutableListOf<String>()
        markdownParser.parse(text).accept(object : AbstractVisitor() {
            var htmlCodeDepth = 0

            override fun visit(text: Text) {
                if (htmlCodeDepth == 0) urls += extract(text.literal)
            }

            override fun visit(link: Link) {
                if (htmlCodeDepth == 0) urls += extract(link.destination)
                visitChildren(link)
            }

            override fun visit(htmlInline: HtmlInline) {
                CODE_TAG.findAll(htmlInline.literal).forEach { match ->
                    htmlCodeDepth = if (match.value.startsWith("</")) {
                        (htmlCodeDepth - 1).coerceAtLeast(0)
                    } else {
                        htmlCodeDepth + 1
                    }
                }
            }

            override fun visit(code: Code) = Unit
            override fun visit(fencedCodeBlock: FencedCodeBlock) = Unit
            override fun visit(indentedCodeBlock: IndentedCodeBlock) = Unit
        })
        return urls.distinct()
    }

    fun extract(text: String): List<String> {
        return urlRegex.findAll(text)
                .map { text.substring(it.range.first, balanceParens(text, it.range.first, it.range.last + 1)) }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .distinct()
                .toList()
    }

    // The pattern stops before a trailing ')' unless the url ends the text, truncating urls with a
    // bracketed path; a url wrapped in brackets needs the opposite trim.
    private fun balanceParens(text: String, start: Int, end: Int): Int {
        var depth = 0
        for (i in start until end) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        var balancedEnd = end
        while (depth > 0 && balancedEnd < text.length && text[balancedEnd] == ')') {
            balancedEnd++
            depth--
        }
        while (depth < 0 && balancedEnd > start && text[balancedEnd - 1] == ')') {
            balancedEnd--
            depth++
        }
        return balancedEnd
    }

    companion object {
        private val CODE_TAG = Regex("</?(?:pre|code)\\b[^>]*>", RegexOption.IGNORE_CASE)

        /** The user-typed text of a message that may carry links: a text body or a media caption (MSC2530 / MSC4274). */
        fun MessageContent.previewableText(isReply: Boolean): String? {
            return when {
                msgType == MessageType.MSGTYPE_TEXT ||
                        msgType == MessageType.MSGTYPE_NOTICE ||
                        msgType == MessageType.MSGTYPE_EMOTE -> {
                    if (isReply) ContentUtils.extractUsefulTextFromReply(body) else body
                }
                this is MessageWithAttachmentContent -> getCaption(isReply)
                this is MessageGalleryContent -> galleryCaption()
                else -> null
            }
        }
    }
}
