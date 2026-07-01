#!/usr/bin/env python3
"""Regenerate the bundled emoji assets.

Three independent steps (run all by default, or pick with flags):
  --datasource  emoji_picker_datasource.json   (unicode.org list + emojilib keywords)
  --twemoji     the Twemoji colour PNG set      (vector/src/main/assets/twemoji/)
  --font        the emoji2 NotoColorEmojiCompat font (vector/src/main/assets/emoji2/),
                refreshed from the latest androidx.emoji2:emoji2-bundled when newer.

Run from the repo root: python3 tools/import_emojis.py
"""

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import urllib.error
import urllib.request
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

import requests
from bs4 import BeautifulSoup

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# --------------------------------------------------------------------------------------------------
# 1. emoji_picker_datasource.json
# --------------------------------------------------------------------------------------------------

# A list of words to not capitalize in emoji-names
CAPITALIZATION_EXCLUDE = {'with', 'a', 'at', 'of', 'for', 'and', 'over', 'the', 'off', 'on', 'out', 'in', 'but', 'or'}


def build_datasource():
    # Create skeleton of the final json file as a python dictionary:
    emoji_picker_datasource = {
        "compressed": True,
        "categories": [],
        "emojis": {},
        "aliases": {}
    }
    emoji_picker_datasource_categories = emoji_picker_datasource["categories"]
    emoji_picker_datasource_emojis = emoji_picker_datasource["emojis"]

    # Get official emoji list from unicode.org (Emoji List, v13.1 at time of writing)
    print("Fetching emoji list from Unicode.org...")
    req = requests.get("https://unicode.org/emoji/charts/emoji-list.html")
    soup = BeautifulSoup(req.content, 'html.parser')

    variation_sequence_data = requests.get("https://www.unicode.org/Public/15.0.0/ucd/emoji/emoji-variation-sequences.txt").text
    variation_sequence_overrides = {}
    for line in variation_sequence_data.split("\n"):
        if "emoji style" in line:
            emoji_hex = line.split(" ", 1)[0]
            variation_sequence_overrides[emoji_hex] = emoji_hex + "-FE0F"

    # Navigate to table
    table = soup.body.table

    # Go over all rows
    print("Extracting emojis...")
    for row in table.find_all('tr'):
        # Add "bigheads"  rows to categories
        if 'bighead' in next(row.children)['class']:
            relevant_element = row.find('a')
            category_id = relevant_element['name']
            category_name = relevant_element.text
            emoji_picker_datasource_categories.append({
                "id": category_id,
                "name": category_name,
                "emojis": []
            })

        # Add information in "rchars" rows to the last encountered category and emojis
        if row.find('td', class_='code'):
            # Get columns
            cols = row.find_all('td')
            code_element = cols[1]
            cldr_element = cols[3]
            keywords_element = cols[4]

            # Extract information from columns
            # Extract name and id
            # => Remove spaces, colons and unicode-characters
            emoji_name = cldr_element.text
            emoji_id = cldr_element.text.lower()
            emoji_id = re.sub(r'[^A-Za-z0-9 ]+', '', emoji_id, flags=re.UNICODE)  # Only keep alphanumeric, space characters
            emoji_id = emoji_id.strip()  # Remove leading/trailing whitespaces
            emoji_id = emoji_id.replace(' ', '-')

            # Capitalize name according to the same rules as the previous emoji_picker_datasource.json
            emoji_name_cap = "".join([w.capitalize() if i == 0 or w not in CAPITALIZATION_EXCLUDE else w for i, w in enumerate(re.split(r'(\W)', emoji_name))])

            # Extract emoji unicode-codepoint
            emoji_code_raw = code_element.text
            emoji_code_list = emoji_code_raw.split(" ")
            emoji_code_list = [e[2:] for e in emoji_code_list]
            emoji_code = "-".join(emoji_code_list)

            # Extract keywords
            emoji_keywords = keywords_element.text.split(" | ")

            # Add the emoji-id to the last entry in "categories"
            emoji_picker_datasource_categories[-1]["emojis"].append(emoji_id)

            # Add the emoji itself to the "emojis" dict
            emoji_picker_datasource_emojis[emoji_id] = {
                    "a": emoji_name_cap,
                    "b": emoji_code,
                    "j": emoji_keywords
            }

    # The keywords of unicode.org are usually quite sparse.
    # There is no official specification of keywords beyond that, but muan/emojilib maintains a well maintained and
    # established repository with additional keywords. We extend our list with the keywords from there.
    print("Fetching additional keywords from Emojilib...")
    req = requests.get("https://raw.githubusercontent.com/muan/emojilib/main/dist/emoji-en-US.json")
    emojilib_data = json.loads(req.content)

    # We just go over all the official emojis from unicode, and add the keywords there
    print("Adding keywords to emojis...")
    for emoji in emoji_picker_datasource_emojis:
        emoji_name = emoji_picker_datasource_emojis[emoji]["a"]
        emoji_code = emoji_picker_datasource_emojis[emoji]["b"]

        # Convert back to actual unicode emoji
        emoji_unicode = ''.join(map(lambda s: chr(int(s, 16)), emoji_code.split("-")))

        # Search for emoji in emojilib
        if emoji_unicode in emojilib_data:
            emoji_additional_keywords = emojilib_data[emoji_unicode]
        elif emoji_unicode + chr(0xfe0f) in emojilib_data:
            emoji_additional_keywords = emojilib_data[emoji_unicode + chr(0xfe0f)]
        else:
            print("* No additional keywords for", emoji_unicode, emoji_picker_datasource_emojis[emoji])
            continue

        # If additional keywords exist, add them to emoji_picker_datasource_emojis
        # Avoid duplicates and keep order. Put official unicode.com keywords first and extend up with emojilib ones.
        new_keywords = OrderedDict.fromkeys(emoji_picker_datasource_emojis[emoji]["j"] + emoji_additional_keywords)
        # Remove the ones derived from the unicode name
        for keyword in [emoji.replace("-", "_")] + [emoji.replace("-", " ")] + [emoji_name]:
            if keyword in new_keywords:
                new_keywords.pop(keyword)
        # Write new keywords back
        emoji_picker_datasource_emojis[emoji]["j"] = list(new_keywords.keys())
        if emoji_code in variation_sequence_overrides:
            emoji_picker_datasource_emojis[emoji]["b"] = variation_sequence_overrides[emoji_code]

    # Filter out components (e.g. skin-tone modifiers) as they are not suitable for single-emoji reactions
    emoji_picker_datasource['categories'] = [x for x in emoji_picker_datasource['categories'] if x['id'] != 'component']

    # Write result to file (overwrite previous), without escaping unicode characters
    print("Writing emoji_picker_datasource.json...")
    with open(os.path.join(REPO_ROOT, "vector/src/main/res/raw/emoji_picker_datasource.json"), "w") as outfile:
        json.dump(emoji_picker_datasource, outfile, ensure_ascii=False, separators=(',', ':'))

    # Also export a formatted version
    print("Writing emoji_picker_datasource_formatted.json...")
    with open(os.path.join(REPO_ROOT, "tools/emojis/emoji_picker_datasource_formatted.json"), "w") as outfile:
        json.dump(emoji_picker_datasource, outfile, ensure_ascii=False, indent=4)


