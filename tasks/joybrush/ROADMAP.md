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

### Phase 0 — Foundation: draw, save and reopen on the Note 9
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-0.01 Pen-sample & stroke contracts, context-aware smoothing, tip direction, curves (`joybrush/core`) | T1 | — | 🟧 Built | Claude 2026-09-28 |
| [JB-0.02](specs/JB-0.02_document_model.md) Document model, boards, layers, cels, file layout | T2 | 0.01 | 🟧 Built | space bunny agent #1 2026-09-28 |
| [JB-0.03](specs/JB-0.03_brush_preset_and_dynamics.md) Brush preset format + dynamics evaluator | T2 | 0.01 | 🟧 Built | stealth/space-bunny-alpha 2026-09-28 |
| [JB-0.02b](specs/JB-0.02b_enum_version_rule.md) "New enum constant ⇒ version bump" rule: docs + guard test (LEAD_RULINGS R3) | T2 | 0.02, 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.03b](specs/JB-0.03b_brush_validation_hardening.md) Brush validation hardening (Lead's rulings on 0.03's questions) + the size = Infinity BLOCKER as a named test, `size.base ≤ 4096` (LEAD_RULINGS R1) | T2 | 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.04](specs/JB-0.04_stroke_codec.md) Stroke recording codec | T2 | 0.01 | 🟧 Built | space bunny agent #3 2026-09-28 |
| [JB-0.05](specs/JB-0.05_android_module_and_input.md) Android module in the app build + Joy Brush screen hosting the engine's drawing view | T2 (T1 review) | 0.01, 0.07 | 🟨 Claimed | orchestrator → subagent 2026-09-28 — there is NO bunny #5; this was unowned. ⚠️ CANNOT reach 🟧 Built in this environment: build.log is 343 min stale (watcher dead) and there is no adb/sandbox phone, so the spec's two verification steps are both unavailable |
| [JB-0.06](specs/JB-0.06_pen_diagnostics.md) Hidden pen-diagnostics overlay (raw pressure/tilt/lean, sample-rate histogram, copy report) | T2 | 0.05 | 🟦 Ready | |
| JB-0.07 GPU tile engine: 256² tiles, dab/commit/display shaders, stroke buffer (flow/opacity, wash/build-up), copy-on-write tile undo, pen input, drawing view (`joybrush/androidkit`) — compiled against the Android API, shaders verified in WebGL2, **not yet run on a phone** | T1 | 1.01 | 🟧 Built | Claude 2026-09-28 |
| [JB-0.08a](specs/JB-0.08a_document_archive.md) The `.joybrush` archive: atomic write, read, zip-slip guard (JVM-tested) | T2 | 0.02, 0.04 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-0.08b](specs/JB-0.08b_save_open_wiring.md) Autosave, reopen, "Save a copy…", "Open…" on the screen | T2 | 0.05, 0.08a | 🟦 Ready | |
| JB-0.09 Lobby entry + first screen chrome in Joy Creator's look | T2-V | 0.05, D.01 | ⚪ Outline | |
| JB-0.10 CPU benchmark harness (flood fill, tile compression, PSD write) on the Note 9 | T2 | 0.05 | ⚪ Outline | |
| JB-0.12 Low-latency front buffer + motion prediction, with an off switch | T1 | 0.07 | ⚪ Outline | |
| JB-0.30 📱 Owner check: draw, save, reopen; lag as good as Infinite Painter | T3 | 0.06–0.12 | ⚪ Outline | |

### Design system
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| [D.01](specs/D.01_joybrush_colour_tokens.md) Joy Brush section-colour tokens (one place, ripples everywhere) + board colours | T2-V | 0.05 | 🟦 Ready | |
| D.02 Extract shared UI kit pieces Joy Brush needs (scrubbable number, slider row, header icon button) out of editor files into a shared package | T2-V (T1 review) | — | ⚪ Outline | |

