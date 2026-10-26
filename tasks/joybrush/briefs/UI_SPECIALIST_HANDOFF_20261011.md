# Handoff: Joy Brush Select UI specialist (JB-5.20e), 2026-10-11

Paste this into a new session to continue the UI specialist's work. Read `briefs/UI_SPECIALIST_BRIEF.md` first: it is
the standing brief (status **GO for both phases**, lead 2026-10-11). This file says where the work stopped.

## Where things are

- **Worktree:** `C:/Temp/jb-select`, branch `ui/select-tool` (from `origin/joy-creator` at 7db941d3). Pushed to
  `origin/ui/select-tool`. Never work in the owner's main folder.
- **Phase 1 deliverable 1, the interaction spec:** `tasks/joybrush/specs/JB-5.20e_select_ui.md`. Written. 24 decisions,
  the Concepts mapping with file:line, hooks H1–H8 for the lead, build slices e1–e3 with tests, owner checks.
  §K (the owner's picks) is empty until he replies.
- **Phase 1 deliverable 2, the owner's options page:** `tasks/design/UI-Select-options.html`. Written, with a working
  true-size mockup (the live phone), three directions (Concepts recommended, Thumb, Quiet), nine scenes, ten parts plus
  JB-5.20's two open questions (Q1 Flatten Lower, Q2 Paint/Airbrush default), a "Copy picks" line, a compare table, and
  the recommendation. **Browser-tested 2026-10-11** (see "How it was tested"). Published as a private Artifact:
  https://claude.ai/artifact/QiADrbXthgwEYYZ45y9jEB (republish from this file path to keep the URL).
- `tasks/joybrush/research/concepts-app-interaction-reference.md` was untracked in the owner's main folder; it is now
  committed on this branch so the spec's `CR:nnn` references resolve. When this branch is merged into the main folder,
  git will refuse while the untracked copy sits there: the lead deletes the untracked copy first (same content).

## Next steps, in order

1. ✅ **Browser test and fixes** (done 2026-10-11, see below).
2. ✅ **Published** as a private Artifact (URL above). The owner picks on his Note 9 with the S Pen; the page treats
   `pointerType === "pen"` as the pen and `buttons & 2` as the S Pen button. If the S Pen button does nothing in his
   browser, the Note 9's Chrome may not report it; right-drag on a computer shows the same behaviour.
3. **Tell the lead** (Claude session "Joybrush orchestration and completion", via SendMessage, or a dated heading in
   `tasks/joybrush/AGENT_BOARD.md`): spec and page are up for review; hooks H1–H8 in the spec's §H are what phase 2
   needs from them; H6 is two default-preserving flags in `:studiokit`'s `TransformOverlayView`.
4. **When the owner sends his picks line**, write it verbatim into the spec's §K with his words, resolve each "§K-Pn"
   reference, and update `D3` to say taps pick on the active layer only (the mockup does this; the spec does not say it).
5. **Phase 2 (code)** starts only after §K is locked: slice e1 (core `LineLasso`, `LineTransform`, `StrokePicker.rank`,
   `SelectionState`, JVM tests), then e2 (androidkit `select/`: `SelectTool : CanvasTool`, `SelectBoxHost`), then e3
   (joybrush-android `select/`). One Gradle at a time through `jb-gradle.lock`. Never install on the phone.

## Update, 2026-10-12 (later): e2 under way on `ui/select-e2`

Worktree `C:/Temp/jb-select-core`, branch `ui/select-e2` (off joy-creator bfab94da, where e1/H6/H7/H9, §K and the v2
page are merged). Lead decisions this round: paint path to the phone first (lines need 5.20a, dispatched, then 5.20d,
next week); extend the Studio box (one box); SelectRouter as pure core; `SelectionHost.kt` IS the contract the lead builds
to; Lock/Group fields go into 5.20a (`StrokeRecord.locked`, `StrokeRecord.groupId`); dab spacing vs widthScale stays as
is (vector successor's owner A/B); H9 Studio default is the lead's question to the owner; reuse `IconContrast` +
`JbCanvasView.sampleScreen` for K3.

Built on `ui/select-e2` (compiles; core select tests green except where noted at the commit):
- core `select/SelectRouter.kt` (+11 tests): who owns a contact and what it means (D3–D8, D12, K11–K19) and the box gate
  `boxTakes(hit, kind, button, selectToolInHand)`.
- core `SelectionState`: lock (skipped unless `pickLocked`) and group (a member brings its group) via `Items` (+2 tests).
- core `select/LineDensify.kt` (+4 tests): K15, pushed lines filled in on a Catmull–Rom curve, repeated passes.
- studiokit `TransformOverlayView`: `setCleanStyle` (K5/K6: extended edge lines, corner rings only, tap-to-pin = FREE,
  two pins move as a pair, centre reticle), `setGuideInk`, `setTouchGate` (the host may decline a contact). Defaults
  keep the Studio unchanged. `HandleModel.fill(..., withEdges)`.
- androidkit `select/SelectionHost.kt` (the contract, incl. `LiftedLines.preview`), `select/SelectTool.kt` (CanvasTool:
  router + state + host; box API beginBox/boxTo/commitBox/cancelBox/resetBox/flip; recolour/rebrush/reweight/
  duplicate/delete/rasterizeDown/reread/chooseFromOrder; nudge and tips with densify; hover glow; tip dots).
- joybrush-android `select/SelectBoxHost.kt`: the Studio box's Host over SelectTool (screen quads ↔ doc quads).

Next (e3): `SelectController` (puts TransformOverlayView over the canvas with clean style, rotate below, no pills, no
loupe, pinch anywhere, the gate from `SelectRouter.boxTakes`, guide ink from IconContrast; a `SelectOverlayView` for the
loop, glow, crosshair, nudge ring, tip dots; `canvas.tool = selectTool` always, `selectToolInHand` from the strip), the
actions row and bar views (K2/K3/K9/K10: paper backing, IconContrast ink, solid icons in JbIcon style), the count
scrubber (K11), `SelectToast`. The activity wiring (a few lines) is the lead's file: send them the exact lines.

## Update, 2026-10-12: the owner locked §K; e1 landed on ui/select-core

- **§K is locked** (spec `JB-5.20e_select_ui.md`, his picks and notes verbatim, K1–K19). Read it before anything else;
  it overrides the decisions above it. Highlights: actions row ABOVE the selection in the paper's colour with
  white/black icons; rotate handle BELOW (app-wide wish); Concepts corners (tap to pin, two pins move as a pair,
  extended trajectory lines, centre reticle) plus the Studio role ring on long-press; line filters imply lines
  (never grey out Lines); scrubbable count; ＋ adds for every way of picking; P3 B on trial; P8 B; P10 default B and
  Q2 default A, both in Settings; Thumb rejected.
- **One open question for him:** his picks line said P10 A but his note said "default b"; §K follows the note.
- **One design trade-off to show him:** with P3 B the lines row is 391dp, so beside the tool strip it can cover part
  of it; without the colour/brush chips (his first instinct, C) it is 311dp and sits beside the strip.
- **Code on `ui/select-core`:** 0c19adcc (e1 core + H6 + H7, tests green), then H9
  `TransformOverlayView.setRotateKnobBelow(boolean)` (default false; Joy Brush sets true; the Studio default is the
  lead's call).
- **Mockup v2** (same Artifact URL) is the locked design working: browser-tested, see "How it was tested" below.
- **Next:** e2 (androidkit `select/`: `SelectTool : CanvasTool`, `SelectBoxHost`, overlays) once the lead's H1/H2/H8
  (`lead/select-hooks` 1d2010b2) land on joy-creator; the lead builds H3–H5 after that, then H10 (adjust colour).
  The box for Joy Brush must follow K5/K6 (thin lines, corner pins, reticle, no edge handles), which is a different
  look from the Studio's handles: either a `HandleModel` style flag in studiokit or Joy Brush's own overlay drawing on
  the same `TransformQuad` maths. Decide with the lead before e2.

## Update, 2026-10-11 evening: the lead split the hooks

- **Lead does:** H1 (read-only `penSeen`), H2 (palm check before `tool.down`), H8 (offer `ACTION_POINTER_DOWN`) now, on
  `lead/select-hooks`; H3, H4, H5 after the owner picks, to the exact contract in spec §H "H4 and H5, the exact contract".
- **This lane does now** (branch `ui/select-core`, worktree `C:/Temp/jb-select-core`, off origin/joy-creator 6784dd76):
  - H6: `TransformOverlayView.setPinchAnywhere(boolean)` and `setBendPillVisible(boolean)`, defaults unchanged. (The
    Done pill needs no flag: it is drawn only when `setOnExit` is given a runnable.)
  - H7: `JbIcon.SELECT`, and a Select button in `ToolStripView` behind `showSelect(shown, chosen)` with
    `Host.selectTapped(anchor)` defaulted, so `JoyBrushActivity` still compiles. **Not a `ToolSlot`** (a slot is a
    remembered brush; the lead was told why).
  - Slice e1: `core/select/LineLasso.kt`, `LineTransform.kt`, `SelectionState.kt`, plus `StrokePicker.rank` (the vector
    specialist said yes, 2026-10-11) and tests `select/LineLassoTest`, `LineTransformTest`, `SelectionStateTest`,
    `vector/StrokePickerRankTest`.
- **Vector specialist's notes on LineTransform** (in its KDoc): `BrushDabber`'s speed input is doc px/s (a known gap,
  theirs); tapers and dashes keep proportion only when weights scale. They agreed `Tween.moved()` should also divide
  `screenPerDoc` by its fit's scale (their successor owns that).
- Builds: a copy of the lead's script is `<scratchpad>/select-build.ps1` (lock `%LOCALAPPDATA%/Temp/jb-gradle.lock`).
  e2/e3 (the tool, the box host, popup, bar) wait for the owner's picks.

## Design decisions worth knowing before you touch anything

- **Nudge means two things in this repo.** The owner's word "nudge" (and Concepts') is bending part of a line inside a
  ring. The code's `core/tool/Nudge.kt` is a 1 or 10 screen-px step move. The spec keeps the code name for the step move
  (keyboard arrows only) and uses "Nudge" on screen for the bend. Do not merge them.
- **The box is the Studio's** (`studiokit/.../transform/TransformOverlayView.java`, owner ruling R16, signed off over six
  rounds as TRANSFORM_UI_FEEL V6). Its handles, colours, rotate arc 46dp above the top edge, snapping and long-press role
  ring are reused unchanged. Only two flags are needed (H6).
- **Lines must keep "all the pressure, velocity, and tilt"** through transforms: `LineTransform` divides
  `StrokeRecord.screenPerDoc` by the scale (smoothing and speed are screen-relative, `InkReplay.kt:134`), rotates and
  mirrors `PenSample.azimuth` and `barrel`, and scales `widthScale` only when weights scale. Tests are in the spec.
- **Facts about the live code** (verified 2026-10-11): `JbCanvasView.tool` exists but nothing sets it; `penSeen` is
  private; the tool is offered every DOWN before palm rejection; `ACTION_POINTER_DOWN` is never offered despite the
  `CanvasTool` doc; the S Pen button's hold action (lasso) is not wired; `InkEditSession` still has its own 50-step undo;
  no code decides which lines a loop encloses, and none moves/scales/rotates a line. All of these are in the spec.

## How it was tested (repeat this after any change to the page)

- The browser pane cannot run `file://` pages, and it is not signed in to claude.ai (never sign in for the owner). Serve
  the folder instead: `.claude/launch.json` in the owner's main folder (untracked, local) has a config
  **`jb-select-design`** that runs `python -m http.server 8766` on `C:/Temp/jb-select/tasks/design`. Start it with
  `preview_start {name: "jb-select-design"}` and open `http://localhost:8766/UI-Select-options.html?v=N` (bump `N`
  after an edit; the server lets the browser cache).
- The page exposes `window.__jbLive` (the live phone) for inspection. Scripted tests dispatch `PointerEvent`s on
  `#live .surf`. While the pane is hidden, `requestAnimationFrame` does not run and screenshots time out: flush a frame
  with `L.paintAll()` (cancel `L.raf` first) instead of awaiting frames.
- Passed: tap pick, same-spot cycle with the count, pen hover glow, lasso (lines, then the chips: paint, touching, all
  layers), keep-weights, duplicate, delete, undo (keeps the selection), pixel loop and move, corner scale (weight
  scales), rotate with 15° snap, nudge, tip drag, More → Rasterize Down with its toast, Thumb dock, Quiet pill and toast,
  tap-adds, the fan, ring-edge nudge size, S Pen button loop from the Brush tool, pen stroke ending a line selection,
  finger loop after the pen, layer panel → Flatten Lower and its undo, two fingers inside (transform) and outside
  (zoom), two-finger tap = undo, every option/paper/Frost/direction control, all nine scenes inside their crops, no
  sideways scroll at 411 px wide.
- Bugs found and fixed in that pass: clicks on the page matched `[data-frost]`/`[data-paper]` on the root element (the
  options did nothing); a gesture could stay stuck after a two-finger lift; a tip on a mid-edge handle could not be
  grabbed (spec D14 updated: tips beat mid-edge handles); mid-edge handles crowded small boxes (hidden under 56dp);
  the bar overflowed 411dp; the popup could sit under the tool strip; no charset line.
- **Design finding for the owner's review:** with P1 "Under the box", the actions row covers the art just below a small
  selection (the nose was under the eye's row). That is the cost of keeping the row off the rotate arc above; it is
  exactly what the P1 options let him compare.

## Known gaps in the mockup (fine for a pilot, listed so nobody mistakes them for the design)

- Lines are drawn more simply than Joy Brush draws them. The Studio role ring, Tilt, Free and Bend appear in a scene
  only, not in the live phone. Two-finger rotate of the page is not built (pinch zoom and pan are).
- Home, Guides, Pin, More, the strip grip, Opacity and most layer-panel rows say "not part of this mockup".

---

## 2026-10-12 STOP: weekly usage at 98%. Next agent starts here.

**Where the code is.** Worktree `C:/Temp/jb-select-core`, branch `ui/select-e2`. e1 + e2 part 1 are MERGED to
joy-creator (lead, fc30baaf, suite green). The e3 view layer is committed on `ui/select-e2` as WIP and has NEVER BEEN
COMPILED: `SelectOverlayView.kt` (androidkit), `SelectRowView.kt`, `SelectBarView.kt`, `SelectController.kt`,
`SelectBoxHost.kt` edits, `JbIcon.kt` new icons (joybrush-android), `SelectTool.kt` additions. Build ONLY through the
lock script (see above); never raw gradle, never install on the phone, never edit JbCanvasView/GlPaintEngine/
JoyBrushActivity (the lead applies activity lines).

**Owner's two new notes (spec K20, K18):** ON = solid cyan with the icon or word cut out, never an outline. This is done
in `SelectBarView.ChipView` with a DST_OUT layer, but NOT yet in the design page. Pan is confirmed as the default for one
finger, switchable in Settings.

**Lead's answer on placement:** the box (TransformOverlayView) goes into JoyBrushActivity's root at INDEX 1, right after
`root.addView(canvas)`, below fillPreview/ring/reference/guides/board/overlays/hairline. The overlay view goes with it.
The lead wants the exact activity lines with e3 and applies them himself.

### Job list, by the model tier each job needs (roughly in this order)

Tiers: **T1 cheap** = Haiku 5.5 / Luna / a small Codex (mechanical, exact instructions given). **T2 near-frontier** =
Sonnet 5.5 / Codex standard (compile-fix, small features with a clear contract). **T3 frontier** = Opus 5.5 / top Codex
(judgment, gesture feel, cross-file design). **T4 frontier, high effort** = the same with thinking on high
(engine-touching, input arbitration, anything that can break a drawing).

| # | Job | Tier | Notes |
|---|-----|------|-------|
| 1 | Design page K20: in `tasks/design/UI-Select-options.html` change the ON rules at CSS lines ~45 (`.seg`), ~90 (`.tb`), ~130 (`.bb .sg`), ~132 (`.bb .tg`) to `background:var(--sel);color:<the paper colour behind>;box-shadow:none;opacity:1`. For the bar add `--pko` = the solid paper rgb, set beside `--pbk` in the ink loop (~line 1193). Change the K18 text (~line 1303) to "confirmed". Republish to the same artifact URL (needs a Claude session with the Artifact tool) | T1 | Pure CSS. A true cut-out isn't needed in the page; paper-coloured ink looks identical over the 88% paper backing |
| 2 | Tidy `SelectController.kt`: the redundant `picks.chipsInRow.not().or(true)` and the dead `!picks.crosshair && false` | T1 | Read the intent around them first; keep behaviour |
| 3 | Compile e3: `-p joybrush :androidkit:compileKotlin`, then root `:joybrush-android:compileDebugKotlin`, through the lock script. Fix errors; `git add` each file; commit; push | T2 | Logs are UTF-16: `tr -d '\000'`. Don't change behaviour while fixing types |
| 4 | Fill action: add `SelectTool.fill(argb)`, which calls the lifted pixels' `fill`, and wire `RowActions.fill`; or hide the bucket until H5 lands | T2 | Today it only toasts a placeholder |
| 5 | SelectToast: a small paper-coloured toast with an optional action word (contract `SelectionHost.toast(text, action, onAction)`) | T2 | Same backing and ink rules as the row (K3) |
| 6 | Settings entries for P10 (one finger pans or selects; default PANS) and Q2 (Paint makes pixels or air lines; default pixels), in the Select tool's menu (tap Select again) | T2 | `SelectTool.fingerSelects`; the Picks defaults already match |
| 7 | Hover labels: confirm every new button calls `kit.label` with plain words | T1 | Owner rule: every button gets a hover label |
| 8 | Count scrubber K11 (`placeCount()` is a stub): the "2 / 5" pill beside a tapped spot. Drag sideways to step through the overlapping lines; tap to cycle | T3 | Feel matters; copy the page's `.cp` behaviour |
| 9 | Write the exact JoyBrushActivity lines for the lead: create `SelectController(kit, ..., root, canvas, host, popovers, app)` with an App implementation; `attach()` with the box at root index 1; `canvas.tool = selectTool`; strip Select tap -> `selectToolInHand` plus the settings popover; `restyleRing(on, pulse)`; `showSelect(true)` | T3 | Send by SendMessage to the "Joybrush orchestration and completion" session |
| 10 | Bind SelectionHost to the real document types once 5.20a (seq/locked/groupId) and 5.20d land: liftLines/liftPixels with preview, paintClip, dimRest, adjustColour | T4 | Touches the GL engine side; one undo step per gesture |
| 11 | Lock, Group and Invert actions (after 5.20a and H5) | T3 | Spec D-items and the K list |
| 12 | Device pass with the owner: pen-vs-finger arbitration (SelectRouter and the `boxTakes` gate), tip dots on hover, nudge smoothness, fades | T4 | Ask the lead before any install; the owner presses Home first |
| 13 | Ask the owner the K20 open items: the strip's active-tool ring, swatches, layer cells | T1 | One plain-words question |

## 2026-10-12 Codex UX continuation — green/yellow source checkpoint

Worktree remains `C:/Temp/jb-select-core`, branch `ui/select-e2`; original e3 WIP is preserved.
Owner revisions are in K21/K22: a room-gradient trial, then a request to preserve cyan's shared selected meaning. The palette is NOT locked. The preview compares flat Cyan / Cyan gradient (recommended trial) / Joybrush gradient; native SelectAccent preserves all three. Switches retain cut-out words/icons; tools/swatches/layers retain contents within outlines.

Implemented:
- #1 design page: gradient selected fills and masked gradient outlines, opaque paper-coloured cut-out ink (`--pko`), K18 finger pan confirmation, K21/K22 palette/shape trials and K23 provisional Q2 wording. All three palette buttons preserve live selection; two finite strip pulses include width; strip width opens line-weight slider, 4x change and Undo verified. Browser checked dark and white paper, enabled Add, selected line/filter segments, outline contents, and 411px page width. Browser console has no errors/warnings. Prior Claude Artifact URL is NOT republished: this Codex session has no Claude Artifact publishing tool. Current desktop preview is `http://127.0.0.1:8776/UI-Select-options.html` from this worktree, not the old jb-select copy.
- #2 controller: redundant conditions removed without changing behavior. Narrow-screen row placement cannot throw on an inverted clamp range.
- #4 `SelectTool.fill(argb)` delegates exactly once to lifted pixels, keeps selection/clip, redraws only on change. `canFill` hides the bucket if the engine refused the lift. Pixel Duplicate is withheld because the inherited tool implements line duplication only; wire real pixel copy/lift before restoring it.
- #5 `SelectToast`: 88% paper backing, black/white sampled ink, initial paper ink, optional labelled one-shot action, accessibility timeout, fades, stale sample guards and detach cleanup. Tool-host toast calls use it; activity host can forward to `controller.showToast(text, action, onAction)`.
- #6 `controller.showSettings(anchor)`: labelled gradient segments. P10 persists in SelectPreferences, default false = pans; updates `tool.fingerSelects` immediately. Q2 uses explicit `App.paintMakesLines()` / `App.setPaintMakesLines(Boolean)` callbacks per lead decision; the lead persists/consumes this shelf preference with false = pixels, preserving the per-brush override.
- #7 all new row/bar/menu/slider/Settings/Toast controls carry plain `kit.label` labels; Add wording includes loops/fan. Shared ToolStrip Select already has a hover label; extend its wording to tap again for settings in lead-owned integration.
- #13 owner answered the ring/solid question, then refined palette/shape direction (K21/K22) and default-brush quality (K23); all recorded verbatim. Palette remains a trial. Q2 pixels default is NOT hard/fast; candidate Proko pencil must reach S-tier raster AND vector, with vector preferred when appearance matches, raster only for a worthwhile quality gain. Raster regression A to B+ reported to lead.

Build verification: included Androidkit compile PASS (2m12s) and root Joybrush UI compile PASS (1m53s). Focused behavior gate PASS (1m26s): SelectSettings1 + SelectToast2 + SelectToolFill2 = 5 tests, 0 failures/errors/skips; XML inspected. Logs in C:/Temp/jb-ux-ops: androidkit-compile.log, app-ui-compile.log, select-tests.log. Tasks: included `:androidkit:compileKotlin`; root `:joybrush-android:compileDebugKotlin`; root `:joybrush-android:testDebugUnitTest --tests '*Select*Test'`. Five focused tests cover Fill edit delegation/retained clip/refused lift, settings default/persistence/Q2 callback, and toast one-shot/stale callbacks. Never claim phone acceptance from these.

Lead integration notes:
- Existing `attach()` installs the overlay directly above the canvas and the box above it, under later canvas adornments; the row/bar/count are root chrome. `canvas.tool = tool` remains always installed, while `selectToolInHand` tracks the strip. Exact activity integration (#9) remains a separate frontier job.
- `SelectionHost.canAdjustColour` defaults false: expose Adjust colour only after H10 has real live preview/one-step undo backing.
- Native `SelectAccent` supplies flat cyan / cyan-to-deeper-cyan / room blue-to-violet; controller `Picks.accent` can switch the trial. The row (22dp), bar (20dp), chips (16dp) and Settings choices (20dp) are full pills; paper-backed row/bar/toast have no perimeter stroke. `controller.showWeightSlider(anchor)` lets the shared strip width control use the selection-weight action; `App.restyleRing` covers width, colour and brush with brief discovery pulses. Global colour mirrors were not changed. Shared ChromeKit/strip/layer outline rollout belongs to the lead after reviewing the owner's trial.
- #8 count scrubber remains stubbed; #9 activity lines, #10 real engine host, #11 Lock/Group/Invert and #12 device pass remain open. No hot-file edits, Gradle outside the shared runner, device commands or installs.

## 2026-10-13 — Native approved styling and review repairs

Owner locked cyan gradient/full pills/borderless backing (K24), requested implementation, and ended web preview work. K21/K22 trials above are historical. Native SelectAccent is now fixed to cyan upper-left → 15% deeper toward ground lower-right, with true diagonal gradient on wide pills. ToolStrip selected tools and activated width/colour/brush keep their contents visible inside gradient rings; docked outer corners23dp. `strip.setSelectionRestyle(on, pulse)` runs two finite600ms cycles on the rings only, then steady; reduced motion is steady. Labels explain the selected-line actions.

Lead released LayerColumn presentation: selected layer/mask outlines use the same gradient and full-pill shape; preview backings/clips match, preserving bitmap scaling/hit rectangles/animation markers. Existing LayerColumnAnimationTest gained a native bitmap render regression; retain Lead's DocVersion10 PAINT+strokesFile fixture when applying its new block to the fresh integration base.

Lead released ToolStripView to UX and narrowly authorized default `TransformOverlayView.Host.cancelGesture()` terminal hook. Ordinary uncommitted drag/tap and cancelled/unmoved pinch call once before sync; existing Studio hosts no-op, bend behavior untouched. SelectBoxHost cancels the tool preview and settles faded row. A refused box write never starts the fade.

Four lead source findings repaired: independent weight snapshots prevent pre-move/previous-selection replay; terminal/selection transitions clear gesture baselines; failed pixel commits roll back the quad/preview and keep the old clip; null pixel lifts retain paint clipping but expose no transform/Delete/Flip/Fill and explain refusal; successful pixel transform rebuilds paintClip from moved loop without committing twice or re-lifting. LiftedPixels.commit must keep the result lifted at identity in its new place, matching the original spec contract.

Owned box-host review additionally corrected accumulated move/scale frames: `gestureQuad` supplies the start pose, similarity lands its centroid on the requested point, and `rebaseGesture` absorbs drag into pinch without another undo snapshot. Cancel restores the original full gesture. Lead's later real all-layer binding must address duplicate line IDs across cels/layers with explicit scoped addresses; no broad API rewrite was made here.

Integration calls: `App.restyleRing(on,pulse)` → `strip.setSelectionRestyle(on,pulse)`; selected width tap → `controller.showWeightSlider(anchor)`; selected width drag → a factor-based `controller.tool.reweight(factor,done)` from the current selected weight (not the ordinary next-brush size); selected colour/brush → real `recolour`/`rebrush` through the chosen picker. Shelf/brush override remains lead-owned and K23 remains quality-dependent. Do not advertise these strip edits as connected until Activity actually routes them.

Verification October13: included `:androidkit:compileKotlin` PASS1m9. Root native and shared Studio compile PASS; final root native compile/focused tests PASS1m41. XML inspected: LayerColumnAnimation6 + ToolStripSelection5 + SelectBoxCancel9 + SelectToolEdit9 + SelectSettings1 + SelectToast2 + SelectToolFill2 = 34 tests, zero failures/errors/skips. Logs: C:/Temp/jb-ux-ops/locked-androidkit-compile.log and locked-native-tests-repair2.log. Initial test fixture setter clash was corrected; native bitmap regression caught the cyan layer ring crossing mask preview, fixed by placing the layer ring below the mask. Its green contents and rounded cutouts now pass.

No phone acceptance or live drawing-engine acceptance. Source released to Lead; its standalone app packaging retry follows this lane's normal lock release. #8/#9/#10/#11/#12 remain separate prerequisites/jobs.

Published source: e2369b2b on origin/ui/select-e2. Preserve full e3 ancestry (ed914bc3 → 3e817d5a and later checkpoints) when taking these controls; do not apply the final delta to a branch missing the original e3 views. The later handoff closure only changes documentation. Source files and shared build slot are released to Lead.

## October13 — Count source completed

Job8 source13c05efe is frozen on ui/select-count-main, C:/Temp/jb-select-count,
base a16059d6; documentation closure58c65392 changes no tested source. Original UI tree
remains preserved. Standalone core32/native56 checks pass with no failures/errors/skips.
Lead integration4dfe0f48 applies only the seven source/test files to combined sourceeb4eb531.
Read reviews/UX_COUNT_20261013.md for current integrated evidence.

Activity still must supply accepted resting-finger context to App.hasRestingThumbForCount()
and chain controller.viewMoved() into existing onViewMoved sampling. Current count string
IDs migrate with LineAddress before all-layer live edits. Real Activity/engine binding,
Lock/Group/Invert and phone acceptance remain open. No preview/pencil/default/engine edits.

## October24 — Select delivery C1/C2 review

Read cc1061634/status98d925b70 from codex/select-lines in isolated
C:/Temp/jb-ux-review-20261024, ui/select-review-20261024. The existing staged work in
C:/Temp/jb-select-ux is preserved. Report: reviews/SELECT_C2_SOURCE_REVIEW_20261024.md.
C2 static review is complete with notes; rendered C2 acceptance and C1 phone pass remain OPEN.

The expanded D2/More content has no scrolling/height adaptation, so short-window/larger-text
fit needs correction or proof. Pick locked lines is always shown despite D2's reveal condition.
Per-brush priority wording remains conditional on real L3/A10 integration. New labelled actions,
full-pill cut-out choices and single-layer20% nudge ghost follow the source design; source is
not a screenshot/feel test. White/dark D2, layer-row and ghost captures are still owed after
Lead integration and final rerun on an identified APK. The report extends the existing C1
checklist without replacing it. C3 veil remains separate and unimplemented by A5 preference
wiring. No build/device action, hot-file edit, preview update or phone-acceptance claim.

Follow-up b590e58c5: reviewed the source-only repair and all five new regression scenarios.
D2/More now have a bounded scroll wrapper retaining row targets; the locked-line option
reveals persistently after encountering a lock and preserves legacy/off choices. Initial
source findings1/2 are addressed. Coordinator owns executing integrated checks; this review
ran no tests and does not close native visual/C1 phone gates. Details are appended in the
same C2 report. No production files changed in the UX review branch.

Caption follow-up2cbfbfb86 / frozen candidate79f2d4dba: source review finds no material
issue. New App capability defaults false and has no enabling production override in the
candidate, so unsupported per-brush-priority wording stays hidden. Read its regression
source but ran no test. All three initial C2 source notes are now addressed; integrated
validation, visual/C1 phone evidence and underlying L3/A10 remain open. Candidate engine
context-loss/paint-mask changes are outside this bounded review and remain unexecuted per
coordinator delivery. No Phase B implementation approval is inferred from its design note.

### Current UI hunks and A10/K25 handoff

Read43d8588ef and current K25/K26 on origin/ui/select-accept. New report:
reviews/SELECT_UI_HUNKS_A10_HANDOFF_20261024.md. No reusable K25 mode renderer is published;
builder can implement its exact12dp raster dots/vector curve-square-nodes directly in a
small native Select helper, driven by the real per-brush L3 flag. JbIcon.RASTER is a
different24px Rasterize Down action. No new appearance/default choice is needed from owner.

Ordered UI integration:9b32f3fe6 benchmark/accessors; f2d1b7f4d geometry/placement/contrast
and body-tap handback;4f35f422f below-box count and correctly scoped finger-pan bench;
43d8588ef evidence/bench instructions. Reapply overlapping Controller/Tool/BenchRunner
hunks preserving A1–A8/C2; never replace them wholesale with this older UI branch.

New material edge: f2 BoxHost.cancelGesture replays tapFromBox even though TOV calls that
hook for both clean uncommitted UP and actual CANCEL. Require clean-UP-only tap handback
and actual overlay UP/CANCEL regressions before acceptance. Reported to Coordinator.
No source repair/bench/device action by this review; watercolor owns the phone window,
candidate55b06 is frozen for validation. A10/L3 actual engine adoption, C3 and combined
C1/C2 proof remain open; earlier source tests/runs do not prove this new integration.

### October25 — C3 exact dependency accepted by Coordinator

Report: reviews/SELECT_C3_DEPENDENCY_CONTRACT_20261025.md (source dfda430d3).
An above-GL paper veil with a geometry/alpha hole cannot preserve real semi-transparent,
textured or transformed selected strokes. The report proves the mismatch numerically and
names the existing rest/lift draw seam. Coordinator accepted no approximate UI veil and
assigned the display-only engine pass, real source-alpha replay and non-display exclusions
to Engine Lead. Existing Controller dimRest calls alone cannot count as implementation.

The report gives the bridge/lifecycle contract and eight bounded composition, preview,
paper, isolation and exact-source phone cases. No code/build/device action by UI. C3 stays
OPEN until the actual hook plus semi-alpha/texture/preview evidence lands; Phase B is not
approved by this dependency note. Locked styling and K25/K26 remain intact.

Additional C3 admission review: verified mainf79efc378 shows flattened selected RGBA,
source-over lift drawing and u_layerOpacity1. The handoff's algebra proves normal
source-over ONLY, not multiply/screen/erase/behind or source-layer opacity/blend/mask and
cross-layer semantics. Expanded matrix now has ten groups, including those destination-
dependent cases and broken flatten/unit-opacity controls. Proposed conservative initial
envelope is documented for Lead; no gate is implemented or general lift correctness claimed.
Existing same-source legacy lift issues remain separate from C3 completion.

### October26 — d11 final screenshot registration reviewed

Report: reviews/SELECT_D11_VISUAL_REVIEW_20261026.md. Primary specialist and Luna each
inspected all 11 final PNGs for run66b2bea7a44c4eaca7e99b5fb53d2ac3, sourced11da3d4b.
All show the expected tan-canvas stages; no Home/stale registration or new obvious
blocking visual issue. Results report20/0. Final finger-pan result has no post-pan PNG;
lock behavior is metadata, and the pasted diagonal is partly outside the viewport.
Coordinator retains independent receipts/events/hash/validator verification; Select
retains postdata/claim release. No device/build/source/preview action by this review.
White/dark, A10 repair rendering, physical pen feel and C3 remain separate open gates.
