"""Lists code-built icon views (ImageView/ImageButton/plain View) with a click listener and no name set in the same file.
Run: python -B tools/scan_hover_code.py   (HOVER-1 inventory helper; skips Avatar and motion-blur paths)
"""
import glob
import os
import re

files = [f for f in glob.glob('**/src/main/**/*.java', recursive=True) + glob.glob('**/src/main/**/*.kt', recursive=True)
         if 'avatar' not in f.lower() and 'motion' not in f.lower()]
n = 0
for f in sorted(files):
    s = open(f, encoding='utf-8', errors='ignore').read()
    seen = set()
    for m in re.finditer(r'(\w+)\.setOnClickListener', s):
        v = m.group(1)
        if v in ('this', 'itemView', 'root', 'view', 'v', 'dialog', 'sheet') or v in seen:
            continue
        seen.add(v)
        if re.search(r'\b' + v + r'\.(setContentDescription|setTooltipText|contentDescription\s*=|tooltipText\s*=)', s):
            continue
        d = re.search(r'\b(ImageButton|ImageView|AppCompatImageView|AppCompatImageButton|FrameLayout|LinearLayout|View)\s+'
                      + v + r'\s*=\s*new|val\s+' + v + r'\s*=\s*(ImageButton|ImageView|View|FrameLayout)\(', s)
        if d:
            n += 1
            print(os.path.basename(f), v, d.group(1) or d.group(2))
print(n)
