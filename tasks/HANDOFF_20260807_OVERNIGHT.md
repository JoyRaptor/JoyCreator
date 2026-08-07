# Handoff — overnight run, 2026-08-06 → 07. Adjustment layers M0–M4.

JoyRaptor went to bed with "finish all known specs". This is what got done, what is
provably true, and what is deliberately not finished. **Read
`tasks/PRIORITY_20260806c.md` for the ordered list; this is the narrative.**

Branch `joy-creator`. Everything is committed. JoyRaptor's own files
(`TRADEMARK.md`, `LAUNCH_STRATEGY.md`, `OUTREACH_ANONFADED.md`) are untouched.

---

## The one-line summary

**M0, M1, M3 and M6 are complete. M2's two shipping bugs are fixed. M4 renders
on export, single-pass, with the multi-pass half explicitly owed. M5 (live
preview of the effect) is NOT started — see "what you will and will not see".**

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
typecheck      606 sources, 1652 classes
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
sliders, blend chips, reorder, delete, the cost meter, undo.

**Will not: the effect itself, anywhere.** M5 is the live preview and it is not
built, so the editor shows the ungraded picture. Export DOES apply the effect —
but that path has never been run (see above), so treat it as untested rather than
as working. **The honest next step is an export A/B**: one adjustment layer, one
Invert, over a solid-colour clip, and compare frames.

**NOT verified on a device, and this is the honest gap:**
- **The export path has never run.** `AdjustmentLayerGlEffect` is written,
  typechecked and gated, but no export has been performed with a layer in it. It
  cannot regress an existing project (see the gate below), but neither can I
  claim it produces a correct picture.
- The mask tab's multi-shape UI, still — the drawer needs a real finger.

---

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

**M5 (preview rendering) and M6 (the FX tab UI) are not started.** Both are
specced in full in `SPEC_ADJUSTMENT_LAYERS_FX`. **Until M6 exists there is no way
to add an effect card from the app**, so an adjustment layer created today is an
empty one — real, movable, saveable, and visually inert. That is the honest state
of the feature.

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
