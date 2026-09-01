# Voyage Changelog

New features, improvements, and notable removals in this fork.

## Features & improvements

- **Faster, freeze-free app**: media uploads no longer block the UI, tab-switching and message-sending are quicker, syncs on launch and idle-return are faster, and timelines and the room list are cached.

- **Background-sync battery fix**: polling is gated on permissions and runs off a single alarm chain with a bounded retry loop, instead of draining the battery overnight.

- **Room previews**: a public room's timeline opens before you join it, from the room directory or a link. You can scroll back through history, search it, and browse the room's profile, members and media gallery, all read-only with a join bar at the bottom. Nothing is stored locally until you join.

- **Invite previews**: an invite to a world-readable room shows the actual conversation with Accept/Decline underneath instead of a blank invite screen, and an invite back to a room you were kicked or banned from previews your retained copy of the history the same way.

- **Watch rooms without joining**: `/watch` follows any world-readable room from a "Watching" entry in the sidebar drawer, as a live read-only preview your account never enters. The watch list lives in account data, so it follows you across devices. `/unwatch` or a long-press stops watching.

- **Historical rooms**: being kicked or banned no longer wipes the conversation. The room stays open read-only with a banner saying who removed you and why, and moves to a "Historical" entry in the sidebar drawer instead of vanishing from All Chats. History stays readable and searchable up to the moment you were removed, survives re-login, and can be forgotten for good with a long-press.

- **Upgraded rooms stay in your list**: when a room is upgraded to a new version, the old room is no longer hidden. It keeps the history that never moved across, and stays reachable from the room list.

- **Room knocking**: request access to rooms that require it, including rooms that combine both rules (MSC3787), where members of a chosen space join directly and everyone else asks to join. Room settings offer it and the room preview shows the right action. Join-rule changes also read correctly in the timeline for knock and restricted rooms, which previously showed nothing at all.

- **Room creation**: an overhauled room-creation wizard, with a per-room Personalization page alongside it.

- **Space creation like room creation**: creating a space no longer starts with an opaque public/private choice. It is one form with the same controls a room gets: a space-access setting (invite only, ask to join, or public), encryption, and an advanced section for room version, your own power level, custom initial state and blocking other servers.

- **Tombstoning**: an overhauled room-tombstoning flow, driven by `/tombstone`.

- **Spaces improvements**: view a space's own timeline, show all rooms in Home by default, and a spaces drawer replacing the new UI's custom spaces view.

- **Custom room-list sections**: group rooms into named sections of your own, which appear in the room list and can be created, renamed, reordered and removed in place. They are the same sections Element Web offers, and stay in sync with it.

- **Room tags**: tag support for rooms.

- **Filter the room directory by type (MSC3827)**: search rooms only, spaces only, or both, from the directory's overflow menu.

- **"Kick", not "remove"**: the action to remove a member from a room is labeled "kick" rather than the vaguer "remove".

- **Local message search, including encrypted rooms**: a local event index with its own database, plus filters for `from:`, `mentions:`, `has:(image|video|audio|file|sticker|poll|link)`, `before:`/`after:` dates, and quoted exact-substring matching. The search bar suggests filters as you type, completes `from:`/`mentions:` from the room's members as mention pills, and results carry the same long-press actions as the timeline.

