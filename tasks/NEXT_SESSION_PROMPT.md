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

JOYRAPTOR'S PRIORITY CALL (2026-07-19 evening, Fable's last day on this plan — these are the HARD
items reserved for the strongest available model; everything else can go to lesser models + JoyRaptor
hand-testing):
P1. ✅ BUILT `96b99d5` (0719 evening, Fable): OPACITY axis end-to-end proves multi-axis
    membership — axis-choice link dialog, per-axis strict conflict rules, per-group unlink
    actions, opacity delta propagation (text+sprite). DEVICE-VERIFY: link 2 texts on TIME in
    group A, then same texts + a third on OPACITY in group B → both propagate independently.
    (was) G9 multi-membership resolver — JoyRaptor requires an item to join DIFFERENT link groups on
    DIFFERENT property-axes simultaneously (TIME→group A + OPACITY→group B). The shipped G9c/d/e
    supports one group per item. Re-scope per PLAN_G9_LINK_ENGINE.md §8 answer 3: membership must
    key by (item, axis)→group in LinkGroup/LinkMember + the resolver + toolbar tie-break.
    Real design work; touches Timeline + FaditorEditorActivity (god file — one agent at a time).
P2. ⏸ ATTEMPTED 0719 ~19:10 (Fable), ABORTED ON HUMAN PRESENCE — notification shade opened
    mid-drive (etiquette rule) with the drill ~90% staged; device FULLY restored (projects
    clean, planted bake deleted). PROVEN RECIPE for the re-run (minutes, not re-derivation):
    (1) bake trigger is UI-edit-only (kickReverseBakeIfNeeded), so injected JSON never bakes —
    instead PLANT a corrupt bake at the exact cache name: key=uri|in|out|v2, filename
    rev-v2-<in>-<out>-<abs(java hashCode%100000)>.mp4 in cache/reversed (Python hashCode
    emulation verified); (2) inject loopMode=2+loopAfterMs into that clip's project.json
    (project 129d8643 'bisect C long 2x' clip 668c2c49 in=98 out=7078 → rev-v2-98-7078-68993
    .mp4); (3) FaditorEditorActivity is exported=false — open via UI: Faditor tab, project row
    (logcat 'Editor opened saved project:' confirms id); (4) play across the wrap → expect
    'RANK-1 recovery: POISONED reversed URI' + no blackout (resolveReversedUri poisons + playlist
    rebuild, FaditorEditorActivity ~3142-3199). Backups land in the session scratchpad —
    re-make them fresh. ALSO incidentally proven this pass: editor renders clean on the P1
    build (rows/diamonds/badges), preview tap=select shows handles (no dialog).
    (was) Ping-pong failure-drill on device — the per-item decode-fallback (poisoned-URI degrade) has
    never been WATCHED failing. Force a failure (corrupt a baked reverse file), confirm the
    shared player never blacks out and only that clip degrades. Failure mode if wrong = whole-
    player black, the exact bug that got the feature parked.
P3. media3-fork discipline — today's H.264 lesson: a fork patch is INERT unless its module is in
    settings.gradle.kts's substitution list; compile-green proves NOTHING about the APK. Rule
    recorded in the H264 finding doc + the auto-memory; any future fork patch must end with a
    runtime proof (ffprobe/logcat), never just a build.

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
