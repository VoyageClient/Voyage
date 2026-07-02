#!/usr/bin/env python3
import re, sys

def extract_paths(svg):
    # returns list of d strings, in order
    return re.findall(r'<path[^>]*\bd="([^"]+)"', svg, re.S)

def parse_viewbox(svg):
    m = re.search(r'viewBox="([^"]+)"', svg)
    minx, miny, w, h = [float(x) for x in m.group(1).split()]
    return minx, miny, w, h

def parse_rotate(svg):
    m = re.search(r'rotate\(([^)]+)\)', svg)
    if not m:
        return None
    parts = [float(x) for x in m.group(1).replace(',', ' ').split()]
    return parts  # angle, px, py

def build_vd(svg, width_dp, height_dp):
    minx, miny, w, h = parse_viewbox(svg)
    rot = parse_rotate(svg)
    paths = extract_paths(svg)
    lines = []
    lines.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    lines.append(f'    android:width="{fmt(width_dp)}dp"')
    lines.append(f'    android:height="{fmt(height_dp)}dp"')
    lines.append(f'    android:viewportWidth="{fmt(w)}"')
    lines.append(f'    android:viewportHeight="{fmt(h)}">')
    indent = '  '
    # outer offset group to normalise viewBox min to 0
    lines.append(f'{indent}<group android:translateX="{fmt(-minx)}" android:translateY="{fmt(-miny)}">')
    indent2 = indent + '  '
    close_rot = False
    if rot:
        angle, px, py = rot
        lines.append(f'{indent2}<group android:rotation="{fmt(angle)}" android:pivotX="{fmt(px)}" android:pivotY="{fmt(py)}">')
        pindent = indent2 + '  '
        close_rot = True
    else:
        pindent = indent2
    for d in paths:
        lines.append(f'{pindent}<path')
        lines.append(f'{pindent}    android:fillColor="#FFFFFF"')
        lines.append(f'{pindent}    android:pathData="{d.strip()}" />')
    if close_rot:
        lines.append(f'{indent2}</group>')
    lines.append(f'{indent}</group>')
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'

def fmt(x):
    if x == int(x):
        return str(int(x))
    return ('%.4f' % x).rstrip('0').rstrip('.')

def build_adaptive(svg, target_box=58.0):
    """108x108 adaptive foreground: mark centred, scaled to fit target_box (of 108 safe area)."""
    minx, miny, w, h = parse_viewbox(svg)
    rot = parse_rotate(svg)
    paths = extract_paths(svg)
    s = target_box / max(w, h)
    cx, cy = w / 2.0, h / 2.0
    tx = 54.0 - s * cx
    ty = 54.0 - s * cy
    lines = []
    lines.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    lines.append('    android:width="108dp"')
    lines.append('    android:height="108dp"')
    lines.append('    android:viewportWidth="108"')
    lines.append('    android:viewportHeight="108">')
    lines.append(f'  <group android:scaleX="{fmt(s)}" android:scaleY="{fmt(s)}" android:translateX="{fmt(tx)}" android:translateY="{fmt(ty)}">')
    lines.append(f'    <group android:translateX="{fmt(-minx)}" android:translateY="{fmt(-miny)}">')
    pindent = '      '
    close_rot = False
    if rot:
        angle, px, py = rot
        lines.append(f'      <group android:rotation="{fmt(angle)}" android:pivotX="{fmt(px)}" android:pivotY="{fmt(py)}">')
        pindent = '        '
        close_rot = True
    for d in paths:
        lines.append(f'{pindent}<path')
        lines.append(f'{pindent}    android:fillColor="#FFFFFF"')
        lines.append(f'{pindent}    android:pathData="{d.strip()}" />')
    if close_rot:
        lines.append('      </group>')
    lines.append('    </group>')
    lines.append('  </group>')
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'

if __name__ == '__main__':
    if sys.argv[1] == '--adaptive':
        src, out = sys.argv[2], sys.argv[3]
        with open(src) as f:
            svg = f.read()
        with open(out, 'w') as f:
            f.write(build_adaptive(svg))
        print('wrote', out)
    else:
        src, out, wdp, hdp = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4])
        with open(src) as f:
            svg = f.read()
        with open(out, 'w') as f:
            f.write(build_vd(svg, wdp, hdp))
        print('wrote', out)
