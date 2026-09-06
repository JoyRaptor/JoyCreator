# Run order — what can go in parallel and what cannot

Five specs: **A** rotation>360 · **B** pivot picker · **C** single-frame export ·
**D** widen handles to PiP+text · **E** mesh GL renderer.

## The blunt version

**A + C together → then B → then D → then E.**

Only one genuinely safe parallel pair. That is not caution for its own sake: two agents editing the
same file at the same time is how work gets silently lost in this repo, and `FaditorEditorActivity.java`
is 36,000 lines that nearly every spec needs to touch.

## Why — the collision table

| | FaditorEditorActivity | ProjectStorage | TextOverlayItem / Clip | transform/ | compositor/ | export/ |
|---|---|---|---|---|---|---|
| **A** rotation | drawer input | read+write | rotation fields | — | rotation | rotation |
| **B** pivot | drawer layout | read+write | pivot field | — | rotation | rotation |
| **C** frame export | export UI | — | — | — | — | new path |
| **D** widen handles | selection wiring | — | — | **owns** | — | — |
| **E** mesh render | — | read+write | mesh field | Bend button | **owns** | **owns** |

- **A and B collide badly** — both edit rotation in the model, in storage, and in *both* renderers.
  Running them together will produce two half-correct rotation paths. B also depends on A being
  settled, because a pivot is meaningless if the rotation value it turns about is being rewritten.
- **C is the isolated one.** Its own export path, its own UI entry, and it only *reads*
  `CompositeExportOverlay`. Safe alongside A.
- **D owns `transform/`** and touches selection. E later touches `transform/` for one line
  (the Bend button), so D must finish first.
- **E is last by necessity** — it needs `TextOverlayItem`, `ProjectStorage` and `transform/` to have
  stopped moving, and it is the highest-risk work in the set.

## The waves

**Wave 1 — run in parallel**
- **A** (rotation beyond 360)
- **C** (single-frame export)

Tell each agent the other exists and name the files it must not touch. A owns rotation in the model,
storage and both renderers; C owns the new export path and the export UI.

**Wave 2 — alone**
- **B** (pivot picker). Needs A's rotation storage settled.

**Wave 3 — alone**
- **D** (widen handles to PiP + text). Owns `transform/` and the selection wiring.

**Wave 4 — alone, and give it your best agent**
- **E** (mesh GL renderer). Parity-critical GL, and the only spec that can break existing exports.

## If you want to compress it

You can run **D in parallel with wave 1** *if* you tell all three agents explicitly that D owns
`ui/faditor/transform/**` and the selection block of `FaditorEditorActivity`
(`updatePreviewHandlesForSelection`, ~line 24535) and that A and C must stay out of both. That gets
you A+C+D → B → E. It is a real saving but it puts three agents in the same activity file, so only
do it if you are comfortable checking the result.

**Do not** run E in parallel with anything.

## Before each wave

`./gradlew assembleDefaultDebug --console=plain` should be BUILD SUCCESSFUL before you start a wave
and after it finishes. If a wave leaves it red, fix that before starting the next one — a red tree
makes the next agent's build verdict meaningless, and they will report someone else's error as their
own.

## After each wave

Ask the agent for: the build verdict, whether it is compile-verified or device-verified, and what it
could NOT do. Then have those claims checked rather than taken at face value — that check is cheap
and has caught real problems every time so far.


---

## AMENDMENT 2026-09-06 — running D and E AT THE SAME TIME

The original order said "do not run E in parallel with anything." That still holds **as the specs
were written**. It can be made safe with ONE boundary change, below. JoyRaptor is short on turns and
asked for D and E together; this is what makes that a reasonable bet instead of a coin flip.

### The only real collision

| File | D | E (as specced) |
|---|---|---|
| `ui/faditor/transform/**` | **owns it** | needs ONE line (step 5, the Bend button) |
| `model/TextOverlayItem.java` | reads only | adds the `mesh` field |
| `FaditorEditorActivity.java` | selection wiring | should not need it |
| `compositor/**`, `export/**`, `ProjectStorage` | untouched | **owns them** |

Everything else is already disjoint. So the fix is to remove E's single reach into D's territory.

### The boundary — put this in BOTH agents' opening prompt, verbatim

> Another agent is working in this repo at the same time. File ownership is absolute:
>
> - **SPEC D owns** `app/src/main/java/com/fadcam/ui/faditor/transform/**` (EXCEPT the
>   `transform/mesh/**` subpackage) and all selection wiring in `FaditorEditorActivity.java`.
>   D must not edit `compositor/`, `export/`, or `ProjectStorage.java`.
> - **SPEC E owns** `compositor/**`, `export/**`, `project/ProjectStorage.java`,
>   `transform/mesh/**`, and the mesh field in `model/TextOverlayItem.java`. **E must not open
>   any file in `transform/` outside `transform/mesh/`, and must not edit
>   `FaditorEditorActivity.java` at all.**
>
> If you believe you must edit a file the other agent owns, STOP and say so in your report
> instead of editing it.

### What this costs

**SPEC E step 5 (enable the Bend button) does not get done.** E delivers the renderer, the model
field, the storage wiring and the parity proof; the button stays greyed. Turning it on is a short
follow-up once both lanes have landed and the tree is green — it is genuinely a few lines in
`TransformOverlayView` plus wiring the net's dots to `MeshProjection.dragToHandle`.

Say this to E explicitly, or it will do step 5 anyway and quietly collide with D.

### The residual risk, stated plainly

Both agents still run `assembleDefaultDebug` against the same tree. If one leaves the tree red,
the other's build verdict is meaningless and it will report the other's error as its own. Tell
both: **"if the build fails in a file you do not own, say so and stop -- do not fix it."**

There is also uncommitted work in `transform/CornerPinTransformHost.java`,
`overlay/TextOverlayLayer.java`, `model/TextOverlayItem.java` and `FaditorEditorActivity.java`
from the pivot lane (2026-09-05/06). It is staged and green. Neither agent should revert it.
