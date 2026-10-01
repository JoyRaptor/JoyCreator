# Paper work: who does what (dispatch sheet), version 2

Rewritten by the paper specialist (Claude), 2026-10-01, after an adversarial audit of round 1. Version 1 promised that rows could
"build it first" and run "at the same time". The specs did not back that up, and six rows stopped. **Version 2 is serial by gates:
a row starts only when every row it needs has LANDED on `origin/joy-creator`. No row ever builds another row's files.**
The design is `research/R10_paper_and_canvas.md`.

## Where things stand

| Row | What | State |
|---|---|---|
| JB-9.01 | height → slopes → packed surface | ✅ landed |
| JB-9.02 | hex sampler (no-repeat maths), CPU twin | ✅ landed |
| JB-9.03 | pencil + Sable on a real, non-repeating paper | ✅ landed, ⚠️ not yet seen on the Note 9 |
| JB-9.09 | brush paper values in the files (sliders hidden by 9.03b) | ✅ landed (its folder's XML: 1357/0, not the 1339 in its report) |
| JB-9.11 | imported textures → surfaces, core half | ✅ landed; the rest moves to JB-1.05d |
| JB-9.03b | audit fixes | 🟦 next for Codex |
| JB-9.04 | paper catalogue | 🟦 unblocked (the stray `detailStrength` key is gone) |
| JB-9.05 | document paper v4 | ⏳ gate: 9.04 |
| JB-9.06 | paper on screen + export + zoom | ⏳ gates: 9.03b, 9.04, 9.05 |
| JB-9.08 | directional + wet deposit | ⏳ gates: 9.06 (and 9.09 ✅) |
| JB-9.07 | the Paper swatch + sheet | ⏳ gate: 9.06; **Joy Brush Lead only** |
| JB-9.10 | picture candidates (Codex) → library (specialist) | 🟦 any time; needs no build |

## The lanes

| Lane | Do now | Then |
|---|---|---|
| **Codex** | **JB-9.03b** | While waiting on gates: **JB-9.10 candidates** (no build needed). Then **JB-9.06** when 9.04 + 9.05 have landed, then **JB-9.08** |
| **OpenCode** (one agent at a time) | **JB-9.04**: finish the work already in `%TEMP%\jb-9.04`, rebased onto this commit | **JB-9.05** |
| **Claude (specialist)** | Review each landing; pick and pack papers from Codex's candidates | Paper Lab pilot |
| **Joy Brush Lead** | — | **JB-9.07** after 9.06; installs on the Note 9 (only after the owner presses Home) |

## House rules (all lanes)

1. **One build at a time.** The machine has ~1 GB free. Before ANY Gradle run, take the lock: create
   `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock` holding your row id and the time. If it already exists and is under 45 minutes old,
   do NOT build: do non-build work (reading, writing tests, pictures) and try again later. If it is older than 45 minutes, it is stale:
   replace it. Always run with `--no-daemon`. Delete the lock when your run ends, pass or fail.
2. **Worktree only**, under `%TEMP%\jb-<row>`. Copy `local.properties` (root and `joybrush/`) and `tools/devices.local.sh`, and `cd` into it.
   Never run Gradle in the main folder. **One exception:** picture candidates (JB-9.10) are written straight into the main folder's
   git-ignored `joybrush/tools/paper/candidates/`, because they are not code.
3. **Counts come from YOUR worktree's XML**, with the time stamp of its newest file (`joybrush/core/build/test-results/jvmTest/*.xml`).
4. **Hot files** (`GlPaintEngine.kt`, `JbCanvasView.kt`, `JoyBrushActivity.kt`, `joybrush/shaders/*`): only Codex, only in rows that list them, and
   **append** one line under "Questions for the Lead" in `LEAD_DESK.md`. Never edit that file's instructions (round 1 overwrote them; repaired).
5. **Never install** on a phone. Leave the APK path in your report.
6. If a spec is wrong or unclear, write under its "Questions" and move to your lane's next non-blocked work. Do not guess. Do not build
   another row's files.
7. Commit "<row>: …", rebase on `origin/joy-creator`, `git push origin HEAD:joy-creator`. Commit messages say "the owner", never a first name.
8. After pushing, update the owner's main folder: `git -C C:/+Projects/Screenrecorder/FadCam merge --ff-only origin/joy-creator`. It refuses
   rather than overwrite anything. If it refuses, say so in the report and leave it alone.

## Codex also makes pictures (JB-9.10)
Codex has image generation and strong 3D. **The specialist keeps taste and the final say.** Codex supplies raw material only:
- **Looks by image generation:** rice paper (cool white + warm cream), Thai sugarcane pulp, papyrus, aged parchment, tan construction paper,
  two chalkboard dusts, blueprint mottling. Shot flat, straight down, even light, no shadows, no curl or edges, **tileable** (seamless on all
  four sides; check with a 2×2 repeat). 1024², sRGB PNG, into `candidates/looks/`, 3–4 each.
- **Surfaces by 3D:** height maps from real geometry where noise looks fake: crumpled paper ×2, canvas weaves ×3 (fine linen, cotton duck,
  rough jute; twisted threads with slubs and knots), papyrus strips, silk and fabric. Orthographic top-down HEIGHT render, 16-bit greyscale,
  tileable, 1024², into `candidates/surfaces/`.
- Notes on how each was made go in `candidates/NOTES.md`. The owner's reference photos are in `joybrush/tools/paper/reference/`: study only,
  never ship. Reference notes (rice paper is flat, its whiter fibres more opaque, not raised; sugarcane has brown/grey-brown chunks) are in
  `specs/JB-9.10_paper_library.md`.
- Codex does NOT pack, choose or catalogue.

## Prompt for Codex
> You are building Joy Brush paper rows in `C:\+Projects\Screenrecorder\FadCam`. Read `tasks/joybrush/PAPER_DISPATCH.md` (version 2) in
> full, then the spec of your next row: start with `tasks/joybrush/specs/JB-9.03b_paper_audit_fixes.md`. Follow the house rules exactly,
> above all the build lock and `--no-daemon`. While a gate is closed, make JB-9.10 picture candidates. Report each row as one plain line:
> what changed, the command, the counts from your worktree's XML and its time stamp, and the mutation check.

## Prompt for OpenCode (one agent)
> You are building one Joy Brush row in `C:\+Projects\Screenrecorder\FadCam`: first **JB-9.04**. Your earlier work is in `%TEMP%\jb-9.04`.
> Rebase it onto `origin/joy-creator` (the stray `detailStrength` key is now deleted). Read `tasks/joybrush/PAPER_DISPATCH.md` (version 2)
> and the spec's answers under "Questions". Follow the house rules exactly, above all the build lock and `--no-daemon`. When JB-9.04 has
> landed, do **JB-9.05** the same way. Report: what changed, the command, the counts from your worktree's XML and its time stamp, the mutation check.
