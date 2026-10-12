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
