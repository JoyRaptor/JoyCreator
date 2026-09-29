# JB-7.02 — Rig test: run the rig through Avatar Studio's own solver

| | |
|---|---|
| **Tier** | T1 (it touches the app's rig classes and the GL preview; it is not a T2 pure-maths row) |
| **Status** | 🟨 **Draft — cannot be specced honestly until Q1 is ruled.** See "Why this is a draft". |
| **Needs** | JB-7.01 (the pins). **JB-7.01 is itself Draft and its own dependency, JB-2.01, is `⚪ Outline`. Nothing in this chain is Built.** |
| **Owner area** | *(cannot be fixed until Q1.)* Provisionally: NEW `joybrush-android/.../board/PuppetRigBridge.kt` and EDIT `PuppetBoard.kt` from JB-7.01. **No file in `app/`, ever** (R16/R18 serialise app-file work, and none of those rows are this one). |
| **Estimated size** | ~250 lines |
| **Command** | `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test`, plus the watcher's `build.log` for `:joybrush-android` |

## Why this is a draft

The board row says *"Rig test through Avatar Studio's own solver (no copy — R23)"* and R17 confirms the
pipeline exists: **contour → triangles → weights → MLS solver → pose tracks, ~290 harness checks
passing**, in `app/.../transform/mesh/` and `app/.../puppet/`.

That is a green engine I am not allowed to copy, in a module I am not allowed to edit, behind a
dependency (`D.03`) that has not been specced. What this spec *can* say with confidence is the part
that is Joy Brush's: **what a Joy Brush puppet board hands the solver, and what comes back.** The
middle — which class is called, with what arguments, on which thread — depends entirely on Q1.

## Goal

Blueprint §2 and §4: after dropping pins on the art you **test the rig** — move a slider, the art
deforms, live. Blueprint line 39 is explicit that this is the *change* to Concepts' design: *"the test
must run through Avatar Studio's own pose solver — one authority, per the repo rule that a second copy
is how preview and export start disagreeing."*

So this row is deliberately small in Joy Brush and large in discipline: Joy Brush owns the pins and the
sliders; **Avatar Studio's solver owns the answer**, and there is exactly one of it.

## What is decided (and checkable) without Q1

1. **Joy Brush holds no solver and copies no maths.** No `PuppetRigSolver`, no `PuppetTriangulator`, no
   MLS, no weights, no contour tracing in `joybrush/`. A test cannot check this; a grep can, and the
   cross-reviewer should run it: `rg "PuppetRig|PuppetMesh|PuppetWeights|PuppetPose" joybrush/` must
   return **nothing**. That is the test for R23 compliance on this row, and it is the only test that
   matters here.
2. **The rig is one object, built once, and handed over whole.** `PuppetRig` from the Studio is
   constructed from Joy Brush's `PinSet` **plus the art**, and it is the same object at test time, at
   export time (JB-7.03) and in the Studio afterwards. A second rig built for export is exactly the
   divergence R23 forbids.
3. **The test is read-only.** Moving a slider poses the rig and draws it. It never writes a pin, never
   edits the document, and never saves. Undo is not involved because nothing changed. (If a future test
   mode bakes a pose, that is a new row.)
4. **One undo step, or none.** Decision 3 settles it: **none**. A rig test that dirtied the document
   would make autosave (JB-0.08b) write a file nobody edited.
5. **A rig that fails to solve draws the art undistorted and says why in words.** Never a blank board,
   never a crash, and never the previous frame's pose left on screen pretending to be current. The
   Studio's `AvatarRigValidator` is the authority on validity; Joy Brush does not re-check.
6. **The pins on screen during a test are the pins from the document**, drawn at their stored
   normalised positions — not the solver's possibly-reordered or re-derived pins. If the solver moves a
   pin, the difference is visible and belongs to the Lead's ruling, not to a silent reconciliation.

## The bridge (provisional shape — Q1 says whether this is the shape at all)

```kotlin
// joybrush-android, package cc.joycreator.joybrush.android.board
/**
 * Everything Joy Brush knows about the Studio's rig, and nothing else. The types on the far side are
 * the Studio's (`com.fadcam.ui.faditor.puppet.*`, `...avatar.*`), read-only here: Joy Brush builds a
 * rig, poses it and draws the result, and never stores one.
 */
interface PuppetRigBridge {
    /** True when a usable rig exists for these pins. */
    fun canPose(pins: PinSet, artWidth: Int, artHeight: Int): Boolean

    /** Poses [rig] for the current slider values. Off the GL thread; never blocking the UI thread. */
    fun pose(rig: StudioRig, sliders: Map<String, Float>): StudioPose

    /** The sentence to show when [canPose] is false. Never empty. */
    fun problemWith(pins: PinSet, artWidth: Int, artHeight: Int): String
}
```

**This spec invents no rig format.** It names the Studio's classes and delegates to them. If Q1 lands
on (c) in JB-7.01 — pins in Joy Brush, solver only touched here — then this interface is the whole
surface and it is thin on purpose.

## Decisions I can make (still checkable)

- **The bridge has exactly three methods and no state.** A rig test is a pure function of (pins, art,
  slider values); anything the bridge remembers between calls is something a second implementation
  could disagree with. Tests: a bridge called twice with the same arguments returns `==` poses.
- **Posing never runs on the UI thread**, and a slow pose is dropped rather than queued: a slider
  dragged faster than the solver answers shows the last pose, and the next one catches up. Tested by
  asserting the bridge is invoked from a background executor (a fake that records its thread name).
- **A missing art size is refused in words** (`problemWith` non-empty when `artWidth` or `artHeight`
  is 0 or negative), because the contour step needs real pixels and a pin on a 0×0 board is not a rig.

## Tests

There is no JVM test for a GL preview. So:

1. **The grep test (D1), in the cross-review checklist, not in the suite.** `rg -i "puppetrig|"
   joybrush/` → nothing. This is the row's real acceptance criterion and I am stating it that way
   rather than pretending a Kotlin unit test can prove "no copy".
2. **`canPose` / `problemWith` agree (D5):** on a 3-pin board over a 512×512 raster, `canPose` is true
   and `problemWith` is `""`. With 0 pins it is false and the message is non-empty and names the
   problem. Both directions — a true with a message, or a false with an empty sentence, is the bug.
3. **Pinning is deterministic (D2):** building the rig twice from the same `PinSet` and art gives
   `==` rigs. This is what makes "the same rig as the export" checkable rather than asserted.
4. **A pose is a pure function of the sliders (D1, D2):** `pose(r, s) == pose(r, s)`; changing one
   slider changes the pose.
5. **No document mutation (D4):** a test that runs fifty poses with the document's `layers`,
   `boards` and every `cel` deep-copied beforehand finds them `==` afterwards.

## Do not

- Do **not** copy, port or "reimplement for the cloud" any of the mesh/rig maths. R23; and the whole
  point of this row is that there is one authority.
- Do **not** edit a file in `app/`. The serialised app-file order (R16/R18) is `D.02a → D.02 →
  D.02c / D.05`, one at a time, and this row is not one of them.
- Do not write a rig JSON. JB-7.03 goes through the Studio's `AvatarLibrary`.
- Do not let a failed pose leave the last good pose on screen.
- Do not cache a rig across an art change.

## Definition of done

- [ ] builds green (paste)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] the grep above returns nothing (paste it)
- [ ] committed as `JB-7.02: rig test through the Studio solver`; pushed
- [ ] ROADMAP row → 🟧 Built — **after Q1**

