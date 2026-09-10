# Handoff — 2026-08-06, overnight run: SPEC_IMAGE_SEQUENCE

Branch `joy-creator`. Six commits, `822f27c` → `f6fed86`. Tree clean, all harnesses green,
APK installed and timestamp-checked, both borrowed projects restored byte-exact.

**SPEC_IMAGE_SEQUENCE.md is BUILT and DEVICE-VERIFIED end to end**, including the export A/B.
The masking work that was in flight is closed out except for one on-device check, named below.

---

## ⚡ THE MOST USEFUL THING IN THIS HANDOFF: the agent can build again

`bash tools/build-install.sh`

Runbook §7f said agents could not build here, based on
`java.io.IOException: Unable to establish loopback connection`. **That message is a red
herring and cost three sessions.** Loopback is fine — bind, connect and cross-process accept
on 127.0.0.1 all succeed. The real failure is `Selector.open()`, which on JDK 17/Windows
builds its wakeup pipe from an **AF_UNIX socket pair**, and AF_UNIX `connect` fails with
`Invalid argument` when `java.io.tmpdir` is an **8.3 short path** —
`C:\Users\JOYRAP~1\AppData\Local\Temp`, which is exactly what the agent shell gets. Gradle
opens a Selector to reach its daemon, so every invocation died there and blamed the network.

```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"
```

`JAVA_TOOL_OPTIONS`, not `GRADLE_OPTS`: the **daemon** needs it too and Gradle does not
forward `-D` into `daemonOpts`. Runbook §7f is rewritten with the diagnosis.

Also new: `bash tools/jvm-harness/typecheck.sh` — javac over all 592 app sources against the
real Android classpath, ~1 minute, no Gradle. It does NOT cover resources (R.jar is a build
artefact, so a NEW id/string reads as "cannot find symbol"); green means the Java type-checks,
not that the APK builds.

**And the lesson that cost ~20 minutes tonight:** the user's watcher died silently at 00:57
while `build.log` kept showing an old `BUILD SUCCESSFUL`. Device testing ran against stale
bits and "the tape draws nothing" was really "the tape isn't on the phone". `build-install.sh`
prints `lastUpdateTime` next to the wall clock every run. **Check it. Do not trust build.log.**

---

## What shipped, against the spec

| § | Thing | State |
|---|---|---|
| §2 | Weights as the only timing model | **built + device-verified** |
| §2a | RELATIVE / ABSOLUTE resize, mode visible ON THE TAPE | built; edge-drag not exercised on device |
| §3a | Sibling-run detection, OFFER never assume, capped | **device-verified** ("We found 12 images") |
| §3b | ONE question, two presets, three live-linked fields | **device-verified** |
| §3c | `10.5s` `1/6 min` `00:00:10.5` `90f` `2m30s` | **device-verified** (`1/6 min` → 1.2 fps live) |
| §3d | Convert to sprite sheet as a drawer ACTION | built; not exercised on device |
| §4 | Tape: uniform thumbs, position carries timing | **device-verified** |
| §5a | Hold = a count badge | **device-verified** (×2 badges) |
| §5b | Vertical drag on the SELECTION, gesture claimed | **device-verified** (frame 3 ×2→×5, 12.00s→13.50s) |
| §5c | Stride / ramp / on-ones-twos-threes / numeric / reverse / shuffle | built; "On twos" and "On ones" device-verified |
| §6 | Loop, ping-pong, and a SAFE "continues" | built + harness-pinned; not exercised on device |
| §7 | `describe_sequence` / `edit_sequence` | built; **NOT exercised — see gaps** |
| §8 | Memory bound, integrity, consolidator, preview==export | **PREVIEW==EXPORT PROVEN ON REAL PIXELS** |
| §9a | Holds survive a resize | automatic (weights are the only unit) |
| §9c | Live `24 frames · 4.0s · 6.0 fps` readout | built; not exercised on device |

### The load-bearing proof (§8)

Fixture `98e303a6` (one 13.7s clip + a 12-frame sequence at 2 fps, all weights ×2 → 1s per
frame). Exported, pulled the mp4, extracted frames with ffmpeg, sampled centre pixels:

