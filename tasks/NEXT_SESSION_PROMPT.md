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

DONE 0719 ~07:30 (Fable session 3, continued): dual-stream P4 committed (`60fd849`, reviewed);
ping-pong found ALREADY un-parked by fe88e39 (docs synced, `19c5f28`); Lane-3 closed (fixed in
a8efb6a, doc updated); fresh build INSTALLED on Note 9 and DEVICE-VERIFIED: new visual picker
tiles render beautifully (screenshot proof), live-viz strip BAKED into a pulled recording (Fire
Mirror pulsing to a test tone; arm confirmed via the "draw is hot: 6.07ms" self-check log —
see the Phase-4 status block in feature-visualizer-studio-spec.md for the perf note + driving
coordinates). Test artifacts cleaned off the device; viz left DISARMED.

QUEUE (in order):
1. Live-viz perf follow-up: 6.07ms/frame avg (budget 4) with a glow style — try strip render at
   half-res upscaled, or skip glow pass on the live path; then the battery/dropped-frame A/B.
2. G9 links device verify per PLAN_G9_LINK_ENGINE.md §7 (editor UI drive, adb-able).
3. Dual-stream pair ops device verify (needs a REAL recorded pair: enable the dual-stream toggle
   in FadRec settings → record → both files → editor "Add as linked pair" → delete/split/trim
   mirrors + one-undo checks per the spec's Phase-4 checklist).
4. Ping-pong owed device checks (PLAN_LOOP_PINGPONG.md Status: frame-inversion, degrade drill,
   reverse audio, >30s guard, L3 readout bubble).
5. road_map.md ship-blocker/verify lists, solo-doable items.
6. OPEN JOYRAPTOR ITEMS (surface, don't block): assets/web FadSec dashboard + live id.fadseclab.com
   domain; locale-file rename sweep; icon-asset renames (ic_launcher_fadseclab*).

HOUSE RULES (unchanged): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
gradlew; NEVER --rerun-tasks (corrupts media3-patched jars); adb at
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — use Grep/ls;
multi-line commits via `git commit -F <file>`; commit style `faditor(scope): ...`; don't touch
icons/PSD, tools/jvm-harness/out2/, whisper.cpp/, media3-patched/; update this file + a
handoff.md top block before each limit; reschedule the CronCreate wakeup (one-shot, ~5h out,
off-minute) every session.
