# HANDOFF — Faditor, 2026-08-13

**Written for a fresh agent of any model family.** Assume you know nothing. Read §0 and §1 before
touching code. This supersedes `WORKLIST_SONNET_TIER.md` as the entry point; that file is still
accurate but has grown layers of superseded notes, and where the two disagree, **this one wins**.

Branch `joy-creator`. HEAD `57e5380`, 26 commits past `3703298`. Source tree clean; the only
modified files are JoyRaptor's own under `tasks/`.

Owner: **JoyRaptor**. Direct, notices unfinished edges, and values an honest "not done" over a
confident overclaim. He has been burned by silent data loss in this editor, twice. **Never report
something as working that you have not verified, and say which kind of verification you did.**

---

## 0. Environment — the commands that actually work

| Thing | Value |
|---|---|
| Repo | `C:\+Projects\Screenrecorder\FadCam` |
| Language | Java, Android, minSdk 24, OpenGL ES 2.0 / GLSL ES 1.00 |
| Main file | `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (~32k lines) |
| Sandbox phone | Note 9, `SANDBOX_SERIAL`, **API 29** |
| JoyRaptor's real phone | Note 20, `REAL_SERIAL`, install ONLY when he asks |
| App package | `com.fadcam.beta` |

```bash
# PATH first, every time — a previous `export PATH=` in the same session clobbers it
export PATH="/usr/bin:/bin:/mingw64/bin:/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin:/c/Python314:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools:/c/WINDOWS/system32"

bash tools/jvm-harness/typecheck.sh     # fast, no device. MUST print TYPECHECK OK
bash tools/jvm-harness/run-anchor.sh    # AnchorShift + RippleObjects + TrimKeyframeBase
bash tools/jvm-harness/run-matte.sh     # Matte + AdjustmentLane + CompactLane + ImageBlendGate + SplitUndo
bash tools/jvm-harness/run-fx.sh        # FX/shader golden strings
bash tools/build-install.sh             # compile AND install to the Note 9
```

**Gotchas that cost me time — do not rediscover them:**

1. `./gradlew.bat` invoked directly fails with `Unable to establish loopback connection`. It needs
   what `build-install.sh` sets: `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"`.
2. `build-install.sh` **refuses to run while the Note 20 is attached** (by design). To build with
   both phones plugged in, or to install to the Note 20 when JoyRaptor asks:
   ```bash
   export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"
   ./gradlew.bat --offline :app:assembleDefaultDebug
   adb -s <serial> install -r app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk
   adb -s <serial> shell dumpsys package com.fadcam.beta | grep lastUpdateTime   # ALWAYS confirm
   ```
3. `typecheck.sh` **cannot see newly added resources** (stale `R.jar`), so a brand-new
   `R.string.*` fails typecheck but compiles fine. Confirm with
   `./gradlew.bat --offline :app:compileDefaultDebugJavaWithJavac` before believing the failure.
4. Chain commands with `&&`, not `;`, when a commit depends on a check passing. I committed once
   on a failing typecheck because of `;`.
