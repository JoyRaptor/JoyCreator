# Adversarial review — JB-3.01 animation model ops

- Reviewer: mimo (second adversarial pass; muse-spark did not review this task — this is its first review).
- Task status: 🟧 Built. Commit reviewed: `513ac900` (branch tip `b74aaf0e`).
- Spec: `tasks/joybrush/specs/JB-3.01_animation_model.md` (incl. Questions 1–5, unanswered by the Lead).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (AnimOpsTest 44).
- §5b checks: commit `513ac900` touches only `doc/AnimOps.kt` + `doc/AnimOpsTest.kt` (owner area). No edits by this reviewer.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: 2 BLOCKER, 2 MAJOR, 4 MINOR.** On a document `DocOps.validate` accepts, every operation is sound (I re-walked id generation, frame/cel atomicity and the linked-cel deletion logic, and confirmed the 300-step random-sequence test's discipline). Both BLOCKERs are cases the spec itself declares in scope — its parity clause and its own refusal contract — reachable because `DocJson.decode` never validates by design (`DocJson.kt:52-54` region; the builder's fps guard is justified with the identical argument).

## F1 (BLOCKER — wrong result; spec clause broken): the three time functions never check `holdFrames ≥ 1`, so `frameAt` confidently reports the wrong frame on a document `validate` calls broken — exactly the failure the spec's parity clause forbids

- **Proof (code):** `frameAt` (`AnimOps.kt:380`), `totalDurationMs` (`:402`), `frameStartsMs` (`:417`)
  guard only the rate: `playableFps` (`:449-458`, `fps !in 1f..60f`). The other half of rule 4 —
  `f.holdFrames < 1` (`DocOps.kt:82-84`, inside the rule-4 block opened at `DocOps.kt:77-78`) — is
  never checked on any read path. `durationMs` (`AnimOps.kt:431`) multiplies by `holdFrames` blind.
- **Proof (spec):** "**`AnimOps` must not be able to call a board playable that `validate` calls
  broken** … JB-3.0x: **do the same for any other number you do arithmetic on.**"
  (`JB-3.01_animation_model.md:97-99`). `holdFrames` is such a number.
- **Concrete input:** `Board(fps = 12f, frames = [Frame("f1", holdFrames = 0), Frame("f2", holdFrames = 1)])`.
  `validate` → `"board … frame \"f1\" is held for 0 frames"`. Then `frameAt(board, 0.0)`:
  `frameStartsMs` → `starts = [0.0, 0.0]` (f1 contributes 0 ms, `:421-424`); `timeMs = 0` is not
  `< 0` and not `>= totalDurationMs() = 83.33`; the loop `:388-390` accepts **both** starts
  (`0.0 <= 0.0` twice) → `chosen = 1` → returns **f2**; f1 is reachable only for `t < 0`.
  With *all* `holdFrames = 0`: total = 0.0 and starts all 0.0 → `frameAt` answers the last frame at
  every time — **verbatim the "confident wrong answer" the spec describes for the fps case**
  (`JB-3.01:95-97`).
- The builder's own KDoc agrees the harm is real — it guards only the *write* path: `setHold` clamps
  (`:332` region) with a comment that a hold of zero "would make `frameAt` and `totalDurationMs`
  meaningless"; the three readers do not refuse. No test feeds `holdFrames < 1` to any time function
  (verified: `AnimOpsTest.kt` has no such case; the fps refusal tests are `:583`/`:601` region).
- **Reachability, stated honestly:** archive read/write call `validate` first (in-flight untracked
  `JbArchive.kt:164,353`) and the mutating ops cannot *create* hold < 1. But the spec's own defence of
  the fps guard — "each is a value a hand-edited or half-written document really does contain"
  (`AnimOpsTest.kt:585` region) and `decode`-without-validate is the documented design — applies
  unchanged. Fix = one `if (f.holdFrames < 1) throw …` guard alongside `playableFps`, mirroring the
  fps message style.

## F2 (BLOCKER — contract broken, wrong result emitted): `addFrame` DUPLICATE/LINK refuses a missing mapping *key*, but not a mapping whose *value* is a cel the layer does not have — it then emits a `CopyCel` naming a nonexistent cel, and LINK makes a broken document strictly more broken

