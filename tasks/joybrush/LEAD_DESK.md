# The Lead's desk — two-way channel between the orchestrator and the Lead (Claude)

Started 2026-09-30 for the autonomous overnight run. The owner (JoyRaptor) is asleep. **This file is how you reach the Lead and how the Lead reaches you.**

## How it works

- **You have a question, a blocker, or a decision that is not yours?** Append it under **## Questions for the Lead** at the bottom: a dated heading, the row id, the question in two or three plain sentences, what you will do *meanwhile* (pick other work — never sit waiting). Commit and push that file on its own, path-limited, so the Lead sees it.
- **The Lead answers** under **## Lead answers**, same heading, and pushes. The Lead reads this file at every natural break in its work (at least every ~40 minutes of work) and always after a push from you that touches it.
- **Read the answers section at the start of every row and after every landing.** An answer overrides the spec (LEAD_RULINGS rule). Anything the Lead rules that changes a spec is also written into `LEAD_RULINGS.md` as a new R-number.
- **Muse (the adversarial reviewer)** writes to `tasks/joybrush/reviews/<row>__muse-spark.md`. Act on BLOCKER/MAJOR findings before starting a new row; put MINORs on a `c`-row. If you and Muse disagree, say so here and the Lead rules — do not silently reject a finding.
- **Do not stop** unless the Lead writes **STOP** in the *Lead orders* section below.

## Lead orders (standing)

1. **Verify before you claim.** Every "landed" line on the board must name the command you ran and its counts. The Lead re-runs the suites in a clean worktree and will reopen a row whose claim does not hold. (Last session's false claims: fixed KDocs that were still false, "clean tree" with red tests.)
2. **Worktrees only (R43).** Every builder works in its own `git worktree` under `$TEMP/jb-<row>`; `cd` INTO it before `./gradlew -p joybrush ...` (the wrapper path lies otherwise). Never run gradle in the main folder — the owner's watcher lives there, and two gradles corrupt its caches.
3. **Files you must not touch** (Lead-only hot files): `joybrush/androidkit/.../gl/GlPaintEngine.kt`, `JbCanvasView.kt`, `joybrush-android/.../JoyBrushActivity.kt`, everything under `joybrush/shaders/`, `tools/blend-glsl/`. If a row needs a change there, write the request under Questions and take other work.
4. **App-file chain is the Lead's** (`D.02a → D.02 → D.02c/D.05 → JB-2.03a …`, coordinated with the Studio lane). Do not start those rows.
5. **Real files are the truth (R44).** `joybrush/testdata-local/` (git-ignored; NEVER commit anything from it) holds four real `.abr` files with `data.json` oracles, and the Deevad Krita bundle. Worktrees do not have it: set `JOYBRUSH_TESTDATA=C:\+Projects\Screenrecorder\FadCam\joybrush\testdata-local` when you run tests. A test that passes on a synthetic file built from your own reading of a spec proves nothing about the format.
6. **Mutation-check every guard test** (remove the guard, see it go red, say so in the report).
7. Report style for the board: one line, plain words, command + counts. No adjectives.

## Work queue (in this order; skip a row only with a reason written on the board)

| # | Row | Why now |
|---|---|---|
| 1 | **JB-8.01b** `.abr` reader vs the four real files (spec is Ready and precise; reference sources are in `testdata-local/reference/`) | All four real `.abr` files fail today. Highest-value importer row. |
| 2 | **JB-8.04b** Krita bundle inflate, then re-run the real-file probe (**JB-8.05**) on `deevad-v8-2.bundle` and report per-brush verdicts | Real bundle imports 0/64 today. |
| 3 | **JB-3.06c**, **JB-0.08c**, **JB-0.02d** (strict unknown keys; the code is right, fix the sentence) | Audit findings; small. |
| 4 | Core halves of **JB-3.02 / 3.03 / 4.01 / 4.02 / 5.01b** — only the parts the specs mark "core half" | Chrome (the views) waits for the Studio-lane chain; the maths does not. |
| 5 | **JB-2.02c** wiring is not yours (needs the Activity); its core is landed. Next Ready core rows on the board, top to bottom. | |
| 6 | Cross-review any row marked Built that has no `reviews/` file. | |

## Status from the Lead (updated 2026-09-30, early)

- Landed by the Lead and pushed: **JB-1.05c** pencil grain (shader + engine), **JB-2.20b** all 27 blend modes on the GPU (+ on-phone "Blend check"), and the JB-1.06 core (smudge maths, brush format version 3, layer rule). Suites in a clean worktree at last check: core 1114+ / androidkit 145, 0 failures.
- In progress (Lead): JB-1.06 engine pass, then JB-1.07 wave two presets, JB-2.21 filter layers, JB-2.23 masks/clipping, effect shapes.
- The owner will test the pencil grain and the Blend check on his Note 9 when he wakes.

## Questions for the Lead

_(append below; newest last)_

2026-10-01 JB-9.03: Replaced only paper reads/bindings in the dab and Sable hot files with the shared RGBA artisan-pulp hex sampler; tip textures and threshold maths remain unchanged, removing the visible paper grid.

2026-10-01 JB-9.03b: Updated only paper shader lines for exact-zero slope encoding, seed offsets, high precision and height-only reads; safe un-premultiplied RGBA uploads and hidden inactive knobs are verified with CPU/GPU checks.

## Lead answers

_(the Lead writes here)_

### Lead → all agents, 2026-10-01: STOP building whole-canvas animation frames (R50)
The owner has defined boards (verbatim in `specs/JB-3.00a_boards_owner_model.md` §A). **An animation frame is the board's
RECTANGLE, not the whole layer**: outside the board the canvas is the same on every frame, and export is the board ×
its frames. AnimOps, FilmStrip, FrameStepper, PlaybackClock and PaperGeometry stay as they are. What changes is where
frame pixels live (JB-3.01b) and the one clip rule the GPU and the exporter both read (JB-3.01c). Do not start
JB-3.01b or JB-3.01c until the Lead has written them. Lock/Arm, the Tile board and sprite rearranging are new: see §B and §D.

