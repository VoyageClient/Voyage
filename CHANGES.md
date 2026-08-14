# Voyage — Changelog

New features, improvements, and notable removals in this fork.

## Features & improvements

- **Runs on very old Android, down to Ice Cream Sandwich (API 14)** — brings a modern Matrix client to devices nothing else supports: automatic disabling of pre-KitKat's Dalvik bytecode verifier, an R8-shrunk release build that fits the old LinearAlloc ceiling, and many old-Android UI fixes. Versions of Android that do not support Emoji fall back to bundled Twemoji.

- **Snappier, freeze-free app** — async media uploads that no longer block the UI, faster tab-switching and message-sending, quicker syncs on launch and idle-return, deep timeline/room-list caching, and a dedicated Performance Mode plus performance/logging toggles.

- **Background-sync battery fix** — permission-gated polling and a single alarm chain with a bounded retry loop, replacing overnight battery drain.

- **Custom emoticons & stickers (MSC2545 image packs)** — send custom emoji and stickers, pickers for both, author your own packs, import and export packs as Misskey-style zip archives, and react with emoticons.

- **Keep deleted messages (MSC2815)** — see what a deleted message said. Room moderators and homeserver admins can fetch the original content back from a Synapse server that supports it. Each deleted message can be revealed or re-hidden from its long-press menu. Configurable account-wide under Settings → Security & privacy → Redactions and per room under Personalization: whether to preserve media, how large a download to accept, whether to restrict it to Wi-Fi, and whether the kept content survives clearing the app cache. Preserved media has its own clear actions, account-wide under Settings → General and per room under Personalization.

- **Per-room media visibility** — the media-visibility settings can now be overridden per room from Personalization, so a single room can show or hide media regardless of the account-wide choice.

- **Live room previews** — a public world-readable room now opens as a real, live-updating timeline before you join it, from the room directory or a link, the way Element Web previews rooms. You can scroll back through history, search it, and browse the room's profile, members and media gallery, all read-only, with a join bar at the bottom; nothing is stored locally until you actually join.

- **Historical rooms** — being kicked or banned no longer wipes the conversation. The room stays open read-only if you're in it, with a banner saying who removed you and why (and a Rejoin button after a kick), and it moves to a new "Historical" entry in the sidebar drawer instead of vanishing from All Chats. You can still scroll and search the history up to the moment you were removed, browse the room's profile and members, and load older messages from the server. Historical rooms survive re-login, and a long-press lets you forget one for good. Leaving a room voluntarily still removes it immediately. Rejoining stitches the timeline back together, recovering whatever the room's history-visibility rules let you see of what happened while you were out — always including your own invite.

- **Watch rooms without joining** — a new `/watch` command follows any previewable (world-readable) room from a "Watching" entry in the sidebar drawer: it opens as a live read-only preview without your account ever entering the room. The watch list is stored in account data, so it follows you across devices. `/unwatch` or a long-press stops watching.

- **Invite previews** — an invite to a world-readable room now shows the actual conversation with Accept/Decline underneath instead of a blank invite screen, and an invite back to a room you were kicked or banned from previews your retained copy of the history the same way.

- **Local message search, including encrypted rooms** — a local event index with its own database, plus advanced filters: `from:`, `mentions:`, `has:(image|video|audio|file|sticker)`, `before:`/`after:` dates, and quoted exact-substring matching.

- **Share encrypted history on invite (MSC4268)** — when you invite someone to an encrypted room whose history is visible to members, they can now read the messages sent before they joined. The keys go over as a single encrypted bundle rather than one message per session, so it works on rooms with a long history, and they are only sent to devices the invitee has cross-signed. Messages decrypted this way say who shared the keys, since only that person vouches for who really sent them.

- **PGP encryption** — opt-in PGP encrypt/decrypt over otherwise-unencrypted rooms via OpenKeychain, with an `/encrypt` command.

