# HANDOFF — JoyCreator / Faditor polish, 2026-08-08

**Written for a fresh agent of any model family.** Assume you know nothing about this session.
Read §0 and §1 before touching code. Everything in §3 is unfinished work with enough detail to
execute without asking questions.

Branch: `joy-creator`. Last commit of this session: `2d03ed9`.
Owner: JoyRaptor. He is direct, notices unfinished edges, and values honest "not done" over
overclaiming. **Never report something as working that you have not verified.**

---

## 0. Environment and hard rules

| Thing | Value |
|---|---|
| Repo | `C:\+Projects\Screenrecorder\FadCam` |
| Language | Java, Android, minSdk 24, OpenGL ES 2.0 / GLSL ES 1.00 |
| Main file | `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (~30k lines) |
| Sandbox phone | Note 9, adb serial `SANDBOX_SERIAL` |
| JoyRaptor's real phone | Note 20, serial `REAL_SERIAL` — **NEVER install to it** |

**Rules that have caused real damage when broken:**

1. **Never `git add tasks/`.** That directory holds JoyRaptor's own live working documents
   (`LAUNCH_STRATEGY.md`, `OUTREACH_ANONFADED.md`, this file). Stage only the exact source paths
   you edited. `git add -A` is forbidden.
2. **Dialogs must be** `new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)`
   with **no** theme-overlay second argument. A raw `AlertDialog.Builder` throws
   `InflateException` at runtime in this app.
3. **Before any `adb push` to `/data/local/tmp`**, run `export MSYS2_ARG_CONV_EXCL='*'` or the
   shell rewrites the device path and silently empties the target file.
4. **`tools/build-install.sh` refuses to run if the Note 20 is attached.** Do not defeat this.
5. **WYSIWYG mandate.** JoyRaptor's words: *"If something can happen in the export, I need to be able
   to see it because nobody's gonna buy a software where they have to guess."* Any visual property
   you add must be threaded through **both** the live preview renderer **and** the export
   renderer. This project has lost hours twice to changing only one of the two.

### Build / verify commands

```bash
bash tools/jvm-harness/typecheck.sh                              # fast, no device. MUST say TYPECHECK OK
bash tools/build-install.sh :app:compileDefaultDebugJavaWithJavac # full compile, no install
bash tools/build-install.sh                                       # compile AND install to Note 9
bash tools/jvm-harness/run-fx.sh                                  # FX/shader golden-string harness
bash tools/jvm-harness/run-adjust.sh                              # adjustment-layer model harness
```
Other harnesses: `run-mask.sh run-key.sh run-matte.sh run-anchor.sh run-promote.sh run-sequence.sh`.
All are off-device and take seconds. **`typecheck.sh` cannot see newly-added resources** (stale
`R.jar`), so a brand-new `R.string.*`/`R.drawable.*` will fail typecheck but compile fine — use
the full compile command to confirm, or use an inline literal with a `// TODO(strings)` marker,
which is this codebase's established practice.

### Driving the phone from a script

There is a helper at
`C:\Users\JOYRAP~1\AppData\Local\Temp\claude\C---Projects-Screenrecorder-FadCam\c15abd34-3fcb-4991-9bec-2d13c5d28d50\scratchpad\fxshot.py`
(`f.adb(...)`, `f.shot(name)`, `f.pull_project()`, `f.push_project(d)`, `f.open_editor()`).
Its `open_editor()` tap coordinates are **stale**; navigate manually:
Faditor tab is at `input tap 627 2109`, then the newest project row at `input tap 540 700`,
then wait ~13s. Writing a stack directly into `project.json` via `push_project` is far faster
than driving the FX UI by taps.

**Known adb limitation:** you cannot inject a second pointer, so pinch and two-finger rotate
cannot be script-verified. `input swipe` and `input motionevent` also do **not** produce a touch
stream the timeline accepts for long-press-then-drag — the row will not even select. Those
gestures need JoyRaptor's finger. Say so rather than claiming verification.

---

## 1. Architecture you must understand first

### The FX system
- `fx/FxStack.java` — ordered list of effect cards + a shared `keys` KeyframeSet. Has
  `copy()`/`copyFrom()` used for undo snapshots.
