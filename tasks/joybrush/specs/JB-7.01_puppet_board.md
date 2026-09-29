# JB-7.01 — Puppet board: pins over the art

| | |
|---|---|
| **Tier** | T2-V (it is UI that must match Joy Creator's look and Avatar Studio's pin colours) |
| **Status** | 🟨 **Draft — not buildable until Q1 and Q2 are ruled.** See "Why this is a draft" below. |
| **Needs** | JB-2.01 (screen chrome: the board switcher, drawers, pills). **JB-2.01 is `⚪ Outline`, so this row has nothing under it that is Built.** |
| **Owner area** | *(cannot be fixed until Q1 — the honest answer is that the owner area depends on whether `D.03` has moved `transform/mesh/` into `:studiokit`.)* Provisionally: NEW `joybrush/core/.../core/puppet/PinSet.kt` + its test; NEW `joybrush-android/.../board/PuppetBoard.kt`; EDIT `JoyBrushActivity.kt` (one board case). **Nothing in `app/`.** |
| **Estimated size** | ~300 lines + ~150 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` (the maths) and `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` (the view) |

## Why this is a draft, not a Ready row

The ROADMAP says this row needs JB-2.01, which is `⚪ Outline` — there is no board chrome to hang a
puppet board on. Worse, and this is the part the spec cannot fix by writing more prose: **the pins are
not Joy Brush data.** They are `PuppetPin`/`PuppetRig`/`PuppetRigJson` in `app/`, and R17 says
`transform/mesh/` moves into `:studiokit` first (row **D.03**, also `⚪ Outline`). Whether a puppet
board can be built on the Studio's classes directly, on `:studiokit`, or not at all until D.03 lands
is a ruling, not a decision I am entitled to make — and guessing wrong means either a second copy of
the rig maths (forbidden by R23) or a spec that cannot be executed.

So: the shape of the work is written here so the ruling has something concrete to rule on, and the
status is Draft.

## Goal

Blueprint §2: a **puppet board** is a board where you drop pins on the art and the art moves. Blueprint
§4 Phase 7: "Pins over the art, test through Avatar Studio's own solver, export `.avatar`."

This spec is **only the pins**: placing them, moving them, deleting them, and saving them. Rig *test*
is JB-7.02. Export is JB-7.03. What the pins mean to a solver is the Studio's business (R23).

## Contract (provisionally — Q1 decides where this lives)

```kotlin
package cc.joycreator.joybrush.core.puppet

/**
 * One pin, in NORMALISED board coordinates (0..1 of the board's rect, y down). Normalised because a
 * board can be resized and a pin that stores document px lands in the wrong place; the same convention
 * `AvatarRig.PartPose.pins` uses.
 */
data class Pin(val id: String, val x: Float, val y: Float, val kind: PinKind)

/** SHARE the Studio's vocabulary. `PuppetPalette` has exactly these three roles. */
enum class PinKind { PIN, STIFF, DANGLE }

/** Pins on one puppet board, in placement order (first pin = the root). */
data class PinSet(val boardId: String, val pins: List<Pin>)

object PinMath {
    /** Nearest pin to (x,y) NORMALISED, within [slop]; null if none. */
    fun pick(pins: PinSet, x: Float, y: Float, slop: Float): Pin?
    /** Move a pin to (x,y), clamped to 0..1 on both axes. Unknown id → the set unchanged. */
    fun move(pins: PinSet, id: String, x: Float, y: Float): PinSet
    fun remove(pins: PinSet, id: String): PinSet
    fun add(pins: PinSet, x: Float, y: Float, ids: () -> String): PinSet
}
```

**The rig JSON is the Studio's.** `PuppetRigJson` writes it, `PuppetRigValidator` checks it, and this
spec copies neither (R23). Joy Brush stores the pins it can describe and hands the rig to the Studio's
own writer. **This spec invents no rig format** — that is a hard constraint, not a style preference.

## Decisions

1. **Pins are NORMALISED to the board's rect, 0..1, y down.** Same convention as
   `AvatarRig.PartPose.pins`, so a Joy Brush pin and a Studio pin mean the same place with no
   conversion table — and the conversion table is where a second copy of the rig maths would creep in.
   Clamped to 0..1 on write, so a pin dragged off the edge comes back on the edge rather than at a
   coordinate nothing can resolve.
2. **Pins are stored on the DOCUMENT, not inside the rig.** A `.joybrush` file with pins in it can be
   reopened, edited and exported without the Studio. The rig remains the Studio's serialisation of the
   same pins at export time (JB-7.03). **This needs a new field on `Board` and therefore a
   `DOC_VERSION` bump to 3** — R3 is explicit that a new constant or field in a serialised file is a
   version bump, and the enum freeze test will catch it.
3. **`PinKind` is the Studio's three roles and no more.** `PuppetPalette` has PIN `#FBBF24`,
   STIFF `#F43F8E`, DANGLE `#22D3EE`, plus FREE/BONE/HELPER guide colours and LOCKED `#5A616B`, which
   are *drawing* colours for the Studio's own editor chrome rather than pin kinds. **Three kinds, and
   the guides stay in the Studio** — a fourth kind would need a colour and a behaviour the Studio does
   not have.
