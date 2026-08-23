# Faditor work list — handoff

> **SUPERSEDED AS THE ENTRY POINT — read `tasks/HANDOFF_20260813_FADITOR.md` FIRST.**
> That file is the current, deduplicated state: environment gotchas, what landed and how each item
> was verified, the three claims I got WRONG this session, and open work in priority order. This
> document is still accurate history and keeps the long-form reasoning behind the ripple design,
> but it has accumulated superseded notes. Where the two disagree, the handoff wins.

Rewritten 2026-08-12 (evening); session-2 results folded in through 2026-08-13 ~10:30.
Branch `joy-creator`, 27 commits past `3703298`. Working tree clean apart from JoyRaptor's own
`tasks/` files. Both phones carry the current build.

**Where to start if you are new:** §1b (the seam jump) is the top user-facing bug and needs JoyRaptor
plus a logcat run. Everything device-blocked is marked as such. Everything claimed below states
HOW it was verified; where I got something wrong this session I have said so in the same place
rather than quietly fixing the text — see §2, which retracts a diagnosis I made a few hours
earlier, and §3, which backs out a feature I had already built.

Read `tasks/HANDOFF_20260808_JOYCREATOR_POLISH.md` §0 and §1 FIRST — environment rules, build
commands, architecture map. Everything below assumes you have.

**Ground rules that bite:**
- Never `git add tasks/`. Stage only the source paths you edited. Never `git add -A`.
- Dialogs: `new MaterialAlertDialogBuilder(ctx)` with NO theme-overlay arg.
- **Any visual property must be threaded through BOTH the preview renderer AND the export
  renderer.** Still the project's #1 rule.
- `bash tools/jvm-harness/typecheck.sh` must say TYPECHECK OK before you commit.
- Note 9 (`SANDBOX_SERIAL`) is the sandbox. Note 20 (`REAL_SERIAL`) is JoyRaptor's REAL phone.
  `build-install.sh` refuses while the Note 20 is attached — **JoyRaptor has repeatedly asked for
  installs to the Note 20 this session**; when he does, build with
  `./gradlew.bat --offline :app:assembleDefaultDebug` and
  `adb -s REAL_SERIAL install -r app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`.
  Confirm `lastUpdateTime` after every install.
- **Shell PATH gets clobbered** if you `export PATH=` in an earlier Bash call. If `javac`/`python`
  vanish, use:
  `export PATH="/usr/bin:/bin:/mingw64/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:/c/Python314:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools:/c/WINDOWS/system32"`

---

## STATUS — session 2 (2026-08-12 evening → 08-13 morning)

