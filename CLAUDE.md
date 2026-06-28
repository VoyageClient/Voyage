# Platform support (Ice Cream Sandwich / API 14)

This is a fork that targets Ice Cream Sandwich (API 14). Any new feature or code you add MUST run on API 14 — either natively or with an explicit fallback. Never introduce an unconditional dependency on an API that didn't exist at 14 without guarding it.

- Before using a platform API, check its `@RequiresApi` / added-in level. If it's above 14, gate it with `Build.VERSION.SDK_INT` and provide a working path for API 14. A feature that silently no-ops on 14 is not acceptable unless that degradation is deliberate and documented in the code comment.
- Prefer AndroidX/compat wrappers (e.g. `ContextCompat`, `ViewCompat`, `HtmlCompat`) and desugared `java.time`/NIO over raw framework calls, since those already backport behavior to 14.
- Where it costs little, write code so it also works below 14 (down to the lowest the dependency allows) — choose the broadest-compatible API rather than the newest convenient one.
- Don't bump `minSdk`, and don't pull in a library whose own `minSdk` exceeds 14. AndroidX raised its floor 14→19 in Oct 2023 (and 19→21 in 2024), so any new AndroidX artifact must stay on its last minSdk-14 release; the `resolutionStrategy.force` block in the root `build.gradle` pins the stack accordingly.
- When a feature genuinely can't work on 14, the higher-API branch must be isolated behind a version check and the 14 branch must still leave the app usable.

# Strings

New strings always go into `library/ui-strings/src/main/res/values/donottranslate.xml` with `translatable="false"`. Do not add them to `strings.xml` — that file is the source for translation pipelines and stale entries cause AAPT warnings ("removing resource X without required default value") across every locale.

# Comments

Default to no comment when writing code. Only write one when the WHY is non-obvious (hidden constraint, upstream-bug workaround, surprising behavior). Don't narrate what the code does or restate the diff in code comments. Identifiers and types already say what; comments are only for what they can't. No multi-paragraph kdoc on internal helpers. Note that this does not apply to dialogue, please do describe what changes you are making.

# Home / room-list layouts

There are TWO room-list layouts, gated by `SETTINGS_LABS_NEW_APP_LAYOUT_KEY` (`isNewAppLayoutEnabled()`):

- Legacy (flag off): `HomeDetailFragment` → `RoomListFragment` → `RoomListViewModel` + `RoomListSectionBuilder` (sectioned list, e.g. People/DMs, Rooms, Favourites).
- New (flag on): `NewHomeDetailFragment` → `HomeRoomListFragment` → `HomeRoomListViewModel` (single filtered list).

Any change to room-list behavior (display, sorting, refresh, item rendering) MUST be implemented for BOTH paths, or it will silently do nothing on whichever layout the user runs. Don't assume one layout.

# Building

The command you should use to build or install should always be ./gradlew :vector-app:installDebug so please do not use anything else. (The old `gplay`/`fdroid` product flavors were removed — the fork is F-Droid-only — so there is no longer an `installFdroidDebug` task; the source set merged into `src/main`.)

To quickly check that code compiles without building/installing the whole app (no device needed), use ./gradlew :vector:compileDebugKotlin.

# Debugging on device

The installed fdroid-debug package is `im.voyage.app.debug` (NOT `im.vector.app.debug`). Use that for `am start`, `pidof`, logcat filters, etc.

The app takes ~45 seconds to start. When launching it (e.g. to read logs after an install), always wait at least 45s before checking for output.

NEVER take device screenshots (no `adb screencap`, no `adb exec-out screencap`, no driving the UI to capture a screen) unless the user explicitly asks for one in that message. To verify behaviour, prefer reading logcat; let the user drive the UI and trigger flows themselves.

While debugging, if you are unsure of what could be causing a particular problem, do not make blind guesses unless there is a high likelihood you are correct. You should feel free to make guesses on the first or second attempt, but if you still have not resolved the issue then you should add as much logging as possible to every part of the program to find out the exact cause for something. This is primarily necessary when debugging UI-related problems, and may not be as useful in other contexts.

NEVER remove temporary debug-related logging until either the problem has been resolved, and/or you were asked to review the changes.

# Reviewing changes

When asked to review, review the entire diff since the last git commit — not just the most recent edit. Go through all of it and check for: dead or unreachable code, stale/unnecessary/narrating comments, bugs and logic errors, and anything that would break on the minimum supported API (currently Ice Cream Sandwich / API 14) — verify it genuinely runs there, not just that it compiles. Don't only report problems: if you spot improvements worth making to the changed code, make them.

Also during review, compact overly verbose comments down to the minimal non-obvious WHY. And delete comments that only make sense relative to uncommitted history — i.e. notes explaining a fix for a problem we introduced earlier in this same uncommitted batch, or contrasting against "how this used to be handled" when that prior state was never committed. To an outside observer reading the committed code fresh, such comments are meaningless; the code should read as if it was always written this way.

# Changelog

Never write changelog entries to any file (no `changelog.d/` fragments, no `CHANGES.md` — that towncrier setup predates this fork and is unused). The changelog lives only in the commit message: a concise imperative subject line followed by a body describing the changes. Every body item MUST start with `- ` — NEVER write a paragraph that does not begin with `- `. Put a blank line between each `- ` entry.
