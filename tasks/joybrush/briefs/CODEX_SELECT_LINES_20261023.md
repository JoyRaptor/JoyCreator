# Codex brief: Select + editable lines, complete (UI specialist, 2026-10-23)

**Goal (owner):** the Select tool on the phone does everything his locked design does. The design is
`specs/JB-5.20e_select_ui.md` (decisions D1–D24; his locked picks §K, K1–K24, quoted verbatim) and the page
`tasks/design/UI-Select-options.html` (open it in a browser: it is the working mock-up he approved). The gap list this
brief works from is `reviews/SELECT_LINES_GAPS_20261023.md` (G-numbers below). Most of the tool is already built and
wired. This brief is the missing part, lines first (Phase A), then paint selections (Phase B).

## Lanes and rules (read before any edit)

- **Owners:**
  - The **Engine Lead** owns `GlPaintEngine.kt`, `JbCanvasView.kt`, `JoyBrushActivity.kt`, `NativeLines` and the line
    engine (`androidkit/lines/*`), and integration to main. See `LEAD_PLAN_20261023.md`.
  - The **UI specialist** (Claude) owns `joybrush-android/.../android/select/*`, the strip and layer-column selection
    visuals, and the look. They review every step you finish against the page.
  - You may edit `android/select/*`, `androidkit/select/*`, `core/select/*` (except `LineTransform.kt`, see below) and
    `core/chrome/*`, plus their tests.
- **Lead's files** (the three hot files, `NativeLines`, `lines/*`): make the smallest hunk, in its OWN commit with the subject
  prefix `LEAD PATCH:`, and message the lead with the commit id. Never merge to joy-creator yourself; the lead merges.
- **Don't touch** (another Codex brief owns them, `briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md` on
  `origin/vector/wc-live-brief`):
  - `core/select/LineTransform.kt` (L4 adds the watercolour wash frame there);
  - `WatercolourDabber`, `androidkit/gl/watercolour/*`;
  - the editable-lines admission gate (L3).

  If you need a change there, ask the lead.
- **Branch and folder:** worktree `C:/Temp/jb-codex-select`, branch `codex/select-lines` from `origin/joy-creator`.
  Never work in the owner's main folder. Push after every step.
- **Builds:** only `tasks/joybrush/lead-scripts/lead-build.sh <worktree> <timeout> <log> <tasks…>` (it takes
  `jb-gradle.lock`). Never raw gradle, and never edit a worktree while it builds.
  - Unit suites: `-p joybrush :core:jvmTest :androidkit:test`.
  - App: root `:joybrush-android:testDebugUnitTest --tests '*Select*'`, then `:joybrush-android:compileDebugKotlin`.
  - Logs may be UTF-16: `tr -d '\000'`.
- **Phone:**
  - Note 9 only (sandbox): `install -r`; never uninstall or clear data; Home first.
  - Launch with `am start -n`, never `monkey`. Take `$LOCALAPPDATA/Temp/jb-phone.lock` (noclobber) and log the phone's
    temperature while working.
  - Note 20 = the owner's real projects: never.
- **Undo:** one press, one step, always (D20). Every action below is exactly ONE undo step. Selecting, cycling and
  re-reading chips are not steps.
- **Look:**
  - Paper-coloured row and bar, black or white ink by brightness (K3).
  - Selected = solid cyan gradient with the icon or word cut out (K20, K24). Never a cyan outline, never a greyed-out
    chip (K13).
  - Every new button gets a plain-words hover label (`kit.label`).
  - dp sizes as drawn in the spec.
- **Copy text:** the owner's words in the spec are verbatim; use them.
- **Identity:** write "JoyRaptor" or "the owner" in repo files, never a personal name or email (a git hook blocks it).
- **Never** resolve a merge conflict: re-apply on the new base.
- **Each step ends with:**
  - tests green;
  - a short evidence note appended to `reviews/SELECT_LINES_GAPS_20261023.md` §5 (what, commit, tests, phone screenshot
    if any);
  - a one-line message to the lead and the UI specialist.

## Phase A: lines complete (do in this order)

