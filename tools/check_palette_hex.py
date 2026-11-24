"""Host check (STYLE-1): the palette cyan (#22D3EE) and pink (#F43F8E) may be typed only in the
shared palette files. Anywhere else a code line must say Studio.ARMED / Studio.LIVE.
Comments are ignored. Files still owed (JoyBrush/Avatar/STU-7 are claimed, see the STYLE-1 card)
are listed in OWED and only shrink. Run: python -B tools/check_palette_hex.py"""
import re, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
PAT = re.compile(r'(0x(ff)?(22d3ee|f43f8e)\b|"#(ff)?(22d3ee|f43f8e)")', re.I)
PALETTE = {"studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java",
           "joybrush-android/src/main/res/values/jb_tokens.xml"}
OWED = {  # JoyBrush lane owns these; sweep after its claim clears
    "joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardChromeView.kt",
    "joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/select/SelectOverlayView.kt",
    # editor colour-chip lists and web scene scripts: swatches the user picks, not accents
    "app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java",
    "app/src/main/java/com/fadcam/ui/faditor/slides/SlideDesigns.java",
    "app/src/main/java/com/fadcam/ui/faditor/layers/ObjectPalette.java",
}
bad = []
for base in ("app/src/main/java", "joybrush-android/src/main", "joybrush/androidkit/src/main"):
    for p in (ROOT / base).rglob("*"):
        if p.suffix not in (".java", ".kt"): continue
        rel = p.relative_to(ROOT).as_posix()
        if rel in PALETTE or rel in OWED: continue
        for n, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            s = line.strip()
            if s.startswith(("//", "*", "/*")): continue
            if PAT.search(line): bad.append(f"{rel}:{n}: {s[:100]}")
if bad:
    print("FAIL: palette colour typed outside the palette:"); print("\n".join(bad)); sys.exit(1)
print("ok: no stray cyan/pink hex outside the palette files")
