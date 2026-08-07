# Voyage — Changelog

New features, improvements, and notable removals in this fork.

## Features & improvements

- **Runs on very old Android, down to Ice Cream Sandwich (API 14)** — brings a modern Matrix client to devices nothing else supports: automatic disabling of pre-KitKat's Dalvik bytecode verifier, an R8-shrunk release build that fits the old LinearAlloc ceiling, and many old-Android UI fixes. Versions of Android that do not support Emoji fall back to bundled Twemoji.

- **Snappier, freeze-free app** — async media uploads that no longer block the UI, faster tab-switching and message-sending, quicker syncs on launch and idle-return, deep timeline/room-list caching, and a dedicated Performance Mode plus performance/logging toggles.

- **Background-sync battery fix** — permission-gated polling and a single alarm chain with a bounded retry loop, replacing overnight battery drain.

- **Custom emoticons & stickers (MSC2545 image packs)** — send custom emoji and stickers, pickers for both, author your own packs, import and export packs as Misskey-style zip archives, and react with emoticons.

- **Keep deleted messages (MSC2815)** — see what a deleted message said. Room moderators and homeserver admins can fetch the original content back from a Synapse server that supports it. Each deleted message can be revealed or re-hidden from its long-press menu. Configurable account-wide under Settings → Security & privacy → Redactions and per room under Personalization: whether to preserve media, how large a download to accept, whether to restrict it to Wi-Fi, and whether the kept content survives clearing the app cache. Preserved media has its own clear actions, account-wide under Settings → General and per room under Personalization.

- **Per-room media visibility** — the media-visibility settings can now be overridden per room from Personalization, so a single room can show or hide media regardless of the account-wide choice.

- **Local message search, including encrypted rooms** — a local event index with its own database, plus advanced filters: `from:`, `mentions:`, `has:(image|video|audio|file|sticker)`, `before:`/`after:` dates, and quoted exact-substring matching.

- **PGP encryption** — opt-in PGP encrypt/decrypt over otherwise-unencrypted rooms via OpenKeychain, with an `/encrypt` command.

- **Multi-account switcher** — switch between multiple logged-in accounts.

- **SchildiChat themes & message bubbles** — SchildiChat Light/Dark/Black themes and opt-in message bubbles (None / Both sides / Same side) with configurable corner roundness, an optional tail, and accent tinting of your own bubbles; timestamps shown inline in the bubble and overlaid on images/videos.

- **SchildiChat layout & behaviour options** — a combined people+rooms Overview list, mark chats as read/unread (MSC2867) synced with compatible clients, URL previews in encrypted rooms, opening a room at its first unread message, jump-to-bottom when sending, remembered collapsed list sections, and showing/hiding space members as people.

- **Classic composer** — the message composer goes back to the flat layout it had before 2020: no rounded input box, a divider above it, and accent-coloured glyphs — a bare `+` for the share options, the emoji/keyboard toggle moved out of the text box, and a plain paper-plane send button instead of a white glyph on a filled circle. The encryption shield returns beside the input, the voice-message mic gets its own slot rather than sharing the send button's, and the composer sits on the toolbar colour so it reads as separate from the timeline. The share-options popout matches it, down to the `+` rotating into the close X. The reply/edit preview slides open and shut again rather than appearing instantly. On by default; turn it off under Settings → Preferences to get the boxed composer back.

- **Steadier share-options toolbar** — the attachment picker is now part of the composer's own layout instead of a floating window placed at fixed screen coordinates, so it can no longer drift out of alignment or be left stranded over the timeline when the keyboard opens or a reply preview appears. It also closes when you start typing or reply to a message.

- **Image editor before sending** — crop, rotate and black out parts of a photo from the attachment preview, with pinch-zoom and panning for precise work. Censor boxes are drawn straight onto the image, so nothing recoverable is left behind. Edits are remembered per attachment, so reopening the editor lets you adjust what you did rather than starting again from the flattened result.

- **Video editor before sending** — trim a video to the part you want from the attachment preview, on a filmstrip timeline with draggable handles, and crop, rotate or mute it. Cropping works exactly like the image editor's — drag the corner handles, pinch and pan — and re-encodes through a GL stage so the result is a genuinely smaller frame. Holding a handle zooms the timeline to a per-frame ruler for exact cuts, with a haptic tick on every frame. Trimming is lossless wherever it can be — the video is re-wrapped rather than re-encoded, which is near-instant and costs no quality — and only falls back to re-encoding when the cut has to land between keyframes. Like the image editor, edits are remembered per attachment and replayed against the original. Playback speed can be set anywhere from 0.1x to 3x on a slider that is finest around normal speed, with the audio either following the speed as tape does or holding its original pitch.

