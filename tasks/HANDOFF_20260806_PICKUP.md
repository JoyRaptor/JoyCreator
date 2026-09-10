# PICKUP HANDOFF — 2026-08-06

> # ⚠ SUPERSEDED — read `tasks/START_HERE_20260806b.md` instead.
> Kept for its §3, which is still the best reference for the OpenRouter API (capability
> fields, the free-vs-auto router distinction, the multimodal request shape). Everything
> else here is out of date: the vision feature it calls "next" is BUILT and
> device-verified, and the todo lists have been recounted against the code since.



**Read this first, then `HANDOFF_20260806_IMAGE_SEQUENCES.md` for detail.**

Branch `joy-creator`. HEAD `ed206a8`. Tree clean, all harnesses green, APK installed and
timestamp-verified. 13 commits this session, `1e907ca` → `ed206a8`.

JoyRaptor is not a developer. Make ENGINEERING calls yourself; bring him PRODUCT/UX decisions only.

---

## 0. THE THING THAT UNBLOCKS YOU — read before anything else

**You can build and install from inside the agent.** Runbook §7f used to say you couldn't.

```bash
bash tools/build-install.sh          # build + install to the sandbox phone, prints APK freshness
bash tools/jvm-harness/typecheck.sh  # whole-app javac, ~1 min, no Gradle
```

`Unable to establish loopback connection` was a red herring that cost three sessions. Loopback is
fine. The real failure is `Selector.open()` on JDK 17/Windows building its wakeup pipe from an
**AF_UNIX socket**, which fails when `java.io.tmpdir` is an 8.3 short path
(`C:\Users\JOYRAP~1\...`). One property fixes it, and it must be `JAVA_TOOL_OPTIONS` (not
`GRADLE_OPTS`) because the **daemon** needs it too:

```bash
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"
```

⚠️ **ALWAYS check the APK timestamp against the wall clock.** JoyRaptor's watcher died silently
mid-session while `build.log` kept showing an old `BUILD SUCCESSFUL`; ~20 minutes of device
testing ran against stale bits and a working feature looked broken. `build-install.sh` prints
both every run. Do not trust `build.log`.

### Harnesses (all green at HEAD)
```bash
bash tools/jvm-harness/run-sequence.sh   # 100/100 timing+tape, 50/50 import
bash tools/jvm-harness/run-key.sh        # chroma, volume, span, MaskAnimator 22/22
bash tools/jvm-harness/run-matte.sh      # run-anchor.sh (35), run-promote.sh (24)
```

### Device
- Sandbox is **Note 9 `<note9-serial>` ONLY**. If Note 20 `<note20-serial>` is attached, STOP —
  it holds JoyRaptor's real project. `build-install.sh` refuses to run when it sees it.
- `am start -n com.fadcam.beta/com.fadcam.SplashActivity`, **never `monkey`** (it silently
  unlocks rotation).
- Screenshots: `adb exec-out screencap -p > f.png` **from the Bash tool** — PowerShell
  redirection corrupts binaries. Same for pulling files: `adb exec-out run-as ... cat > f.png`.