- **Message translation**: translate any received message (and untranslate it again), and translate outgoing messages with `/translate`, a `$lang` message prefix, or a per-room auto-translate mode. Translation runs on-device by default (Meta's NLLB-200 model, [downloaded once from RTranslator's releases](https://github.com/niedev/RTranslator/releases/tag/2.0.0), ~1 GB, Android 7+), with Google, Microsoft, DeepL, DeepSeek and OpenAI-compatible web engines as alternatives.

- **Message forwarding**: forward messages, using their most recent edit. Pick as many rooms as you like from the room picker and send to all of them at once; the same picker handles content shared into the app from other apps. Forwards carry metadata about the message they came from (MSC2723), so they are labeled in the timeline with the original sender and date, and tapping the label opens the original, offering to join the room if you are not in it. Forwarding out of a DM omits that metadata, and long-pressing the send button omits it from anywhere.

- **Message pinning**: pin and unpin messages, sorted by most recent, with a pinned-messages banner you can turn off.

- **Mass redactions**: bulk redaction via `/massredact`, with a cooldown and an optional `from:`/`until:` time range. Redacting a message also redacts its edits and reactions and applies live to open timelines, "remove" is renamed to "redact", and the confirmation dialog can be skipped.

- **Redact on kick/ban**: kicking or banning someone offers to redact everything they sent in the room, from the member's profile or as `/kick @user:server redact`.

- **Consistent deleted-message previews**: a deleted message reads as deleted everywhere it is previewed, not only in the timeline. Reply headers, the composer's reply preview, the room list, the pinned-messages banner and list, and the long-press menu all show it grayed out with a trash icon rather than as ordinary text, and thread summaries gray it out too. The wording is unified on "Message redacted".

- **Classic composer**: the message composer goes back to the flat layout it had before 2020. No rounded input box, a divider above it, accent-colored glyphs, a bare `+` for the share options, the emoji toggle outside the text box, and a plain paper-plane send button. On by default; turn it off under Settings → Preferences for the boxed composer.

- **Slash commands**: added `/jumpto`, `/jumptostart`, `/jumptodate`, `/converttodm`, `/converttoroom`, `/blockquote`, `/greentext`, `/html`, `/massredact`, `/tombstone`, `/download`, `/encrypt`, and `/trans`/`/transme` for trans-flag gradient messages, plus the ability to run slash commands on a reply or an edit. `/rainbow` paints nheko's vivid gradient instead of washed-out CIELAB colors.

- **Sed substitutions**: maubot-style `s/typo/fixed/` built into the composer, so no bot has to be in the room. Your own messages are corrected as an edit, everyone else's with a notice reply. Replying to a message aims the substitution at it.

- **Intentional Mentions (MSC3952)**: proper support, plus mention-rendering improvements, with mentions backed by a single character rather than the full display name.

- **Frecency-ranked @-mentions**: the `@`-autocomplete lists the people you mention most often in a room first, instead of alphabetically. The per-room counts are backed up to account data, so the ranking follows you across devices.

- **Selectable message & topic text**: select text directly from timeline messages. Double-tap starts a selection anywhere, long-press on a code block or inline code starts one locked to that code (Select all expands it to the whole message), links and plain text keep their long-press actions, and the selection menu is trimmed to Copy, Share and Select all. The room profile topic is selectable the same way, replacing long-press-to-copy.

- **Rich room topics (MSC3765)**: room and space topics support formatted content. Their HTML body renders like timeline messages, falling back to markdown when a topic is plain text only, and editing a topic publishes the HTML rendering alongside the plain text so other clients can show it too. Room IDs, aliases and user IDs in a topic show as tappable pills, where previously only the homeserver part of an alias was a link, and in the room profile a matrix link opens the room or user in-app while other links open in the browser.

- **Link previews that work in encrypted rooms (MSC4095)**: messages you send carry the preview of their links with them, so nobody's homeserver ever sees what you linked. Your own device reads the page, and where that happens is configurable per account and per room, down to letting your homeserver generate previews the old way. Previews received this way display in encrypted rooms with no setting to turn on.

- **Read receipts**: private read receipts, a toggle for sending them at all, and queued receipts that retry until the server confirms, so they no longer desync from what the server holds.

- **Auto-dismiss "Jump to unread"**: an optional mode where reaching the end of the timeline, by opening the room at the bottom or scrolling down to it, dismisses the banner and marks the room read instead of leaving it up.

- **Media galleries (MSC4274)**: send several photos, videos, files and audio as a single message that renders as a grid of thumbnails with a caption underneath. Each tile opens, saves, shares and forwards on its own, the whole message can be saved at once, and every item appears individually in the room's media viewer and Uploads tab. Galleries from other clients always display; sending them is opt-in under Settings → Labs.

- **Attachments send before the upload finishes (MSC2246)**: a photo or video counts as sent as soon as the message itself reaches the server, with the bytes following behind, so a large video no longer holds the message mid-send. Recipients see the message straight away and the media fills in. Falls back to upload-then-send on servers without support.

- **Media captions & replies**: add, edit and remove captions on media, reply to or comment alongside media, rich reply previews with embedded image, video and sticker thumbnails, and replies to and redactions of non-message events such as reactions, joins, leaves and redactions.

- **Captions in the attachment preview**: write a caption for what you are sending from the preview screen itself, one per attachment, or a single caption for the ones going out as a gallery.

- **Edit the media in a message**: replace the photo, video, file or sticker you sent, or give a message media it never had. Every version stays in the message's edit history, where each can be opened and saved.

- **Image editor before sending**: crop, rotate and black out parts of a photo from the attachment preview, with pinch-zoom and panning. Censor boxes are drawn onto the image itself, so nothing recoverable is left behind. Edits are remembered per attachment, so reopening the editor adjusts what you did rather than starting again from the flattened result.

- **Video editor before sending**: trim a video on a filmstrip timeline with draggable handles, and crop, rotate, reverse it, or set its volume anywhere from silent to 500%. Holding a handle zooms the timeline to a per-frame ruler for exact cuts. Trimming is lossless wherever it can be, re-wrapping rather than re-encoding, and falls back to re-encoding only when the cut lands between keyframes. Playback speed runs from 0.1x to 3x, with the audio either following the speed as tape does or holding its original pitch. Sound an mp4 can't carry, such as Opus or Vorbis from a downloaded webm, is re-encoded to AAC rather than dropped.

- **Animated image editor**: GIF, APNG and animated WebP can go through the video editor before sending, to trim, crop, rotate or change their speed frame by frame. The result is always written as an animated WebP, the one animated format the app can both write and display everywhere.

- **Audio preview & editor before sending**: sound files stop at the attachment preview instead of uploading straight away, showing a SoundCloud-style waveform you can swipe through, with the file's cover art blurred behind it and its title and artist above. The editor trims on that waveform and can change the speed, set the volume from silent to 500%, or play the whole thing backwards. A plain trim is copied rather than re-encoded, so it is near-instant and costs no quality.

- **Compression controls before sending**: set the quality and the exact output resolution of a photo or video from the attachment preview, with width and height linked to the source aspect ratio until you break the link. The preview reshapes to what will be sent, and the editor opens on that same shape.

- **Video playback controls**: Telegram-style playback in the full-screen viewer and the attachment preview, with an on-video play/pause button, controls that hide themselves during playback, double-tap on either side to jump 10 seconds, a frame preview while scrubbing, and a millisecond seekbar with elapsed and total time. Videos loop unless the new "Loop videos" setting is off, and a video's length shows on its timeline thumbnail. The overflow menu sets playback from 0.1x to 3x with the pitch either following or holding, and the volume from silent to 500%, boosting the sound above its own loudness rather than scaling it. Speed and volume belong to that video and reset when you swipe away.

- **Media viewer with pinch-to-zoom**: a reworked image and video viewer with pinch-to-zoom on still images, animated images and videos, an overhauled compression pipeline that compresses by the shorter side so long media isn't squished, and correct thumbnail stubs during upload.

- **Voice messages overhaul**: an Opus decoder, playback of audio while it still uploads, scheduled playback for not-yet-downloaded audio, and a processing-stage indicator when sending.

- **Music in the timeline**: an audio message shows its embedded cover art blurred behind the message, with the track title and artist in place of the file name. Read from the file itself and kept, so it appears at once the next time.

- **JPEG XL images**: `.jxl` images sent by other clients display in the timeline and the media viewer, and you can send them yourself. They are recognized as images rather than plain files, so they get a preview, a blurhash and correct dimensions, and can go through the image editor. Sending at original size keeps the file byte-for-byte; compressing re-encodes to WebP like any other format. Needs Android 5.0 or later; below that a `.jxl` still sends fine as a file attachment, it just can't be displayed.

- **Blurhash placeholders**: images and videos show a compact blurred preview while they load, and as the placeholder for hidden media, instead of a blank box.

- **Animated images are marked as such (MSC4230)**: a sent image records whether it actually animates instead of leaving clients to guess from the file type, so animated WebP and APNG get the play badge that only GIFs used to.

- **Faster sending for large videos**: a video sent at original size is rewritten in a single pass straight from the source instead of being copied and then rewritten in full.

- **Custom emoticons & stickers (MSC2545 image packs)**: send custom emoji and stickers, author your own packs, import and export them as Misskey-style zip archives, react with emoticons, and use them in your profile biography.

- **SchildiChat themes & message bubbles**: SchildiChat Light/Dark/Black themes and opt-in message bubbles (None / Both sides / Same side) with configurable corner roundness, an optional tail, and accent tinting of your own bubbles. Timestamps sit inline in the bubble and overlay images and videos.

- **SchildiChat layout & behavior options**: a combined people+rooms Overview list, mark chats as read/unread (MSC2867) synced with compatible clients, URL previews in encrypted rooms, opening a room at its first unread message, jump-to-bottom when sending, remembered collapsed list sections, and showing or hiding space members as people.

- **Markdown & HTML rendering overhaul**: added or improved tables (with a no-wrap option), blockquotes, spoilers, greentext, code blocks, underline (`__x__`), strikethrough (`~~x~~`), subscript (`~x~`) and superscript (`^x^`). Links and pills no longer render inside code blocks.

- **Greentext**: quote-style greentext rendering, with an option to send all blockquotes as greentext.

- **Emoji font options**: render emoji with bundled Twemoji, the system emoji font, or a custom emoji font you supply. Emoji autocomplete can be turned off.

- **Configurable reactions**: quick reactions that sync across your devices, a compact quick-reactions layout, remote sync of frequent emoji, and freeform reactions by typing any text.

- **Configurable avatars**: configurable avatar shapes, avatar-hiding options in the timeline and on invites, avatar removal, an empty-display-name fallback, and full-screen avatar zoom through the media viewer.

- **Pick which color palette names and avatars use**: separate choices for people and for rooms, each offering Element's palettes from 2015, 2018, 2020 and today, previewed swatch by swatch. People can also be set to None, which leaves names in plain text and dims message bodies so they still stand apart.

- **Room & profile banners (MSC4221 / MSC4427)**: Discord-style banner images on room and user profile pages (2.8:1, avatar overlapping, tap to view full-screen), settable from room settings and account settings. Banner changes show as timeline notices. Interoperable with the Haven element-web patchset.

- **Status & biography in profiles (MSC4426 / MSC4440)**: set a status (one line, with any leading emoji stored as its emoji field) and a free-form biography in account settings. The status shows under a user's pronouns and time zone; the biography gets its own expandable section on their profile, rendering markdown, links and custom emoji. Both also appear in the user card from a mention. Written under the standard and unstable field keys as well as the ones other clients already read.

- **Pronouns & time zone in profiles (MSC4247 / MSC4175 / MSC4133)**: set your pronouns (common presets or custom text, multiple allowed) and IANA time zone in account settings. A user's pronouns and current time-zone abbreviation show under their name as e.g. `she/her • PST`, DST-aware, and their pronouns gender timeline notices such as "changed **her** avatar". Every profile field is written under both its stable and unstable key and read stable-first, and interoperates with other clients' pronoun schemas.

- **Profile notes (MSC4441)**: keep a private note on any user's profile, written in markdown, visible only to you and synced across your devices. Notes are end-to-end encrypted (MSC4483) by default.

- **Personal room and user overrides (MSC3015, MSC4529)**: rename a room or change its avatar just for yourself, and override any user's display name and avatar everywhere they appear. User overrides are end-to-end encrypted (MSC4483) by default.

- **Profile name colors (MSC4522)**: choose the color your name and avatar are shown in, for your account or per room, from a palette or any custom color. Other users' colors are shown too, and can be overridden just for you from their profile.

- **Force display name & avatar**: override display name and avatar per room and per group DM.

- **Mutual Rooms in profiles**: a user's profile has a Mutual Rooms button opening a compact list of the rooms you share, grouped under their spaces and with DMs included. Tap a room to open it, or a space to filter the room list to it.

- **Stealth mode**: keep this fork's own client-specific settings on your device instead of in account data, so a homeserver administrator can't use your choice of client to de-anonymize you. Opt-in per account.

- **VPN protection**: opt-in warnings when your VPN is off. A full-screen warning blocks all network activity until you confirm, switching accounts asks first, and a per-account list decides which accounts are protected.

- **Metadata stripping on upload**: sent photos and videos no longer leak embedded metadata, including GPS location, capture timestamps, camera make, model and serial numbers, and the hidden EXIF thumbnail. JPEG, PNG and WebP are scrubbed losslessly while keeping display orientation, formats that can't be scrubbed in place such as HEIC are re-encoded, and videos are re-muxed to drop their location atoms without re-encoding. Images sent through the file picker, plus profile and room avatars and banners, are covered too. Stripping metadata and randomizing uploaded file names are each a three-way choice of always, never, or only in public rooms, and each room can override either of them.

- **Direction-override (RLO) spoofing protection**: hostile Unicode direction-override characters in display names, messages, mention pills and room names no longer flip the surrounding text backwards, a trick used to spoof user IDs and file extensions. They show as a visible placeholder box instead. Genuine right-to-left text is unaffected.

- **PGP encryption**: opt-in PGP encrypt/decrypt over otherwise-unencrypted rooms via OpenKeychain, with an `/encrypt` command. If you want that. For *some* reason.

- **Share encrypted history on invite (MSC4268)**: invite someone to an encrypted room whose history is visible to members, and they can read the messages sent before they joined.

- **Encrypted account data (MSC4483)**: the developer account-data browser shows encrypted entries decrypted, edits them in decrypted form, and creates new ones encrypted, with a Raw toggle for the ciphertext.

- **Block all room invites (MSC4380)**: one switch has your homeserver reject every invite sent to you, on all your devices at once. Requires server support.

- **Hideable message shields**: toggles to hide the gray key-backup shield, on messages decrypted with a key restored from secure backup, and the red encryption-warning shield, on unencrypted messages in encrypted rooms or messages from unverified, unknown or deleted sessions. Reactions and redactions, which are always sent unencrypted, no longer get a red shield in encrypted rooms.

- **Identity-change banner**: backported from Element Web. A banner at the top of an encrypted room warns when a member's cross-signing identity changes, in red for someone you had previously verified. Dismissing it, or "Withdraw verification" for the verified case, pins their current identity, so it only reappears if their identity resets again. Identity pinning is tracked in the crypto store, and a toggle can hide the banner outright while still accepting any current changes.

- **Media hiding**: hide media, and inline images and emoji, in the timeline until tapped. The media-preview and invite-avatar settings live on your account rather than only on the device that set them (MSC4278), so a new sign-in keeps the choices you already made and Element Web and Element X read the same setting, and either can be overridden per room.

- **Ignored users fully silenced**: read receipts and presence from ignored users are dropped during sync, alongside the typing notifications already filtered.

- **Multi-account switcher**: switch between multiple logged-in accounts.

- **Token sign in**: a "Token Sign In" option alongside Create account and Sign in takes an access token you already hold instead of a password.

- **Local sign out**: long-press Sign out to remove an account from the app without telling the homeserver. The session stays active server-side until you remove it yourself.

- **Homeserver mirrors**: the homeserver entry in settings is an editable, reorderable list. Add mirrors of your homeserver (alternate domains, a reverse proxy, an onion address) and the app falls back to the next one whenever the current one can't be reached or answers with a gateway error. Your ordering is never rewritten. Mirrors above the one in use are rechecked every few minutes while the app is open, or on demand, and the app moves back up as soon as one is reachable.

- **Misc improvements**: long room-topic changes shortened to a single timeline notice, View Profile Source alongside View Membership Source on user profiles in developer mode, first-frame video thumbnails, toggleable app shortcuts, display of custom power levels, WebView SSL-error tolerance, an overhauled jump-to-latest button, and MSC references like "MSC1234" in messages tappable as links to the spec proposal. The media viewer gains a "Show in chat" action that jumps back to the message an attachment came from, and an "Info" action listing its size, resolution, duration, codecs and embedded metadata. Settings rows no longer reserve an empty icon column, so their text sits as far from the left edge as from the right, and a direct message's room settings show the other person's avatar the way the room list does. Links are no longer drawn underlined where they were left inconsistent, in room, space and directory previews, permalink pills, and the dialogs and banners built from raw HTML.

### Removals

- **Removed calling support (Element Call / Jitsi / WebRTC)**: the entire voice and video calling and Jitsi conferencing stack was dropped, since it blocked old devices and pulled in heavy native dependencies. Call events render inline in the timeline instead.

- **Removed telemetry & reporting**: dropped telemetry, analytics, bug reporting, Sentry, the content-reporting system, sunsetting banners, and the "push notifications are disabled" banner.

- **Removed voice broadcast**: the upstream voice-broadcast feature was dropped.

- **Removed legacy mention matching**: only an explicit mention notifies you. Your display name, your username or the word "@room" in someone's message no longer counts as one, and the mention settings are a single "Messages that mention me" toggle alongside @room and your keywords.

### Branding

- **Rebrand to Voyage**: new app ID, icons, and a configurable logo and app-icon background color, a Voyage color scheme on the splash screen and throughout, an overhauled Help & About screen, and removal of hardcoded Element-green usages. The app opens dark with a cyan accent and a black app icon by default.

- **Accent picker**: a swatch picker for the app's accent color, offering thirty colors including monochrome white and black.

## Under the hood

- **Persistence rewrite, Realm → SQLDelight**: replaced Realm with a custom framework-SQLite driver, which unblocks older Android and desktop, alongside an overhaul of the ignore system.

- **New crypto backend, libce**: replaced vodozemac with the libce submodule, enabling builds for old devices.

- **Performance internals**: SQLite WAL, reactive-layer deduplication, an epoxy-pipeline rework, gated space-hierarchy revalidation, bulk timeline queries replacing per-row N+1s, and a memoized event mapper are what the faster, freeze-free app is built on.

- **Next-generation sync (MSC4525 paginated sync, MSC4186 simplified sliding sync)**: when the homeserver offers either, syncing moves onto it, preferring paginated sync. Responses arrive bounded and room by room instead of in one huge batch, so the app becomes usable sooner on accounts with many rooms. Falls back to the standard sync when the server has neither, and can be turned off under Labs.

- **Modern sync and spec endpoints**: sync asks for room state as of the end of the timeline (MSC4222), so state stops drifting out of date after a gap. A thread's edits and reactions arrive in one request (MSC3981), and reporting (MSC4277), room forgetting (MSC4267), recent emoji, relations and the room directory moved onto their stable identifiers and endpoints. A room upgraded after the fact can also declare what it continues from (MSC3946).

- **Profile updates over sync (MSC4429, MSC4262)**: when the homeserver supports it, other people's status, pronouns, time zone and bio arrive with your sync instead of being fetched one user at a time, so they stay current without the extra requests.

- **ExoPlayer in the media viewer**: full-screen video plays through ExoPlayer where the platform reaches it (API 16+), so a looping video runs through the seam without a gap and playback speed is no longer capped near 2x. Ice Cream Sandwich keeps the platform player. The audio route is also held open while the app is in the foreground on Bluetooth, where an idle sink otherwise costs a second of stalled playback.

- **Dependency & build slimming**: dropped the WYSIWYG composer, Sentry, and the unused JNA dependency; vendored markwon-html; bumped conscrypt; and enabled optimization for libopus.

## Significant bugfixes

- Fixed scrollback in rooms damaged by the Synapse depth exploit, where scrolling back jumped over months or years of history as if it had never existed. Suspicious jumps are now verified against the local search index or the server, and the skipped span is fetched and stitched back into the timeline (labs toggle, on by default).

- Fixed messages from a slow or recovering server showing under the wrong date. A message delivered long after it was sent now sits where it was sent, even when that part of the history has to be loaded first (labs toggle, on by default).

- Fixed rooms that stopped loading history. A fetched page whose boundary token didn't match the one stored was saved unreachable, so the timeline stayed at the handful of messages the last sync had cached until the room was reopened.

- Fixed the app showing stale rooms after being backgrounded, with no sign it was catching up. Returning to the foreground now always starts an immediate sync and shows progress while it runs.

- Fixed logging into a plain-`http://` homeserver, whether self-hosted, a LAN IP or Tor, failing with a cleartext-not-permitted error on Android 6+.

- Fixed attachment sends sticking at "Waiting…" forever when the network dropped or the system reclaimed the upload task, which was treated as a cancellation and killed the send permanently instead of retrying it.

- Fixed media uploads failing with a permission error when the app restarts mid-send. Attachments are copied into app storage before upload, so a resumed upload no longer depends on the picker's expired access grant.

- Fixed message sending growing sluggish the longer the app stays open, with messages queueing up and batching out.

- Fixed editing a just-sent message silently doing nothing if it was still sending.

- Fixed link previews, images, GIFs and reply previews flashing or reloading at the moment a sent message is confirmed by the server, and blurhash fades replaying while sending.

- Fixed a blank timeline after screen rotation.

- Fixed reaction counts drifting: a single reaction showing 2+, or reactions becoming un-clickable, from duplicated local echoes.

- Fixed replies getting scrambled.

- Fixed room v12 support regressions.

- Fixed a client freeze triggered by fast uploads.

- Fixed a space-hierarchy recursion that could crash the app with a stack overflow.

- Fixed a crash on Lollipop.

- Fixed a storage-fill bug.
