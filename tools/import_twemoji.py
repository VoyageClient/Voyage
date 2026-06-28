#!/usr/bin/env python3
"""Download the full Twemoji colour PNG set into vector assets.

We render colour emoji ourselves (a ReplacementSpan + sprite) on Android versions where the
platform/emoji2 can't: below KitKat there is no emoji font at all, and on newer versions the user
can opt into Twemoji for a consistent look. We bundle the *entire* Twemoji 72x72 set (base emoji,
skin-tone variants, ZWJ sequences, flags, keycaps) so any emoji a message contains can render — the
runtime scanner builds its index from whatever assets are present.

Asset names = the codepoint sequence, lowercase hex, '-' joined, with U+FE0F stripped. The runtime
derives the same name from a detected emoji, so generation and lookup always agree. Twemoji's own
filenames keep an internal FE0F for some sequences, so we re-save under the stripped name.

Source: jdecked/twemoji (the maintained Twemoji fork), assets licensed CC-BY 4.0.
Run from the repo root: python3 tools/import_twemoji.py
"""

import json
import os
import sys
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_ROOT, "vector/src/main/assets/twemoji")
TREE_URL = "https://api.github.com/repos/jdecked/twemoji/git/trees/main?recursive=1"
BASE_URL = "https://raw.githubusercontent.com/jdecked/twemoji/main/assets/72x72"


def canonical_name(twemoji_stem):
    """Twemoji file stem (e.g. '2764-fe0f-200d-1f525') -> runtime save name ('2764-200d-1f525')."""
    return "-".join(p for p in twemoji_stem.split("-") if p != "fe0f")


def list_twemoji_stems():
    req = urllib.request.Request(TREE_URL, headers={"User-Agent": "twemoji-import"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        tree = json.load(resp)
    stems = []
    for entry in tree.get("tree", []):
        path = entry.get("path", "")
        if path.startswith("assets/72x72/") and path.endswith(".png"):
            stems.append(path[len("assets/72x72/"):-len(".png")])
    return stems


def fetch(stem):
    dest = os.path.join(OUT_DIR, canonical_name(stem) + ".png")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return "cached"
    url = "%s/%s.png" % (BASE_URL, stem)
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


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    stems = list_twemoji_stems()
    print("Twemoji 72x72 files: %d -> %s" % (len(stems), OUT_DIR))

    ok = cached = 0
    errors = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        for i, (stem, status) in enumerate(zip(stems, pool.map(fetch, stems)), 1):
            if status == "ok":
                ok += 1
            elif status == "cached":
                cached += 1
            else:
                errors.append((stem, status))
            if i % 500 == 0:
                print("  %d/%d (ok=%d cached=%d err=%d)" % (i, len(stems), ok, cached, len(errors)))

    print("Done: ok=%d cached=%d err=%d unique-files=%d" % (ok, cached, len(errors), len(os.listdir(OUT_DIR))))
    if errors:
        print("Errored:")
        for stem, status in errors[:50]:
            print("  %s (%s)" % (stem, status))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
