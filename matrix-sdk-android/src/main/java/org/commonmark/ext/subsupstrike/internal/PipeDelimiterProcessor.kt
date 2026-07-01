/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike.internal

import org.commonmark.ext.subsupstrike.Spoiler
import org.commonmark.node.Text
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

// Matrix-style spoiler: `||text||` -> <span data-mx-spoiler>. minLength 2 keeps a lone `|`
// (and GFM table cell separators) literal.
internal class PipeDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '|'

    override fun getClosingCharacter() = '|'

    override fun getMinLength() = 2

    override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun): Int {
        return if (opener.length() >= 2 && closer.length() >= 2) 2 else 0
    }

    override fun process(opener: Text, closer: Text, delimiterUse: Int) {
        val node = Spoiler()
        var tmp = opener.next
        while (tmp != null && tmp !== closer) {
            val next = tmp.next
            node.appendChild(tmp)
            tmp = next
        }
        opener.insertAfter(node)
    }
}
