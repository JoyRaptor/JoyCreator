# TAB-1: Every screen size: tablets, landscape and wide screens

**Milestone:** M1 (feeds M2)  ·  **State:** 🟡 helper + simple screens built, Note 9-checked; design page with the owner  ·  **Owner:** TAB-1 sizes lane (11-14)  ·  **Checked:** 2026-11-14

## Why it matters
Owner, 11-13: the UI must be "optimized for tablets and different aspect ratios so they look awesome in
every format. Studio drawers for instance may be able to become side panels so they no longer cover the
screen when on a big enough display." Until today the app had never been opened on a tablet. On the test
tablet (Tab S8 Ultra, 1848x2960, landscape) the welcome screen is a phone layout stretched wide: the top
two-thirds empty, one button across the whole width.

## Done when
- [ ] Every screen is checked at four sizes and listed with a screenshot each: phone portrait (the Note 9),
      phone landscape, tablet landscape, tablet portrait. Split-screen and a folded phone are noted where cheap.
- [ ] No screen is a stretched phone layout: content has a sensible maximum width, and wide screens use the
      extra room for something (a second column, a side panel, a larger canvas).
- [ ] Studio: on a wide enough screen a drawer opens as a side panel beside the preview, not over it.
      The phone behaviour is unchanged.
- [ ] The owner has picked the wide-screen designs from a mix-and-match page, and has seen them on the tablet.
- [ ] STATE rows say which sizes each wing is proven on.

## Rules that apply (owner rulings; check memory and FUTURE_PLANS before designing)
- Phone-first: nothing here may make the phone worse; the phone layout stays exactly as drawn.
- dp values stay as drawn: he likes the Note 9's dense look. Do not inflate sizes for big screens; use the
  room for more content, not bigger controls.
- Drawers are never opaque; Frost adds blur only. A side panel follows the same rule.
- Design choices go to him as a mix-and-match page at true size; he picks, the pick becomes the spec.
- A reworked behaviour ships beside the old one where he may want to compare.
- One mechanism, app-wide: decide by the window's width and height classes (not by "is a tablet"), in one
  shared helper, so split-screen and landscape phones get the same treatment.

## §K The owner's picks (11-15): the wide-screen spec
Picks line from the design page: `S1 C · S2 A · S3 C · S4 A · S5 B · S6 C · S7 C | J1 A · J2 A · J3 B · J4 B | L1 A · L2 A · L3 A · L4 B · L5 A`.
His notes overrule the line where they differ (S5 especially). In his words, summarised:

**Studio drawers on a wide window**
- K1. **Editing desk** (side panel, timeline full width under picture and panel) and **Tall column** (panel full
  height, timeline under the picture only) are the two everyday layouts for a tablet sideways. Both must exist and
  be one touch-friendly switch apart in Studio, and be a choice in Settings: effects work wants the tall column,
  cutting wants the wide timeline.
- K2. **Where a drawer goes follows the free space**, which comes from the project's shape against the window's
  shape: a 16:9 piece on an upright tablet leaves room below, so drawers drop down from the bottom (two can stack
  there); a 9:16 piece leaves room beside, so a side panel. Easy to change by hand, because the project decides.
- K3. **The picture moves out of the way as far as the free space allows, never shrinks; where there is no room,
  the see-through drawer overlaps it** (as the transcript panel already does). This replaces pick S5 B.
- K4. Side: right, mirrored by a left-hand switch (S1 C). Width 360 dp, the phone drawer as drawn (S2 A).
- K5. Two drawers: stacked in the one column (S3 C), with a Settings option for **one left and one right**
  (After Effects feel). Small drawers (blend mode: one line) should not waste a tall slot; tall drawers (sprite
  animation) may use the extra room.
- K6. The panel mode is a Settings switch, starting from "wide windows (840 dp and up)" (S6 C). The phone drawer
  stays the default on a phone.
- K7. Joybot's chat stands beside a drawer as the second panel (S7 C).

**JoyBrush on a tablet**
- K8. Tool rail on the left as on the phone (J1 A).
- K9. Layers: the phone's compact strip by default, but **grab its edge and pull it wider** to show each layer's
  name, opacity, lock, duplicate, delete, merge down (what a double-tap opens today); pull it down and **move it
  around**: as much room as you want for layers, never a fixed slice of the screen (J2 A + his note).
- K10. Brushes: a **side panel that can stay open**, brushes listed vertically, most recent first with **starred
  favourites always on top**, filter by category; **hold a brush to keep its settings open** in a drawer or side
  tab while painting (an option) (J3 B). Favourites-on-the-rail: my call.
- K11. Paper fills the screen or steps aside: a Settings option (J4 B; Atlas makes it matter less).

**Lobby** (L1 A · L2 A · L3 A · L4 B · L5 A for now): two columns, room names once, New buttons at phone size in a
row, recent work as one row, floor words grouped bottom left. He will rethink the lobby later: it can show all the
cool things at once and none of the options used the room well.

## What is left
- [ ] Survey: each lane lists its screens' problems on the tablet in its own card (the UI leftovers and lobby
      sessions started 11-13; Studio, JoyBrush, VisLab, TextLab, SpriteLab, Library, Settings to follow).
      Studio (11-13, test tablet, landscape): see [STU-4](STU-4_studio_sweep_leftovers.md) "11-13 afternoon":
      the tool row fits; text drawer rows sit over the preview's upper third; bottom sheets open only peeking;
      the floating pencil covers the first tool cell.
