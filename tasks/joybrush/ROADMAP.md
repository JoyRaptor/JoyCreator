# JOY BRUSH — ROADMAP AND TASK BOARD (the one file to point an agent at)

> **Agent: this file is your whole briefing.** Read it top to bottom, then do what §2 says.
> You do not need to read the rest of the repo unless your task's spec tells you to.

---

## 1. Who are you? Pick your role

The person who started you will usually say it ("you are a design builder"). If not, pick the
**highest** role you honestly meet.

| Role | Tier | You are… | You may take tasks tagged |
|---|---|---|---|
| **Code builder** | T2 | a capable coding model; vision not needed | `T2` |
| **Design builder** | T2-V | a coding model **with strong vision/design ability** that can look at screenshots | `T2-V`, `T2` |
| **Spec writer** | T2+ | a strong model asked to extend the runway | turns `⚪ Outline` rows into draft specs (§4) |
| **Cross-reviewer** | T2+ | a strong model of a DIFFERENT family from the spec's writer/builder | reviews `🟧 Built` or draft specs (§5) |
| **Adversarial reviewer** | T2+ | a strong model, different family from the builder, told to BREAK things | reads `🟧 Built` work and files findings; never edits code (§5b) |
| **Lead** | T1 | Claude (frontier) | anything; owns `T1` rows and final review |
| **Owner** | T3 | JoyRaptor, on the phone | `📱` checks and `✅` sign-off — nobody else |

If you have no vision, never take `T2-V`. If a task is `T1`, only Claude takes it.

## 2. The loop (every builder, every time)

1. `git pull` (branch **`joy-creator`**). If git reports a conflict: **STOP** and tell the owner.
   Never resolve a merge conflict (START_HERE.md rule 4).
