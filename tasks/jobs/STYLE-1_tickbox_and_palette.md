# STYLE-1: tick boxes for on/off, palette for cyan and magenta

Owner rules: **A (11-21)** a sliding switch is only for two equal choices; a plain on/off is a tick box
(solid cyan box, tick knocked out, never outlined). **B (11-22)** on the phone, cyan and magenta come from
the shared palette (`Studio.ARMED` #22D3EE, `Studio.LIVE` #F43F8E, `s_armed`, `s_live`), never a room's own hex.
Part of [UI-1](UI-1_app_ui_leftovers.md). Lane: Sonnet side session, 11-23.

## Shared pieces
- `SheetKit.tickBox(Context)`: the one tick box (cyan fill from `Studio.ARMED`, tick cut out in `s_on_go`). Still a CompoundButton, so listeners and saved keys are untouched.
- `tools/check_palette_hex.py`: host check; fails if cyan/pink hex is typed in code outside the palette files. Allowlist `OWED` only shrinks.

## Switch inventory
| Site | Was | Now |
|---|---|---|
| `FaditorSettingsBottomSheet.addSwitchRow` | MaterialSwitch (on/off) | tick box |
| `SnapSettingsSheet` master + per-kind rows | MaterialSwitch (on/off) | tick box |
| `FaditorEditorActivity` beats "snap" row | SwitchCompat (on/off) | tick box |
| `SpriteSheetEditorActivity.cellEnabled` (2 builds) | Switch (on/off) | tick box |
| `model/SpaceLink.Switch` | a data class, not a widget | no change |
| **`JoybotSwitch`** (20 layouts, 35 uses, 68 Java sites: legacy settings pages) | Joybot-thumb pill, on/off | **OWED, owner's call**: it is his deliberate "Joybot is the thumb" design (solid cyan, glyph cut out) and is the app's switch for plain on/off. Converting means retiring that design on every settings page. Needs his yes. |

## Hex inventory (cyan/magenta/pink, outside the palette)
Converted to the palette: TextLab (`LookChipRow, LookExportDialog, TextLabActivity, TextLookRow, TlColourDialog, TlKnobPanel, TlLightPad, TlPiecesDialog, TlSheet`), `BringInPanel`, `FaditorMiniFragment` + editor snackbar action colour, `SpriteTheme` (SELECTED, LIVE, ACCENT_SEQ, ACCENT_ALIGN), `PuppetPalette` (STIFF, DANGLE).

Left, with reason:
- JoyBrush `BoardChromeView.kt`, `SelectOverlayView.kt`: JoyBrush lane's files (BOARD). Owed.
- `FaditorEditorActivity` swatch lists (lines ~22239, 22619, 33275), `SlideDesigns`, `ObjectPalette` (room gradient #FF008C), `KnobPanel`/`VisLabSession` (VisLab "Joy" colour set): colours the user picks or room gradients, not UI accents. Judged not rule B; owner can overrule.
- `AppearanceSettingsFragment` eye colours (#00E5FF, Material cyan): the avatar-eye choice list, user data.
- `SpriteOptions` #FF00FF: Joybot's word "magenta" mapped to a colour for the user's sprite; data.
- `toggle_*_cyan/violet` drawables + `FullscreenPreviewActivity`/`HomeFragment` maps: retired FadCam avatar-toggle art, #00E5FF/#D500F9. Dead-ish; delete with the FadCam sever, not here.
- Other spellings (Color.parseColor of other cyans, e.g. #06B6D4) were searched; none found in code.

## Owed: Avatar Studio (held by other sessions, not touched)
`tools/avatarlab/**`, `tasks/avatar/**`, avatar room classes on `claude/priceless-yalow-249f46` and the lobby Avatar door: any switches or room-own cyan/pink there are owed. Avatar web pilot has ~23 cyan/switch mentions; ask in `tasks/avatar/CHANNEL.md` before touching the door. The Note 9 look waits for the Avatar slice-1 lane to release the phone lock.

## State
- [x] Tick box helper, 4 switch sites converted, hex converted, host check passes.
- [x] Cloud build passed 11-23 (run 45297041125, defaultDebug, commit 6821c8f11).
- [ ] Phone look (Note 9): owed, not checked.
- [ ] JoybotSwitch decision (owner).

## 11-24 check of screens added since 11-23 (hover-1 lane, from the code, nothing built or run)
- Looked at the 156 app files changed since the STYLE-1 build and the new ones: the Studio effect swatch grid (`FxSwatchViews`, `FxSwatchRenderer`), part previews (`PartThumbs`), LUT swatches, transition glyphs, `MomentsReader` (Detect moments model), the path model and drawing (`model/path/*`, `PathDraw`; its options screen does not exist yet, the feature is behind a hidden switch), `KeptCopiesSheet`.
- **Switches:** none of them builds a Switch, MaterialSwitch, SwitchCompat or CheckBox. The files that still do are the legacy `JoybotSwitch` pages already on this card (`AnnotationService`, `FadRecHomeFragment`, `DebugLogBottomSheetFragment`, `RemoteFragment`, the three sidebars, `SpriteSheetEditorActivity`'s converted rows): the owner's call, unchanged.
- **Colours:** `tools/check_palette_hex.py` passes. The remaining typed hex in the new files is picture content (the swatches' sample sky, grass and sun, the transition glyphs' two scene colours, the path's default white stroke), not UI accent, so rule B does not apply. The editor swatch lists named above are user-pickable colours, already on this card.
- Result: nothing to fix. The check should be repeated when the path options screen and the Detect moments screen get built.