- **Animated image editor** — GIF, APNG and animated WebP can go through the video editor before sending, to trim, crop, rotate or change the speed of them frame by frame. The result is always written as an animated WebP, the one animated format the app can both write and display everywhere.

- **Compression controls before sending** — set the quality and the exact output resolution of a photo or video from the attachment preview, with width and height linked to the source aspect ratio until you break the link. The preview reshapes to what will actually be sent, and the editor opens on that same shape, so what you see before sending is what arrives.

- **Video playback controls** — a seekbar with elapsed and total time under the attachment preview, tap-to-play/pause on videos in the full-screen viewer, and the length of a video shown on its thumbnail in the timeline. Photos and videos can be pinch-zoomed and panned before sending.

- **Media captions & replies** — add/edit/remove captions on media, reply to or comment alongside media, rich reply previews with embedded image/video/sticker thumbnails, and the ability to reply to and redact non-message events (reactions, joins, leaves, redactions).

- **Room & profile banners (MSC4221 / MSC4427)** — Discord-style banner images on room and user profile pages (2.8:1, avatar overlapping, tap to view full-screen), settable from room settings and account settings, with a per-room override of your own banner in the room's personalization tab; banner changes show as timeline notices. Interoperable with the Haven element-web patchset.

- **Mutual Rooms in profiles** — a user's profile has a Mutual Rooms button opening a compact list of the rooms you share, grouped under their spaces (with rounded-square space avatars) and DMs included; tap a room to open it, or tap a space to filter the room list to it.

- **Pronouns & time zone in profiles (MSC4247 / MSC4175 / MSC4133)** — set your pronouns (common presets or custom text, multiple allowed) and IANA time zone in account settings; a user's pronouns and current time-zone abbreviation show under their name on profile pages as e.g. `she/her • PST` (DST-aware), and their pronouns gender timeline notices such as "changed **her** avatar". Reads the stable field keys and writes the unstable ones, and interoperates with other clients' pronoun schemas.

- **Message pinning** — pin and unpin messages, sorted by most recent, with a pinned-messages banner (toggleable).

- **Message forwarding** — forward messages (using their most recent edit), with a custom Forward icon. Pick as many rooms as you like from the room picker, each with its own checkbox, and send to all of them at once from the button in the toolbar. The same picker handles content shared into the app from other apps.

- **Mass redactions** — bulk redaction via a `/massredact` command (with cooldown); redacting a message also redacts its edits and reactions and is applied live to open timelines, "remove" is renamed to "redact", and there's a toggle to skip the confirmation dialog.

- **Homeserver mirrors** — the homeserver entry in settings is now an editable, reorderable list. Add mirrors of your homeserver (alternate domains, a reverse proxy, an onion address) and the app falls back to the next one whenever the current one can't be reached or answers with a gateway error. Your ordering is never rewritten: the mirrors above the one in use are rechecked every few minutes while the app is open (or on demand from the recheck button), and the app moves back up as soon as one is reachable again.

- **Read receipts** — private read receipts, a toggle for sending them at all, and queued receipts that retry until the server confirms.

- **Ignored users fully silenced** — read receipts and presence from ignored users are now dropped during sync, alongside the typing notifications already filtered.

- **Auto-dismiss "Jump to unread"** — optional: whenever the timeline is at its end (opening a room at the bottom, or scrolling down to it), the Jump to unread banner is dismissed and the room marked as read instead of the banner appearing.

- **Intentional Mentions (MSC3952)** — proper support, plus mention-rendering improvements (mentions backed by a single character rather than the full display name).

- **Frecency-ranked @-mentions** — the `@`-autocomplete now lists the people you mention most often in a room first, instead of plain alphabetical order; these per-room counts are backed up to account data so the ranking follows you across devices.

- **Selectable message & topic text** — select text directly from timeline messages: double-tap starts a selection anywhere, long-press on a code block or inline code starts one locked to that code (Select all expands it to the whole message), links and plain text keep their long-press actions, and the selection menu is trimmed to Copy, Share and Select all; the room profile topic is selectable the same way, replacing long-press-to-copy.

- **Rich room topics (MSC3765)** — room and space topics now support formatted content: their HTML body is rendered like timeline messages (falling back to markdown when a topic is plain text only), and editing a topic publishes the HTML rendering alongside the plain text so other clients can show it too. The room IDs, aliases and user IDs in a topic show as tappable pills (previously only the homeserver part of an alias was a link), and in the room profile tapping a matrix link opens the room/user in-app while other links open in the browser.

