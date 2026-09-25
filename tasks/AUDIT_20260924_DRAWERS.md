# Drawer consistency audit — 2026-09-24 (read-only)

Scope: every Studio drawer builder listed in the brief. Paths are relative to
`app/src/main/java/com/fadcam/ui/faditor/`. `FEA` = `FaditorEditorActivity.java`.
ColorPickerDialog is out of scope and not listed. Nothing was edited, built or committed.

**Categories**
- **hex**: a literal colour, OR a token from the wrong ramp. Screen-ramp `Studio.INK*` used inside a drawer is the contrast bug Studio.java measured at 1.09:1, and the drawer ramp is `DRAWER_*` / `Kit.*`. A flat identity colour used as an action colour also counts here.
- **label**: a tappable control with no `Kit.describe` / `SheetKit.label` (no TalkBack name, no stylus hover tooltip).
- **string**: user-facing words typed as a literal.
- **helper**: a hand-rolled control where a shared helper exists, or a duplicate kit.
- **dishonest**: a control that is shown but does nothing, or only toasts.

Transparent fix used below: there is no `Studio.CLEAR`, so the fix is `Studio.alpha(Studio.GROUND, 0)`. That is the same bits as `0x00000000`, but it comes from the palette.

## Counts

| Drawer / file | hex | label | string | helper | dishonest | total |
|---|---|---|---|---|---|---|
| tools/ObjectDrawer.java (shell + Kit) | 0 | 0 | 0 | 0 | 0 | 0 |
| tools/TextOverlayDrawer.java (second Kit) | 1 | 0 | 0 | 2 | 0 | 3 |
| tools/PipDrawerTabs.java | 0 | 3 | 8 | 1 | 3 | 15 |
| tools/AudioDrawerTabs.java | 1 | 1 | 10 | 7 | 0 | 19 |
| tools/FxPanel.java | 0 | 3 | 10 | 5 | 0 | 18 |
| tools/PuppetDrawerTabs.java | 3 | 0 | 8 | 2 | 0 | 13 |
| tools/MaskKeyPanel.java (UNREACHABLE) | 0 | 1 | 1 | 1 | 0 | 3 |
| effects/ColorGradePanel.java (UNREACHABLE) | 0 | 0 | 1 | 1 | 0 | 2 |
| SnapSettingsSheet.java | 0 | 0 | 0 | 1 | 0 | 1 |
| FEA — Text drawer | 8 | 9 | 6 | 6 | 1 | 30 |
| FEA — Caption drawer | 2 | 5 | 11 | 5 | 0 | 23 |
| FEA — Image drawer | 0 | 0 | 5 | 0 | 0* | 5 |
| FEA — Sprite drawer | 0 | 0 | 1 | 0 | 0 | 1 |
| FEA — PiP menu / drawer | 0 | 0 | 1 | 0 | 1 | 2 |
| FEA — Visualizer drawer | 0 | 0 | 2 | 0 | 0 | 2 |
| FEA — createAvSyncView | 0 | 0 | 0 | 0 | 0 | 0 |
| FEA — showExportConfirmation | 0 | 0 | 7 | 1 | 0 | 8 |
| **Total** | **15** | **22** | **71** | **32** | **5** | **145** |

\* The image drawer's dead eyedropper is counted once, under PipDrawerTabs P3.

## Top 15 highest-value fixes (what the owner would notice)

