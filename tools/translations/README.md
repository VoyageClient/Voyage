# UI string translations

`tr.py` finds, validates and imports translations for `library/ui-strings`. Every translation lands in the locale's `strings.xml` only after it passes validation (placeholders, `${...}` templates, markup, URLs, escaping, plural forms), and each import is written atomically, so an interrupted run can be resumed at any point: `export` always returns the strings a locale still lacks.

    python3 tools/translations/tr.py status              missing/stale counts per locale
    python3 tools/translations/tr.py export LOC OUT.json [N] [K1,K2]   N=0 means all; optional key filter
    python3 tools/translations/tr.py import LOC IN.json
    python3 tools/translations/tr.py prune LOC|all      drop entries whose key left values/strings.xml
    python3 tools/translations/tr.py drop K1,K2 [LOC|all]   drop translations of reworded English strings so they get re-exported
    python3 tools/translations/tr.py check LOC|all

`en-rGB` is not a translation: it only overrides strings whose British spelling differs (colour, favourite, cancelled, …).

## Translating a locale (instructions for an agent)

You translate the missing UI strings of one locale of an Android Matrix chat client (a fork of Element Android) yourself. Work until the locale has 0 missing strings. Use a work directory outside the repo for batch files.

### Before starting

Read a good chunk of the existing `values-<LOC>/strings.xml` to learn its terminology and register: formal vs informal address, the words used for room, space, session, device, homeserver, verify, encryption, recovery key, etc. Reuse them exactly, and grep the file whenever unsure. If the locale is new, choose terminology the way mainstream apps (Android, WhatsApp, Signal, Element) localized to that language do, and keep it consistent.

### Terminology overrides

These win over whatever the existing locale file uses:

- All locales: "Key Backup" (formerly "Secure Backup") uses the locale's existing term for key backup, as in the `keys_backup_*` strings.
- `de`: Key Backup = Schlüsselsicherung
- `ru`: sticker = стикер (not наклейка)
- `zh-rCN`: passphrase = 密码短语

### Loop

1. `tr.py export <LOC> <workdir>/batch.json 60` writes the next 60 missing entries: `[{"name", "en", "quantities"?}]`. `en` is a string, or for plurals an object of English quantity forms.
2. Write the translations to `<workdir>/tr_N.json` (new N each batch) as one JSON object: `{"key": "text", "plural_key": {"one": "...", "other": "..."}}`. For plurals give exactly the quantities listed in `quantities`.
3. `tr.py import <LOC> <workdir>/tr_N.json`. Rejected entries print `REJECT key: reason` and are not written; fix them and import them again.
4. Repeat until import prints `still missing 0`, then run `tr.py check <LOC>`.

### Rules for every string

- The value is raw Android string-resource content. Escape exactly as the English does: apostrophe `\'`, double quote `\"`, newline `\n`, `&amp;`, `&lt;`. In JSON the backslash itself is doubled: `"l\\'app"` produces `l\'app`. Don't wrap values in double quotes, and never start one with an unescaped `@` or `?`.
- Keep every format placeholder (`%1$s`, `%2$d`, `%s`, `%d`, `%%`). Reorder freely, never renumber, drop or add; a plural's non-`other` forms may omit the number.
- Keep `${app_name}`-style templates, markup (`&lt;b>`, `&lt;a href="...">`, `<b>`) and URLs byte-for-byte.
- Don't translate product and protocol names (Matrix, Element, SchildiChat, OpenKeychain, PGP, VPN, Twemoji, UnifiedPush), MSC numbers, slash commands (`/translate`), user-agent strings, file extensions or code identifiers.
- Keep button labels and titles short, and use natural UI phrasing rather than word-for-word calques. The key name tells you what a short label means (`action_forward` forwards a message).
- Use the locale's standard script and orthography: `zh-rTW` is Traditional Chinese as used in Taiwan, `pt` European Portuguese, `pt-rBR` Brazilian, `nb-rNO` Norwegian Bokmål, `in` Indonesian, `iw` Hebrew.
- Edit locale files only through `tr.py import`. Don't touch other locales or `values/`.
