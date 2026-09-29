# Adversarial review — JB-5.02 Stroke picking in dense line work

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `b74aaf0e`.
- Spec reviewed: `tasks/joybrush/specs/JB-5.02_stroke_picking.md` (contract, decisions 1–4, tests 1–6 + builder Questions 1–6).
- §5b checks: diff touches only NEW `vector/StrokePicker.kt` + NEW `StrokePickerTest.kt` + the spec's Questions appendix — inside the owner area. Suite evidence: `StrokePickerTest` 23/23, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file). All six spec tests map (upper-line pick, between-tap recency, 3-tap cycle wrap, 500 ms reset, far-tap null, zoom-8 slop shrink).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead — including independent confirmation of the builder's own hard questions

1. **Decisions 1–2 (candidate = score ≤ slop; rank by `d − halfWidth`)** implemented literally (`StrokePicker.kt:107-113, 143-169`): per-segment clamped projection with half-width interpolated at the nearest parameter (not the vertex), single-point lines as dots, stable score sort. The zoom effect in the tests falls out of `slop = 12/zoom` exactly as specified.
2. **Q6 (fat line beats thin at an exact crossing) confirmed real, correctly attributed, not re-filed.** Traced: both centrelines through the tap ⇒ `d = 0` both ⇒ scores `−halfWidth` (−2 vs −0.2); gap 1.8 doc px exceeds the 1-screen-px tie window at zoom 1 ⇒ wider line wins regardless of draw order. This is Decision 2's literal consequence, and the builder already filed it as Q6 with the exact fix shape ("`d == 0` on several lines is always a tie") and pinning tests (`aCrossingIsRankedByHowFarInsideTheInkTheTapLands`). Owner decision needed; nothing in code contradicts the spec — duplicating it here would only fork triage.
3. **Q2 (non-transitive ties) confirmed as implemented:** best-anchored clusters (`:118-130`), no cluster spans more than the tie window, recency inside. A plain sort would leave pile-ups to the implementation; this doesn't. Anchor choice stands as the builder's documented call.
4. **Q1/Q3/Q4 (slop decides count-not-winner; recompute-per-tap cycling with the three same-line-twice cases; stateful `pick` with no read-only peek)** all confirmed present in code + kdoc (`:92-98, :40-50, :52`) and correctly addressed to future specs, not to this code.
5. **Q5 (backwards clock / non-positive-NaN zoom) confirmed guarded and tested:** `dt.isNaN() || dt < 0 || dt >= 400` restarts (`:177`); `zoom` falls back to 1.0 (`:78`); zero zoom therefore cannot divide the slop to infinity.
6. **Hostile-input trace (no crash, no pick):** NaN tap coords ⇒ all segment distances NaN ⇒ `d < bestD` never fires (`:163`) ⇒ score `MAX_VALUE − halfWidth` ⇒ excluded by `score <= slop` (NaN halfWidth ⇒ NaN score ⇒ excluded too); ±Inf coords ⇒ Inf scores ⇒ excluded; empty lines ⇒ null + cycle reset (`:80-83`); negative halfWidth merely inflates the score (conservative, unpickable-ish, never corrupt). No `require` needed anywhere on this path.
7. **Boundary exactness:** `dt >= 400` ⇒ fresh (spec: "< 400 ms"); `dist² <= reach²` ⇒ cycle at exactly 6 px (spec: "within 6"); tie `score − best <= tie` ⇒ exact ties cluster even at huge zoom (deterministic, documented).
8. Duplicate ids behave as documented (first-in-ranking wins; cycle may repeat the id — the caller cannot distinguish them anyway: `:48-50, :191-196`).

## Recommendation
No send-back. Clean: no BLOCKER, MAJOR, or MINOR open. The only open item is the builder's Q6, which is an owner ruling on Decision 2's literal (and correct) consequence — confirm or overrule there.