2. Read `START_HERE.md` rules 1–10 once per session. **One exception to rule 2 ("never run
   gradle") for Joy Brush:** `./gradlew -p joybrush :core:jvmTest` and
   `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` are allowed — they build only the
   standalone `joybrush/` project, install nothing and never touch the app build. Anything in the
   app or in `joybrush-android/` is verified by the watcher's `build.log`, never by running gradle.
3. Find the **first** row in the board (§6) where: status is `🟦 Ready`, your role may take its
   tier, and every task in "Needs" is at `🟧 Built` or better.
4. **Claim it:** edit that row — status `🟨 Claimed`, fill "Who" with your model name and the date.
   Commit just that change (`JB-x.yy: claim`) and push **before** starting, so no one else takes it.
   If the push fails because someone else pushed: pull, re-check the row, pick another if taken.
5. Open the spec file linked in the row. Do **exactly** that spec. It lists the only files you may
   touch, the tests, and the command that proves it.
6. When the spec's tests pass: set the row to `🟧 Built`, paste nothing else here. Commit your work
   plus the row change (`JB-x.yy: <what>`), push.
7. If you get stuck, or the spec seems wrong: set the row to `⛔ Blocked`, write your question in the
   spec's **Questions** section, commit, push, and pick another task. **Never guess.**
8. Go back to step 3 until nothing is left for your tier.

## 3. What the statuses mean (the progress ladder)

| Status | Meaning | Who sets it |
|---|---|---|
| ⚪ Outline | Idea is scheduled but no spec exists yet | Lead |
| 📝 Draft spec | A spec exists but has not been checked | Spec writer |
| 🟦 Ready | Spec checked; anyone of the right tier may take it | Lead or cross-reviewer |
| 🟨 Claimed | Someone is working on it (see "Who") | Builder |
| ⛔ Blocked | Stopped with a question in the spec | Builder |
| 🟧 Built | Code done, the spec's automated tests pass | Builder |
| 🟩 Reviewed | Code read and approved by the Lead (or a cross-reviewer, marked "xr") | Lead / cross-reviewer |
| 📱 On phone | Owner has tried it on the Note 9 and it works | Owner |
| ✅ Signed off | Owner says it is finished | Owner only |

"Done" means ✅. Everything before that is progress, and is reported as such, never as "done".

## 4. Spec writer: extending the runway
Take the first `⚪ Outline` row whose "Needs" are all `🟧 Built` or better. Write
`tasks/joybrush/specs/JB-x.yy_<name>.md` from `specs/SPEC_TEMPLATE.md`, following
`specs/README.md` §3 (owner area, contract verbatim, every decision made, tests, do-not list, stop
rule). Read the blueprint (`JOYBRUSH_BLUEPRINT.md`), the owner's rulings (`OWNER_CONSTRAINTS.md`)
and the specs it depends on. Set the row to `📝 Draft spec` and put your model in "Who".
Never contradict the blueprint or the owner's rulings; if they seem wrong, write a question instead.

## 5. Cross-reviewer
- **Draft spec → Ready:** check it against the template and the blueprint; check that a builder could
  finish it without making a design decision. Fix small gaps; if it is fine, set `🟦 Ready` and add
  "xr: <your model>" to "Who". You must be a different model family from the writer.
- **Built → Reviewed (xr):** read the diff; run the spec's command yourself; check nothing outside the
  owner area changed. Approve as `🟩 Reviewed (xr)` or send back to `🟦 Ready` with notes in the spec.
- The Lead (Claude) re-checks xr approvals when it has budget, T1 rows first.

## 5b. Adversarial reviewers (read-only)
Two reviewers from DIFFERENT model families may attack the same `🟧 Built` task independently.
- **Never edit code, specs or this board.** Write findings only to
  `tasks/joybrush/reviews/<task-id>__<your-model>.md` (one file per reviewer per task).
- **Every finding needs proof:** either a failing test (paste its code and output), or the exact
  `file:line`, the input that breaks it, and what goes wrong. No proof → don't file it.
- Each finding gets a severity: **BLOCKER** (wrong result, data loss, crash, contract broken),
  **MAJOR** (fails an edge case the spec names), **MINOR** (clarity, naming). Also state whether it
  contradicts the spec — "the spec is wrong" is a valid finding, sent to the Lead, never "fixed".
- Allowed commands: reading files and `./gradlew -p joybrush :core:jvmTest` /
  `:androidkit:compileKotlin :androidkit:test`. Put any test you wrote inside the findings file,
  never in the source tree.

**The orchestrator triages** (the reviewers never decide):
1. A finding with a failing test that reproduces for the orchestrator → fix it (a subagent does it
   within that spec's owner area), add the test to the suite, note it in the log.
2. The same issue found independently by both reviewers → treat as reproduced; fix it.
3. Anything that disagrees with a spec, `JOYBRUSH_BLUEPRINT.md` or `OWNER_CONSTRAINTS.md` → copy it to
   the spec's **Questions** as "for the Lead", status unchanged. Never "fix" a spec by changing code.
4. Everything else → recorded in the findings file as "not reproduced", no action.
A task only moves to `🟩 Reviewed (xr)` when no BLOCKER or MAJOR finding is open against it.

## 6. The board

Columns: **Task** (link to spec) · **Tier** · **Needs** · **Status** · **Who** (model, date).
Phases follow `JOYBRUSH_BLUEPRINT.md` §4. Each phase ends with an owner check (T3).

> **App-file work is serialised (Lead, 2026-09-29):** `D.02a → D.02 → D.02c / D.05`, **one at a time.**
> Never two of these in the tree together, and never alongside `JB-0.09`. `D.02a` and `D.02` are
> Lead-owned — do not dispatch them.

### Phase 0 — Foundation: draw, save and reopen on the Note 9
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-0.01 Pen-sample & stroke contracts, context-aware smoothing, tip direction, curves (`joybrush/core`) | T1 | — | 🟧 Built | Claude 2026-09-28 |
| [JB-0.02](specs/JB-0.02_document_model.md) Document model, boards, layers, cels, file layout | T2 | 0.01 | 🟧 Built | space bunny agent #1 2026-09-28 |
| [JB-0.03](specs/JB-0.03_brush_preset_and_dynamics.md) Brush preset format + dynamics evaluator | T2 | 0.01 | 🟧 Built | stealth/space-bunny-alpha 2026-09-28 |
| [JB-0.02b](specs/JB-0.02b_enum_version_rule.md) "New enum constant ⇒ version bump" rule: docs + guard test (LEAD_RULINGS R3) | T2 | 0.02, 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.03b](specs/JB-0.03b_brush_validation_hardening.md) Brush validation hardening (Lead's rulings on 0.03's questions) + the size = Infinity BLOCKER as a named test, `size.base ≤ 4096` (LEAD_RULINGS R1) | T2 | 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.04](specs/JB-0.04_stroke_codec.md) Stroke recording codec | T2 | 0.01 | 🟧 Built | space bunny agent #3 2026-09-28 |
| [JB-0.05](specs/JB-0.05_android_module_and_input.md) Android module in the app build + Joy Brush screen hosting the engine's drawing view | T2 (T1 review) | 0.01, 0.07 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — **the watcher is ALIVE again** (build.log 2026-09-28 21:52:58, `:joybrush-android:compileDebugKotlin` UP-TO-DATE inside `BUILD SUCCESSFUL`; the earlier "watcher dead" note was a stale clock, not a dead build). The 5 build-file edits are verbatim the spec's list. Spec step 2 (open it on the sandbox phone) is still outstanding — no adb here — so the device half of the check is the owner's: it is the `📱` row's job, not this row's. T1 review still owed before 🟩 (it edits the app build files) |
| [JB-0.06](specs/JB-0.06_pen_diagnostics.md) Hidden pen-diagnostics overlay (raw pressure/tilt/lean, sample-rate histogram, copy report) | T2 | 0.05 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — long-press "×" toggles it; watcher green with `:joybrush:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` both EXECUTED (build.log 22:21:11). The sandbox screenshot (overlay live, tilt changing) is still owed — no adb here. 🔴 For the Lead: `androidkit` has no Android resources, so the panel's two colours cannot come from `jb_tokens.xml`; either a debug panel is exempt from D.01's "no hex literal" rule, or the tokens grow a diagnostics pair and the view moves to `joybrush-android` |
| JB-0.07 GPU tile engine: 256² tiles, dab/commit/display shaders, stroke buffer (flow/opacity, wash/build-up), copy-on-write tile undo, pen input, drawing view (`joybrush/androidkit`) — compiled against the Android API, shaders verified in WebGL2, **not yet run on a phone** | T1 | 1.01 | 🟧 Built | Claude 2026-09-28 |
| [JB-0.08a](specs/JB-0.08a_document_archive.md) The `.joybrush` archive: atomic write, read, zip-slip guard (JVM-tested) | T2 | 0.02, 0.04 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.08b](specs/JB-0.08b_save_open_wiring.md) Autosave, reopen, "Save a copy…", "Open…" on the screen | T2 | 0.05, 0.08a | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 + orchestrator — autosave through the archive's **atomic** write (tmp + fsync + rename + `.bak`), so a failed save leaves the old drawing intact; a file the screen cannot show is **refused in words before any GL work** ("this drawing has 3 layers, and this screen holds one"), never half-opened. R11 applied: a save asked for while the pen is down sets a *save owed* flag and writes the moment the stroke ends (never ends the person's stroke to save), and "Save a copy" builds the whole archive in `cacheDir` first, then streams to the Uri. 🔴 For the Lead: **no thumbnail written** (Q7), and **any document with a paper texture will not open here** (Q6 — blocks JB-2.13b). Device check owed: draw → Home → force-stop → reopen |
| JB-0.09 Lobby entry + first screen chrome in Joy Creator's look | T2-V | 0.05, D.01 | ⚪ Outline | |
| JB-0.10 CPU benchmark harness (flood fill, tile compression, PSD write) on the Note 9 | T2 | 0.05 | ⚪ Outline | |
| JB-0.12 Low-latency front buffer + motion prediction, with an off switch | T1 | 0.07 | ⚪ Outline | |
| JB-0.30 📱 Owner check: draw, save, reopen; lag as good as Infinite Painter | T3 | 0.06–0.12 | ⚪ Outline | |

