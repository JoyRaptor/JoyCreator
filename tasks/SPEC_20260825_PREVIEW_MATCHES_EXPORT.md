# SPEC — The preview must show what the export will produce

**Written:** 2026-08-25 · **For:** an external agent · **Owner ruling:** JoyRaptor.

> "It is imperative that these all function similar to what people use in Adobe Photoshop
> where in the preview, it correctly shows what will be exported. And in the preview, I can
> see blend modes, stacked effects, and masks altogether properly interacting on stacked
> images and videos."

Every fact in §1 was verified against the repo on 2026-08-25. **Read §3 before writing code** —
it lists mistakes already made on this exact problem, including one that cost this session two
rounds and one that is explicitly warned about in the source and was still repeated.

---

## 1. What is actually true today (verified)

**There are TWO renderers and they are separate implementations of the same picture.**

- **Export** builds a media3 `Effect` list per clip in
  `export/ExportManager.java` — crop at `:2820-2831` (`new Crop(left, right, bottom, top)`
  in NDC, i.e. `cropLeft * 2 - 1`), then the effect stack, blends and masks.
- **Live preview** is drawn by the GL renderer `compositor/FxPreviewTextureView`, driven by
  `compositor/FxLivePreviewController`, wired up in
  `FaditorEditorActivity.syncGlAdjustmentPreview()` (~`:22760`) against
  `R.id.fx_preview_view`.

Coverage today, by number of files mentioning each feature:

| Feature | export/ | compositor/ | Symptom JoyRaptor reports |
|---|---|---|---|
| Crop (`getCropLeft`) | 1 | **0** | Crop never shows in the preview at all |
| Blend modes (`BlendMode`) | 7 | 4 | "Screen" composites over the spine clip but not over images below it |
| Masks (`MaskPathBuilder`) | 3 | 2 | A mask only appears once a blend mode is set — the shader path is the only one that implements it |
| `EffectStack` | 1 | 2 | (works) |

**There is also a THIRD, DEAD crop path.** `FaditorEditorActivity.updatePreviewTransforms()`
(`:8918`) implements crop as a `PlayerView` transform — `setClipBounds` plus scale/translate.
It still runs, but the pixels the user sees come from the GL surface, so it changes nothing
visible. It is the most likely source of the "squish to a third height, then pop" flash at
every clip seam, because it transforms a view underneath the GL output using
`effectiveVideoSize()` (`:7824`), which reads the *player's current* video size and is stale
or zero for a frame at each transition.

