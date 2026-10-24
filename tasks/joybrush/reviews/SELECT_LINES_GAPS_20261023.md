# Select + editable lines: what the app has vs the locked design (UI specialist, 2026-10-23)

Source of truth for the design: `specs/JB-5.20e_select_ui.md` (D1–D24, owner's §K K1–K24) and the owner's page
`tasks/design/UI-Select-options.html`. Audit of `origin/joy-creator` at 2cda719a2 (static read; key claims re-checked by
hand: `CanvasSelectionHost.kt:371-378`, `JoyBrushActivity.kt:525,2565,2574`, `JbCanvasView.kt:593,1775`,
`BrushShelf.makesLines` used only in tests). "Built" means the code path exists and is wired; NOT phone-accepted yet
(the Note 9 pass is job C1 below).

Path shorthand: Act = `joybrush-android/.../android/JoyBrushActivity.kt`; Host = `.../android/select/CanvasSelectionHost.kt`;
Controller = `.../android/select/SelectController.kt`; Tool = `joybrush/androidkit/.../select/SelectTool.kt`.

## 1. Built and wired (needs the phone pass, C1)

| Behaviour | Spec | Where |
|---|---|---|
| Select on the strip; tap again = settings | D1.1, H7 | Act:753-761, 2516-2529 |
| Tap picks; same spot cycles; count pill scrubs and wraps | D3, D4, K11 | SelectionState:90-121; Controller:263-307 |
| Pen hover glow; finger crosshair | D5, D7, P4 | Tool:213,705; SelectRouter:152 |
| Lasso; Lines/Paint, Whole/Touching, layer chips (never grey, K13 fallback) | D8, D9, K13 | Tool:498-515; SelectBarView:89 |
| S Pen button lasso from any tool | D1.2, H3 | SelectRouter:102,136 |
| One finger pans (default) / selects (setting) | P10, K18 | SelectSettingsView:25 |
| Studio box: guides, corner pins, pair move, reticle, rotate below, two fingers anywhere | K4-K6, K19 | Controller:111-117 |
| Row above the selection: paper backing, black/white ink, slow fades | K1-K3 | SelectRowView |
| Colour, Brush, Weight (drag + slider), Nudge, Duplicate, Delete, Flip H/V | D15, D16, K9 | SelectRowView:74-94 |
| Strip colour/brush/width act on the selection; ring pulses | K8, P3 B | ToolStripView:138-198 |
| Nudge ring + size slider + Box chip; tip dots on pen hover | D13, D14, K15, K16 | Tool:556-594, 746-759 |
| Live drag preview of lines; Rasterize Down | D18, H5, D16 | Host:335-365 |
| Keep-weights chip; ＋ add chip; solid-cyan cut-out ON states | D10.6, P6, K20, K24 | SelectBarView:106-177 |
| Ends on tap outside / pen stroke / layer change | D6, D12, D19 | SelectionState:118 |

## 2. Gaps

| # | Gap | Spec | Evidence | Kind |
|---|---|---|---|---|
| G1 | Lines only appear if the owner finds the global ⋯ "Editable lines" switch (default OFF). Per-brush defaults (`BrushShelf.makesLines`) are not live; the Select menu's "Paint and Airbrush make" label actually flips the global flag | JB-5.20 D6, D22, Q2/K23 | JbCanvasView:593,1775; Act:966,2569 | engine admission (lead) + UI |
| G2 | Copy only toasts "Copy is coming next"; no Paste anywhere | D16, K10, D2 | Act:2565 | lines: select/*; pixels: needs G9 |
| G3 | Lock / Group / Invert / "Pick locked lines too" have no UI (records already carry `locked`, `groupId`) | D16, D2 | Controller:375 | select/* + core |
| G4 | Adjust colour (HSB) hidden: `canAdjustColour` false, `adjustColour` empty | K17, H10 | Host:378; SelectionHost:82 | lines: select/*; pixels: G9 |
| G5 | Undo / redo / frame change END the selection (spec: keep it if its lines still exist, refit the box) | D20 | Act:525 | select/* + Act hunk |
| G6 | D2 popover holds only the two settings; missing Select all lines, Paste, Dim toggle | D1.4, D2 | SelectSettingsView:24-27 | select/* |
| G7 | Fade the rest is empty (`dimRest {}`) | D17, P2 | Host:376 | overlay (taste) |
| G8 | Layer panel rows missing: Select all lines, Rasterize Down, Flatten Lower | D21, Q1 A | Act:1505-1517 | Act hunk |
| G9 | Paint selections do nothing useful: `liftPixels` = null (no box, Fill, Delete, Flip, Duplicate), `paintClip` never read by brushes | D12, D16, H4, H5 | Host:371-374 | ENGINE |
| G10 | The paint-selection toast says "You can paint inside this selection", which is false today | — | Tool:614 | one line |
| G11 | Brush drawer badge, per-brush Editable lines switch, the two one-time toasts | D22, D23, P9 C | none | chrome UI |
| G12 | Ring's Bend slot inert (`supportsBend` not overridden) | K7, D10.7 | SelectBoxHost; TOV:161,1316 | select/* maths |
| G13 | Colour chip drag-off to the eyedropper doesn't recolour the selection | D15 | Act:550-553 | Act hunk |
| G14 | Nudge's 20% "before" ghost missing | D13 | SelectOverlayView | overlay |
| G15 | Resting thumb for the count is hard-coded false (the ＋ chip covers it) | K11 | Act:2574 | canvas touch (lead), low |
| G16 | Slice eraser preset not found | D24 | — | verify, then preset |
| G17 | Duplicating an animation frame drops its editable lines | — | lead's note 10-23 | ENGINE (lead, in hand) |
| G18 | Brushes that can't be lines yet: taper/dash shaping, picture tips, media pencil/watercolour/oil, smudge, fill | JB-5.20 | LineAdmission.kt:18 | Unibrush lane U2/U7 (lead + Codex Unibrush) |
| G19 | Stale "not yet" comments | — | Act:2508; Host:94; JbCanvasView:1290 | comments |

## 3. Who does what, and on which model

Tiers: 🟢 **cheap** = Haiku 5.5 / Luna (mechanical, exact instructions). 🟡 **near-frontier** = Sonnet 5.5 or Codex
Sol 6.1 (clear contract, thorough). 🟠 **frontier** = Opus 5.5 (taste, feel, judgment across files). 🔴 **frontier,
high effort** = engine/GPU/undo invariants where a mistake corrupts drawings (Opus 5.5 high, or Codex Sol 6.1 with a
design note reviewed BEFORE code).

| Job | Gaps | Who | Tier |
|---|---|---|---|
| L0: lines without a menu trip (owner picks: per-brush switch defaulted ON for line-capable brushes, or global ON). Same job as the watercolour brief's L3 (`briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md`); one builder only | G1 | Lead or the watercolour brief's L3, after the owner picks | 🟡 |
| L1: frame duplicate keeps lines | G17 | Lead (in hand) | 🔴 |
| L2: more brushes as lines (Unibrush U2/U7) | G18 | Lead + Codex Unibrush lane | 🔴 |
| L3: resting thumb for the count | G15 | Lead, later | 🟠 |
| **Codex Swath A: lines complete** (brief `briefs/CODEX_SELECT_LINES_20261023.md`) | G2 (lines), G3, G4 (lines), G5, G6, G8, G11 (after L0), G13, G14, G16 | Codex Sol 6.1 | 🟡 |
| Bend on the ring (touches `LineTransform.kt`, after the watercolour brief's L4 wash frame lands) | G12 | Codex or UI specialist | 🟠 |
| **Codex Swath B: paint selections** (same brief, Phase B, design note first) | G9, G2 (pixels), G4 (pixels) | Codex Sol 6.1 high, lead reviews the note | 🔴 |
| C1: Note 9 acceptance pass of section 1, with screenshots | — | UI specialist (Claude) | 🟠 |
| C2: look-and-feel review of every Codex delivery against the locked page | — | UI specialist | 🟠 |
| C3: Fade the rest (veil with the selection punched out, paper-aware) | G7 | UI specialist | 🟠 |
| H1: false paint-clip toast; stale comments (lead files go to him as a patch) | G10, G19 | Haiku | 🟢 |
| H2: builds, test runs, before/after screenshots per screen | — | Haiku | 🟢 |

## 4. Phone acceptance checklist (C1) — Note 9, under `jb-phone.lock`, "Editable lines" ON

For each: ✅ works and looks like the page / ⚠️ works, looks or feels off / ❌ broken. Screenshot each.
1. Draw 5 crossing ink lines. Select tool: tap one → glow, row above in the paper's colour, box with rotate knob below.
2. Tap the same spot again → next line under it; the count pill shows "2 / n"; drag the pill sideways steps through.
3. Lasso loosely around 2 lines → chips Lines/Paint, Whole/Touching, layer; tap Touching → re-reads.
4. Box: drag, corner scale, rotate knob below, two fingers anywhere scale/rotate; tap a corner to pin, two pins move as a pair.
5. Each gesture is ONE undo; undo/redo correct (note: today undo ends the selection — G5).
6. Row: colour (strip swatch pulses), brush swap, weight drag (2^(dx/60dp)) and the slider, Duplicate, Delete, Flip H/V.
7. Nudge: ring follows, size slider in the bar, smooth bend, Box chip returns.
8. Pen hover over a selected line shows tip dots; dragging a tip reshapes.
9. S Pen button + drag from the Brush tool lassos.
10. One finger pans; switch to "selects" in Select's menu and back.
11. ＋ add: chip turns solid cyan with the icon cut out; taps add/remove.
12. White paper and dark paper: row/bar ink flips black/white; fades smooth (220 ms out, 300 ms in).
13. Save, close, reopen: lines still editable.

## 5. Evidence log (append one entry per finished step)

### 2026-10-24 — Codex line-selection delivery, built and awaiting acceptance

- ⚠️ **A1 / G2 lines:** session clipboard, frozen-brush import, fresh line/group identities, one Add batch,
  destination-cel routing, location/centring and selection box implemented in `cc1061634`.
  Clipboard and host tests pass; undo/redo coverage exercises a recorded transaction, not a phone gesture.
- ⚠️ **A2–A4 / G3–G5:** Lock/Group/Invert, locked picking, relative HSB preview and history reconciliation built.
  Tests cover codec retention, preview without history, one release edit, move/Delete/creation undo, drawing epochs,
  partial survival and delayed snapshots. Exact-address refused-edit recovery received a final fix after the last run.
- ⚠️ **A5–A6 / G6/G8:** settings and scoped layer rows built. Core policy tests added; full core/androidkit suites
  still owed. Flatten Lower uses one existing engine transaction and a final snapshot-publication fix (`614c6eb1f`).
  White/dark settings and layer-menu screenshots are still owed. C3's outside veil stays with the UI specialist.
- ⚠️ **A7–A8 / G13/G14:** eyedropper final-sample recolour and 20% before-nudge ghost built; gesture/ghost
  regression tests pass. Real phone gestures and ghost screenshot are not claimed.
- ✅ **Executed evidence only:** app Select tests and Kotlin compile passed, 122 tests, zero failures/errors,
  7m13s. This run predates final snapshot/refused-edit fixes; it is not final-branch acceptance.
- ⚠️ **Remaining validation:** final app rerun and standalone `:core:jvmTest :androidkit:test`. The wrapper's
  2.2 GB free-RAM gate did not admit the standalone request; it was cancelled before Gradle started.
- ⚠️ **A9/G16:** no invalid Slice preset shipped. Zero-width cut admission and an atomic remove/add split are
  engine dependencies; details in `SELECT_LINES_IMPLEMENTATION_20261024.md`.
- ⚠️ **A10/G11 and G12:** vector per-brush/default and wash-frame candidates exist but are not settled/accepted;
  badge/toasts and ring Bend remain gated. Phase B remains gated by Phase A integration and an approved design note.
- Branch `codex/select-lines` pushed. Supporting source `cc1061634` precedes the individual Lead patches during
  cherry-pick integration; exact order is in the implementation review. Lead/UI notified. No main merge, no Note 9
  install, no phone acceptance and no owner sign-off claimed. STATE and both roadmaps now include these statuses.