- **Proof (code):** `AnimOps.kt:227` `val sourceCelId = sourceFrameId?.let { layer.frameCel[it] }`;
  both throw sites fire only on `null` — `:230-233` (LINK), `:237-240` (DUPLICATE). The value is
  never checked against `layer.cels`. DUPLICATE then returns
  `CelWork.CopyCel(layerId, fromCelId = <phantom>, toCelId)` (`:248`), violating the file's own
  invariant "A [CopyCel] names ids that exist in the returned document" (`AnimOps.kt:12`), and the
  refusal contract "Refuses: … an animated layer that has **no cel for the source frame**"
  (`AnimOps.kt:186-189`).
- **Concrete input (DUPLICATE):** animated layer with `cels = [c-anim]` and
  `frameCel = {"f1" → "ghost", "f2" → "c-anim"}` where `"ghost" ∉ cels`
  (`validate` reports it: `DocOps.kt:125` region — "shows frame … with cel …, which it does not have").
  `addFrame(…, afterFrameId = "f1", DUPLICATE)` → no throw (`"ghost"` is non-null) → new cel
  `gen1`, mapping `gen0 → gen1`, and `CopyCel(fromCelId = "ghost")` handed to the engine — an
  instruction to copy tiles from a cel that is not in the document.
- **Concrete input (LINK), worse:** same document, `NewFrame.LINK` → new frame maps to `"ghost"`;
  output now reports the phantom **twice** (one problem per frame pointing at it): the operation
  turned 1 validation problem into 2, against "never hands back a half-applied one" (`AnimOps.kt:75`
  region).
- **Spec/test agree the case should throw:** the builder tested only the missing-*key* variant and
  wrote the principle in its own comment — test `addDuplicateRefusesWhenTheSourceFrameHasNoCelOnThatLayer`
  (`AnimOpsTest.kt:296-304`) says copying "would mean naming a cel that does not exist, so it is
  refused in words". A mapping to a cel not on the layer is the same words. Spec is not wrong;
  the check is half-implemented. One `if (sourceCelId != null && sourceCelId !in layer.cels.map { it.id }) throw …`.

## F3 (MAJOR — data loss on an input the document rules name): `deleteFrame` with duplicate frame ids deletes only one copy but removes the shared mapping, dropping the live cel and leaving the surviving frame with a hole

- **Proof (code):** `AnimOps.kt:291-292` `frames.removeAt(index)` removes the *first* matching
  frame only; `:301` `celId = layer.frameCel[frameId]`; `:304-305` the mapping is keyed by **id**, so
  `map.remove(frameId)` removes the mapping for **both** copies; `:306-308` `!map.containsValue(celId)`
  is then true → the cel is filtered out of `cels` and `DropCel` is emitted.
- **Concrete input:** frames `[Frame("f1"), Frame("f1")]`, `frameCel = {"f1" → "c-anim"}`,
  `cels = [c-anim]`. `validate` → one problem ("two frames called f1", `DocOps.kt:80`).
  `deleteFrame(…, "f1")` → frames `[f1]`, `frameCel = {}`, `cels = []`, `DropCel(c-anim)` —
  the surviving frame has **no cel** (new problem: "has no cel for frame f1") and the user's ink
  was handed to the engine to free.
- Duplicate frame ids are refused by `validate` and unreachable from the archive path, but the same
  is true of every input the spec's own fps guard treats as in-scope; and the file promises `DropCel`
  only for a cel no remaining frame points at — here the surviving `f1` still needs it.
  No test anywhere in `AnimOpsTest.kt` feeds duplicate frame ids (verified by grep).

## F4 (MAJOR — spec test clause not implemented): spec test clause 7 requires `moveFrame` to prove "the static layer and other boards are unchanged" — no `moveFrame` test asserts it

- **Proof (spec):** "7. moveFrame reorders; **the static layer and other boards are unchanged**."
  (`JB-3.01_animation_model.md:66`); Decision 1 extends it: "assert this in every test" (`:50` region).
- **Proof (test):** the four `moveFrame` tests are `AnimOpsTest.kt:618, 633, 640, 648`; grep for
  `l-static`/`b-canvas` in that range returns only the refusal fixture at `:655`. The equivalent
  assertions DO exist for the sibling ops — `addFrameLeavesLayersThatDoNotAnimateHereAlone` (`:273`),
  `deletingAFrameLeavesOtherBoardsAndStaticLayersAlone` (`:446`) — so this is a gap, not a design
  choice. Production behaviour appears sound (`withFrames` at `:484-85` region touches only `boards`),
  but the spec asked for a proof and there isn't one.

## MINOR findings

