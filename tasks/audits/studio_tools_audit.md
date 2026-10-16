# Studio drawers audit: app/src/main/java/com/fadcam/ui/faditor/tools/*.java

Read-only audit, 2026-10-15. No source file was edited. Scope: the 26 files in `tools/`. Token source: `studiokit/.../faditor/Studio.java`. Shared helpers: `ObjectDrawer.Kit` (in ObjectDrawer.java, lines 1329-1714), `TextOverlayDrawer.Kit` (a second Kit in TextOverlayDrawer.java, lines 50-243), `PipDrawerTabs` (tab helpers), and `app/.../faditor/SheetKit.java` (the sheet family, outside `tools/`). `ColorPickerDialog` and the colour helper/picker were not touched.

Cross-reference: `tasks/AUDIT_20260924_DRAWERS.md` already covers drawer title strings in the editor activity. Items below were checked against the current tree and are not a re-run of that audit.

## Counts

| Category | Actionable items | Notes |
|---|---|---|
| 1. Hard-coded colours | 0 UI literals; 7 duplicated token definitions; 3 hand-typed alphas; 4 other-palette refs | 8 sites excluded by rule (masks, transparent, content swatches) |
| 2. Hand-built duplicates of Kit/SheetKit | 9 | 3 more are intentional one-offs, listed for information |
| 3. Hard-coded user-visible English | 16 sites | 12 are real words; 4 are unit strings |
| 4. Missing content descriptions / hover labels | 4 items (8 lines) | 72 click/touch listener sites checked; 1 unnamed button |

Checked and clean: all 4 CheckBox constructions use `Kit.styleCheck`. All 6 SeekBar/FineSeekBar constructions use `Kit.styleSlider`. Every drawer chip is `Kit.chip` except item 2.9.

## 1. Hard-coded colour literals

No raw `0xFF...`, `Color.parseColor`, `Color.rgb/argb`, or `Color.WHITE`-style constant was found as a UI colour in `tools/`.

Duplicated token definitions (should alias `ObjectDrawer.Kit`):
- TextOverlayDrawer.java:55 SCRIM = alpha(GROUND,0xA3): duplicate of ObjectDrawer.java:89 SCRIM.
- TextOverlayDrawer.java:57 CTL = alpha(DRAWER_INK,0x1A): duplicate of ObjectDrawer.java:1368.
- TextOverlayDrawer.java:59 RING = alpha(DRAWER_INK,0x1F): duplicate of ObjectDrawer.java:1370.
- TextOverlayDrawer.java:61 EDGE = alpha(DRAWER_INK,0x1A): same value as CTL; not in ObjectDrawer.Kit.
- TextOverlayDrawer.java:63 TRACK = alpha(DRAWER_INK,0x2E): duplicate of ObjectDrawer.java:1372.
- TextOverlayDrawer.java:65 GRAB = alpha(DRAWER_INK,0x4D): duplicate of ObjectDrawer.java:1374.
- TextOverlayDrawer.java:67 PRESS_MS (long 140L): duplicate of ObjectDrawer.java:1378 (int 140).

Hand-typed alpha that equals an existing Kit token:
- FaditorToolsDrawer.java:92 SHEET_EDGE = alpha(DRAWER_INK,0x1A): equals Kit.EDGE.
- FaditorToolsDrawer.java:94 CELL_FILL = alpha(DRAWER_INK,0x1A): equals Kit.CTL.
- FaditorToolsDrawer.java:425 alpha(DRAWER_INK,0x4D): equals Kit.GRAB.

Other-palette colour refs (the Kit uses Studio only):
- FaditorToolsDrawer.java:255 ObjectPalette.TEXT (group dot).
- FaditorToolsDrawer.java:256 ObjectPalette.AUDIO (group dot).
- AudioDrawerTabs.java:67 ObjectPalette.AUDIO.
- PuppetDrawerTabs.java:211 default accent = ObjectPalette.IMAGE. ObjectDrawer's default accent is Studio.ARMED (ObjectDrawer.java:269), so the two defaults differ.
- Out of scope, same issue: layers/ObjectPalette.java literals. TEXT 0xFF8C3DFA equals Studio.ROOM_AVATAR_DEEP. AUDIO 0xFF4ADE80 equals Studio.AUDIO.

