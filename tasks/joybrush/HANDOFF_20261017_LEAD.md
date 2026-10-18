# JoyBrush Lead handoff — 2026-10-17 night (Claude Opus lead → next orchestrator)

The owner (JoyRaptor, an artist, not a developer) asked for a handoff because this lead's context is long. The next orchestrator may be the
"Opus helper for Joybrush engine" session, which has a fresh context. Read this file first, then LEAD_PLAN_20261016.md (owner
direction, lanes, progress log) and the specs named below. Older Codex HANDOFF_2026101x files are history.

## Owner direction (verbatim themes)
- **First:** "the whole canvas — brush, oil, watercolour on one layer plus animation, tile and all boards — work flawlessly …
  invisible, user-facing, things just work."
- **Editable strokes** are high priority, so he can judge vector quality and the editing experience.
- **Watercolour 10 fps is "atrocious":** he wants 10×. Creases or anything that gives away the sparse tiles is unacceptable.
- **Brush drawer previews "suck".** He wants to see watercolour speed option A (brush instant, water catches up) shown before choosing.
- **/goal (2026-10-17):** "work autonomously to get absolutely everything done in your purview … use Haiku 5.5 subagents liberally …
  adversarially review what you have made … work with your helper; one of you tackle DIGITAL_PEN_SPEC_20261016.md after all the
  other work."
- **Permissions:** bypass mode. The Note 9 is the SANDBOX: install, test and draw freely; never uninstall or clear data. The Note 20
  holds his real projects: never touch it.
- **Merge conflicts:** never resolve them without his OK (START_HERE rule 4). He granted one exception, already used: the
  pencil × atlas shader merge.

## What is on origin/joy-creator (verified, all suites green)
`353b068dc` plus earlier work:
- **Editable lines v1** (NativeLines: live capture, edit re-render, instant undo, save/reopen, clear) + the Select tool wired.
- **Open admission:** consistency checks on open.
- **Tile tools:** smudge, fill pen, eyedropper and the watercolour line wrap on armed Tile boards.
- **Pencil:** lean fix + Proto 2 / Proto Pencil / Proto Mix + tuner.
- **Live water tilt.**
- **Media Atlas v2 B1+B2** (flag OFF), device-proven on the Note 9:
  - atlas == window bit-exact, scrambled == window, broken control caught;
  - evidence in reviews/MEDIA_ATLAS_B2_20261017.md + the device probe JSON.

The phone runs the probe build 1d1d4e19f (atlas off).

The owner has NOT yet hand-tested editable lines / Select / Tile tools / the pencil fix. Ask him for that test; the list is
in the 10-16 night progress log.

## Open branches — state, and what is left
| Branch | State | Next |
|---|---|---|
| `lead/claude-lines2` (2a21d866b) | **Select live drag preview** (lift lines out of their tiles, draw through the box homography, put back; `settleLift()` guards on every read/mutate entry; jb_lift.vert) + the v1.2 work order. `:joybrush-android:testDebugUnitTest` 247/0 pass; androidkit COMPILES, `:androidkit:test` NOT run with it yet | Run `:androidkit:test`, then merge (with or before lines-v12) |
| `lead/lines-v12` (322554843) | **v1.2 hardening** per specs/NATIVE_LINES_V12_HARDENING_20261017.md: H1 (upper pixel slabs) committed b292b3fb9; H2+ partly written, WIP commit 322554843 **UNTESTED** (FillRequest.kt, LineAdmission.kt, guard test, MaskPaint units) | Resume H2–H13 (Sonnet agent a95b88c9f11dce474 can continue via SendMessage when Sonnet quota returns, or a new agent with the work order). Then adversarial review again. |
| `lead/just-works` (c3627d200 + agent WIP) | **Silent failures said in words**, first pass done and verified: undo-trim notice, tiny fill, pen-down layer ops, lost brushes, greyed Export explains; suites 927 / 255 / core 2486 (3 test-arithmetic fixes after). Second pass (10 more: **first media stroke on a fresh drawing paints nothing**, blank rename, folder-copy failure notice …) is being finished by agent ae34e9e030492f05d, which was still running at handoff | Check its report; verify; merge |
| `lead/brush-previews` (2b642fd3e) | **Real-engine drawer previews**: cache/key/LRU/disk-trim files + tests written; renderer + JbCanvasView hook partly; WIP **UNTESTED** | Finish per the agent brief (renderBrushPreview on the GL thread with a throwaway engine like runBlendCheck; media via MediaLayerEngine offscreen) |
| `lead/device-matrix` (2ba653ed8) | **Bench case `matrix`** (every brush × every board kind, JSON per cell) committed; not yet built, installed or run | Build the APK, install on the Note 9, run `am start … --es jb_bench matrix`, collect `logcat -s JoyBrushPerf`, make the table |
| `helper1/media-atlas-b1` (acfe28b50+) | Atlas **B3 steps 1+2** proved headless: long strokes across 36 tiles, LRU f16 write-back eviction, never refused, 0 px vs oracle; 3398/3398 suites | Helper continues B3 steps 3–5 (wet per tile; D1/D2 rules below), step 6 (temporal split), then B4–B6 + media leftovers + watercolour/oil speed |

