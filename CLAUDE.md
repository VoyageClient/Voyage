# Strings

New strings always go into `library/ui-strings/src/main/res/values/donottranslate.xml` with `translatable="false"`. Do not add them to `strings.xml` — that file is the source for translation pipelines and stale entries cause AAPT warnings ("removing resource X without required default value") across every locale.

# Comments

Default to no comment when writing code. Only write one when the WHY is non-obvious (hidden constraint, upstream-bug workaround, surprising behavior). Don't narrate what the code does or restate the diff in code comments. Identifiers and types already say what; comments are only for what they can't. No multi-paragraph kdoc on internal helpers. Note that this does not apply to dialogue, please do describe what changes you are making.

# Building

The command you should use to build or install should always be ./gradlew :vector-app:installFdroidDebug so please do not use anything else.

# Changelog

Never write changelog entries to any file (no `changelog.d/` fragments, no `CHANGES.md` — that towncrier setup predates this fork and is unused). The changelog lives only in the commit message: a concise imperative subject line followed by a body describing the changes. Every body item MUST start with `- ` — NEVER write a paragraph that does not begin with `- `. Put a blank line between each `- ` entry.
