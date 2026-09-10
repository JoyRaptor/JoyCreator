# SPEC — Device verification sweep: look at all fourteen unlooked-at features

**Written:** 2026-08-29 · **For:** an external agent (suggested: the IMAGE_ANIM_PRESETS
lane, whose phase 3 is blocked) · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim a lane named `SPEC_20260829_DEVICE_VERIFY_ALL`.
**Take the `DEVICE:` token** before any `adb` command and release it when you stop.

**This spec writes NO production code.** If you find a defect, you write it down; you do
not fix it. Fixing means editing files three other lanes are living in, and a verification
pass that also changes things cannot say what it verified.

---

## 1. Why this is the highest-value work in the repo

Fourteen features are committed, compiled and installed. **One** has been seen working.
The building has run far ahead of the looking, and the gap is where this project's real
risk sits — on 2026-08-29 an audio feature shipped that silenced all audio, and the
acceptance run that would have caught it in three seconds was never done.

You are not proving the code compiles. You are answering: **does a person, holding the
phone, get the thing the spec promised?**

---

## 2. Ground rules — the ones that were broken before

1. **Never run gradle.** LANES rule 6. On 2026-08-29 an agent ran `--rerun-tasks`, corrupted
   the resource merge, and left JoyRaptor without an installable build for twenty minutes.
2. **Paste `adb devices` output.** If nothing is attached, say so and stop. That is a
   complete, honest, acceptable result.
3. **Never describe a screenshot you did not take.** On 2026-08-28 an agent invented PSNR
   figures for an unplugged phone and every claim it made had to be re-checked.
4. **Report the build you tested.** `adb shell dumpsys package com.fadcam.beta | grep
   lastUpdateTime` plus the APK's mtime. A verification of yesterday's APK is worthless.
5. Screenshots to `tasks/screenshots/`, named for the check (`v03_ramp_easein.png`).

---

## 2b. HOW TO DRIVE THE PHONE — read this before check 1

**Added 2026-08-29 after a run returned 0 PASS / 0 FAIL / 41 BLOCKED.** That agent was
honest, and the blockage was my spec's fault: it never said how to navigate. It got three
screenshots of the splash screen and stopped.

**Read `tasks/DEVICE_CONTROL_RUNBOOK.md` first.** The essentials:

- `uiautomator dump` returns a NULL ROOT on these phones. There is no accessibility tree.
  The only loop is: **screenshot → read the pixels → tap by coordinate → screenshot to
  confirm.** If you are waiting for an element tree you will wait forever.
- `FaditorEditorActivity` is **not exported** — you cannot `am start` it. You must navigate
  through the UI from the launcher.
- Screenshots come back at full device resolution. Note the device: Note 20 `<note20-serial>`
  is 1440×3088; Note 9 `<note9-serial>` reports 1440×2960 physical but an **override
  size of 1080×2220** — `adb shell wm size` first and compute taps against the OVERRIDE.

A working path into a project, verified by hand on 2026-08-29:

1. `am force-stop com.fadcam.beta`, then
   `monkey -p com.fadcam.beta -c android.intent.category.LAUNCHER 1`; wait ~8s.
2. Screenshot. The bottom nav has six icons; the **clapper-with-pencil ("Faditor")** is the
   4th. On the Note 9 (1080×2220) that tap is about **(628, 2110)**; on the Note 20
   (1440×3088) about **(833, 2928)**. Recompute from your own screenshot rather than
   trusting these.
3. Screenshot: the project list. Tap a project row (~y of its title). Wait ~12s — a project
   with media takes time to open.
4. Screenshot: the editor. Transport play is centred above the timeline; on the Note 9 it
   was about **(539, 971)**, on the Note 20 about **(718, 2031)**.
5. **Tap play ONCE.** Tapping twice pauses it again, which will make you report a stopped
   AudioTrack as a failure. This happened.

Useful non-visual evidence, which is often stronger than a screenshot:

- `adb shell "dumpsys audio | grep <pid>"` — an AudioTrack at `state:started` proves audio
  is flowing; `state:idle` proves it is not.
- `adb logcat -d | grep "new player piid"` — counts AudioTrack allocations. Churn here is a
  bug even when it sounds fine.
- `adb logcat -d -s AudioLayerSync:V` — `drift baseline` once per layer then silence is
  correct; any `repark` is a failure.

**If you genuinely cannot reach a screen, BLOCKED is the right answer** — but say which tap
failed and attach the screenshot you were looking at, so the next run starts further along
than yours did.

## 3. The checks

Each row: what to do, and what a PASS looks like. Record **PASS / FAIL / BLOCKED** and the
screenshot filename. `FAIL` needs one sentence of what you saw instead.

### 3.1 Keyframe shapes (`88e73dc3`)

| # | Check | PASS looks like |
|---|---|---|
| 1 | All 15 `Easing` values as glyphs, side by side | every one visually distinct |
| 2 | **Ramp direction.** Two keys; set the first `EASE_IN`, screenshot; set `EASE_OUT`, screenshot | the two are mirror images, and the slow-start one starts flat on its right side. **The check most likely to be backwards — do it carefully** |
| 3 | Same keyframe at max and min timeline zoom | detail at max, clean silhouette at min, never a smudge |
| 4 | Hollow / solid / carved-`×` on a ramp and on a circle | all three states work on shapes other than the diamond |
| 5 | The `?` legend sheet | five shapes, live curves, no "ease in/out" jargon |
| 6 | The long-press-drag-up family cycle | cycles, one undo step per change |

