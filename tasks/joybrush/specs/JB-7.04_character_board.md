# JB-7.04 — Character board: expression and mouth boards wired into a head, parallax, look-at

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 🟨 **Draft — this is the row the blueprint says to build LAST, and the reason is a contract question, not a workload question.** |
| **Needs** | JB-7.03 (a character exists and is in the library), JB-4.03 (sprite boards / sheets). **Neither is Built; JB-7.03 is Draft; JB-4.03a is Built but JB-4.03 (the board) is `⚪ Outline`.** |
| **Owner area** | *(cannot be fixed until Q1.)* Provisionally: NEW `joybrush/core/.../core/puppet/CharacterGraph.kt` + test; NEW `joybrush-android/.../board/CharacterBoard.kt`. **No file in `app/`.** |
| **Estimated size** | ~400 lines + ~200 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` (the wiring maths) and `:androidkit:compileKotlin :androidkit:test` |

## Why this is a draft

Blueprint line 40, on wiring boards into a character: *"A strong vision and already half-built: an
`AvatarRig` **is** parts that reference sprite sheets plus a mouth-shape map. Change: Joy Paint makes
the parts and draws the wires; **Avatar Studio stays the one place rig behaviour is edited. Build it
last.**"*

Blueprint line 72 calls the Character board "(later)". JB-7.03's Q2 is still open and this row depends
on the answer — a character is a set of `Part`s, and whether a board is one part or several is
undecided. Meanwhile JB-4.03 (the sprite **board**) is `⚪ Outline`, so the mouth board it needs does
not exist as a thing a user can make.

What I can write honestly is the **wiring**, because that is Joy Brush's and it is pure maths with
real tests: which parts exist, what each one is driven by, how the mouth map resolves, and what
parallax and look-at mean as numbers. What I cannot write honestly is the rig JSON — that is
`AvatarRig`'s (`PoseDomain` with `driverX`/`driverY`, `cols`/`rows`, `Cell`, `PartPose`, and
`visemeMap`), it lives in `app/`, and **this spec does not invent a rig format.** So the Questions are
the substance of this row.

## Goal

Blueprint §4 Phase 7, second half: *"Then the Character board: wire expression and mouth boards into a
head, parallax, look-at."*

You draw a head on one board. You draw an expression sheet on another. You draw mouth shapes on a
third. The character board **wires them together** — the head moves a little, the mouth shows the shape
the current state asks for, and a look-at proxy turns the whole thing towards where the pen is.

## Contract — the wiring, not the rig

```kotlin
package cc.joycreator.joybrush.core.puppet

/** Which board supplies which part of the character. Ids are BOARD ids, never file paths. */
data class CharacterWiring(
    val head: String,                       // board id of the head
    val expression: String? = null,         // board id of an expression sheet (a sprite board)
    val mouth: String? = null,              // board id of a mouth-shape sheet (a sprite board)
    val parallax: List<String> = emptyList(),// board ids, back to front
    val lookAt: Boolean = false,
)

/** One named driver and where it reads from. The values are the Studio's parameter names (Q3). */
data class Wire(val from: WireSource, val to: String, val amount: Float)
sealed interface WireSource {
    data class Driver(val name: String) : WireSource        // a rig driver, e.g. yaw / pitch
    data object PenTilt : WireSource
    data object PenAzimuth : WireSource
    data class ExpressionCell(val col: Int, val row: Int) : WireSource
    data class MouthViseme(val viseme: String) : WireSource
}

object CharacterGraph {
    /** Every problem with [w], in words. Empty = it wires up. Never throws for a user mistake. */
    fun validate(w: CharacterWiring, boards: List<Board>, kinds: Map<String, BoardKind>): List<String>

    /**
     * The parallax offset per layer for a look direction ([yaw], [pitch]) in −1..1, doc px.
     * Depth is the layer's index from the back: 0 does not move, 1 moves the most. Every number
     * non-finite or out of range is read as 0 — a bad slider must not put a layer off the board.
     */
    fun parallax(w: CharacterWiring, yaw: Float, pitch: Float, amountPx: Float): List<OffsetPx>

    /** The expression cell a pose selects, or null when the expression board is absent. */
    fun expressionCell(w: CharacterWiring, state: Map<String, Float>): CellRef?

    /** The mouth shape a viseme asks for, or null. Never guesses a shape it cannot name. */
    fun mouthShape(w: CharacterWiring, viseme: String): CellRef?
}

