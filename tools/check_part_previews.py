"""PREVIEW-1 host check: every list that has been given a part preview must still use PartThumbs.

Owner rule 11-20: wherever a part is listed or picked, a tiny preview sits beside its name. A list is added to DONE when it
gets one (so it cannot quietly lose it), and leaves OWED when it does. OWED only shrinks. Run: python -B tools/check_part_previews.py
"""
import sys

DONE = {
    'app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java': 'sprite sheet manager, built-in transition type picker, reactive overlay picker',
    'app/src/main/java/com/fadcam/ui/faditor/avatar/AvatarStudioActivity.java': 'avatar part chips, library, add-part and parent pickers',
    'app/src/main/java/com/fadcam/vislab/room/LookSheets.java': 'VisLab sprite sheet and TextLab look choosers',
    'app/src/main/java/com/fadcam/ui/faditor/text/TextStyleLibraryPanel.java': 'built-in text styles',
    'app/src/main/java/com/fadcam/ui/faditor/FilterBottomSheet.java': 'colour grade chooser',
    'app/src/main/java/com/fadcam/ui/faditor/effects/LutLibraryPanel.java': 'grades drawer list',
}
OWED = [
    'VisLab look strip and Studio VisLab style chips', 'VisLab parts shelf and layer rows', 
'the 26 GL transitions (need a GPU render)', 'duck-under lane picker (audio lanes: no picture to show)', 'Unibrush tip textures (JoyBrush lane)',
    'Studio text lanes in the timeline', 'avatar manager in Studio', 'Avatar room stage tabs',
]
bad = []
for path, what in DONE.items():
    try:
        text = open(path, encoding='utf-8', errors='ignore').read()
    except OSError:
        bad.append('MISSING FILE ' + path)
        continue
    if 'PartThumbs' not in text:
        bad.append('LOST PREVIEW %s (%s)' % (path, what))
for b in bad:
    print(b)
print('part-preview check:', 'FAIL' if bad else 'ok', '- done in %d files, owed: %d' % (len(DONE), len(OWED)))
sys.exit(1 if bad else 0)
