/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike.internal

import org.commonmark.ext.subsupstrike.Superscript
import org.commonmark.node.Text
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

internal class CaretDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '^'

    override fun getClosingCharacter() = '^'

    override fun getMinLength() = 1

    override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun) = 1

    override fun process(opener: Text, closer: Text, delimiterUse: Int) {
        val node = Superscript()
        var tmp = opener.next
        while (tmp != null && tmp !== closer) {
            val next = tmp.next
            node.appendChild(tmp)
            tmp = next
        }
        opener.insertAfter(node)
    }
}
