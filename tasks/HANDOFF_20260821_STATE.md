# HANDOFF — 2026-08-21, end of session

Written because context is nearly full. Everything open, with enough detail to resume cold.
Owner: JoyRaptor. He is not a programmer — explain in end-user terms, make the engineering calls
yourself, and never report something as working that you have not verified.

---

## 0. RULES THAT COST US THIS SESSION — read before touching the device

1. Never drive synthetic taps or swipes over the video preview area of JoyRaptor's phone.
   I used `input swipe ... y=836` believing it was the minimap. On his 1440x3088 Note 20,
   y=836 is INSIDE THE PREVIEW, so four swipes dragged his overlays and, with the playhead
   at 0, wrote keyframes at t=0. It damaged five objects in his real project. Recovered only
   because I happened to have a project.json snapshot from earlier.
   - Verify coordinates against a FRESH screenshot before every tap.
   - Prefer non-touch routes. The landscape test was done by writing `user_rotation` and
     restoring it afterwards, and that was the right call.
   - Snapshot project.json before any device session that could write.

2. Gradle: use `--no-configuration-cache`. The config cache intermittently fails with
   "AnalyticsEnabledValueSource$Params not found". A failed build still lets a stale APK
   install and look successful. Always check build exit=0 before adb install.

3. A comment asserting a call is not a call. Two bugs this session hid behind comments that
   described the opposite of the code. Grep for the CALL, not the prose.

---

## 1. NEXT UP — masks on image overlays (ruling #1, decided: build it properly)

THE GAP. An image carrying ONLY a mask never leaves the Canvas path, so its mask is
invisible while editing. It still exports correctly. An image with a blend/FX/key DOES get
masked in the editor, because those route it to GL where the Pip carries the mask spec —
which is why JoyRaptor saw masks start working once he set a blend to Add.

THE PREDICATE. `TextOverlayItem.wantsGlExport` is
`wantsExportBlend() || hasExportFx() || hasExportKey()`. Mask is deliberately absent.

DO NOT add mask to that predicate. GL-composited images draw at the GL layer's z, not their
lane's — that re-creates the z-order bug fixed 2026-08-19 and confirmed by JoyRaptor. The
one-line fix is the wrong fix.

DO THIS INSTEAD: mask on the Canvas path, where the image is already drawn.
- Images render as ImageView children of TextOverlayLayer (buildView, around line 820).
- The export masks images through MaskPathBuilder in ImageOverlayDraw (around line 177).
  Use the SAME authority so preview and export cannot drift. OverlayVideoPreviewView
  (around 1044) is the PiP precedent: beginMask / endMask around the draw.
- Likely shape: a small ImageView subclass overriding onDraw to open a MaskScope, draw, then
  close it. Contained, keeps z-order, keeps gestures.
- Watch: mask coordinates are in FRAME space (beginMask is opened with the output frame
  size) while the view works in view space — see the note on Pip.ofImage.
- Verify numerically (two renders, one setting changed, compared pixel-wise), not by eye.

---

## 2. Open bugs

- 2a text uneditable after an animation preset. Two real bugs were fixed underneath it
  (None-clears-zones, then the live/imeActive leak). The ORIGINAL symptom was never verified
  fixed. Ask JoyRaptor to apply an animation and try to edit the text.
- 2b text not rendering until an empty adjustment layer was deleted. No repro. Ask: was the
  empty layer BELOW the text, and did text return instantly or only after a scrub?
- 2c does audio extend the EXPORT? Model says yes (getTotalDurationMs is max(video,
  audioEnd)). Output never checked. Export a project with audio past the last clip and
  compare the length.
- Dalvik heap around 226 MB. After the image fix this is the largest remaining block and is
  unexplained. Next performance target — measure before guessing.

---

## 3. Rulings still needed

Full text in tasks/RULINGS_NEEDED.md. ANSWERED: 1 (build robustly), 2 (keep the blur
change), 5 (done — None now preserves timing), 6, 7, 8 (defer), 9, 11.

STILL OPEN:
- 3. The 4m16s of black auto-appended to "first lecture on phone". Keep or remove?
- 4. Should overhanging objects ALWAYS pull black in behind them? Currently yes.
- 10. The two repros above (2b and 2c).

9, audio in any lane — ANSWERED: yes to any lane, no to the video spine. Audio needs no
picture under it, so free placement costs nothing. But the spine is the coordinate system —
every ripple, anchor and snap is defined against "what frame is here" — so a spine clip with
no frame forces a new answer everywhere. JoyRaptor has another agent writing an audio spec.

---

## 4. Built this session, NOT yet confirmed by JoyRaptor

