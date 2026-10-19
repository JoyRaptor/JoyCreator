# Codex brief: build the Unibrush (Digital Pen), 2026-10-18 (from the JoyBrush Lead)

You build `tasks/joybrush/specs/DIGITAL_PEN_SPEC_20261016.md`. Its **§0 (owner amendment, 2026-10-18: "it is the UNIBRUSH")
wins** over anything later in the file: one versatile brush with many modes (Unibrush Soft, Hard, Calligraphy, Taper, Fade,
Texture …), a four-point tip shape you drag by hand with a facing-angle arrow, a second shape B it morphs into by pressure,
tilt or speed, corners from sharp to round, radial + angled softness, jitter, a stamp texture plus a stroke texture, and
"what it turns with" behaviours. "New brush" saves the person's own mode into the Unibrush shelf.

Read first: `START_HERE.md` (the rules), `tasks/lessons.md` (mistakes already made), then the spec.

## Where to work
- Start from the newest `origin/joy-creator` (the Lead pushed the integration on 2026-10-18; it includes editable lines v1.2,
  real-engine brush previews, layer lock/merge/flatten and the media atlas with its flag off).
- Your own worktree, e.g. `C:/Temp/jb-codex-unibrush`, branch `codex/unibrush`. Never work in the main folder.
- Slices in the spec's order (§7, D1 → D10, with D3 now the four-point shape + morph). One slice = one or a few commits with
  JVM tests, then a short note on `tasks/joybrush/AGENT_BOARD.md` (commit hash, what is proved, what is not). The Lead
  reviews each slice before it is merged into joy-creator.

## Your lane (so three builders never collide)
| You own | Others own; send a small hunk instead of editing |
|---|---|
| core brush model: `BrushPreset`, `BrushJson` (version bump at landing, R3/R30 append-only), `Dynamics`, `Response`, validation | **Lead:** `GlPaintEngine.kt`, `JbCanvasView.kt`, `gl/lines/**`, `lines/**`, layers/boards ops |
| the stamp engine: `BrushDabber`, `jb_tip.glsl`, tip/grain/dual-tip shaders, `InkReplay` for the new tip fields | **Opus helper:** `gl/media/**`, `shaders/media/**`, `core/media/**` (pencil, watercolour, oil) |
| the Unibrush settings sheet (hold a brush → its settings with a live preview), preset family, the shelf | **UI specialist:** board/animation chrome (after the animation-clips handoff) |
If a slice needs a change in someone else's file (for example A6 lifts `prepareRecordedLine`'s refusal of image tips, which
lives in `GlPaintEngine.kt`), put the exact hunk in your commit message or a patch file and tell the Lead; don't edit it.

## Rules that cost real data before
- Never resolve a merge conflict: `git merge --abort`, stop, report. Check `git merge-tree --write-tree HEAD <branch>` before
  any merge. Never start a build in the same step as a merge.
- `git add` each file as you write it; push your branch at the end of every session. Never `--no-verify`. Write
  "JoyRaptor" / "the owner", never a real name or email.
- One Gradle build at a time on this PC: take `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock` with bash noclobber,
  delete it when your build ends, never delete one you didn't create. Flags: `--offline -Pkotlin.compiler.execution.strategy=in-process --max-workers=1`.
  A fresh worktree needs `local.properties` copied in (the Android SDK path).
- Phone: the Note 9 (SM-N960U) is the sandbox; take `C:/Users/JoyRaptor/AppData/Local/Temp/jb-phone.lock` the same way, press
  Home before installing, `adb install -r` only, never uninstall or clear data. Never touch the Note 20.
- Every new tip feature must also replay exactly as an editable line (records keep raw samples; same seed → same dabs). A
  feature that can't replay yet stays pixels, said once (editable lines v1.2 H12 does this automatically).
- Performance (§6): pen-line modes at 60 fps on the Note 9, Soft Air at 2000 px ≥ 45 fps; measure with the bench, report numbers.
- Owner design rules: every control gets a hover label; a chosen chip is solid cyan with its icon cut out; drawers stay
  about 55% see-through; dp values as drawn (the Note 9's density); phone-first, one hand.

## Done means
Each mode drawn on the Note 9 as raster AND as an editable line (then moved, recoloured, re-brushed), the owner judges
against Photoshop's default round and Procreate's, and the Lead has merged it.
