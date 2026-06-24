/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike.internal

import org.commonmark.ext.subsupstrike.Strikethrough
import org.commonmark.ext.subsupstrike.Subscript
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

internal class TildeDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '~'

    override fun getClosingCharacter() = '~'

    override fun getMinLength() = 1

    // A double run is strikethrough, a single run is subscript.
    override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun): Int {
        return if (opener.length() >= 2 && closer.length() >= 2) 2 else 1
    }

    override fun process(opener: Text, closer: Text, delimiterUse: Int) {
        val node: Node = if (delimiterUse >= 2) Strikethrough() else Subscript()
        var tmp = opener.next
        while (tmp != null && tmp !== closer) {
            val next = tmp.next
            node.appendChild(tmp)
            tmp = next
        }
        opener.insertAfter(node)
    }
}