All typechecked, built and installed. The listed check is what is missing.

- Blend/mask apply LIVE (previously only on the next scrub). Change one and watch it land
  without touching the playhead.
- Image overlay memory: native heap 726 -> 152 MB, PSS 1206 -> 631 MB. Measured on his
  project; two full scrubs showed zero dropped frames. He has not used it in anger.
- Landscape PiP restored — device-verified by me (PROMOTED/DEMOTED logs plus screenshot).
- "None" keeps animation timing — harness only (24 assertions), not exercised on device.
- Motion range clamped to its object's span (was surviving 7.9s past the object's end).
- Amber entrance/exit zone buttons — these never existed; four triangles, one trio of
  buttons. Untested by JoyRaptor.
- Long-press any Start/Span/End chip to type an exact time (2m30s, 90f, 1/6 min).
- Drawer retargets to whatever object is selected while it is open.
- Black clip insert in the Add sheet, and stills stretchable past 5s.

---

## 5. Not started

- SPEC_IMAGE_SEQUENCE 3b: three linked fields (frame rate / duration per image / total) that
  all write one model and update each other live. promptForTimeMs is the single-value
  version and the right building block.
- 4.7 one timeline-to-source authority. FOUR pieces of code answer "what point in what clip
  is this moment", and an audit found they already disagree. Highest-value structural fix
  left. JoyRaptor PARKED it until this video ships. When it happens: harness tests pinning
  current behaviour FIRST, then the merge.
- 4.2 seam jump and 4.3 load flicker — need device sessions with logcat.
- Audio editor: blank projects, audio lanes, mini-map audio colour.
- fMP4 seek subsystem port from upstream (FragmentedMp4IndexBuilder plus hybrid
  finalization, around 2,300 lines over 8 files). Would make filmstrip extraction fast on
  FadCam recordings and retire the ffmpeg remux. Correctly deferred; a fresh-session job.

---

## 6. Environment

- Harness: 6 runners, 131 checks, all green — run-anchor 24, run-matte 25, run-fx 8,
  run-caption 9, run-flextime 42, run-motionrange 24. run-anchor had been failing to COMPILE
  for some time (missing getAssets() in the Context stub); a test that never runs reads as
  coverage. Check that runners actually run.
- JoyRaptor's Note 20 is REAL_SERIAL; the sandbox Note 9 is SANDBOX_SERIAL and holds the
  FXPROOF-* fixtures built for image FX/blend verification — use those, not his real project.
- Backup of his project: project.json.bak_20260821 on the device. The local copy lives in a
  temp scratchpad and WILL NOT SURVIVE; re-snapshot before device work.

---

## 7. ADDENDUM — 2026-08-22, after the handoff was first written

Installed on the Note 20 at 07:45 (`lastUpdateTime=2026-08-22 07:45:24`) and re-measured on
JoyRaptor's project: native heap 143 MB, PSS 628 MB, no crashes. The zoom-aware decode below did
NOT cost memory — most images are not zoomed, and the LRU bounds the rest.

### Also built, also unconfirmed by JoyRaptor

- **Imports are content-addressed.** Adding the same picture twelve times used to write
  twelve byte-identical files named `asset_<millis>_<original>`, each with its own URI, so
  everything downstream decoded it twelve times. Now the SHA-256 is computed during the copy
  that was already happening and an existing file is reused. One file, one decode, one
  bitmap, however many times it is placed.
- **Preview decode follows zoom.** The flat 1080p-class cap I added on 2026-08-21 would have
  made JoyRaptor's core workflow soft — "very large high rez images showing a detailed chart and
  zoom around it... i want to make sure those zooms are crisp and sharp." The bound now reads
  the item's maximum SCALE keyframe (the track IS the set of extremes) and decodes to match,
  ceilinged at 4096. Export was never affected; it decodes at the OUTPUT frame size.
- **The animation row dims to 45%** when the preset is None, so kept-but-inert timings cannot
  read as a live animation. Dimmed rather than hidden on purpose: hiding would make the
  timings feel deleted and would remove the ability to adjust them while auditioning.

### Two things this surfaced that are NOT fixed

1. **Assets imported before content-addressing are still duplicated.** The dedupe only
   applies to NEW imports. Demonstrated on JoyRaptor's own device: `files/images` holds
   `asset_1786773518189_msf:1000118765` and `asset_1786792958262_msf:1000118765` — the same
   picture, same 172857 bytes, imported twice under the old naming. A one-time migration
   (hash every file in the assets dir, rewrite project URIs that point at duplicates to a
   single canonical copy, delete the rest) would reclaim this for existing projects. Needs
   care: it rewrites project files, so snapshot first and do it off the main thread.

