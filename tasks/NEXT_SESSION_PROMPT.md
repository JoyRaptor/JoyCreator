# NEXT SESSION PROMPT (rewritten 2026-07-19 ~04:10 by Fable 5, mid-run)

You are resuming autonomous spec-finishing on FadCam/Joy Creator at
`C:\+Projects\Screenrecorder\FadCam` (branch `joy-creator`). JoyRaptor's standing directive: work
autonomously through unfinished specs toward an industry-leading mobile recording/editing/
animation studio, document as you go, never stop to ask, and always keep a one-shot CronCreate
wakeup ~5h out pointing back at this file (reschedule each session before the limit hits).

READ FIRST: tasks/handoff.md top block (🔗 2026-07-19 ~04:00) — full state. HEAD when this file
was written: `3c443b0`. Tree was CLEAN at that point except tools/jvm-harness/out2/ (ignore).
If the tree is dirty now, a §4.5 slice was in flight — compile, review, finish or commit it
coherently before anything else (slice plan below).

DONE THIS ARC (do not redo): GL live-blend + export parity committed (`a8efb6a`); KineMaster
playhead + all 3 deferred seams (`d83bfd2`,`f0e0cd2`); audio-band marquee + audio batch delete
(`2341596`); G9a–e links complete (`cc68b3b`,`114a163` — see PLAN_G9_LINK_ENGINE.md STATUS).
Earlier arcs: viz Phase 4 (`f58a120`), H.264 baseline hook (`18a1824`), depoliticize A1/A2/B1
(`492a55c`). Latest build with ALL of this is installed on the Note 9 (29e37138, attached).

DONE 0719 ~07:15 ARC (Fable session 3): §4.5 was already landed by the parallel arc (S1-S3
committed). NEW this arc: viz style-picker VISUAL PREVIEWS (`2236b58`, JoyRaptor directive — rendered
tiles, not text); de-politicize B2 forensics→"Smart Detection" vocabulary + B4 Hidden Thumbnails
(`fc2caea`, JoyRaptor-approved mapping; B3 kept by decision; assets/web dashboard + locale sweep still
open — the web dashboard has a LIVE domain id.fadseclab.com, needs JoyRaptor). Dual-stream P4
reachable ops agent was IN FLIGHT at write time (both entry points per JoyRaptor: asset-browser pair
auto-detect + manual link via marquee menu; mirrored delete/split required, trim same-speed).

QUEUE (in order):
1. Dual-stream P4: if uncommitted edits exist in FaditorEditorActivity/AssetBrowserPanel/
   Timeline, that's the agent's lane — compile, review, commit if sound (it was told not to).
2. DEVICE VERIFY batch on the Note 9 (build+install AFTER the tree is clean): (a) live-viz strip
   — long-press the record-row waveform icon → NEW visual picker → pick a style (auto-arms) →
   record with audio playing (pulse.wav is at /sdcard/Download/pulse.wav; ACTION_VIEW plays it)
   → pull mp4 → frames show the bottom-strip visualizer. CAUTION: blind taps misfired twice —
   the annotation toggle sits near the viz toggle; use uiautomator dump WHILE THE APP IS OPEN
   for real bounds. Logcat proof: "Live visualizer armed: style=" at recording start.
   (b) G9 links per PLAN_G9_LINK_ENGINE.md §7. (c) new picker tiles render. (d) forensics
   screens show Smart Detection vocabulary. (e) dual-stream pair ops if a recorded pair exists.
3. Lane-3 GL export A/B (tasks/LANE3_gl_transition_ab_hypothesis.md — sandbox export recipe).
4. road_map.md ship-blocker/verify lists, solo-doable items.
5. OPEN JOYRAPTOR ITEMS (don't block, just surface): assets/web FadSec dashboard + live domain;
   locale-file rename sweep; icon-asset renames (ic_launcher_fadseclab* etc).

HOUSE RULES (unchanged): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
gradlew; NEVER --rerun-tasks (corrupts media3-patched jars); adb at
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — use Grep/ls;
multi-line commits via `git commit -F <file>`; commit style `faditor(scope): ...`; don't touch
icons/PSD, tools/jvm-harness/out2/, whisper.cpp/, media3-patched/; update this file + a
handoff.md top block before each limit; reschedule the CronCreate wakeup (one-shot, ~5h out,
off-minute) every session.