Excluded by rule (not findings):
- 0x00FFFFFF masks: GradientRampEditorView.java:184, :235, :392; PipDrawerTabs.java:1155.
- Transparent 0x00000000: BlendPickerPopover.java:227; PivotPickerPopover.java:109.
- Chroma-key content swatches: PipDrawerTabs.java:1125 (0x00FF00, 0x0000FF, 0x000000, 0xFFFFFF) and :1164.
- Opacity-as-grey preview: GradientRampEditorView.java:210 (grey derived from data, not a token).

Private aliases of Studio tokens (acceptable, listed for consistency): TXT and TXT_DIM in AudioDrawerTabs.java:59-60, FxPanel.java:60-61, ObjectDrawer.java:92-93, PuppetDrawerTabs.java:79, :82, and BlendPickerPopover.java:54-55 (which uses screen INK, not DRAWER_INK; this matches its opaque sheet, so it is correct).

## 2. Hand-built duplicates of Kit / SheetKit

Actionable:
- 2.1 ObjectDrawer.java:424-428 grip pill is built by hand. It is identical to `Kit.pill(ctx, Kit.GRAB, 0)`.
- 2.2 TextOverlayDrawer.java:50-70 is a second Kit. Its constants duplicate ObjectDrawer.Kit (item 1). Most of its methods already delegate (lines 95-106, 131-133, 151, 174-176), so only the constants need to move.
- 2.3 BlendPickerPopover.java:287-312 `row()` builds a selectable list row by hand (TextView, ROW_ON GradientDrawable, 44dp). It should use `SheetKit.row` / `SheetKit.paintRow(selected)`.
- 2.4 BlendPickerPopover.java:247-253 `sheet()` drifts from the sheet family. Radius is 16dp, colour is RAISED (SHEET_BG, line 50). SheetKit uses 20dp radius (SheetKit.java:73) and PANEL (SheetKit.java:120-126). The comment at lines 240-242 says the popovers "share" a sheet, but it is a second sheet. Pick one.
- 2.5 AudioDrawerTabs.java:815-820 `row()` is hand-built (padding 6/2, no gravity, no min height). It should call `Kit.row(ctx)` (CENTER_VERTICAL, 46dp min) and then set padding.
- 2.6 PuppetDrawerTabs.java:1289-1295 `row()` is hand-built. It should call `Kit.row` and then set its layout params.
- 2.7 PuppetDrawerTabs.java:510-511 (tool cell) and :754-756 (pin chip) rebuild the chip recipe by hand: pill + ring + ON_ALPHA + ink. `Kit.setChipOn` accepts only TextView, so these cannot use it. The ON fill here is the object hue; the Kit uses the drawer accent. Add a View overload for `Kit.setChipOn`, or a Kit icon-chip helper.
- 2.8 GradientRampEditorView.java:96-97 overrides the colour and size that `Kit.styleCheck` sets (INK_DIM and 11.5f). It should keep the Kit's DRAWER_INK / DRAWER_DIM.
- 2.9 LinkTool.java:211 uses `SheetKit.chip` (RAISED, 32dp, 12sp), while every drawer uses `Kit.chip` (CTL, 40dp, 11sp). These are two chip faces for one job. The decision is whether the sheet family and the drawer family stay separate.

Intentional one-offs (no helper exists; listed for information):
- FxPanel.java:1775-1789 disabled chip (ring only, transparent fill).
- ObjectDrawer.java:1080-1088 header toggle is an oval button (Kit has only square/pill faces).
- FaditorToolsDrawer.java:390-430 tile grid and see-through sheet. There is no Kit helper for a grid tile.

## 3. Hard-coded user-visible English

Tool labels (shown on every tool cell via FaditorToolsAdapter.java:874 and FaditorToolsDrawer.java:349). The other tools in the same file use `ctx.getString(R.string...)`. These 7 do not:
- 3.1 FaditorToolRegistry.java:87 "Visualizer".
- 3.2 FaditorToolRegistry.java:112 "Fix audio".
- 3.3 FaditorToolRegistry.java:115 "Beats".
- 3.4 FaditorToolRegistry.java:118 "Align".
- 3.5 FaditorToolRegistry.java:134 "Adjust".
- 3.6 FaditorToolRegistry.java:144 "Consolidate".
- 3.7 FaditorToolRegistry.java:148 "Select".