| # | Step | Gap | Done when |
|---|---|---|---|
| A1 | **Copy and Paste lines.**<br>• Copy (paperclip) puts the selected `StrokeRecord`s in an in-app clipboard that survives switching drawings in the session.<br>• Paste adds them as NEW lines (new ids, next seq) on the current layer, in the same place if that's on screen, else centred in view; they become the selection with the box. One step.<br>• Paste appears in the D2 popover (tap Select again) and in the row's More when the clipboard holds lines. Replace `copySelection`'s "Copy is coming next" (Act:2565, LEAD PATCH) | G2 lines | JVM tests: copy → paste → undo → redo; paste into another layer; ids never collide |
| A2 | **Lock, Group, Invert**, and "Pick locked lines too".<br>• Lock/Unlock and Group/Ungroup in More: set `locked` / `groupId` on record copies through `host.replaceLines`, one step each.<br>• Locked lines can't be picked unless the D2 popover's "Pick locked lines too" is on (`SelectionState.pickLocked`). Tapping a grouped line selects the group (`withGroups`).<br>• Invert = every pickable line on the layer minus the selection | G3 | Tests for each, including undo, save/reopen keeping `locked` / `groupId`, and lasso honouring both |
| A3 | **Adjust colour (HSB) for lines.**<br>• More → Adjust colour opens the three H/S/B sliders the controller already has (`showAdjust`).<br>• Live preview through `LiftedLines.preview`; ONE step on release. Set `canAdjustColour` true only when lines are selected.<br>• Every line keeps its own hue relationship (shift, don't flatten to one colour) | G4 lines | Tests: preview writes no history; release = 1 step; multi-colour selection keeps relative hues |
| A4 | **Undo keeps the selection** (D20). On undo, redo or frame change, re-resolve the selected addresses. Keep those that still exist and refit the box; end only if none remain. Today `onBeforeHistoryChange` ends it (Act:525, LEAD PATCH); the logic lives in `select/*` (`SelectionSync`) | G5 | Tests: move → undo keeps the selection at the old place; delete → undo restores lines and selection; undo past creation ends it |
| A5 | **D2 popover complete:** Select all lines (this layer), Paste (A1), Pick locked lines too (A2), Dim the rest on/off (wire it to `Picks.fadeRest`; the look is the UI specialist's job C3), beside the two settings already there | G6 | Popover screenshot on white and dark paper |
| A6 | **Layer panel rows** (D21): Select all lines; Rasterize Down (bakes the selected lines on this layer, else all; reuse `bakeLines`); Flatten Lower (this layer and every layer below become one pixel layer, all lines baked; Q1 A). Each shows only when it applies; one step each. The panel is in Act (LEAD PATCH); keep row logic in `select/*` | G8 | Tests for the bake scope; screenshots |
| A7 | **Eyedropper from the colour chip.** Dragging off the row's colour dot (or the strip swatch while lines are selected) picks a colour and RECOLOURS the selection, one step (D15). Act:550-553 hunk as LEAD PATCH | G13 | Test: pick → one step; a plain eyedropper with nothing selected is unchanged |
| A8 | **Nudge "before" ghost.** While nudging, the line's pre-nudge shape shows at 20% in the overlay until release (D13) | G14 | Screenshot |
| A9 | **Slice** (D24): an eraser preset at width 0 named Slice in the eraser shelf, if it isn't there. Check the shipped brush assets first | G16 | The preset exists; a slice line splits a line into two selectable lines (or note why not, for the lead) |
| A10 | **Per-brush lines switch, badge and toasts** (owner ruling 10-24, spec K25 + D6/D22/D23; wait for L3's engine default). (a) In each brush's hold-for-settings sheet: an "Editable lines" switch reading/writing the same per-brush pref L3 uses (`BrushLinePreferences`, brush id key). Brushes that can never be lines (media, smudge, wet, blur, clone, paste) show NO switch, only the D22 sentence. (b) The drawer badge shows each brush's CURRENT mode (what it was last used as): raster = the P9 C dotted corner, vector = a small node-curve; the UI specialist supplies both glyphs; pixels-always brushes always wear the raster mark. (c) The two D23 toasts, verbatim, one pref each, through `SelectToast` | G11 | Screenshots of the sheet (switch on/off, a no-switch brush) and the drawer on white and dark paper; each toast once per install |

**Not in Phase A:** Bend on the ring (G12) touches `LineTransform.kt`; it comes after the watercolour brief's L4 lands.
The resting thumb for the count (G15) is the lead's, later.

## Phase B: paint selections (only after Phase A is merged)

Engine work in the lead's files. 🔴 **Write a design note first** (`specs/SELECT_PIXELS_DESIGN_20261023.md`) and wait for
the lead's yes before code. The note must answer:
- how a lift works on a plane that holds line items, given the invariant `tile == base ∘ items in seq order`
  (`specs/NATIVE_LINES_V1_20261016.md`, `NATIVE_LINES_V12_HARDENING_20261017.md`: slabs);
- texture budget on the Note 9 at a full-screen selection;
- how the drag preview reuses the v1.1 lift (`beginLift` / `setLiftTransform` / `previewLift` / `endLift`);
- context loss.

| # | Step | Gap |
|---|---|---|
| B1 | `liftPixels` / `LiftedPixels`: transform preview through the box homography; `commit` keeps it lifted at identity in its new place (spec H5); `clear`; `fill(argb)`; `release`; ONE step per gesture | G9 |
| B2 | `paintClip` honoured by every brush stroke (dabs masked by the selection, D12, H4); then fix the paint toast (Tool:614) to tell the truth | G9, G10 |
| B3 | Pixel Duplicate, Delete, Flip, Fill, Copy and Paste (row and More, as for lines) | G2, G9 |
| B4 | Adjust colour for pixels (shader, live preview, one step) | G4 |
| B5 | The outside tint for paint selections (D17), with the UI specialist on the look | G7 |

## Reporting

The owner can't read code. Each step's note says in plain words what he can now do on the phone, with ✅ / ⚠️ / ❌, the
commit, and the test counts. Don't claim phone acceptance from unit tests.