1. **The text drawer's Delete button has no label.** `FEA:36508` is a red trash glyph with no hover and no TalkBack name. Fix: `ObjectDrawer.Kit.describe(deleteGlyph, getString(R.string.faditor_text_delete))`. The string already exists.
2. **Ten unlabeled buttons on the Text tab's top row.** These are B / I / U, Tt / TT / ᴛᴛ, Align and FX (`FEA:34928-34965, 35010, 35086`). Fix: `Kit.describe` each one (E1–E4).
3. **Dead ◇ diamonds on every mask slider row.** There are 7 per mask shape (`PipDrawerTabs:1335/1343, 1385/1397`). Each is dimmed to 35%, has no listener and does nothing. Fix: remove them, or bind a real `KeyframeDiamondControl` (P1/P2).
4. **The eyedropper chip on the Key tab of an Image or an Adjustment layer only toasts "isn't available".** The chip is at `PipDrawerTabs:1165` and the refusing hosts are at `FEA:33848` and `FEA:29385`. Fix: hide the chip when the host cannot sample (P3).
5. **The Text tab's Outline / Glow / Shadow / Dist sliders are raw, unstyled `SeekBar`s**, drawn in the theme's default colours (`FEA:36053, 36256`). They look nothing like the sliders in every other drawer. Fix: `FineSeekBar` + `Kit.styleSlider` (E15/E16).
6. **The Text tab has a second Opacity slider** (`FEA:36841-36870`). It is unstyled, has no diamond, and duplicates the Transform tab's Opacity row (`FEA:34314`). Fix: remove it (E22).
7. **Text-drawer labels use screen greys on the see-through drawer.** The headers, "Plate", "Animation", "Dist", the status lines and the swatch rings (E10/E12/E13/E21) are the 1.09:1 case. Fix: `Kit.sectionLabel` / `Kit.rowLabel` / `Studio.DRAWER_LABEL`.
8. **Caption drawer chips are a fifth chip recipe, and they fade to 45% to mean "off".** `FEA:22047` `styleDrawerChip`, plus the alpha selection at 21274 / 21786 / 22652 / 22683. They look unlike every other drawer and break "off is grey, never a faded colour". Fix: `Kit.chip` + `Kit.setChipOn` (C1/C2).
9. **Selected chips look different on the Effects tab and the Transform/Mask tabs.** `TextOverlayDrawer.Kit.setChipOn` draws a cyan ring; `ObjectDrawer.Kit.setChipOn` draws an accent fill with dark ink. Fix: one kit (T2).
10. **Effects-tab sliders fill grey; every other drawer's fill in the object's colour.** `FxPanel:73 SLIDER_FILL = Studio.DRAWER_DIM` (F1).
11. **Caption Size / Floor use a Material `Slider`** with a caption-coloured thumb and an `OFF` track, and Timing In/Out are raw `SeekBar`s (`FEA:21162, 21854, 23586`). Fix: `Kit.styleSlider` (C9/C10).
12. **Unlabeled caption controls.** The colour swatches, the track-pill eye ("visibility" is read out as a ligature), the "+" add-track button and the truncate "check_box" ligature (C4–C7).
13. **The Audio drawer draws its own kit.** Row labels are 8sp mono UPPERCASE ("LEVEL") where the other drawers use 11sp ("Opacity"). Values sit in pills where the other drawers show plain numbers. Every word carries a drop shadow the other drawers dropped (A2/A4).
14. **Every export's default filename is `Faditor_yyyyMMdd…`** (`FEA:12660`). The product is Joy Creator (ExportConf-1).
15. **"Clear" on the Text tab silently does nothing on a timer item** (`FEA:36484 if (session.item.isTimer()) return;`). It is also violet (`Studio.GUIDE`), a colour whose meaning is "alignment guide". Fix: hide it on timers and use `Kit.chip` (E20).

Also worth a look after the top 15:
- No slider in Transform, Mask or Effects has a TalkBack name (P4/F3).
- The PiP sound-strip toggle only toasts while the overlay's sound is off (PiP-2).

---

## tools/ObjectDrawer.java — clean
The shell and its `Kit` are the reference. `close.setText("✕")` (381) is a glyph that is already described (388). Every toggle icon in use maps to a label in `toggleLabel` (1041), or passes its own label.

## tools/TextOverlayDrawer.java — the second Kit (used by FxPanel, pickers, ColorGradePanel)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| T1 | 290 | hex | `b.setColor(0x00000000);` | `Studio.alpha(Studio.GROUND, 0)` |
| T2 | 129–170 | helper | `setChipOn`: `g.setStroke(... on ? Studio.ARMED : RING)` | A second chip face. The ObjectDrawer chip is accent-fill with no ring and a 40dp min height; this one is a cyan ring with no min height. Make `chip`, `setChipOn`, `sectionLabel`, `styleSlider`, `describe` and `pressable` delegate to `ObjectDrawer.Kit`, then retire the duplicates. |
| T3 | 55, 255 | helper | `SCRIM = Studio.alpha(Studio.GROUND, 0xA3)` → `surface()` | Popovers are 64% opaque; drawers use the user-set `ObjectDrawer.Kit.drawerFill(ctx)` (50% by default). Use `drawerFill(ctx)` in `surface()`. |

