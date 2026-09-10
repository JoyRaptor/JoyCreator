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

- **Note 20 `<note20-serial>`** — the user's REAL phone; large 45-min project
  `a32d24e2-6b8d-4bd8-8432-ef5a6169dcfc`. Experimental work here only with the user present.
  The build watcher auto-installs to whatever single device is attached, which KILLS the running
  app — do not save app source while they are mid-test.
- **Note 9 `<note9-serial>`** — the sandbox.
- adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Never `logcat -c`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`. Never `--rerun-tasks`.

## OUTSTANDING WORK, in priority order

### 1. Close the second stranded-latch path — instrument is already in and waiting
`b0400b8` fixed the playhead/timeline freeze and it is **device-confirmed** (90 samples, 0
frozen, user: "played well"). But the confirming run showed the SELF-HEAL firing once, proving a
second stranding path the direct fix did not cover. Not user-visible (the net catches it), but a
net that is load-bearing is not a net.
**The instrument is already committed (`c158456`)** — additive, no control flow depends on it.
On every ACTION_UP/CANCEL the view snapshots the branch-selecting flags and the heal warning
prints them:
`userDragging was stranded … | lastUp: action=UP reorder=… scaling=… postPinchPan=… activeDrag=…`
So: have the user drive the editor normally, `grep lastUp:`, read which branch was live, fix
THAT early return. Suspects: the `if (isScaling) return true` UP, the audio-band tap and
double-tap returns, the slide double-tap return.
**Then REMOVE both `PHDIAG` (`61f184d`) and this snapshot (`c158456`).**

**UPDATE 2026-07-28 — hunted over adb, NOT reproduced, and the search is now narrowed.** Every
adb-injectable SINGLE-TOUCH gesture releases the latch cleanly (audio-band scrub — the only one
that actually latches, confirmed by a mid-gesture control — plus ruler/filmstrip/empty-lane
scrubs, flings, cross-lane releases, double-taps): `drag=false` after release, no heal. The
prime remaining suspect is the one `adb input` CANNOT synthesize — the `if (isScaling) return
true` ACTION_UP on a PINCH, which is the same shape as the first stranding. `sendevent` is
denied on this device. **So this needs the user: one pinch-zoom-and-release WHILE PLAYING, then
`grep lastUp:`.** Don't burn more autonomous cycles on it. Full method + the two
self-manufactured signals it produced are in handoff §0z (2026-07-28 ~03:35) — read that before
re-running, especially the "playback stops on a short fixture and `tail -1` goes stale" trap.

### 2. Undo-snapshot cost on large projects — ~~implementation open~~ **DONE (`88cd1b7`)**
Implemented as the schema-**v12** transcript pool: each distinct transcript is stored ONCE per
file (`transcriptPool`) and clips carry `transcriptRefs`. Proved by `TranscriptPoolCodecTest`
29/29 plus a Note 9 round-trip 14/14 and a cold-reopen read-path check 7/7 — see handoff §0z
(2026-07-28 ~02:30) for the method and, importantly, for what was measured vs projected.
**The effect on the user's real 5.3M-char project is PROJECTED, not measured** (it lives on the
Note 20). Confirm it there when the user is present: watch the `BASELINE refresh: Nms, N chars`
line — the char count should drop by roughly the duplication share.
**Still open:** `undo_history.json` was **22 MB** — several full project snapshots. It is now
written pooled so it should shrink by itself, but that is unconfirmed, and nobody has asked
whether retaining 50 snapshots is the right number. Worth its own look.
**Not made worse, still not fixed:** the `activeTranscript` index→id migration (`ed0d7ec`).
Pooling preserves the version list's order exactly (asserted in the harness), so the persisted
index stays valid — but an index into a mutable list is still the wrong key.

### 3. Inter-clip seam stall — RE-MEASURED 2026-07-28; THE CAUSE BELOW IS REFUTED
**Do not start from the "discontinuous source position" explanation in the paragraph that
follows — it was tested and it fails.** On the Note 9 (project `cebc19e0`, 9 clips, two full
playthroughs at 50ms resolution) a **contiguous** same-source seam (`gap=+0ms`, no seek needed)
cost **287ms / 327ms** while a **discontinuous** one (`gap=+404ms`) cost **155ms / 150ms**. The
seam machinery itself is the cost, not the seek. Confirmed alongside: whole-playthrough rate
**0.884× / 0.888×** (a 27.6s project takes ~31s) and per-seam costs of 123–327ms, with a
control showing 1–8ms drift away from seams so the metric is not manufacturing the deficit.
**UPDATE 2026-07-28 ~04:40 — the gapless caveat is now CLOSED.** Re-measured on `74e36000`
(0 transitions, `gapless=true`, 3 playthroughs): the contiguous `gap=+0ms` seam cost
**348 / 299 / 276ms** vs **123–213ms** for cross-source seams, and the overall rate was
**0.887× / 0.890× / 0.894×** — indistinguishable from legacy. So gapless does NOT make seams
cheaper, and the refutation holds on both engines (5 runs, 2 projects). ~90% of the whole
playthrough deficit sits inside the seam windows, so a fix here is worth real wall-clock.
Also answered: `cebc19e0` was legacy because **transition projects are gapless-ineligible**
(`FaditorPlayerManager`), which likely explains its backward playhead jumps too — the gapless
fixture had zero.
**UPDATE 2026-07-28 ~05:10 — WHERE THE TIME GOES IS NOW KNOWN: the PLAYER, not the UI thread.**
`PHDIAG` logs two independent clocks. Across three gapless runs, `head` (main-thread ticker)
and `playerPos` (ExoPlayer's own) lose time IDENTICALLY after each seam — 0.85–0.98× on both,
with a seam-free control at 1.00×/1.00×. So the plausible "the seam handler's main-thread work
starves the playhead ticker" story is REFUTED: ExoPlayer really is running slow for a few
hundred ms after a media-item transition. **A fix belongs in buffering / decoder ramp-up at the
window change, not in trimming `onGaplessSeam`'s UI work.** Trap: `playerPos` is window-local
and resets at each seam — start the measurement window only once it advances monotonically.

**UPDATE 2026-07-28 ~05:40 — CONFOUND RESOLVED; the "contiguous is worse" claim is RETRACTED.**
Seam kind does not drive the cost: within CROSS-SOURCE seams alone the losses span
191/1000/151/925/524/165ms in a single run — a ~6.6× spread inside one kind, which the
276–348ms contiguous figure sits inside. Cost scales with the clip being ENTERED (decode
ramp-up), not with source continuity. **The refutation of the original recorded cause still
stands** on its own footing: a seam needing no seek at all costs 276–348ms, so a seek cannot be
the mechanism.
**UPDATE 2026-07-28 ~06:15 — MECHANISM FOUND: the video renderer is torn down and rebuilt at
EVERY cut.** EventLogger (already on in debug builds) shows `videoDisabled → videoEnabled →
downstreamFormat → renderedFirstFrame` at each seam, taking **250/271/330ms** — the entire
measured loss — on a project where every window is the SAME file with a byte-identical format
(`video/hevc hvc1.1.6.L150.B0 1080x1920`). The decoder is flushed twice per seam and never
re-created. So every clipped playlist item pays a renderer restart regardless of source
continuity, which explains all of the week's measurements at once.
**ONE FIX TRIED AND REJECTED — do not repeat blind:** `setPreloadConfiguration(2s)` (media3
1.8.0 disables playlist preloading by default, and the engine never set it). Baseline
250/271/330 vs preload 219/236/398 — same mean, and the event sequence was unchanged, so it did
not address the restart. Reverted; a comment at the call site records it.
**Next candidates, untried:** whether per-item `ClippingConfiguration` is what forces a fresh
period+renderer at each cut (test with pre-cut media or a clipping-free playlist);
`DefaultPreloadManager` (a different API from the one tried); and the redundant second
`signalFlush` per seam.
**Metric to use:** `videoDisabled → renderedFirstFrame` from EventLogger — ms-accurate, no
instrument rebuild needed (`scratchpad/seamgap.sh`).

**Design a fix against this sentence:** *crossing a window boundary costs 120–350ms (sometimes
~1s) of real playback rate; the loss is inside ExoPlayer, not the UI thread; and it scales with
the entered clip rather than with source continuity.*
**Separate possible bug, found while trying to measure, NOT chased:** `aeb0517e` will not play
through — it stops ~5s in with `playing=false pwr=true` (an ExoPlayer buffering stall at a
window transition) and further play taps do nothing. It runs the LEGACY path (2 transitions).
Worth its own look; `bdd51919` is the retry fixture.
Method + the reproducible unexplained backward playhead jumps are in handoff §0z
(2026-07-28 ~04:10). Original (now-refuted) text kept below for context:
Every clip boundary costs ~150–250ms: playback runs at 0.77–0.91× for ~1s after each seam then
recovers to exactly 1.00×. Cause is a decoder seek into a DISCONTINUOUS source position — the 11
clips are cuts from one long recording, so each seam jumps in source time. The clips DO abut in
timeline time; there are no real gaps. The display-only inter-clip inset from `d697ca9` was ruled
OUT by reading the code (applied at draw time only, costs no playback time). The user says perf
"was better than I remembered" but wants it as smooth as possible without removing features.

### 3c. Seam stall — SOLVED, but the fix asserts something untrue — NEEDS A USER DECISION
Root cause proven (`DefaultMediaSourceFactory:589`:
`setEnableInitialDiscontinuity(!clippingConfiguration.startsAtKeyFrame)`): the engine leaves
`startsAtKeyFrame` false on every clipped window, so ExoPlayer resets the video renderer at
every cut. Flipping it to true removed **all** renderer teardowns and decoder flushes and took
the whole-playthrough rate from **0.887×/0.890×/0.894× to 0.999×/0.993×** — an ~11% deficit to
~0, with content duration preserved (see handoff §0z 2026-07-28 ~06:45 for the table).
**NOT shipped:** the flag ASSERTS the clip start is a key frame; FadCam's in-points are
arbitrary user trims, so on a non-keyframe start the decoder can begin mid-GOP. My fixture
looked clean but I did NOT verify pixels.
**Your call:** (1) snap trim in-points to keyframes — free fix, costs sub-GOP trim precision;
(2) pre-cut/re-encode at the trim point — exact trims and no stall, costs a transcode per clip;
(3) accept the deficit; or (4) hybrid — set the flag only for windows already keyframe-aligned,
so most seams win and none lie. Any of 1/2/4 needs a pixel check right after a seam first.

### 3b. Undo sidecar staleness after a crash — NEEDS A USER DECISION
`d41e130` fixed the big half (autosave now writes the undo sidecar, so one undo after a crash
no longer reverts a whole session — reproduced and proved on the Note 9; see handoff §0z
2026-07-28 ~03:05). But a death inside the 15s throttle still leaves `project.json` ahead of
`undo_history.json`, and a stale sidecar's newest snapshot is a pre-state for an edit that is
no longer the last one.
Detection is easy (stamp the sidecar with the project's `lastModified`, compare on load; the
sidecar is a bare JSON list today so this needs a header shape, list = legacy). **The
behaviour is the user's call: after a crash, no undo history at all, or one that might
over-revert?** Precedent for discarding exists (the downgrade drill refuses to restore a
read-only project's history as "incoherent"), but it removes undo right after the event where
it is most wanted. Ask, don't guess.

### 4. AI reorder drops unlisted clips — NEEDS A USER DECISION
`EditScriptApplier.applyReorderClips` silently deletes any clip missing from the AI's `newOrder`.
Deliberate for a well-formed script, but a truncated or hallucinated response deletes clips with
no error and no dependable undo (audit 1.5: the undo stack does not survive an AI reload intact).
The mechanical half is DONE (`29c7937` — captures a pre-state, restores on a mid-rebuild throw).
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
