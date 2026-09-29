# Adversarial review — JB-3.01 Animation model ops (frames, holds, duplicate/link, timing)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `513ac900`.
- Spec reviewed: `tasks/joybrush/specs/JB-3.01_animation_model.md` (contract, decisions 1–5, tests 1–7 + builder Questions 1–5).
- §5b checks: diff touches only NEW `doc/AnimOps.kt` + NEW `AnimOpsTest.kt` — inside the owner area; `DocModel`/`DocOps` untouched per "Do not". Suite evidence: `AnimOpsTest` 44/44, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file). All seven spec tests map, plus empty-board semantics, id-shape, and the 300-step randomised orphan invariant from the Questions.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): the three time functions skip the kind check every mutating op enforces
Proof:
1. `addFrame/deleteFrame/setHold/moveFrame/animateLayer` all route through `animationBoard()` (`AnimOps.kt:465-471`), which refuses non-ANIMATION boards.
2. `frameAt/totalDurationMs/frameStartsMs` (`:380, :402, :417`) take a bare `Board` and check only frames-emptiness and the 1..60 fps range (`playableFps`, `:449-458`) — never `kind`.
3. `DocOps.validate` rule 4 constrains ANIMATION boards only (`DocOps.kt:78`: `if (b.kind == BoardKind.ANIMATION)`), so a CANVAS board carrying a frames list validates clean — and then `frameStartsMs` returns it a schedule. A renderer/playhead trusting "has a schedule ⇒ playable" will play a board that cannot play.
Garbage-in, low blast radius (nothing writes such boards today), and the fps-range sharing is exemplary (same idiom as rule 4, with the Infinity-through-`<= 0` lesson written down at `:433-448`). Fix is one predicate (`require kind == ANIMATION`, mirroring `animationBoard`) or a kdoc line scoping the three functions to animation boards. Filed MINOR (spec is silent on board kind for the readers; validator silent too).

## Verified (proof)
- Decision 1 (valid-in ⇒ valid-out-or-throw): every op test asserts `DocOps.validate` clean; nothing mutates (new `JbDocument` throughout, so undo can hold the old one: `:81`).
- Decision 2 refusals with naming messages: unknown board/layer/frame, double-animate, last-frame delete, out-of-range `toIndex`, DUPLICATE/LINK with no source, id-generator collision against the flat used-set (`:120-152, :185-211, :230-238, :284-289, :351-356, :529-538`).
- Decision 3 shapes: new frame `holdFrames = 1`, new cels via `ids()`; source-frame rule (afterFrameId, else currently-first) and empty-board BLANK-works semantics per spec Q2/Q3 (`:199-211`).
- Decision 4 boundaries: exact start belongs to the starter, negative → first, past-end → last, NaN → first (documented at `:376-378`; NaN falls out of both comparisons into `chosen = 0` — traced, not just tested).
- Decision 5 layer isolation: non-animated layers carried by reference, untouched (`:222-226, :296-300`); LINK shares the cel with zero pixel work; DUPLICATE emits exactly one `CopyCel` per animated layer with a fresh cel; `deleteFrame` drops a cel (with `DropCel`) iff no remaining frame maps it (`:301-312`, `containsValue` on the post-removal map — the linked-pair-keeps-cel case falls out correctly); `frameCel` keys pruned with the frame (rule-7 junk never created).
- Holds: slider-semantics clamp 1..999 (`setHold`, `:329-335`, pinned at 100000 → 999); `durationMs` multiplies before dividing (`:431`, 3@12fps = 250.0 exactly); `frameStartsMs` last-start + last-length == total by shared expression/order (`:413-426`).
- Id discipline (spec Q4): frame-first-then-cels-in-layer-order shape, flat cross-namespace collision refusal (`usedIds`, `:515-526`), exactly one shape-pinning test with structure-only asserts elsewhere — the round-1 `[c-anim, gen1]`-vs-`gen2` lesson visibly applied.
- Orphan policy (spec Q5): never created (`deleteFrame` drops unreferenced), never repaired, never required by the validator — with the randomised invariant test as the backstop. `withFramesAndLayers` keeps frames+cels atomic (`:494-502`).
- INK layers flow through the same cel maths (empty cels valid per rule 8); `CopyCel`/`DropCel` name existing/vanished ids respectively per the sealed-class kdoc (`:15-30`).

## Explicitly not filed (builder's Questions, endorsed as asked)
- Q1 (`toIndex` = final index, `size` refused — JB-3.03 must clamp gap-drops), Q3 (start-insert sources the currently-first frame), Q4 (flat-strict ids), Q2/Q5 above: all implemented as documented and all correctly addressed to the Lead/future specs, not to code.

## Recommendation
No send-back. One MINOR (kind-check the three readers). No BLOCKER or MAJOR open.

## Addendum 2026-09-29 — MINOR fixed (`bbc34640`), verified
- Finding 1 fixed: the three time readers now go through `playableSchedule`, which refuses non-ANIMATION boards with a naming message (same rule the mutating ops enforce, now shared rather than duplicated). Empty-board semantics kept total (0 ms, not an error) as the spec requires.
- Fresh run in a clean HEAD worktree: `AnimOpsTest` 52/52 (44 old + 8 new), 0 failures. Finding closed; no open findings remain in this file. (The row's BLOCKERs fixed in the same commit came from the other reviewer's file, not this one.)
