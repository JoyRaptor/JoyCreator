# Paper work: who does what (dispatch sheet)

Written by the paper specialist (Claude), 2026-10-01, before a cooldown. The design is
`research/R10_paper_and_canvas.md`. Each job below is one spec in `specs/`. **Paste the prompt for a lane into that harness.**
When a lane finishes a row, it moves to the next row in ITS column. Rows in different columns can run at the same time.

| Order | Codex (Sol 6.1) | OpenCode free agents | Claude (specialist, after cooldown) | Joy Brush Lead |
|---|---|---|---|---|
| 1 | **JB-9.03** quick win: pencil + Sable on a real, non-repeating paper | **JB-9.01** height → slopes | Review everything landed | — |
| 2 | **JB-9.06** paper on screen, swappable, export, zoom | **JB-9.04** paper catalogue | **JB-9.10** paper library (rice, sugarcane, …) | **JB-9.07** Paper swatch + sheet (or Codex, if the Lead is on cooldown) |
| 3 | **JB-9.08** directional + wet deposit, then **JB-9.10 picture making** (see below) | **JB-9.02** hex sampler CPU twin | Paper Lab pilot (optional) | Installs on the Note 9 |
| 4 | — | **JB-9.05** document paper v4 | | |
| 5 | — | **JB-9.09** brush paper sliders | | |
| 6 | — | **JB-9.11** imported textures → surfaces | | |

Dependencies are written in each spec. Where a Codex row needs an OpenCode row that has not landed, the spec says "build it first, in its own commit".
Whoever lands first wins, and the other rebases.

## Codex also makes pictures (owner, 2026-10-01)
Codex has image generation and strong 3D. The **specialist keeps taste and the final say**; Codex supplies raw material for JB-9.10:
- **Looks by image generation:** rice paper (cool white + warm cream), Thai sugarcane pulp, papyrus, aged parchment, tan construction
  paper, two chalkboard dusts, blueprint mottling. Shot flat, straight down, even light, no shadows, no curl or edges, and
  **tileable** (seamless at all four sides; check with a 2×2 repeat). 1024², sRGB PNG, into `joybrush/tools/paper/candidates/looks/`,
  3–4 candidates each. Reference notes are in `specs/JB-9.10_paper_library.md`. The owner's reference photos (not in git; they are
  other people's photos, so use them for study only and never ship them) are in `C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/reference/`.
  Candidates are also kept out of git (`candidates/` is ignored). Leave them in the MAIN folder's `joybrush/tools/paper/candidates/` so the specialist finds them.
- **Surfaces by 3D:** height maps rendered from real geometry where noise looks fake: crumpled paper ×2 (cloth/paper crumple then
  flatten), canvas weaves ×3 (modelled twisted threads with slubs and knots; fine linen, cotton duck, rough jute), papyrus strips,
  silk and fabric weaves. Orthographic top-down HEIGHT render, 16-bit greyscale, tileable, 1024², into `tools/paper/candidates/surfaces/`.
- Codex does NOT pack, choose or catalogue. The specialist picks, runs `tools/paper/pack.py`, and adds the catalogue entries.

## Prompt for Codex
> You are building Joy Brush paper rows in the repo at `C:\+Projects\Screenrecorder\FadCam`. Read
> `tasks/joybrush/PAPER_DISPATCH.md`, then `tasks/joybrush/research/R10_paper_and_canvas.md`, then the spec for your next row in the
> Codex column (start with `tasks/joybrush/specs/JB-9.03_paper_grain_quick_win.md`). Rules: work ONLY in a git worktree under
> `%TEMP%\jb-<row>` (never run Gradle in the main folder), copy `local.properties` (root and `joybrush/`) and `tools/devices.local.sh`
> into it, and `cd` into it before Gradle. Follow the spec's hot-file rule. NEVER install on a phone. Commit with the row id, rebase on
> `origin/joy-creator`, `git push origin HEAD:joy-creator`. Commit messages say "the owner", never a first name. Report each row as one
> plain line: what changed, the exact command, the test counts from the XML. If the spec is wrong or unclear, write under its
> "Questions" section and continue with the next row.

## Prompt for OpenCode free agents (one agent per row)
> You are building one Joy Brush row: `tasks/joybrush/specs/<ROW>.md` in the repo at `C:\+Projects\Screenrecorder\FadCam`. Read
> `tasks/joybrush/PAPER_DISPATCH.md` and the spec in full. Write the tests FIRST, exactly as the spec lists them. Work ONLY in a git
> worktree under `%TEMP%\jb-<row>`, `cd` into it, and copy `local.properties` (root and `joybrush/`) into it. Touch only the spec's
> owner-area files. Run the spec's command and read the counts from `joybrush/core/build/test-results/jvmTest/*.xml`. Do the spec's
> mutation check and say whether it went red. Commit "<ROW>: …", rebase on `origin/joy-creator`, push with `git push origin HEAD:joy-creator`.
> Commit messages say "the owner", never a first name. If anything in the spec is unclear, write it under "Questions" and stop that
> row; do not guess.

## After each landing
The specialist (or the Lead) re-runs the suites in a clean worktree before a row is called done (LEAD_DESK order 1).
