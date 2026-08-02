/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.OrderedListItemSpan

/**
 * Markwon span subclasses that expose what the stock classes keep private, so rendered text can
 * be turned back into its markdown source. The list spans only live until
 * [EventHtmlRenderer] replaces them with literal marker characters.
 */
class SourceBulletListItemSpan(theme: MarkwonTheme, level: Int) : BulletListItemSpan(theme, level)

class SourceOrderedListItemSpan(theme: MarkwonTheme, val number: Int) : OrderedListItemSpan(theme, "$number. ")

/** Marks literal list-marker characters; [source] is the markdown that produces them. */
class ListMarkerSpan(val source: String)