2. **`copyUriToInternalStorage` SKIPS anything over 10 MB** and returns the original
   `content://` URI. That matters more than it looks for JoyRaptor's workflow, because his
   "very large high rez" charts are exactly the files over that threshold:
   - they are never copied locally, so they are **not** deduped by the new hashing, and
   - their survival depends on the persistable URI permission rather than on a local copy,
     which is the weaker guarantee.
   The 10 MB limit exists to avoid an ANR from copying on the main thread. The right fix is
   almost certainly to copy large files OFF the main thread with progress, rather than to
   skip them — but that is a decision, not a detail, so it is written down rather than done.

### Motion blur — assessed, not started (JoyRaptor's brainstorm, 2026-08-22)

- **Velocity-based directional blur is the practical version.** Keyframed positions already
  give velocity, the FX shader chain already exists, and overlays/PiPs already composite
  through GL. It is one more shader pass with velocity as a uniform, engaged only while
  something is actually moving. This is the one to build if he wants it.
- **True accumulation blur (render N sub-frames and average) is not realistic for live
  preview** on a phone — it multiplies render cost by the sample count. It could be offered
  as an export-only quality option.
- **Honest limitation to tell him again if it comes up:** his signature move is ZOOMING,
  which is a scale change, not a translation. Directional blur does very little for a zoom;
  radial/zoom blur is a different shader and a separate decision.
- Gate any of it behind the existing `onFxShaderUnavailable` path so a device that cannot
  compile the shader degrades to no blur rather than a broken preview.

---

## 8. JOYRAPTOR'S DECISIONS — 2026-08-22 (these are settled; do not re-ask)

### 8.1 Large media: ASK, then remember (his design, accepted)

Above the size threshold, prompt: *"This file is bigger than X — move it into your project,
or just link to its current location?"* Plus:
- a **"don't ask again, remember my choice"** checkbox,
- the same choice exposed in **editor settings** so it can be changed later,
- a **"migrate all large media links into project"** button in settings.

He named the real trade himself: linking means he can keep editing the chart externally and
have the project pick up his changes; copying means it cannot break. Both are legitimate,
which is exactly why this should be a question rather than a hardcoded 10 MB cutoff.

TWO THINGS THE DESIGN STILL NEEDS (raised with him, not yet ruled on):
- **Live-update is not automatic today.** Decoded bitmaps are cached and filmstrips are
  baked, so an externally edited chart will not refresh until those are invalidated. If
  "edit the chart and see it update" is the point of linking, the link path must check the
  source's last-modified time and re-decode when it changes. Otherwise the advantage he is
  choosing linking FOR does not actually materialise.
- **A broken link needs a way back.** Linked files can be moved, renamed, or lose their URI
  permission. Without a relink flow a broken link is a dead end. `showMissingOverlay` is the
  existing precedent for missing sources — extend that to offer "find this file again"
  rather than only reporting the loss.

Implementation note: the copy itself must move OFF the main thread with progress. The 10 MB
skip exists only to avoid an ANR, and that constraint disappears once copying is async.

### 8.2 Keep the auto-added black tail (ruling 3 = A)

The 4m16s stays in "first lecture on phone".

He attached a NEW feature to this decision: **export a range.**
- A checkbox in the export flow; ticking it reveals a **minimap with a selection bracket**
  (start and end handles).
- Tapping either handle opens the **shared time-entry dialog** so exact times can be typed —
  explicitly reusing `promptForTimeMs` and the SPEC 3c grammar (`2m30s`, `90f`, `1/6 min`).
- This is the natural companion to keeping the black tail: keep the project honest, and let
  the export decide what part of it ships.

### 8.3 Auto-extend stays automatic (ruling 4 = A)

Objects hanging past the end always pull black in behind them. Silent non-export is the
worse surprise.

### 8.4 Run the duplicate-asset cleanup (ruling 4/housekeeping = A)

Do the one-time migration that hashes existing assets, points project URIs at a single
canonical copy and deletes the rest. **Not while he is mid-edit** — snapshot the project
first, run it off the main thread, and treat it as a deliberate job.

### 8.5 The two observations (ruling 10) — still owed, deferred by him

"I'll need to look at later." Do not block on these, but do not lose them:
- Was the empty adjustment layer BELOW the text, and did text return instantly or only after
  a scrub?
- Export a project whose audio runs past the last video clip and compare the file's length.
- Also still unverified: whether a text box is editable again after applying an animation —
  his ORIGINAL 2026-08-15 complaint, with two bugs fixed underneath it and the symptom never
  re-checked.
