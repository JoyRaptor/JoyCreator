# mesh v0 — ABANDONED PRIOR ART, not compiled, do not restore wholesale

Written 2026-09-04 by an agent working from a brief that assumed a REGULAR LATTICE. Cancelled and
moved here when JoyRaptor asked that the same architecture also serve PUPPETEERING
(see tasks/SPEC_20260904_PUPPET_ARCHITECTURE.md).

WHY IT WAS SET ASIDE: the authoring model is fused to the lattice — `level`, `side`, `gridN`,
`arityFor(level)` run through MeshWarp / MeshWarpSpec / MeshPoseTrack. A puppet mesh has irregular
topology and no (row, col), so a lattice-typed core cannot express it.

WORTH LIFTING:
 - MeshTessellator's OUTPUT shape is already right: neutral `positions` / `uvs` / `indices` float
   and short arrays, reused across frames with no per-frame allocation. Keep that.
 - MeshPoseTrack's SHAPE is close to correct — one keyframe holds the whole pose as `float[]`, with
   easing per pose. Generalise it: a pose is "an array of N authored handle values", where N comes
   from the TOPOLOGY, not from a lattice level.
 - The clamps, the min-cell-area fold guard and the JSON key names are all reasonable.

DO NOT LIFT: anything that types the core on `level` / `side` / `gridN`.