Gradient editor:
- 3.8 GradientRampEditorView.java:80 "Mirror" (checkRow label).
- 3.9 GradientRampEditorView.java:81 "Flip". A resource already exists: `faditor_tool_flip` (strings.xml:1122).
- 3.10 GradientRampEditorView.java:82 "Solid bands".
- 3.11 GradientRampEditorView.java:393 "Stop color" (ColorPickerDialog title).

Generated headings:
- 3.12 FxPanel.java:1754 `pretty(family.name())` turns an enum name into an English section heading (FxPanel.java:445-448). It needs a resource per family.

Units and readouts (low):
- 3.13 AudioDrawerTabs.java:760 "%.1f dB".
- 3.14 AudioDrawerTabs.java:1246-1247 "C", "R", "L" pan readout.
- 3.15 AudioDrawerTabs.java:1252 "ms".
- 3.16 AudioDrawerTabs.java:1253 "%.2fs".

Out of scope, same problem: studiokit/.../fx/FxRegistry.java has about 70 hard-coded effect names and parameter labels (for example :392-395 "Gaussian Blur" / "Radius", :408-413 "Brightness / Exposure"). FxPanel renders them at FxPanel.java:313, :466 and :1775.

## 4. Missing content descriptions / hover labels

Hover coverage is good. Of 72 click/touch listener sites, all but one are named. Named means the view has `describe`, `setContentDescription`, a tooltip, or a visible text label, or it sits inside a named parent. The exception and the other gaps are below.

- 4.1 RotationDialView.java:42 and onTouchEvent at :188. This custom dial has zero accessibility calls. TalkBack cannot name or adjust it.
- 4.2 GradientRampEditorView.java:124 RampBar, with onTouchEvent at :290. Zero accessibility calls. The stop handles are not named.
- 4.3 FaditorToolsDrawer.java:166 `scrim.setOnClickListener(dismiss)`. The scrim is an unnamed clickable. Name it, or mark it not important for accessibility, since the close button already exists.
- 4.4 ObjectDrawer.java:1033 leaves a header toggle unnamed when the label is null. `toggleLabel` (lines 1040-1072) covers only 9 icons. Any other toggle icon without an explicit label gets no name.
- 4.5 Icon-ligature TextViews are not hidden from TalkBack, so it reads the ligature word: FaditorToolsAdapter.java:270, :294 ("tune"), :768 ("check"), :908 ("expand_more").

## Top 10 fixes, by value

1. Localise the 7 tool labels at FaditorToolRegistry.java:87, 112, 115, 118, 134, 144, 148. They show on every tool cell and are English-only. Follow the `faditor_tool_*` pattern already used in the same file.
2. Localise GradientRampEditorView.java:80, 81 (reuse `faditor_tool_flip`), 82 and 393.
3. Replace the enum-derived headings at FxPanel.java:1754 with a resource per family.
4. Give RotationDialView (:188) and GradientRampEditorView.RampBar (:290) names and accessibility actions.
5. Make TextOverlayDrawer.Kit constants alias ObjectDrawer.Kit (TextOverlayDrawer.java:55-67; ObjectDrawer.java:89). Replace FaditorToolsDrawer.java:92, :94, :425 with Kit tokens.
6. Decide one popover sheet and one selectable row. BlendPickerPopover.java:247-312 should use SheetKit (radius 20, PANEL), or the difference should be written down as an exception.
7. Add a View overload for `Kit.setChipOn` (or an icon-chip helper), then use it at PuppetDrawerTabs.java:510 and :754. This also settles the hue-versus-accent drift.
8. Replace the hand-built rows with `Kit.row`: AudioDrawerTabs.java:815 and PuppetDrawerTabs.java:1289. Remove the overrides at GradientRampEditorView.java:96-97.
9. Replace the grip pill at ObjectDrawer.java:424-428 with `Kit.pill(ctx, Kit.GRAB, 0)`.
10. Give every header toggle a name (ObjectDrawer.java:1033 and :1040-1072). Hide the ligature icons from TalkBack (FaditorToolsAdapter.java:270, :294, :768, :908).