| t | expected frame | found |
|---|---|---|
| 0.5s | 0 | 0 ✓ |
| 1.5s | 1 | 1 ✓ |
| 4.5s | 4 | 4 ✓ |
| 8.5s | 8 | 8 ✓ |
| 11.5s | 11 | 11 ✓ |
| 12.5s | — (past the 12s end) | nothing drawn ✓ |

That is the one-evaluator rule proving itself on pixels rather than on argument.

---

## How it fits the existing sprite work (read this before changing anything)

The spec's §0 is emphatic that a second evaluator would be the project's oldest mistake
repeated. There isn't one.

- **A sequence is a KIND of `SpriteSheet`** (`kind = grid|sequence`, `frameUris[]`). Everything
  non-addressing — frame track, resolver, presets, end behaviour, preview view, export overlay,
  timeline tape, palette, gesture contract — is shared unchanged.
- **Weights ride `SpriteSheet.Preset`**, and the tick domain is the one `SpriteFrameResolver`
  already computed (`floor(elapsed × fps / 1000)`). With no weights a tick IS a frame — today's
  arithmetic exactly. `SequenceTimingTest` re-implements the OLD index maths inline and demands
  the live resolver agree on every tick of every preset type at every length.
- **fps stays the stored authority**; total duration is a derived view (`fps = Σw / seconds`).
  That is why frame rate, seconds-per-frame and total length are three windows on one array
  rather than three modes.
- `SpriteSheetRenderer` grew a `SequenceFrameCache` backing rather than a sibling class, so
  every consumer keeps calling `drawCell`/`cellAspect`.

