# PUPPET — the finish ledger

JoyRaptor, 2026-09-16: *"systematically go through all the functions that were previously
designed, that we talked about, that we mocked up in the web thing ... bring everything to a
finished state. And don't come back here until it's done."*

This file is the list. Every row is **DONE** or **OPEN WITH A REASON**. Nothing is allowed to sit
in a third state, and "built" is not the same as done: a row earns DONE when the code exists
**and drives something real**. A control whose value no renderer or solver reads is the dead-knob
defect — it teaches the user the feature is broken rather than missing, which is worse than an
absence — and **nine** of them were found by this audit.

Sources swept: the whole design conversation of 2026-09-15/16 (both HTML studies, control by
control), `SPEC_20260915_PUPPET_UI.md` including its own §7 gap list, and the code itself.

---

## A. Dead knobs — on screen, connected to nothing

| | Control | Finding | Resolution |
|---|---|---|---|
| A1 | "Scale at this point" (Free pin) | `PuppetPin.scale` persisted, read by nothing. `handleComponents()` is 2: a pin is x and y and has no scale for the deformer to apply. | ✅ **Removed**, with the sentence beside it that still advertised the deleted rotate arc |
| A2 | Show · Mesh | `rig.showMesh` written, saved, never read | ✅ **Wired** — `PuppetMeshWire` runs the same `PuppetDeformer` the renderer uses, so the wireframe is where the mesh actually is |
| A3 | The reach EYE on a slider | Documented as the reach ring; actually toggled `showPins`, duplicating the Character row, while no ring was drawn anywhere | ✅ **Wired** — dashed, because MLS has no edge and a solid circle would claim a boundary that does not exist |
| A4 | Per-bone `bendSign` | Stored by every bone, honoured by `FabrikSolver`, never copied into the `Chain` | ✅ **Plumbed** — "Flip elbow" would have been born dead |
| A5 | `PuppetPin.muted` | Honoured by the mesh builder AND drawn hollow by the overlay, with no way to set it | ✅ **Given its switch** |
| A6 | `PuppetPin.weight` | Saved, round-tripped through JSON, read by nothing | ✅ **Wired** — scale applied before normalisation, wire format V5, 7 new assertions |
| A7 | "Snap to keys" (Recording scope) | Written, saved, read by NOWHERE. The one whose absence does real damage: it is the guard against the exact failure the `‹ ♦ ›` exists for — after a take the keys are dense, the playhead lands a frame off one, and dropping a key there authors a second beside the first. | ✅ **Wired** — applied to key EDITS, not to the playhead itself, because a playhead that jumps while you scrub is a worse feature |
| A8 | `PuppetPin.scale`, the FIELD | Removing the slider was not enough: a persisted field nothing reads is the same lie one level down, and the next person to add a scale control would have found a number waiting and shipped the dead knob again | ✅ **Field and its JSON removed**; `PuppetRigTest`'s deep-copy check re-pinned to `weight`, which still means something |
| A9 | `MeshCurves` claiming `Easing` is android-free | It was not: two `@NonNull`s pulled in androidx, so nothing touching easing could compile in the harness | ✅ **Made true** — annotations removed, the null they documented now actually checked |

## B. Designed, specced, and never built

