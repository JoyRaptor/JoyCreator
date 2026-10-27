# Adversarial review — JB-3.08a Three-finger swipe logic (R25 per-board override + pure badge)

- Reviewer: muse-spark (cross-reviewer; fixer was the Lead — different family by construction, R25).
- Task status: 🟧 Built. Commit reviewed: `2688b124` (on top of the WIP `f1809041` with 15/17 green).
- Spec reviewed: `tasks/joybrush/specs/JB-3.08a_three_finger_swipe.md` (contract, decisions 1–6 with R25 codified, tests 1–6 + Questions incl. the push-clock readings).
- §5b checks: diff touches only `tool/ThreeFingerSwipe.kt`, its test, and spec Decisions/Questions — inside the owner area; no Android, size/opacity maths called-not-copied per "Do not". Suite run by me in a clean HEAD worktree: `ThreeFingerSwipeTest` 23/23, 0 failures (both pre-fix reds closed — see below).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead (including both pre-fix reds, closed with proof)

1. **The spec contradiction is resolved, not papered over.** The old test asserted forgotten-on-switch AND per-board memory at once (unsatisfiable — no implementation could pass). R25 rules remembered-per-board; the old line-129 assertion is GONE (replaced, with a comment saying so), `badge()` is a pure read (zero assignments — verified by reading, plus a repeat-alternating-boards purity test), toggle-back-to-auto and <2-frames refusal and drop-deleted-boards all pinned. The Lead's note is also honest about its own misdiagnosis (line 138, not 128–130; a per-board map alone would not have passed the old test).
2. **The second red was a genuine code bug, not the test's fault alone:** `badge()` called `forgetOtherBoard()` — a GETTER mutating state. Now pure. Both reds' root causes are named in code comments, not just commit messages.
3. **The first red was the test's fault, correctly diagnosed:** a horizontal move is a SIZE-axis move that changes nothing (opacity path never fires), so it returns `Nothing` — the test has been rewritten to assert the lock (`Nothing` on the locking move) then the doubling across two moves, documenting the axis semantics.
4. **All six decisions verified by reading + tests:** auto FRAMES iff ANIMATION ≥2 (null/unknown/non-ANIMATION/1-frame → BRUSH); per-board map with toggle/refusal/drop; mode snapshotted at `begin` (both directions pinned); FRAMES 36 px flip, left-is-next, clamp, Ended-exactly-once, 3-step push-wrap measured from whole steps (one-sample ≡ many-sample — the `move`-takes-TOTAL contract honored), restart-after-wrap, vertical ignored, ShowFrame-on-change; BRUSH delegating total offsets to `SizeOpacityDrag` (lock-from-own-point interplay derived, not assumed); guards (no-begin/ended → Nothing, non-finite → Nothing-but-gesture-continues).
5. **Builder Question readings confirmed as implemented:** push clock from whole steps (Ended at 126, Wrapped at 252 — pure function of dx); begin-on-end wraps after 3 steps with no Ended tick; wrapped-onto end gets no Ended (suppressed until the index leaves — one arrival, one tick).
6. Hunted and cleared: override leak across deleted ids (dropped at next tap, re-added id returns automatic — pinned), wrap retrigger (one `Wrapped` per move, restart forces 3 more steps, fling equivalence pinned), Ended duplication (re-tick only after leave), density guard (non-finite/≤0 → 1), begin-frameIndex clamp (both directions).

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. The R25 ruling + pure-`badge()` combination is the durable part — JB-3.08 (UI) inherits logic that survived its own contradiction.
