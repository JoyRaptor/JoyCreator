# JB-3.03 — adversarial audit (muse, for bunny)

- Auditor: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta). Read-only: no gradle,
  no edits. Spec `tasks/joybrush/specs/JB-3.03_film_strip.md` read whole; ORCHESTRATOR_LOG
  JB-3.03 section read. Local HEAD `7bc60742`.
- Target: 🟧 Built (core) 2026-09-30 (sibling orchestrator's builder; 3 new files, 28 tests).
- Geometry re-derived CLEAN (widths 44/88/44/132, lefts 0/44/132/176, strip 308, density-3
  tick 132, 999×44 = 43956; edge zones disjoint; t7/t8/t10/t12/t13 fixes verified; Q1 24dp
  one-sided verified; no vacuous tests).

**AUDIT-VERDICT: FINDINGS-OPEN (0 BLOCKER, 3 MAJOR, 1 MINOR).**

## MAJOR

**F1 — `frameAt(-Inf)` returns 0; spec demands the last frame; zero test coverage.**
`FilmStrip.kt:116-125`: NaN-only early return; `-Inf` falls to the walk, all `cellLeft<=x`
false, `chosen` stays 0. Spec contract (`JB-3.03_film_strip.md:187-191`): "Before first → 0;
past last → last; a non-finite x → the last frame" — `-Inf` is non-finite, spec demands 3.
Code KDoc (`:110-114`) carves `-Inf` out as "before the strip → 0" — a gap-fill decision with
no test (`frameAt.*NEGATIVE_INFINITY` has no hits in `FilmStripTest.kt`; t6 pins -1, 1e9, NaN,
+Inf only). The log's "frameAt(-Inf) pinned" claim is false. Either the spec line or the code
is wrong; nothing guards it. For the Lead to rule.

**F2 — `previewHoldAt` Int overflow: enormous drag previews a negative hold.**
`FilmStrip.kt:488`: `taken.holdAtDown + dragStep(...)` is raw `Int+Int`. `dragStep`
saturates to `INT_MAX` (own comment at `:349`), so `1 + 2147483647` wraps to `-2147483648`.
Input: `down(44f)` then `previewHoldAt(1e11f)` (~2.27e9 ticks). Preview returns a negative
hold; cell draws negative width. `withHoldStep` (`:267-278`) does the same sum in `Long` +
`coerceIn` for exactly this reason; the preview path missed it. Contradicts KDoc `:472-476`
("a preview beyond the limit is a cell drawn a little too wide") and Decision 13's
visible-clamp intent. No test probes preview beyond ~150 px.

**F3 — `FilmStripNoSecondCopyTest.kt:307-333` (`routeTo`): success-path hang.**
`parent[from]=from` self-loop (`:323`, first pop with `owner==node==from`); unwind loop
`:326-331` (`at=parent[step]`, break only on null) never reaches null — `from→from→…`
forever, `chain` grows unbounded. Green path (no clock) never enters it, so the suite is green
today; the first red would hang CI rather than fail. The `expanded: HashSet<Type>` guard
(`:316,321`) is correct and threaded through both walks — but the parent-chain reintroduces a
hang on the only path that matters (J1's contract is "fail if clock referenced"; cf. Lesson 8).
Corroboration: the JB-3.08 cross-review (independent reviewer, same session) separately flagged
this helper's spec pointer as stale — two reviewers, same 30 lines, both directions (spec wrong
about the helper; helper hangs on its red path).

## MINOR

**m1 — `playheadAfterDelete` on an unknown id returns the unknown id.**
`FilmStrip.kt:180-189` vs KDoc `:176-178`: `strip` on `[f0..f3]`,
`playheadAfterDelete(boardAfter, "gx", "gx")` with `"gx"` in neither board returns `"gx"`
(`:186` "id stands"); KDoc promises "frame list's answer (new last frame, or null)". No test
pins `was<0` (t16/t17 use valid deletes). Unreachable via `deleteFrame` (throws first) — MINOR.