| | Item | Resolution |
|---|---|---|
| B1 | Bone selection and the bone scope | ✅ Tap a shaft to select; from/to, length, rest angle, stretchy, flip elbow, joint limits |
| B2 | Flip elbow | ✅ (needed A4 first) |
| B3 | Wind, and which way it blows | ✅ Direction shown only when there is wind |
| B4 | Poof on delete | ✅ Three rings in the pin's colour. Not a warning — one undo brings it back |
| B5 | Long-press a pin for what is underneath | ✅ Names what it landed on ("L.Elbow · 2 of 3"); no fourth copy of the type ring |
| B6 | Tape: drag the body to slide | ✅ `MeshPoseTrack.shiftRange` |
| B7 | Tape: drag a cap to stretch | ✅ `MeshPoseTrack.scaleRange` |
| B8 | Tape: drag a key to retime | ✅ `MeshPoseTrack.moveKey` |
| B9 | The chip row | ✅ Per pin, dot when it holds animation, hollow dot for a simulated one |
| B10 | Change a pin's type from the drawer | ✅ The swatch cycles; long-press is the ring |
| B11 | Drop a swatch ON a pin → a bone | ✅ One gesture, two outcomes |
| B12 | No pin-dropping during playback | ✅ Authoring versus performing |
| B13 | Easing matched to the recorded motion | ✅ `MeshEasingFit` — and it is allowed to conclude LINEAR, which is the half that matters |
| B14 | "13 of 103 keys" — honest about thinning | ✅ Spoken in the caption after a take |
| B15 | A rigged picture opens on the Puppet tab | ✅ One-shot, not a remembered preference |
| B16 | Pins on a ROTATED picture | ✅ One map, one inverse; also fixes the scatter during a rotate gesture |
| B17 | Captions on every action | ✅ (landed 2026-09-16 morning) |
| B18 | Depth stack while scrubbing Z | ✅ (same) |
| B19 | A cursor that looks like a cursor | ✅ (same) |
| B20 | The strip stops running away | ✅ (same) |
| B21 | Loupe and strip stop sharing a corner | ✅ (same) |

## C. Open, each with the reason

| | Item | Why it is open |
|---|---|---|
| C1 | The timeline grab bar | **Not this feature's doing**, and not reproducible from here — the phone holds JoyRaptor's real projects and opening one to drag it is not mine to do. The most likely cause is now defended against anyway (`requestDisallowInterceptTouchEvent` on DOWN: an ancestor intercepting the first MOVE produces exactly the reported symptom), and the diagnostic is now conclusive — one press of the bar prints `moves=`, which separates "the touch never arrived" from "something ate the drag" from "the clamp refused the size". |
| C2 | The corner widget that overlaps | Three of the four things that were colliding are accounted for. The fourth was called "the audio clipping widget" and no such view has been found by that name. **A screenshot settles it in one message.** |
| C3 | The "Everything" chip | SPEC §6C says build it only if the absence is felt, because it is the rejected ghost-strip in a hat. The chip row (B9) now answers most of what it was proposed for — which pins hold anything — without drawing a thousand grey marks. Left to see whether the rest is missed. |
| C4 | Face/voice drivers | A whole subsystem, listed in the manifest as "if/when" and half-built in `avatar/` for a different surface. Wiring it to a picture rig is its own spec, not a control on this one. |
| C5 | Enclosed holes in traced artwork | A donut traces as a disc. Engine lane (`AlphaContour`), flagged by that lane, and it needs a hole-aware triangulator rather than a UI change. Not hit by the art rigged so far, which is islands rather than holes. |
| C6 | Delaunay edge-flipping | Same lane. Ear clipping yields legal but occasionally sliver triangles; visible only as slightly uneven bending on a thin limb. |
| C7 | The app-wide hover-label sweep | Owed across the whole app, not this feature. |

## D. Questions the design left open, now answered

- **Should the helper strip's buttons wear tag labels?** ("Useful the first week, noise the
  second.") **No** — and the caption pill settles it: every action now names itself *when you do
  it*, which beats a permanent label at both ends of that first week.
- **Does Z belong to the island or the pin?** Both, and that was already right: the island is what
  overlaps, and the per-handle depth field is what lets a forearm cross a belly halfway along.
- **Detail per-recording or per-project?** Per-character, on the Recording scope. A per-recording
  setting would mean the same character's takes thin differently with nothing on screen saying so.

---

## The standing rule this audit ran on

Every control was traced to the code that has to read it. If nothing read it, there were only ever
two honest outcomes — wire it, or remove it — and which one depended on whether the engine could
express the thing at all. `scale` could not (a pin is two numbers); `weight` could, and now does.