# --------------------------------------------------------------------------------------------------
# 2. Twemoji colour PNG set
# --------------------------------------------------------------------------------------------------
#
# We render colour emoji ourselves (a ReplacementSpan + sprite) where the platform/emoji2 can't: below
# KitKat there is no emoji font at all, and on newer versions the user can opt into Twemoji for a
# consistent look. We bundle the *entire* Twemoji 72x72 set (base emoji, skin-tone variants, ZWJ
# sequences, flags, keycaps) so any emoji a message contains can render — the runtime scanner builds
# its index from whatever assets are present. Asset names = the codepoint sequence, lowercase hex, '-'
# joined, with U+FE0F stripped (the runtime derives the same name, so generation and lookup agree).
# Source: jdecked/twemoji (the maintained fork), assets licensed CC-BY 4.0.

TWEMOJI_OUT_DIR = os.path.join(REPO_ROOT, "vector/src/main/assets/twemoji")
TWEMOJI_TREE_URL = "https://api.github.com/repos/jdecked/twemoji/git/trees/main?recursive=1"
TWEMOJI_BASE_URL = "https://raw.githubusercontent.com/jdecked/twemoji/main/assets/72x72"


def _twemoji_canonical_name(twemoji_stem):
    """Twemoji file stem (e.g. '2764-fe0f-200d-1f525') -> runtime save name ('2764-200d-1f525')."""
    return "-".join(p for p in twemoji_stem.split("-") if p != "fe0f")


