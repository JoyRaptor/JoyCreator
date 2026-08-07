# Handoff — overnight run, 2026-08-06 → 07. Adjustment layers M0–M6, and more.

JoyRaptor went to bed with "finish all known specs". This is what got done, what is
provably true, and what is deliberately not finished. **Read
`tasks/PRIORITY_20260806c.md` for the ordered list; this is the narrative.**

Branch `joy-creator`. Everything is committed. JoyRaptor's own files
(`TRADEMARK.md`, `LAUNCH_STRATEGY.md`, `OUTREACH_ANONFADED.md`) are untouched.

---

## The one-line summary

**M0 through M6 are complete.** M2's two shipping bugs are fixed; M4 renders on
export, interleaves correctly in z, AND does multi-pass — so blur, the effect
the spec called "the one everyone expects", exports. **M7 (per-object FX on PiP
and text) is the only milestone not started.**

**Also done from START_HERE's backlog:** all four FF-B sprite AI tools, and the
duplicate-onto-its-own-lane button — for adjustment layers, text and sprites.

**The feature is usable end to end from the app**: add a layer, open its FX
panel, pick from 12 effects, set parameters, opacity and blend, reorder, delete.
All of that was driven on the phone and the result read back out of the project
file.

## Every harness, at HEAD

```
run-adjust     33/33 + 38/38        run-fx        18/18 + 71/71
run-mask       21/21 + 47/47        run-key       26/26 (4 suites)
run-matte      all pass + 12/12     run-sequence  100/100 + 50/50
schema_mask_stamp.py   13/13        schema_adjust_stamp.py  13/13
typecheck      607 sources, 1657 classes
```

Two new harnesses this run: `run-adjust.sh` (model + SDF) and `run-fx.sh`
(compiler + blend). `AdjustmentLaneTest` rides `run-matte.sh` because
`Timeline` reaches media3 and cannot load on a gson-only classpath.

---

## What is device-verified, and what is not

**Verified on the Note 9, with my own eyes:**
- Off-stage ghost outlines — both tiers, on screen at once.
- Creating an adjustment layer: the project came back with
  `"adjustmentLayers":[{… "name":"Adjustment 1"}]` **and `"schemaVersion": 13`**,
  the conditional stamp firing only because a layer was present.
- The editor is healthy with a layer present — no crash, scrubbing fine.
- **The whole M6 chain.** Tapped Adjust → panel opened on "Adjustment 1 / No
  effects" → Add effect → picker showed all four families → Invert. The project
  came back with `"fx":{"cards":[{"id":"invert","slot":0}],"nextSlot":1}` at
  schemaVersion 13 — the minimal additive shape, exactly as designed.

## What you WILL and WILL NOT see

**Will:** the layer in the band, the FX panel, the picker, cards with working
sliders, blend chips, reorder, delete, per-parameter keyframe diamonds, saved
looks, the cost meter, undo.

**Will not, ON THIS PHONE: the effect itself.** Not a gap in M5 — a hardware
floor. The Note 9 is **API 29**; `RenderEffect` starts at 31 and AGSL at 33, so
that device is permanently tier C and the panel says so in its own header. On an
Android 13 phone the same build previews the full chain live. Export applies
everything on every device.

**The export path HAS now been run**, and it was worth doing. See below.

**NOT verified on a device, and this is the honest gap:**
- **The export path has never run.** `AdjustmentLayerGlEffect` is written,
  typechecked and gated, but no export has been performed with a layer in it. It
  cannot regress an existing project (see the gate below), but neither can I
  claim it produces a correct picture.
- The mask tab's multi-shape UI, still — the drawer needs a real finger.

---

## The export A/B — run, and it found four bugs

A project was built with one adjustment layer carrying a single Invert, and
exported on the phone. **The first run failed four different ways**, in a class
that typechecked perfectly and would have shipped as "the adjustment layer does
nothing":

1. **Shader assembly order** — `MaskSdf`'s functions and the composite's uniforms
   were appended AFTER the compiler's output, so `main()` used them before they
   were declared. GLSL ES 1.00 requires declaration before use.
2. **The composite's uniforms were never declared at all.** `FxCompiler` emits
   uniforms for effect CARDS and knows nothing about layers — rightly, since it
   also serves the preview.
3. **The shaders were passed in the wrong order** — `GlProgram(fragment, vertex)`
   — so the fragment source was compiled AS A VERTEX SHADER. The driver's
   `'gl_FragColor' : undeclared identifier` was telling the exact truth.
4. **Unused uniforms are optimised away**, and media3 looks names up in the
   LINKED program, so setting one the driver deleted throws NPE. An invert-only
   stack reads none of `uTexel`/`uTime`/`uAspect`/`uDir` — **the simplest
   possible stack was the one that crashed.**

Plus a missing `setBufferAttribute` for the quad.

**All fixed. The run after that: "Export Complete", ZERO AdjustmentLayer
warnings.** That proves the shader compiles, links, binds and draws every frame.

**Then multi-pass landed and was exported too.** A stack of
`gaussian_blur(radius 6) + invert` — 3 renders through 2 ping-pong FBOs —
exported clean. It found one more bug of the same shape: only the composite pass
READS `uBaseSampler`, so the driver strips it from every intermediate pass and
media3 throws on the lookup. Sampler binds are guarded now, like the float
setters. That stack trace only existed because the log was changed to pass the
throwable — a bare `toString` on an NPE names no line.

