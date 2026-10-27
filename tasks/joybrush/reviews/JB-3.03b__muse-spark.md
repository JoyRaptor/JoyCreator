# JB-3.03b — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from spec writer/reviser `openrouter/stealth/space-bunny-alpha`. Independent read.
- Spec reviewed: `tasks/joybrush/specs/JB-3.03b_strip_thumbnails.md` (local HEAD `7bc60742`).
- Method: read-only. No gradle, no edits. Round-1 BLOCKER fixes re-derived from scratch,
  not trusted from the log. 59 `File.kt:line` occurrences checked: ~24 unique locations
  exact, 13 stale (all in the M1 cluster below), 1 off-by-one.
- Board action (bunny's): stays 📝 Draft spec until M1–M3 are fixed.

**Verdict: SEND-BACK — 3 MAJORs, 7 MINORs. No BLOCKERs.**

## Round-1 BLOCKER closure (independently re-derived — all three land)

1. **Budget predicate written down; tests 6/7/8 mutually satisfiable: FIXED.**
   Predicate exists in prose, pseudo-code, and public `admitted()` (spec:219-226,:568).
   Truth table rebuilt from the spec's own numbers (`MAX_STRIP_THUMB_PX = 4 194 304`,
   one-tick cell at density 3 = 17 424 px): 240 admitted (4 181 760, 12 544 under); 241st
   refused (4 199 184, 4 880 over); 6b (1 024 × 4 096, total exactly 4 194 304) kills the
   `<=`→`<` impostor; test 7 kills per-cell; 8b kills order-insensitive. Each wrong reading
   dies on ≥1 test.
2. **Un-premultiply divides by alpha: FIXED.** Pseudo-code (spec:404-411) matches landed
   `RegionRenderer.kt:197-205` (`val a = p[i+3]` at :198, `k = 1/a` at :201). Three tests
   carry non-uniform alpha (11, 12b, 14) with wrong-divisor numbers worked out
   (128 vs 255; 64 vs 255; 32 vs 255) — all re-derived, agree.
3. **Scale table: FIXED.** `4 194 304 / 17 424 = 240.7199` ✓ (old 240.66 withdrawn);
   `17 424 = 2³×3²×11²`, `4 194 304 = 2²²` so exact-cap impossible at density 3 (why test 6b
   exists). All other figures recomputed and agree (44²/88²/110²/132² cells; 30/60/240/241
   totals; 210/211-tick; 999-tick 17 406 576 = 4.15× budget; density-1/2 variants; fixture A;
   filter tests 12/12b/13/14/15 byte arithmetic).

## MAJORs

**M1 — ~15 cited line numbers stale (JB-2.23 drift), sitting under the spec's own Stop rule 1.**
Verbatim quotes correct, `:line` pins moved — a builder obeying Stop rule 1 can false-stop.
Corrected readings: `RegionRenderer.kt` requireSize `:340`→**357**, toByte255 `:365`→**386**,
throw list `:341`→**358**, `:278-280`→**287-289**, `:414`→**433-436**, skip-tiles `:277`→**286**,
refusal sentence `:346-350`→**363-366**, `opacityOf` `:372-379`→**393-399**, thumbnail caller
`:100`→**99**; `DocOps.kt` celFor `:178-182`→**218-222**, rule 8 `:141-146`→**152-157**;
`DocModel.kt` RectPx `:36`→**40**, Frame `:62-65`→**90-93**, Board `:69-78`→**97-106**,
Cel `:133-137`→**161-165**, Layer `:139-149`→**167-186**, Paper `:38-44`→**61-71**.
(`FilmStrip.kt`, `AnimExport`, `AnimOps`, `BenchCases`, `InkRaster`, `FilmStripTest`,
`RegionRendererTest`, `FilmStripNoSecondCopyTest`, `DefaultPresetsTest.joybrushRoot`
verified exact.)

**M2 — `PictureKey`/`keyOf` omit fields the renderer composites; Decision 10's "the key is
everything the document CAN say" is false.** `RegionRenderer.kt:264-280` now composites
`layer.mask`, clip-base visibility/cels (`:273-280`, JB-2.23), per-layer
opacity/blend/visible — none in `PictureKey` (spec:531) or `ThumbKey` (spec:534-540); the
spec's `Layer` quote (spec:146-150) omits landed `mask`/`clip` (`DocModel.kt:183-185`).
Opacity 1→0.5, NORMAL→MULTIPLY, or a newly added mask change the picture with an unchanged
key. Steps §3 (spec:464-468) claims the inclusion rule "mirrors `RegionRenderer`'s three
`continue`s at `:265`, `:268`, `:270` exactly" — there are now five-plus. Fix: extend the key
or narrow the Decision-10 claim with the omitted fields named.

**M3 — Budget-vs-ink check order claimed pinned by 18b, but no test pins that pair.**
18b first half (density 0 + ink) cannot exercise budget-vs-ink (`0 ≤ cap` admits — the spec's
own parenthetical concedes this, making its "AND over budget" wording false). A 999-tick cell
on an ink layer (budget-refused AND ink-refused) would pin it; nothing does. A builder
transposing budget/ink passes the suite while violating the contract.

## MINORs

- m1 Spec:775: dangling fragment from the test-8 rewrite (belongs to test 8, sits inside 8b).
- m2 18b header "degenerate AND … AND over budget" wrong per its own parenthetical.
- m3 Spec:362 "2 896 × 2 896 (at `MAX_REGION_PX`)": 2896² = 8 386 816, 1 792 under the cap —
  "at" is approximate; say so.
- m4 Spec:734-740 `16f/11f` product printed `64.000002`; exact float product is
  implementation-rounded — harmless under the 0.5 margin, but the literal is asserted, not derived.
- m5 Spec:158-170 self-contradiction ("`DocOps.kt:72` only refuses `w <= 0 || h <= 0`, not a
  negative side" — `<= 0` includes negatives); reword, point survives.
- m6 Spec:520-521 precedent "`OraExport.Thumb`": actual top-level `private class Thumb`
  (`OraExport.kt:61`), object at :151.
- m7 Steps §8 / Definition of done still order the builder to run `./gradlew`; under R43 +
  the 2026-09-30 ops rule the orchestrator owns the only Gradle JVM — test-running lines should
  say "report; orchestrator runs".

## No contradiction found

Contract implementable; all 13 Decisions + seam made (Decision→Test rows, spec:613-628); 26
`commonTest` census correct; all 12 non-vacuity mutations redden-able as claimed. R32/R33/R30,
iOS door, owner area (exactly 3 new files), stop rule (6 items), Q1(b)/Q5 corrections all hold.

**Bottom line:** predicate, filter, numbers, mutations, scope survived independent
re-derivation. Fix citation freshness (M1), key completeness (M2), one unpinned order pair
(M3) → READY.
