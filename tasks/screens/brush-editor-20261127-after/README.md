# Brush editor after BRUSH-UI section L, on the cloud emulator (11-27, hover-1 lane)

Source: emulator run 46060879930 (phone profile: 1080x2220, density 315, API 29, x86_64), debug build of `joy-creator` including `ac711fe3c`.
Script: `tools/cloud/emulator/scripts/brush_editor.sh` (JoyBrush started directly; brush held about a second in the shelf).

| File | Brush |
|---|---|
| `oil_round_settings.png` | Oil round |
| `watercolour_round_settings.png` | Watercolour round |
| `pencil_hb_settings.png` | Pencil HB |
| `ink_line_settings.png` | Ink (a line) |
| `unibrush_calligraphy_settings.png` | Unibrush Calligraphy: Pen with the four-point Tip diamond, nib angles, Pressure and Tilt curves, Tip On/Off |

**What the emulator cannot show:** the live test-stroke strip at the top of each sheet is BLANK here. The emulator's software GPU does not draw the paint engine (it also killed the emulator in an earlier run on the watercolour brush); the real Note 9 draws it (see `tasks/screens/brush-editor-20261127/`, taken on the Note 9 before section L). So the live Unibrush stroke and picture tips of section L still need a Note 9 look. The layout, rounded corners, tabs, Pen and curve panels, and the Tip section are real.
