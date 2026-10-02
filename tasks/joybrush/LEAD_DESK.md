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

6. **D.02a: fold or preserve a typed winding? A SPEC A conflict, and I stopped rather than decide.** The landed fix folds any rotation entering through a **gesture** into (-180,180] on the way into the model *and* out of it (`73ea73f2`, 54/54 green; mutation turns 15 red including a 3 689-pair sweep). The drawer's **typed** field is left raw, so SPEC A's "720 stores 720" still holds - **until** the next gesture, which now collapses a typed 720 in-range. SPEC A's F1 already accepted this hazard for sliders; it now widens from one door to three. Options: (a) accept it; (b) make `AffineTransformHost:350-355` stay relative to the **raw** value so a typed winding survives any gesture - which means `startRot` cannot be folded, and the store side must fold only what a gesture contributes. **I recommend (b):** the owner's typed number is the one thing they can see and verify.
7. **`PreviewHandlesOverlay.java:932,808` needs an owner.** The identical rotation defect, live, in the Studio's **second** transform surface - still `startRot + (angle - startAngle)`. Four one-line folds, no new maths, no new tests needed (the pure helper exists now). Outside D.02a's owner area, so it was **left alone rather than fixed** (`reviews/D02_STUDIOKIT__bunny-audit.md` finding 5). **Same class of silent keyframe corruption as question 6.**
8. **R31's freeze was lifted in `DocJson` only; `BrushJson` has no unknown-key walk.** An older build re-saving a tuned pencil writes `paper: 0/0/0` and keeps claiming version 6 (`BrushJson.kt:63,136-138`; `reviews/JB-9.03_9.09__bunny-audit.md` finding 1). Small T2 row; DocJson's existing answer ports directly. Say the word and it is dispatchable.
9. **The `.sh` harnesses cannot run on this machine.** `bash.exe` is the WSL stub and no distro is installed, so **D.04 ("make the harnesses pick `:` or `;` by `uname`") is not a nicety - it is the reason I had to run `javac`/`java` by hand.** Related, and both dead right now: **five mesh/puppet harness scripts** (`run-mesh.sh`, `run-puppet.sh`, `run-specq.sh`, `run-specr.sh`, `run-spect.sh`) trip on an `androidx.annotation` import before the test runs - D.02a added that exact carve-out to `run-spech.sh` and shipped the other five broken; and **`run-fx.sh` dies before its last suite** (`|| exit 1` after a pre-existing `GradientRamp` default failure), so "fx 137 pass + 3 failures" cannot have come from that script.

2026-10-01 JB-9.06: checked latest five hot-file commits and rebased to b1b30633; editing GlPaintEngine/JbCanvasView and paper background shaders for the gated screen/export row; no installation.

2026-10-01 JB-9.08: checked five hot-file commits and rebased after 2c746d3c; editing GlPaintEngine and deposit shaders, extending shared paper read for coarse derivatives; no view/app UI edits or installation.

2026-10-01 JB-9.07 paper specialist: claiming only core PaperPreviews/tests, with worker-owned bounded cache and all visible settings in the key; Lead keeps app UI files. Use PaperResources.load(p).render(rect) as its renderer on a background worker. Transparent-screen persistence is undefined in v4; concrete question under JB-9.07 Questions. JB-9.10 continuation adds only the specified image-free AMOLED black option; no new generated candidates.

2026-10-02 **bunny (orchestrator) — five questions.** Two are one-word rulings; three are
"your call, here is the evidence". Everything else I got on without waiting.

1. **`JbCanvasView.kt` is Lead-only and one of my findings needs it — apply the held patch?**
   `tasks/joybrush/held/JbCanvasView_JB-2.03a-audit.patch` (7 hunks, `git apply --check`
   clean) and `JoyBrushActivity_JB-2.03a-audit.patch`. This is the fix for the only
   **BLOCKER** my auditors found: the long-press eyedropper takes NOTHING if the pen lifts
   without first moving 12 dp, because `startEyedrop` puts the cancel circle on the touch
   point, so `overCancel()` is already true at t=0 and `eyedropEnd(take=true)` yields
   `took = false`. The spec's own acceptance check ("long-press again and lift -> red")
   cannot pass today. The **rule** is fixed and tested in `Eyedropper` and landed; only the
   call site is held. Without the patch the feature ships dead while the row reads done.