### Design system
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| [D.01](specs/D.01_joybrush_colour_tokens.md) Joy Brush section-colour tokens (one place, ripples everywhere) + board colours | T2-V | 0.05 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — 30 tokens, one owner-editable room pair, 24 mirrors, and `tools/check_joybrush_tokens.py` FAILS the moment a mirror drifts by hand (12-case drill, all correct). Watcher green twice with `compileDebugKotlin` EXECUTED. Q1 (screenshot) rerouted to the owner; Q2 ruled by me (file moved to `src/main/kotlin`); Q3/Q4 open for the Lead |
| [D.02](specs/D.02_studiokit_module.md) Move the shared UI kit pieces into a `:studiokit` module (scrubbable number, slider row, header icon button) + update the test-harness scripts **(REVISED)** | T2-V (T1 review) | D.02a | ⚪ Outline | **Lead-owned, do not dispatch.** REVISED (R20–R24): a pure move into `:studiokit` per R23 "share, don't copy", and it also updates the test-harness scripts. **App-file work — order one at a time: D.02a → D.02 → D.02c / D.05** |
| [D.02a](specs/D.02a_transform_fixes.md) Fix the Studio transform tool's four gesture bugs (before it becomes shared) | T2 (T1 review) + T3 | — | ⚪ Outline | **Lead-owned, do not dispatch.** App-file work (Studio). First in the app-file order: **D.02a → D.02 → D.02c / D.05, one at a time** |
| [D.02c](specs/D.02c_recent_colours_bar.md) Shared app-wide colour history + recent-colours bar (owner: "a must have") | T2-V + T3 | D.02 | 🟦 Ready (after D.02) | **Lead 2026-09-29** (R22, R24) — one history shared by the Studio picker and Joy Brush; a thin bar that grows, divides by history, 12 max. 🟦 dispatch once **D.02** lands; app-file work, one at a time with D.05 |
| [D.05](specs/D.05_share_fx_gradients_blends.md) Move fx (17 effects, gradient ramp/curve), keyframes, BlendModes, MaskSdf, gradient editor into `:studiokit` (share, don't copy — R23) | T2 + T3 | D.02 | 🟦 Ready (after D.02) | **Lead 2026-09-29** (R23, R24) — improvements land once, never copied. 🟦 dispatch once **D.02** lands; app-file work, one at a time with D.02c |
| D.05b Move `FxPanel`, `BlendPickerPopover`, `MaskKeyPanel` (+ resources) with them | T2 | D.05 | ⚪ Outline | Lead designs; spec follows D.05 |

### Phase 1 — The brush engine
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-1.01 Tip shape maths (superellipse corners, taper, aspect to razor, rotation, min width) — `joybrush/shaders/jb_tip.glsl` | T1 | — | 🟧 Built | Claude 2026-09-28 |
| JB-1.02 Grain maths: tip texture + paper grain, height threshold with edge width, tilt-aimed gradient — `joybrush/shaders/jb_grain.glsl` | T1 | — | 🟧 Built | Claude 2026-09-28 |
| [JB-1.03](specs/JB-1.03_cloud_texture.md) Procedural tileable cloud texture generator (deterministic) | T2 | — | 🟧 Built | space bunny agent #4 2026-09-28 |
| [JB-1.04](specs/JB-1.04_brush_dabber.md) BrushDabber: brush file drives every dab (pressure, tilt, speed, deterministic randomness) | T2 | 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-1.05a](specs/JB-1.05a_scatter.md) Scatter and count (jitter → leaves) | T2 | 1.04 | 🟧 Built | space bunny #1 orchestrator 2026-09-28 — set by R13: the code is committed (`9c7cc9a`) and JB-1.05b is built on top of it, so the row was stale. 11 tests |
| [JB-1.05b](specs/JB-1.05b_brushes_in_the_view.md) Brush files drive the drawing view (+ brush picker pill) | T2 (T1 review) | 0.05, 1.04, 1.05a | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — `BrushLibrary` reads `joybrush/brushes/index.txt`, decodes each `brush.json` with the real `BrushJson`, validates with `BrushValidate`; watcher green with `:joybrush:androidkit:compileKotlin` AND `:joybrush-android:compileDebugKotlin` both EXECUTED (build.log 22:33:33). 🔴 Two Lead questions: (1) the spec's second salted `SplitMix` contradicts `Scatter`'s own "same generator" note, and (2) the seed is `uptimeMillis()`, so a preset-drawn stroke **cannot be replayed** — that collides with the blueprint's "strokes are recordings", and it lands squarely on JB-0.08b's save/reopen. Q5 also: nothing on screen scales `size.base` yet, so switching brush changes the apparent size. T1 review owed (it touches the drawing view) |
| JB-1.05c Grain in the dab shader: tip texture + paper grain textures, tilt gradient uniforms (JB-1.02 maths) | T1 | 1.05b, 1.03 | ⚪ Outline | |
| JB-1.06 Smudge & nudge (ONE carried colour per brush — patent rule, blueprint §5) | T1 | 1.05 | ⚪ Outline | |
| JB-1.07 Default presets: Ink, Pencil, Marker, Soft air, Smudge, Nudge, Eraser | T1 + T3 tuning | 1.05, 1.06 | ⚪ Outline | |
| [JB-1.08a](specs/JB-1.08a_fill_pen.md) **Fill pen brush**: `engine "fill"`, `blend "behind"`, outline maths, shipped preset | T2 | 0.03b, 0.01 | 🟦 Ready | **Lead 2026-09-29** (R21, R24) — **the fill pen is a BRUSH, not a tool** (R21); its stroke is a filled shape. 🟦 **dispatch now** (core; no app files). Blocks 2.06b |
| [JB-1.20](specs/JB-1.20_pc_brush_lab.md) PC Brush Lab (one HTML file, WebGL2, runs the shared shaders, pen pressure/tilt, fake rotation) | T2 | 1.01, 1.02 | 🟦 Ready | space bunny #1 orchestrator 2026-09-28 — code landed + JS parses (`node --check`); finish = screenshots via headless Chrome OR owner confirms (LEAD_RULINGS R6); 3 brush-format questions in the spec for the Lead |
| [JB-1.21](specs/JB-1.21_phone_brush_lab.md) Phone Brush Lab: edit brush.json on the PC, the phone reloads it in ~1 s | T2 | 1.05b | 🟦 Ready | |
| JB-1.30 📱 Owner signs off each brush on the Note 9 | T3 | 1.07 | ⚪ Outline | |

