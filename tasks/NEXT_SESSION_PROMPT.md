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

QUEUE (in order):
1. LANE_BADGES §4.5 eye/lock → PER-OBJECT migration (LANE_BADGES_AND_PREVIEWS_SPEC_20260714.md).
   Slice plan (each always-green + committed separately):
   S1: `hidden`+`locked` booleans on TextOverlayItem, SpriteOverlayItem, WaveformOverlayInstance,
       overlay Clip; `locked` ONLY on AudioClip (its per-clip mute already IS the eye) +
       ProjectStorage tolerant round-trip (write-if-true). Inert.
   S2: hide goes live — LayerPreviewController visible* filters also drop object.hidden (this is
       the single authority preview AND export consume for text/sprite/image; check viz + PiP
       export feeds separately and filter there too); LayerRowRenderer ghosts hidden objects
       (extend the existing track-ghosted flag per item); ObjectMenuSheet drawers gain
       Hide/Show + Lock/Unlock actions (audio: Lock only), one undo step each.
   S3: locked enforcement — locked items stay SELECTABLE (else they could never be unlocked via
       the drawer) but LayerGestureController never arms trim/pickup on them and the delete
       badge is suppressed; THEN remove the per-track eye/lock gutter icons + hit zones + M6
       toggle glue (keep caret + audio mute; per-layer SOLO is a separate unbuilt feature —
       do not bundle it).
2. G9 device verify, adb-drivable (PLAN_G9_LINK_ENGINE.md §7): marquee-link two text overlays →
   badges; hold-drag one → partner follows live; ONE undo restores both; unlink scopes;
   save/reload round-trip (project JSON gains linkGroups).
3. Lane-3 GL export A/B (tasks/LANE3_gl_transition_ab_hypothesis.md — sandbox export recipe).
4. Dual-stream P4 reachable ops — consider proposing the marquee-batch-menu pattern as the
   link-creation UI (mirrors G9d) in a spec note for JoyRaptor rather than staying blocked.
5. Whatever remains in road_map.md's ship-blocker/verify lists that is solo-doable.

HOUSE RULES (unchanged): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
gradlew; NEVER --rerun-tasks (corrupts media3-patched jars); adb at
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe; Glob broken — use Grep/ls;
multi-line commits via `git commit -F <file>`; commit style `faditor(scope): ...`; don't touch
icons/PSD, tools/jvm-harness/out2/, whisper.cpp/, media3-patched/; update this file + a
handoff.md top block before each limit; reschedule the CronCreate wakeup (one-shot, ~5h out,
off-minute) every session.
