# Held by the Opus lane — do NOT dispatch these three

**These are on the docket and deliberately not tailored for another agent.** Each one is a place
where the failure mode is a confident wrong answer that compiles, passes review by reading, and is
discovered on a phone weeks later. They are listed here so the docket is complete and so nobody
picks one up thinking it was missed.

If you are an agent and your prompt pointed you at this file: you were sent to the wrong sheet. The
dispatchable ones are `SPEC_ZA`, `SPEC_ZB` and `SPEC_ZC`.

---

## 1. SPEC Y stage 2 — merge `SpineTransformHost` into the shared host

**Why held: a naive merge silently costs the spine its non-uniform scaling.**

`AffineTransformHost.writeQuad` decomposes a dragged quad into ONE uniform factor. The spine writes
a uniform `SC` for a pinch and **per-axis `SX`/`SY` for a corner drag** (`SpineTransformHost` ~line
283). Merge it without noticing, and every spine corner drag quietly becomes uniform — the picture
still moves, nothing errors, no harness fails, and the loss shows up the first time somebody tries
to squash a clip.

The design is already written in `SPEC_Y_one_transform_surface.md`: a `scaleAxesTo` on the adapter,
defaulting to a no-op, implemented by the spine and by the image host's bake. There is a second
trap beside it — the spine works in canvas NDC with y-UP and tests perpendicularity in a
**square metric**, undoing the NDC anisotropy by aspect. Feed it pixel-space rects and that
correction becomes wrong rather than unnecessary, in a way that only shows on a non-square canvas.

## 2. SPEC Y stage 3 — merge `CornerPinTransformHost`

**Why held: ~740 of its 1078 lines are the most device-verified code in the app.**

The general third is already extracted into `AffineTransformHost`. What remains is the commit-time
bake (`tryNormalizeOnCommit`), `verifyBake`, the SPEC L escape clamp (`keepDrawnQuadOnCanvas`), the
armed-keyframe surgery, the `hadPinKeys` animated-pin walkaway, and the whole bend seam — every one
of which exists because a specific bug reached JoyRaptor's phone and was fixed there.

Two known traps recorded on 2026-09-13:
- the minimum size floor is **0.01** here and 0.02 everywhere else; collapsing them changes the
  image floor by 2x
- only this host corrects the centre for a stored rotation pivot (`writeSimilarity` ~337-351).
  Losing that reintroduces the "pop on release" bug that took three attempts to kill.

## 3. SPEC ZD — wire PiP and spine warps into the shaders

### 2026-09-16 — the finding that makes this tractable, and the trap it avoids

**Do NOT route video through `MeshStampGl`.** Its shader samples a `sampler2D`, and the two
surfaces do not agree on the texture target:

* PREVIEW binds `GL_TEXTURE_EXTERNAL_OES` for a live PiP — `FxPreviewTextureView:2672`.
* EXPORT receives a plain `GL_TEXTURE_2D` from media3.

Serving both through the stamp means an OES variant of the mesh shader, the decoder's texture
transform threaded into it, and two shaders that must agree forever. That is precisely the
"looks finished while the two disagree by a y-flip" failure this sheet was written about.

**The cheap way in: bend the GEOMETRY, not the sampler.** Both surfaces already draw the PiP as a
QUAD through their own shader. A lattice bend is a displacement of vertex POSITIONS; the UVs are
unchanged, because a warp moves where a texel lands, not which texel is read. So:

* emit a grid of vertices instead of four corners,
* displace each position by `LatticeDeformer.eval` (the same authority `SpriteMeshDraw` samples),
* leave the UVs, the sampler, and both shaders **completely untouched**.

Neither surface needs a shader edit, so neither can drift from the other in the way this sheet
fears — and it works for OES and 2D identically because it never touches sampling. The shared
piece is one vertex generator; both draws call it, the way both text surfaces now call
`SpriteMeshDraw`.

Still requires both draw paths edited together and a device pass. It is no longer a shader
problem, which is what made it a held item.

### (original)


**Why held: preview/export parity is the top-severity bug class in this repo, and this is the one
place both sides must be edited at once.**

Once `SPEC_ZB` gives `Clip` the model, PiP and spine need the warp reading in
`FxPreviewTextureView` (preview) and in `GlPipFrameOverlay` / `SpineTransformExportEffect`
(export). Both are already live GL textures, so the render side is the easiest of the four — and
that is exactly what makes it dangerous: it is easy enough to look finished while the two surfaces
disagree by a y-flip or a pivot, which is precisely how SPEC Q and SPEC R happened (a mesh that was
displaced, then a mesh that was upside-down, each found on a phone after the code "worked").

`SpineTransformExportEffect`'s own doc records that this feature has ONE authority shared with
`FxPreviewTextureView.drawSpineTransform`. Keeping that true through a mesh addition is the job.

---

## Dependency order for the whole docket

```
SPEC ZA (sprite GL export)     ── independent, dispatch FIRST (closes a live parity gap)
SPEC ZB (Clip warp model)      ── independent, dispatch in parallel
SPEC ZC (text pinned view)     ── independent, dispatch in parallel
        │
        └── SPEC ZD (PiP + spine shaders)   needs ZB      [held]

SPEC Y stage 2 (spine host)    ── independent                [held]
SPEC Y stage 3 (image host)    ── independent, largest       [held]
```

The three dispatchable sheets do not touch each other's files and can run at once. Check
`tasks/LANES.md` before starting any of them — a SpriteLab lane is also live.