### Phase 2 — A real painting app
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-2.01 Screen chrome: control cluster, thumb rail, drawers-on-phone / popovers-on-tablet | T2-V | 0.09, D.02 | ⚪ Outline | |
| [JB-2.02](specs/JB-2.02_view_and_gestures.md) Zoom / pan / rotate (snap to 90°), 2-finger tap undo, 3-finger tap redo, fingers navigate once a pen is seen | T2 (T1 review) | 0.05 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — `ViewTransform` (pure maths, 16 tests) + `CanvasGestures` (pinch/pan/rotate + tap machine). `:core:jvmTest` **410/0** and the watcher green with `:joybrush:androidkit:compileKotlin` EXECUTED. 🔴 Two for the Lead: `Brush.sizePx` is now document px (a "12 px" brush is 48 screen px at 4× — JB-2.16 inherits the decision), and four `Float`s cross to the GL thread un-synchronised, so a pinch can show a one-frame wobble. Six of the builder's eight questions ruled in the spec |
| JB-2.02b Tool finger modes (select · lasso · colour pick) and assignable 2/3-finger gestures | T2 | 2.02, 2.05 | ⚪ Outline | |
| [JB-2.03a](specs/JB-2.03a_colour_pill_and_eyedropper.md) **REVISED** — colour pill + drag-off eyedropper + long-press with cancel (supersedes JB-2.03) | T2 + T3 | D.02, 2.13a | 🟦 Ready (after D.02) | **Lead 2026-09-29** (R22, R24) — eyedropper = **drag off the colour swatch** (fast; drag back to cancel) **and** long-press (visible cancel: slide back to the start circle or a second finger; a setting to turn it off) |
| JB-2.04 Layers panel: paint/ink layers, blend modes, opacity, runtime layer budget | T2-V | 0.07, 2.01 | ⚪ Outline | |
| [JB-2.05a](specs/JB-2.05a_selection_mask.md) Selection masks: lasso (non-zero), rect, ellipse, from-fill, boolean ops | T2 | 0.07, 2.06a | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 (2nd attempt, agent type changed after 1 empty report) — 13 tests, 4×4 supersampled non-zero-winding lasso, copy-on-write tiles. `:core:jvmTest` 509/0. The builder found a **spec** error: test 3's sample breakdown is 6 inside / 3 on-edge / 7 outside, not 6/4/6 — but 6 inside is right, so the asserted range still holds and the KDoc carries the derivation |
| [JB-2.05b](specs/JB-2.05b_transform_and_resample.md) Transform maths: affine, box handles, tile resample | T2 | 2.05a, 2.02 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 — **a homography**, not an affine map: 3×3 projective `Homography` (adjugate inverse, Gaussian `fromQuads`) plus inverse-mapped `Resample` with per-tile minification from the Jacobian. 34 tests, green; the agent found and fixed two of its own bugs by **modelling the intended Kotlin in Python and diffing** — `sampleSpan` refused every uniform scale (discriminant exactly 0, so minification silently never ran), and `warp` sampled the destination without mapping it through the inverse. 🔴 For the Lead: **Decision 5's "±1" split is arithmetically impossible over a source-over backdrop** — splitting an opaque colour into two halves and compositing them over that same colour gives 191, not 255, whatever the split. It is exact over a TRANSPARENT backdrop only |
| [JB-2.05](specs/JB-2.05_selection_transform_ui.md) Selection + transform (one-gesture start, live box, tap outside commits, survives ops) | T1 | 0.07, 2.02, 2.05a, 2.05b | ⚪ Outline | |
| [JB-2.06a](specs/JB-2.06a_flood_fill.md) Fill maths: flood fill with tolerance, gap closing, no fringe | T2 | — | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28 — 3 failures were all TEST bugs, not code (see the spec's orchestrator rulings: the thin-line push-back, the 5x5 diagonal dilation is 19 not 25, and a closed 3x3 ring has 72 in the region, not 73 white) |
| [JB-2.06b](specs/JB-2.06b_fill_tools_on_canvas.md) **REVISED** — tap fill tool + fill pen on paint layers; **the lasso fill/erase tools are GONE** | T2 + T3 | 2.06a, 2.07a, 2.05a, 1.08a, 2.13a | 🟦 Ready (after 1.08a, 2.07a) | **Lead 2026-09-29** (R21, R24) — R21: JB-2.07a (`MaskPaint`) survives as **the fill pen's raster maths**; the old lasso tools are removed. Dispatch **after 1.08a and 2.07a** |
| [JB-2.07a](specs/JB-2.07a_lasso_fill.md) `MaskPaint` raster maths — repurposed as the fill pen's raster maths | T2 | 2.06a, 2.05a | 🟦 Ready | ⚠️ **orchestrator-added 2026-09-29** — the spec file exists but was never on the board; R24 makes it a 2.06b dependency and R21 repurposes it. **Status and Tier are the Lead's to confirm** |
| [JB-2.10](specs/JB-2.10_shape_recognizer.md) Hold-to-shape maths (recognise + perfect, keep pressure/tilt) | T2 | 0.01 | 🟧 Built | **Claude 2026-09-29** (`bab2b0e4`) — the Lead took it over after 4 empty dispatches; R12 says do not split or dispatch it. The maths is available; the hold-to-shape UI is still JB-2.11 ⚪ |
| JB-2.11 Hold-to-shape UI (hold timer, preview, resize before lift) | T2 | 2.10, 1.05 | ⚪ Outline | |
| [JB-2.12a](specs/JB-2.12a_guides_and_snapping.md) Guides: grid / iso / perspective / ruler / ellipse, stroke snapping, visible lines | T2 | 0.01, 2.02 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 (2nd attempt, agent type changed) — 20 tests. **Two real bugs found by me, both invisible by reading:** `best.first`/`.second` on a `Pt` data class (compile error), and Newton's second derivative for the ellipse tracer missing its `(ry²−rx²)(cos²t−sin²t)` term, which made the curvature negative at the minimum and sent the iteration 230 px away from a pen that was 5 px off the curve — so the tracer **never captured** the stroke. Found by instrumenting after a first fix that was also wrong. `:core:jvmTest` 509/0 |
| JB-2.12 Helpers: grid, perspective guides, shape tracers (never exported) | T2-V | 2.01, 2.12a | ⚪ Outline | |
| [JB-2.13a](specs/JB-2.13a_region_renderer.md) RegionRenderer: flatten any rectangle/frame to pixels, all blend modes (CPU) | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-2.13b Paper setting (colour/texture) + export PNG: screen / selection / board, include paper | T2 | 2.13a, 2.14a, 0.08b | ⚪ Outline | |
| [JB-2.14a](specs/JB-2.14a_png_writer.md) PNG writer (exact, JVM-tested) | T2 | — | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 — 17/17 green; review found a real w*h IDAT-cursor bug (infinite loop → OOM) now fixed |
| [JB-2.14b](specs/JB-2.14b_openraster_export.md) Export OpenRaster (.ora) — layers for Krita/GIMP/MyPaint | T2 | 2.13a, 2.14a, 0.08a | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28, finished by the orchestrator — the 2 failures were both the test counting the FIXTURE instead of the output (3 layers ≠ 3 files; and "Loud" at opacity 2f clamps to opaque and IS exported, so 4 omissions and 2 survivors, not 5 and 1). Also fixed a **shared** bug: `:androidkit:test` was failing with `NoClassDefFoundError: JbDocument` for *every* test in the module, because core declared kotlinx-serialization as `implementation` while `JbDocument`/`BrushPreset` are `@Serializable` on its public API — now `api`. That had been silently disabling JB-0.08a's tests too. `:core:jvmTest` 394/0 · `:androidkit:test` 85/0 |
| JB-2.14c Export PSD (own writer, 8-bit layered) | T2 | 2.13a | ⚪ Outline | |
| JB-2.15 Autosave and crash safety (never lose work) | T2 | 0.08 | ⚪ Outline | |
| [JB-2.16a](specs/JB-2.16a_size_opacity_drag.md) Size & opacity drag maths + zoom-scaled nudge | T2 | 0.03b, 2.02 | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 — 16 tests, **green on the first `:core:jvmTest` run** (476/0). Four questions ruled. 🔴 Two for the Lead: `MAX_SIZE = 4096f` is a **local copy** of `BrushValidate.MAX_SIZE_PX` (which is `private`), so nothing can catch the two drifting apart; and `screenPerDoc` IS `ViewTransform.zoom` — pass `view.zoom`, never `1f / view.zoom` |
| JB-2.16 Brush size/opacity by dragging the brush swatch; nudge scaled to zoom | T2 | 2.01, 2.16a | ⚪ Outline | R10 already ruled the hard part: the size control shows the on-screen circle at its true **screen** size (radius × zoom) while dragging, and writes `size.base` in **document** px (screen px ÷ zoom) |
| JB-2.17 Gesture cheat-sheet + first-run hints | T2-V | 2.02 | ⚪ Outline | |
| [JB-2.20a](specs/JB-2.20a_all_blend_modes.md) **The Studio's 26 blend modes** in the document + export maths, golden-table parity, `DOC_VERSION` 2 | T2 | 2.13a, 0.02b | 🟦 Ready | **Lead 2026-09-29** (R23, R24) — core is pure Kotlin and cannot call the Studio's Java, so the maths is **proved equal by a generated golden table + drift check, never copied by eye**. 🟦 **dispatch now** (core; no app files). Needs the `api(...)`/serialization care from JB-2.14b |
| JB-2.20b GL layer compositing with the Studio's `GLSL_BLEND_FN` — **preview = export** | T1 | 2.20a | ⚪ Outline | Lead designs; spec follows 2.20a. Closes the "6 of 8 modes have no GPU implementation" gap referred in turn 5 |
| JB-2.21 **FILTER layers = the Studio's adjustment layer**: an `FxStack` over everything below (blur, levels, colour grade, gradient MAP to remap colours, posterize, duotone…); UI = the Studio's `FxPanel` | T1 engine + T2 UI | D.05, 2.20b | ⚪ Outline | Lead designs; spec follows its deps |
| JB-2.22 Gradient tool: app-wide gradient editor bar (`GradientRampEditorView`) + drag to place linear / radial / curve gradients, baked into the layer, clipped to a selection or a fill-pen shape | T2 | D.05 | ⚪ Outline | Lead designs; spec follows its deps |
| JB-2.22b Fill pen with a **gradient fill** ("set a shape as this", owner) | T2 | 2.22, 1.08a | ⚪ Outline | Lead designs; spec follows its deps |
| JB-2.23 Layer masks and clipping — a fill-pen shape can be a mask; an adjustment layer clipped to a shape = "set a shape as a gradient map" (owner) | T1 | 2.21 | ⚪ Outline | Lead designs; spec follows its deps |
| JB-2.30 📱 Owner finishes a real illustration; then Tab S8 check | T3 | Phase 2 | ⚪ Outline | |

### Phase 3 — Animation board
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| [JB-3.01](specs/JB-3.01_animation_model.md) Animation model ops: add/duplicate/link/delete/move frames, holds, timing | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-3.02 Animation paper: peg bar that doubles as buttons, pixel rulers | T2-V | 3.01, 2.01 | ⚪ Outline | |
| JB-3.03 Film strip: sprockets, ± actions, drag a frame's edge to hold it, finger scrub | T2-V | 3.01 | ⚪ Outline | |
| JB-3.04 Onion skin as ONE shared component (extracted from SpriteLab, same settings) | T1 | 3.01 | ⚪ Outline | |
| [JB-3.05a](specs/JB-3.05a_playback_clock.md) Playback clock: loop / ping-pong / once, range, audio position, no drift | T2 | 3.01 | 🟧 Built | **built by the orchestrator**, not a 4th dispatch — three dispatches returned nothing, and the transcript showed the last one dying **mid-sentence** in an unresolvable spec ambiguity (Decision 3 never says which interval the backward leg traverses, or what the seam does). Ruled, then written: 12 tests, green. Two of my own bugs found and fixed on the way: the backward leg reached one frame too low (`A B C D B`), and `cycleMs` was used as the period for LOOP/ONCE as well as PING_PONG. 🔴 Decision 5's PING_PONG audio formula was **wrong as written** — `t mod rangeMs` desyncs sound from picture after the first cycle; the audio now follows the frame |
| JB-3.05 Playback + an audio track | T2 | 3.03, 3.05a | ⚪ Outline | |
| [JB-3.06a](specs/JB-3.06a_gif_encoder.md) Animated GIF encoder (pure, deterministic) | T2 | — | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-28, finished by the orchestrator — **two real encoder bugs, both found by asking GDI+/ImageIO to read the file, not by reading the code**: (1) the Logical Screen Descriptor was 5 bytes instead of 7, so the colour table started 2 bytes early and **every file was malformed**; (2) the LZW code width grew one code too early. Plus 5 test-side bugs, all recorded in the spec. `:core:jvmTest` 394/0 |
| JB-3.06b Export MP4 / WebP / PNG sequence / GIF / sprite sheet from the animation board | T2 | 3.01, 3.06a, 2.13a, 4.03a | ⚪ Outline | |
| JB-3.07 Send to Studio (drops on the timeline) | T1 | 3.06 | ⚪ Outline | |
| [JB-3.08a](specs/JB-3.08a_three_finger_swipe.md) Three-finger swipe: mode choice, badge override, frame flip / brush | T2 | 2.16a, 3.01 | 🟨 Claimed | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 — **in the tree, NOT landed.** 17 tests, **15 green, 2 red**, both mine to finish. 🔴 `anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother`: the spec (Decision 2 + test 2) is unambiguous — an override is forgotten as soon as a **different** board is asked about, and switching back does not restore it. The implementation keeps it, so the test is right and the code is wrong: `badge()` must clear the override when `doc`'s active board differs from the board it was made on. 🔴 `aBrushGestureIgnoresNonFiniteOffsetsToo`: a valid move after a NaN returns `Nothing` instead of `Step.Brush`, which smells like `running` having been cleared, or `begin` picking FRAMES on a canvas board so a 173 px move crosses no boundary. **Do not guess at this one** — the first failing assert of the sibling test also returns BRUSH where the automatic answer is FRAMES, so the two may share a root cause in the fixture or in `begin` |
| JB-3.08 Context-aware 3-finger swipe: frame flip when the ACTIVE board is an animation board with ≥ 2 frames, else brush size/opacity; corner badge (running figure / brush) shows the mode and a tap overrides it; never switches mid-gesture | T2 | 2.02, 3.03, 3.08a | ⚪ Outline | |
| JB-3.30 📱 Owner animates a loop and drops it in the Studio | T3 | Phase 3 | ⚪ Outline | |

### Phase 4 — Sprite board
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| [JB-4.01a](specs/JB-4.01a_sprite_grid_math.md) Sprite grid maths: by size / count, cell lookup, edge drag, sub-grids | T2 | 0.02, 4.03a | 🟧 Built | subagent of openrouter/stealth/space-bunny-alpha 2026-09-29 — 33 tests, **green on the first `:core:jvmTest` run** (476/0), and test 8 checks grid maths against the *landed* `SpritePacker.pack` rather than a comment. 🔴 For the Lead: `rect.x + col * cellW` is plain `Int` and overflows near 2×10⁹ — a **silently wrong cell**, not a crash (same family as JB-2.13a Q3); and `cellAt` relies on JVM-only `Float.toInt()` saturation, which matters for the iOS door |
| JB-4.01 Sprite board: grid by px or cell count, sub-grids, edge sizing | T2-V | 2.01, 4.01a | ⚪ Outline | |
| JB-4.02 Tap cells in order, play preview (SpriteLab's chip mechanic, reused) | T2-V | 4.01 | ⚪ Outline | |
| [JB-4.03a](specs/JB-4.03a_sprite_sheet_packer.md) Sprite sheet packer + SpriteLab `.sprite.json` sidecar | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-4.03b "Export" and "Export and open in SpriteLab" buttons on the sprite board | T2 | 4.01, 4.03a | ⚪ Outline | |
| JB-4.30 📱 Owner check | T3 | Phase 4 | ⚪ Outline | |

### Phase 5 — Ink layers
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-5.01 Ink layer renders from stroke records, crisp at any zoom | T1 | 0.07, 1.05 | ⚪ Outline | |
| [JB-5.02](specs/JB-5.02_stroke_picking.md) Picking the right stroke in dense line work (tap again to cycle) | T2 | 5.10 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-5.03a](specs/JB-5.03a_stroke_edits.md) Stroke record v2 (colour, width) + reshape / re-weight / re-brush | T2 | 0.04, 5.02 | 🟦 Ready | **Claude 2026-09-29** (R15) — edits `stroke/`, so nothing else touching `stroke/` runs beside it |
| JB-5.03 Reshape / re-weight / re-brush a stroke after drawing | T2 | 5.01, 5.03a | ⚪ Outline | |
| [JB-5.10](specs/JB-5.10_vector_eraser_geometry.md) Vector eraser maths: partial, whole, to-intersection | T2 | — | 🟧 Built | space bunny #1 orchestrator (subagent) 2026-09-28 — 40 tests green; 1 contract question to Claude |
| JB-5.11 Context-aware eraser (ink erases lines, paint erases pixels) | T2 | 5.10, 5.01 | ⚪ Outline | |
| JB-5.30 📱 Owner check | T3 | Phase 5 | ⚪ Outline | |

### Phase 6 — Wash (wet paint)
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-6.01 Level 0: watercolour look on commit (edge darkening, granulation, bloom) | T1 | 1.05 | ⚪ Outline | |
| JB-6.02 Level 1: wet-lite flow only in the wet area, paused while the pen is down | T1 | 6.01 | ⚪ Outline | |
| JB-6.03 The one great brush (tip for detail, belly for washes) | T1 | 6.02 | ⚪ Outline | |
| JB-6.30 📱 Owner check | T3 | Phase 6 | ⚪ Outline | |

### Phase 7 — Puppet board and characters
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-7.01 Puppet board: pins over the art | T2-V | 2.01 | ⚪ Outline | |
| JB-7.02 Rig test through Avatar Studio's own solver (no copy) | T1 | 7.01 | ⚪ Outline | |
| JB-7.03 Export `.avatar` into the character library | T2 | 7.02 | ⚪ Outline | |
| JB-7.04 Character board: wire expression + mouth boards into a head, parallax, look-at | T1 | 7.03, 4.03 | ⚪ Outline | |
| JB-7.30 📱 Owner check | T3 | Phase 7 | ⚪ Outline | |

### Phase 8 — Brush import
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-8.01 Photoshop `.abr` import (port of ag-psd's reader, MIT) | T2 | 0.03 | ⚪ Outline | |
| JB-8.02 Procreate `.brush` / `.brushset` import | T2 | 0.03 | ⚪ Outline | |
| [JB-8.03](specs/JB-8.03_mypaint_import.md) MyPaint `.myb` import (then bundle CC0 MyPaint brushes) | T2 | 0.03, 0.03b | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-8.04 Krita `.kpp` / `.bundle` import (pixel + colour smudge engines only) | T2 | 0.03 | ⚪ Outline | |