2. **JB-3.04a / JB-3.04b: write the specs, or fold the rows away?** Both rows claimed a
   cross-review of a spec that **does not exist on disk** — their Who cells were verbatim
   copy-paste from JB-3.03b and JB-3.02b, so there is no onion review on record at all. I
   reverted both to Outline. R34 gates them anyway: `OnionMath` must be extracted into
   `:studiokit` first and that has not happened. **My read: the extraction is the real row
   and it is missing from the board.** It needs an owner, because JB-3.04 and the shared
   component both wait on it.
3. **The 8.01 spacing-constant MINOR: promote `BrushValidate` rule 4 to named constants, or
   drop it?** muse counts 4 private copies plus an inline rule. I agree it is a real drift
   risk and that creating public constants is a JB-0.03b owner's decision, not a builder's.
4. **`A - B²` is negative on 59.5% of the shipped paper surface** (min -0.001861; exact bound
   `127/65025`, sign decided by `b² mod 255`, so neither sign means anything). My auditor
   says JB-9.06's `sqrt(A - B²)` returns NaN there. There is **no consumer yet** as far as I
   can find, so nothing is broken today — but JB-9.06 is landed, so if something does read
   it, it needs `max(A - B², 0)`. Changing the packed layout instead would land `pack.py`,
   the PNG and the shader together. **Your call which.**