## Decisions already made (do not reopen without the owner)
- **Editable lines model:** NATIVE_LINES_V1 (tiles stay pixel truth; base ∘ items; instant undo by tile and line swaps). Codex's
  captured/joint-root machinery stays parked; nothing is deleted.
- **v1.2:**
  - upper pixel slabs (non-line strokes on line layers become slabs, so no refusals except media);
  - Rasterize Down = slab at the line's seq;
  - structural/board ops flatten affected planes' items in the same step, with a sentence;
  - delete removes items in the same step.
- **Atlas:**
  - page-table addressing (no halos), 16 slots = 15 resident + 1 staging;
  - unbounded layer space;
  - (ii) the dab frame is derived from cell centres in both builds (a deliberate tiny change);
  - B3 D1: settle only whole quiet connected wet components, farthest first (never cut connected water: creases);
  - D2: a drip's oldest tail dries silently rather than refusing painting;
  - D3: oversize frames split TEMPORALLY by dab order.
- **Digital Pen:** specs/DIGITAL_PEN_SPEC_20261016.md. A stabilizer section separate from the brush (with a per-brush
  suggestion); records keep raw samples + the exact stabilizer.

## Remaining list (the /goal), in order
1. Finish lines-v12 (H2–H13). Adversarial review → merge with claude-lines2 → integration build → Note 9 device replay
   proof (re-render == live).
2. Copy/paste of lines (App.copySelection today says "Copy is coming next"; add a Paste entry). Lines on Tile boards
   (prepareRecordedLine refuses `tileBoardId != null`).
3. Merge just-works (second pass) and brush previews; the device matrix run; then fix what the matrix shows.
4. Layer operations: merge down, flatten (incl. media payload and lines/slabs), lock button, apply mask, whole-drawing export.
5. Atlas B3–B6 (helper), media leftovers (board-following, Sprite, dried-water save, slice-2 bake), watercolour 10× + the A/B
   for the owner, oil profile, pencil 20–50 ms hitches.
6. Re-test the owner's Oct 2 reports with him (smudge, fill pen, Soft Air, paper textures). Proto Pencil sign-off.
7. Then the Digital Pen spec (D1–D10). One of lead/helper builds it.

## How to work here (rules learned)
- **Worktrees only** (C:/Temp/jb-*), never the main folder. The main folder has a peer's auto-builder (Gradle client 33328 /
  daemon 28356, since 10-16): leave it.
- **Builds:** one at a time machine-wide via `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock` (bash
  `( set -o noclobber; echo name > LOCK )`); ≥2.4–2.6 GB free RAM for tests, ≥3 GB for the APK; `--offline
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`.
  - Lead helper script: `<scratchpad>/lead-build.sh <worktree> <minMB> <log> <tasks…>`.
  - Stale locks happen when an agent is cut off by a usage limit. A janitor (`<scratchpad>/lock-janitor.sh`) frees a lock
    older than 60 min when no Gradle client started after it. It dies with the session, so re-run it if needed.
  - Old stale locks are kept as `jb-gradle.lock.stale-*`.
- **Phone:** adb at `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`, Note 9 = SM-N960U by serial. KEYCODE_HOME first if
  Joy is in front; `am start` (never monkey); never uninstall or clear data.
- **Publishing:** push verified work to joy-creator only when it's a fast-forward of origin. The owner's main folder may hold
  a peer's unpushed commits: never merge over them; it gets fast-forwarded when they push.
- **Agents:** Sonnet subagents hit the account's weekly Sonnet limit on 10-17 (resets Oct 23, 6am). Haiku and Opus may still
  be available. Every agent claim gets verified against git/tests before it's reported ([[verify-agent-work-report-plainly]]).
- **Reporting to the owner:** plain language, emoji status tables, end-user terms. He can't read code.