- **Timeline polish** — consecutive hidden events collapse into a single "N hidden events" tile, overhauled state-change notices, room-list previews that reflect message edits, an always-show-timestamps option, and a jump-to-present button that returns to the message you jumped from.

- **Direction-override (RLO) spoofing protection** — hostile Unicode direction-override characters in display names, messages, mention pills, and room names no longer flip the surrounding text backwards (a trick used to spoof user IDs and file extensions); they now show as a visible placeholder box instead. Genuine right-to-left text is unaffected.

- **Metadata stripping on upload** — sent photos and videos no longer leak embedded metadata (GPS location, capture timestamps, camera make/model & serial numbers, and the hidden EXIF thumbnail). JPEG, PNG and WebP are scrubbed losslessly while keeping display orientation; formats that can't be scrubbed in place (e.g. HEIC) are re-encoded; videos are re-muxed to drop their location atoms without re-encoding; and images sent through the file picker, as well as profile/room avatars and banners, are covered too. Controlled by a new "Remove metadata from sent media" toggle in Settings → Security & Privacy → Visual (on by default).

- **Media hiding** — hide media, and inline images/emoji, in the timeline until tapped.

- **Hideable message shields** — toggles in Settings → Security & Privacy → Visual to hide the grey key-backup shield (messages decrypted with a key restored from secure backup) and the red encryption-warning shield (unencrypted messages in encrypted rooms, or messages from unverified/unknown/deleted sessions); reactions and redactions, which are always sent unencrypted, no longer get a red shield in encrypted rooms.

- **Identity-change banner** — backported from Element Web: a banner at the top of an encrypted room warns when a member's cross-signing identity changes, shown in red for someone you had previously verified. Dismissing it (or "Withdraw verification" for the verified case) pins their current identity, so it only reappears if their identity resets again. Identity pinning is tracked in the crypto store, and a Settings → Security & Privacy → Visual toggle can hide the banner outright while still accepting any current changes.

- **Voice messages overhaul** — an Opus decoder, playback of audio while it still uploads, scheduled playback for not-yet-downloaded audio, and a processing-stage indicator when sending.