- [x] Shared size-class helper (11-14): `ui/size/SizeClass` (pure; AndroidX's breakpoints 600/840 wide,
      480/900 tall; JVM test `tools/jvm-harness/run-sizeclass.sh`), `WindowSize` (the window's classes, never
      "is a tablet"), `ContentColumn` (centred column measured from the view itself; keeps padding an insets
      listener sets). XML side: `values-w600dp` / `values-w840dp`, never `sw600dp` (the welcome column moved).
      **Use these for every wide-screen change** (Joybot's chat panel already plans to).
- [x] Maximum content width for the simple screens (11-14, built): Settings hub and all 19 settings pages,
      the intro's permissions and consent pages, What's New sit in a 640 dp centred column on a window wider than
      that (`ContentColumn.Page` marker, `ContentColumn.watch` in MainActivity and OnboardingActivity). The
      Library fits more cards across at phone size on a window 600 dp or more (cards >= 168 dp, list rows
      >= 360 dp); under 600 dp it keeps the phone's 2 / 1. Bottom sheets stop at 640 dp (`CustomBottomSheetStyle`
      maxWidth; the Material 3 theme already did). The folder step already had its own column (5a390fa4d).
      **Note 9 (548 dp wide at the owner's dense setting, 1127 dp sideways):** Settings and Library pixel-identical
      before/after (the only change was a "4h ago" turning "5h ago"); the first build showed 3 Library cards there, fixed by the under-600 rule and re-seen: 2 cards (`note9_after_library_projects_grid.png`). Shots
      `tasks/screens/tab1-20261114/`. **Tablet, 11-14 18:03 (sideways, 1480 dp):** Settings in a centred column, the Library 8 phone-size cards across (was 2 cards ~980 dp wide each), the Library options sheet 640 dp and centred (`tablet_land_before/after_*`). **Fold 4 (adb only, broken screen):** cover screen (344 dp) intro unchanged; the unfolded size (829 dp, `wm size`, reset after) puts the permissions page in the column; the tablet's upright size (924 dp) puts the welcome words in their 560 dp column (`fold_*`). Seen there: on a short window (690 dp tall) the permissions page's button sits over the page dots until scrolled (page height, not the column).
      **Fixed 11-14 night (da1e4b702):** sheets open to their full height on any landscape window (`values-land/sheets_tab1.xml`: the app's two sheet bases, `Base.JoySheet.MC` under `CustomBottomSheetStyle` and `Base.JoySheet.M3` via `JoySheetDialog.M3` in the Material 3 themes, get a first height larger than any screen; Material's own automatic height is window height - width x 9/16, about 60 dp on the tablet sideways). Tablet-checked: the Library options sheet and an item's sheet open fully (`tablet_land_sheet_fixed.png`, `tablet_land_item_sheet_fixed.png`). Upright windows unchanged. The intro's permissions and consent pages keep their button above the page dots on a short window (`ui/size/BarClearance`, sum `SizeClass.clearBottomPadding`, JVM-tested); Fold-checked at the short 829 x 690 dp window: the button now sits above the dots (`fold_open_sideways_intro_permissions_after.png`); the cover screen's page is pixel-identical to before (only Samsung's edge-panel handle differs). A page that already clears the dots keeps its padding.
      Open: dialog widths on the tablet not looked at.
- [x] Design page (11-14): [UI-TAB1-wide-screens.html](../design/UI-TAB1-wide-screens.html), sent to the
      orchestrator for the owner. Studio side panels S1-S7 (S7 = Joybot's chat in the same panel region, the
      owner's 11-14 ask), JoyBrush J1-J4, lobby L1-L5 (with DOOR-2). Provisional rule agreed with the Joybot sprites
      lane until he picks: 360 dp, right, full height, from 840 dp. **Waiting on his picks line.**
- [ ] Build the picked designs, wing by wing; phone-check that nothing moved on the Note 9.

## Survey notes from the UI leftovers lane (Tab S8 Ultra, 2960 x 1848, 11-13)
Shots in `tasks/screens/tablet/`.
- Onboarding welcome: phone layout stretched (words bottom-left, one full-width button, empty glow above). Fixed for width >= 600dp with a centred 560dp column; not yet seen on the tablet.
- Onboarding permissions page: rows and the "Allow what's missing" bar run the full width; text is phone-size. Needs the same column.
- Choose-a-folder screen: stock grey, unthemed, content top-left. Needs the Joy Creator look and a centred column.
- Lobby: headline strip, row of four buttons across the whole width, empty lower third.
- Lobby (11-14): the hero picture stretches the full width, Library cards sit at far left with a mostly empty right side (`ui1_tablet_lobby2_20261114.png`).
- What's New (11-14): text hugs the left third, "Got it" is a full-width bar; needs a centred column (`ui1_tablet_whats_new_20261114.png`).
- Settings hub: rows run edge to edge (2900 px wide); tab chips and search bar fine.

## Files and traps
- Studio drawers: `app/.../ui/faditor/**` (ObjectDrawer, TextOverlayDrawer and their Kit); the editor is one
  ~36k-line activity: add the panel host beside the existing drawer host, do not rewrite the drawers.
- The tablet is on Wi-Fi debugging (`PHONE_SLOT=tablet`); its rotation is locked to landscape by the owner:
  do not change it. Tablet portrait needs him to turn it.