- `fx/FxInstance.java` — one card: effect id, param values, opacity, blend mode, `slot` (stable
  id used to name its keyframe tracks — never renumbered, which is why reordering doesn't break
  animation).
- `fx/FxRegistry.java` — the catalogue of effects and their params.
- `fx/FxCompiler.java` — assembles GLSL. `emitGlsl()` for export/GL-preview, `emitAgsl()` for the
  API-33 RuntimeShader path.
- `fx/FxGlSource.java` — **shared** shader source so preview and export compile identical text.
  Read its doc comments before touching any shader assembly.
- `tools/FxPanel.java` — the effect-stack UI. Key helpers: `structural()` wraps any stack
  mutation in one undo step; `recordSnapshot()`; `diamond()` is the keyframe control.

### Renderers (the parity pairs)
| Feature | Live preview | Export |
|---|---|---|
| Adjustment layers | `compositor/FxPreviewTextureView.java` | `export/AdjustmentLayerGlEffect.java` |
| PiP / overlay clip | `compositor/OverlayVideoPreviewView.java` + FxPreviewTextureView composite | `export/BlendModeGlEffect.java` |
| Text | `overlay/TextOverlayLayer.java` → `overlay/TextBoxRenderer.java` | `export/TextFxGlEffect.java` + `TextBoxRenderer` |

### Preview interaction
`overlay/PreviewHandlesOverlay.java` is now the **single gesture authority** for the preview
canvas. It sits above all five sibling overlay views and owns tap-to-select, drag, pinch-scale,
and two-finger rotate. Its `SelectionSource` interface calls back into
`FaditorEditorActivity.previewSelectionSource()` which hit-tests every object top-down and
selects via `selectLayerItemById()` → `ctrl.setSelectedItem()` — the same call the timeline
makes, so timeline row, handles, and drawer all stay in sync.

### THE TWO SELECTION CONCEPTS (JoyRaptor asked about this directly — see §2.2)
1. **`selectedClipIndex`** — an `int` index into `timeline.getClips()`, the master video spine.
2. **layer-band selection** — a String id via `editorTimeline.getSelectedLayerItemId()` →
   `LayerGestureController.getSelectedItemId()`, covering text/sprite/PiP/adjustment/audio.

Both can be non-empty simultaneously. **Every toolbar action must check object-selection FIRST
and only fall back to the clip.** Violating this is the exact bug fixed in `2d03ed9`.

---

## 2. Answers to JoyRaptor's open questions

### 2.1 What Heal actually does (he asked; the answer is: not what he thinks)
`healAtPlayhead()` at `FaditorEditorActivity.java:29242`. **It does not rejoin two clips.** It
takes the *currently selected single clip*, and adds a **removed span** (~`HEAL_DEFAULT_RANGE_MS`
wide, centred on the playhead) to `clip.getRemovedSpans()` — i.e. it deletes a chunk of footage
from *inside* one clip and closes the gap. "Heal" here means *heal a blemish* (cut out a cough, a
stumble), not *heal a seam*.

That is why JoyRaptor split a clip and pressed Heal and "it did nothing": at a fresh seam the
playhead sits at a clip *boundary*, so `leftRoom` or `rightRoom` is below `HEAL_MIN_EDGE_MS` and
the method bails to a generic error toast.

**JoyRaptor's proposal, which is the right one:** Split's button should become a Heal affordance only
when the seam under the playhead is *legitimately healable* — meaning two adjacent clips that
came from the same source and are contiguous and in order in source time. See §3.6 for the spec.
This also means the current "heal = remove a span" feature needs a different name/home; suggest
**"Remove section"** or folding it into the ripple-delete family.

### 2.2 Can the two selection concepts be unified? (his "reducible complexity" question)
**Honest answer: yes in principle, no as a safe change today.** JoyRaptor is right that the master
spine's only genuinely special property is that it defines project length and gap/ripple
behaviour. But the master spine is load-bearing in ways an overlay lane is not:
- it is **index-ordered**, and transitions are stored *by clip index*
  (`shiftTransitionsAfterSplit`, `removeTransitionsForDeletedClip`) — overlays are id-keyed;