- **Media viewer with pinch-to-zoom** — a reworked image/video viewer that supports pinch-to-zoom on still images, animated images (GIFs / animated WebP), and videos, an overhauled compression pipeline (compress by the shorter side so long media isn't squished), and correct thumbnail stubs during upload.

- **Blurhash placeholders** — images and videos show a compact blurred preview while they load, and as the placeholder for hidden media, instead of a blank box.

- **Markdown & HTML rendering overhaul** — add/improve tables (with a no-wrap option), blockquotes, spoilers, greentext, code blocks, underline (`__x__`), strikethrough (`~~x~~`), subscript (`~x~`), and superscript (`^x^`) tags; links and pills no longer render inside code blocks.

- **Greentext** — quote-style greentext rendering, with an option to send all blockquotes as greentext.

- **Slash commands** — added `/jumpto`, `/jumptostart`, `/jumptodate`, `/converttodm`, `/converttoroom`, `/blockquote`, `/greentext`, `/html`, `/massredact`, `/tombstone`, `/download`, `/encrypt`, and `/trans`/`/transme` (trans-flag gradient messages), plus the ability to run slash commands on a reply; `/rainbow` now paints nheko's vivid gradient instead of washed-out CIELAB colors.

- **Emoji font options** — render emoji with bundled Twemoji, the system emoji font, or a custom emoji font you supply; emoji autocomplete is toggleable.

- **Configurable reactions** — configurable quick reactions that sync across your devices, a compact quick-reactions layout, remote sync of frequent emoji, and freeform reactions by typing any text.

- **Room knocking** — request access to rooms that require it.

- **Room creation & tombstoning** — an overhauled room-creation wizard, a per-room Personalization page, and a tombstoning overhaul driven by `/tombstone`.

- **Room tags** — tag support for rooms.

- **"Kick", not "remove"** — the action to remove a member from a room is now labelled "kick" rather than the vaguer "remove".

- **Spaces improvements** — view a space's own timeline, show all rooms in Home by default, and a spaces drawer replacing the new UI's custom spaces view.

- **Force display name & avatar** — override display name and avatar per room and per group DM.

- **Configurable avatars** — configurable avatar shapes, avatar-hiding options (in the timeline and on invites), avatar removal, an empty-display-name fallback, and full-screen avatar zoom through the media viewer with a smooth open/close animation.

- **Consistent deleted-message previews** — a deleted message now reads as deleted everywhere it is previewed, not just in the timeline: reply headers, the composer's reply preview, the room list, the pinned-messages banner and list, and the long-press menu all show it greyed out with a trash icon rather than as ordinary text, and thread summaries grey it out too. The wording is unified on "Message redacted".

- **Block all room invites (MSC4380)** — one switch under Settings → Security & privacy has your homeserver reject every invite sent to you, on all your devices at once. Requires server support.

- **Media visibility follows your account (MSC4278)** — the media-preview and invite-avatar settings are stored on your account rather than only on the device that set them, so a new sign-in keeps the choices you already made and Element Web and Element X read the same setting.

- **Key backup choice follows your account (MSC4287)** — turning key backup on or off is remembered account-wide, so a new device stops prompting you to set up a backup you declined elsewhere.

- **Knocking on restricted rooms (MSC3787)** — a room can now combine both rules: members of a chosen space join directly, everyone else asks to join. Room settings offer it and the room preview shows the right action. Join-rule changes also read correctly in the timeline for knock and restricted rooms, which previously showed nothing at all.

- **Filter the room directory by type (MSC3827)** — search rooms only, spaces only, or both, from the directory's overflow menu.

- **Misc improvements** — randomizable upload filenames, first-frame video thumbnails, toggleable app shortcuts, display of custom power levels, WebView SSL-error tolerance, an overhauled jump-to-latest button, a "Show in chat" action in the media viewer that jumps back to the message an attachment came from, room-list preview polish, a direct message's room settings showing the other person's avatar as the room list does, and links no longer drawn underlined anywhere they were left inconsistent — room, space and directory previews, permalink pills, and the dialogs and banners built from raw HTML.

### Removals

- **Removed calling support (Element Call / Jitsi / WebRTC)** — the entire voice/video calling and Jitsi conferencing stack was dropped (it blocked old devices and pulled in heavy native deps); call events are now rendered inline in the timeline instead.

- **Removed telemetry & reporting** — dropped telemetry, analytics, bug reporting, Sentry, the content-reporting system, sunsetting banners, and the "push notifications are disabled" banner.

- **Removed voice broadcast** — the upstream voice-broadcast feature was dropped.

### Branding

- **Rebrand to Voyage** — new app ID, icons, and (configurable) logo, a Voyage colour scheme on the splash screen and throughout, an overhauled Help & About screen, and removal of hardcoded Element-green usages.

- **Accent picker** — a swatch picker for the app's accent color, plus an optional "ugly" username color palette. The picker offers thirty colors including monochrome white and black accents.

## Under the hood

- **Persistence rewrite: Realm → SQLDelight** — replaced Realm with a custom framework-SQLite driver (unblocking older Android and desktop), alongside an overhaul of the ignore system.

- **New crypto backend: libce** — replaced vodozemac with the libce submodule, enabling builds for old devices.

- **Reads off the database write thread** — the timeline and the room list each read on their own thread, so opening a room or refreshing the list no longer waits for a sync response to finish being written.

- **Performance internals** — SQLite WAL, reactive-layer deduplication, an epoxy-pipeline rework, gated space-hierarchy revalidation, bulk timeline queries replacing per-row N+1s, and a memoized event mapper underpin the user-facing speedups above.

- **Modern sync and spec endpoints** — sync asks for room state as of the end of the timeline (MSC4222), so state stops drifting out of date after a gap; a thread's edits and reactions arrive in one request (MSC3981); and reporting (MSC4277), room forgetting (MSC4267), relations and the room directory moved onto their stable endpoints.

- **Dependency & build slimming** — dropped the WYSIWYG composer, Sentry, and the unused JNA dependency; vendored markwon-html; bumped conscrypt; and enabled optimization for libopus.

## Significant bugfixes

- Fixed editing a just-sent message silently doing nothing if it was still sending.

- Fixed link previews, images, GIFs and reply previews flashing or reloading at the moment a sent message is confirmed by the server (and blurhash fades replaying while sending).

- Fixed message sending growing sluggish the longer the app stays open, with messages queueing up and batching out.

- **Plain-http homeservers** — logging into an `http://` homeserver (self-hosted, LAN IP, Tor) no longer fails with a cleartext-not-permitted error on Android 6+.

- Fixed the app showing stale rooms after being backgrounded, with no sign it was catching up — returning to the foreground now always starts an immediate sync and shows progress while it runs.

- Fixed a space-hierarchy recursion that could crash the app with a stack overflow.

- Fixed a crash on Lollipop.

- Fixed a client freeze triggered by fast uploads.

- Fixed room v12 support regressions.

- Fixed replies getting scrambled.

- Fixed reaction counts drifting — a single reaction showing 2+, or reactions becoming un-clickable, from duplicated local echoes.

- Fixed a storage-fill bug.

- Fixed a blank timeline after screen rotation.
