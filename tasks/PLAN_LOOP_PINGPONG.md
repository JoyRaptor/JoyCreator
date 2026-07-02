# PLAN — Loop / Ping-Pong v2 (diagnosis + executable spec)
Written 2026-07-02 ~13:10 by the orchestrator (Fable) after direct code-reading. Supersedes the loop behavior
shipped by the earlier Opus 4.8 session. User verdict on that version: "80% there, still janky, unclear whether
the jank was looping itself or other features cutting into it." Answer: BOTH — five concrete root causes below.

## PART 1 — ROOT CAUSES (all confirmed by reading, with anchors)
1. **Loop wraps are poll-driven + cold-seek.** All wrap logic lives in FaditorEditorActivity's playback tick
   (~6805-6944): detect `isAtTrimEnd()` in a poll → `loopRestartPending` → `seekTo(0)`. Every wrap pays poll
   latency + a cold seek → a visible hitch per wrap. Same defect class as the clip-seam stall M-COMP-0 fixed.
2. **Preview ping-pong "reverse" is `setPlaybackSpeed(-1f)`** (activity ~6843) → FaditorPlayerManager:592
   feeds it straight into `new PlaybackParameters(speed)`. Media3 REQUIRES speed > 0 — this throws
   IllegalArgumentException (or is swallowed somewhere upstream). True reverse playback does not exist in the
   current preview; a wall-clock "decoupled timeline" hack (~6819-6853) papers over it.
3. **Export ping-pong doesn't reverse either — and disagrees with preview.** ExportManager
   `buildLoopExtensionItem` ~872-891: the "reverse" leg just plays the TAIL of the source FORWARD
   (`[outPoint-span, outPoint]`, normal direction). Preview fakes reverse one way, export another →
   preview ≠ export, the worst kind of editor lie.
4. **A single looped clip disables the gapless engine for the WHOLE project.**
   MasterPlaybackEngine.isEligible (~138-145) rejects any timeline containing `hasLoopExtension()` → every
   other seam in that project regresses to the legacy 100-400ms stalls. This is the "other features cutting
   into it" the user sensed.
5. STILL mode (freeze-frame extension) is poll+wall-clock but conceptually fine — lowest priority.

## PART 2 — TARGET ARCHITECTURE
**A NORMAL loop is just the same clipped MediaItem repeated in the gapless playlist.** Warm ExoPlayer
item-transitions make wraps seamless BY CONSTRUCTION — no polling, no seeks. **True ping-pong requires a real
reversed video segment:** bake it ONCE with the bundled ffmpeg-kit into a cache (pattern: the existing
remux-to-seekable cache), then ping-pong = playlist alternation [fwd, rev, fwd, …] — and EXPORT uses the SAME
baked file, so preview==export by construction.

## PART 3 — MILESTONES

### L1 — NORMAL loop rides the gapless engine (Sonnet, medium effort)
- `MasterPlaybackEngine.isEligible`: ACCEPT clips with `loopMode==LOOP_MODE_NORMAL` (still reject
  PING_PONG/STILL until L2/L3; transitions/images unchanged).
- Playlist build: for a NORMAL-loop clip emit before-reps + main pass + after-reps. Rep-duration clamping must
  MIRROR ExportManager ~866-873 EXACTLY (ceil reps; clamp so reps sum to extensionMs; note export clamps the
  partial rep and orders 'before' reps so the PARTIAL rep is furthest from the main pass — read `isBefore`
  handling and match) — this is what keeps preview time == export time.
- Window→timeline mapping: engine's window→clip table gains (clip, repIndex) entries; absolute-position math
  uses `getVisualDurationMs()` for looped clips (see activity ~6929 for the established convention).
- Seam listener: same-clip repeat transitions must NOT re-fire clip-changed side effects (caption rebind etc.)
  — suppress when clipIndex is unchanged, or make handlers idempotent.
- Bypass the poll-based NORMAL wrap block (activity ~6857-6895) when `gapless()` — legacy path keeps it.
- DEVICE ACCEPTANCE (Note 9, sandbox): loop wrap shows ZERO frozen frames (ffmpeg freezedetect, the M-COMP-0
  method); playhead advances continuously through extensions; a project with one looped clip keeps gapless
  seams everywhere else (fix for root cause 4); flag OFF = today's behavior.

### L2 — True ping-pong via reversed-segment bake (OPUS — the hard one)
- New `export/ReversedSegmentCache.java` (mirror FragmentedMp4Remuxer's cache/lifecycle): key =
  sourceUri + inPointMs + outPointMs (speed NOT in key — applied at play time). Bake with bundled ffmpeg-kit:
  fast-seek `-ss <in> -to <out>` before `-i`, then `-vf reverse,setpts=PTS-STARTPTS -af areverse,asetpts=PTS-STARTPTS`.
  reverse buffers the whole segment in memory → GUARD: segment > ~30s ⇒ don't bake; keep forward-tail
  fallback + one-time toast ("Reverse for long loops coming later"). Bake OFF-MAIN (mirror ExportService's
  remux warm pattern); trim changes invalidate via the key.
- Preview: eligibility accepts PING_PONG once its reversed file is cached; playlist alternates fwd/rev with
  the SAME rep-clamp math; until the bake completes, play forward-tail (today's export behavior) — NEVER
  `setPlaybackSpeed(-1f)`; DELETE that path (root cause 2).
- Export: `buildLoopExtensionItem`'s reverse branch swaps the forward-tail fake for the SAME cached reversed
  file (map `[startMs,endMs]` into reversed-file coordinates: revStart = outPoint - endMs, revEnd =
  outPoint - startMs, both minus inPoint offset of the bake). Preview==export by construction (fixes 3).
- DEVICE ACCEPTANCE: frame-extract a ping-pong wrap — a moving object's positions must INVERT sequence on the
  reverse leg (true reverse, not tail-replay); export the same project → sampled frames match preview;
  audio on reverse legs = areverse'd (weird by nature — confirm inoffensive, consider mute-on-reverse-leg as
  a drawer option later).

### L3 — UX polish "easy, stable, intuitive" (Sonnet, low)
- Loop drawer (activity ~4031-4102, loop_mode_* views) + timeline extension drag (EditorTimelineView, ~18 loop
  refs) already exist. Verify extension-edge drag works post-L1; add live numeric readout while dragging
  ("+2.4s ≈ 3 loops"); STILL unchanged; small-screen safe (no clipping).

## PART 4 — SEQUENCING + OWNERSHIP
L1 → L2 → L3, strictly (L2 builds on L1's playlist expansion). Files: MasterPlaybackEngine,
FaditorPlayerManager, FaditorEditorActivity (loop tick + drawer), ExportManager (L2 only), new
ReversedSegmentCache (L2). ⚠️ Coordination: these files overlap the editor god-files — only ONE harness/agent
on this track at a time; the 14:25 scheduled session should take rebrand/dedup/M11/purple-linkage and check
`git status` before touching FaditorEditorActivity.

## Status
- [x] L1 (2026-07-02, device-verified Note 9 sandbox bdd51919…: gapless wrap + exhaustion freeze-free,
  pause/trim/undo preserved, seam-clobber fix holds under loops; see handoff.md for full evidence and the
  one open follow-up (totalEffectiveMs() loop-duration bug, pre-existing, spawned separately) — L2/L3 untouched)
- [ ] L2  - [ ] L3