## tools/PipDrawerTabs.java (Transform / Mask / Key / Blend tabs, shared by PiP, Image, Text, Sprite, Viz, Adjustment)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| P1 | 1335, 1343 | dishonest | `TextView key = stepper(ctx, "◇", d); … key.setAlpha(0.35f);` | Six per shape. Delete `key`, and keep the column with a `Space` of the same 30dp width. Or bind `KeyframeDiamondControl` to `MaskAnimator.trackFor(slot,i)`. |
| P2 | 1385, 1397 | dishonest | same placeholder in `maskRotateRow` | Same as P1. |
| P3 | 1164–1172 (+FEA 33842–33850, 29381–29387) | dishonest | `dropper.setOnClickListener(v -> host.pickColorFromPreview(...))` | On Image and Adjustment-layer drawers the host only toasts "isn't available". Add `default boolean canPickColor() { return true; }` to `Host`, return false in those two hosts, and do not add `dropper` when it is false. |
| P4 | 339, 446, 1312 | label | `FineSeekBar bar = new FineSeekBar(ctx);` | Sliders have no name. Add `bar.setContentDescription(prop.label())` (for `slider()`, `ctx.getString(labelRes)`), the same way AudioDrawerTabs:967 does. For split Scale X/Y (`scaleSlider` 336) use each prop's own label. |
| P5 | 393, 553, 1408 | label | `value.setOnClickListener(v -> promptForValue(...))` | Tap-to-type readout with no hint. Add `ViewCompat.setTooltipText(value, getString(R.string.lane_a_value_type_hint))` (new: "Tap to type an exact value"). Use a tooltip only: a description would replace the number, which is why AudioDrawerTabs:958 does the same. |
| P6 | 1029, 1057 | label | `chip(ctx, "◆ Key at playhead", d)` / `chip(ctx, "Clear", d)` | `Kit.describe(addKey, …lane_a_mask_key_here)` and `Kit.describe(clearKeys, …lane_a_mask_clear_keys)` (new). |
| P7 | 620, 625 | string | `input.setHint("720, -45, 16x"); // TODO(strings)` | `R.string.lane_a_rotation_hint`, and `lane_a_range_hint` ("%1$s … %2$s") |
| P8 | 821 | string | `"No mask. Add one to show only part of this object."` | `R.string.lane_a_mask_empty` |
| P9 | 877 | string | `{"Add", "Subtract", "Intersect"}` | `lane_a_mask_mode_add/_subtract/_intersect` (new; `faditor_blend_add` is the blend meaning, so do not reuse it) |
| P10 | 967 | string | `link.setText("Move with the object");` | `R.string.faditor_mask_link_object` (MaskKeyPanel:220 has the same text) |
| P11 | 1013–1014 | string | `"Off: the mask stays put and the object moves under it. "…` | `R.string.faditor_mask_link_hint` |
| P12 | 1027 | string | `spec.hasMaskKeys() ? "animated" : "not animated"` | `lane_a_mask_animated` / `lane_a_mask_static` |
| P13 | 1029, 1057 | string | `"◆ Key at playhead"`, `"Clear"` | `lane_a_mask_key_chip`; `R.string.faditor_kf_clear` (exists) |
| P14 | 1054 | string | `"Shape " + (sel[0] + 1) + " keyed at " + …` | `lane_a_mask_keyed_toast` ("Shape %1$d keyed at %2$.1fs") |
| P15 | 1494–1508 | helper | `TextView t = new TextView(ctx); t.setText(glyph);` | The stepper is hand-rolled with `TXT` and no press feedback. Move it into `ObjectDrawer.Kit.stepper(ctx, glyph, nameRes)` with `Kit.pressable`, so FxPanel's `keyChevron` (1429) can share it. |

## tools/AudioDrawerTabs.java (Level / FX tabs)
The file carries its own private copy of the Kit. Its visible differences are A2 and A4.
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| A1 | 70–76 | helper | `CTL_FILL = Studio.alpha(Studio.DRAWER_INK, 0x1A)` (+ `CTL_RING`, `TRACK`) | `ObjectDrawer.Kit.CTL / RING / TRACK` |
| A2 | 863–897 | helper | `text()` adds `setShadowLayer(... SHADOW)`; `inlineLabel()` = 8sp mono caps | Row labels → `Kit.rowLabel(ctx, label, LABEL_W)`, section heads → `Kit.sectionLabel`. Drop the per-word shadow the other drawers removed. |
| A3 | 913–935 | helper | `private static TextView chip(...)` | `Kit.chip` + `Kit.chipLp` + `Kit.pressable` |
| A4 | 938–960 | helper | `valuePill(...)` pill + mono 12sp | Pick ONE readout face for all drawers. Either promote `valuePill` into `Kit.value`, or use `Kit.value` here. Today Level looks different from Transform. |
| A5 | 963–1000 | helper | private `styleSlider(ctx, bar, ACCENT)` | `drawer.setAccent(ObjectPalette.AUDIO)` + `Kit.styleSlider(bar)` |
| A6 | 1004–1050 | helper | private `checkBox()` / `checkBoxFace()` | `new CheckBox` + `Kit.styleCheck(cb)` |
| A7 | 1054–1100 | helper | private `pill/rounded/press/hoverLabel` | `Kit.pill`, `Kit.pressable`, `Kit.describe` |
| A8 | 1031 | hex | `rounded(ctx, 0x00000000, TXT_DIM, 5)` | Goes away with A6; otherwise `Studio.alpha(Studio.GROUND, 0)` |
| A9 | 165–166, 1292, 1370 | string | `fadeRow(ctx, fades, "In", …) // TODO(strings)`; `"Fade in"`, `"Fade in (s)"` | `lane_b_audio_fade_in_slider` / `_out_slider` (exist) + `lane_b_audio_fade_prompt` (new) |
| A10 | 192, 265, 330 | string | `inlineLabel(ctx, "Level", LABEL_W)`, `"Pan"`, prop label `"Level"` | `R.string.lane_b_audio_level_slider` / `lane_b_audio_pan_slider` (exist) |
| A11 | 361, 378–380 | string | `chip(ctx, "Clear")`, `"Flat gain — ◇ drops the first envelope point"`, `"Envelope · " + n + " pts"` | `faditor_kf_clear` (exists); `lane_b_audio_env_flat`, `lane_b_audio_env_count` (plural) |
| A12 | 521–529, 580–587 | string | `"Processing…"`, `"Processed audio in use — original kept."`, `"Failed: "` | `lane_b_audio_bake_*` |
| A13 | 614–626 | string | `chip(ctx, "Reduce noise")`, `"Normalize loudness"`, `"Runs ffmpeg offline…"` | `lane_b_audio_denoise`, `_normalize`, `_bake_note` |
| A14 | 689–709 | string | `checkBox(ctx, "Enhance voice")`, toast `"Voice chain ON — this clip only"` | `lane_b_audio_voice*` |
| A15 | 717–726 | string | `chip(ctx, "◆ Beat-reactive link…")` + note | `lane_b_audio_beat_link_chip`, `_beat_link_note` |
| A16 | 729, 754–758 | string | `sectionLabel(ctx, "Compressor")`, `"FX chain BYPASSED — …"` | `lane_b_audio_comp*` |
| A17 | 1308–1371 | string | `input.setHint("0 … 200")`, `confirmDialog(ctx, "Level (%)", …)` | `lane_b_audio_prompt_*` |
| A18 | 295, 367, 539, 694, 1232, 1247, 1280, 1347 | string | `host.recordUndo("Pan", …)` | Undo names are announced ("Undid …", FEA:13637), so use `getString(...)` |
| A19 | 529, 614, 619 | label | `chip(ctx, "Revert to original")` etc. | Has text but no hover. Add `hoverLabel` (→ `Kit.describe`) with a one-line "what it does", as linkBtn (718) already has. |