def _twemoji_list_stems():
    req = urllib.request.Request(TWEMOJI_TREE_URL, headers={"User-Agent": "emoji-import"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        tree = json.load(resp)
    stems = []
    for entry in tree.get("tree", []):
        path = entry.get("path", "")
        if path.startswith("assets/72x72/") and path.endswith(".png"):
            stems.append(path[len("assets/72x72/"):-len(".png")])
    return stems


def _twemoji_fetch(stem):
    dest = os.path.join(TWEMOJI_OUT_DIR, _twemoji_canonical_name(stem) + ".png")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return "cached"
    url = "%s/%s.png" % (TWEMOJI_BASE_URL, stem)
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = resp.read()
        with open(dest, "wb") as fh:
            fh.write(data)
        return "ok"
    except urllib.error.HTTPError as exc:
        return "err:%s" % exc.code
    except Exception as exc:  # noqa: BLE001 - report and continue
        return "err:%s" % exc


def import_twemoji():
    os.makedirs(TWEMOJI_OUT_DIR, exist_ok=True)
    stems = _twemoji_list_stems()
    print("Twemoji 72x72 files: %d -> %s" % (len(stems), TWEMOJI_OUT_DIR))
    ok = cached = 0
    errors = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        for i, (stem, status) in enumerate(zip(stems, pool.map(_twemoji_fetch, stems)), 1):
            if status == "ok":
                ok += 1
            elif status == "cached":
                cached += 1
            else:
                errors.append((stem, status))
            if i % 500 == 0:
                print("  %d/%d (ok=%d cached=%d err=%d)" % (i, len(stems), ok, cached, len(errors)))
    print("Twemoji done: ok=%d cached=%d err=%d unique-files=%d" % (ok, cached, len(errors), len(os.listdir(TWEMOJI_OUT_DIR))))
    if errors:
        print("Errored:")
        for stem, status in errors[:50]:
            print("  %s (%s)" % (stem, status))
    return not errors


# --------------------------------------------------------------------------------------------------
# 3. emoji2 NotoColorEmojiCompat font
# --------------------------------------------------------------------------------------------------
#
# We deliberately don't depend on androidx.emoji2:emoji2-bundled (its newer releases raise minSdk to
# 21/23, and its bundled font lags Unicode). We keep the emoji2 *core* (KitKat-compatible) and bundle
# the font straight from the noto-emoji repo's main branch, which always serves the newest release
# (Unicode 17+). The font MUST stay a CBDT/CBLC (bitmap) colour font — KitKat can't render COLR/sbix —
# and must carry the emoji2 'meta' table; both are still verified below before we overwrite the asset.

EMOJI2_FONT_URL = "https://github.com/googlefonts/noto-emoji/raw/refs/heads/main/fonts/NotoColorEmoji-emojicompat.ttf"
EMOJI2_FONT_ASSET = os.path.join(REPO_ROOT, "vector/src/main/assets/emoji2/NotoColorEmojiCompat.ttf")


def _sfnt_tables(data):
    num_tables = struct.unpack(">H", data[4:6])[0]
    return {data[12 + i * 16:12 + i * 16 + 4].decode("latin1") for i in range(num_tables)}


def update_emoji2_font():
    print("Downloading latest NotoColorEmoji-emojicompat font from noto-emoji main...")
    font = requests.get(EMOJI2_FONT_URL).content

    tables = _sfnt_tables(font)
    colour = sorted(t for t in tables if t in ("CBDT", "CBLC", "COLR", "CPAL", "sbix"))
    if not {"CBDT", "CBLC"} <= tables:
        print("  REFUSING update: font colour tables are %s, not CBDT/CBLC — it would NOT render on KitKat." % colour)
        return False
    if "meta" not in tables:
        print("  REFUSING update: font has no emoji2 'meta' table.")
        return False

    new_hash = hashlib.sha256(font).hexdigest()
    old_hash = hashlib.sha256(open(EMOJI2_FONT_ASSET, "rb").read()).hexdigest() if os.path.exists(EMOJI2_FONT_ASSET) else None
    if new_hash == old_hash:
        print("  Font already up to date (%d bytes)." % len(font))
        return True
    os.makedirs(os.path.dirname(EMOJI2_FONT_ASSET), exist_ok=True)
    with open(EMOJI2_FONT_ASSET, "wb") as fh:
        fh.write(font)
    print("  Updated %s: %d bytes (was %s)." % (
        os.path.relpath(EMOJI2_FONT_ASSET, REPO_ROOT), len(font), "absent" if old_hash is None else "different"))
    return True


def main():
    parser = argparse.ArgumentParser(description="Regenerate bundled emoji assets.")
    parser.add_argument("--datasource", action="store_true", help="rebuild emoji_picker_datasource.json")
    parser.add_argument("--twemoji", action="store_true", help="download the Twemoji PNG set")
    parser.add_argument("--font", action="store_true", help="refresh the emoji2 NotoColorEmojiCompat font")
    args = parser.parse_args()
    run_all = not (args.datasource or args.twemoji or args.font)

    ok = True
    if run_all or args.datasource:
        build_datasource()
    if run_all or args.font:
        ok = update_emoji2_font() and ok
    if run_all or args.twemoji:
        ok = import_twemoji() and ok
    print("Done.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
