# Adversarial review — JB-3.01 `AnimOps` (`holdFrames` guard, `addFrame` value check, `playableBoard` duplicate-id refusal)

- Reviewer: claude (second adversarial pass; **two reviewers already attacked this task** —
  `tasks/joybrush/reviews/JB-3.01__muse-spark.md` (1 MINOR, closed) and
  `tasks/joybrush/reviews/JB-3.01__mimo.md` (2 BLOCKER, 2 MAJOR, 4 MINOR; F1/F2/F3 are the three
  fixes I was asked to verify). I state AGREE/DISAGREE explicitly.
- Task status: 🟧 Built. Suite run by me at HEAD: `./gradlew -p joybrush :core:jvmTest` → 659 tests,
  1 failure, **not** in this task.
- Spec reviewed: `tasks/joybrush/specs/JB-3.01_animation_model.md` (contract, Decisions 1–5,
  tests 1–7, builder Q1–Q5).
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict on the three named fixes: all three are CORRECT and all three are NON-VACUOUS.** I
reproduced each pre-fix failure and each post-fix guard, and I say so below with the input.

My one finding is a **blanket contract sentence in the class KDoc that is false for a broken
input**, plus a note that mimo's F1 is the interesting one.

---

## Finding 1 (MINOR — FALSE CLAIM, unqualified contract sentence): "every operation returns a
## document that `DocOps.validate` is happy with, or throws" is false for a document that was
## already broken

**File:line.** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/AnimOps.kt:74-78`:

> *"So the rule this object follows is: **every operation returns a document that
> [DocOps.validate] is happy with, or throws.** It never repairs a broken document quietly and it
> never hands back a half-applied one."*

**The input.**

```kotlin
val doc = JbDocument(
    layers = listOf(
        Layer(id = "L", name = "L", kind = LayerKind.PAINT, visible = true, opacity = 1f,
              blend = BlendMode.NORMAL, animatedIn = "b", cels = listOf(Cel("c1")),
              frameCel = mapOf("f1" to "ghost")),        // "ghost" is NOT in cels
    ),
    boards = listOf(
        Board(id = "b", name = "b", kind = BoardKind.ANIMATION, fps = 12f,
              frames = listOf(Frame("f1", holdFrames = 1))),
    ),
)
```

`DocOps.validate` reports this (rule 7 / the animated-layer mapping rule, `DocOps.kt:116-125`):
*"shows frame f1 with cel ghost, which it does not have"*.

**What happens now:**

| call | result |
|---|---|
| `addFrame(doc, "b", "f1", NewFrame.BLANK) { "gen0" }` | **no throw.** The value check at `AnimOps.kt:254` is gated `mode != NewFrame.BLANK && sourceCelId != null && …`, so `BLANK` skips it entirely; the `NewFrame.BLANK` arm at `:284-292` only *adds*. The returned document still names `"ghost"`. |
| `addFrame(doc, "b", "f1", NewFrame.DUPLICATE) { "gen0" }` | **throws** — `:254-261`. |
| `addFrame(doc, "b", "f1", NewFrame.LINK) { "gen0" }` | **throws** — `:254-261`. |
| `deleteFrame(doc, "b", "f1")` | **no throw.** No input-validity check exists on this path at all; the returned document still names `"ghost"`. |
| `setHold(doc, "b", "f1", 3)` | **no throw**, same document still names `"ghost"`. |
| `moveFrame(doc, "b", "f1", 0)` | **no throw** (early-returns `doc` at `:396`), same. |

So two of the six operations on the same document refuse and four hand the broken document
straight back. `playableBoard` (`:568-582`) checks exactly two things — `kind == ANIMATION` and no
duplicate frame id — and neither is a validity check.

**The KDoc's second sentence is honoured; the first is not.** "It never repairs a broken document
quietly" is true — the phantom is preserved, not swept. "It never hands back a half-applied one" is
true in the sense the author means (frames + layers are written atomically, `:612-620`). But
"returns a document `validate` is happy with, **or throws**" is an unconditional promise about the
*return value*, and on a broken input four of the six operations return a document `validate`
rejects without throwing.

**Note the asymmetry is a decision, not an oversight** — `animateLayer` *does* refuse a broken
input, and says why at `:154-166` ("*fixing them here would be a quiet repair, and this object
never repairs quietly*"). So the object has two policies (refuse-broken-input for `animateLayer`,
pass-it-through for the frame ops) and the class KDoc states a third.

**Severity: MINOR.** Garbage in, garbage out; nothing paints a wrong frame, and no production path
can produce this input (`DocJson.decode` deliberately does not validate, and the archive read/write
path calls `validate` first). This is the same class as mimo's m1 (assertion/wording discipline)
and the same input class as their F1/F2, which the project already treats as in-scope. Fix: qualify
the sentence — "*every operation returns a document that is no **less** valid than the one it was
given, and every operation on a document `validate` accepts returns a document `validate`
accepts*" — which is the true and useful claim.

**Not a spec contradiction.** Decision 1 says "*`DocOps.validate` must return no problems after
every operation **on a valid input***" (`JB-3.01_animation_model.md:49-50`). The code matches the
spec. **The class KDoc is the thing that overstates it.**

---

## The three named fixes: correct, and non-vacuous (with the failing input for each)

### 1. `playableSchedule`'s `holdFrames` guard — `AnimOps.kt:512-520` — **AGREE with mimo F1, fixed and non-vacuous**

```kotlin
val fps = playableFps(board)
for (frame in board.frames) {
    if (frame.holdFrames < MIN_HOLD_FRAMES) {
        throw DocException(
            "board \"${board.id}\" holds frame \"${frame.id}\" for ${frame.holdFrames} frames, " +
                "so its schedule has no length in time (a frame is held for at least " +
                "$MIN_HOLD_FRAMES frame)",
        )
    }
}
```

**Pre-fix failure, reproduced by trace against the code as it stood** (mimo F1, which I confirm):
`Board(fps = 12f, frames = [Frame("f1", 0), Frame("f2", 1)])`. `durationMs` (`:475`) multiplies by
`holdFrames` blind, so `f1` contributes 0 ms and `frameStartsMs` returns `[0.0, 0.0]`. `frameAt(board, 0.0)`
(`:420-432`): `0.0 < 0.0` false, `0.0 >= 83.33` false, then the walk at `:428-430` accepts **both**
starts (`0.0 <= 0.0` twice) → `chosen = 1` → returns **`f2`**. So `f1` was reachable only for
`t < 0`, and with *all* holds 0 the total is 0.0 and every frame at every time answers the last one
— the "confident wrong answer" the spec's Q2 forbids in so many words.

**Post-fix:** all three time readers route through `playableSchedule` (`:424`, `:443`, `:461`), so
the guard is reached by `frameAt`, `totalDurationMs` and `frameStartsMs` alike, and it throws.
**Non-vacuity:** the guard is a *precondition on real data*, not a tautology — the test
`aBoardThatCannotBeTimedIsRefusedInWords` has to construct a `holdFrames = 0` frame to reach it,
and I confirmed the suite contains no such input on any other test's fixture. The guard is also
placed **after** `playableFps` and **before** any arithmetic, so there is no path where a zero hold
reaches `durationMs`. ✓

One **MINOR nit, not filed separately**: the message reads "a frame is held for at least **1
frame**" — `$MIN_HOLD_FRAMES` is a *tick count* at the board's fps, so "at least 1 frame" is the
wrong unit next to "is held for 0 frames" in the same sentence (`DocOps.kt:83` has the same
phrasing). A frame held for 1 tick is not "held for 1 frame". Purely cosmetic; I am recording it
rather than filing it, because the number is right and the sentence is unambiguous once you know
the domain.

### 2. `addFrame`'s mapping-**value** check — `AnimOps.kt:254-261` — **AGREE with mimo F2, fixed and non-vacuous**

```kotlin
if (mode != NewFrame.BLANK && sourceCelId != null &&
    layer.cels.none { it.id == sourceCelId }
) {
    throw DocException(
        "layer \"${layer.id}\" shows frame \"$sourceFrameId\" with cel \"$sourceCelId\", " +
            "which it does not have, so a new frame cannot be copied from or linked to it",
    )
}
```

**Pre-fix failure, confirmed.** Before the fix both throw sites (`:264-267` LINK, `:270-274`
DUPLICATE) fired only on `sourceCelId == null`. With the document in Finding 1 above,
`addFrame(…, "f1", DUPLICATE)` returned a new cel plus `CelWork.CopyCel(layerId = "L",
fromCelId = "ghost", toCelId = "gen1")` — an instruction to the engine to copy tiles out of a cel
that does not exist, violating the file's own invariant at `:12` ("*A [CopyCel] names ids that exist
in the returned document*"). `LINK` was worse: it wrote `"f1"`'s new sibling frame onto `"ghost"`
too, turning **one** validator problem into **two** against "*never hands back a half-applied one*"
(`:75-76`).

**Post-fix:** both `DUPLICATE` and `LINK` throw, before any id is drawn and before any document is
built, so no partially-applied state is ever produced.

**Non-vacuity — this is the good one.** The test that pins it,
`AnimOpsTest.kt:341 addDuplicateAndLinkRefuseWhenTheSourceFramePointsAtACelTheLayerDoesNotHave`,
uses a fixture whose `frameCel` names a cel **not** in `cels`. That is exactly the distinguishing
input: the *key* is present, so the pre-fix `?: throw` sites never fired, and the *value* is
phantom. So the test would have **failed against the pre-fix code** and passes now. I checked the
builder's own comment on the sibling test (`addDuplicateRefusesWhenTheSourceFrameHasNoCelOnThatLayer`,
`AnimOpsTest.kt:296`) — it was written for the *missing-key* case, and this one is a genuinely
different input. This is the correct shape of pin.

Also correct: the check is inside the `for (layer in doc.layers)` loop and after the
`if (layer.animatedIn != boardId) { … continue }` guard (`:242-245`), so it applies to exactly the
layers the operation touches and to no others.

### 3. `playableBoard`'s duplicate-frame-id refusal — `AnimOps.kt:568-582` + `firstRepeatedFrameId`
(`:585-589`) — **AGREE with mimo F3, fixed and non-vacuous**

```kotlin
val repeated = firstRepeatedFrameId(found)
if (repeated != null) {
    throw DocException(
        "board \"$boardId\" has two frames called \"$repeated\", so a frame id does not say " +
            "which frame is meant; that is a broken document, and no frame operation can " +
            "work out which of the two was meant — fix that first",
    )
}
```

**Pre-fix data loss, confirmed.** `frames = [Frame("f1"), Frame("f1")]`,
`frameCel = {"f1" → "c-anim"}`, `cels = [c-anim]`. Pre-fix `deleteFrame` removed only the *first*
matching frame (`:331`), then removed the mapping by **id** (`:344`), so `map` became empty, so
`!map.containsValue("c-anim")` was true (`:345`) → the cel was filtered out of `cels` **and**
`DropCel("c-anim")` was emitted for the engine to free. The surviving `f1` was left with **no cel**
and the user's live cel was handed to the free list. The KDoc at `:313-318` now names this exact
scenario as the reason for the refusal, and `:563-566` names the old mechanism ("*Picking one is how
`deleteFrame` used to lose a cel*"). That is the failure the review asked for and it is closed.

**Non-vacuity:** the guard is reached from all five mutating operations (`animateLayer :140`,
`addFrame :216`, `deleteFrame :321`, `setHold :369`, `moveFrame :388`), so the pre-fix data-loss
path is unreachable through the public API, and `firstRepeatedFrameId` returns the id of the
*second* occurrence, which is what the message quotes. The cost is the one the KDoc claims: a
person gets one sentence instead of a silently truncated board. ✓

---

## Verified CORRECT (beyond the three named fixes)

* **The spec's Decision 1's actual requirement is met.** "*on a **valid** input*" —
  `JB-3.01_animation_model.md:49-50`. The 300-step randomised property test
  (`AnimOpsTest.aLongRandomSequenceOfOperationsNeverProducesAnInvalidDocument`) calls
  `DocOps.validate` after every step and asserts empty, with **structural** assertions rather than
  generation-counter ones. That is the right test: I checked the property is what is being
  asserted, not an echo of the implementation's own bookkeeping.
* **`deleteFrame`'s cel-drop condition is the right one.** `:345` tests `!map.containsValue(celId)`
  against the **post-removal** map, so a linked pair keeps the cel until the last mapping goes
  (pinned by `deletingAFrameThatHoldsACelAnotherFrameStillShowsKeepsTheCel` and
  `…DropsTheCelOnlyOnTheSecond`). The `DropCel` invariant at `:24-28` ("*Only ever emitted for a cel
  nothing else points at*") is therefore true by construction. ✓
* **`durationMs` multiplies before dividing** (`:475`), so `holdFrames = 3` at 12 fps is exactly
  250.0 — and `frameStartsMs`'s last-start-plus-last-length equals `totalDurationMs` exactly
  because both walk the same expression in the same order (`:461-470`, pinned by
  `theScheduleAndTheTotalAgreeExactly`). No accumulated drift. ✓
* **The fps guard is the validator's own rule in the validator's own idiom**, and I checked *why*
  that matters rather than accepting it: `!in` (`:543`) rather than `<`/`<=`, so `+Infinity` and
  `NaN` are both caught. A `fps <= 0` guard would let `Infinity` through, and then every frame's
  length is `x / ∞ = 0`, `totalDurationMs` is 0.0 and `frameAt` reports the last frame at every
  time. The KDoc at `:532-539` writes that out. This is the same argument the `holdFrames` guard
  now completes, and mimo's F1 was right that it had to be extended. ✓
* **Ids are drawn in a fixed shape and collisions are refused against a flat cross-namespace set**
  (`usedIds :633-644`, `freshId :647-656`), which is stricter than `DocOps.validate`'s per-list rule
  and is disclosed as such in the spec's Q4. The id-shape is for diagnosis only, and the suite
  asserts structure everywhere except the one test that pins the shape on purpose — the exact
  discipline the spec's Q4 describes learning the hard way. ✓
* **`moveFrame` cannot desynchronise play order from the cel mapping**, because `frameCel` is keyed
  by frame id and not by position (`:383-385`), and `withFrames` (`:602-603`) touches only `boards`.
  `toIndex == from` returns the same instance (`:396`), which mimo filed as m2 — I agree it is a
  wording mismatch with the class KDoc's "every function returns a new [JbDocument]" (`:88-89`) and
  that it is value-equal and harmless. **Not re-filed.**
* **An empty board is handled as the spec's Q2 rules** (`:500-503`): `playableSchedule` is total over
  an empty frame list, so `totalDurationMs` is 0.0 and `frameStartsMs` is `[]`, while `frameAt`
  throws (`:421-423`) because it must return a `Frame`. I agree with the spec that this is the
  right split and with mimo's m3 that the spec's Q2 sentence and its own `fps` rule conflict for
  the *empty board with a bad fps* case; the code's choice (refuse) is defensible and the KDoc at
  `:437-441` states the three refusal reasons explicitly. **Not re-filed** — it is mimo's, and it is
  a spec-wording ruling, not a defect.

---

## Recommendation

No send-back. The three fixes I was asked to attack are **correct and non-vacuous**, and two of them
(F1, F2) close genuine wrong-answer and phantom-instruction bugs with pins that would have failed
against the pre-fix code. That is the outcome §5b item 2 wants.

One MINOR (Finding 1): qualify the class KDoc's "returns a document `validate` is happy with, or
throws" so it matches what the code does — no *less* valid than the input, strictly valid for a
valid input, which is the spec's Decision 1 verbatim. Consider also naming the two policies
explicitly (refuse-broken-input for `animateLayer`, pass-through for the frame ops) so the next
reader does not have to rediscover the asymmetry.

The open Lead questions in the spec (Q1 `toIndex`, Q2 empty-board/fps wording, Q4 flat id
strictness, Q5 orphan cels) remain open and remain the Lead's; nothing in this review resolves any
of them.
