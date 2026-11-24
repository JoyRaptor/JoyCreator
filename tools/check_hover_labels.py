"""Fails if a clickable ImageButton/ImageView in a layout has no contentDescription or tooltipText.

HoverLabels (installed for every activity) turns a spoken name into the hover label, so a name is enough.
Library-styled Exo player buttons (ids starting exo_) get their names from the style and are skipped.
Run: python -B tools/check_hover_labels.py
"""
import glob
import re
import sys

bad = []
for f in sorted(glob.glob('*/src/main/res/layout*/*.xml')):
    s = open(f, encoding='utf-8', errors='ignore').read()
    for m in re.finditer(r'<(ImageButton|ImageView|androidx\.appcompat\.widget\.AppCompat\w+)\b[^>]*>', s):
        t = m.group(0)
        clickable = m.group(1) != 'ImageView' or 'clickable="true"' in t
        if not clickable or '@id/exo_' in t or 'importantForAccessibility="no"' in t:
            continue
        if 'contentDescription' in t or 'tooltipText' in t:
            continue
        bad.append(f + ': ' + re.sub(r'\s+', ' ', t)[:120])
for b in bad:
    print('NO HOVER LABEL', b)
print('hover-label check:', 'FAIL' if bad else 'ok', len(bad))
sys.exit(1 if bad else 0)