**Nothing is broken about the saved data or the export.** `project.json` holds every crop
(17 of 21 clips on JoyRaptor's main project, byte-identical to the 2026-08-21 backup), and the
export reads it correctly. This is purely a preview-fidelity defect.

---

## 2. Required end state

**One source of truth for the picture.** The preview must compose from the same per-clip
description the export composes from — crop, effect stack, blend modes and masks — so that a
frame shown at playhead T is the frame the export writes at T.

Strongly preferred: extract the export's per-clip effect-list construction into a shared
builder that BOTH `ExportManager` and `FxLivePreviewController` call, rather than adding a
second crop/blend/mask implementation to the compositor. **A second implementation is what
caused this bug.** If a shared builder proves impossible, say so and explain why before
writing a parallel implementation.

Specifically:

1. **Crop renders in the preview**, for `cropPreset == "custom"` and for named presets, using
   the same NDC conversion as `ExportManager:2822-2825`. It must follow the **playhead**, not
   the selection (see §3.4), and must survive scrubbing across clips with different crops.
2. **Masks render without requiring a blend mode.** A mask with blend mode `none` must show.
3. **Blend modes composite against everything beneath them** — the master spine clip AND any
   image/video layers below — in the same z-order the export uses.
4. **The seam flash is gone.**
5. **The dead path is removed or neutralised.** Do not leave a third crop implementation in
   the tree. If `updatePreviewTransforms()` still has non-crop duties (rotation, flip), keep
   only those and delete its crop block, with a comment saying where crop now lives.

---

## 3. Traps — read every one, these have already been paid for

**3.1 — Check which renderer draws the pixels BEFORE fixing anything.** This session lost two
rounds fixing *which clip* the preview asked about, when the renderer reading that answer was
not the one on screen. Before you change a line, confirm which surface the user is looking at.

**3.2 — Do NOT try to fix this with media3 `setVideoEffects` on the player.** The source says
so at `FaditorEditorActivity:7271`: measured on 2026-08-07, the export's own `Effect` list
handed to the player left saturation 0 fully saturated, routed and unrouted. That is exactly
why the grade moved to `FxPreviewTextureView`. The comment ends "Do not spend the afternoon on
it twice." Do not spend it a third time.

**3.3 — Preview and export must not drift again.** Whatever you build, add a check that fails
when a clip property affects the export but not the preview. The repo already has a working
pattern for file-provable checks: `export/run_negctl_suite.py` and `tasks/probe_*.py`, with
demonstrated negative controls (a probe that cannot fail proves nothing).

**3.4 — Never read `getSelectedClip()` for anything the preview shows.** It falls back to
`getClip(0)` when nothing is selected (`FaditorEditorActivity:822-832`), silently returning
the WRONG clip. On JoyRaptor's project clip 0 is uncropped, so the preview reset itself to full
frame. Use `clipUnderPlayhead()` (`:22738`). That method is also unguarded against an empty
timeline — `Timeline.getClip()` (`model/Timeline.java:289`) is a bare `clips.get(index)` and
throws. Roughly 120 call sites of `getSelectedClip()` remain unaudited.

**3.5 — `View.animate()` is ONE shared animator per view.** Starting a second animation on the
same view cancels the first mid-flight, including axes you did not name. Any writer must carry
every axis it cares about. See commit `30ef6b68` and `tasks/lessons.md`.

**3.6 — Never rewrite a whole file to make a small edit.** Commit `584904f9` did, and the
rewrite introduced a UTF-8 BOM plus 19 lines of double-encoded text; `javac` rejected the BOM
on line 1 and HEAD did not compile. Repaired in `07f36175`. Edit in place; if you must rewrite,
verify the first bytes are not `EF BB BF` and that the file still decodes as UTF-8.

**3.7 — Build protocol.** Read `tasks/LANES.md` fresh and follow it, especially **rule 6: never
run gradle/gradlew.** Save, then read `build.log` for a fresh `BUILD SUCCESSFUL`, and confirm
its mtime is NEWER than your last edit. A stale log claiming success has cost this project a
day. `bash tools/jvm-harness/typecheck.sh` is a javac-only check you may run yourself; it is
not a substitute for a real build, and it passed on a tree that gradle could not compile.

**3.8 — Claim a lane before your first edit** (LANES.md rule 1) and `git add` each file the
moment you edit it (the WORKING-TREE HAZARD note — uncommitted work in this repo has been
destroyed at least six times). Commit by explicit path; never `git add -A`.

---

## 4. Out of scope

- The floating PiP / landscape pop-out work — a different, in-flight task.
- The `getSelectedClip()` empty-timeline crash and its caller audit — real, tracked as S5 in
  `tasks/SPEC_20260824_VERIFIED_FIXES.md`. Do not start it here; just don't add new callers.
- Any change to the export renderer's output. Export is correct today. If a shared builder
  changes exported bytes, that is a regression, and the probe suite must prove it did not.

---

## 5. Reporting

State line counts from `git diff --numstat`, not estimates. Paste the literal last line of
`typecheck.sh` and of the probe suite. Say plainly which of the five §2 items are done and
which are not — an unfinished item that is named is worth more than a finished one that
reached past this spec. Items 2 and 3 (masks without blend, blend over lower layers) need
JoyRaptor's eye on a device; say so rather than claiming them.