**Two directions that must stay opposite:** editing a WEIGHT lengthens the object (fps
unchanged — the animator's expectation). Dragging the object's EDGE in RELATIVE mode keeps the
weights and moves fps. Each leaves the other's authored value alone. If you change one, check
the other.

---

## Adversarial review — 8 defects found, 7 fixed (commit `c6f5d9b`)

A read-only agent reviewed the whole session diff. Fixed: a resize on a
"continues" object being silently reverted (and keeping the new fps — a 60s object
playing a 10s run); every dope-sheet edit destroying the frame selection; `makePresetFromKeys`
deleting keys it refused to include; the AI resolving open-ends across all lanes where the
editor resolves per lane; "clipped by neighbour" being reported when the PROJECT END stopped
it; the two entry points of the one evaluator disagreeing about fps clamping; avatar rigs
blitting frame 0 for every part of a sequence sheet; and the sheet manager offering Edit/Relink
on sequences (both no-ops or worse) plus placing them as a static cell.

### ⚠ NOT fixed — the one that needs a daylight decision

**fps is SHEET-scoped, but a RELATIVE resize derives it from ONE placed item's span.** Place a
sheet twice, resize one, and the other silently retimes: same span, but its frames now play at
the dragged item's cadence and it holds the last frame for the remainder. No cue on its row, and
no undo entry of its own — undo happens to restore it only because the sheet's fps is captured
in the dragged item's step.

The honest fix is **copy-on-write**: a RELATIVE resize on a sheet with more than one placement
clones the sheet (frameUris are just strings), repoints this item, and sets fps on the clone —
with the sheetId swap inside the same undo step. That is a real change and I would not land it
at 4am unreviewed. `cycleSequenceLoopMode` has the same shape and is slightly worse: it writes
sheet-scoped `preset.type` AND item-scoped `endBehavior`, so cycling on one placement leaves the
other with mismatched halves.

### Also open, lower severity

- **Multi-select import silently drops non-matching files.** Selecting `shot_001.png…040.png`
  plus `shot_041.jpg…045.jpg` imports 40 and says nothing about the 5. Detection filters to the
  first pick's stem+extension and the activity builds only from that. The single-pick case gets
  a dialog; the partial-drop case gets nothing.
- **ABSOLUTE resize does not actually differ from RELATIVE in the model.** The ABSOLUTE branch
  does nothing (fps is left alone and the span just ends early), so the documented left-vs-right
  asymmetry — "trimming from the left decides WHICH frames survive" — does not exist, and
  `SequenceTiming.framesFittingIn` is only ever called by the readout. The red "cuts frames"
  comb handle advertises a destructive difference the gesture does not deliver.
- **The MISSING-frame affordance has no callers.** `isCellMissing` / `clearMissingCache` /
  `SequenceFrameCache.isMissing` / `clearFailures` form a chain nothing consumes, so an
  unreadable frame draws as a gap with no placeholder, and `isSpriteSheetMissing` returns false
  for a sequence with 239 of 240 frames gone (`loadSequence` succeeds if ANY frame decodes).
  `ProjectIntegrity` *does* check all N — the detection is right, the surfacing is missing.
- **`SequenceFrameCache`'s 24MB budget is per-cache**, and there are up to three caches per
  sheet (editor, timeline, export). Three sequences with the timeline open is a 144MB ceiling.
  Per-consumer caches are deliberate; the constant was reasoned about as if global.
- **`frameChangesIn` rounds where `resolveCellAt` floors**, so scrubbing exactly onto a tape mark
  can show the previous frame (sub-frame, cosmetic). `Math.ceil` is the correct inverse.
- **Dead code:** `MaskKeyPanel` (357 lines, no callers — left in place deliberately, see
  `c5bd23f`), `SpritePalettePanel.openDopeSheet()`, `SequenceImportDialog.showFor()`.

## Genuinely open

1. **The mask panel has not been opened on device this session.** `MaskKeyPanel` gained the two
   writers that make masking non-inert ("Move with the object", "◆ Key at playhead"), it
   type-checks and is installed, and `MaskAnimatorTest` (22/22) pins the arithmetic — including
   the pixel-rotation claim, which now fails loudly if someone "simplifies" it to a normalised
   rotation. But *"the dialog inflates and the checkbox writes"* is unproven, and the runbook's
   note about bare `MaterialAlertDialogBuilder` is exactly the sort of thing that only fails at
   runtime.
   **To check:** open a project with a PiP (`aeb0517e` has one at 7.948s on lane `video`),
   select the PiP, object menu → Mask, toggle "Move with the object", then confirm
   `project.json` shows `"link": true` plus `linkBase*` in that clip's compositing block.
   I could not reach that menu by coordinate tapping — the PiP overlaps an image overlay in the
   preview and its lane row stayed collapsed. Fingers will find it in seconds.

2. **The AI tools are wired but unexercised.** No OpenRouter key in the sandbox, and the
   headless ADB path applies EditScripts rather than tools. Their arithmetic is the
   harness-pinned `SequenceTiming`; their dispatch is not. A JVM harness for them needs a real
   `org.json`, which is not in the gradle cache.

3. **Not exercised on device** (built, type-checked, harness-pinned where applicable):
   §2a edge-drag resize + §9c readout, §6 continues/loop chips, §3d convert-to-sprite-sheet.

4. **The `SequenceDetector` cap is 600 frames.** Fine for the spec's own 240-frame example;
   revisit if someone points at a longer render.

---

## Device state

- Sandbox `302da9ac` — byte-identical to pristine (`aac2ba5c`).
- `aeb0517e` — restored byte-exact from `project.json.bak-seq-20260806` (`f764e002`) after I
  borrowed it for the first import test.
- `98e303a6` "Untitled" — **left in place deliberately.** It is the verification fixture: one
  clip plus a 12-frame sequence. Open it, drag the palette grip up twice, and the dope sheet is
  right there.
- Test frames live at `/sdcard/Download/seqtest/frame_001..012.png` (12 numbered coloured
  discs). Regenerate with PIL if needed — they are just circles with a number.
- Rotation lock still 0. Note 20 was never attached.

## Harnesses

```bash
bash tools/jvm-harness/run-sequence.sh   # 87/87 timing+tape, 50/50 import
bash tools/jvm-harness/run-key.sh        # chroma, volume, span, MaskAnimator 22/22
bash tools/jvm-harness/typecheck.sh      # whole-app javac, no Gradle
bash tools/build-install.sh              # build + install + freshness check
```
