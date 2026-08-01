# Voyage — Changelog

New features, improvements, and notable removals in this fork.

## Features & improvements

- **Runs on very old Android, down to Ice Cream Sandwich (API 14)** — brings a modern Matrix client to devices nothing else supports: automatic disabling of pre-KitKat's Dalvik bytecode verifier, an R8-shrunk release build that fits the old LinearAlloc ceiling, and many old-Android UI fixes. Versions of Android that do not support Emoji fall back to bundled Twemoji.

- **Snappier, freeze-free app** — async media uploads that no longer block the UI, faster tab-switching and message-sending, quicker syncs on launch and idle-return, deep timeline/room-list caching, and a dedicated Performance Mode plus performance/logging toggles.

- **Background-sync battery fix** — permission-gated polling and a single alarm chain with a bounded retry loop, replacing overnight battery drain.

- **Custom emoticons & stickers (MSC2545 image packs)** — send custom emoji and stickers, pickers for both, author your own packs, import and export packs as Misskey-style zip archives, and react with emoticons.

- **Local message search, including encrypted rooms** — a local event index with its own database, plus advanced filters: `from:`, `mentions:`, `has:(image|video|audio|file|sticker)`, `before:`/`after:` dates, and quoted exact-substring matching.

- **PGP encryption** — opt-in PGP encrypt/decrypt over otherwise-unencrypted rooms via OpenKeychain, with an `/encrypt` command.

- **Multi-account switcher** — switch between multiple logged-in accounts.

- **SchildiChat themes & message bubbles** — SchildiChat Light/Dark/Black themes and opt-in message bubbles (None / Both sides / Same side) with configurable corner roundness, an optional tail, and accent tinting of your own bubbles; timestamps shown inline in the bubble and overlaid on images/videos.

- **SchildiChat layout & behaviour options** — a combined people+rooms Overview list, mark chats as read/unread (MSC2867) synced with compatible clients, URL previews in encrypted rooms, opening a room at its first unread message, jump-to-bottom when sending, remembered collapsed list sections, and showing/hiding space members as people.

- **Media captions & replies** — add/edit/remove captions on media, reply to or comment alongside media, rich reply previews with embedded image/video/sticker thumbnails, and the ability to reply to and redact non-message events (reactions, joins, leaves, redactions).

- **Room & profile banners (MSC4221 / MSC4427)** — Discord-style banner images on room and user profile pages (2.8:1, avatar overlapping, tap to view full-screen), settable from room settings and account settings, with a per-room override of your own banner in the room's personalization tab; banner changes show as timeline notices. Interoperable with the Haven element-web patchset.

- **Message pinning** — pin and unpin messages, sorted by most recent, with a pinned-messages banner (toggleable).

- **Message forwarding** — forward messages (using their most recent edit), with a custom Forward icon.

- **Mass redactions** — bulk redaction via a `/massredact` command (with cooldown); redacting a message also redacts its edits and reactions and is applied live to open timelines, "remove" is renamed to "redact", and there's a toggle to skip the confirmation dialog.

- **Read receipts** — private read receipts, a toggle for sending them at all, and queued receipts that retry until the server confirms.

- **Intentional Mentions (MSC3952)** — proper support, plus mention-rendering improvements (mentions backed by a single character rather than the full display name).

- **Timeline polish** — consecutive hidden events collapse into a single "N hidden events" tile, overhauled state-change notices, room-list previews that reflect message edits, an always-show-timestamps option, and a jump-to-present button that returns to the message you jumped from.

- **Direction-override (RLO) spoofing protection** — hostile Unicode direction-override characters in display names, messages, mention pills, and room names no longer flip the surrounding text backwards (a trick used to spoof user IDs and file extensions); they now show as a visible placeholder box instead. Genuine right-to-left text is unaffected.

- **Metadata stripping on upload** — sent photos and videos no longer leak embedded metadata (GPS location, capture timestamps, camera make/model & serial numbers, and the hidden EXIF thumbnail). JPEG, PNG and WebP are scrubbed losslessly while keeping display orientation; formats that can't be scrubbed in place (e.g. HEIC) are re-encoded; videos are re-muxed to drop their location atoms without re-encoding; and images sent through the file picker, as well as profile/room avatars and banners, are covered too. Controlled by a new "Remove metadata from sent media" toggle in Settings → Security & Privacy → Media and avatars (on by default).

- **Media hiding** — hide media, and inline images/emoji, in the timeline until tapped.

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

- **Misc improvements** — randomizable upload filenames, first-frame video thumbnails, toggleable app shortcuts, display of custom power levels, WebView SSL-error tolerance, an overhauled jump-to-latest button, and room-list preview polish.

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

- **Dependency & build slimming** — dropped the WYSIWYG composer, Sentry, and the unused JNA dependency; vendored markwon-html; bumped conscrypt; and enabled optimization for libopus.

## Significant bugfixes

- Fixed editing a just-sent message silently doing nothing if it was still sending.

- Fixed link previews, images, GIFs and reply previews flashing or reloading at the moment a sent message is confirmed by the server (and blurhash fades replaying while sending).

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
