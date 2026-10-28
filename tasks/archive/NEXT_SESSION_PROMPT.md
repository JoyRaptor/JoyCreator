# NEXT SESSION PROMPT

> **▶ START HERE — UPDATE 2026-07-25 ~21:30 (Opus 5, device-verification session). HEAD
> `8fc7cb4`, tree clean. Read `handoff.md`'s TOP block first — it supersedes the banner below.**
> **THE BUILD WAS BROKEN.** The previous session's "35 commits, all compile-green" was false:
> the watcher had been dead since 13:56 and a stale-from-07-06 javac intermediates dir made
> every build die before type-checking, hiding a real error. Both fixed (`9ec93c0`). **Before
> trusting any "compile-green" claim, check the APK's `lastUpdateTime` against `git log` and
> confirm the watcher is alive (build.log mtime > source mtime AND java processes exist).**
> **FIRST DEVICE-PROVEN SUBSTRATE BUG, fixed + pinned (`8fc7cb4`):** a payload on another
> type's seeded lane let an earlier phase's leftover flush steal that lane — renaming it,
> flipping its TrackKind and hoisting it up the band, which under cross-type Z is a silent
> paint-order change. `getlayers_equiv.py` now carries 4 assertions that go red if it returns.
> **PiP audio:** the shared `effectiveOverlayVolume` authority is verified across all four gate
> states (incl. lane-mute, last session's bug #4). **Still owed: the preview/export plumbing on
> each side of it — acceptance 2 and 4.**
> **METHOD (reuse):** the lane header is canvas-drawn — row names are unreadable from
> screenshots/`uiautomator`. Use a temporary `Log` probe in `getLayers()`, and probe the INPUT
> lists too. Beware: pristine project.json OMITS an absent `layerId`; writing `"layerId": null`
> makes the loader drop the sprite (a real, separate loader fragility).
> Sandbox `129d8643` restored sha256-identical. Device driving was user-approved this session.

> **UPDATE 2026-07-25 evening (Opus 5, continuous session). Tree clean; see
> `git log` for the ~20 commits of this session (they are individually revert-friendly).** Read `handoff.md`'s top block for the full account; the short version:
> **BUILT:** neutral substrate S0–S5 (FULL neutrality — JoyRaptor's call: any object on any floating
> lane, audio is the only separate band; `getLayers()` is now layerId-first), PiP audio A–D (it
> had NO audio path at all: opt-in `Clip.overlayAudioEnabled`, preview + export + object-menu
> toggle + a waveform shelf on lane rows), and a read-only warning for newer-schema projects.
> **TWELVE real bugs found by adversarial review** — see the handoff block. Three were in my own
> new code; the rest pre-existing, and the biggest is its own lane: **transition placement
> survived NO clip-structural undo** (delete/split/insert/duplicate/pair ops all left
> transitions on the wrong seams — they still play, at a cut the user never chose). All fixed,
> and that lane is the ONE part of this session that is verified: `tools/jvm-harness/
> TransitionIndexTest.java`, 16/16 PASS. Audit closed in `tasks/AUDIT_TRANSITION_INDEX_UNDO.md`.
> **SPECS:** `SPEC_NEUTRAL_SUBSTRATE.md`, `SPEC_PIP_AUDIO.md`, `SPEC_CROSSTYPE_Z.md`,
> `REVIEW_ORDER_20260725.md`, `DRILL_SCHEMA_DOWNGRADE.md`.
> **⚠️ NOTHING BELOW WAS DEVICE-VERIFIED — this is the top priority for the next session.** The
> phone was in human use (Messages), so tap injection was stopped per the etiquette rule and not
> resumed. Validation queues are written into both specs; the drill recipe is its own file.
> Two device gotchas learned: the app ROTATES its own `project.json.bak` (so it is NOT a pristine
> snapshot — capture originals host-side first), and `FaditorEditorActivity` is `exported=false`
> (open projects through the UI, not `am start`).
> **REGRESSION GUARD:** `python tasks/getlayers_equiv.py "<pulled>/proj_*.json"` after ANY
> `getLayers()` change — it proved 9/10 real projects byte-identical across the rewrite.
> **CROSS-TYPE Z IS NOW BUILT** (`SPEC_CROSSTYPE_Z.md`, Z1–Z5): lane order finally decides what
> paints over the video, in preview AND export. Two-bucket model (in front of / behind the PiP
> plane), inert until a lane is deliberately ordered under a PiP — asserted across 11 real
> projects, not assumed. Z3 and Z4 landed together on purpose: either alone would have let a
> user make export honour an ordering the preview ignored. **OWED: visual check of the preview
> stack + an absolute-geometry export A/B frame diff.** Full interleaving (text A over PiP over
> text B) is still out of scope and remains the documented limit.

> **UPDATE 2026-07-25 ~13:25 (Opus 4.8, user directive "everything except frontier"): HEAD is now
> `ae3d3d5`, tree CLEAN.** Landed since `828ed68`: `066d20d` speed-change gapless sibling (speed
> edits re-bake the snapshot on speed-sheet commit), `031ed07` F9 "analyzing audio…" sticky fix.
> The 0719 hand-test batch (`c207526`) is now INSTALLED on the Note 9 (<sandbox-serial>) and smoke-verified
> (editor renders; transport SELECT toggle functional). Latest APK (both fixes + batch) installed
> 13:20. See handoff.md top block for the full state. NEUTRAL-SUBSTRATE lane deliberately left for
> the frontier model (architecture mapped in the handoff block). REMAINING device-verify (below) is
> for JoyRaptor hand-test / lesser models. The DONE/QUEUE lists below predate this and are otherwise
> current.

# NEXT SESSION PROMPT (rewritten 2026-07-19 ~04:10 by Fable 5, mid-run)

You are resuming autonomous spec-finishing on FadCam/Joy Creator at
`C:\+Projects\Screenrecorder\FadCam` (branch `joy-creator`). JoyRaptor's standing directive: work
autonomously through unfinished specs toward an industry-leading mobile recording/editing/
animation studio, document as you go, never stop to ask, and always keep a one-shot CronCreate
wakeup ~5h out pointing back at this file (reschedule each session before the limit hits).

READ FIRST: tasks/handoff.md top block (🔗 2026-07-19 ~04:00) — full state. HEAD when this file
was written: `828ed68`. Tree was CLEAN at that point except tools/jvm-harness/out2/ (ignore).
If the tree is dirty now, a §4.5 slice was in flight — compile, review, finish or commit it
coherently before anything else (slice plan below).

DONE THIS ARC (do not redo): GL live-blend + export parity committed (`c7d7149`); KineMaster
playhead + all 3 deferred seams (`dd6eb98`,`a90b5b7`); audio-band marquee + audio batch delete
(`282f182`); G9a–e links complete (`6e0295d`,`50e540a` — see PLAN_G9_LINK_ENGINE.md STATUS).
Earlier arcs: viz Phase 4 (`af1eab9`), H.264 baseline hook (`85feacc`), depoliticize A1/A2/B1
(`c409234`). Latest build with ALL of this is installed on the Note 9 (<sandbox-serial>, attached).

DONE 0719 ~07:15 ARC (Fable session 3): §4.5 was already landed by the parallel arc (S1-S3
committed). NEW this arc: viz style-picker VISUAL PREVIEWS (`cf15e38`, JoyRaptor directive — rendered
tiles, not text); de-politicize B2 forensics→"Smart Detection" vocabulary + B4 Hidden Thumbnails
(`43d8437`, JoyRaptor-approved mapping; B3 kept by decision; assets/web dashboard + locale sweep still
open — the web dashboard has a LIVE domain id.fadseclab.com, needs JoyRaptor). Dual-stream P4
reachable ops agent was IN FLIGHT at write time (both entry points per JoyRaptor: asset-browser pair
auto-detect + manual link via marquee menu; mirrored delete/split required, trim same-speed).

DONE 0719 ~07:30 (Fable session 3, continued): dual-stream P4 committed (`a6bf440`, reviewed);
ping-pong found ALREADY un-parked by 72c8cd7 (docs synced, `4285f17`); Lane-3 closed (fixed in
c7d7149, doc updated); fresh build INSTALLED on Note 9 and DEVICE-VERIFIED: new visual picker
tiles render beautifully (screenshot proof), live-viz strip BAKED into a pulled recording (Fire
Mirror pulsing to a test tone; arm confirmed via the "draw is hot: 6.07ms" self-check log —
see the Phase-4 status block in feature-visualizer-studio-spec.md for the perf note + driving
coordinates). Test artifacts cleaned off the device; viz left DISARMED.

DONE 0719 ~07:50 (wrap): live-viz perf fix COMMITTED `89399d3` (LIVE_RENDER_SCALE=2 half-res
strip render, density-compensated; expected ~6ms→~1.5ms; arm-log mystery resolved BENIGN —
one-shot service-tag log vs periodic hot-draw warning). All 11 tracked lanes of the 0719 arcs
are now COMMITTED and the tree is CLEAN except tools/jvm-harness/out2/ (ignore).

RE-MEASURE RESULT (0719 07:33, post-89399d3 build ON-DEVICE): arm log now captured
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
P1. ✅ BUILT `409b820` (0719 evening, Fable): OPACITY axis end-to-end proves multi-axis
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
   substitute media3-transformer, so the Maven artifact shipped. Fixed in `9c40917`.
   ⚠️ CONSEQUENCE: the shipping EXPORT ENGINE provenance changed from Maven transformer to the
   fork's. (a) ✅ flag-OFF export re-probed 15:43: profile=High, level=31, full 9.03s duration —
   fork transformer behaviorally identical when Baseline not requested. (b) ✅ 15:48 GL-transition
   export smoke on the fork transformer (project 81033, tangentMotionBlur): seam frame-tiles show
   the incoming clip at CONSTANT pillarbox scale mid-blend→post-cut, no snap/black/stretch —
   engine parity holds. (The crop-injected extreme case stays covered by c7d7149's historical
   measurement; re-run it only if a transition regression ever appears.)
5. Ping-pong owed device checks (PLAN_LOOP_PINGPONG.md Status list).
6. OPEN JOYRAPTOR ITEMS (surface, don't block): assets/web FadSec dashboard + live id.fadseclab.com
   domain; locale-file rename sweep; icon-asset renames; rebrand art set (ASSETS_WISHLIST.md).
DEVICE ETIQUETTE: if screenshots show human activity (shade pulls, app switches you didn't
cause), STOP injecting immediately and say so — JoyRaptor sometimes picks the phone up.


JOYRAPTOR 0719 HAND-TEST BATCH (`c207526`, compile-green, NOT yet installed - device was unplugged;
install on next connect): select-mode drag-on-object = instant move (empty space = box; this is
what blocked her linked-motion test); preview double-tap fixed (handles overlay was eating the
second tap - now forwards to the type editor); kind badges moved onto OBJECT blocks
(payload-derived), row gutters identity-free; select toggle promoted to the transport row left
of ripple (btn_select_mode). RE-TEST OWED (JoyRaptor): drag one TIME-linked text -> partner follows;
double-tap text in preview opens text dialog; badges read per-object; transport toggle cycles.
STILL OPEN from her feedback - THE BIG ONE: "any object on any layer" (neutral substrate,
model half): Timeline.getLayers routes items into per-kind tracks and payloadCompatible blocks
cross-kind drops. Needs: layerId-first routing in getLayers, payloadCompatible relaxed for the
floating band, mixed-row rendering audit (drawItemBody is already per-item-kind), preview
z-order across mixed lanes. Design lane - spec it before building.

HOUSE RULES (unchanged): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
gradlew; NEVER --rerun-tasks (corrupts media3-patched jars); adb at
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — use Grep/ls;
multi-line commits via `git commit -F <file>`; commit style `faditor(scope): ...`; don't touch
icons/PSD, tools/jvm-harness/out2/, whisper.cpp/, media3-patched/; update this file + a
handoff.md top block before each limit; reschedule the CronCreate wakeup (one-shot, ~5h out,
off-minute) every session.