### Phase 1 — The brush engine
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-1.01 Tip shape maths (superellipse corners, taper, aspect to razor, rotation, min width) — `joybrush/shaders/jb_tip.glsl` | T1 | — | 🟧 Built | Claude 2026-09-28 |
| JB-1.02 Grain maths: tip texture + paper grain, height threshold with edge width, tilt-aimed gradient — `joybrush/shaders/jb_grain.glsl` | T1 | — | 🟧 Built | Claude 2026-09-28 |
| [JB-1.03](specs/JB-1.03_cloud_texture.md) Procedural tileable cloud texture generator (deterministic) | T2 | — | 🟧 Built | space bunny agent #4 2026-09-28 |
| [JB-1.04](specs/JB-1.04_brush_dabber.md) BrushDabber: brush file drives every dab (pressure, tilt, speed, deterministic randomness) | T2 | 0.03 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| [JB-1.05a](specs/JB-1.05a_scatter.md) Scatter and count (jitter → leaves) | T2 | 1.04 | 🟦 Ready | |
| [JB-1.05b](specs/JB-1.05b_brushes_in_the_view.md) Brush files drive the drawing view (+ brush picker pill) | T2 (T1 review) | 0.05, 1.04, 1.05a | 🟦 Ready | |
| JB-1.05c Grain in the dab shader: tip texture + paper grain textures, tilt gradient uniforms (JB-1.02 maths) | T1 | 1.05b, 1.03 | ⚪ Outline | |
| JB-1.06 Smudge & nudge (ONE carried colour per brush — patent rule, blueprint §5) | T1 | 1.05 | ⚪ Outline | |
| JB-1.07 Default presets: Ink, Pencil, Marker, Soft air, Smudge, Nudge, Eraser | T1 + T3 tuning | 1.05, 1.06 | ⚪ Outline | |
| [JB-1.20](specs/JB-1.20_pc_brush_lab.md) PC Brush Lab (one HTML file, WebGL2, runs the shared shaders, pen pressure/tilt, fake rotation) | T2 | 1.01, 1.02 | 🟦 Ready | space bunny #1 orchestrator 2026-09-28 — code landed + JS parses (`node --check`); finish = screenshots via headless Chrome OR owner confirms (LEAD_RULINGS R6); 3 brush-format questions in the spec for the Lead |
| [JB-1.21](specs/JB-1.21_phone_brush_lab.md) Phone Brush Lab: edit brush.json on the PC, the phone reloads it in ~1 s | T2 | 1.05b | 🟦 Ready | |
| JB-1.30 📱 Owner signs off each brush on the Note 9 | T3 | 1.07 | ⚪ Outline | |

### Phase 2 — A real painting app
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-2.01 Screen chrome: control cluster, thumb rail, drawers-on-phone / popovers-on-tablet | T2-V | 0.09, D.02 | ⚪ Outline | |
| [JB-2.02](specs/JB-2.02_view_and_gestures.md) Zoom / pan / rotate (snap to 90°), 2-finger tap undo, 3-finger tap redo, fingers navigate once a pen is seen | T2 (T1 review) | 0.05 | 🟦 Ready | |
| JB-2.02b Tool finger modes (select · lasso · colour pick) and assignable 2/3-finger gestures | T2 | 2.02, 2.05 | ⚪ Outline | |
| JB-2.03 Colour: panel, drag-off-swatch picker, long-press eyedropper | T2-V | 2.01 | ⚪ Outline | |
| JB-2.04 Layers panel: paint/ink layers, blend modes, opacity, runtime layer budget | T2-V | 0.07, 2.01 | ⚪ Outline | |
| JB-2.05 Selection + transform (one-gesture start, live box, tap outside commits, survives ops) | T1 | 0.07, 2.02 | ⚪ Outline | |
| [JB-2.06a](specs/JB-2.06a_flood_fill.md) Fill maths: flood fill with tolerance, gap closing, no fringe | T2 | — | 🟦 Ready | |
| JB-2.06b Fill tool on screen: reference layer, drag to set tolerance | T2 | 2.06a, 2.13a, 2.01 | ⚪ Outline | |
| [JB-2.10](specs/JB-2.10_shape_recognizer.md) Hold-to-shape maths (recognise + perfect, keep pressure/tilt) | T2 | 0.01 | 🟦 Ready | (3 dispatches, 2 agent types, all returned empty — hardest maths in T2: PCA + Kåsa + RDP. Needs scaffolding, not a 4th retry) |
| JB-2.11 Hold-to-shape UI (hold timer, preview, resize before lift) | T2 | 2.10, 1.05 | ⚪ Outline | |
| JB-2.12 Helpers: grid, perspective guides, shape tracers (never exported) | T2-V | 2.01 | ⚪ Outline | |
| [JB-2.13a](specs/JB-2.13a_region_renderer.md) RegionRenderer: flatten any rectangle/frame to pixels, all blend modes (CPU) | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-2.13b Paper setting (colour/texture) + export PNG: screen / selection / board, include paper | T2 | 2.13a, 2.14a, 0.08b | ⚪ Outline | |
| [JB-2.14a](specs/JB-2.14a_png_writer.md) PNG writer (exact, JVM-tested) | T2 | — | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 — 17/17 green; review found a real w*h IDAT-cursor bug (infinite loop → OOM) now fixed |
| [JB-2.14b](specs/JB-2.14b_openraster_export.md) Export OpenRaster (.ora) — layers for Krita/GIMP/MyPaint | T2 | 2.13a, 2.14a, 0.08a | 🟦 Ready | |
| JB-2.14c Export PSD (own writer, 8-bit layered) | T2 | 2.13a | ⚪ Outline | |
| JB-2.15 Autosave and crash safety (never lose work) | T2 | 0.08 | ⚪ Outline | |
| JB-2.16 Brush size/opacity by dragging the brush swatch; nudge scaled to zoom | T2 | 2.01 | ⚪ Outline | |
| JB-2.17 Gesture cheat-sheet + first-run hints | T2-V | 2.02 | ⚪ Outline | |
| JB-2.30 📱 Owner finishes a real illustration; then Tab S8 check | T3 | Phase 2 | ⚪ Outline | |