- gapless playback builds an ExoPlayer playlist window per master clip
  (`resyncGaplessAfterStructuralEdit`);
- ripple/gap mode, `getTotalDurationMs()`, and anchor-shift all walk the spine;
- `layerId == null` *means* "master clip" throughout the codebase — several branches test exactly
  that.

A true unification is a multi-day refactor with a high chance of regressing playback and export.
**Recommended instead:** keep the two models, but make the *interaction layer* behave as if there
is one — i.e. enforce "the selected object is the target of every verb" at every entry point
(§3.5). That buys JoyRaptor the entire user-visible benefit at a fraction of the risk. If a real
unification is ever wanted, do it as its own project with the harness green at every step.

### 2.3 Locked objects
JoyRaptor: asking is the right call. So: any verb (delete/duplicate/move/trim) hitting a locked object
should show a confirm — *"This object is locked. Unlock and continue?"* — not silently no-op and
not silently act. `isLocked()` exists on Clip, TextOverlayItem, AdjustmentLayer, sprite.

### 2.4 Multi-select
It already exists (marquee, a couple of icons left of the play button). So the rule in §3.5 must
be stated as **"operate on everything currently selected"**, not "the selected object".

---

## 3. WORK REMAINING — the checklist

Ordered by JoyRaptor's stated priority. Each item is self-contained.

### 3.1 Colour picker layout — REDO (I got this wrong; he was explicit)
File: `app/src/main/java/com/fadcam/ui/faditor/tools/ColorPickerDialog.java` (+ `ColorWheelView.java`).

What I built is wrong: I offset **individual swatches** vertically. He wants **whole rows offset
horizontally**, honeycomb-style, so rows nest closer together vertically.

Required layout, top to bottom:
```
[ HSB wheel+triangle ]   [ H ──────○──  54 ]
   (TOP-LEFT)            [ S ────○────  77 ]
                         [ B ──────────○ 100 ]
[ swatch ⧉ #FFEB3B ]     <- hex + copy icon UNDER the wheel
        OOOOOOOO          <- row 1: 8 common colours
          OOOOOOOO        <- row 2: 8 common colours, offset RIGHT by ~half a swatch
        OOOOOOOO          <- row 3: 8 EMPTY slots = recents
```
- Wheel/triangle widget moves to **top-left**; the three HSB sliders sit to its **right**.
- Hex value + copy icon go **under the wheel**, not in their own full-width row.
- Three rows of exactly **8** swatches, stacked tight. Middle row offset right by roughly half a
  swatch + gap so the circles interlock (reduce vertical spacing to suit — that's the point).
- One of the common-colour swatches is the **"none"** circle with a diagonal line.
- **Delete the "RECENT" label** — it eats vertical space. The empty bottom row is self-evident.
- Remove the per-swatch `offset(...)` vertical translation helper I added; offset the **row
  containers** horizontally instead (a left margin/padding on row 2).
- **It must appear as a bottom drawer, not a centred dialog** — it currently covers the preview
  window. Covering the timeline is fine; covering the preview is not.

Live-preview / Set / Cancel semantics are **already correct and working** — do not change them:
`show(ctx, title, initial, allowNone, onLive, onPicked)`; `onLive` fires on every change,
`Set` only closes, `Cancel` replays the original through `onLive`. Verified on device.

### 3.2 All top-anchored drawers should cover the app header
JoyRaptor: the drawers currently stop below the header (project name, ✕, export button), and that
makes the header read like the *drawer's* own title bar and close button — confusing. Covering it
also buys vertical space.

In `FaditorEditorActivity`, the PiP/adjustment drawer is added with a `topMargin` set from
`R.id.editor_top_bar`'s height (grep `editor_top_bar` — there's a `lp.topMargin =` and a
`topBar.post(...)` that re-applies it). **Remove that inset** so the drawer starts at the top of
the root frame, and make sure the drawer is added *after*/above the header in z-order.

