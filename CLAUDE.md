# Platform support (KitKat / API 19)

This is a fork that targets KitKat (API 19). Any new feature or code you add MUST run on API 19 — either natively or with an explicit fallback. Never introduce an unconditional dependency on an API that didn't exist at 19 without guarding it.

- Before using a platform API, check its `@RequiresApi` / added-in level. If it's above 19, gate it with `Build.VERSION.SDK_INT` and provide a working path for API 19. A feature that silently no-ops on 19 is not acceptable unless that degradation is deliberate and documented in the code comment.
- Prefer AndroidX/compat wrappers (e.g. `ContextCompat`, `ViewCompat`, `HtmlCompat`) and desugared `java.time`/NIO over raw framework calls, since those already backport behavior to 19.
- Where it costs little, write code so it also works below 19 (down to the lowest the dependency allows) — choose the broadest-compatible API rather than the newest convenient one.
- Don't bump `minSdk`, and don't pull in a library whose own `minSdk` exceeds 19.
- When a feature genuinely can't work on 19, the higher-API branch must be isolated behind a version check and the 19 branch must still leave the app usable.

# Strings

New strings always go into `library/ui-strings/src/main/res/values/donottranslate.xml` with `translatable="false"`. Do not add them to `strings.xml` — that file is the source for translation pipelines and stale entries cause AAPT warnings ("removing resource X without required default value") across every locale.

# Comments

Default to no comment when writing code. Only write one when the WHY is non-obvious (hidden constraint, upstream-bug workaround, surprising behavior). Don't narrate what the code does or restate the diff in code comments. Identifiers and types already say what; comments are only for what they can't. No multi-paragraph kdoc on internal helpers. Note that this does not apply to dialogue, please do describe what changes you are making.

# Building

The command you should use to build or install should always be ./gradlew :vector-app:installFdroidDebug so please do not use anything else.

# Debugging on device

The app takes ~45 seconds to start. When launching it (e.g. to read logs or screenshot after an install), always wait at least 45s before checking for output.

# Changelog

Never write changelog entries to any file (no `changelog.d/` fragments, no `CHANGES.md` — that towncrier setup predates this fork and is unused). The changelog lives only in the commit message: a concise imperative subject line followed by a body describing the changes. Every body item MUST start with `- ` — NEVER write a paragraph that does not begin with `- `. Put a blank line between each `- ` entry.
