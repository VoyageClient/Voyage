#!/usr/bin/env python3
#
# Copyright 2026 Voyage Client
#
# SPDX-License-Identifier: AGPL-3.0-only
# Please see LICENSE files in the repository root for full details.
#
"""Translation helper for library/ui-strings. See README.md for the workflow.

  tr.py status                      missing/stale counts per locale
  tr.py export LOC OUT.json [N] [K1,K2]  write the first N (0 = all) missing entries for LOC, optionally only keys K1,K2
  tr.py import LOC IN.json          validate + append translations; invalid entries are reported and skipped
  tr.py prune LOC|all               drop locale entries whose key no longer exists in values/strings.xml
  tr.py drop K1,K2 [LOC|all]        drop these keys' translations (after rewording the English) so they are re-exported
  tr.py check LOC|all               XML-parse + validate every entry already in the locale file

IN.json: {"key": "translated text", "plural_key": {"one": "...", "other": "..."}}
Text is raw XML string content, escaped exactly like the English source (\\' \\" &lt; &amp; \\n).
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../library/ui-strings/src/main/res')
BASE = os.path.join(RES, 'values/strings.xml')

PLURALS = {
    'ar': ['zero', 'one', 'two', 'few', 'many', 'other'],
    'cs': ['one', 'few', 'other'], 'sk': ['one', 'few', 'other'], 'ro': ['one', 'few', 'other'],
    'pl': ['one', 'few', 'many', 'other'], 'ru': ['one', 'few', 'many', 'other'], 'uk': ['one', 'few', 'many', 'other'],
    'iw': ['one', 'two', 'many', 'other'],
    'ja': ['other'], 'ko': ['other'], 'th': ['other'], 'vi': ['other'], 'in': ['other'],
    'zh-rCN': ['other'], 'zh-rTW': ['other'],
}
DEFAULT_PLURALS = ['one', 'other']

ENTRY = re.compile(r'[ \t]*<(string|plurals)\b[^>]*?\sname="([^"]+)"[^>]*>(.*?)</\1>', re.S)
COMMENT = re.compile(r'<!--.*?-->', re.S)
UNFORMATTED = set()
ITEM = re.compile(r'<item\s+quantity="([a-z]+)"\s*>(.*?)</item>', re.S)
FMT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaAn]|%%')
TMPL = re.compile(r'\$\{[A-Za-z_]+\}')
TAG = re.compile(r'&lt;/?[A-Za-z]+|<(?!!--)/?[A-Za-z]+')
URL = re.compile(r'https?://[^\s"<&]+')


def parse(path):
    """name -> (type, value) where value is str or {quantity: str}; plus the raw span of each entry."""
    if not os.path.exists(path):
        return {}, {}
    text = open(path, encoding='utf-8').read()
    text = COMMENT.sub(lambda m: re.sub(r'[^\n]', ' ', m.group(0)), text)
    out, spans = {}, {}
    for m in ENTRY.finditer(text):
        kind, name, body = m.group(1), m.group(2), m.group(3)
        head = m.group(0).split('>', 1)[0]
        if 'translatable="false"' in head:
            continue
        if path == BASE and 'formatted="false"' in head:
            UNFORMATTED.add(name)
        if kind == 'plurals':
            out[name] = ('plurals', {q: v for q, v in ITEM.findall(body)})
        else:
            out[name] = ('string', body)
        spans[name] = (m.start(), m.end())
    return out, spans


def base():
    return parse(BASE)[0]


def loc_path(loc):
    return os.path.join(RES, 'values-' + loc, 'strings.xml')


def locales():
    return sorted(os.path.basename(d)[7:] for d in glob.glob(os.path.join(RES, 'values-*')))


def plural_forms(loc):
    return PLURALS.get(loc, DEFAULT_PLURALS)


def unwrap(s):
    """Android treats a value wrapped in bare double quotes as literal (apostrophes need no escaping inside)."""
    if len(s) > 1 and s[0] == '"' and s[-1] == '"' and s[-2] != '\\' and not re.search(r'(?<!\\)"', s[1:-1]):
        return s[1:-1], True
    return s, False


def check_text(en, tr, allow_fewer_fmt=False, formatted=True):
    errs = []
    if not tr.strip():
        return ['empty']
    en, _ = unwrap(en)
    tr, quoted = unwrap(tr)
    a, b = sorted(FMT.findall(en)), sorted(FMT.findall(tr))
    if len(a) == len(b) == 1:
        a, b = [re.sub(r'^%1\$', '%', a[0])], [re.sub(r'^%1\$', '%', b[0])]
    if not formatted:
        if b:
            errs.append(f'{b} reads as a format placeholder (lint StringFormatInvalid); rephrase around the %')
    elif allow_fewer_fmt:
        if not set(b) <= set(a):
            errs.append(f'placeholders {b} not a subset of {a}')
    elif a != b:
        errs.append(f'placeholders {b} != {a}')
    if sorted(TMPL.findall(en)) != sorted(TMPL.findall(tr)):
        errs.append('${...} templates differ')
    tags = lambda s: sorted(t.lower().replace('&lt;', '<') for t in TAG.findall(s))
    if tags(en) != tags(tr):
        errs.append(f'markup {TAG.findall(tr)} != {TAG.findall(en)}')
    if sorted(URL.findall(en)) != sorted(URL.findall(tr)):
        errs.append('URLs differ')
    if not quoted and re.search(r"(?<!\\)'", tr):
        errs.append("unescaped apostrophe (use \\')")
    bare_q = lambda s: len(re.findall(r'(?<!\\)"', re.sub(r'<[^>]*>|&lt;[^>]*>', '', s)))
    # aapt drops bare quotes, and several upstream English strings carry a stray one.
    if bare_q(tr) > bare_q(en):
        errs.append('unescaped double quote (use \\")')
    if re.search(r'&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)', tr):
        errs.append('bare & (use &amp;)')
    if re.search(r'<(?![A-Za-z/!])', tr):
        errs.append('bare < (use &lt;)')
    if tr.lstrip()[:1] in ('@', '?'):
        errs.append('leading @ or ? must be escaped with \\')
    try:
        ET.fromstring('<r>' + tr.replace('&lt;', '&#60;') + '</r>')
    except ET.ParseError as e:
        errs.append(f'not well-formed XML content: {e}')
    return errs


def check_entry(loc, kind, en, tr, name=None):
    if kind == 'string':
        if not isinstance(tr, str):
            return ['expected a string']
        return check_text(en, tr, formatted=name not in UNFORMATTED)
    if not isinstance(tr, dict):
        return ['expected an object of plural quantities']
    errs = []
    need = plural_forms(loc)
    missing = [q for q in need if q not in tr]
    extra = [q for q in tr if q not in need]
    if missing:
        errs.append(f'missing quantities {missing} (need {need})')
    if extra:
        errs.append(f'unexpected quantities {extra} (need {need})')
    en_other = en.get('other', next(iter(en.values())))
    for q, v in tr.items():
        if q in need:
            errs += [f'[{q}] {e}' for e in check_text(en_other, v, allow_fewer_fmt=(q != 'other'))]
    return errs


def render(name, kind, val):
    if kind == 'string':
        return f'    <string name="{name}">{val}</string>'
    items = ''.join(f'\n        <item quantity="{q}">{val[q]}</item>' for q in val)
    return f'    <plurals name="{name}">{items}\n    </plurals>'


def ensure_file(loc):
    p = loc_path(loc)
    if not os.path.exists(p):
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'w', encoding='utf-8').write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n')
    return p


def missing(loc):
    b = base()
    have = parse(loc_path(loc))[0]
    return [(k, v) for k, v in b.items() if k not in have]


def cmd_status():
    b = base()
    total = 0
    for loc in locales():
        have = parse(loc_path(loc))[0]
        s = len([k for k in have if k not in b])
        if loc == 'en-rGB':
            print(f'{loc:8} spelling overrides only  stale {s}')
            continue
        m = len([k for k in b if k not in have])
        total += m
        print(f'{loc:8} missing {m:5}  stale {s}')
    print('total missing', total)


def cmd_export(loc, out, n=None, keys=None):
    items = missing(loc)
    if keys:
        wanted = set(keys.split(','))
        items = [i for i in items if i[0] in wanted]
    if n and int(n) > 0:
        items = items[:int(n)]
    data = []
    for k, (kind, v) in items:
        e = {'name': k, 'en': v}
        if kind == 'plurals':
            e['quantities'] = plural_forms(loc)
        data.append(e)
    json.dump(data, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'{len(data)} entries -> {out} ({len(missing(loc))} missing in total)')


def cmd_import(loc, src):
    b = base()
    tr = json.load(open(src, encoding='utf-8'))
    p = ensure_file(loc)
    have = parse(p)[0]
    ok, bad = {}, 0
    for k, v in tr.items():
        if k not in b:
            print(f'SKIP {k}: not in values/strings.xml')
            bad += 1
            continue
        if k in have:
            print(f'SKIP {k}: already translated')
            continue
        kind, en = b[k]
        if kind == 'plurals' and isinstance(v, dict):
            v = {q: v[q] for q in plural_forms(loc) if q in v} | {q: v[q] for q in v if q not in plural_forms(loc)}
        errs = check_entry(loc, kind, en, v, k)
        if errs:
            bad += 1
            print(f'REJECT {k}: ' + '; '.join(errs))
            continue
        ok[k] = (kind, v)
    if ok:
        order = list(b)
        lines = [render(k, *ok[k]) for k in sorted(ok, key=order.index)]
        text = open(p, encoding='utf-8').read().rstrip()
        assert text.endswith('</resources>')
        text = text[:-len('</resources>')].rstrip() + '\n' + '\n'.join(lines) + '\n</resources>\n'
        tmp = p + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        ET.parse(tmp)
        os.replace(tmp, p)
    print(f'{loc}: wrote {len(ok)}, rejected {bad}, still missing {len(missing(loc))}')
    return 1 if bad else 0


def cmd_drop(keys, loc='all'):
    cmd_prune(loc, frozenset(keys.split(',')))


def cmd_prune(loc, drop=frozenset()):
    b = base()
    for l in (locales() if loc == 'all' else [loc]):
        p = loc_path(l)
        if not os.path.exists(p):
            continue
        text = open(p, encoding='utf-8').read()
        have, spans = parse(p)
        stale = [k for k in have if k not in b or k in drop]
        for k in sorted(stale, key=lambda k: -spans[k][0]):
            s, e = spans[k]
            if text[e:e + 1] == '\n':
                e += 1
            text = text[:s] + text[e:]
        open(p, 'w', encoding='utf-8').write(text)
        ET.parse(p)
        if stale:
            print(f'{l}: pruned {len(stale)}')


def cmd_check(loc):
    b = base()
    rc = 0
    for l in (locales() if loc == 'all' else [loc]):
        p = loc_path(l)
        if not os.path.exists(p):
            continue
        ET.parse(p)
        have = parse(p)[0]
        n = 0
        for k, (kind, v) in have.items():
            if k in b and b[k][0] == kind:
                errs = check_entry(l, kind, b[k][1], v, k)
                if errs:
                    n += 1
                    print(f'{l} {k}: ' + '; '.join(errs))
        if n:
            rc = 1
            print(f'{l}: {n} problem entries')
    return rc


if __name__ == '__main__':
    a = sys.argv[1:]
    cmd = a[0]
    rc = {'status': lambda: cmd_status(), 'export': lambda: cmd_export(*a[1:]), 'import': lambda: cmd_import(*a[1:]),
          'prune': lambda: cmd_prune(a[1]), 'drop': lambda: cmd_drop(*a[1:]), 'check': lambda: cmd_check(*a[1:])}[cmd]()
    sys.exit(rc or 0)