## tools/FxPanel.java (Effects tab of every object and adjustment layer)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| F1 | 73 | helper | `SLIDER_FILL = Studio.DRAWER_DIM;` | Grey fill; every other drawer fills with the object accent. Use `ObjectDrawer.Kit.styleSlider(bar)` (it reads `Kit.accent()`). |
| F2 | 1029–1034 | helper | `SeekBar bar = new SeekBar(ctx);` | `FineSeekBar` (the fine-drag every other drawer slider has) |
| F3 | 920 / 937 → 1029 | label | slider built without a name | In `sliderRow` add `bar.setContentDescription(labelText)` |
| F4 | 997–1006 | helper | `private static TextView label(...)` | `ObjectDrawer.Kit.rowLabel(ctx, text, 86)` |
| F5 | 235, 258, 275, 337, 642 | helper | `TextView cost = new TextView(ctx); cost.setTextColor(TXT_DIM);` | `ObjectDrawer.Kit.note(ctx, …)` (the warnings add `.setTextColor(Studio.CAREFUL)`) |
| F6 | 1778–1782 | helper | `new GradientDrawable(); hollow.setStroke(..., TextOverlayDrawer.Kit.RING)` | `Kit.background(c, Kit.pill(ctx, Studio.alpha(Studio.GROUND,0), Kit.RING))` |
| F7 | 788 | label | `strip.setOnClickListener(v -> openGradientDialog(...))` | Plain `View`, no name. Add `Kit.describe(strip, getString(R.string.faditor_fx_edit_gradient))` (new). |
| F8 | 1651–1667 | label | saved-look chip; delete is a hidden long-press | `setContentDescription(getString(R.string.faditor_fx_look_desc, name))` ("Load %s. Hold to delete"). Use contentDescription only, because long-press is its gesture. |
| F9 | 277–280 | string | `"This layer changes nothing yet. Add an effect…"` | `faditor_fx_empty_layer` / `faditor_fx_empty_object` |
| F10 | 423, 842 | string | `"Positioning in preview — done" : "Position in preview"` | `faditor_fx_position_*`, `faditor_fx_curve_edit_*` |
| F11 | 434, 445, 823 | string | `sliderRow(ctx, "Opacity", …)`, `label(ctx, "Blend", d)`, `"Vertices"` | `faditor_tool_opacity` (exists), `faditor_fx_blend`, `faditor_fx_vertices` |
| F12 | 475, 478 | string | `chip(ctx, on ? "On" : "Off", d)` | `R.string.setting_on` / `setting_off` (exist) |
| F13 | 785, 851, 1621, 1735 | string | `"Edit"`, `"Straighten"`, `"Save look"`, `"＋ Add effect"` | `faditor_tools_edit` (exists), `faditor_fx_straighten`, `faditor_fx_save_look`, `faditor_fx_add` |
| F14 | 900, 1624–1636 | string | `.setPositiveButton("Done", …)`, `setHint("Name this look")`, `"Save"`, `"Cancel"` | `faditor_fx_look_*`; `universal_cancel` (exists) |
| F15 | 1659–1674, 1786–1792 | string | `"Load '" + name + "'?"`, `" needs its own pass"` dialog | `faditor_fx_load_confirm`, `faditor_fx_needs_pass_*` |
| F16 | 1528, 1633, 1695, 1714 | string | `param.label + " keys cleared"`, `"Saved '" + name + "'"` | `faditor_fx_toast_*`; 1714 builds `" effect"/" effects"` → plurals |
| F17 | 1645 | string | `sectionLabel(ctx, "Saved looks")` | `faditor_fx_saved_looks` |
| F18 | 351, 366, 436, 447, 828, 854, 1182, 1497, 1522, 1720, 1797 | string | `structural(..., "Delete " + def.displayName, …)` | Undo names are announced; use `getString(R.string.faditor_undo_*, …)` |

