# Symmetry on the Note 9: agent check, 2026-10-27

**What this is:** an agent (not the owner) drove the Note 9 sandbox with the APK built from
`claude/symmetry-recover-20261027` at `1ad2bd9e5` (APK SHA-256 starts `afad0a0633d675b4`, installed 23:45,
`lastUpdateTime` moved from 23:37:31 to 23:45:52). A fresh drawing was used; no existing drawing was opened or changed.
Screenshots are half size.

**How the strokes were made:** `adb input` finger swipes. This phone remembers that an S Pen has been used, and after that a
finger only pans the page, so for the test the app's `penSeen` preference was set to false, the app restarted, and the
original value (`true`) was restored afterwards (file compared byte for byte with the backup). Real pen input,
pressure and tilt were NOT exercised.

## Results (ink brush, kraft paper, editable lines on)

| Check | Result | Proof |
|---|---|---|
| Helpers → Symmetry shows a vertical line with two end handles and a pill (count, Mirror, Overlap, Symmetry) | ✅ | 01 |
| Mirror ×2: a stroke left of the line is mirrored | ✅ 2,655 ink px each side; every left pixel has its mirror within 2 px (89% exactly) | 01 |
| One Undo removes both copies, one Redo brings both back | ✅ ink 5,310 → 64 (handle edges) → 5,310 | counted from screenshots |
| Scrub the count to ×8: eight rotated copies around the line's midpoint, one Undo/Redo | ✅ 12,383 → 5,310 → 12,383 | 02 |
| Mirror chip at ×8 (kaleidoscope): 16 copies from one stroke, one Undo | ✅ 16 separate strokes; 24,983 → 12,383 | 03 |
| Eraser: cuts the stroke and its mirror alike, one Undo restores both | ✅ left 378 / right 377 px after; 860 / 861 before and after Undo | 04 |
| Select: tap one copy → the whole set ("8 lines"), one box, one recolour for all eight, one Undo puts all eight back | ✅ | 05, 06 |
| Save, go Home, reopen: strokes (12,383 px, identical), guide, ×8, Mirror and the 8-line grouping all come back | ✅ | 07 |
| A realistic brush (Proto Pencil) with Symmetry on | refuses in words, paints nothing: "Symmetry is not available for pencil, watercolour and oil on this build." | 08 |
| Switching Symmetry off and on re-centres the line (and resets to ×2, Mirror off) | ✅ the way back after a bump | 09 |

Temperature 34.4 to 36.7 °C over the test (began at 36.6 °C), falling at the end: no throttling seen. Rotation lock untouched.

## Findings (none is a crash; all are for the JoyBrush lead and the owner)

1. **Moving the symmetry line is not an Undo step.** `GuideOverlayView` → `guidesChanged()` writes only the app preferences, as
   for the grid and ruler. Pressing Undo after bumping the line undid an older step and left the line where it was. Way back:
   Helpers → Symmetry off, then on (re-centres; also resets the count and Mirror). Possible fix: a small "Centre" action, or
   put guide moves into the undo log. Owner's call.
2. **A stroke that starts within about 10 dp of the line grabs the line** (it moves, nothing is drawn). By design (the line's
   body is draggable), but it means a stroke cannot start right on the line.
3. **The dotted paint-zone circle shows for realistic brushes although they are refused** on this build (the atlas has no
   symmetry pin). It promises something the app then declines. Suggest hiding it while the paint engine is the atlas.
4. Choosing a realistic brush shows the toast "Lines on Layer 1 became paint so pencil, watercolour and oil fit. Undo brings them
   back": existing behaviour, not symmetry (one Undo gave the lines back).

## Not checked (still owed)

Real pen: tilt/lean on a copy (the app's y-down convention versus the media lab's), pressure, a calligraphy tip's angle;
scatter brushes; speed and memory at ×30 (the count was scrubbed to ×24 only; no timing taken); realistic paint with a copy
inside the dotted zone (the one try dragged the line instead); two-finger rotate and the 45° snap; Overlap chip; a Studio
export of a symmetry drawing; the owner's own look and feel.
