#!/usr/bin/env python3
"""Rasterise an app-logo SVG into launcher/splash PNGs.

Modes:
  square  rounded-square legacy launcher icon on the brand background
  round   circular legacy launcher icon on the brand background
  mark    white mark only, centred on a transparent canvas (starting-window splash)

Usage: compose_icon.py <svg> <out.png> <size> <square|round|mark>
"""
import re, sys, subprocess, os

BG = "#000000"

def inner_and_viewbox(svg):
    vb = re.search(r'viewBox="([^"]+)"', svg).group(1)
    body = re.search(r'<svg[^>]*>(.*)</svg>', svg, re.S).group(1)
    return body.strip(), vb

def compose(svg_path, size, kind, out_png):
    with open(svg_path) as f:
        svg = f.read()
    body, vb = inner_and_viewbox(svg)
    if kind == 'mark':
        inset = size * 0.10
        bg = ''
    else:
        inset = size * 0.18
        if kind == 'round':
            r = size / 2.0
            bg = f'<circle cx="{r}" cy="{r}" r="{r}" fill="{BG}"/>'
        else:
            rr = size * 0.18
            bg = f'<rect x="0" y="0" width="{size}" height="{size}" rx="{rr}" ry="{rr}" fill="{BG}"/>'
    mw = size - 2 * inset
    doc = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}">'
        f'{bg}'
        f'<svg x="{inset}" y="{inset}" width="{mw}" height="{mw}" viewBox="{vb}" '
        f'preserveAspectRatio="xMidYMid meet">{body}</svg>'
        f'</svg>'
    )
    tmp = out_png + '.svg'
    with open(tmp, 'w') as f:
        f.write(doc)
    subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), tmp, '-o', out_png], check=True)
    os.remove(tmp)
    print('wrote', out_png)

if __name__ == '__main__':
    svg_path, out_png, size, kind = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    compose(svg_path, size, kind, out_png)