### 3.3 Long-press on an adjustment layer doesn't open its drawer
JoyRaptor reports this is still missing. `showAdjustmentDrawer(...)` exists and works (the Adjust
tool opens it). The gap is the **long-press route**: in `FaditorEditorActivity`'s
`layerGestureCallback()`, `onItemMenuRequested(track, item)` routes text/sprite/audio/PiP/waveform
to their menus but has **no `item.getAdjustment() != null` branch**. Add one calling
`showAdjustmentDrawer(item.getAdjustment())`. Also check `onItemDoubleTapped` for the same gap.

### 3.4 Adjustment layer drawer — verify on device
Merged this session but **never run on the phone**: Mask / Chroma-key / Blend-mode tabs
(`PipDrawerTabs.maskTab` was widened via a `LinkSource` interface; `blendTab` widened to
Supplier/Consumer; `AdjustmentLayer.blendMode` is new and threaded through `FxGlSource
.withComposite` so preview and export share it). Open an adjustment layer, exercise all three
tabs, and **export a clip** to confirm the blend mode matches the preview.

### 3.5 The "selected = target" rule, applied everywhere (JoyRaptor's consistency ask)
Fixed in `2d03ed9` for **Delete** and **Duplicate** only. Audit every other bottom-toolbar verb
for the same ordering bug — each must check layer-item selection *first*:
- Split (`splitAtPlayhead`) — already has an adjustment-layer branch; check text/sprite/PiP.
- Crop, Speed, Filter, Volume, Consolidate layers, Extract audio, Transitions.
Pattern to copy: `deleteSelectedLayerItem()` / `duplicateSelectedObject()` in
`FaditorEditorActivity` — get `editorTimeline.getSelectedLayerItemId()`, loop the typed lists,
act, return true; caller falls through to the master-clip path only on false.
Then add the **locked-object confirm** (§2.3) and make it **multi-select aware** (§2.4).

### 3.6 Heal / Split affordance rework (see §2.1 for why)
- A seam is **healable** iff the two adjacent master clips share the same `sourceUri`, and the
  left clip's `outPointMs` == the right clip's `inPointMs` (contiguous, in order). Clips from
  different sources or rearranged out of order are **not** healable — don't offer it.
- When the playhead is on a healable seam, the **Split** tool should swap its icon/label to
  **Heal**; otherwise it stays Split. No separate always-on Heal button firing errors.
- Healing rejoins the two clips into one (restore the right clip's out-point onto the left,
  delete the right, fix transitions via the existing
  `shiftTransitionsAfterSplit`/`removeTransitionsForDeletedClip` helpers), as one undo step.
- If the two halves are *nearly* contiguous but not exactly, JoyRaptor's suggestion: confirm with
  *"Healing this split would add/remove X to the timeline. OK?"*
- **Rename the existing `healAtPlayhead()` feature** (remove-a-span-inside-a-clip) to something
  honest like **"Remove section"** so the two stop colliding.

### 3.7 Text drawer — finish the two unfinished pieces
`tools/TextOverlayDrawer.java` (new) + `model/TextOverlayItem.java`.
- **Motion range is half-built.** `motionStartMs`/`motionEndMs` fields and the "Start motion
  here"/"End motion here" buttons exist and write the model, but **nothing reads them** — the
  entrance/exit animation evaluator in `TextBoxRenderer`, `TextOverlayLayer` and
  `CompositeExportOverlay` still uses the item's full on-screen span. Wire all three (preview +
  export parity), then add the draggable range carrots on the timeline block, mirroring the
  existing loop-animation / slide range handles (grep `LayerRowRenderer.java` and
  `EditorTimelineView.java` for how those are drawn and dragged).
- **Alignment is not applied on the GL text path.** `TextBoxRenderer` honours it;
  `overlay/TextOverlayRenderer.java` (the per-object-FX GL path) does not — documented gap.
- **DROP "plate" — RESOLVED.** The original spec listed `background` and `plate` as separate STYLE
  rows, but `TextOverlayItem` only has `backgroundColorInt` and the agent pointed both rows at it,
  so two controls edited one value. **JoyRaptor's ruling: "just use 'background' not plate."** Delete
  the Plate row entirely. One row, one field, no new model surface needed.