data class CellRef(val boardId: String, val col: Int, val row: Int)
data class OffsetPx(val boardId: String, val dx: Float, val dy: Float)
```

## Decisions

1. **The wiring names BOARD ids; it never names files, paths or layer indices.** A board id is a
   document fact that `DocOps.validate` already checks for uniqueness, and a board can be renamed or
   moved without the wiring breaking. This is the same reason `StrokeEdit` keeps a stroke's `id`
   through every edit.
2. **Every wire is explicit and named, and there are exactly these sources** (Decision: no
   `ANYTHING_ELSE`). An unwired character is a valid character; a wire that cannot be resolved is a
   **problem in words**, never a silent no-op. This is the JB-8.03 lesson applied before it is learned
   again.
3. **Parallax is arithmetic on a depth index, not a rig feature.** Back layer = index 0 = does not
   move; front layer = the largest index = moves the most, by `amountPx`. Horizontal offset is
   `depth × yaw × amountPx`, vertical is `depth × pitch × amountPx × PARALLAX_Y_SCALE` (0.6 — heads
   turn further than they nod, and it is one constant). Non-finite or out-of-0..1 inputs read as 0:
   a bad slider puts nothing off the board.
4. **Look-at is a proxy, never a measurement.** Joy Brush has no face tracker, and building one is not
   in the blueprint. So look-at reads **the pen's tilt and azimuth** — which is exactly what the pen
   gives us for free and what makes a puppet board feel alive on a Note — and that is *all* it reads.
   A future camera-driven source is a new `WireSource`, added then.
5. **A missing board is a named problem, not a crash and not an empty board.** `validate` returns one
   sentence per wire that cannot resolve, naming the board id and why (gone, wrong kind, not a sprite
   board). The UI shows the list and draws whatever does resolve, with the rest visibly unwired.
6. **A mouth shape is chosen by NAME, and an unknown name resolves to nothing.** `mouthShape` returns
   null for a viseme with no cell. **It never falls back to cell 0** — a mouth that silently shows a
   neutral shape when it was asked for an "F" is a puppet that lies about what it is saying, and the
   owner can see it.
7. **An expression cell outside the sheet is no cell.** `expressionCell` returns null rather than
   clamping to the nearest cell. Same reason as Decision 6: a clamped cell is a face showing an
   emotion nobody asked for.
8. **The Character board wears Avatar's violet→purple** (design record §3.5, and the lobby's
   "Character" chip already sends to that gradient), with a distinct glyph. The two-colour rule holds:
   the record explicitly rejects a three-stop Sprite→Avatar ramp for exactly this board, and I am not
   second-guessing the owner on a point they already ruled.
9. **Nothing in this file is a rig.** The output is a wiring plus numbers; turning either into an
   `AvatarRig` is JB-7.03's export path calling the Studio's writer. No JSON, no schema, no version.
10. **Rewiring is one undo step and never a destructive gesture** — deleting a wire is a long-press
    with a confirm, like deleting a pin (JB-7.01 Decision 6).

## Tests (`CharacterGraphTest`, `:core:jvmTest`)

1. **Ids, not paths (D1):** two wirings differing only in which board id is the head give different
   parallax offsets, and a wiring naming a board id that is not in `boards` produces a `validate`
   sentence containing that id.
2. **Every unwired wire names itself (D2, D5):** `validate` on a wiring whose head board does not
   exist returns exactly one sentence, containing the board id and the word for why. A wiring with a
   missing `expression` but a good `head` returns **one** sentence, not two and not zero.
3. **Parallax is ordered by depth (D3):** with three parallax boards, `parallax(1, 0, 100)` gives
   `dx` of 0, −100, −200 for depths 0, 1, 2 — **the front layer moves the most**, and the signs are
   stated because a character whose layers drift apart on the wrong axis is a bug nobody notices until
   a face turns.
4. **Parallax degrades, never explodes (D3):** `yaw = Float.NaN`, `yaw = 5f`, `amountPx = Float.NaN`
   and `amountPx = -20f` all give **0 offsets for every layer** — no NaN in any output, no exception.
   (Same family as `StrokeSmoother`'s two untrusted numbers and R19's Long-before-Int.)
5. **Vertical parallax is scaled (D3):** at yaw 0 and pitch 1, the front layer's `dy` is
   `0.6 × amountPx` exactly — the derivation is in the test, per R9's standing rule.
6. **An unknown viseme is no mouth (D6):** `mouthShape` for a viseme the sheet has no cell for is
   null; for one it has, it is the right `(col, row)`. Non-vacuity: assert the **known** viseme is NOT
   null in the same test, so a sheet that parsed as empty cannot pass both halves.
7. **An out-of-range expression cell is no cell (D7):** a cell at `col = 99` on a 3×3 sheet is null,
   not `2`.
8. **A missing mouth board is no mouth, not a crash (D5, D6):** `mouthShape` on a wiring with
   `mouth = null` is null, for every viseme.
9. **Round trip through `DocJson` (D1):** a document with a `CHARACTER` board whose wiring is stored on
   the board decodes to the same wiring; a version-2 document with none decodes to a wiring of all
   nulls and no error. (`BoardKind.CHARACTER` already exists, so no enum constant is added — but the
   wiring is a new field, so `DOC_VERSION` goes to 3, which JB-7.01's Q3 also asks about; **they must
   land together**.)
10. **Unwiring is one step (D10):** changing the head id and changing it back is two steps, and both
    restore exactly.

## Do not

- Do **not** write an `AvatarRig`, a rig JSON, a `PoseDomain`, a viseme map or a schema version. They
  are the Studio's.
- Do **not** add a camera/face-tracking source. Decision 4 says where look-at's numbers come from.
- Do **not** fall back to a default cell for a name that is not in the sheet (Decision 6) — that
  single fallback is the difference between a puppet and a liar.
- Do not edit `app/`, and do not add a `BoardKind` constant (R3: `CHARACTER` is already there).
- Do not build a mouth board here. JB-4.03 owns the sprite board.

## Definition of done

- [ ] tests pass (paste output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-7.04: character board wiring`; pushed
- [ ] ROADMAP row → 🟧 Built — after Q1

