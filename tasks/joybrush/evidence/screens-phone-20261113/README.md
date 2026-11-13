# JoyBrush screens lane: Note 9 check, 2026-11-13 13:16 to 13:35

Build: own debug APK from `374fe099f` (holds c070fb5e1: JBX-02, 03, 05, 06 and paper in thumbnails), installed over
`com.fadcam.beta` on the Note 9 only. Phone 24.5 °C at the start, 24.9 °C at the end, no throttling. A throwaway
drawing ("Drawing 11", kraft paper) was used; no other drawing was opened or changed.

| Item | Result | How | Shot |
|---|---|---|---|
| JBX-02: the dead "Dim the rest while selecting" switch is gone | ✅ | Select tapped twice: the menu shows "One finger…", "Select all lines", "Pick locked lines too", no dim row | `01_select_settings_no_dim_row.png` |
| JBX-05 (A3): painting a hidden layer | ✅ | Layer 1 hidden from its menu (crossed eye on the pill), reopened, one finger stroke: toast "\"Layer 1\" was hidden, so it is shown again for your paint.", the eye mark gone, the stroke painted (a watercolour wash), Undo live; Undo removed the stroke and the layer stayed shown | `02_…`, `03_…` |
| JBX-05 (A5): fill pen with the mask selected | ✅ notice and selection | Add mask (toast "Painting on the mask: dark hides, light shows"), Fill → Fill pen, one stroke: toast "The fill pen fills the layer itself. The mask stays selected for your other brushes.", mask chip still ringed. adb on this phone can only draw straight lines, so the fill had no area and the filled pixels themselves were not seen | `04_…` |
| JBX-05 (A6): multiply/screen with the mask selected | ⏳ unit tests only | no glaze brush picked in this pass | |
| JBX-15: paper look in the drawings list | ✅ | The thumbnail written at 13:01 (Drawing 10, kraft) is 301 KB with 261 colours, stddev about 1.6 per channel in the paper; the older flat thumbnails are about 3 KB (one colour). Drawing 11's tile showed the same grain | `05_kraft_thumbnail_zoom.png` |
| JBX-03: a pen on the symmetry line paints | ⏳ needs a real S Pen | On Android 10, `adb shell input stylus …` arrives as a finger (no stylus tool type), and a finger is meant to move the line. Unit-tested with real stylus events (`GuideOverlayPenTouchTest`) | |
| JBX-06: Unibrush wire brushes off the drawer | n/a on the phone | no Unibrush wire brush ships, so there is nothing to see; unit-tested (`ChromeCoreTest`) | |

Method note: once a pen has been used, one finger only pans (owner's ruling), and adb cannot make a pen. For this check
the canvas's "pen seen" flag (`shared_prefs/joybrush.canvas.xml`) was set false with `run-as` while the app was stopped,
then restored; the restored file was diffed identical to the backup before the phone was released.
