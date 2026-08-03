FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch `joy-creator`.

**Supersedes NEXT_SESSION_PROMPT_20260731c.md.** Read `tasks/LEDGER.md` FIRST. Never delete an
entry to shorten it — strike through and correct in place.

**JoyRaptor is not a developer and has said so explicitly (2026-08-03): he is trusting the agent to
apply best practice.** So: make the ENGINEERING calls yourself and stop asking him technical
questions. Still bring him PRODUCT/UX decisions — those are his and guessing wastes his time.
And raise the bar on proof rather than lowering it: he cannot check the work, which is a reason
to be more rigorous, not less.

---

## READ THESE THREE, IN THIS ORDER
1. `tasks/PLAN_TIMELINE_MANIPULATION_V1.md` — the umbrella. §2 is the point: shared primitives.
2. `tasks/PLAN_LAYERS_UX_ADDENDUM.md` §3A (M12 spine drag) + §4A (anchoring). **§4A is DRAFT 2**,
   rewritten after an adversarial audit found **18 confirmed breakages** in draft 1. Every one
   carries its `file:line`. Do not re-litigate these decisions.
3. `LEDGER.md` §2d, §2e, §3i.

## WHAT CLOSED THIS SESSION

- **§2d PROVED ON DEVICE — overlays drift in export whenever a transition exists.** Prediction
  frozen in a commit BEFORE the capture; PiP onset measured at **5.50s** against 5501 authored,
  where correct is 4901. Flat baseline through 4.90. The control discriminated.