## tools/PuppetDrawerTabs.java
Labels are exemplary: every control goes through `hoverLabel` with a string resource. The problems are a private kit and literal words.
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| U1 | 88–99, 1293–1430 | helper | private `CTL_FILL/CTL_RING/TRACK`, `text/sectionLabel/valueText/styleSlider/checkBoxFace/pill/rounded/press/hoverLabel` | `ObjectDrawer.Kit.*` (same mapping as A1–A7). Shadowed text differs visibly from the Kit drawers. |
| U2 | 81 | helper | `TXT_FAINT = Studio.DRAWER_LABEL;` | Identical to `TXT_DIM`. Delete it and use `Studio.DRAWER_LABEL`. |
| U3 | 436 | hex | `ring.setColor(rig.recordOnTouch ? … : 0x00000000);` | `Studio.alpha(Studio.GROUND, 0)` |
| U4 | 777 | hex | `g.setColor(0x00000000);` | same |
| U5 | 1364 | hex | `rounded(d, 0x00000000, 0, 5)` | same (or `Kit.styleCheck`) |
| U6 | 306, 452–453 | string | `hoverLabel(swatch, pin.typeLabel() + " pin. Tap to change type.")`, `"Recording when you touch a pin…"` | `lane_b_puppet_swatch`, `lane_b_puppet_rec_on/_off` |
| U7 | 385 | string | `pos.setText("sim")` | `lane_b_puppet_sim` |
| U8 | 479–484 | string | `addTool(..., "Grab", …)` ×6 | `lane_b_puppet_tool_grab…bone` |
| U9 | 547–549 | string | `addScope(..., "Selected")` ×3 | `lane_b_puppet_scope_*` |
| U10 | 587–588, 634–635, 659–675, 897–898, 915–920 | string | `hint(ctx, d, "An anchor has nothing else to set…")` | `lane_b_puppet_hint_*` |
| U11 | 597–648, 606–618 | string | `slider(..., "Springiness", …)`, `addToggle(..., "Reach ring", …)` | `lane_b_puppet_pin_*` |
| U12 | 705 | string | `"Removes " + detail + " and every keyframe"` | `lane_b_puppet_reset_desc` (%s) |
| U13 | 835–935 | string | `addReadout(..., "Rest angle", …)`, `slider(..., "Gravity", …)` | `lane_b_puppet_bone_*`, `lane_b_puppet_rig_*` |

## tools/MaskKeyPanel.java — UNREACHABLE
Its only opener is `FEA:28775 showMaskDialog`, which has **zero callers**. It is a superseded duplicate of `PipDrawerTabs.maskTab/chromaTab`, not a half-built feature, and it has none of their per-shape stacking. Fixing it is wasted effort unless it is revived. Its screen-ramp inks are correct for an opaque dialog.
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| M1 | 220, 243, 256, 258, 279, 282 | string | `link.setText("Move with the object");` | Same strings as P10–P14 |
| M2 | 497–551 | helper | `chipButton`, `addHeader`, `new SeekBar(activity)` unstyled | `SheetKit.chip` / `SheetKit.sectionLabel` / styled slider |
| M3 | 539 | label | `SeekBar bar = new SeekBar(activity);` | `bar.setContentDescription(activity.getString(labelRes))` |

## effects/ColorGradePanel.java — UNREACHABLE
No class references it (grep of `app/src/main` finds only itself).
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| G1 | rebuild(): 10 `addSlider("Exposure", …)` | string | `addSlider("Exposure", -100, 100, 0, …)` | `faditor_grade_*` |
| G2 | addSlider | helper | `new TextView` label + `new SeekBar` + `TextOverlayDrawer.Kit.styleSlider(seekBar, Studio.DRAWER_DIM)` | `Kit.rowLabel` + `FineSeekBar` + `ObjectDrawer.Kit.styleSlider` |

## SnapSettingsSheet.java
Otherwise model code: SheetKit throughout, every chip and switch labelled, all strings from resources.
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| S1 | 156, 159–163 | helper | `final View chipRows = chips;` | The Rotation step chips (`steps`) stay at full strength when the Rotation switch is off, while the strength chips dim. Dim `steps` in the same listener. |

---

## FaditorEditorActivity.java