## Questions — for the Lead

**Q1. BLOCKING, and it is the whole row. Which class is "Avatar Studio's own solver", and from where?**

The pipeline is spread across at least six classes in two packages — `PuppetTriangulator` (43 KB),
`PuppetWeights` (29 KB), `PuppetTopology` (21 KB), `PuppetDeformer`, `PuppetMeshBuilder` (27 KB) and
`PuppetRigSolver` (13 KB) — and `AvatarStudioActivity` calls
`PuppetPoseResolver.resolve(rig, params, discreteState)` for the live preview. **I cannot tell from the
outside which of those is the intended single entry point for a third-party caller**, and picking the
wrong one is how Joy Brush ends up with its own pipeline that nobody notices until export disagrees
with preview. The ruling must name:

- the **entry point** for "pose this rig for these parameters" (I am guessing `PuppetPoseResolver`,
  but `PuppetRigSolver.solveChain` is also public and is the one with `Chain`/`bakeDangle` — they may
  be two layers, and a rig test needs both);
- **how a Joy Brush `PinSet` becomes a `PuppetRig`** — is there a public builder, or must the pins be
  fed to `PuppetMeshBuilder`'s contour step, and if the latter, with what parameters (contour
  resolution, erosion, pin density)? **Nothing in the repo tells me the intended parameters, and
  inventing them is inventing a rig.**
- whether the answer is available **before** `D.03` moves `mesh/` into `:studiokit`, or whether this
  row is explicitly sequenced after D.03.

**Q2. Is a rig test allowed to touch `app/` at all, even read-only?** `:joybrush-android` compiles
against the app's classes (it is in the same Gradle build), so a read-only import works today. R23
describes integration code living in `joybrush-android`, but the *app-file serialisation* rule is about
**edits**. **Confirm: importing app classes read-only from `:joybrush-android` is fine, and no file
in `app/` may be edited by any Phase 7 row.** I have written the spec on that reading.

**Q3. Who owns the sliders, and do they exist yet?** "Pitch sliders puppet the rig live" is in
`AvatarStudioActivity` today. A Joy Brush puppet board needs its own slider set (it has no Studio
chrome), and every slider is a `params` key the resolver reads. **The ruling must either name the
parameter keys Joy Brush may drive, or say that Joy Brush drives a fixed, named set** — otherwise I am
guessing at strings, which is precisely the "invent a rig format" failure in a smaller coat.

**Q4. Low risk, ruled provisionally.** I made the bridge stateless (Decision: three methods, no
state). If the Studio's solver is expensive enough that rebuilding the rig per slider frame is too
slow, this needs a cache keyed on (pins, art) — which is a correctness risk, because a stale cache is
a rig test that lies. Provisionally: no cache, rebuild on pins/art change only, and if that is too slow
the ruling should change the design deliberately rather than by profiling.
