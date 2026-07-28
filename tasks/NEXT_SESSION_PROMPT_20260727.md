# NEXT SESSION PROMPT — written 2026-07-27 ~21:00, branch `joy-creator`

Supersedes `NEXT_SESSION_PROMPT.md` (2026-07-25). Copy everything below the rule into a fresh
session.

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
Continuing an interactive build/debug session. Work autonomously until I say otherwise.

**FIRST, IN THIS ORDER:**
1. `tasks/HANDOFF_20260726_CONTEXT_SWITCH.md` §0z PROGRESS LOG — newest entry first. Trust it +
   `git log` over older prose.
2. `tasks/SPEC_OBJECT_TIME_SCRUBBER.md` §12 (DONE/OPEN ledger) and §13 (round-2 device feedback).
3. `tasks/AUDIT_UNFINISHED_20260726.md` — but VERIFY each item against the code before working
   it. Items 1.2, 1.3 and 1.6 were all found already-fixed-or-since-fixed; the doc had gone
   stale, and re-scoping them would have wasted a whole pass.

## Rules that were paid for in bugs — keep them

- **Prove with a harness or a positive control; never assert.** The playhead-freeze bug was
  cracked by instrumenting, and the instrument proved BOTH read-only hypotheses wrong. Say
  "unverified" when it is.
- **Never trust "compile-green".** `build.log` is UTF-16 (`Get-Content -Encoding Unicode`).
  Confirm the APK actually installed: `dumpsys package com.fadcam.beta | grep lastUpdateTime`
  must be NEWER than the newest `app/src` mtime. A build can succeed and still fail to install
  ("No connected devices!") and the watcher does NOT retry — touch a source file to retrigger.
- **`adb logcat` with no `-T` replays the whole ring buffer.** A freshly-armed monitor
  re-reports OLD lines and looks like a regression. Use `logcat -T 1` for new events only.
- **Git Bash mangles device paths** (`/data/local/tmp` → `C:/Program Files/Git/data/...`). Use
  PowerShell for `adb push` / `adb shell` with absolute device paths.
- **Before writing to the user's project.json: re-pull and compare.** The on-disk file changes
  while they work. A stale write silently deletes whatever they did in between — this nearly
  destroyed a waveform overlay they had just added. Back up, write, then READ BACK and
  byte-compare.
- **The editor autosaves.** Any write to `project.json` while `FaditorEditorActivity` is
  resumed gets overwritten. Wait until it is closed, then `am force-stop`, then write.
- Keep the tree clean; one commit per fix stating what was proved and how. `git commit -F`.

## Devices

- **Note 20 `REAL_SERIAL`** — the user's REAL phone; large 45-min project
  `a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc`. Experimental work here only with the user present.
  The build watcher auto-installs to whatever single device is attached, which KILLS the running
  app — do not save app source while they are mid-test.