5. `adb` sees nothing if the phone's USB mode is "charging only" — it must be File transfer/MTP.
6. Reading a project: `adb -s <serial> exec-out run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
   Writing one: push to `/data/local/tmp` then `run-as ... cp`. **`export MSYS2_ARG_CONV_EXCL='*'`
   first** or the shell rewrites device paths and silently empties the target.

---

## 1. Rules that have caused real damage when broken

1. **Never `git add tasks/`.** That directory is JoyRaptor's own working documents, including this
   file. Stage only the source paths you edited. `git add -A` is forbidden.
2. Dialogs must be `new MaterialAlertDialogBuilder(ctx)` with **no** theme-overlay argument.
3. **WYSIWYG mandate.** Any visual property must reach BOTH the preview renderer and the export
   renderer. Where that is not yet true, the drawer must SAY so — the codebase has an
   "export-only" note pattern (`faditor_image_*_export_note`) and an "inert" one for features that
   reach neither.
4. **Prove a fix fails without the fix.** Every harness test added this session was run against
   the un-fixed code first; the commit message records what failed and by how much. Keep that.
5. **Bracket every length change.** See §2 — `beginStructuralEdit()` … `endStructuralEdit()`.

---

## 2. What landed this session, and how each was verified

**DEVICE-VERIFIED** means exercised on the Note 9 and the specific claim confirmed by a screenshot,
a pixel measurement or a log line. **HARNESS** means proven off-device with a test that fails
without the fix. **COMPILE** means it builds and is reasoned, nothing more.

### Ripple — objects follow every length change (the headline)
| Commit | What | Verified |
|---|---|---|
| `e954856` | Unanchored objects ripple; whole shift gated on `rippleMode` (gap = nothing moves, orphans still reported) | HARNESS (7+12 assertions fail without) |
| `2bbbd78` | **`3703298` had introduced a DOUBLE shift** — editor brackets undo wholesale AND `TrimAction` bracketed itself. `Timeline` now owns the bracket; ownership keyed on the outermost bracket's map IDENTITY, not a depth counter, because two sites could return early and a leaked counter would switch rippling off silently | HARNESS (3200 vs 2700) |
| `109165a` | **Split-undo stranded riders** on halves that no longer existed; a dangling host is read as an orphan forever and never ripples again. Redo re-homes too | HARNESS (3 assertions) |
| `e5c01b2`+`20ce381` | Load re-homes already-dangling anchors, on the LOAD paths only — never on snapshot restore, where the restored state is the truth | HARNESS |
| `40d8502` | **Sprites, PiPs, audio clips, adjustment layers and detached visualizers had no mechanism at all** to follow an edit; only `TextOverlayItem` can carry an anchor | HARNESS (5 assertions) |
| `648dc02` | Bracket audit: silence removal, delete-linked-pair, speed, loop mode/extend/edge-resize were unbracketed length changes whose UNDO was covered — so every do/undo drifted | model half HARNESS; brackets COMPILE |
| `ece8be4` | `correctDurationFromPlayer` — the one length change nobody asks for — bracketed | COMPILE |
| `f623565` | **AI edits (`EditScriptApplier`) never bracketed anything.** One bracket per script, in a `finally` | COMPILE |
| `822a2c9` | **Snapshot-based undo double-shifted.** Every history entry is snapshot-only once a project is reopened, so this was the NORMAL case. Detected by Timeline identity | **DEVICE** (exact round trip + skip log line) |
| `403d13a` | Pins why the guard is identity-based: a leaked bracket must not disable rippling for the session | HARNESS |

**Device proof of the whole feature:** deleting a 500 ms clip in `P0 control2 plain` moved four
unanchored objects left by exactly 500 (9190→8690, 7749→7249, 9396→8896, 43159→42659); undo
returned every one exactly, including through the snapshot path.

### Keyframes
| Commit | What | Verified |
|---|---|---|
| `76b9fe8` | **Front trim dragged every keyframe with it** (JoyRaptor's report). Keys are local to the item's start, so moving the start moved the animation. `setTrimmedTimeRange` rebases by the start delta; `setTimeRange` keeps MOVE semantics | HARNESS (5/18) + **JoyRaptor confirmed on device** |
| `e11548b` | Same bug for SPRITES, found by reading. PiP and adjustment-layer keys are ABSOLUTE-time and were already correct | HARNESS (2 assertions) |
| `08fc041`, `6d84810` | Pins: a trimmed-past (negative) key survives save/load, which is what makes the handle reversible; and a RIPPLE carries an animation while a TRIM does not — the two methods sit side by side and "make these consistent" would be wrong | HARNESS |

### Export correctness
| Commit | What | Verified |
|---|---|---|
| `5c62c71` | **A portrait project exported on a LANDSCAPE canvas.** `ExportManager` read width/height and never the ROTATION tag; media3 applies rotation when decoding, so the whole pipeline is in rotated space. Fed the canvas cap (black bars on the video), the OVERLAY bitmap size (a 4:3 image exported 3:4 and squashed), waveform geometry and the bitrate. Fixed at the source with `sourceDisplayDims()` | **DEVICE** (`1280x720` → `720x1280`) |
| `599bd6a` | Image overlays ignored **EXIF orientation** — fixed on BOTH renderers, because fixing only the export would trade a wrong picture for a preview that disagrees | COMPILE |
| `22d6062` | **Masks on image overlays** reach the export, via the same `MaskPathBuilder` a PiP uses, on both export paths | **DEVICE** |
| `05613e7`, `57e5380` | **Image FX and chroma key** reach the export | **DEVICE**, measured (see §3) |

### UX
| Commit | What | Verified |
|---|---|---|
| `5e4f438` | Undo/redo toast what they consumed (previous toast cancelled, not queued); trim descriptions in clock time; "Close without saving" on the load-skip dialog; Cancel on the relink sweep | **DEVICE** (toast + counter) |
| `a281910` | Export completion: poster frame + tap to play, view-in-Recordings, Recordings auto-refreshes on `ACTION_EXPORT_COMPLETED`, one-shot arrival pulse ALONGSIDE the NEW badge (they answer different questions) | COMPILE — **not yet exercised** |

---

## 3. Mistakes I made — do not re-inherit them

Three claims I made this session were wrong. Each is corrected in the code and in git; they are
listed here so nobody rebuilds on them.

1. **"Image FX corrupts the composite" — FALSE, and I reverted a working feature over it**
   (`758285c`, undone by `05613e7`). The overlay image in the test fixture is a photo of the SAME
   PLAYGROUND SCENE as the video, so "the image, inverted" and "the video, inverted" look
   identical. I read a frame by eye. **If you test image effects with an overlay that resembles the
   footage, you will make this mistake too.** Measure instead — see §5.
2. **"The zero-height fallback rect is the load flicker" — FALSE** (`5cb0663`, retracted by
   `e7b0d11`). The measurement is real (the fallback fires 8-9 times in ~180 ms with `view=1080x0`)
   but every consumer already bails on a degenerate rect. I wrote the diagnosis from the
   measurement without reading the consumers.
3. **"Chroma key does nothing" — FALSE.** Two bad tests: the first hand-wrote flat
   `keyEnabled`/`keyColor` fields that the loader ignores (the wire format is a nested
   `chromaKey` object), the second analysed a stale screenshot.

The pattern in all three: a plausible signal trusted before it was checked. The previous handoff
warned about exactly this and I did it anyway. Budget the extra ten minutes to measure.

---

## 4. Open work, in the order I would take it

### 4.1 `[M]` Preview of image-overlay effects — the remaining half of §3
Export is done and proven; the preview still shows images un-effected and the drawer says
export-only. `RenderEffect` (API 31) and AGSL (API 33) are **not options** — both phones are API 29.

The route is the GL composite that already exists: `FxPreviewTextureView` composites the master
clip, adjustment layers and PiPs, and its `Pip` record already carries a fused FX pass, its uniform
values and a key, with a still-bitmap path (`still upload OK` in logcat) used for PiPs beyond the
live-decoder cap. Sketch:
- build a `Pip` per image overlay from its animated centre/size/rotation/opacity + decoded bitmap +
  `FxStack`;
- insert it at its place in `LayerPreviewController.orderedCompositedItems` (image overlays are
  already visual items there);
- hide the Android view for that overlay while GL owns it — the same `glOwnsImagePreview` dance
  image CLIPS do — or it draws twice.

### 4.2 `[M]` §1b the seam jump — still the top user-facing bug, needs JoyRaptor
Three real defects were fixed on 08-12 and he still heard the jump. Assume a fourth cause. My
sandbox repro ran `gapless=true` and therefore proves nothing about his configuration (his project
forces the legacy path). **Cheapest decisive evidence:** have him play across the seam on the Note
20 with `adb logcat` running and read `FaditorPlayerManager: SEEKRANGE` — if `clip` is right and
`rel` is wrong it is the offset maths; if `clip` is wrong it is the selection.

### 4.3 `[M]` §2 the load flicker — undiagnosed, needs one sentence from JoyRaptor
Burst-capturing a load at ~3 fps showed nothing anomalous (one transition, then stable), and that
rate cannot catch a sub-300 ms flash. **Ask what flashes and to what** (the video, an image, the
whole preview; to black, to the wrong size, to the wrong position) before spending more. Measured
load timeline for reference: activity START → WebView init 0.4 s → `GlTransitionCardBaker` bakes
~15 strips in ~160 ms (2 shader compile failures, `powerKaleido`) → project loaded → GC frees
8.2 MB of large objects → **84-frame skip** at surface setup.

### 4.4 `[S]` Exercise the new export-completion UX
`a281910` is compile-only. Export something, then check: poster frame appears, tapping it plays,
"view in Recordings" switches tabs, the list already contains the file without a pull-to-refresh,
and the row pulses once (and only once — scroll it off and back).

### 4.5 `[S]` §7 pinch — **CONFIRMED GOOD by JoyRaptor** on 08-13 ("snaps at the appropriate
intervals"). Remaining unverified: whether a drag still snaps at 400% scale.

### 4.6 `[M]` §8 sampler-effect scaling parity
Export runs adjustment layers at canvas size, preview at decoded-bitmap size capped at 1920, so a
blur radius means different things. Untouched.

The opacity-under-GL half of §8 **could not be reproduced** and no fix shipped: an opacity envelope
faded the preview correctly without one, and two attempts to force the GL chain from a
hand-authored project.json failed (zero `fx program compiled` lines), so the fixture never engaged
the chain. **Create the adjustment layer THROUGH THE UI before testing that again.**

### 4.7 `[XL]` §11 ONE timeline↔source mapping authority — needs JoyRaptor's call
Four implementations of timeline-ms → (clip, source offset) disagree: three ignore `removedSpans`,
`Clip.getEffectiveDurationMs` does not. Concretely relevant: `clipSpanMs` — which every ripple
delta, anchor and hit-test rests on — uses `getTrimmedDurationMs`. The editor's own view uses the
same space, so ripple is self-consistent; it is playback and export that sit in the other one.
JoyRaptor's project has heal spans, so this is live, not theoretical. High regression risk: ask first.

### 4.8 Known limits deliberately left in the ripple work
- An **in-point** trim does not move riders inside the clip being trimmed (its start never moves,
  so there is no delta). Out-point trims are exact. Documented at `Timeline.applyAnchorShift`.
- A rider past the LAST clip's old start takes that clip's delta; distinguishing it needs the old
  total duration captured alongside the starts.

---

## 5. How to test rendering claims without fooling yourself

The technique that finally settled §3, reusable for any "did this effect reach the pixels" question:

1. **Dump the input.** A one-shot `bitmap.compress(PNG)` in `ImageOverlayFrameOverlay.getBitmap`
   writes the exact frame-sized overlay the export composites to
   `getExternalFilesDir(null)/overlay_dump.png`. Pull it. Now you have ground truth.
2. **Build a MINIMAL fixture.** One 2-second clip, one overlay, nothing else — a 6-second project
   took ~10 minutes to export on the Note 9; a 2-second one takes ~13 seconds.
3. **Export, play it in the app, screenshot.** The player letterboxes: a 9:16 export on the
   1080x2220 screen is full-width and vertically centred, so the video occupies `x 0..1080`,
   `y (2220-1920)/2 .. +1920`. **Pin that rect** — detecting it from pixels catches the player
   chrome and smears every sample (it moved my invert result from 132/56 to 174/16).
4. **Compare numerically**, sampling only where the dump is opaque: is the screen pixel closer to
   the dump's colour or to its inverse? Scripts in this session's scratchpad: `prove_invert.py`,
   `prove_key.py`, `fixrect.py`.

**Wire formats for hand-authored fixtures** (all learned the hard way — the app silently drops what
it cannot parse, so ALWAYS read the project back after pushing and confirm your field survived):
```jsonc
"fx": { "cards": [ { "id": "invert", "slot": 0 } ], "nextSlot": 1 }   // opacity defaults to 1
"compositing": {
  "masks": [ { "cx": 0.5, "cy": 0.5, "w": 0.6, "h": 0.6, "corner": 1.0 } ],
  "invertMasks": true,                                    // true = visible only inside
  "chromaKey": { "color": "#786078", "tolerance": 0.45, "fuzziness": 0.2 }   // presence = enabled
}
"opacityKeyframes": [ { "t": 0, "o": 1.0 }, { "t": 1900, "o": 0.0 } ]      // clip-local ms
```
Effect ids live in `FxRegistry` (`invert`, `levels`, `threshold`, `posterize`, `duotone`,
`gradient_map`, `rgb_shift`, `pixelate`, the blurs…). `invert`'s amount defaults to 1.

**A stripped uniform throws.** `GlProgram.setSamplerTexIdUniform`/`setFloatUniform` raise on a
uniform the driver removed as unused. `setFxUniforms` guards each set for that reason; the
sampler and blend-mode sets in `ImageBlendGlEffect.drawFrame` do NOT, so if you edit that shader
and leave `base` unused, the export dies with an NPE inside `Assertions.checkNotNull`.

---

## 6. Device and data state

- **Note 20** (JoyRaptor's): carries the build installed at `2026-08-13 09:31:43` — ripple, undo toast,
  front-trim fix, image masks, EXIF, the canvas rotation fix, and the new export dock. It does
  **NOT** have the image FX/chroma re-land (`05613e7`, later). Ask before installing.
- **Note 9** (sandbox): current HEAD. Sandbox project `302da9ac-…` was used as a test fixture all
  session and has been **restored to its original contents**; scratch exports from the testing are
  still in `files/FadCam/Faditor/` and are harmless.
- **JoyRaptor's lecture project** (`a32d24e2-…`, Note 20): untouched by me. His lost cut was already
  recovered by his own hand-dragging before I started; backups `project.json.pre-recut-1930` and
  `undo_history.json.pre-recut-1930` sit unused beside it. The out-point reads 501611 versus its
  true 501515 — 96 ms of overshoot, his to keep or nudge.

## 7. Deliberately not doing
- The 47-commit upstream merge (recorder-side; unblocks nothing here; deserves its own session).
- Unifying `selectedClipIndex` vs layer-item id — `[XL]`, high regression risk, little user-visible
  gain left.
- The UI/design review — JoyRaptor runs that separately.