4. **A pin's colour is never chosen in Joy Brush.** The design record is explicit that pin colours come
   from `PuppetPalette` unchanged, and state colours are global (cyan = selected, pink = live) and
   never a section colour. So the board wears Avatar's `#CC27FF → #8C3DFA` and the pins wear
   `PuppetPalette`. No new tokens, and specifically no hex literal in Joy Brush code (D.01's rule).
5. **Placement is a tap with the tool finger; a pen always draws.** R5/JB-2.02's rule, unchanged:
   fingers are the tool finger once a pen has been seen. A tap on empty board adds a pin at that
   point; a tap **on an existing pin** starts dragging it (never adds a second one on top).
6. **Dragging a pin is one undo step** and, like every other drag here, is **cancelled by a second
   finger** — the same rule the drawing view already implements for a finger stroke. No destructive
   gesture anywhere: deleting a pin is a long-press with a visible confirm, never a swipe (blueprint
   §3.5).
7. **The hit slop is 24 SCREEN px, not document px**, for the same reason `StrokePicker` uses screen
   px: 24 px is a finger, and it must feel the same at every zoom.
8. **The first pin is the root and cannot be deleted** while other pins exist. Reason: it is the anchor
   the Studio's contour step hangs from, and deleting it silently would produce a rig that fails
   validation in a different app. Deleting the last remaining pin is allowed (that is deleting the rig).
9. **An empty board and a board with one pin are both valid** and both draw. Nothing here validates a
   rig — `AvatarRigValidator` does, in the Studio, and copying it is R23's prohibition.
10. **The pin list's size is bounded by pins, not by bytes.** Cap **512 pins per board**. Beyond that
    the board is not a puppet, it is a mess, and every pin costs Studio-side triangulation work.
    Refused in words, not truncated.

## Tests (`PinMathTest`, pure maths, `:core:jvmTest`)

1. **Normalised and clamped (D1):** `move` to x = 1.4 → 1.0; to −0.2 → 0.0. Two pins 0.1 apart in
   normalised space are the same distance apart after a board is resized to 4× — because there is no
   document px anywhere in `PinSet` (assert by rebuilding the same set and comparing, not by measuring).
2. **Pick honours the slop (D7):** at `slop = 0.05` a tap 0.04 away picks the pin, 0.06 away does not,
   and with two pins 0.02 apart the **nearest** wins; a tap 0.02 from each with a slop of 0.03 picks the
   more recently added (the same recency rule `StrokePicker` uses — one idiom, not two).
3. **Move / remove / add (D1, D6):** moving an unknown id returns the set `==`; removing the root from
   a 3-pin set returns the set `==` (D8); removing the last pin returns an empty set; `add` on a
   non-empty set appends (so placement order is order of placing).
4. **Non-finite coordinates never enter a `PinSet` (D1):** `add`/`move` with NaN or Infinity x or y
   returns the set unchanged. *This is the same "check in Long before any Int pixel arithmetic" family
   as R19 and the `Cell.fitsIntPixels` guard.*
5. **The pin cap (D10):** 512 pins is accepted; the 513th `add` returns the set unchanged **and** the
   caller gets a way to know why (assert the refusal is observable — either a nullable result or a
   thrown exception with a sentence; whichever you choose, the test names it).
6. **`PinSet` round-trips through `DocJson` (D2) and a v2 file without pins still decodes (D2):** a
   document encoded at `DOC_VERSION` 3 with a pin-carrying board decodes to the same pins; a document
   encoded at version 2 with no pins decodes with `pins = emptyList()` and **no** error. The second half
   is the forward-compatibility promise, and it is the assertion JB-0.02's review asked for and did not
   get on the save side — so it is worth having here.

## Visual check (T2-V's part, and it needs a human or a screenshot)

The board wears Avatar's violet→purple; the three pin kinds wear `PuppetPalette` exactly. Compare
against `tasks/joybrush/design/JOYBRUSH_VISUAL_LANGUAGE.md` §3.5 and the Studio's own pin rendering.
**No pin colour may be a Joy Brush token** — the design record lists the Avatar board's own gradient as
its identity and says pin colours are unchanged.

## Do not

- Do **not** write a rig format. No new JSON, no new schema, no `.avatar` writer here (JB-7.03, and it
  must go through the Studio's `AvatarLibrary`).
- Do **not** touch `app/`. Every one of R16/R18's app-file rows is serialised and this spec is not one
  of them.
- Do **not** copy `PuppetMeshBuilder`, `PuppetTriangulator`, `PuppetWeights` or `PuppetRigSolver` into
  Kotlin "for the cloud tests" — R23 forbids it and a second copy is how preview and export start
  disagreeing.
- Do not add a fourth pin colour.
- Do not make a pen place pins.

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-7.01: puppet board pins`; pushed
- [ ] ROADMAP row → 🟧 Built — **only after Q1 and Q2 are ruled**

## Questions — for the Lead

**Q1. BLOCKING. Is the puppet board allowed to depend on the app's puppet classes, and if so, when?**
The pins on screen are `PuppetOverlayView` (52 KB) + `PuppetPin` + `PuppetRig` + `PuppetRigJson`, all
in `app/src/main/java/com/fadcam/ui/faditor/puppet/`. Joy Brush's only module in the app build is
`:joybrush-android` (R23), which **can** import app classes. But R17 sequences **`D.03` — move
`transform/mesh/` into `:studiokit` — before "Phase 7's puppet board builds on the same engine"**, and
D.03 is `⚪ Outline` and Lead-designed. So:

- **(a) Build against the app classes now** from `:joybrush-android`, and do the D.03 move later as a
  pure move. Fast, but binds Joy Brush to file locations that are about to move.
- **(b) Wait for D.03**, and build against `:studiokit`. Cleaner, and Phase 7 slips behind a row nobody
  has specced.
- **(c) Build the pin UI with no solver dependency at all** — pins are stored, drawn and edited in Joy
  Brush; nothing in Joy Brush ever runs the rig. JB-7.02 is then the first thing that touches the
  solver, and it is a T1 row that would own that decision alone.

**My read is (c), and it is what this spec is written as** — `PinSet` is Joy Brush data, no rig, no
solver, nothing from `app/`. That keeps this row T2-V-buildable, keeps R23 intact, and puts the whole
Studio dependency in JB-7.02 where the board already says "through Avatar Studio's own solver". **Rule
it and the owner area stops being provisional.**

**Q2. BLOCKING for the owner area. Does this row edit `JoyBrushActivity.kt`?** Every board needs a case
in the board switcher, and that file is in `:joybrush-android` (allowed — R23 says integration code that
calls kit classes lives there) — but JB-2.03a, JB-2.06b, D.02c and D.05 all want that same file, and
the board's app-file order is `D.02a → D.02 → D.02c / D.05`, **one at a time**. This spec adds a fifth
claimant. **Either the board switcher becomes a shared `:studiokit` piece under D.02, or Phase 7 is
scheduled into the queue and takes its turn.** Which?

**Q3. `DOC_VERSION` 2 → 3 for the pin field (Decision 2).** R3 makes it mandatory and I have planned it
that way, but it is a document-format change and every other spec that reads `Board` will see a new
field. Alternative: pins live in a sidecar inside the `.joybrush` zip beside `document.json` (the
archive already has a `strokesFile` precedent), leaving `DOC_VERSION` alone. **Which do you want?** My
reason for the field is that a pin is part of what a board *is* and a sidecar is a second place to look
when a board has no pins because the sidecar failed to load.

**Q4. Low risk, ruled provisionally — say if you disagree.** The 512-pin cap (Decision 10) is a number
I chose. Nothing in the repo bounds pins, and I do not know Avatar Studio's real ceiling, so it may be
far too low or far too high. It is one constant.