### Text drawer — showTextOverlayEditor · buildTextTopRow · buildTextStyleSection · buildTextMotionRangeSection · buildOverlayAnimationControls (+ their helpers)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| E1 | 34928, 34930, 34932 | label | `TextView boldBtn = topRowToggle(this, d, "B");` | `Kit.describe(boldBtn, getString(R.string.desc_bold))`, `desc_italic` (exist); `faditor_text_underline` (new) |
| E2 | 34963–34965 | label | `topRowToggle(this, d, "Tt")` / `"TT"` / `"ᴛᴛ"` | `Kit.describe` with `faditor_text_case_first/_all/_small` (new) |
| E3 | 35010 | label | `alignBtn.setOnClickListener(...)` (AlignIconView has no description) | `Kit.describe(alignBtn, getString(R.string.faditor_text_align))`, and re-describe on tap with the current alignment |
| E4 | 35086 | label | `fxBtn.setOnClickListener(v -> showTextFxDrawer(item));` | `Kit.describe(fxBtn, getString(R.string.drawer_tab_effects))` |
| E5 | 35075–35076 | hex | `fxBg.setColor(hasFx ? Studio.alpha(Studio.GUIDE, 0x55) : Studio.alpha(Studio.INK, 0x22));` | Off: `Kit.CTL` fill + `Kit.RING`. On: `Kit.onFill(Kit.accent())`. GUIDE means "snap line", not "has effects". |
| E6 | 35162–35178 | helper | `topRowChip`: `new TextView`, `Studio.INK`, `floating_button_item_bg` | `ObjectDrawer.Kit.chip` + `Kit.setChipOn`. `styleToggleState` (32003) already paints the Kit's on-fill, so the off state is the only odd one out. |
| E7 | 32026 | hex | `new int[]{Kit.onFill(accent), 0x00000000}` | `Studio.alpha(Studio.GROUND, 0)` |
| E8 | 22583, 22607 | hex + label + string | `p.setColor(Studio.INK);` … `v.setContentDescription("Text motion"); // TODO(strings)` | `Studio.DRAWER_INK`; `Kit.describe(v, getString(R.string.faditor_lc_motion_title))` (exists, "Motion") |
| E9 | 36148 | label | `swatch.setOnClickListener(v -> { … ColorPickerDialog.show(this, title, …` | `Kit.describe(swatch, title)`. The title is already passed in. |
| E10 | 36142–36143 | hex | `bg.setStroke(Math.round(1.5f * d), Studio.INK_FAINT);` / `? 0x00000000 : c` | `Studio.DRAWER_LABEL` (same as PipDrawerTabs:1152); the ternary is a no-op, so use `c` |
| E11 | 34914 | string | `"Text",` (colour-picker title) | `faditor_text_color` (new) |
| E12 | 35778–35785, 36811–36818 | helper | `header.setTextColor(Studio.INK_FAINT); header.setTextSize(12); … setAllCaps(true)` | `ObjectDrawer.Kit.sectionLabel(this, getString(R.string.faditor_text_decor_section))` / `faditor_kf_section` |
| E13 | 35848–35850 | hex | `label.setTextColor(Studio.INK_FAINT);` ("Plate") | `Kit.rowLabel(this, getString(R.string.faditor_text_decor_plate), 84)` |
| E14 | 36045–36050 | helper | `desc.setTextColor(Studio.INK_DIM); desc.setWidth(Math.round(84 * d));` | `Kit.rowLabel(this, label, 84)` |
| E15 | 36053 | helper | `android.widget.SeekBar bar = new android.widget.SeekBar(this);` | `FineSeekBar` + `Kit.styleSlider(bar)` + `setContentDescription(label)`. Unstyled today: the theme's default slider in a drawer. |
| E16 | 36250–36256 | helper + string | `distLabel.setText("Dist"); // TODO(strings)` … `new android.widget.SeekBar(this)` | `Kit.rowLabel` + `faditor_text_shadow_distance` (new); slider as E15 |
| E17 | 36202; 36760, 36767 | label + hex | `ShadowDirectionKnob knob = …` (no name); `paint.setColor(Studio.INK_OFF)` / `Studio.GUIDE` | `Kit.describe(knob, getString(R.string.faditor_text_shadow_direction))`; ring `Kit.RING`, dot `Kit.accent()` |
| E18 | 36206–36225 | label + string | `angleReadout.setOnClickListener(...)`, `.setTitle("Shadow angle")` | `Kit.value(this, 40)` + tooltip hint (as P5); `faditor_text_shadow_angle` |
| E19 | 36503–36508 | label | `deleteGlyph.setOnClickListener(v -> deleteTextOverlay(item, session));` | `Kit.describe(deleteGlyph, getString(R.string.faditor_text_delete))` (exists) |
| E20 | 36477–36484, 36516–36519 | dishonest + hex | `if (session.item.isTimer()) return;` / `clearChip.setTextColor(Studio.GUIDE)` / `INK_OFF` | Do not add `clearChip` when `item.isTimer()`. Build it with `Kit.chip` and `setEnabled(hasSpans)` instead of violet/off-grey text. |
| E21 | 36363–36365, 36436, 36469 | hex + string | `motionLabel.setText("Animation"); … setTextColor(Studio.INK_FAINT)`; `sep … Studio.alpha(Studio.INK, 0x33)` | `Kit.rowLabel` + `faditor_text_anim_label`; separator `Kit.RING`; status `Kit.note` |
| E22 | 36841–36870 | helper + string | `opLabel.setText("OPACITY");` + raw `SeekBar opBar` | A duplicate of the Transform tab's Opacity row (34314, which has a real diamond). Delete the block. |

