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

DONE 0719 ~07:50 (wrap): live-viz perf fix COMMITTED `058f5c8` (LIVE_RENDER_SCALE=2 half-res
strip render, density-compensated; expected ~6ms→~1.5ms; arm-log mystery resolved BENIGN —
one-shot service-tag log vs periodic hot-draw warning). All 11 tracked lanes of the 0719 arcs
are now COMMITTED and the tree is CLEAN except tools/jvm-harness/out2/ (ignore).

RE-MEASURE RESULT (0719 07:33, post-058f5c8 build ON-DEVICE): arm log now captured
("Live visualizer armed: style=fire_mirror" — benign-window theory confirmed) but the draw is
STILL hot: 4.5–6.25ms avg. Halving fill pixels barely moved the number ⇒ the cost is NOT
Canvas-fill-dominated — next lever is SPLIT TIMING inside GLWatermarkRenderer.drawVisualizerLayer
(measure renderFrame vs texImage2D upload vs GL sync separately; suspect the per-frame
texImage2D upload + implicit sync). Half-res VISUAL PARITY confirmed (pulled-frame crop is
crisp). No jank at 30fps — 6ms of a 33ms frame; the 4ms budget is self-imposed. Treat as
POLISH, not a blocker.

QUEUE (in order — pure device-verify + small errands; NOTHING needs re-deriving):
1. Live-viz perf round 2 (polish): add split timing logs, then either texture double-buffering
   / texSubImage2D reuse, or accept and raise the self-check budget to ~8ms with a comment.
   Then the battery/dropped-frame A/B (coordinates: viz toggle = 4th record-row button x906
   y1998 @1080x2220; x802 = Audio Source (keep Mic); consent "Start now" x674 y2085;
   start/stop x380 y1998; tone: ffmpeg sine+tremolo wav → push → ACTION_VIEW
   file:///sdcard/Download/pulse.wav).
2. G9 links device verify per PLAN_G9_LINK_ENGINE.md §7. HEAD START: project bdd51919 already
   holds a persisted TIME group "test-g9-group-1" (master clip 92bec151… + waveform f6ba8ced…,
   hostOffset 2000) and 5 text overlays — storage round-trip is half-proven; what remains is
   the UI drive: hold-drag a member → partner follows live; ONE undo restores both; unlink.
3. Dual-stream pair ops device verify (needs a REAL recorded pair: FadRec settings → "Record
   webcam as separate file" toggle → record → editor playhead-insert offers "Add as linked
   pair" → delete/split/trim mirrors + one-undo, per the spec's Phase-4 checklist).
4. ~~H.264 Baseline runtime proof~~ ✅ DEVICE-PROVEN 15:38 (ffprobe profile=Baseline level=31).
   CRITICAL FINDING while proving it: the patch was DEAD CODE — settings.gradle.kts didn't
   substitute media3-transformer, so the Maven artifact shipped. Fixed in `f99f1fc`.
   ⚠️ CONSEQUENCE: the shipping EXPORT ENGINE provenance changed from Maven transformer to the
   fork's. (a) ✅ flag-OFF export re-probed 15:43: profile=High, level=31, full 9.03s duration —
   fork transformer behaviorally identical when Baseline not requested. (b) ✅ 15:48 GL-transition
   export smoke on the fork transformer (project 81033, tangentMotionBlur): seam frame-tiles show
   the incoming clip at CONSTANT pillarbox scale mid-blend→post-cut, no snap/black/stretch —
   engine parity holds. (The crop-injected extreme case stays covered by a8efb6a's historical
   measurement; re-run it only if a transition regression ever appears.)
5. Ping-pong owed device checks (PLAN_LOOP_PINGPONG.md Status list).
6. OPEN JOYRAPTOR ITEMS (surface, don't block): assets/web FadSec dashboard + live id.fadseclab.com
   domain; locale-file rename sweep; icon-asset renames; rebrand art set (ASSETS_WISHLIST.md).
DEVICE ETIQUETTE: if screenshots show human activity (shade pulls, app switches you didn't
cause), STOP injecting immediately and say so — JoyRaptor sometimes picks the phone up.

HOUSE RULES (unchanged): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
gradlew; NEVER --rerun-tasks (corrupts media3-patched jars); adb at
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — use Grep/ls;
multi-line commits via `git commit -F <file>`; commit style `faditor(scope): ...`; don't touch
icons/PSD, tools/jvm-harness/out2/, whisper.cpp/, media3-patched/; update this file + a
handoff.md top block before each limit; reschedule the CronCreate wakeup (one-shot, ~5h out,
off-minute) every session.
