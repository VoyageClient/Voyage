# Markwon golden dumps

`src/test/resources/markwon-golden/*.json` is the oracle for the shared rich-text parser: one file per
input in `vector/src/test/resources/richtext-corpus/NNN-<slug>.html`, produced by rendering the input
through the *real* mobile timeline pipeline under Robolectric and serialising the resulting `Spanned`.
The shared parser must reproduce `text` and `spans` of every golden exactly.

## Regenerating

```
./gradlew :vector:testDebugUnitTest --tests '*MarkwonGoldenDumpTest*'
# output dir override: -PrichtextGoldenDir=/path  or  RICHTEXT_GOLDEN_DIR=/path
```

Harness: `vector/src/test/java/im/vector/app/features/html/MarkwonGoldenDumpTest.kt` (pipeline) and
`SpanDump.kt` (span → JSON mapping). Robolectric sdk 28, `Theme_Vector_Light`, density 1.0 (all
px values are converted to dp through `displayMetrics.density` anyway). Session stubs: mxc URLs resolve to
`https://media.example.org/_matrix/media/v3/download/<server>/<id>`; `isPermalinkSupported` mirrors
`DefaultPermalinkService` (matrix.to, `matrix:` URIs, or a host from `permalink_supported_hosts`:
app/develop/staging.element.io, riot.im); `@alice:example.org` resolves to display name `Alice`, every other user has no
profile; no room summaries / members exist; `roomId = !roomid:example.org`; `latexMathsIsEnabled = true`
(the mobile default); `@room` pills use the room id.

## Pipeline exercised (= `MessageItemFactory.buildFormattedTextItem` + `MessageTextItem` bind)

1. `ProcessBodyOfReplyToEventUseCase.stripExistingMxReply` — drops the first `<mx-reply>…</mx-reply>`.
2. `VectorHtmlCompressor.compress` — HtmlCompressor (collapses whitespace runs incl. newlines to one
   space, drops comments) + strips a space after `<br>`/`<p>` and before `<br>`. Stored as `compressed`.
3. `EventHtmlRenderer.render(compressed, PillsPostProcessor)` — Markwon (HTML only, markdown blocks
   disabled, inline parser = HTML + entities) + the fork's post passes (`collapseBlockQuotePadding`,
   `separateBlockQuoteTrailingContent`, `collapsePhantomWhitespaceLines`, `materializeListMarkers`,
   trailing-whitespace strip) + pills (permalink `<a>` → `PillImageSpan`, backing text collapsed to
   U+FFFC).
4. `trimUncoveredWhitespace` — outer whitespace not covered by a LeadingMargin/LineHeight span.
   → stored as `markwon` (`{text, spans}`), i.e. what `EventHtmlRenderer.render` hands out.
5. `EventTextRenderer.render` — plain-text matrix.to permalinks and `@room` become pills (not inside code).
6. `linkify` — `MatrixLinkify` + `VectorLinkify.addLinks(keepExisting = true)` (WEB_URLS + EMAIL),
   then links over emotes / code are removed.