Devices came and went through the night; both are back and both carry the current build. JoyRaptor has
CONFIRMED on the Note 20: the front-trim keyframe fix, and pinch/two-finger rotate ("looks good,
snaps at the appropriate intervals").

**Biggest find of the morning, from JoyRaptor's canvas report:** a portrait project was exporting on a
LANDSCAPE canvas because the export never read the video rotation tag (§ below). That one wrong
answer produced black bars on the video AND squashed his image overlay. Fixed and device-verified.


**RIPPLE IS DONE AND DEVICE-VERIFIED.** Commits `e954856`, `2bbbd78`, `109165a3`, `e5c01b2`,
`40d8502`, `648dc02`, `5e4f438` on `joy-creator`. Details at the bottom under "WHAT SESSION 2 DID".
The section immediately below is kept for the reasoning; the gap it describes is closed.

**Your lost cut was already back before I touched anything** — you dragged clip 6's out-point to
501611 at 19:26, past its true 501515. No repair was written. Backups `project.json.pre-recut-1930`
and `undo_history.json.pre-recut-1930` sit on the Note 20 unused. The loss was NOT a trim you made:
the out-point was 501515 all day (09:09, 10:08, 16:19) and read 398273 by the app's own 17:12 save,
with only EXTENSIONS in the undo history — i.e. the `3cbd6b2` split-undo truncation, not your edit.

---

## THE ONE UNFINISHED, AGREED PIECE OF WORK — DO THIS FIRST — **DONE**

### RIPPLE OBJECTS ON EVERY LENGTH CHANGE `[M]`
JoyRaptor approved the design in conversation on 2026-08-12 and it is **not implemented**. He called
it existential: *"one simple trim could mess up a whole person's timeline … it only takes a person
losing a project once to wanna move on to a competitor."*

**Agreed rule:**
- **Ripple mode** (`Timeline.getRippleMode()`, default `"ripple"`): any edit that changes length at
  time T — trim, delete, split, and the UNDO/REDO of each — shifts everything after T. Clips **and
  objects**, whether or not the object is anchored.
- **Gap mode** (`"gap"`): nothing moves. This is the industry's per-track Sync Lock / Auto Track
  Selector, expressed as one project-wide mode.
- **Host anchors stay** as the finer mechanism. Anchors survive clip REORDERING, which a pure
  time-shift cannot; ripple handles length. FCP does anchors (connected clips), Premiere/Resolve do
  ripple + sync lock. JoyRaptor wants both.

**Where the gap actually is.** `Timeline.applyAnchorShift` (Timeline.java ~2048) only moves overlays
with a non-null `hostClipId`; an unanchored overlay sits at absolute time and never ripples. Three
of the twelve images in JoyRaptor's lecture project are unanchored.

**Implementation sketch (not yet written):**
1. `applyAnchorShift` already receives `beforeStarts` (clipId → old timeline start). For an overlay
   with NO host, find which clip's OLD span contained the overlay's start and shift by that clip's
   delta. Old spans are reconstructible from the ordered old starts.
2. Gate the whole shift on ripple mode — in gap mode `applyAnchorShift` should be a no-op.
3. Every length-changing path must be inside the bracket. Verified bracketed today:
   drag-trim commit (FaditorEditorActivity ~1773), delete (~31979), and now `TrimAction`
   (EditActions) which was fixed in `3703298`. **Still unaudited:** `splitAtPlayhead`,
   `SplitClipAction.execute/undo`, `DeleteClipAction`, `applySilenceCuts`, the linked-pair trim
   lambdas (~31898), and heal/remove-section.
4. Pin with harness tests BEFORE going near the phone: trim, delete, undo and redo each move
   objects correctly, in ripple mode and not in gap mode. `tools/jvm-harness/SplitUndoTest.java`
   is the template — it runs off-device via `run-matte.sh`.

---

## DONE THIS SESSION — do not redo

All committed on `joy-creator`, all typecheck + harness green.

| Commit | What |
|---|---|
| `4653da5` | Inert image-overlay tabs are labelled instead of silently doing nothing |
| `ba651a3` | **Image overlay blend modes reach the EXPORT** (`ImageBlendGlEffect`, `ImageOverlayFrameOverlay`, shared `ImageOverlayDraw`) |
| `3152cc4` | Blend routing reads ONE predicate (`TextOverlayItem.wantsExportBlend`), harness-pinned |
| `6aa9fd5` | Curve-gradient legacy `curve`→`path` migration (exact, harness-pinned); GPU shader failure now reaches the user |
| `4448c41` | Locked-object confirm actually unlocks; batch delete/duplicate are marquee- and lock-aware |
| `fa6e083` | **Image transform smoothness**: per-tick `rebuild()` removed, preview decode downsampled+cached, pinch stream no longer declined on a near-miss DOWN, drawer rows refresh live during hand gestures. Tap a number to type it |
| `948cde3` | `displaySize` cached per source URI (did NOT fix the flicker — see below) |
| `b8dadda` | Fine-drag zone on sliders (`FineSeekBar`); text drawer became a scrim; trim chips on one line |
| `4f9d890` | Text drawer opens two rows tall with a MORE affordance |
| `c5fd925` | App header slides away behind translucent drawers; trim chips semi-transparent |
| `9758719` | Font moved to drawer header (named, in its own typeface); title shows the text; `AlignIconView` (fixed-size drawn paragraph) |
| `c7dcdf8` | PiP handles gated on the PiP's time window; editing one text box no longer kills the whole preview; `setData` repositions instead of rebuilding; below-z overlays hit-testable; position rails scale with object size |
| `39fc5c1` | Scaling stopped rewriting the motion path (`addPropertyKeyframeAt` force-wrote X/Y/SCALE from STATIC fields); stale centre-limit clamp fixed; pinch scale/rotate deadzones |
| `ee5d8de` | **`snapRotation` corrected to −360° instead of 0°** — a −1° twist stored a full turn. Preview tapping resolves through the z authority |
| `ba835bf` | Consolidate layers minted fresh lanes for ones it had just emptied |
| `5d43f5e` | Placing a new text box stopped destroying it; rebuild guard actually guards |
| `09983b8` | **Playback died for the session when the OS reclaimed `cache/remuxed`** — cached playback URI now revalidated; recovery self-heals a missing file instead of latching off |
| `3cbd6b2` | **Split-undo returned a TRUNCATED clip** (`updateTrimEndOnly` wrote through to the model). Player adopts clip A by identity. Orphan dialog gained a **Cancel** |
| `afbd53e` | **Split-undo removed two clips by blind index** — could destroy unrelated clips (net −1). Both directions now identity-based |
| `3703298` | Undoing a trim left riders where the trim put them — `TrimAction` now brackets |

New harness tests: `SplitUndoTest` (run-matte), `CurveMigrationTest` (run-fx), `ImageBlendGateTest`
(run-matte). **Both SplitUndoTest assertions were proven to FAIL without their fix** — keep that
discipline.

---

## CORRECTIONS — things stated wrongly this session, do not re-inherit

1. **"Split orphans everything anchored to a clip" — FALSE.** `Timeline.splitAt` calls
   `reanchorAfterSplit`, which re-homes overlays onto the correct half. The 8 dangling
   `hostClipId`s in JoyRaptor's lecture are from splits made BEFORE that existed = legacy data only.
2. **"97 skipped frames on project load" — misattributed.** That was the LAUNCHER's process. In the
   app's own pid the load stalls are WebView/Chromium init, `GlTransitionCardBaker` baking
   transition strips, and a GC freeing 10MB of large-object bitmaps. **The preview flicker is still
   undiagnosed** — measure against those three.
3. **The rotate stalk does NOT shadow the corner handles.** Stalk sits 28dp out, grab radius is
   14dp. The nearest-wins hit-test that shipped is insurance, not a fix.
4. Rotation deadzone was briefly widened to 16° on a wrong diagnosis; reverted to 7°. The real
   cause was `snapRotation`.

---

## JOYRAPTOR'S DEVICE + DATA STATE (Note 20)

Project `a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc` — "first lecture on phone", schemaVersion **12**
(current is 13), 11 clips / 2585593 ms, 17 overlays (12 images), one PiP, transcriptPool of 23.

Repaired by hand this session (app force-stopped, diffed before/after, read back to verify):
- two rotation keys holding `-360.0` and `-720.0` → `0.0`
- **CAUTION / my error:** restoring `85ae9c0c` put back its bounds as of the 17:00 snapshot
  (`132998→398273`), which REVERTED whatever trim JoyRaptor had made to that clip in between. It now
  reads `132998→413827`. When hand-repairing a live project, restore only the field that is
  actually broken, never a whole object from an older snapshot, and say out loud what will be
  reverted BEFORE writing.
- image overlay `a4bb07db` restored after an undo removed it
- master clip `85ae9c0c` (132998→398273, 4m25s) restored after the split-undo index bug ate it

On-device backups: `project.json.bak-rotfix`, `project.json.rescue-173407`, `project.json.snap-181219`.
Local snapshots in the session scratchpad (`lec.json`, `RESCUE-*.json`, `now-*.json`, `verify*.json`).

**Legacy markers in this project** (may explain oddities that are NOT current bugs): schema 12; the
same source file recorded with two different `sourceDurationMs` (2755799 vs 2757301); 8 dangling
host anchors; per-clip `activeTranscript` indices that disagree with each other.

---

## OPEN WORK

### 1. `[M]` Ripple objects — see the top of this file. **Highest priority.**

### 1b. `[M]` THE SEAM JUMP IS NOT FIXED — start here, with this lead
Three real defects were fixed today (`3cbd6b2`, `afbd53e`, `3703298`), each proven by a test that
failed without it, and JoyRaptor STILL hears the jump. At least one cause remains. Do not assume the
earlier fixes were wrong — they were independently verified — assume a fourth cause.

**The exact seam, from his device (2026-08-12 ~18:30):**
- clip 6 `85ae9c0c` source 132998→413827, timeline start 118554, so it ends at timeline **399383**
- clip 7 `eb36b1df` source 506760→1725033
- there is a **108-second gap in the SOURCE** between them (413827 → 506760), which is what the
  user hears as "jumps to this is hannah" — i.e. playback is reaching source content that should
  have been skipped, or seeking to the wrong clip.

**The lead.** The project runs `gapless=false` (legacy path — forced whenever any master source is
an unremuxed fMP4, which a screen recording is). On that path:
- `FaditorPlayerManager.isAtTrimEnd()` (~line 1020) reports end as
  `pos >= effectiveTrimEnd() - 150L`, where `pos` is an ABSOLUTE source position and
  `effectiveTrimEnd()` is `min(trimEndMs, duration)`.
- The ADVANCE to the next clip is driven by the ACTIVITY, not the player — find the
  `isAtEnd → advance` logic and check which clip it selects and how it derives the next position.
  A stale `selectedClipIndex` or a cumulative-offset recomputation there would produce exactly this
  symptom.
- Check also that `adoptClipSilently` (added `3cbd6b2`) leaves `trimStartMs`/`trimEndMs` correct
  for the clip actually being played after an advance.

**Cheapest decisive evidence:** have JoyRaptor play across the seam while `adb logcat` runs, then read
the `FaditorPlayerManager: SEEKRANGE` lines — they print `rel`, `window` and `clip=<id>`. If `clip`
is right and `rel` is wrong, it is the offset maths; if `clip` is wrong, it is the selection.

### 2. `[M]` Preview flicker on project load — **STILL UNDIAGNOSED. My earlier "mechanism" was WRONG.**
`5cb0663` claimed the zero-height fallback rect was the flicker; `bb1e6a5` retracts it. The
measurement is real — `computeVideoContentRect`'s fallback fires 8–9 times in ~180ms during load
and every call reports `view=1080x0`, the PlayerView and its parent both unmeasured — but **every
consumer already bails on a degenerate rect** (`TextOverlayLayer.position`, four sites in
`PreviewHandlesOverlay`, the sprite draw, the mask-drag path). So it is ignored, not laid out
against, and it is not the flicker. I wrote the diagnosis from the measurement without reading the
consumers.

Tried and REJECTED on device: deriving the rect from the source aspect via `displaySize` — the
parent reports `1080x0` too.

What IS measured about the load, for whoever picks this up: activity START → WebView init 0.4s →
`GlTransitionCardBaker` bakes ~15 strips in ~160ms (2 shader compile failures, `powerKaleido`) →
project loaded → GC frees 8.2MB of large objects → **84-frame skip** at surface setup. The 84-frame
skip is a stall, not a flash, so the flicker is still unexplained — next step is to capture the
load with `screenrecord` and step frames, rather than reason from the log.

Load timeline for reference (same run): activity START → WebView init 0.4s → `GlTransitionCardBaker`
bakes ~15 strips in ~160ms (2 shader compile failures, `powerKaleido`) → project loaded → GC frees
8.2MB of large objects → **Skipped 84 frames** at surface setup.

### 3. `[L]` Image overlays — **EXPORT DONE AND DEVICE-PROVEN (mask + FX + chroma). PREVIEW IS THE REMAINING PIECE.**

`05613e7`, `57e5380`. All three now reach the export and all three are verified on a Note 9 by
MEASURING exported frames against a dump of the overlay bitmap, not by looking at them:
- **mask** — the window is cut exactly as authored;
- **FX** — with an invert card, 174 of 190 sampled points inside the mask are closer to the INVERSE
  of the un-effected image than to it;
- **chroma key** — keyed to the image's own dominant colour at max tolerance, the image leaves the
  frame entirely; its uniforms were confirmed reaching the shader first (`keyParams=[1,1,0,0]`).

**The `758285c` backout was my mistake and is reverted.** The fixture's overlay is a photo of the
SAME SCENE as the video, so "the image, inverted" and "the video, inverted" look identical; I read
a frame by eye and concluded the composite was corrupt. A second false negative on the key came
from analysing a stale screenshot. Both were caught by measuring. **If you test image FX with an
overlay that resembles the footage, you will fool yourself the same way.**

**PREVIEW still shows images un-effected** and the drawer says export-only. The route is to feed
image overlays into the GL composite as stills — the machinery `FxPreviewTextureView` already uses
for PiPs beyond the live-decoder cap (`Pip.of(...)` takes a fused pass, its uniform values and a
key). `RenderEffect` (API 31) and AGSL (API 33) are NOT options: both test devices are API 29.
Sketch: build a `Pip` per image overlay from its animated centre/size/rotation/opacity plus its
decoded bitmap and `FxStack`, insert it at its place in `LayerPreviewController
.orderedCompositedItems`, and hide the Android view for it while GL owns it (the same
`glOwnsImagePreview` dance image CLIPS already do) or it will draw twice.

<details><summary>superseded: the backout note</summary>

**Mask works and is verified** (`22d6062`): `ImageOverlayDraw` runs the same `MaskPathBuilder` a PiP
does, so it lands on BOTH export paths, and it does not route to GL. Proven on the Note 9 with a 2s
fixture — the mask window is cut exactly as authored.

**FX and chroma are written, compile, and are NOT routed.** With routing live, the export composite
came out inverted ON THE WRONG SIDE: through the mask window the VIDEO was inverted (purple
dinosaur → green, blue slide → orange) while the image was untouched. Backed out — a corrupted
frame is worse than an inert tab.

Everything else was verified correct on device first, which narrows the suspect hard:
routing reached the emitter (`gl=true fx=true stack=1/1`); the composed `main()` is exactly right
(`sc → fadKeyAlpha → fxc → fxBlendOver(fxc, fx0(ovc,fxc), …) → sc → mix(base, blendPix(base,sc), a)`);
it compiled; uniforms uploaded (`u0_amount=1.0 u0_opacity=1.0 u0_blend=0.0`); invert's default
amount is 1 and a card's default opacity is 1. **So the fault is in how the overlay texture's ALPHA
relates to that fold — not routing, splice, compile or uniforms.** Note the arithmetic says a
transparent region (`a=0`) must leave `base` untouched, and on device it did not: that contradiction
is the thread. Next probe: render the overlay texture alone to a frame and look at its alpha, and
check whether `ExportManager` emits the effect TWICE (it iterates both z buckets).
</details>

<details><summary>superseded note from the first FX attempt</summary>
Mask and Chroma key are still inert. **FX now compiles into the export**: `ImageBlendGlEffect`
splices the item's `FxStack` exactly as `BlendModeGlEffect` does for a PiP, routed through one
predicate (`TextOverlayItem.wantsGlExport()` = blend or fx). SAMPLER cards (blur) are skipped, as
they are for a PiP. Preview unchanged — the drawer note now says export-only instead of inert.

**What was verified:** full compile, all harnesses, `ImageBlendGateTest` extended to 12 checks, and
a real 720p export on the Note 9 ran to completion **with no shader compile failure and no
`VideoFrameProcessingException`** — i.e. the spliced shader compiles and runs on a device.

**What was NOT verified — do this next.** I exported the same project twice, with and without an
`invert` card on one image overlay, and diffed the frames. The differences are confined to the
overlay band (y≈1200–1450; the video itself is untouched), but sampling that band shows the pixels
are NOT the inverse of the control (mean RGB 152/110/124 vs 149/108/116; only 3624 of 12483 sampled
pixels are closer to inverted than to identical). So either the effect is not reaching the pixels,
or the band I sampled is the PiP rather than the image, or the two frames are not the same instant.
Do it properly with a still, full-frame image overlay and a `threshold`/`invert` card, and compare
at a frozen playhead. **Do not treat image FX as working until that comes back clean.**
</details>

### 4. `[M]` Keyframing sweep — JoyRaptor: *"Moving, placing, scaling, rotating, dropping keys, and
animating should all be super easy. So do a sweep of all those things."*
Partly addressed (`39fc5c1`, `ee5d8de`) but never swept end to end.

**Session 2 pass (code-level; the gestures themselves still need a finger):**
- **FIXED `76b9fe8`** — pulling an object's FRONT trim dragged every keyframe with it. Keys are
  stored local to the item's start, so moving the start moved the animation. `setTrimmedTimeRange`
  rebases by exactly the start delta; `setTimeRange` keeps MOVE semantics. Reported by JoyRaptor.
- **FIXED `e11548b`** — the same bug for SPRITES, found by reading rather than by report. Their
  keys share the same local base. Checked the other families: a PiP's transform keys and an
  adjustment layer's FX keys resolve against ABSOLUTE timeline ms, so their front edges were
  already right and were left alone.
- **Checked and CORRECT, so recorded rather than changed:** `addPropertyKeyframeAt` converts the
  playhead to local time before writing, so every key-drop path lands on the right base; negative
  key times (a key the front trim passed) survive `KeyframeTrack.put` and the codec, which is what
  makes dragging a handle out and back exactly reversible even across a reopen. Both pinned
  (`08fc041`), as is the fact that a RIPPLE must carry an animation while a TRIM must not
  (`6d84810`) — the two methods now sit side by side and "make these consistent" would be wrong.

Still unswept: the gesture feel itself (pinch/rotate/drag), which adb cannot drive.

### 5. `[S]` Undo should say WHAT it is about to undo
JoyRaptor lost track of which action each press was consuming. `EditAction.getDescription()` already
exists — surface it (toast or a history popup).

### 6. `[S]` Cancel on the OTHER destructive dialogs — **DONE, audit clean**
Two were genuinely trapping and got an escape (`5e4f438`): the load-skip dialog gained "Close
without saving" (both its other buttons COMMIT — "Keep going" finalises the dropped items on the
next save, "Open last backup" abandons this session's file), and the relink prompt gained a Cancel
that stops a relink-ALL sweep instead of forcing an answer per missing file.

Then every `MaterialAlertDialogBuilder` in `ui/faditor` was swept (83 of them). Everything else
either already has a Cancel — heal/remove-section, "Save look", "Exact start time" — or is a plain
acknowledgement with nothing to lose ("Read-only project", "<effect> needs its own pass"). The one
without a Cancel that isn't an acknowledgement is FxPanel's numeric entry ("Done"), where the back
button dismisses and nothing is destroyed. **No keep-or-lose choice is left without a way out.**

<details><summary>original note</summary>
`4653da5`…`3cbd6b2` added Cancel to the orphaned-anchor dialog only. JoyRaptor's rule: *"if it's gonna
give you the option to keep something or delete it, you might as well also have the option to cancel
the whole thing."* Audit every confirm that offers two destructive-ish choices.
</details>

### 7. `[S]` PINCH IS STILL UNVERIFIED ON A DEVICE
adb cannot inject a second pointer. The delivery fix (`c7dcdf8`) and the deadzones (`39fc5c1`) are
reasoned, not observed. Also unverified: whether a drag still snaps at 400% scale.

### 8. `[M]` Image export parity caveats (carried over)
- Sampler effects scale differently on images: export runs adjustment layers at canvas size,
  preview at decoded-bitmap size capped at 1920. A blur radius means different things. **Untouched.**
- Master-clip opacity keyframes invisible while the GL chain is engaged — **COULD NOT REPRODUCE, and
  no fix shipped.** I wrote one (mirror the export's post-composite placement by fading the GL
  surface, since `OpacityExportEffect` runs after the overlay pass) and then could not show it did
  anything: on the sandbox an opacity envelope of 1.0→0.0 faded the preview correctly WITHOUT it
  (luma 96.9 → 22.9 → 6.0). Both attempts to force the GL chain from a hand-authored project.json
  failed — zero `fx program compiled` lines — so the fixture never engaged the chain and the test
  was vacuous in both directions. The change was reverted rather than shipped unproven.
  **Next: create the adjustment layer THROUGH THE UI (so the chain really engages), then re-test.**

### 9. `[M]` Multi-decoder follow-ups (carried over)
Cap is 2 live PiPs; the fallback path has never been forced on a device. Untested: audio with two
opted-in overlays, sustained frame rate with two live PiPs + adjustment layers.

### 10. `[S]` Curve gradient loose ends (carried over)
`SAMPLE_POINTS` is 26; raise only if an extreme three-vertex path visibly flattens.

### 11. `[XL]` ONE timeline↔source mapping authority
A subagent found **four** implementations of timeline-ms → (clip, source offset):
`EditorTimelineView.getSegmentStartTime` + `SegmentData.effectiveMs`, `Timeline.segmentStartMs`,
`FaditorEditorActivity.getAbsolutePlayheadMs`, and `Clip.getEffectiveDurationMs`. **They already
disagree** — the first three ignore `removedSpans`, the fourth does not, so any clip carrying a heal
span makes the transcript's word→time math differ from the tape's. Collapsing these onto `Timeline`
is the durable fix JoyRaptor asked for ("one transcript and clips window") but it touches
`EditorTimelineView`, ~15 call sites in the activity, and `FaditorPlayerManager`. Not required for
any bug currently known.

---

## WHAT SESSION 2 DID (2026-08-12 evening, autonomous)

| Commit | What | Verified how |
|---|---|---|
| `e954856` | **Unanchored objects ripple.** `applyAnchorShift` only moved riders with a `hostClipId`; one with none sat at absolute time while the footage slid under it. Also gated the whole shift on `rippleMode` (gap = nothing moves; orphans still reported) | 7+12 assertions proven to FAIL without it |
| `2bbbd78` | **`3703298` had introduced a DOUBLE shift.** The editor brackets undo/redo wholesale AND `TrimAction` brackets itself, so undoing a 500ms trim moved riders 1000ms. `Timeline` now owns the bracket (`beginStructural`/`endStructural`), keyed on the outermost bracket's identity, not a depth counter — two editor sites could return early between begin and end and a leaked counter would have switched ripple off silently | assertion fails (3200 vs 2700) without the guard |
| `109165a` | **Split-undo stranded its riders** on halves that no longer existed. A dangling host is read as an orphan forever, so the rider never ripples again. This is where your 8 dangling anchors came from — they were NOT just legacy data | 3 assertions fail without it |
| `e5c01b2`+`20ce381` | Load re-homes already-dangling anchors (`healDanglingHostAnchors`), on the load paths only — deliberately not on snapshot restore | harness; idempotence + valid-anchor-untouched pinned |
| `40d8502` | **Sprites, PiPs, audio clips, adjustment layers and detached visualizers had NO mechanism to follow an edit** — only `TextOverlayItem` can carry an anchor. All ripple now | 5 assertions fail without it |
| `648dc02` | Bracket audit: silence removal, delete-linked-pair, speed, loop mode/extend/edge-resize were all unbracketed length changes whose UNDO was covered — so each do/undo cycle drifted | model half pinned; brackets reasoned |
| `ece8be4` | `correctDurationFromPlayer` clamps in/out on its own when ExoPlayer disagrees with the stored duration — the one length change nobody asks for, now bracketed | typecheck |
| `5e4f438` | §5 undo/redo toast (previous toast cancelled, not queued); trim descriptions in clock time. §6 Cancel on the load-skip and relink dialogs | **device**: "Undid: Move overlay", 48→47 |
| `822a2c9` | **Snapshot-based undo double-shifted.** Every entry is snapshot-only after a project is reopened, so this was the normal case | **device**: exact round trip + the skip log line |
| `5cb0663` | §2 flicker: measured that the fallback rect has ZERO HEIGHT during load (see §2 below) | device measurement |
| `f623565` | **AI edits (`EditScriptApplier`) never bracketed anything** — a dozen length changes in a row with nothing following them. One bracket per script, in a `finally` | typecheck + harnesses |
| `403d13a` | Pins WHY the bracket guard is identity-based: a leaked bracket must not switch rippling off for the session | leak scenario asserted |
| `b45de37`→`758285c` | Image FX/chroma export: built, then **backed out** — the composite came out wrong (§3) | device-proven wrong, then reverted |
| `22d6062` | **Masks on image overlays reach the export** (both paths, same `MaskPathBuilder` a PiP uses) | **device**: mask window cut as authored |
| `76b9fe8` | **Front trim no longer drags keyframes** (JoyRaptor's report) | 5/18 assertions fail without it |
| `e11548b` | Same fix for SPRITES, found by reading | 2 assertions fail without it |
| `08fc041`,`6d84810` | Pins: trimmed-past keys survive save/load; ripple carries an animation, trim does not | harness |
| `e7b0d11` | **Retracts** the §2 flicker diagnosis in `5cb0663` — every consumer already ignores that rect | re-read the consumers |
| `a281910` | **Export completion is usable**: poster frame + tap to play, view-in-Recordings, Recordings auto-refreshes on export, one-shot arrival pulse beside the NEW badge | compiles; UX not yet exercised |
| `599bd6a` | Image overlays ignored **EXIF orientation** — fixed on BOTH renderers | compiles |
| `5c62c71` | **A portrait project exported on a LANDSCAPE canvas** — the export never read the video ROTATION tag | **device**: cap line 1280x720 → 720x1280 |
| `05613e7`,`57e5380` | Image **FX + chroma** re-landed and proven; the earlier backout was a misread | **device**, measured |

**Device-verified on the Note 9 sandbox** (`P0 control2 plain`, 4 unanchored objects): deleting a
500ms clip moved all four left by exactly 500 (9190→8690, 7749→7249, 9396→8896, 43159→42659) and
undo returned every one to its exact original. Nothing was installed to the Note 20.

### Still open on ripple
- **IN-point trims** do not move riders inside the trimmed clip (its start does not change, so
  there is no delta). Out-point trims are exact. Documented at `Timeline.applyAnchorShift`.
- A rider past the LAST clip's old start can't be distinguished from one inside it; both take the
  last clip's delta. Needs the old total duration captured alongside the starts.

### §1b seam jump — what I ruled OUT, honestly
Built a 2-clip repro on the sandbox from a screen recording with a 5s SOURCE gap at the seam.
**That project ran `gapless=true`, so it did NOT reproduce your configuration** and proves nothing
about your seam; on the gapless path the advance was clean (`head=4008 sel=0→1`, window position
restarting at 0). The premise in §1b that an unremuxed fMP4 forces the legacy path did not hold for
that file. Next person: force the legacy path explicitly rather than assuming a screen recording
gets it. `correctDurationFromPlayer` was found and bracketed on the way past; it is not the seam.

---

## DELIBERATELY NOT DOING
- **The 47-commit upstream merge.** Fork is far ahead; upstream work is recorder-side and unblocks
  nothing in the editor. Deserves its own session.
- **Unifying `selectedClipIndex` vs layer-item id.** `[XL]`, high regression risk; the user-visible
  benefit comes from "selected = target", which is largely done.
- **The UI/design review.** JoyRaptor runs that separately.

---

## Verification standard
Device-verified = built, installed, exercised, and the specific claim confirmed by a screenshot,
pixel sample, or log line. "It compiles" is not verification. **Report honestly what you verified
versus what you only built.** This session that mattered repeatedly: three separate diagnoses were
wrong and had to be corrected, each time because a plausible signal was trusted before it was
checked. When a fix is reasoned rather than observed, say so in the commit message.