## Questions — for the Lead

**Q1. BLOCKING, and it is inherited from JB-7.03 Q2: is a character one part or several?** The board
says "wire expression + mouth boards into a head", which implies the head is a part and the expression
and mouth are **inputs to it** — not parts in their own right. But `AvatarRig` models everything as
`Part`s each naming a `sheetId`, with `PoseDomain` cells choosing `PartPose`s. **Those two models do not
obviously fit**, and the fitting is a rig decision: either Joy Brush expresses "the head is one part
whose expressions come from a second sheet" through whatever the Studio's rig already supports, or the
Character board is something the Studio must grow. **I am not writing the rig.** What the ruling must
cover: whether this row is *wiring into an existing rig vocabulary* (in which case Q3 is the whole
spec) or *a request for the Studio to grow one* (in which case this row is blocked on an app-side task
that has to be scheduled and serialised first).

**Q2. Is the Character board even in scope for Joy Brush, or is it Avatar Studio's own screen?**
Blueprint line 40: *"Joy Paint makes the parts and draws the wires; Avatar Studio stays the one place
rig behaviour is edited."* That reads as: Joy Brush's job ends at producing parts and a wiring, and
**the editing of rig behaviour happens in the Studio**. If that is right, this row is *make the parts
and hand them over* — not *edit a character here* — and that is a much smaller row with a much clearer
end. If instead the owner wants to wire characters up inside Joy Brush, the row needs a Studio change
to accept them back, and that is a different conversation. **My read is the first**, and I have written
the maths that way; the UI half of this spec is deliberately absent because I do not know which half it
is.

**Q3. What are the driver and viseme names?** `WireSource.Driver(name)` and `WireSource.MouthViseme`
carry strings, and those strings have to be the Studio's (`AvatarRig.PoseDomain.driverX` is `"yaw"` in
the sample I read, and `rig.getVisemeMap()` is a `Map<String, Integer>`). **I will not guess a
vocabulary.** The ruling must either give the legal driver names and the legal viseme names, or say
that Joy Brush drives a fixed named set and the Studio matches on it. Without this, the "wire" is a
string that either works or does nothing.

**Q4. `DOC_VERSION` 2 → 3 is asked for twice (JB-7.01 Q3 and this row's Decision 9).** Both rows want
a new field on `Board`. **They must be one version bump in one edit**, or the second one to land writes
a file the first cannot read. Should I fold the two fields into one small spec so they cannot race,
or leave them separate with an explicit "do not run in parallel" note?

**Q5. Low risk, ruled provisionally.** `amountPx` (Decision 3's parallax travel) has no default here —
the caller passes it, presumably from a slider — and `PARALLAX_Y_SCALE = 0.6` is my number. Both are
one constant each. The one I would most like a ruling on is the sign convention in test 3: a yaw of +1
moving the front layer **−100 px** is a choice, and the other choice looks just as defensible.