### Phase 3 — Animation board
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| [JB-3.01](specs/JB-3.01_animation_model.md) Animation model ops: add/duplicate/link/delete/move frames, holds, timing | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-3.02 Animation paper: peg bar that doubles as buttons, pixel rulers | T2-V | 3.01, 2.01 | ⚪ Outline | |
| JB-3.03 Film strip: sprockets, ± actions, drag a frame's edge to hold it, finger scrub | T2-V | 3.01 | ⚪ Outline | |
| JB-3.04 Onion skin as ONE shared component (extracted from SpriteLab, same settings) | T1 | 3.01 | ⚪ Outline | |
| JB-3.05 Playback + an audio track | T2 | 3.03 | ⚪ Outline | |
| [JB-3.06a](specs/JB-3.06a_gif_encoder.md) Animated GIF encoder (pure, deterministic) | T2 | — | 🟦 Ready | |
| JB-3.06b Export MP4 / WebP / PNG sequence / GIF / sprite sheet from the animation board | T2 | 3.01, 3.06a, 2.13a, 4.03a | ⚪ Outline | |
| JB-3.07 Send to Studio (drops on the timeline) | T1 | 3.06 | ⚪ Outline | |
| JB-3.08 Context-aware 3-finger swipe: frame flip when the ACTIVE board is an animation board with ≥ 2 frames, else brush size/opacity; corner badge (running figure / brush) shows the mode and a tap overrides it; never switches mid-gesture | T2 | 2.02, 3.03 | ⚪ Outline | |
| JB-3.30 📱 Owner animates a loop and drops it in the Studio | T3 | Phase 3 | ⚪ Outline | |

### Phase 4 — Sprite board
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-4.01 Sprite board: grid by px or cell count, sub-grids, edge sizing | T2-V | 2.01 | ⚪ Outline | |
| JB-4.02 Tap cells in order, play preview (SpriteLab's chip mechanic, reused) | T2-V | 4.01 | ⚪ Outline | |
| [JB-4.03a](specs/JB-4.03a_sprite_sheet_packer.md) Sprite sheet packer + SpriteLab `.sprite.json` sidecar | T2 | 0.02 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-4.03b "Export" and "Export and open in SpriteLab" buttons on the sprite board | T2 | 4.01, 4.03a | ⚪ Outline | |
| JB-4.30 📱 Owner check | T3 | Phase 4 | ⚪ Outline | |

### Phase 5 — Ink layers
| Task | Tier | Needs | Status | Who |
|---|---|---|---|---|
| JB-5.01 Ink layer renders from stroke records, crisp at any zoom | T1 | 0.07, 1.05 | ⚪ Outline | |
| [JB-5.02](specs/JB-5.02_stroke_picking.md) Picking the right stroke in dense line work (tap again to cycle) | T2 | 5.10 | 🟧 Built | unknown model (subagent of openrouter/stealth/space-bunny-alpha) 2026-09-28 |
| JB-5.03 Reshape / re-weight / re-brush a stroke after drawing | T2 | 5.01 | ⚪ Outline | |
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