- **Multi-account switcher** — switch between multiple logged-in accounts.

- **VPN protection** — opt-in warnings when your VPN is off: a full-screen warning that blocks all network activity until you confirm, and a confirmation before switching accounts, with a per-account list of which accounts to protect.

- **Token sign in** — a "Token Sign In" option alongside Create account and Sign in takes an access token you already hold instead of a password. The token is adopted as-is, so no new device or session is created.

- **Local sign out** — long-press Sign out, in the sidebar drawer, in Settings → General, or on an account switcher row, to remove an account from the app without telling the homeserver. The session stays active server-side until you remove it yourself.

- **SchildiChat themes & message bubbles** — SchildiChat Light/Dark/Black themes and opt-in message bubbles (None / Both sides / Same side) with configurable corner roundness, an optional tail, and accent tinting of your own bubbles; timestamps shown inline in the bubble and overlaid on images/videos.

- **SchildiChat layout & behaviour options** — a combined people+rooms Overview list, mark chats as read/unread (MSC2867) synced with compatible clients, URL previews in encrypted rooms, opening a room at its first unread message, jump-to-bottom when sending, remembered collapsed list sections, and showing/hiding space members as people.

- **Classic composer** — the message composer goes back to the flat layout it had before 2020: no rounded input box, a divider above it, and accent-coloured glyphs — a bare `+` for the share options, the emoji/keyboard toggle moved out of the text box, and a plain paper-plane send button instead of a white glyph on a filled circle. The encryption shield returns beside the input, the voice-message mic gets its own slot rather than sharing the send button's, and the composer sits on the toolbar colour so it reads as separate from the timeline. The share-options popout matches it, down to the `+` rotating into the close X. The reply/edit preview slides open and shut again rather than appearing instantly. On by default; turn it off under Settings → Preferences to get the boxed composer back.

- **Steadier share-options toolbar** — the attachment picker is now part of the composer's own layout instead of a floating window placed at fixed screen coordinates, so it can no longer drift out of alignment or be left stranded over the timeline when the keyboard opens or a reply preview appears. It also closes when you start typing or reply to a message.

- **Image editor before sending** — crop, rotate and black out parts of a photo from the attachment preview, with pinch-zoom and panning for precise work. Censor boxes are drawn straight onto the image, so nothing recoverable is left behind. Edits are remembered per attachment, so reopening the editor lets you adjust what you did rather than starting again from the flattened result.

- **Video editor before sending** — trim a video to the part you want from the attachment preview, on a filmstrip timeline with draggable handles, and crop, rotate or mute it. Cropping works exactly like the image editor's — drag the corner handles, pinch and pan — and re-encodes through a GL stage so the result is a genuinely smaller frame. Holding a handle zooms the timeline to a per-frame ruler for exact cuts, with a haptic tick on every frame. Trimming is lossless wherever it can be — the video is re-wrapped rather than re-encoded, which is near-instant and costs no quality — and only falls back to re-encoding when the cut has to land between keyframes. Like the image editor, edits are remembered per attachment and replayed against the original. Playback speed can be set anywhere from 0.1x to 3x on a slider that is finest around normal speed, with the audio either following the speed as tape does or holding its original pitch. Sound an mp4 cannot carry — Opus or Vorbis from a downloaded webm, for instance — is re-encoded to AAC rather than dropped, so an edited clip keeps its audio.

- **Animated image editor** — GIF, APNG and animated WebP can go through the video editor before sending, to trim, crop, rotate or change the speed of them frame by frame. The result is always written as an animated WebP, the one animated format the app can both write and display everywhere.

- **Compression controls before sending** — set the quality and the exact output resolution of a photo or video from the attachment preview, with width and height linked to the source aspect ratio until you break the link. The preview reshapes to what will actually be sent, and the editor opens on that same shape, so what you see before sending is what arrives.