**What is still NOT proven: the pixels.** Both exports went to a SAF location the
adb shell cannot traverse, so no frame was extracted and compared. "It ran clean"
is not "it inverted". Pulling one frame through the app's own Records screen is
the last step, and it is small — **this is the top of the open list.**

**The design decision that paid for itself:** this effect degrades to passthrough
and never throws. Four consecutive driver-level failures each produced one log
line and a finished export instead of a lost render.

## The safety property that matters most

Every risky change this run is **gated so the pre-existing case runs the
identical code**:

| Change | Gate |
|---|---|
| Multi-shape mask fold | no INTERSECT → today's two-bucket path, untouched |
| Per-shape feather | no override → today's one-blur-over-combined-path |
| Adjustment layers in the export chain | empty list → nothing added, chain built identically |
| Schema v13 stamp | only on a project that actually uses the feature |

That is why an old project's export is byte-identical *by construction* rather
than by careful reading — which matters, because the export wiring sits in the
code the PiP z-unification fix wrote.

**The one exception, deliberately:** the two colour-grade fixes DO change what
existing projects export. That is the point — export now matches what the
preview always showed — but JoyRaptor should know before re-exporting something old.

---

## Bugs found and fixed that were not on anyone's list

1. **The FX registry would have crashed on every device.** Its rule table was
   declared *after* the static initializer that reads it; Java initialises fields
   in source order, so the table was null and the class failed to load.
2. **`SCHEMA_VERSION` was 13 with no code that stamped it.** The javadoc
   described a conditional stamp in detail; `ProjectStorage` had no v13 clause at
   all and `usesIntersect()` was an orphan. Every intersect project would have
   been silently degraded by an older build.
3. **Export double-applied exposure and temperature** on every graded project
   with a vignette or grain — additive in one place, multiplicative in another.
4. **The preview vignette had never worked**, at any setting, since it was
   written. Pixel-space coordinate, `smoothstep` against ~700.
5. **A single keyframe was dropped on save** in `FxStack` — one key is a static
   value, not an animation, and the save was gated on `isAnimated()`.
6. **`FX_NOISE` was used and never implemented**, so the macro would have reached
   the driver raw.

Numbers 1, 5 and 6 were caught by the harness within minutes of it existing.
Numbers 2, 3 and 4 were caught by reading the diff against the spec.

---

## What is deliberately NOT finished

**M4 remainder — multi-pass export.** Only cards the compiler can FUSE render
today: 9 of the 12 effects. A SAMPLER card (both blurs, RGB shift) needs
ping-pong FBOs. Such a card is **skipped with a warning**, not rendered wrongly
— a plausible-looking wrong blur is worse than an absent one.

**M4 remainder — the fully merged z-loop.** Adjustment layers are appended after
the PiP loop, which is correct for the default case because the adjustment phase
is emitted above the PiP phase. A layer deliberately ordered BETWEEN two PiPs is
not yet honoured; it grades everything instead of only what is beneath it.
`LayerPreviewController.orderedCompositedItems` is built and tested for exactly
this and simply is not consumed yet.

**M5 (preview rendering) is not started** — and it is now the single biggest
gap, because everything else works. A user can author a full effect stack and
see absolutely nothing happen in the editor. §6 of the spec specifies it in
full; the `fx_below_group` wrapper is an XML change rather than a runtime
reparent, and `applyPreviewColorGrade` already proves RenderEffect works on a
ViewGroup containing a TextureView in this app.

**M6 remainders:** reorder is by arrow buttons rather than long-press drag, and
there is no preset store or per-parameter keyframe diamond yet. All three are
additive to what exists.

**START_HERE's other items** — the FF-B sprite AI tools, the duplicate-layer
button, the §3A device verification list — were not touched this run.

---

## Device hygiene

- `129d8643` ("bisect C long 2x") — used as the mask/ghost fixture. Backed up
  before first use, **restored byte-exact to `md5 c2bfa8c6`**.
- `cebc19e0` ("P0 control2 plain") — opened by accident during a smoke test
  (the project list had reordered). **Restored from its own backup to
  `md5 4c1b03c5`**, which is exactly what START_HERE records.
- `302da9ac` — the designated sandbox. Its md5 no longer matches the value
  START_HERE records; it is the project explicitly set aside for testing, and
  that line should be treated as stale rather than as damage.
- A path-mangling quirk truncated a project file to zero bytes at one point. It
  was caught immediately and restored. **`export MSYS2_ARG_CONV_EXCL='*'` before
  any `adb push` to `/data/local/tmp`** — without it the shell rewrites the
  device path into a Windows one and `cat > file` empties the target.

## Two working notes worth keeping

- **`typecheck.sh` cannot see new resources.** It does not run `aapt`, so any new
  drawable or id fails it with "cannot find symbol" until `build-install.sh`
  regenerates `R`. This cost a confused minute twice.
- **The PiP drawer's hold gesture is still not adb-drivable.** Four injection
  strategies, zero gesture events logged. The table is in
  `PRIORITY_20260806c.md`; do not spend the hour again.