7. `EventHtmlRenderer.setTextWithPlugins(TextView, …)` — Markwon `afterSetText` plugins:
   `removeLeadingNewlineForInlineElement` (issue #423), `IntermediateCodeSpan` cleanup, emote binding.
   → stored as the top-level `text` / `spans`: **this is what the timeline shows** and the target.

When the compressed body has a top-level `<table>` or `<pre>`, the timeline doesn't show `text` at all;
it shows `segments` (`HtmlBodySegmenter.segment(compressed)`, rendered by `RichMessageBodyRenderer`).
Each `html` segment and each table cell goes through steps 3, 6, 7 individually (no `EventTextRenderer`,
no `trimUncoveredWhitespace`; cell html is `trim()`med first). A `<table>`/`<pre>` nested inside another
element does not trigger segmentation (single `html` segment → no `segments` key, plain `text` is shown).

## JSON schema

```
{
  "input":      original formatted_body,
  "compressed": after steps 1-2,
  "text":       displayed text (step 7),
  "spans":      [ {start, end, kind, ...attrs, flags?}, ... ],   // sorted start asc, end desc, kind asc
  "markwon":    { "text", "spans" },                              // after step 4
  "segments"?:  [ {kind:"html", html, text, spans}
                | {kind:"code", code}
                | {kind:"table", rows:[ {header, cells:[ {header, align:"left|center|right", html, text, spans} ]} ]} ],
  "error"?:     exception text if the case threw (none currently)
}
```
`flags` is present only when not `SPAN_EXCLUSIVE_EXCLUSIVE`. Colours are `#AARRGGBB`. dp values are doubles.

## Kind mapping (SpanDump.kt)

| kind | attrs | Android / Markwon span | produced by |
|---|---|---|---|
| bold | | `StrongEmphasisSpan`, `StyleSpan(BOLD)` | `<strong>`/`<b>` (`StrongEmphasisHandler`); `<summary>` (StyleSpan) |
| italic | | `CustomTypefaceSpan(DEFAULT italic)` (`italicPlugin` replaces Markwon's `EmphasisSpan` factory) | `<em>`/`<i>` |
| boldItalic | | `StyleSpan(BOLD_ITALIC)` | not observed |
| underline | | `UnderlineSpan` | `<u>` |
| strikethrough | | `StrikethroughSpan` | `<del>`, `<s>` (**not** `<strike>`) |
| subscript / superscript | | markwon-html `SubScriptSpan`/`SuperScriptSpan` (0.75 text size) | `<sub>`/`<sup>` |
| code | | `HtmlCodeSpan(isBlock=false)` — monospace + `code_block_bg_color` bg | `<code>` |
| codeBlock | | `HtmlCodeSpan(isBlock=true)` — monospace, full-width bg drawn in `drawLeadingMargin` | `<pre><code>` only (a bare `<pre>` gets nothing) |
| intermediateCode | block | `IntermediateCodeSpan` (internal) | present only in `markwon`; removed at bind |
| heading | level 1-6 | `HeadingSpan` (theme sizes 2.0/1.5/1.17/1.0/0.83/0.67, h1/h2 underline rule) | `<h1>`-`<h6>` |
| listMarker | source | `ListMarkerSpan` (fork) — marks the literal marker text inserted by `materializeListMarkers`; `source` is the markdown for copy (`"- "` / `"1. "`, `"   "`×depth prefix) | every `<li>` |
| relativeSize | proportion 0.8 | `RelativeSizeSpan(0.8)` on the `●` glyph only | bullets |
| blockquote | | `QuoteMarginSpan` (4dp stripe, 8dp margin, stripe = text colour @ alpha 25) | `<blockquote>` |
| link | url | `LinkSpan` (coloured, no underline) | `<a href>` — only in `markwon`; linkify replaces it |
| permalink | url | `MatrixPermalinkSpan` (ClickableSpan, link colour, no underline) | step 6 `MatrixLinkify`: bare mxids / aliases / event ids / `+group` ids / `matrix:` URIs / element.io-style URLs in plain text |
| url | url | `NoUnderlineUrlSpan` (URLSpan subclass) | step 6: existing `<a>` links are re-created as this, plus autolinked URLs/emails (`http://` prefixed for `www.`, `mailto:` for emails) |
| pill | id, itemType (UserItem/RoomItem/RoomAliasItem/EveryoneInRoomItem), displayName? | `PillImageSpan` over the single U+FFFC backing char | matrix.to `<a>` (step 3), plain matrix.to text + `@room` (step 5) |
| emote | mxcUrl, shortcode, body | `EmoteImageSpan` (box = line height) over the alt text | `<img>` with `data-mx-emoticon` or width/height 32 and an `mxc://` src |
| image | destination, width?, height? | `AsyncDrawableSpan` over alt text (U+FFFC when no alt) | any other `<img src>` (`width`/`height` attrs → "200.0", unit-less) |
| maths | latex, display inline/block | `JLatexInlineAsyncDrawableSpan` / `JLatexAsyncDrawableSpan` (44sp) | `data-mx-maths` (rewritten to `$$…$$` before parsing) |
| spoiler | | `SpoilerSpan` (blur + tint, click to reveal) — the `data-mx-spoiler` reason is **dropped** | `<span data-mx-spoiler>` |
| color | color | `ForegroundColorSpan` | `data-mx-color`, `color=`, `style="color:"` on `font`/`span` and on strong/em/u/del/s/sup/sub/h1-h6 |
| bgColor | color | `BackgroundColorSpan` | `data-mx-bg-color`, `style="background-color:"` (same tags) |
| verticalPadding | top, bottom (dp) | `VerticalPaddingSpan(4dp,4dp)` | every `<p>` unless it is the sole child of the root; `<details>` |
| leadingMargin | margin (dp) | `LeadingMarginSpan.Standard(8dp)` | `<details>` |
| hiddenImage | | `HiddenImageSpan` | only on the media-hidden path (not in goldens) |
| *ClassName* | raw:true, class | anything unmapped | none observed |

Default text: 15.5sp (segments/bubbles), theme text colour; link colour = `textColorLink`; headings and
code sizes are Markwon theme defaults (`MarkwonTheme` built by `Markwon.builder(context)`).

## Observed Markwon behaviour / quirks (the spec to match)

Whitespace & block separation (the HTML is tokenised by Markwon's parser only for tags; text between
tags reaches the builder through commonmark `Text` nodes, which is why several "ensure newline"
rules misfire):

1. Compression: all newline/space runs → a single space, so `<p>A</p>\n<p>B</p>` reaches Markwon as
   `<p>A</p> <p>B</p>` and renders **`A\n \nB`** — a space-only line between paragraphs (plus 4dp
   padding spans on each paragraph). With no whitespace between the tags (`<p>A</p><p>B</p>`) it is
   `A\nB`. An empty `<p></p>` yields `a\n\nb`. Space-only lines are only removed when the message
   contains a list (`collapsePhantomWhitespaceLines`), e.g. `<p>changelog:</p>\n<ol>…` → `changelog:\n1. first`.
2. A `<p>` that is the only child of the body gets no `verticalPadding` (markdown wrapper artifact).
3. `<br>` → `\n`. `<br><br>` → blank line. `<p>one</p><br><p>two</p>` → `one\n\ntwo`.
4. Block tags in Markwon's BLOCK_TAGS set (p, div, h1-6, li, ul/ol, blockquote, pre, hr, details,
   summary, table…) get a newline *before* them if the output doesn't already end with one; `</p>` and
   `</summary>` append a newline; other block ends don't. Result: `<h2>Title</h2>bare text` →
   **`Titlebare text`** (no break after a heading when followed by bare text), `<div>one</div><div>two</div>text`
   → `one\ntwotext`, adjacent quotes `<blockquote><p>one</p></blockquote><blockquote><p>two</p></blockquote>`
   → **`onetwo`** (two `blockquote` spans, no separator).
5. Trailing spaces: text followed by a block boundary keeps its trailing space, e.g. headings render as
   `Heading 1 \nHeading 2 \n…`, list items as `● first \n● second \n● third` (the last
   item is trimmed), `<li>one-b <ul><li>deep</li></ul></li>` leaves `deep     \n` (accumulated inter-tag
   spaces) before the next top-level item.
6. The whole rendered text is right-trimmed of `\n`, space, tab (`renderAndProcess`) and then
   `trimUncoveredWhitespace` trims the left edge too, except characters covered by a LeadingMargin /
   LineHeight span: `<details> <summary>…` renders with a leading **` \n`** because the details
   span covers it (`108-details-summary-paragraphs`: `" \nSummary\n \npara one\n \npara two\n  \nafter"`).
7. Markwon #423: text appended via commonmark leaves `previousIsBlock` set, so the first *inline tag*
   after a block boundary gets a stray `\n` before it (span starts on the `\n`). `removeLeadingNewlineForInlineElement`
   deletes that newline **at bind time** for emphasis/strong/underline/strike/URL/emote/image/inline-code
   spans — compare `markwon.text` `● fixed the \nNullPointerException` with displayed
   `● fixed the NullPointerException` in `145-element-web-realistic`. Not covered (newline survives):
   sub/sup/color/spoiler-only spans, e.g. `146-schildichat-realistic` keeps `1. bubbles \n(almost)`.
8. `<hr>` renders nothing at all (no span), only the surrounding newlines: `above\n \n \nbelow`;
   `above<hr>below` → `above\nbelow`. `<hr><hr><p>x</p><hr>` → `x`.
9. `<iframe>` → U+00A0; unknown tags (`marquee`, `custom-tag`, `abbr`) → plain text; `<script>`/`<style>`
   contents **are rendered as text** (`alert(1)p{}visible`); HTML comments are dropped by the compressor.
10. Leading/trailing whitespace and blank input render as empty; plain text newlines collapse to spaces
    (`line one\nline two\n\nline four` → `line one line two line four`); NBSP (`&nbsp;`) survives as U+00A0.
11. Inline whitespace is HTML-collapsed but a space at the inner edge of an inline tag is kept once on the
    outside too: `d <strong>  e  </strong> f` → `d  e  f` (two spaces each side), `<code> two spaces </code> after`
    → `" two spaces  after"` with the code span covering `" two spaces "`.
12. Unclosed inline tags produce no spans (`<strong>never closed <em>also not` → plain). Mismatched nesting
    `<b><i>x</b> y</i>` still spans `italic` over the whole run and `bold` over `x`. Stray closing tags are ignored.
    Uppercase tags work.

Lists:

13. Markers are literal text inserted by `materializeListMarkers`: `●` + NBSP for bullets (the `●` carries
    `relativeSize 0.8`), `N.` + NBSP for ordered (honours `start=`). Nesting = 3 spaces per depth, prefix
    included in the `listMarker` span and in `source`. Markwon's own `BulletListItemSpan`/`OrderedListItemSpan`
    are removed.
14. Items are separated by `\n` only; loose lists (`<li><p>…</p></li>`) add `verticalPadding` on the paragraph
    text (`● first item` span 2..12) and no blank line. Two paragraphs in one item keep the
    space-only line: `● para one\n \npara two`. `<br/>` after a loose paragraph yields exactly one blank line.
15. Empty `<li></li>` disappears entirely. A `<p>` left open before `<ul>` is auto-closed (`open para\n● item`).
16. A blockquote / code block / heading inside an item starts on the next line without a marker
    (`● item \nquote in item`, the quote span covering only its own text).

Blockquotes:

17. `<blockquote>` → `blockquote` span over its trimmed content (leading/trailing whitespace & `<br>` padding
    inside the quote are deleted). Following content not inside a quote is separated by `\n\n`
    (`quoted\n\nreply`, also for bare text after `</blockquote>`).
18. Nested: outer span covers everything incl. inner; `outer\n \ninner \nouter again`.
19. Greentext (`&gt;` lines) gets **no** special treatment in this pipeline — plain `>` text.

Code:

20. Inline `<code>` keeps entities decoded; URLs / mentions inside code get no link/pill (links removed by
    `removeLinksOverCode`, pills skipped by the post-processors), `@room` in code stays text.
21. `<pre><code>` → `codeBlock` span in `text`, but display uses the `code` segment: `wholeText()` of the
    inner `<code>` (or the `<pre>`), one trailing `\n` removed, indentation preserved (the `text` version
    loses leading indentation: `raw pre\n  indented` → `raw pre\nindented`). `class="language-x"` is ignored
    everywhere. Inline formatting inside `<pre>` keeps its spans in `text` (bold/link) but the segment is plain.
22. `<pre>` inside blockquote/list → not segmented; `codeBlock` + `blockquote` spans overlap the same range.

Links / pills / emotes / images:

23. `<a href>` → `link` in `markwon`, re-created as `url` (NoUnderlineUrlSpan) by linkify; `<a>` without href → nothing;
    `&amp;` in href is decoded. matrix.to user/room/alias links → `pill`; event permalinks are not pilled by
    step 3 but `EventTextRenderer` pills them as `RoomItem` with displayName `Message`
    (`072-link-matrix-to-event`: text `this message` → `￼`). `matrix:` URIs stay plain links.
24. Pill backing text is always a single U+FFFC; every span nested inside the link (bold, italic, emote) is
    removed (`<a><em>Alice</em></a>` → pill only). User display name comes from the session (`Alice`), absent
    otherwise; room pills have no displayName (no summary).
25. Autolink (`078`): `https://example.org/path`, `www.example.com` → `http://www.example.com`, emails → `mailto:`.
    Plain matrix.to links in text become pills (`079`). `@room` → `EveryoneInRoomItem` pill with
    displayName `@room` (word-boundary checked; `email@room.org` is a mailto link instead).
26. Emotes: backing text is the alt (`:party:`); `shortcode` = title ?: alt without surrounding colons; an
    `<img data-mx-emoticon>` without alt backs onto U+FFFC with `shortcode ""` and no `body` (`091`). An emote
    right after `</p>` keeps the paragraph newline (`first\n:e6:`). A link wrapping only an emote loses the
    link (`093`); an emote inside a mention is swallowed by the pill (`094`).
27. Other `<img>`: `image` span over alt text (U+FFFC without alt), `width`/`height` attributes as "200.0"/"100.0".
    Non-mxc sources are kept as destination but the Glide loader ignores them.

Colours / spoilers / details / maths / tables:

28. Colour parsing = `android.graphics.Color.parseColor` + a named fallback list: `#rrggbb`, `#aarrggbb`,
    names (`blue`, `teal`, `red`…) work; **`#f0f` and `rgb()` are not parsed** (no span). Priority
    `data-mx-color` > `color` > `style color`; `data-mx-bg-color` > `style background-color`. Empty tags get no span.
    Nested colours overlap (both spans present). `color`/`bgColor` layer on strong/em/u/del/s/sup/sub/h1-h6 too.
29. Spoiler reason is discarded; spoiler spans can contain pills/bold; `data-mx-color` on the same span → both spans.
30. `<details>` → `leadingMargin 8dp` + `verticalPadding 4dp` over the whole element; `<summary>` → `bold` and a
    `\n` after it; `open` attr ignored; `<strong>` inside summary gives a second identical `bold` span.
31. Maths: inline `data-mx-maths` → `maths(display=inline)` over the `$$…$$` literal; block `<div data-mx-maths>`
    → `maths(display=block)` whose `latex` keeps the surrounding `\n…\n` and is separated from neighbours by
    blank lines (`before\n\n\sum…\n\nafter`). HTML entities in the attribute are unescaped.
32. Tables are only shown via segments: `thead` rows are header rows, a body row whose cells are all `<th>` is a
    header row; alignment from `align=` or `style="text-align:…"`; ragged rows are kept as-is; empty cells give
    `text ""`. The `text` fallback for a table is all cell texts concatenated with the inter-tag spaces
    (`Name Value     a 1   b 2`), never displayed.
33. `<mx-reply>` is stripped before anything else (`111`, `112`); the renderer's `MxReplyTagHandler` is dead code
    on this path.
34. The `* ` edit-fallback prefix is kept as text: `* \nedited body…`.

Bare identifiers, permalinks and autolinks (cases 160-212):

35. `MatrixLinkify` puts a `permalink` span on bare `@user:server`, `#alias:server`, `$event:server`,
    `+group:server` and `matrix:` URIs (url = `https://matrix.to/#/<id>` or the URI itself) — but **not**
    on a bare `!roomid:server` (163). An mxid directly after `/` is not linked (168); at start/end of text
    and comma-separated pairs all link (171-173). Inside `<code>` nothing links (169).
36. Every bare identifier additionally gets a **nested `url` span over its server part**: `@alice:example.org`
    → `permalink` 5..23 **and** `url http://example.org` 12..23, `matrix:r/room:example.org/e/$abc?via=x` →
    `url http://example.org/e/$abc?via=x` (WEB_URLS matches the domain; `MatrixPermalinkSpan` is not a
    URLSpan so `keepExisting` can't protect it). The overlap is real mobile behaviour.
37. Plain-text matrix.to permalinks become pills through `EventTextRenderer` (regex `https?://[^\s/]+/#/\S+`,
    then trailing `.`, `)`, `,` are excluded — 179-182): event link in the harness room → `RoomItem`
    "Message"; other room → "Message in room"; alias → "Message in #alias:example.org"; user link with
    `?via=` → user pill. element.io-host room links pill too (`RoomItem` "Room/Space", 184). A `<a>` whose
    text is the URL behaves like any `<a>` (pill, 183). `matrix:` URIs in `<a href>` are pills (073), bare
    `matrix:` URIs only `permalink` + nested `url` (166/167).
38. `VectorLinkify`: trailing `/` kept; balanced parens kept, trailing unbalanced `)` dropped
    (`https://example.org/a_(b)`, `https://example.org`); `foo.com.fizzbuzz` not linked; `mailto:` prefix
    included in the span when present, bare emails get `mailto:`; `MSC1234`/`msc4` →
    `https://github.com/matrix-org/matrix-spec-proposals/pull/N` (8-digit `MSC12345678` not linked);
    `geo:` URIs linked (`geo:48.85,2.35`, `geo:48.858093,2.294694;u=35`), bare coordinates not; unicode
    paths, `%20`, uppercase hosts and `&` queries are linked verbatim; U+202E/U+202C in the text do **not**
    suppress linking (197); `https://example.org/@alice:example.org` is one `url`, no `permalink` (200).
    An `<a>` next to a bare copy of the same URL gives two independent `url` spans (199).
39. `removeLinksOverCode` / `removeLinksOverEmotes` remove *every* `ClickableSpan` overlapping code or an
    emote — including `SpoilerSpan`: `<span data-mx-spoiler>secret <code>code</code></span>` and
    `…secret <img data-mx-emoticon…></span>` lose the spoiler entirely (201, 202; the `markwon` stage still
    has it). Spoilers over a URL or an mxid survive and carry the `url`/`permalink` spans too (203, 204).
40. A link wrapping an emote **plus text** loses the whole link, not just the emote part (212: `:e: text`
    keeps only the `emote` span).
41. `@room`: alone / with punctuation / twice / inside `<strong>` → `EveryoneInRoomItem` pill (bold span shrinks
    onto the U+FFFC); `@rooms`, `email@room.org` (mailto link) and `<code>@room</code>` are not pilled.


42. **/rainbow, /trans commands** (`RainbowGenerator`): each non-space character is its own
    `<font color="#rrggbb">X</font>`, spaces bare between them. Renders as one `color` span per character
    (`#FFrrggbb`), the bare spaces carrying no span (213: `Hello world`, 10 spans, gap at index 5). Colour
    layers with inline markdown on the same char — `<font><strong>B</strong></font>` gives overlapping
    `bold` + `color` at 0..1 (214). A multi-codepoint emoji wrapped in one font tag is coloured across its
    whole UTF-16 range as a single span (215: `🏳` at 13..15). Single-char (`size==1`) is one span (216).