- **Video playback controls** — Telegram-style playback in the full-screen viewer and the attachment preview: an on-video play/pause button, controls that hide themselves during playback, double-tap on either side to jump 10 seconds back or forward, a frame preview while scrubbing, and a smooth millisecond seekbar with elapsed and total time. Videos loop when they finish unless the new "Loop videos" setting is off, and the length of a video is shown on its thumbnail in the timeline. Photos and videos can be pinch-zoomed and panned before sending. The full-screen viewer's overflow menu can set playback anywhere from 0.1x to 3x on the same slider the editor uses, with the pitch either following the speed or holding; the speed belongs to that video and goes back to normal when you swipe away.

- **Media captions & replies** — add/edit/remove captions on media, reply to or comment alongside media, rich reply previews with embedded image/video/sticker thumbnails, and the ability to reply to and redact non-message events (reactions, joins, leaves, redactions).

- **Room & profile banners (MSC4221 / MSC4427)** — Discord-style banner images on room and user profile pages (2.8:1, avatar overlapping, tap to view full-screen), settable from room settings and account settings; banner changes show as timeline notices. Interoperable with the Haven element-web patchset.

- **Mutual Rooms in profiles** — a user's profile has a Mutual Rooms button opening a compact list of the rooms you share, grouped under their spaces (with rounded-square space avatars) and DMs included; tap a room to open it, or tap a space to filter the room list to it.

- **Status & biography in profiles (MSC4426 / MSC4440)** — set a status (typed as one line, with any leading emoji stored as its emoji field) and a free-form biography in account settings. The status shows under a user's pronouns and time zone; the biography gets its own expandable section on their profile, rendering markdown, links and custom emoji. Both also appear in the user card from a mention. Written under the standard and unstable field keys as well as the ones other clients already read.

- **Pronouns & time zone in profiles (MSC4247 / MSC4175 / MSC4133)** — set your pronouns (common presets or custom text, multiple allowed) and IANA time zone in account settings; a user's pronouns and current time-zone abbreviation show under their name on profile pages as e.g. `she/her • PST` (DST-aware), and their pronouns gender timeline notices such as "changed **her** avatar". Every profile field is written under both its stable and unstable key and read stable-first, and interoperates with other clients' pronoun schemas.

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

- **matrix: links (MSC2312)** — `matrix:` URIs to users, rooms and events open in the app, both from other apps and when tapped in a message. `/jumpto` also takes a link now, so it can jump to an event in another room, not just one in the room you're in.

- **Timeline polish** — consecutive hidden events collapse into a single "N hidden events" tile, overhauled state-change notices, room-list previews that reflect message edits, an always-show-timestamps option, and a jump-to-present button that returns to the message you jumped from.

- **Direction-override (RLO) spoofing protection** — hostile Unicode direction-override characters in display names, messages, mention pills, and room names no longer flip the surrounding text backwards (a trick used to spoof user IDs and file extensions); they now show as a visible placeholder box instead. Genuine right-to-left text is unaffected.

- **Metadata stripping on upload** — sent photos and videos no longer leak embedded metadata (GPS location, capture timestamps, camera make/model & serial numbers, and the hidden EXIF thumbnail). JPEG, PNG and WebP are scrubbed losslessly while keeping display orientation; formats that can't be scrubbed in place (e.g. HEIC) are re-encoded; videos are re-muxed to drop their location atoms without re-encoding; and images sent through the file picker, as well as profile/room avatars and banners, are covered too. Controlled by a new "Remove metadata from sent media" toggle in Settings → Security & Privacy → Visual (on by default).

- **Media hiding** — hide media, and inline images/emoji, in the timeline until tapped.

- **Hideable message shields** — toggles in Settings → Security & Privacy → Visual to hide the grey key-backup shield (messages decrypted with a key restored from secure backup) and the red encryption-warning shield (unencrypted messages in encrypted rooms, or messages from unverified/unknown/deleted sessions); reactions and redactions, which are always sent unencrypted, no longer get a red shield in encrypted rooms.