- **§2e FOUND — an image clip on the master spine kills the export** ("asset loader has no audio
  or video track to output"), leaving a plausible partial MP4 behind. Leading suspect: the literal
  colon in the asset filename. UNPROVEN; the settling test is written in the ledger.
- **Anchoring core BUILT and PROVED off-device.** `AnchorMath` (39 checks) + `AnchorShiftTest`
  (24 checks against the REAL model classes). `Timeline.captureClipStarts` /
  `applyAnchorShift` / `attachOverlayToHostUnderStart`. Persisted sparsely on `TextOverlayItem`.
- **The three-audit sweep**: access points (19 doorless features), master-edit call-site map,
  adversarial review. All three results are summarised in the ledger / task list.

## ⚠ FIRST JOB: WIRE `applyAnchorShift` INTO THE EDIT PATHS. The engine works; nothing calls it.

The shape is forced, not chosen — **there is no clip start FIELD** (a start is a prefix sum), so
it must be `before = captureClipStarts(); …mutate…; applyAnchorShift(before);` at each **USER-ACTION
boundary**. Not per primitive: one split is `remove + add + add`, so a per-primitive hook fires on
a half-mutated list and shifts a rider two or three times.

**~49 sites** (full table in the call-site audit, reproduced in §4A's notes): ~26 in
`FaditorEditorActivity`, 11 `EditActions` classes (all reachable through the single chokepoint
`UndoManager.undo()/redo()`), ~12 AI paths in `EditScriptApplier`/`AIToolExecutor`.

**The four that WILL be missed if you are not deliberate:**
1. `autoRelinkSiblings` (:3266) — no undo action at all, silent, changes durations.
2. `resolveTransitionSeamAtPlayhead` (:21402) — a hidden `splitAt` inside a *transition* op, no undo.
3. `correctDurationFromPlayer` (:7989) — fires automatically on load, no undo.
4. `EditScriptApplier.applyReorderClips` (:866) — **clears and rebuilds the whole list**, so an
   index-based hook breaks. `captureClipStarts` is ID-keyed for exactly this reason.

**BUILD THE DEBUG INVARIANT PROBE ALONGSIDE IT** — with 49 sites, "we got them all" is not
checkable by reading. On save/load in debug builds, assert each anchored rider's offset still
matches its host's actual start and log when it does not, in the spirit of the existing
`SEEKRANGE`/`PHDIAG` probes. A missed site then becomes discoverable within a session instead of
invisible until someone's export is wrong.

## THEN, IN ORDER

1. **Split and gap-delete must RE-ANCHOR inside `Timeline`** — split mints fresh UUIDs on BOTH
   halves (`Clip.java:486`), in TWO independent implementations (`Timeline.splitAt` and
   `EditScriptApplier:755`), and gap-delete's black spacer is a `new Clip` with a fresh id. All
   three currently orphan every anchor on the clip.
2. **The orphan prompt** (§4A) — tri-state pref (`ask`/`always re-anchor`/`always delete`); needs a
   NEW row helper (the sheet only has `addSwitchRow`, a boolean); must MERGE with the existing
   `confirmDeleteLinkedPair` dialog rather than stack a second modal.
3. **Anchor the other rider types** — sprites, PiP overlay clips, audio. Only `TextOverlayItem`
   carries the fields today.
4. **§2d fix** (task #9) and **§2e fix** (task #10) — both affect the user's real exports NOW.
5. M12 model ops, then the gesture. §3A.
6. The access-point backlog (task #8). Cheapest-first: transition shader params (the catalog
   already ships name/default/min/max), then the orphaned `ui/faditor/text/` style subsystem
   (`TextStyleLibraryPanel` is never instantiated), then text stroke/glow/shadow/background
   (already rendered in BOTH preview and export, no UI).

## HOW TO WORK HERE — rules each paid for in a bug

Carry forward every rule from `NEXT_SESSION_PROMPT_20260731c.md` §"HOW TO WORK HERE". New ones
earned this session:

- **A green harness can still be measuring a no-op.** Two trim checks passed against a fixture
  whose `setOutPointMs` was silently CLAMPED by `sourceDurationMs`, so the "trim" never happened.
  Build fixtures with headroom and assert the setup took effect.
- **Prove discrimination by INJECTING the defect, not by arguing.** Reverting `shiftEnd` to the
  naive one-liner failed exactly 2 checks; restored byte-identical against a scratch backup.
- **Never do arithmetic on a sentinel.** `Long.MAX_VALUE` = open end; `MAX_VALUE + negativeDelta`
  is still `> start`, so `setTimeRange` silently stores it as a CLOSED astronomical end and the
  load-time enforcer then exiles every sibling to its own lane. Same family as §1l's 2^61−1.
- **A failed prediction is DATA.** P1 predicted 13126ms and got 8126. Recording the miss rather
  than reinterpreting it is what exposed §2e.
- **`android.jar` is compile-only** — `Uri.parse` throws `Stub!` at runtime. Real-model harnesses
  pass `null` for `sourceUri` (`MatteVisibilityTest` does the same).
- **Git Bash mangles absolute device paths** — `export MSYS_NO_PATHCONV=1` before any
  `adb shell /storage/...`, or it becomes `C:/Program Files/Git/storage/...`.
- **`ffmpeg`'s `drawtext` is unusable here** (no fontconfig; segfaults). Label montages out-of-band.
- Harness commands: `bash tools/jvm-harness/run-anchor.sh` (real model classes),
  and `javac -d tools/jvm-harness/out-anchor app/src/main/java/com/fadcam/ui/faditor/model/AnchorMath.java
  app/src/main/java/com/fadcam/ui/faditor/model/RiderPolicy.java tools/jvm-harness/AnchorMathTest.java
  && java -cp tools/jvm-harness/out-anchor AnchorMathTest` (primitive-only, seconds).

## DEVICE RULES — NON-NEGOTIABLE

Unchanged from `20260731c`. Note 9 `SANDBOX_SERIAL` is the sandbox and must be the ONLY phone
attached; if the Note 20 `REAL_SERIAL` appears, STOP. Launch with `am start`, **never `monkey`**
(it calls `thawRotation()` and unlocks the user's rotation). Rotation lock verified `0` at the end
of this session.

Work as autonomously as you can. Prove things rather than asserting them. Update the ledger as you
go — it is the only thing that survives between sessions.

---

## ⚠ ADDENDUM (end of session) — THE §2d FIX IS **NOT** DONE. TWO ATTEMPTS, BOTH INERT.

`editorTimeOffsetMs` was threaded into **`CompositeExportOverlay`** (attempt 1) and then into
**`BlendModeGlEffect` → `PipFrameOverlay`** (attempt 2). Measured after each, same fixture, same
method: PiP onset stayed at **5.50s** both times. Target 4.90s. **Neither attempt moved it.**

**Do not assume the plumbing is wrong — it is more likely UNREACHED.** The measurement says the
patched code never ran for this PiP. The ledger's own M-EXPORT-2 note says PiP export "rides
`CompositeExportOverlay`" via an overlay-video pass, while `ExportManager:2606` ALSO builds a
`BlendModeGlEffect` per overlay clip. **Find out which one actually draws the PiP before changing
anything else** — add a one-line log to each and export; the path that prints is the real one.
That single log is worth more than another round of reasoning.

The offset is 0 when a project has no transitions, so both edits are inert for such projects and
safe to leave in place while this is settled. **They are NOT a fix and must not be recorded as one.**

Verification recipe, ready to re-run:
1. Fixture `302da9ac` (transition 600ms at editor 3200; PiP `overlayStartMs` 5501).
2. **Identify the project by logcat `Editor loaded saved project:`, never by row position** — the
   export bumps `lastModified`, which RE-SORTS the list and moved the fixture to the top mid-session.
3. Export, pull, then: `ffmpeg -ss 4.4 -t 1.6 -i x.mp4 -vf "fps=20,scale=48:27,format=rgb24"
   -f rawvideo -` and find the R−G step. 5.50 = broken, 4.90 = fixed.
