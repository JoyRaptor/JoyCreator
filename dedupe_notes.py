from pathlib import Path
import re
p=Path('C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates/NOTES.md')
s=p.read_text(encoding='utf-8')
parts=re.split(r'(?m)(?=^### )',s)
latest={}
for part in parts:
    key=part.splitlines()[0] if part.startswith('### ') else '__intro__'
    latest[key]=part
p.write_text(''.join(latest.values())+'\n\nGeneration paused by the owner on 2026-10-01. These are raw candidates: no material is selected or packed. Rice has subtle relief independent of opacity; dark inclusions are not dips. New prompts use D65 neutral white balance. Several generated joins need seam repair. Crumple geometry also needs stronger folded facets before selection.\n',encoding='utf-8')