- **Identity-change banner** — backported from Element Web: a banner at the top of an encrypted room warns when a member's cross-signing identity changes, shown in red for someone you had previously verified. Dismissing it (or "Withdraw verification" for the verified case) pins their current identity, so it only reappears if their identity resets again. Identity pinning is tracked in the crypto store, and a Settings → Security & Privacy → Visual toggle can hide the banner outright while still accepting any current changes.

- **Voice messages overhaul** — an Opus decoder, playback of audio while it still uploads, scheduled playback for not-yet-downloaded audio, and a processing-stage indicator when sending.

- **Media viewer with pinch-to-zoom** — a reworked image/video viewer that supports pinch-to-zoom on still images, animated images (GIFs / animated WebP), and videos, an overhauled compression pipeline (compress by the shorter side so long media isn't squished), and correct thumbnail stubs during upload.

- **JPEG XL images** — `.jxl` images sent by other clients now display in the timeline and the media viewer, and you can send them yourself: they are recognised as images rather than plain files, so they get a preview, a blurhash and correct dimensions, and can go through the image editor. Sending at original size keeps the file byte-for-byte; compressing re-encodes to WebP like any other format. Needs Android 5.0 or later — below that a `.jxl` still sends fine as a file attachment, it just can't be displayed.

- **Blurhash placeholders** — images and videos show a compact blurred preview while they load, and as the placeholder for hidden media, instead of a blank box.

- **Markdown & HTML rendering overhaul** — add/improve tables (with a no-wrap option), blockquotes, spoilers, greentext, code blocks, underline (`__x__`), strikethrough (`~~x~~`), subscript (`~x~`), and superscript (`^x^`) tags; links and pills no longer render inside code blocks.

- **Greentext** — quote-style greentext rendering, with an option to send all blockquotes as greentext.

- **Slash commands** — added `/jumpto`, `/jumptostart`, `/jumptodate`, `/converttodm`, `/converttoroom`, `/blockquote`, `/greentext`, `/html`, `/massredact`, `/tombstone`, `/download`, `/encrypt`, and `/trans`/`/transme` (trans-flag gradient messages), plus the ability to run slash commands on a reply; `/rainbow` now paints nheko's vivid gradient instead of washed-out CIELAB colors.

- **Sed substitutions** — maubot-style `s/typo/fixed/` built into the composer, so no bot has to be in the room: your own messages are corrected as an edit, everyone else's with a notice reply. Replying to a message aims the substitution at it.

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

- **Attachments send before the upload finishes (MSC2246)** — a photo or video counts as sent as soon as the message itself reaches the server, with the bytes following behind, so a large video no longer holds the message mid-send. Recipients see the message straight away and the media fills in. Falls back to upload-then-send on servers without support.

- **Faster sending for large videos** — a video sent at original size is now rewritten in a single pass straight from the source instead of being copied and then rewritten in full, and shows real progress while it works.

- **Animated images are marked as such (MSC4230)** — a sent image now records whether it actually animates instead of leaving clients to guess from the file type, so animated WebP and APNG get the play badge that previously only GIFs could.

- **Block all room invites (MSC4380)** — one switch under Settings → Security & privacy has your homeserver reject every invite sent to you, on all your devices at once. Requires server support.

- **Media visibility follows your account (MSC4278)** — the media-preview and invite-avatar settings are stored on your account rather than only on the device that set them, so a new sign-in keeps the choices you already made and Element Web and Element X read the same setting.

- **Key backup choice follows your account (MSC4287)** — turning key backup on or off is remembered account-wide, so a new device stops prompting you to set up a backup you declined elsewhere.

- **Knocking on restricted rooms (MSC3787)** — a room can now combine both rules: members of a chosen space join directly, everyone else asks to join. Room settings offer it and the room preview shows the right action. Join-rule changes also read correctly in the timeline for knock and restricted rooms, which previously showed nothing at all.

- **Filter the room directory by type (MSC3827)** — search rooms only, spaces only, or both, from the directory's overflow menu.

- **Disclosure sections in messages (MSC2184)** — a `<details>` section renders as a titled, indented block with its summary in bold instead of running title and body together. It is always shown rather than folded away, since a collapsed state can't be held safely per message in a scrolling timeline.

- **Misc improvements** — long room-topic changes shortened to a single timeline notice, View Profile Source alongside View Membership Source on user profiles in developer mode, randomizable upload filenames, first-frame video thumbnails, toggleable app shortcuts, display of custom power levels, WebView SSL-error tolerance, an overhauled jump-to-latest button, a "Show in chat" action in the media viewer that jumps back to the message an attachment came from, an "Info" action there listing an attachment's size, resolution, duration, codecs and embedded metadata, room-list preview polish, a direct message's room settings showing the other person's avatar as the room list does, MSC references like “MSC1234” in messages tappable as links to the spec proposal, and links no longer drawn underlined anywhere they were left inconsistent — room, space and directory previews, permalink pills, and the dialogs and banners built from raw HTML.

### Removals

- **Removed calling support (Element Call / Jitsi / WebRTC)** — the entire voice/video calling and Jitsi conferencing stack was dropped (it blocked old devices and pulled in heavy native deps); call events are now rendered inline in the timeline instead.

- **Removed telemetry & reporting** — dropped telemetry, analytics, bug reporting, Sentry, the content-reporting system, sunsetting banners, and the "push notifications are disabled" banner.

- **Removed voice broadcast** — the upstream voice-broadcast feature was dropped.

### Branding

- **Rebrand to Voyage** — new app ID, icons, and a configurable logo and app-icon background colour, a Voyage colour scheme on the splash screen and throughout, an overhauled Help & About screen, and removal of hardcoded Element-green usages.

- **Accent picker** — a swatch picker for the app's accent color, plus an optional "ugly" username color palette. The picker offers thirty colors including monochrome white and black accents.

## Under the hood

- **Persistence rewrite: Realm → SQLDelight** — replaced Realm with a custom framework-SQLite driver (unblocking older Android and desktop), alongside an overhaul of the ignore system.

- **New crypto backend: libce** — replaced vodozemac with the libce submodule, enabling builds for old devices.

- **Reads off the database write thread** — the timeline and the room list each read on their own thread, so opening a room or refreshing the list no longer waits for a sync response to finish being written.

- **ExoPlayer in the media viewer** — full-screen video plays through ExoPlayer where the platform reaches it (API 16+), so a looping video runs through the seam without a gap and playback speed is no longer capped near 2x. Ice Cream Sandwich keeps the platform player. The audio route is also held open while the app is in the foreground on Bluetooth, where an idle sink otherwise costs a second of stalled playback.

- **Performance internals** — SQLite WAL, reactive-layer deduplication, an epoxy-pipeline rework, gated space-hierarchy revalidation, bulk timeline queries replacing per-row N+1s, and a memoized event mapper underpin the user-facing speedups above.

- **Modern sync and spec endpoints** — sync asks for room state as of the end of the timeline (MSC4222), so state stops drifting out of date after a gap; a thread's edits and reactions arrive in one request (MSC3981); and reporting (MSC4277), room forgetting (MSC4267), relations and the room directory moved onto their stable endpoints.

- **Dependency & build slimming** — dropped the WYSIWYG composer, Sentry, and the unused JNA dependency; vendored markwon-html; bumped conscrypt; and enabled optimization for libopus.

## Significant bugfixes

- Fixed attachment sends sticking at "Waiting…" forever when the network dropped or the system reclaimed the upload task, which was treated as a cancellation and killed the send permanently instead of retrying it.

- Fixed media uploads failing with a permission error when the app restarts mid-send — attachments are now copied into app storage before upload, so a resumed upload no longer depends on the picker's expired access grant.

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