### 3.8 Rich text — per-selection formatting (NOT STARTED; JoyRaptor's newest requirement)
His words: *"the user can select specific words or parts and apply different formatting... if I
have selected a mixed group of text with different parameters it will have a split color swatch
in the circle or a 'mixed' text indicator and then if I click a variable it will overwrite the
mixed group within my selection with that one choice overriding."*

Everything currently built styles the **whole text box**. Required:
1. `List<StyleSpan>` on `TextOverlayItem` — each span `[startChar, endChar)` + *optional*
   overrides (font, bold, italic, underline, case, and each style layer's colour). Resolve
   last-span-wins per glyph; unspanned ranges fall back to the item's base style.
   **Check first:** `TextBoxRenderer` already walks text in per-unit runs via
   `CaptionAnimator.splitUnits` — that is the natural place to also split at span boundaries.
   (`CaptionSpanRef` is *per-clip caption placement*, not per-glyph style — not reusable.)
2. A text-selection gesture in the live preview (`TextOverlayLayer`/`TextBoxView`) reporting
   `[start,end)` to the drawer.
3. Every drawer control reads the selection's effective value; where the selection is **mixed**,
   show a split/half-and-half colour swatch (colour controls) or a "Mixed" label (font, case,
   toggles). Setting any control **overwrites the whole selection** with that value.
4. No selection → controls edit the base style, exactly as they do today.
5. Thread spans through **export** (`TextFxGlEffect` / `TextBoxRenderer`) or preview and export
   will disagree.

### 3.9 Gradient system — finish + verify
`fx/GradientRamp.java`, `tools/GradientRampEditorView.java`, new `FxParam.Kind.GRADIENT`,
`solid_color` + `gradient_fill` in `FxRegistry`. Compiles and the FX harness is green (93/93),
but **never run on the phone** and the gesture code is untested.
- **Curve gradient is a stub** — the shape chip literally says "Curve (soon)" and falls back to
  Linear. Needs 1–3 bezier vertices with handles, arc-length parameterised (sampled arc-length
  table in a fixed uniform array fits this codebase's existing `MaskSdf` packing pattern).
- Gradients have **no drag handles in the preview** — they're centred at a fixed extent, not
  start/end defined like Photoshop. JoyRaptor will likely want handles.
- Verify on device: tap-to-add stop, tap-stop-to-pick-colour, drag to move, drag-off to delete,
  bias diamonds, mirror/flip/solid-bands checkboxes.
- ⚠ **Review this behaviour change:** the gradient agent also switched the generic COLOR param row
  (previously three raw RGB sliders) to the round swatch + `ColorPickerDialog` for **all** effects
  app-wide (gradient_map, duotone, …). Almost certainly right, but JoyRaptor hasn't seen it.

### 3.10 Multi-PiP z-order (long-standing, from last night)
With more than one PiP on screen, the lower ones are drawn from **still frames on a Canvas that
sits above the GL composite**, so an adjustment layer never reaches them and their own FX don't
composite in order. Fix = feed those stills into the GL chain as 2D textures
(`FxPreviewTextureView` currently supports exactly one decoder-backed PiP). Non-trivial: needs a
program cache keyed by fxKey, a bitmap→texture cache, and a `sampler2D` variant of the PiP
fragment (the live one uses `samplerExternalOES`). Watch the recycled-bitmap race.

### 3.11 Gradient is NOT yet "the standard app-wide" (JoyRaptor said it must be)
His words: *"THIS WILL BE THE STANDARD FOR ALL GRADIENTS APP WIDE."*
**Only the new `gradient_fill` effect uses `GradientRamp`.** The pre-existing two-colour
`gradient_map` effect (`FxRegistry.java`, `BODY_GRADIENT_MAP` ~line 89, registered ~line 258)
still uses its own two-colour params, which is exactly the limitation JoyRaptor called out
(*"the gradient ramps we currently have are just two color, and that's pretty limiting"*).
Migrate `gradient_map` to a `FxParam.gradient(...)` ramp, and audit for any other two-colour
gradient in the codebase (duotone, caption/text backgrounds, visualizer styles) that should adopt
it. Keep a migration path so existing projects' two-colour values load as a 2-stop ramp rather
than resetting to default.

### 3.12 Adversarial design review of the gradient work — REQUESTED, NEVER DONE
JoyRaptor explicitly asked: *"so make it look very good, have an adversarial world class design
specialist review your work. you yourself are a Sr. level graphic design expert."* This did not
happen — the gradient shipped compile-verified only. Run a genuine design critique pass over
`GradientRampEditorView` and the colour picker together (they are the two widgets JoyRaptor judges
the app's craft by), covering: touch-target sizes, snap feel, stop-handle affordance, the
drag-off-to-delete discoverability, spacing/rhythm, and motion. The gradient agent itself flagged
three items for review:
- shape math uses a fixed extent around a `center` point — **no drag handles in the preview** to
  define start/end like Photoshop. JoyRaptor will almost certainly want these.
- Linear/Angle/Diamond/Box are aspect-corrected approximations, not pixel-exact Photoshop parity.
- the app-wide COLOR-param row change (§3.9's warning) is unreviewed.

### 3.13 Bottom-drawer collision — RESOLVED, implement this
Both the text drawer and the colour picker come from the bottom, and the text drawer contains
colour swatches. **JoyRaptor's ruling:** the colour picker covering the text drawer is fine — but the
nicer behaviour, which he wants, is that **the bottom drawer slides horizontally out of the way
(left or right) to reveal the colour picker, and slides back once the colour is Set.** His
reasoning: you cannot interact with anything else until you confirm the colour anyway, so the
drawer's content is dead weight during that moment. Animate it; don't just hide it.

### 3.14 Bottom TIMELINE drawer resize — JoyRaptor reported this TWICE, verify it
Pre-compaction: *"I'm not able to resize my bottom drawer. If I collapse things, I can make it get
shorter, but I can't pull down any further even if they're uncollapsed, nor can I bring it up to
make it taller."* Then again later: *"I still can't drag the bottom timeline drawer to resize
it."* This is the **timeline band**, not the top PiP drawer (the top one's grip resize was built
and works). The band has `host.setBandDp()` / `isGrabBarDragging()` machinery in
`player/PreviewPipController.java` and a grab bar in the timeline. Work out whether the drag is
wired at all, whether it is clamped too tightly, or whether something above it eats the touch —
then fix. **Do not assume this is done because a "bottom drawer resize" item was ticked off; that
referred to the top drawer.**

### 3.15 Drag affordance is undiscoverable
Pre-compaction: *"it looks that I can click on the word and drag it, but there's nothing really
that's discoverable to tell me that."* There is a one-time gesture coach mark
(`maybeShowGestureCoachMark()`), but no persistent affordance. Now that tap-to-select and drag
work on every object, give selected objects a visible "you can move this" cue — the handles help,
but consider a move cursor/grip glyph or a brief hint on first selection of each object type.

### 3.16 Gradient: is "sweep" missing?
JoyRaptor listed the shapes as: *"linear, radial, angle (like in photoshop) mirror, box, dimond,
sweep, curve"* — that is **eight**. The agent shipped seven: Linear, Radial, Angle, Reflected
(=mirror), Diamond, Box, Curve(stub). In Photoshop terms "Angle" and "sweep"/conic are usually the
same gradient, so this may be a duplicate in his list — **but confirm with JoyRaptor** rather than
assuming. If they are meant to be distinct, add the eighth.

### 3.17 Legacy adjustment UI still exists and duplicates the new one
Pre-compaction: *"there's an invert somewhere in this file, and I can't find it that's inverting
the video. Oh, here it is. We still have a stable legacy adjustment form, which is nowhere... From
when I click on a video clip in the main timeline."* There is an older per-clip adjust/grade UI
reachable from a master-clip selection that overlaps the new FX system. Find it, and either
retire it or fold it into the FX panel — two places to set the same thing is exactly the kind of
seam JoyRaptor called out as the enemy in the polishing stage.

### 3.14 Carried-over items from the two adversarial reviews (never closed)
These were found by review passes earlier in the session, triaged as lower priority, and never
returned to. Each is small.
- **C6** — FX card drag displaces neighbours by the *dragged* card's height. With mixed
  collapsed/expanded cards the gap opens at the wrong size. Should displace each neighbour by its
  own height. `FxPanel.slideNeighbours()`.
- **C10 / U10** — **touch pass-through only falls through to the master video, not to a PiP
  beneath it.** Currently documented honestly in code but not fixed. Now that
  `previewSelectionSource()` hit-tests all objects top-down, this is much easier: skip
  pass-through objects during hit-testing and keep walking down. (The hit-test already skips
  them; verify a PiP *under* a pass-through PiP is now reachable, which it may well be — this may
  already be fixed as a side effect. Test before implementing.)
- **C21** — dead unreachable branch in `FxPreviewTier.cardNote`.
- **U2** — `KeyframeDiamondControl` exists as a shared widget and is used by `PipDrawerTabs`, but
  `FxPanel` still draws its own ◆/◇ chip. **Two keyframe UIs for one concept.** Adopt the shared
  control in FxPanel.
- **U4** — no "Static — tap ♦ to animate" hint on an unkeyed FX param; the diamond's purpose is
  undiscoverable.
- **U11** — **tapping empty canvas does not deselect.** I deliberately left this alone (a miss in
  the preview is usually a miss, and deselecting would close the drawer being worked in), but
  JoyRaptor has never ruled on it. **Ask him.**
- **U13** — FX card header density; and card delete has no confirm (it *does* now have full undo,
  which is why the confirm was skipped — reconfirm that's the call JoyRaptor wants).

### 3.15 Landmine to be aware of (not a bug today)
`FxPreviewTextureView.pipFragment()` strips the compiler's emitted copy of `blendPix` by an
**exact string match** against `BlendModes.glslBlendFnWithModeParam()`. It is correct right now
because both sides interpolate the same constant — but if `FxCompiler.emitGlsl` ever changes that
text's whitespace or ordering, the strip silently stops matching, the PiP shader fails to compile,
the exception is caught, and **every PiP effect silently turns off again** with only a log line.
That exact failure cost hours this session. If you touch shader assembly, re-verify PiP effects
render.

### 3.16 Smaller loose ends
- `TODO(strings)` literals added throughout today's work need extracting to `strings.xml`.
- The `fx` badge on timeline rows is complete (clip/text/adjustment are the only payloads that
  can carry a stack — sprites and visualizers have no `getFx()`).
- Pinch-scale / two-finger rotate: **JoyRaptor confirmed working on device.** Done.
- Adjustment-layer lane drag: the blocking gate (`payloadCompatible`) is removed and the staging
  paths carry the payload, but **it was never proven with a finger** — adb cannot produce the
  gesture. Have JoyRaptor confirm.

---

## 4. What was completed today (all merged to `joy-creator`, typecheck + compile green)

| Commit | What |
|---|---|
| `e65eff5` | `fx` badge on timeline rows for anything carrying a live effect |
| `a3e73dd` | **Preview gesture overhaul** — one authority; tap-select anything, tap-and-drag in one gesture, pinch, two-finger rotate w/ 0° snap; selection syncs to timeline + drawer; PiP no longer starts with a mask; preview moves down but no longer shrinks |
| `da72cc1` | Adjustment layer can change lanes (`payloadCompatible` never listed it); PiP audio drawer restored on double-tap |
| `23690c9` | **Universal colour picker** + app-wide recents (layout now being redone, §3.1) |
| `3b09f5a` | 👇 pointing-hand pass-through icon; colour picker goes live (Set only closes, Cancel reverts) |
| `ea52bdf` | **Adjustment layers get Mask / Chroma-key / Blend-mode tabs** |
| `7789ae3` | **Bottom text drawer** replaces the modal editor — font+import, B/I/U, case, alignment, relocated motion, style rows w/ live colour + keyframes, shadow direction knob |
| `57511d1` | **Solid Color + Gradient effects** with the Photoshop-style ramp editor |
| `2d03ed9` | **Delete/Duplicate follow the selected object**, not the clip underneath |

Earlier the same night (context for why the FX system is trustworthy now): the GL live-preview
path was built and pixel-verified on the Note 9; a shader bug that silently disabled **every**
PiP effect was found and fixed; and two adversarial review passes found and fixed 14 defects in
that work, including an FX undo that restored an orphaned stack and destroyed the effect.

**Verification status of today's merges:** everything typechecks and compiles; the preview
gestures and the colour picker's live behaviour were confirmed on the Note 9. The adjustment-layer
tabs, the text drawer, and the gradient system are **compile-verified only** — nobody has run them
on the phone. Start there.

---

## 5. Full work queue, ordered, with sizes

Sizes: **S** ≈ under an hour · **M** ≈ a few hours · **L** ≈ a day · **XL** ≈ multi-day.
Nothing here is optional; the order is about risk and dependency, not importance.

**Wave 1 — device-verify what already merged (do this FIRST, before building on it)**
| # | Item | Size |
|---|---|---|
| 1 | §3.4 Adjustment-layer Mask/Chroma/Blend tabs on the phone, **including an export** to prove blend-mode parity | M |
| 2 | §3.7 Text drawer on the phone — every control, plus an export | M |
| 3 | §3.9 Gradient + Solid Color on the phone — all seven shapes, every ramp gesture | M |
| 4 | §3.16 Confirm adjustment-layer lane drag works with a finger | S |

**Wave 2 — small, visible, JoyRaptor is actively looking at these**
| # | Item | Size |
|---|---|---|
| 5 | §3.1 Colour picker layout redo + move to bottom drawer | M |
| 6 | §3.3 Adjustment long-press opens its drawer | S |
| 7 | §3.2 All top drawers cover the app header | S |
| 8 | §3.13 Resolve the bottom-drawer collision (text drawer vs colour picker) | S–M |
| 9 | §3.14 U2 — one keyframe widget, not two (adopt `KeyframeDiamondControl` in FxPanel) | S |
| 10 | §3.14 U4 hint · C21 dead branch · C6 neighbour heights | S |
| 11 | §3.14 U11 — **ask JoyRaptor** whether empty-canvas tap should deselect | S |

**Wave 3 — correctness and consistency**
| # | Item | Size |
|---|---|---|
| 12 | §3.5 "Selected = target" audit across every toolbar verb | M |
| 13 | §3.5 Locked-object confirm + make all verbs multi-select aware | M |
| 14 | §3.14 C10 — verify/fix pass-through falling through to a PiP beneath | S–M |
| 15 | §3.6 Heal/Split rework + rename the span-removal feature | M–L |

**Wave 4 — finish what's half-built**
| # | Item | Size |
|---|---|---|
| 16 | §3.7 Text motion range: wire the evaluator (preview **and** export) + timeline carrots | M–L |
| 17 | §3.7 Real `plate` model surface, separate from background | M |
| 18 | §3.7 Alignment on the GL text path | S–M |
| 19 | §3.11 Migrate `gradient_map` (and any other 2-colour gradient) to `GradientRamp` | M |
| 20 | §3.9 Curve/bezier gradient shape (currently stubbed to Linear) | L |
| 21 | §3.9 Gradient drag handles in the preview | M |

**Wave 5 — the big ones**
| # | Item | Size |
|---|---|---|
| 22 | §3.12 Adversarial design review of gradient + colour picker, then act on it | M |
| 23 | §3.8 Rich text — per-selection formatting with mixed-state controls | XL |
| 24 | §3.10 Multi-PiP z-order (stills into the GL chain) | L |
| 25 | §3.16 `TODO(strings)` extraction sweep | S |

**Note on "waves":** they are sequencing advice, not batches to parallelise blindly. Items 5–11
touch overlapping files (`FaditorEditorActivity`, `FxPanel`, drawer classes) and will conflict if
run as simultaneous agents. If you do parallelise, split by **file ownership**, not by wave — e.g.
one agent owns `ColorPickerDialog`+`ColorWheelView`, another owns `TextOverlayDrawer`+
`TextOverlayItem`+text renderers, another owns `FxPanel`+`FxRegistry`+gradient. Give each agent an
isolated git worktree and merge sequentially, typechecking after each merge. That worked cleanly
three-for-three today with zero conflicts.