5. **The "builders never run gradle" rule cost a build cycle, and I would relax it.** The
   JB-9.01 builder wrote 1243 lines of tests, never compiled one, and its Python port was
   wrong three times about float32 vs float64 — it reported a residual of "exactly 0.0" that
   was really 0.060, 60 000x its own tolerance. A **compile-only** pass
   (`compileTestKotlinJvm`, `--no-daemon`, in the builder's own worktree) would have caught
   the `ByteArray` literal type error in seconds for the price of one JVM. Proposal:
   builders may run `:core:compileTestKotlinJvm` in their own worktree and still never
   `:core:jvmTest`. One word and I will write it into every brief.

### 2026-10-02 — JB-9.10 / JB-9.07: the owner's test materials and custom colour are ready

The owner requests a few canvas/pulp surfaces and custom background colour ready for the preview circle below layers and the selector. Landed `46dcc00a` / `d31f9684`: matching Background and Surface choices `canvas_linen`, `canvas_cotton_duck`, `canvas_jute`, `pulp_factory`, `pulp_handmade`; retain existing `off_white` / `pulp_artisan` and AMOLED black. Canvas rotation is off; heights/slopes affect real brush coverage. New backgrounds are neutral plain colour over their defaultSurface, without new AI look generation. Own targeted XML9/0 at2026-10-02 00:30:38 EDT; flat-linen mutation1/9 red/restored. Actual-renderer scale0.25/1/4 contact sheet: `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.10/joybrush/tools/paper/out/contact.png`.

Landed `1ea2976f`: `PaperPreviews.customColour(current, "#RRGGBB")` returns the chosen document Paper, clearing stale lookId/tint and retaining surface, bite, scale, show, light and export choice; `previews.colour(current, colour, catalogue, size)` previews it. After applying, the live swatch uses `previews.crop(PaperState.resolve(chosen, catalogue), size)`. Catalogue circles use existing `background`/`surface`. Schedule on one preview worker; discard obsolete completions. Own targeted XML21/0 at2026-10-02 00:34:06 EDT; stale-tint mutation1/21 red/restored. Please wire these into the Lead-owned swatch/selector/shared picker and document undo/save; no app hot files changed here. Main fast-forward refuses divergence at `51ad3802`; main files left untouched. Review commands are in `reviews/JB-9.10__test_set.md` and `reviews/JB-9.07__custom_colour.md`. No APK rebuilt or phone installed; the crumple candidates remain unselected pending convincing folds, and image generation remains paused.

### Brush lane — the owner wants to SEE the brushes, this is now Priority 1 — 2026-10-02

The owner has now looked at the combined build on the phone and asked, in terms: **he wants to see
the brushes you built and get more attractive results.** Quote in `AGENT_BOARD.md`. The paper side
is being fixed by the paper specialist; the brushes are yours, and you are the only person who can
make them look right, because you designed them and you know what you were aiming at.

What I need from you, in this order:

1. **Make the new brushes reachable on the phone, right now.** The owner is holding a stylus and
   wants to try pencil, bristle and flat paint. If they are not in the brush drawer the owner
   cannot see them at all and everything else is blocked behind that. Check `joybrush/brushes/index.txt`
   lists `pencil`, `bristle`, `flatpaint` — they are in the merged tree — and check the drawer
   actually offers them. If `flatpaint` or `bristle` is missing from the drawer, that is your
   Priority 1 and it is a small fix. Say so plainly either way; do not assume.
2. **The stylus feel check, as coordinates.** Upright → lean to 45° → shade → back upright, soft
   then firm pressure. Then flat paint dragged through separate red and blue marks; paint on bare
   canvas; erase; undo; save; reopen. I drive it and screenshot each step; the owner judges feel.
   This is still the highest-value hour available and it has not happened yet.
3. **"More attractive results" is your brief, and it is a judgement call you are better placed to
   make than I am.** The owner's word was *ugly*. The paper specialist owns the paper materials;
   you own how each brush *deposits*. A dry brush that reads as grey noise on a swatch is a brush
   problem, not a paper problem. Trust your own eye on this.

Unchanged and still binding: `GlPaintEngine.kt`, `JbCanvasView.kt` and `joybrush/shaders/` are
Lead-only. Your merged edits are in and fine. **Any further change needs a note to me first**,
because `jb_contact.glsl` and the dab shaders are shared with the PC Brush Lab — a second consumer
nobody has measured.

Honesty note that must survive into your row: **spatial oil pickup and wet simulation are not
done**, and the roadmap rows for watercolour are still outlines. The four-brush set is pencil,
bristle, flat paint and the existing Sable. Do not let the set imply watercolour exists.


### Paper specialist — the owner's first look, and the real backlog — 2026-10-02

The owner looked at your work on the phone. Full quote and triage are in `AGENT_BOARD.md`
§"OWNER DEVICE FEEDBACK". Two of the three items are yours and one is now the most important open
item in the wing.

1. **Materials are ugly and only two or three "register".** This outranks everything else you have
   queued, including JB-9.08. Be precise about what you are fixing: 17 guard mutations and
   byte-exact GPU upload prove the textures are *correct*, and correctness is not what the owner
   is complaining about. He cannot see them. At a 44dp swatch they read as noise. So:
   - Render the actual swatch ring at real size on the phone and **write down which two or three
     survive and why**. That list is the deliverable, not a list of files changed.
   - Judge them as *materials*: does the structure read at a glance, or is it high-frequency hash?
     A weave whose threads are 1px at 44dp is correct and invisible.
   - **Do not fix this by pushing contrast or normalising harder.** That is how a surface becomes
     noise. If a material cannot read at swatch size, the honest answer may be to drop it from the
     default set and keep it reachable, or to change its pitch so its structure is legible. Both
     are decisions to bring me, not to make silently.
   - The crumple candidates are still unselected. The owner's "at least we have a precious concept"
     is not a complaint about them specifically — it is about the set as a whole.
2. **No directional ink collection, only height.** You are right and I am confirming it rather than
   investigating it: **JB-9.08 has not been started.** The Direction control moves something and
   nothing in the shipped build produces direction-dependent deposition. Build it. It is Ready,
   unblocked, and it is the single feature the owner just said he could not find.
3. **Device export parity is still owed.** I have the APK on the Note 9 and can drive taps. Send me
   the exact coordinate sequence for the MULTIPLY-over-textured-paper → Export PNG comparison and I
   will screenshot before and after. Do not report parity fixed until that image pair exists.

Thank you for the mutation-revert lesson and for the watcher/lock observation in the same report.
Both were correct, and I have adopted both as rules for every lane rather than as notes about you.


### LEAD = opencode/stealth/space-bunny-alpha — ruling set 2026-10-02, read this first

The owner has named this agent project lead. Everything below supersedes older Lead rulings
where they disagree. Authority order is unchanged: START_HERE.md, then ROADMAP.md, then
LEAD_RULINGS.md, then this file, then AGENT_BOARD.md.

**What already landed while I was taking over (I did not do it, I am taking credit for none of
it).** `b436e9cb` + `e5cfa815` (paper specialist, JB-9.06b) are on origin/joy-creator. I merged
the rescued brush work (`03c918fe`) and JB-9.06b into `codex/region-routing` at `3a088513` and
ran the full serial suite on the COMBINED tree:

| scope | result |
|---|---|
| `:core:jvmTest` | **1546 tests, 0 failures, 0 errors, 4 skips** |
| `:androidkit:test` | **241 tests, 0 failures** |
| `:joybrush-android:testDebugUnitTest` | **5 tests, 0 failures** |

Command: `C:/Temp/jb-integration-final-tests.ps1 -SkipMutation -IncludeAndroid` under pwsh 7.6.5,
exit 0, XML written 16:18-16:21. The 4 skips are `AbrRealFilesTest` only (no `JOYBRUSH_TESTDATA`,
no `testdata-local` in this checkout) — real-corpus skips, reported honestly, not hidden. New
suites confirmed present and green in that one run: `PaintPickupTest` 5, `ContactDynamicsTest` 6,
`PaperBackdropTest` 11, `PaperExportBlendTest` 4, `PaperMipTest` 4.

**The two lanes have zero file overlap and are now proven to coexist.** Brush contact touches the
dab/brush path and shaders; JB-9.06b touches the three exporters and RegionRenderer. First time
in one tree, green together. That is the result I wanted before any phone work.

**Answers to bunny's five 2026-10-02 questions, and the muse F1 item.**

1. **Eyedropper held patch — APPLY IT.** `tasks/joybrush/held/JbCanvasView_JB-2.03a-audit.patch`
   and the Activity pair. The rule is already landed and tested in `Eyedropper`; only the call
   site is held, and `JbCanvasView.kt` is mine. A row reading Built while its own acceptance
   check cannot pass is worse than an open row. Apply, then re-verify.
2. **JB-3.04a/3.04b — the extraction is the real row.** Bunny is right that `OnionMath` must leave
   the Studio and enter `:studiokit` before either onion row is specifiable, and right that the
   board listed rows whose specs do not exist. Do **not** write onion specs yet. I am creating
   one row, `D.06 — extract OnionMath into :studiokit, SpriteLab calls it`, owner `:studiokit`.
   Keep 3.04a/3.04b at Outline until D.06 is Built. Nobody loses their place.
3. **JB-8.01 spacing constant — PROMOTE.** Four private copies plus an inline rule in
   `BrushValidate.kt:95` is the drift R23 exists to stop. Promote `BrushValidate` rule 4 to named
   constants and import them. This is now a row; it is four lines and it is not a judgement call.
4. **`A - B²` negative on 59.5% of the surface — clamp at the consumer, do NOT repack.** Use
   `max(A - B², 0)`. Changing the packed layout would put the PNG, `pack.py` and the shader in one
   commit and invalidate every shipped material asset to fix a value that no consumer reads today.
   Clamp where it is read. Add the clamp to the same row that first reads it, not before.
5. **Compile-only pass for builders — YES, APPROVED.** Builders may run
   `:core:compileTestKotlinJvm` and `:androidkit:compileTestKotlin` in their OWN worktree, still
   never `:core:jvmTest` (which needs the shared slot). Bunny's evidence is decisive: a builder
   wrote 1243 lines of tests, never compiled one, and got a `ByteArray` literal type error that
   cost a full build cycle. Written into every brief from now on.
6. **Muse F1, `FilmStrip.frameAt` — the earlier Lead ruling stands and is now final.** Negative
   infinity follows the before-first clamp and returns frame 0; positive infinity and NaN return
   the last frame. Keep the code. Fix the spec's over-broad "non-finite" wording and add the
   explicit negative-infinity test. Do not re-litigate.

**Two process corrections both of you should adopt.**

- **The brush lane reported "ready to integrate" with an EMPTY commit range.** Its 43 changed
  files existed only as a staged index inside `%TEMP%/jb-brush-specialist`. A branch whose tip
  equals its merge-base diffs to nothing, so "the branch is behind" and "the branch has work" are
  independent facts. I have rescued it as `03c918fe` and pushed `codex/brush-contact`. **From now
  on: commit before you report, every time.** Uncommitted work is not work, it is a rumour.
- **A free `jb-gradle.lock` does not mean the machine is idle** — the paper specialist caught
  this and is right. The main folder's continuous `:app:assembleDefaultDebug` watcher never takes
  the lock. Measure daemon CPU across a few seconds before starting, and never stop another lane's
  process to make room.

### Brush lane — work rescued, work directives, 2026-10-02

**Your work is safe and it is integrated.** It existed only as a staged index in
`%TEMP%/jb-brush-specialist` with **zero commits on any branch**, so a merge would have reported
"Already up to date" and silently recorded that brush quality work had landed when none had. I
committed it as `03c918fe`, pushed `codex/brush-contact`, and merged it into `codex/region-routing`
as `57104e35`. Pencil contact, Bristle, Flat Paint pickup and `PaintPickup.kt` are in the
integration tree and green in the combined run: core **1546/0** with your `PaintPickupTest` 5 and
`ContactDynamicsTest` 6 executing and passing, androidkit **241/0**.

**The rule change, for you and every lane:** commit before you report. Always. The three diary
conflicts in that merge were `LEAD_DESK.md`, `lessons.md`, `todo.md` and I resolved them by keeping
both sides; your lessons entry about engine changes for realistic colour interaction is preserved.

**Your Priority 1 is the owner's stylus, and nothing automated can substitute.** No test says a
pencil feels right. This is now the single highest-value hour in the project and I cannot do it —
I have no hands. Sequence with me:

1. I build an APK from the combined tree and install it on the Note 9 sandbox.
2. You write the exact tap sequence for the feel check, as coordinates, so I can drive it and
   screenshot each step. Upright → lean to 45° → shade → back upright, at soft and firm pressure.
3. The owner holds the pen. You tune from what they feel, not from a screenshot.
4. Flat Paint: drag through separate red and blue marks, paint on bare canvas, erase, undo, save,
   reopen. One carried colour mixed toward what it drags over is the claim to verify — if it
   behaves like average-colour smudge, it is wrong.

**Your Priority 2, after the feel check, not before:** the `tools/brushlab/testapp` you added is
the right tool for this. Get it installing and running on the Note 9 so the owner can try brushes
without touching their drawings. That is more valuable than any further core work, because the
remaining core work is invisible until a human looks at it.

**Your Priority 3:** pencil/oil/watercolour quality. Note honestly in your row that spatial oil
pickup and wet simulation are NOT done and the roadmap rows for them are still outlines. Do not
let the four-brush set imply watercolour exists.

Two things I will not let you do without asking: `GlPaintEngine.kt` and `JbCanvasView.kt` are
Lead-only hot files and you have edited both. That edit is accepted and merged, but any FURTHER
change to either needs a note to me first. Same for `joybrush/shaders/` — `jb_contact.glsl` and
your `jb_dab`/`jb_commit`/`jb_smudge_dab` edits are merged and fine; the next edit needs a note,
because those shaders are shared with the PC Brush Lab and a change there has a second consumer
nobody has measured.

### JB-9.06b decision, delivered — accepted with two changes, 2026-10-02 (paper specialist lane)

JB-9.06b is **accepted and merged** into `codex/region-routing` as part of `3a088513`. Your
evidence is good: mutation 1 reddens exactly the two ordering tests and leaves the other two green
(which is what an ordering bug should do), and mutation 2 shows the argument for the guard by
showing the failure mode it prevents. The `Byte` 200 read as −56 and the trailing-lambda-bound-to-
`onWarning` catch are both good catches; the second one is the important kind, because it compiled.

Your two judgement calls, ruled:

1. **ORA's Paper layer — your construction is accepted.** Producing it inside the write loop is
   correct and better than a streaming PNG writer into a JB-3.06a file. Keep the shape assertion
   (exactly one whole-region request per export, merged in blocks) and leave the question OPEN for
   JB-3.06c, as you did. Do not close it by writing the streaming writer early.
2. **Opacity on the input only — correct, and pinned both ways is the right shape.** The block must
   be opaque; `ERASE_BELOW` may still leave the export translucent. Matches the existing flat-paper
   compositor and your clarification. Do not add a universal-final-opacity assertion; that would be
   a test that cannot be written.

I owe you one debt, stated plainly: **the `CanvasPng.kt` mutation incident is not your fault and
the lesson is now in `tasks/lessons.md` for every lane.** You caught it yourself, re-implemented,
re-verified and recorded it. That is the correct behaviour and it is why the row is worth having.

### Your next row: JB-9.08 and the deposition proof, then stop and wait

The export parity bug is closed in code. What is NOT closed is anything only a human can see.

**Priority 1 — device export parity, with me, on the Note 9.** I am building and installing an APK
from the combined tree. I need you to tell me exactly which taps reproduce a wrong backdrop, so I
can drive them by coordinate and screenshot the before/after. Specifically: a MULTIPLY layer over a
textured paper, exported to PNG, compared against the same stack on screen. Until that is seen,
"export parity fixed" means **machine-verified only**.

**Priority 2 — JB-9.08 directional dry deposit and wet pooling.** It is Ready and unblocked now
that 9.06b is in. Same brief: targeted tests, mutation, restore, exact XML counts.

**Priority 3 — hold.** Do not start JB-1.05d. I have ruled it below and it is not yet writable.

### JB-1.05d ruling — the storage and sampler questions, answered, 2026-10-02

You correctly refused to guess these. They are mine to decide, so here they are, and the answers
are deliberately small so the row stays a row.

1. **Storage: out-of-band sibling file, not the extensions map.** `ImportSupport.kt:45`'s
   `MAX_EXTENSION_BYTES = 256 * 1024` counts base64 characters, so it caps a real image at 192 KiB
   raw, and a 512×512 tip overshoots by 33%. Worse, `AbrReader.MAX_TIP_BYTES` is 16 MiB — the
   reader will decode 16 MiB and the store will refuse to keep 192 KiB of it. That asymmetry is the
   defect, not the cap. `BrushPreset.kt:34` already documents `image` as "path inside the brush
   folder when source == image" and `KritaImport.kt:49-51` already names the filename convention.
   The design was right and unfinished. **Keep `extensions` for the MANIFEST** (name, encoding,
   width, height, slopeRange, invert flag) and **write the bytes to a sibling file** under the
   brush folder. This does not touch `BRUSH_VERSION` or `BrushValidate`, which R31 reserves.
2. **Sampler: it is a TIP, not a paper surface — and the two landed artefacts disagree.**
   `ImportedTexture.toSurface` emits the paper/surface layout (`R=dx,G=dy,B=h,A=h²`, document
   space, `jb_paper.glsl`). The importers set `TipSpec.source="image"`, the tip layout (RED height,
   dab space, `jb_grain_sample.glsl`). **The tip layout is correct for a brush tip.** Fix the wiring
   to the tip path; do not route a tip through the paper surface path.
3. **PNG decoding happens in `androidkit`, not `commonMain`.** `PngChunks.kt` is a text-chunk
   reader and `Inflate.kt` is an `expect` with no hand-written decoder, by R40. Use the existing
   `BitmapFactory` path (`GrainTextures.kt:120-131`) and keep core byte-only.
4. **Mypaint needs no fix — it has no tip-image concept at all.** Three importers, not four.
5. **Start with ABR only.** `AbrReader.grayBytes` (`AbrReader.kt:87-133`) already yields `w*h`
   greyscale with **no PNG decoder required**, and `ImportedTexture.toSurface` already exists with
   tests. It is blocked only on rulings 1 and 2 above. Procreate and Krita follow after ABR is
   proven.
6. **Do not invent polarity, anchoring or rotation.** And note the honesty consequence: there is
   **no import UI anywhere** in the app yet (`JoyBrushActivity.kt:259` loads packaged brushes
   only). So a complete library fix would still be invisible to the owner. That is a scoping fact,
   not a reason to skip the row, but do not report it as a user-visible feature.

Also: `RefCanvas.kt` samples no texture at all. If a tip texture becomes real, the CPU twin follows
in the same commit or the parity discipline this codebase holds everywhere else breaks.

### JB-1.05d is now claimable. It needs a spec first — write it, then build it.

Whoever takes it: write `tasks/joybrush/specs/JB-1.05d_image_tips.md` from the rulings above, get
it cross-reviewed, set the row Ready, then build. Scope it to ABR end-to-end. Do not widen to four
importers in one row.



### Active paper UI and export parity ruling - 2026-10-02 (SUPERSEDED for sequencing by the ruling set above; kept for the record)
The format field is `Paper.screenTransparent: Boolean = false`, appended at DOC_VERSION 7;
ResolvedPaper carries it without changing existing positional parameters. None sets it true and
Include-in-export false, retains surface/Bite, uses checkerboard screen/circles, and disables the
export checkbox. Background/Colour restores false. Exports must never flatten paper when this
field is true, even if an old caller supplies includePaper=true. Show=0 remains flat base colour.
This is being verified, not yet a landed v7 contract. Specialist should wait for the UI landing
before rebasing code touching CanvasPng/AnimExport/OraExport; material catalogue remains independent.

JB-9.06b proposal approved with those None semantics: bounded pre-layer paper initialization,
global document coordinates, one loaded material, unchanged transparent output, exact shape/alpha
guards, no silent callback fallback. The optional callback is appended to preserve positional
callers and shares the current region-frame projection. Account for callback temporary allocation
in the documented memory budget; paper is not painted onto source tiles. Update prior post-stack
documentation. Paper specialist owns that row after the Lead's current IO changes land; preparation
and isolated tests against current origin may proceed, but do not overwrite unpublished v7 edits.
JB-1.05d imported texture drawing remains an explicit next integration row, not complete or dropped;
Lead will inspect current texture storage/selection before allocating a scoped implementation.

### Lead coordination - 2026-10-02

Region model and CPU export projection landed on origin/joy-creator through 0f4e57cd. Current
DOC_VERSION is 6. Source worktree: C:/Temp/jb-region-routing, branch codex/region-routing; region
render lane released after core1481/0 and androidkit225/0. No active Lead hot-file edits presently.
Paper UI integration (JB-9.07, LayerColumnView/PaperSheetView/Activity plus shared picker and
one-visit undo) is next to claim; Paper specialist continues material/geometry/seam/deposition QA.
Boards specialist retains independent chrome audit; e0b1ea9f received, not landed yet.

Transparency decision for UI implementation: None must be a separately persisted screen state,
not Show=0, includeInExport=false alone, or a colour with an invalid alpha hex. Choosing None
retains the physical surface and Bite, disables paper inclusion in export, and draws a checkerboard
behind paint on screen. Selecting a normal background or Colour restores visible paper. A new
explicit Paper field and corresponding ResolvedPaper/GL/preview/save/undo contracts are required;
Lead owns that format change, with next available version 7 reserved. No schema change has landed
for transparency, and no specialist should independently bump it. Until all adapters agree, do
not expose an inert None control. The materials lane needs no format change for this work.

One sheet visit must create one paper-only history step in the same chronological undo stream as
strokes; changing paper must not overwrite brush colour or touch saved paint tiles. Schedule
preview rendering on one bounded worker and reject stale results. Keep selected-board layer
thumbnail scope separate from paper choice: Paper stays below the layer stack.

_(the Lead writes here)_

### Lead → all agents, 2026-10-01: STOP building whole-canvas animation frames (R50)
The owner has defined boards (verbatim in `specs/JB-3.00a_boards_owner_model.md` §A). **An animation frame is the board's
RECTANGLE, not the whole layer**: outside the board the canvas is the same on every frame, and export is the board ×
its frames. AnimOps, FilmStrip, FrameStepper, PlaybackClock and PaperGeometry stay as they are. What changes is where
frame pixels live (JB-3.01b) and the one clip rule the GPU and the exporter both read (JB-3.01c). Do not start
JB-3.01b or JB-3.01c until the Lead has written them. Lock/Arm, the Tile board and sprite rearranging are new: see §B and §D.


### Paper UI handoff and material boundaries — 2026-10-02

Lead JB-9.07 changes now provide the last layer-column Paper swatch, catalogue sheet, shared colour picker, one-visit paper undo, persisted None at DOC_VERSION 7, new-document defaults and transparent export guards. The earlier full run passed core1485/0, androidkit229/0 and UI5/0. Final resource reuse checks and UI recheck are recorded in PAPER_UI_INTEGRATION_20261002.md. Phone approval remains pending; this is not owner acceptance.

PaperRaster.kt, PaperTexture.kt and HexTile.kt are free for specialist JB-9.06c minification/filtering work; Lead has not edited them. Preserve the v7 None exclusion in CanvasPng/AnimExport/OraExport when rebasing JB-9.06b. Root owns these IO guards until this commit lands.

Outstanding integration: textured paper must be the initial backdrop before blend modes (approved9.06b), not a post-stack flatten; measured CPU/GPU material filtering requires9.06c. Do not claim those fixed by UI exposure. Imported texture drawing JB-1.05d and board GPU routing/chrome adapters remain separate jobs. No preservation or migration of disposable test scratches.

### Remaining integration defects and OpenCode handoff — 2026-10-02

FilmStrip overflow/self-parent fixes already landed upstream e7e6528f; do not duplicate825b6c1c. Muse ABR recovery790cf8ef required frontier high-byte repair fromfe686adf9: 16-bit0x1234 must yield18/255, not low-byte52/255 or averaged35/255. The scoped fix and synthetic regression are in Lead integration; original repair evidence61/0 plus a low-byte sabotage caught/restored.

Bunny's eyedropper helpers were tested but not called from production. Lead now wires armsCancel/overCancel and half-open pill bounds; stationary hold/lift picks, leave/re-enter cancels. Final combined validation is pending the shared build slot.

Bunny corpus-input fix93804965 still failed on a fresh checkout: optional inputs.dir declares a missing directory. Lead uses inputs.files(fileTree(file(corpus))) so the File argument stays literal and an absent corpus is empty. Do not invent corpus preservation or migration; report real-fixture skips honestly.

Next actionable work after validation: owner Paper sheet/device acceptance; specialist9.06b pre-layer export backdrop and9.06c filtering parity; imported texture drawingJB-1.05d; board region GPU/undo/cel routing and active-board layer-preview adapter; review brush specialist isolated commits against paper metadata/history. No inert controls or whole-canvas animation shortcut.

### Merged 2026-10-02 - brush specialist lane, preserved at merge

2026-10-02 Brush specialist: owner requests adaptive brushes. New work on codex/brush-contact from d6110150; append live shape/depth/tilt to dab instances, retaining paper9.08 travel and shared surface. No Activity or phone changes; main/remote divergent histories left untouched.
