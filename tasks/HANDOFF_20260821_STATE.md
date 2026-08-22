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
