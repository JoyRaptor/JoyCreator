# Mesh warp must be built so PUPPETEERING can reuse it

JoyRaptor, 2026-09-04, while the grid mesh-warp engine is being built:

> "The mesh warp with a grid for a standard image is good — a quick fix a lot of people will like
> for basic warps. However, if we could build it so the ARCHITECTURE COULD BE REUSED the same way
> as After Effects and Adobe Character Animator: I upload a PNG, it sees a blob of pixels in the
> shape of a person, puts TRIANGLES AROUND THE SHAPE in a mesh, and anything transparent it cuts
> off on some threshold. Then I put in PINS — a pin to keep the hips still, a pin in each knee, a
> pin in each foot, and a SPRING PIN that just follows the inertia of the rest of the object, in a
> tail or a pigtail. Then I animate just those. That wouldn't be fifty points each with their own
> keys — that might be six, seven or eight, but they're efficient and could be used for character
> animation. I don't need this right now for the transform tool. What I want is the PLUMBING for
> that to be available for the next feature, which is PUPPETEERING."

## The one architectural rule this implies

**Separate MESH TOPOLOGY from DEFORMER.** They are different concerns and must not be fused:

| | Grid warp (building now) | Puppet (later) |
|---|---|---|
| topology | regular MxN lattice | triangulation of the alpha contour |
| deformer | direct per-vertex offsets | sparse pins + a solver (ARAP / MLS) |
| authored points | 9 / 25 lattice points | 6-8 pins |
| output | triangle vertices + UVs | **the same** triangle vertices + UVs |

The RENDERER MUST NOT CARE which produced it. If the GL side is handed "vertices + UVs + indices",
both features share the entire rendering, export and parity story — which is the expensive half.

## Consequences for the engine being built now

1. Tessellation output must be a neutral `MeshBuffers`-style struct (positions, UVs, indices) with
   no lattice assumptions leaking into it. A puppet mesh has irregular topology and no (row, col).
2. Deformation must sit behind an interface — something like `MeshDeformer.solve(rest, handles, out)`
   — so the lattice deformer is ONE implementation, not the only shape the code can express.
3. `MeshPoseTrack` (one keyframe = the whole lattice) GENERALISES DIRECTLY: for a puppet, one
   keyframe = all pin positions. Keep it typed on "a pose is an array of authored handle values",
   not on "a pose is a lattice".
4. Unit-space storage and the fold/validity guard carry over unchanged.

## What puppeteering additionally needs (NOT now — do not build)

- **Alpha contour trace + simplify**, with a transparency threshold.
- **Constrained Delaunay triangulation** of that contour (interior points seeded by density).
- **A deformer solver.** AE's Puppet is roughly ARAP (as-rigid-as-possible). MLS (moving least
  squares) is cheaper and simpler and may be enough on a phone. This is the single biggest unknown
  and needs its own cost study — a per-frame solve over a few hundred vertices on a Note 20 is not
  free, and it must not be re-solved per frame when nothing moved.
- **Pin types**: deform (drags the mesh), rigid/"starch" (resists deformation), and SPRING pins with
  inertia — a secondary-motion simulation, i.e. per-frame state that must be deterministic and
  seekable, because an editor can scrub BACKWARDS. A naive spring integrator is not scrubbable; this
  needs deciding before it is built.

## Cost, honestly
Puppeteering is a milestone comparable to the whole mesh-warp lane, probably larger. The point of
this note is NOT to build it now — it is to make sure the grid warp does not have to be rewritten to
get there.