### Caption drawer — showCaptionDrawer · buildCaptionStyleTab · buildCaptionFitTab · buildTrackPillsRow/fillTrackPillsRow (+ helpers)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| C1 | 22047–22058 | helper | `styleDrawerChip`: `chip.setTextColor(Studio.INK); … floating_button_item_bg` | `ObjectDrawer.Kit.chip` (fifth chip recipe in the editor). Used by 21271, 21301, 21321, 21783, 21887, 21914–21918, 22649, 22680. |
| C2 | 21274, 21786, 22652, 22683 | helper | `ab.setAlpha(cur.anim == anim ? 0.88f : 0.45f);` | `Kit.setChipOn(ab, cur.anim == anim)`. Off is grey, never a faded colour. |
| C3 | 22617, 22630, 22667, 22698, 21450, 22254, 23518, 23526, 23532, 23581 | hex | `tl.setTextColor(Studio.INK_FAINT)`, `bg.setStroke(..., Studio.INK_OFF)`, `btn.setTextColor(Studio.INK_DIM)` | Drawer ramp: `Studio.DRAWER_LABEL` (labels, rings), `Studio.DRAWER_DIM` (icons/off) |
| C4 | 22636, 22669 | label | `swatch.setOnClickListener(v -> ColorPickerDialog.show(this, label, …` | `Kit.describe(swatch, label)` |
| C5 | 21447–21453 | label | `eye.setText(isEnabled ? "visibility" : "visibility_off");` | Read aloud as a ligature. Use `Kit.describe(eye, getString(isEnabled ? R.string.lane_b_caption_hide : R.string.lane_b_caption_show))` (new). Colour `INK_OFF` → `DRAWER_DIM`. |
| C6 | 21496–21504 | label | `add.setText("+"); … add.setOnClickListener(v -> showWireCaptionTrackDialog());` | `Kit.chip` + `Kit.describe(add, …lane_b_caption_add_track)` |
| C7 | 21802–21812 | label + helper | `truncateBox.setText(... "check_box" : "check_box_outline_blank")` | A real `CheckBox` + `Kit.styleCheck` with the label text, replacing the glyph + separate label |
| C8 | 22708, 22098, 22178, 22263 | label | `btn.setContentDescription(cd);` | `Kit.describe` (adds the hover tooltip). Only the long-press dials keep contentDescription-only. |
| C9 | 21162–21172, 21854–21864 | helper | `new com.google.android.material.slider.Slider(...)`, `setTrackInactiveTintList(... Studio.OFF)` | `FineSeekBar` + `Kit.styleSlider` (white thumb, `Kit.TRACK`, accent fill); `drawer.setAccent(CAPTION)` is already set |
| C10 | 23586 | helper | `SeekBar bar = new SeekBar(this);` (Timing In/Out) | `FineSeekBar` + `Kit.styleSlider` + name |
| C11 | 21921 | hex | `allChip.setTextColor(Studio.VIDEO);` | Identity blue used as an action colour. Use `Kit.chip` default ink, or `TextOverlayDrawer.Kit.setPrimaryAction` if it is THE action. |
| C12 | 21202, 21261, 21315, 21347, 21771, 21831, 21839, 21848 | helper | `new TextView` + `setTextColor(Studio.DRAWER_DIM)` + `setShadowLayer(...)` each time | `Kit.rowLabel` / `Kit.note` |
| C13 | 21021 | string | `"Fit", R.drawable.ic_caption_fit_24` | `getString(R.string.drawer_fit)` (exists) |
| C14 | 21203, 21262, 21316 | string | `fontLabel.setText("Font")`, `"Highlight"`, `"Motion"` | `faditor_font_picker_title`, `faditor_lc_motion_title` (exist); `caption_highlight` (new) |
| C15 | 21251–21254 | string | `addCaptionActionIcon(fontRow, d, "save", "Save as my style", …)` ×4 | `caption_style_save/_delete/_copy/_import` |
| C16 | 21268 | string | `{"Pop", "Zoom", "Bounce"}` | `caption_anim_pop/_zoom/_bounce` |
| C17 | 21293–21298, 21303 | string | `addColorControl(..., "Text", …)`, `"Box"`, `"Outline"`, `"Shadow"`, `"Box…"` | `faditor_text_decor_stroke` / `_shadow` (exist); `caption_box`, `caption_box_more` |
| C18 | 21348 | string | `empty.setText("No captioned clip selected");` | `caption_drawer_empty` |
| C19 | 21773–21779, 21809 | string | `{"Off", "Uniform", "Per cue"}`, `"truncate on"` | `setting_off` (exists) + `caption_fit_*` |
| C20 | 21832, 21840, 21849, 22098, 22178 | string | `"Words"`, `"Max lines"`, `"Floor"`, `setContentDescription("Words per caption")` | `caption_fit_words/_lines/_floor` |
| C21 | 21889–21920, 22030 | string | `"Grouping: Slide"`, `{"Grow: center", …}`, `"Copy to all"`, toast `"Anchor + align applied to all tracks"` | `caption_group_*`, `caption_grow_*`, `caption_align_*`, `caption_copy_all*` |
| C22 | 22263 | string | `v.setContentDescription("Caption position"); // TODO(strings)` | `caption_position` |
| C23 | 23517, 23525, 23542, 23550–23552 | string | `heading.setText("Timing")`, `"In " + in + "%   Out "…` | `caption_timing*` |