- **m1 (MINOR):** Decision 1's "assert `validate` clean … in every test" (`JB-3.01:50`) is not met —
  `theScheduleAndTheTotalAgreeExactly` (`AnimOpsTest.kt:465`), `holdsChangeWhatIsShowingWhen` (`:559`
  region), `moveFrameToTheSameIndexChangesNothing` (`:633`) produce documents and never call
  `DocOps.validate`. Assertion discipline only; behaviour verified elsewhere by the 300-step
  property test (`:789` region, validates every step).
- **m2 (MINOR):** `moveFrame` returns the *same instance* on a no-op (`AnimOps.kt:357`
  `if (toIndex == from) return doc`) while Decision 1 and the file KDoc say every function returns a
  new document (`:80-81` region). Value-equal, no behaviour harm; the wording and code disagree.
- **m3 (MINOR, spec self-conflict, untested):** the spec says an empty board has `total = 0.0` and
  empty starts **and** that a bad fps makes all three functions refuse (`JB-3.01:91-93`);
  `playableFps` runs before the frames loop (`AnimOps.kt:403,418`), so an empty board with `fps = 0f`
  throws rather than returning 0.0. The code's choice (refuse) is defensible; the spec should pick one.
  Test `aBoardWithNoFramesHasNothingToShowAndNoLength` uses fps 12 only.
- **m4 (MINOR):** `addFrame(BLANK)` on an empty board (blessed by the builder's Question 2) maps only
  the new cel, so the layer's original cel becomes unreachable forever — `animateLayer` refuses an
  already-animated layer (`:123` region) and `deleteFrame` only considers mapped cels
  (`:301`), so no operation ever drops or re-shows it (orphan = valid per Q5, but it is created by
  the blessed path). Needs a Lead ruling, not a patch.

## Verified sound (checked independently)

- **Valid input → valid output, every op.** Flat id uniqueness across boards/frames/layers/cels
  (`usedIds` `:515-526` + `freshId`), pinned by `addFrameRefusesAnIdTheDocumentAlreadyUses` (`:318`
  region); frames+layers written atomically; pinned by `aLongRandomSequenceOfOperationsNeverProducesAnInvalidDocument`
  (`:789` region — 300 random ops, `validate == []` after each, structural not generation-counter
  assertions — this is the test the orchestrator's log says was rebuilt properly).
- **Linked cels:** cel kept while any frame maps it, dropped exactly when the last mapping goes —
  `deletingAFrameThatHoldsACelAnotherFrameStillShowsKeepsTheCel` (`:336` region), `…DropsTheCelOnlyOnTheSecond` (`:360`).
- **Timing exactness:** `durationMs` multiplies before dividing (`:431`) → `threeTicksAtTwelveFramesPerSecondIsExactly250Ms`
  with delta 0.0; `frameStartsMs` last start + last length == `totalDurationMs` exactly (`:465` test);
  `Int.MAX_VALUE` hold cannot overflow (Double promotion before multiply).
- **fps guard parity with validate** for all three functions incl. NaN/±Inf/0.9/120 —
  `aBoardThatCannotBeTimedIsRefusedInWords` (`:583`), `theRateGuardIsTheModelsOwnRuleAndSaysSo` (`:601`).
  This is the model F1 asks to extend to `holdFrames`.
- **frameAt edges:** boundary belongs to the starting frame (`:514`), negative → first, past end →
  last incl. `Double.MAX_VALUE` (`:526`), NaN → first (`:538`), empty board throws (`:573`).
- **Move semantics:** to 0/last/middle/self, refusals at `size`/`−1`/unknown/non-ANIMATION;
  holds travel with frames (`:660`); matches Question 1's "final index" reading (still Lead-unratified).
- **Determinism:** `LinkedHashMap` everywhere order matters (`:304` comment; `frameCel + pair`),
  work order follows `doc.layers`; `DocJson.canonical` sorts keys on encode (`DocOps`/`DocJson.kt:41-49`).
- **Purity:** commit touches only the two owner files; input document never mutated (`:682` test).

## Open questions for the Lead (neither answered in `LEAD_RULINGS.md`)

- Q1 (`toIndex` = final index; `size` refused) — contract line states no range; JB-3.03's film strip
  needs the answer.
- Q2 (fps guard / empty-board conflict — see m3), Q4 (flat cross-namespace id refusal stricter than
  contract), Q5 (validator doesn't sweep orphan cels — interacts with m4).