### 3.2 Audio sync + A/V calibration

| # | Check | PASS looks like |
|---|---|---|
| 7 | Press play on a project with a music layer. Time from playhead moving to first sound | under ~30ms wired; no perceptible gap |
| 8 | Play 5 minutes. `logcat -s AudioLayerSync:V` | `drift baseline [n] = ...` once per layer, then **silence**. Any `repark` is a FAIL |
| 9 | Count AudioTracks during 30s playback: `logcat \| grep "new player piid"` | **0** after playback starts |
| 10 | Listen to those 5 minutes | no clicks, no pitch wobble, no volume pumping |
| 11 | A/V Sync tab: measured value + route, wired vs Bluetooth | BT substantially larger. Identical = route detection broken |
| 12 | A/V Sync **Test**: click track + flashing dot, move the slider | flash and click converge |
| 13 | With a latency set, does the waveform spike sit under the playhead when you hear it | yes — this is the whole point of the feature |

### 3.3 Caption layers

| # | Check | PASS looks like |
|---|---|---|
| 14 | **Open a project saved BEFORE this change** | captions render exactly as before, same place, same style. **Do this first — a project that loses its captions is a total failure** |
| 15 | Save in the new build, reopen in it | still correct |
| 16 | Three tracks on one clip, three styles, three positions | all three visible, non-overlapping |
| 17 | Tap track 1 in the preview, then track 3 | caption drawer AND transcript drawer both follow the last-touched one |
| 18 | With track 1 active, drag over track 2 | track 1 moves, track 2 does not |
| 19 | Different `FitMode` per track | each fits independently; track 2 does not inherit track 1's size |
| 20 | Export 15s with all three, compare a frame to the preview at the same time | identical text, size, position |

### 3.4 Image presets (`1bc9a273`)

| # | Check | PASS looks like |
|---|---|---|
| 21 | **Open a pre-change project** | identical rendering, no amber anywhere |
| 22 | Fit and Fill, on a tall image and a wide image | 4 screenshots, wallpaper-style behaviour |
| 23 | Apply `ZOOM_IN` | two amber keys at the ends |
| 24 | Drag the item's out point | the amber key stays glued to the new end |
| 25 | Change zoom in the PREVIEW | keys stay amber (does not convert) |
| 26 | Drag an amber key in the TIMELINE | all keys turn ordinary in ONE step |
| 27 | Then press undo **once** | amber AND the preset kind both return. **Most likely to be half-right** |
| 28 | `PAN_RIGHT` on an image barely wider than canvas; step through | no frame shows background. Say how many positions you checked |
| 29 | `PAN_*` on a square image, square canvas | a message, not a dead animation |
| 30 | Preset animation: export 10s, compare a frame to preview | identical |
| 31 | Preset opacity animation + a dragged fade handle | the fade multiplies, does not replace |

### 3.5 Preview performance (only if that lane reports landed)

| # | Check | PASS looks like |
|---|---|---|
| 32 | Animated title under a blended image, before/after | it appears in the composite at its real z |
| 33 | A text item whose content is time-dependent (counter/timecode) | **still counts.** The tempting wrong fix freezes it |
| 34 | 3-minute play with several animated overlays; `dumpsys meminfo` before/after | no monotonic climb |

### 3.6 Carried over from 2026-08-28 — never looked at

| # | Check |
|---|---|
| 35 | Opacity keyframe delete + on-key dot + amber indicator (`6ac64156`) |
| 36 | Caption font `+ Import` chip is reachable (`90d77721`) |
| 37 | Caption Fit tab: OFF / UNIFORM / PER_CUE, floor, max-lines (`a9c67386`) |
| 38 | Export GL frames: **before/after export timing + PSNR**. Baseline to beat: **1m38s** for the 46s project at 720p/Low. `am force-stop com.fadcam.beta:export` first — export runs in its own process and survives restarts, so without this you time old code |
| 39 | Transcript source affordance: header name, `+ Source` chip, one-time offer (`60919802`) |
| 40 | Horizontal reflow on a PORTRAIT canvas — the one reflow case never seen |
| 41 | The ~1:03 playback ceiling: play past 63s on a long project. Probably gone; confirm or reproduce |

---

## 4. Output

Write `tasks/VERIFY_20260829_RESULTS.md`:

- The build tested (APK mtime + `lastUpdateTime`) and `adb devices` output
- A table: check #, PASS/FAIL/BLOCKED, screenshot filename, one sentence if not PASS
- A **short list of the FAILs ranked by how much they would annoy JoyRaptor**, which is the
  section that decides what gets fixed next

`git add` it the moment you create it, and again as you fill it in — this repo has
destroyed uncommitted work three times.

**A sweep that honestly reports 25 PASS, 10 FAIL and 6 BLOCKED is a complete success.**
A sweep that reports 41 PASS is not believable and will be checked line by line.