- **Note 9 `SANDBOX_SERIAL`** — the sandbox.
- adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Never `logcat -c`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`. Never `--rerun-tasks`.

## OUTSTANDING WORK, in priority order

### 1. Close the second stranded-latch path — instrument is already in and waiting
`f59850a` fixed the playhead/timeline freeze and it is **device-confirmed** (90 samples, 0
frozen, user: "played well"). But the confirming run showed the SELF-HEAL firing once, proving a
second stranding path the direct fix did not cover. Not user-visible (the net catches it), but a
net that is load-bearing is not a net.
**The instrument is already committed (`1fc3298`)** — additive, no control flow depends on it.
On every ACTION_UP/CANCEL the view snapshots the branch-selecting flags and the heal warning
prints them:
`userDragging was stranded … | lastUp: action=UP reorder=… scaling=… postPinchPan=… activeDrag=…`
So: have the user drive the editor normally, `grep lastUp:`, read which branch was live, fix
THAT early return. Suspects: the `if (isScaling) return true` UP, the audio-band tap and
double-tap returns, the slide double-tap return.
**Then REMOVE both `PHDIAG` (`701c1e0`) and this snapshot (`1fc3298`).**

### 2. Undo-snapshot cost on large projects — investigation DONE, implementation open
`BASELINE refresh: ~250ms, 5.3M chars` on the real project = a main-thread stall per edit burst.
The user chose "investigate a faster path before trading anything away", and the answer is
dramatic: **transcripts are 99.2% of the payload, and 71.8% of the project is byte-identical
duplication** — the same transcript object (same `id`, same sha) stored on up to 7 clips.
Collapsing it is **zero semantic change**.
Implement either: store each transcript ONCE per distinct id and reference it, or exclude
transcripts from the undo snapshot and re-attach on restore. Prove with a harness first.
Fallback the user pre-approved only if needed: scale the snapshot interval with project size.
The project has since grown to ~7.9MB (Best transcripts added), so this got MORE valuable.
Related: `undo_history.json` is **22 MB** — several full project snapshots. Worth its own look.

### 3. Inter-clip seam stall — measured, not yet optimised
Every clip boundary costs ~150–250ms: playback runs at 0.77–0.91× for ~1s after each seam then
recovers to exactly 1.00×. Cause is a decoder seek into a DISCONTINUOUS source position — the 11
clips are cuts from one long recording, so each seam jumps in source time. The clips DO abut in
timeline time; there are no real gaps. The display-only inter-clip inset from `1bfc121` was ruled
OUT by reading the code (applied at draw time only, costs no playback time). The user says perf
"was better than I remembered" but wants it as smooth as possible without removing features.

### 4. AI reorder drops unlisted clips — NEEDS A USER DECISION
`EditScriptApplier.applyReorderClips` silently deletes any clip missing from the AI's `newOrder`.
Deliberate for a well-formed script, but a truncated or hallucinated response deletes clips with
no error and no dependable undo (audit 1.5: the undo stack does not survive an AI reload intact).
The mechanical half is DONE (`3b03081` — captures a pre-state, restores on a mid-rebuild throw).
Options: refuse unless `newOrder` is a permutation of the existing clip ids, or append the
unlisted clips in their original relative order. **Ask before changing behaviour.**

### 5. Audit 1.5's three-way decision — NEEDS A USER DECISION
What undo should do after an AI reload replaces the project: clear the stack / capture an "AI
edits" checkpoint / document AI edits as outside the undo model. Real UX consequences either way.

### 6. Remaining SPEC §12 / §13 items
Scrubber completion (extend to sprite/audio/PiP/visualizer; push-through relayer + cross-lane
y-glide per §9; move-drawer port per §11), B-DIAGPREVIEW (the drag proxy is not WYSIWYG during a
diagonal drag — it snaps vertically and only shows the horizontal offset on release),
B-GREENHILITE (verify the green selection highlight is not a uniform-axis regression).
F-COLOR and F-MINIMAP are DONE and user-confirmed ("minimap looks great").

## Transcripts — what was done, and what is left

Every clip now carries a **Best** transcript built by anchoring the best TEXT onto vosk TIMINGS
(vosk is the reliable clock; whisper accumulates timestamp error over long audio, and Sonnet's
"Imported" merge drifted badly). All proven before writing, text preserved exactly, monotonic:

| clips | text from | timing from | before | after |
|---|---|---|---|---|
| 0–6 | Imported | Accurate+Fast (vosk) | +245ms mean, 288 words >500ms, peak 5.07s | 0ms, 0 words |
| 7–8 | High accuracy (whisper) | user's fresh Fast (vosk) | −117ms clip7 / **−6429ms clip8** | 0ms, 0 words |
| 9–10 | High accuracy (whisper) | Accurate (vosk) | −140ms mean, 234 words >500ms | 0ms, 0 words |

Key diagnosis worth keeping: clip 8's whisper was a CONSTANT −6.4s off (mean ≈ median), while
clip 7's agreed with vosk to −117ms — the same whisper transcript object, correct at its start
and 6.4s adrift 26 minutes later. That is whisper accumulating error, not a mapping bug, and it
is why the longest clip was worst. Vosk does not do this.

Scripts live in the session scratchpad and are worth rewriting if needed: measure drift by
aligning normalised tokens with `difflib` and comparing `s` values on exactly-matching words;
always include a control (two vosk transcripts against each other agree to −1ms, which is what
proves the method is not itself noisy).

**Open transcript work the user asked for:** they want obviously-wrong ASR text corrected using
context ("if there are transcripts that look grossly wrongly translated, go ahead and correct
them"). NOT started. Do it as a reviewable diff — show sample corrections before applying, and
never change timings as part of a text pass.
Backups on device: `project.json.pre_best_20260727`, `project.json.pre_best_b_20260727`.

## Standing debt only a human can confirm
PiP-audio export (plays, not doubled), and the caption size slider live in preview.

---