- Screen is **1080×2220** on this device (the runbook's 1440×2960 is wrong).
- Back up `project.json` with `run-as cp`, verify with `md5sum`. Both borrowed projects were
  restored byte-exact: `302da9ac` = `aac2ba5c`, `aeb0517e` = `f764e002`.
- **Fixture left in place on purpose: project `98e303a6`** ("Faditor_20260805_200116") — one clip
  + a 12-frame image sequence. Sprites tool → ▦ chip → dope sheet in two taps.
- Test frames: `/sdcard/Download/seqtest/frame_001..012.png` (numbered coloured discs).

---

## 1. SPEC_IMAGE_SEQUENCE — DONE. What is verified vs merely built.

Every section is implemented. The split below is what matters.

### Device-verified on real pixels
- Import: folder detection ("We found 12 images"), `1/6 min` parsed live → 1.2 fps, 12 frames
  imported, composites on video, survives a full app restart, advances during playback.
- Tape (§4): uniform thumbnails at each change, hairline at every change, lane colour through
  the gaps, thumbs skipped (not shrunk) when they would collide.
- Dope sheet (§5): "On twos" → 6.00s → 12.00s with ×2 badges, one undo step.
- **§5b vertical drag**: frame 3 ×2 → ×5, others untouched, total 12.00s → 13.50s — exactly
  (11×2 + 5) ÷ 2fps.
- **§5c.7 shuffle preserves weights**: the ×5 authored on `frame_003` ended at strip position 11,
  and position 11 held doc id `127416` = `frame_003`. The weight travelled with its frame.
- §6: `continues: true` persisted with `endMs: 13726` — a concrete number equal to the project
  end, never `MAX_VALUE`.
- §2a mode toggle persisted (`ABSOLUTE`), §3d convert packed 12 frames into a 4×3 grid PNG with
  the weights carried onto the new sheet's preset.
- Undo/redo across weight edits.
- **§8 PREVIEW == EXPORT, the load-bearing proof.** Exported the fixture, pulled the mp4,
  extracted frames with ffmpeg at 0.5 / 1.5 / 4.5 / 8.5 / 11.5 / 12.5s and sampled centre
  pixels. Every timestamp showed EXACTLY the frame the weight model predicts (0, 1, 4, 8, 11),
  and 12.5s — past the 12s end — drew nothing.

### Built + type-checked + installed, but NOT exercised on device
Do these first; they are cheap and they are where a regression would hide.

1. **§2a ABSOLUTE left-trim** (`sequenceStartFrame`) — drag the LEFT edge of a sequence in
   ABSOLUTE mode; frames should come off the FRONT, and dragging back out should restore them.
   Harness-pinned (`startFrameOffsetsTheRun`, `startFrameOffsetsTheTapeIdentically`).
2. **§9c live readout** — `24 frames · 4.0s · 6.0 fps` while dragging an edge. Needs a
   mid-gesture screenshot: run `adb shell input draganddrop x1 y1 x2 y2 3000` in the BACKGROUND,
   sleep ~1.5s, screencap.
3. **Copy-on-write** — place ONE sequence twice, resize one, confirm the other is untouched and
   that a new sheet appeared in the manager named e.g. "frames 2".
4. **§8 MISSING placeholder** — rename a frame file on device, confirm the dashed amber slash
   draws and the manager says "1 of 12 frames missing".
5. **§7 AI tools** — `describe_sequence` / `edit_sequence`. **JoyRaptor has now added an OpenRouter
   key**, so this is finally testable. Ask the assistant "what image sequences are in this
   project" then "make every 6th frame hold for 5".
6. **The mask tab** — `PipDrawerTabs.maskTab` gained "Move with the object" + "◆ Key at
   playhead". Select a PiP → its drawer → Mask tab → toggle → confirm `"link": true` and
   `linkBase*` in project.json. I could not reach the PiP's menu by coordinate tapping; its lane
   row stayed collapsed and it overlaps an image overlay in the preview. Fingers will find it.

### Known-open, low severity
- `SequenceFrameCache`'s 24MB budget is **per cache**, and there are up to 3 caches per sheet
  (editor / timeline / export). Three sequences with the timeline open = 144MB ceiling.
- `SequenceDetector.MAX_OFFERED` is 600 frames.

---

## 2. DECISIONS JOYRAPTOR HAS MADE (do not re-litigate)

- **Copy-on-write, option A** — sheet-scoped edits (fps, loop type, resize mode, weights) clone
  the sheet when it backs more than one placed object. BUILT (`sheetForExclusiveEdit`).
- **Instanced-vs-independent objects: NOT building it.** JoyRaptor floated twin badges / link states
  / a settings default and then talked himself out of it — correctly. Loop already covers the
  main "many copies of one thing" case, and a link mode is exactly the kind of invisible state
  SPEC_IMAGE_SEQUENCE §3b warns against. **If the need resurfaces, the cheap version is ONE chip
  in the drawer — "Apply to all 3 copies" — shown only when copies exist.** Explicit, named, no
  mode, no default to configure.
- **Duplicate button** (wanted, not built): duplicate **onto its own lane at the same time
  position**. Cannot overlap, needs no push-through, matches the existing no-overlap invariant.
  Duplicating "after" silently makes a timing decision for the user.

---

## 3. NEXT FEATURE — OpenRouter vision. Fully researched, ready to build.

JoyRaptor explicitly asked for this and it is the next thing to do. **Verdict: build it.** An AI that
can look at the composition can answer "is this text readable over that background", "which take
is in focus", "label these 16 sprite cells".

### What the research established (all verified against the live API)
- **Capability detection is one field.** `GET https://openrouter.ai/api/v1/models` →
  `data[].architecture.input_modalities` is a string array, e.g. `["text","image","video"]`.
  Server-side filtering works: `?input_modalities=image` returned 182 of 340 models (verified,
  zero false positives), `?input_modalities=video` → 50, `?input_modalities=audio` → 26. **Fetch
  the filtered list, not the 534KB catalogue.**
- **The `:free` question, answered.** Three distinct things:
  - `openrouter/free` — "Free Models Router", `pricing.prompt: "0"`. Almost certainly what JoyRaptor
    means. Its own `input_modalities` is **`["text","image"]` — image yes, video/audio NO.**
  - `openrouter/auto` — "Auto Router", **paid** (`pricing.prompt: "-1"`).
  - `:free` **suffix** on concrete slugs (14 exist) — not routed, slug known, capability directly
    readable.
- ⚠️ **`ChatAssistantActivity.DEFAULT_MODEL` is `openrouter/auto` — the PAID router.** Check what
  is actually in the `ai_model` pref before assuming JoyRaptor is on free.
- **Routing pre-filters by content**: the free router "intelligently filters for models that
  support the features your request needs, such as image understanding". So attaching an image is
  safe — prediction is for the UI affordance, not for correctness.
- **The response says which model served it** (top-level `model` field), so the eye can go from
  *predicted* to *confirmed* after any call.
- **Mismatch behaviour is UNDOCUMENTED.** Do not architect around it. Attempt, catch non-2xx,
  fall back to text with an explanatory bubble.
- **Request shape is already correct in this app.** `content: [{type:"text"...},
  {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}]`. Base64 data URLs are
  explicitly supported. **Send the text part BEFORE the image** (documented parsing order). No
  documented byte limit.

### Most of the plumbing already exists — this is wiring, not new work
- `AIToolExecutor.callOpenRouterVision` (~line 1231) already builds the correct multimodal
  request.
- `AIToolExecutor.assetThumbnailBase64` (~line 1191) already pulls a video frame via
  `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST_SYNC)`, scales to 512px, JPEG-70.
- `ChatAssistantActivity.sendVisionMessage` (~line 482) already sends picked images.

### Build order
1. **The eye indicator.** Fetch `?input_modalities=image` once, cache the id set with a TTL, light
   an eye in `ChatAssistantActivity.updateModelLabel()` (~line 1583) when the configured slug is
   in it. Special-case the two routers by reading their OWN `input_modalities` (both include
   image, so the eye is honestly on). Decide what shows before the first fetch lands — suggest
   hidden, not lit. Upgrade to "confirmed" from the response's `model` field after a call.
2. **Send a frame from the playhead.** Extract with the existing recipe, reuse
   `callOpenRouterVision`. The only design question is whether the agent or the user picks the
   timestamp.
3. **Audio/video**: scope OUT unless JoyRaptor will pin a slug or pay. Free router is text+image only.

### ⚠️ Latent bug to fix while you are in there
`sendVisionMessage` reads `inputField.getText()` from a **background thread** (~line 497), and
pushes the full base64 data URL into the static `conversationHistory` (capped at 200 entries) —
so every subsequent turn re-sends megabytes. That is a cost and memory problem that gets much
worse once frame-sending is routine rather than rare.

---

## 4. THE STALENESS AUDIT — orphans and gaps

A full trace of every animation doc against the code. **Four docs are accurate as written**
(`PLAN_LOOP_PINGPONG`, `PLAN_PINGPONG_UNPARK`, `SPEC_IMAGE_SEQUENCE`, `SPEC_VIZ_ENGINE`, plus
`PLAN_A5_AI_RIGGING`). The rest had drift in BOTH directions.

### Already fixed this session (they were live bugs, not staleness)
- AI was told to call `author_sprite_animation`, which does not exist → dispatch would hard-fail.
- `get_project_state` emitted no sprites/sequences/rigs, so the model could not learn an
  `objectId` it needs. Now emits sheets, placed objects with ids and spans, and rigs.
- The dope sheet had **no button** — reachable only by an undocumented double-drag. Now a ▦ chip.
- The A3 spectral viseme chain was inert: `AvatarRig.visemeMap` had NO writer anywhere, so five
  classes analysed speech into classes that drove nothing. Empty map now means "natural order".
- Missing sequence frames drew invisibly; now a dashed amber MISSING placeholder + "12 of 240
  frames missing" in the manager.

### STILL ORPHANED — built but unreachable (ranked by stranded work)
| What | Note |
|---|---|
| **`MaskKeyPanel`** (466 lines) | Dead since the 2026-08-05 PiP-drawer redesign; `showMaskDialog()` has zero callers. Left in place deliberately — **ask JoyRaptor whether to delete.** Its features now live in `PipDrawerTabs.maskTab`. |
| **`AvatarRigTemplates.bipedTemplate()` / `templateJson()`** | Only callers are the JVM harness. No "start from a biped" flow exists, and `AIToolExecutor` hand-writes its own rig shape instead of using `templateJson()`. Avatar Studio hand-builds a 1-part rig. |
| `ObjectTimeScrubSession.currentLane()` / `currentStart()` | No callers — the §8 wiring took a shortcut around the designed contract. |
| `SequenceImportDialog.showFor()` | Dead overload. |
| `FrameTrack.resort()`, `SpriteSheet.getOrder()`, `SequenceFrameCache.residentLimit()`, `SequencePacker.cellRect()`, `TimeShuttleView.setMaxMsPerSec()`, `DangleSim.isPrimed()`, `TrackingDriverBus.latestTSeconds()` | Declaration-only. `getOrder()` means the spec'd `order: "row-major"` field is stored, serialised and never read. |

### STILL MISSING — claimed or planned but absent
| What | Source |
|---|---|
| `set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation`, `apply_sprite_proposal` | PLAN_SPRITE_ANIMATION FF-B |
| Palette per-track arm toggles (Pos/Scale/Rot/Opacity) | S3 — arming exists, but in `ObjectMenuSheet`, not the palette |
| Dope-sheet **transform** rows + per-key easing | FF-A — `DopeSheetView` is frames-only; the capability lives in `ObjectMenuSheet` |
| `sw600dp` two-pane setup editor / dope-sheet-below-timeline | S2b + "Tablets" — **no `values-sw600dp` resource dir exists at all** |
| Time scrubber for sprite / audio / PiP | SPEC_OBJECT_TIME_SCRUBBER §12 — `setTimeScrub` has one call site, text only |
| Cross-lane vertical y-GLIDE + push-through relayer | SPEC_OBJECT_TIME_SCRUBBER §9 |
| Retrigger-on-value-change (timer tick pop) | SPEC_TEXT_ANIMATION |
| Per-item `anchorX/anchorY`, `parentItemId` sprite parenting | PLAN_SPRITE_ANIMATION "RIG VISION" — correctly deferred |

### Docs whose STATUS LINES are wrong (fix the doc, the code is fine)
- `PLAN_SPRITE_ANIMATION` "REMAINING": S7 relink, S2b auto-detect/bg-key/onion/sidecar are all
  **built and reachable**.
- `PLAN_AVATAR_STUDIO:220` A5 marked `[ ]` — **A5 is built and reachable** (see `PLAN_A5`).
- `PLAN_AVATAR_STUDIO:172` A3 "spectral tier open" — built (and now un-stranded).
- `SPEC_TEXT_ANIMATION` "tape carets PARKED, no caller" — revived and live under new names
  (`hitTestTextAnimHandle` etc.). "six presets implemented" — there are **eleven**.
- `SPEC_OBJECT_TIME_SCRUBBER` §7 "EDITOR WIRING (NOT built)" — wired for text overlays;
  "'End here' does NOTHING" — fixed.

---

## 5. SUGGESTED ORDER FOR THE NEXT SESSION

1. **Device-verify §1's six unverified items** (~1 hour). Cheapest possible regression catch.
2. **Build OpenRouter vision** (§3) — JoyRaptor asked for it directly; research is done; plumbing
   exists.
3. **Fix the stale doc status lines** (§4 last block) — pure documentation, prevents the next
   session repeating this audit.
4. **Ask JoyRaptor about `MaskKeyPanel`** — delete or revive.
5. Then the MISSING list by whatever JoyRaptor wants next.

## 6. WORKING NOTES EARNED THIS SESSION

- **Ask "who calls this?" before believing anything is built.** Two orphans were found by grep
  where the only hit was the declaration. It is the single highest-yield check in this repo.
- **Adversarial self-review pays.** A read-only review agent over the session diff found 13
  issues, 8 real, including two that made headline gestures silently inert.
- The dope sheet rebuild runs from its own `ACTION_UP`, so it must be **posted**, not inline, or
  the view detaches mid-dispatch.
- Binary through `adb shell` is mangled on Windows; use `exec-out`.
- Tapping outside the sprite palette collapses it — a mis-aimed tap eats the gesture you meant.
