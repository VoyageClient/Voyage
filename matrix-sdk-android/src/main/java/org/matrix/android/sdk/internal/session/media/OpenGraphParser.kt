/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor

private const val MAX_TAGS = 50
private const val TAG_NAME_MAXLEN = 50
private const val TAG_VALUE_MAXLEN = 1000

private const val DESCRIPTION_MIN_SIZE = 200
private const val DESCRIPTION_MAX_SIZE = 500

// Elements which are not part of what a reader would call the text of the page.
private val NOT_TEXT = listOf(
        "header", "nav", "aside", "footer", "script", "noscript",
        "style", "svg", "iframe", "video", "canvas", "img", "picture"
)
private val ARIA_ROLES_TO_IGNORE = setOf("directory", "menu", "menubar", "toolbar")

private val WHITESPACE = Regex("\\s+")
private val WORD = Regex("\\s*\\S+")

/**
 * Turns a page into the OpenGraph fields of a preview, following what Synapse's own previewer
 * (`synapse/media/preview_html.py`) produces, so that a preview generated on the device looks like
 * the one a homeserver would have returned for the same page.
 */
internal object OpenGraphParser {

    fun parse(document: Document): Map<String, String> {
        val og = mutableMapOf<String, String>()
        // Later prefixes win, as in Synapse: og | article | profile.
        listOf("og", "article", "profile").forEach { prefix ->
            og += document.metaTags(attribute = "property", prefix = prefix)
        }
        // Twitter cards duplicate OpenGraph, but never overwrite it.
        document.metaTags(attribute = "name", prefix = "twitter").forEach { (key, value) ->
            twitterToOpenGraph(key)?.takeIf { it !in og }?.let { og[it] = value }
        }

        if ("og:title" !in og) {
            document.selectFirst("title, h1, h2, h3")?.text()?.trim()?.let { og["og:title"] = it }
        }
        if ("og:image" !in og) {
            document.findImage()?.let { og["og:image"] = it }
        }
        val description = og["og:description"]?.let { summarizeParagraphs(sequenceOf(it)) }
                ?: document.metaContent("description")
                ?: document.describeBody()
        description?.let { og["og:description"] = it }

        og["og:image"]?.let { image ->
            HttpUrl.parse(document.baseUri())?.resolve(image)?.let { og["og:image"] = it.toString() }
        }

        return og.filterValues { it.isNotEmpty() }
                // A page which stuffs a novel into a tag is not describing itself.
                .filterNot { (key, value) -> key.length > TAG_NAME_MAXLEN || value.length > TAG_VALUE_MAXLEN }
    }

    private fun Document.metaTags(attribute: String, prefix: String): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        select("meta[$attribute^=\"$prefix:\"][content]").forEach { tag ->
            val content = tag.attr("content")
            if (content.isEmpty()) return@forEach
            // More tags than any page has a reason to declare: someone is taking the piss.
            if (tags.size >= MAX_TAGS) return emptyMap()
            tags[tag.attr(attribute)] = content
        }
        return tags
    }

    private fun twitterToOpenGraph(key: String): String? = when (key) {
        "twitter:card", "twitter:creator" -> null
        "twitter:site" -> "og:site_name"
        else -> "og" + key.removePrefix("twitter")
    }

    /**
     * The largest image the page offers, preferring one it marked up as its own, and settling for the
     * favicon.
     */
    private fun Document.findImage(): String? {
        select("meta[itemprop][content]")
                .firstOrNull { it.attr("itemprop").equals("image", ignoreCase = true) }
                ?.absUrl("content")
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }

        val images = select("img[src]").toList()
        images.filter { it.dimension("width") > 10 && it.dimension("height") > 10 }
                .maxByOrNull { it.dimension("width") * it.dimension("height") }
                ?.let { return it.absUrl("src").takeIf { url -> url.isNotEmpty() } }
        images.firstOrNull()?.absUrl("src")?.takeIf { it.isNotEmpty() }?.let { return it }

        return selectFirst("link[href][rel~=(?i).*icon.*]")?.absUrl("href")?.takeIf { it.isNotEmpty() }
    }

    private fun Element.dimension(attribute: String) = attr(attribute).toDoubleOrNull() ?: 0.0

    private fun Document.metaContent(name: String): String? {
        return select("meta[name][content]")
                .firstOrNull { it.attr("name").equals(name, ignoreCase = true) && it.attr("content").isNotEmpty() }
                ?.attr("content")
    }

    /**
     * A very coarse plain text rendering of the page, used when it describes itself no other way.
     */
    private fun Document.describeBody(): String? {
        // The document is stripped in place rather than cloned: a page can be half a megabyte, and this is
        // the last thing read from it.
        val body = body()
        body.select(NOT_TEXT.joinToString(",")).forEach { it.remove() }
        body.select("[role]").toList().filter { it.attr("role").lowercase() in ARIA_ROLES_TO_IGNORE }.forEach { it.remove() }
        val paragraphs = mutableListOf<String>()
        NodeTraversor.traverse({ node, _ ->
            if (node is TextNode) {
                node.text().replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }?.let { paragraphs.add(it) }
            }
        }, body)
        return summarizeParagraphs(paragraphs.asSequence())
    }

    /**
     * Whole paragraphs up to [DESCRIPTION_MIN_SIZE], then whole words up to [DESCRIPTION_MAX_SIZE].
     */
    private fun summarizeParagraphs(paragraphs: Sequence<String>): String? {
        val description = buildString {
            for (paragraph in paragraphs) {
                if (length >= DESCRIPTION_MIN_SIZE) break
                append(paragraph.replace(WHITESPACE, " ")).append("\n\n")
            }
        }.trim()
        if (description.isEmpty()) return null
        if (description.length <= DESCRIPTION_MAX_SIZE) return description

        val truncated = buildString {
            for (match in WORD.findAll(description)) {
                val word = match.value
                if (word.length + length < DESCRIPTION_MAX_SIZE) {
                    append(word)
                } else {
                    // The next word overflows, but a single enormous word would otherwise leave us empty.
                    if (length < DESCRIPTION_MIN_SIZE) append(word)
                    break
                }
            }
        }
        return truncated.take(DESCRIPTION_MAX_SIZE).trim() + "…"
    }
}