### Image drawer — showImageOverlayDrawer · buildOverlayTransformRows · buildImageMoveTab
`buildImageMoveTab` delegates to `buildLanesTab` and is clean. The dead eyedropper is P3.
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| I1 | 33848 | string | `"Pick a swatch — the eyedropper isn't available on an image overlay"` | Goes away with P3 |
| I2 | 33869 | string | `"Image", getString(R.string.drawer_tab_transform)` | `faditor_tool_sticker` ("Image", exists) |
| I3 | 33928 | string | `"Effects", R.drawable.ic_fx_24` | `R.string.drawer_tab_effects` (exists) |
| I4 | 33951, 34001 | string | `"Puppet"` … `if ("Puppet".equals(drawer.currentTabTitle())` | `drawer_tab_puppet` (new), and compare by `activeTabIndex()` or the same resource so a translation cannot break the pin-restore |
| I5 | 34293–34314 | string | `overlayMenuProp(o, K_X, "Pos X", …)`, `"Scale"`, `"Scale X"`, `"Rotate"`, `"Opacity"` | `faditor_prop_pos_x/_pos_y/_scale/_scale_x/_scale_y` (new), `faditor_tool_rotate`, `faditor_tool_opacity` (exist). Share them with PiP-1 and V-2. |

### Sprite drawer — showSpriteDrawer
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| SP1 | 28228 | string | `sheet != null ? sheet.getName() : "Sprite";` | `R.string.sprite_editor_default_name` (exists) |

### PiP menu / drawer — showObjectMenuSheetForPipClip (+ showPipDrawer toggles)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| PiP-1 | 29918–29932 | string | `pipMenuProp(c, …X, "Pos X", …)` … `"Opacity"` | Same resources as I5 |
| PiP-2 | 30383–30396 | dishonest | `Toast.makeText(this, "Turn this overlay's sound on first", …)` | The sound-strip toggle is always shown, but with sound off it only toasts. Add it to `toggles` only when `c.isOverlayAudioEnabled()`, or show it dimmed and non-clickable. Its string goes away with the fix. |

### Visualizer drawer — showVisualizerObjectDrawer · buildVisualizerTransformTab
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| V-1 | 31285 | string | `"Visualizer", getString(R.string.drawer_tab_transform)` | `drawer_title_visualizer` (new) |
| V-2 | 31324–31345 | string | `staticProp("viz_x", "Pos X", …)`, `"Width"`, `"Height"`, `"Rotate"` | I5 resources + `faditor_mask_w/_h` (exist: "Width"/"Height") |

### createAvSyncView — clean (the reference implementation)
Kit.row, rowLabel, FineSeekBar + Kit.styleSlider + name, Kit.value, Kit.note, Kit.chip + describe + pressable, all strings from resources.

### showExportConfirmation (an opaque dialog, so the screen ramp is correct here)
| # | Line | Cat | Snippet | Fix |
|---|---|---|---|---|
| ExportConf-1 | 12660 | string | `final String defaultExportBaseName = "Faditor_"` | Every export is named after the legacy fork. Use `getString(R.string.export_default_basename)` = "JoyCreator_". |
| ExportConf-2 | 12628 | string | `" • " + tl.getAudioClips().size() + " audio"` | plural `export_audio_count` |
| ExportConf-3 | 12704, 12755, 12760, 12863 | string | `{"Off", "YouTube -14 LUFS", …}`, `{"Original", "1080p", …}`, `{"High", "Medium", "Low"}` | `string-array` resources; `remote_quality_high/_medium/_low` exist |
| ExportConf-4 | 12734, 12785, 12789, 12862 | string | `buildExportSettingSpinner(root, "Loudness target", …)` | `export_loudness`, `row_resolution_title` (exists), `export_quality`, `export_format` |
| ExportConf-5 | 12708, 12729, 12742 | string | `"Measured: -- LUFS → Target: Off"` | `export_loudness_readout` (%1$s / %2$s) |
| ExportConf-6 | 12806, 12818, 12837, 12848–12851, 12866, 12880, 12941, 12974 | string | `audioOnlyBox.setText("Export audio only (.m4a)")` etc. | `export_audio_only*`, `export_frame*`, `export_time_hint` |
| ExportConf-7 | 13005, 13011–13013 | string | `frameError.setText("Enter a time like 01:23.4 …")` | `export_frame_error_*` |
| ExportConf-8 | 12766–12780 | helper | `lowBandwidthChip = new TextView(this); … setBackgroundResource(R.drawable.settings_home_row_bg)` | `SheetKit.chip(this, getString(R.string.export_draft_chip))` (the describe is already there) |

---

## Structural notes (not counted)
- **There are four chip recipes and four slider looks in the drawers today.** Chips: `ObjectDrawer.Kit`, `TextOverlayDrawer.Kit`, AudioDrawerTabs/PuppetDrawerTabs private copies, and caption `styleDrawerChip`, with text `topRowChip` as a fifth. Collapsing T2, A1–A7, U1, C1 and E6 onto `ObjectDrawer.Kit` removes most of the visible mismatch in one sweep.
- `diamondButton` (FEA:36085) is unreachable: every `buildStyleRow` caller passes a `keyframeProp`. It is not a live control, so it is not counted.
- `ObjectDrawer.toggleLabel` returns null for any icon it does not know. Every toggle in use today is covered or passes its own label, but a new icon would silently ship unlabeled. Consider falling back to the tab/drawer name, or asserting in debug builds.
