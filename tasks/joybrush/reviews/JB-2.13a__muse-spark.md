# Adversarial review — JB-2.13a RegionRenderer (flatten any rectangle to pixels, all blend modes)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `7da867a5`.
- Spec reviewed: `tasks/joybrush/specs/JB-2.13a_region_renderer.md` (contract, `Blend.kt` formulas, decisions 1–5, tests 1–6).
- §5b checks: diff touches only NEW `render/RegionRenderer.kt`, NEW `render/Blend.kt`, NEW `RegionRendererTest.kt` — inside the owner area. Suite evidence: `RegionRendererTest` 34/34, 0 failures (verification run 2026-09-28 19:11; fresh re-run blocked — see top note in JB-0.02b file). All six spec tests map (single-red-tile, 4-tile straddle incl. negatives, paper/no-paper, per-mode W3C pairs, opacity, invisible/animated).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (BLOCKER): no size/overflow guard — a validator-legal rect crashes or OOMs the single export chokepoint
Proof (`RegionRenderer.kt`):
1. `render` (`:76`) allocates `ByteArray(rect.w * rect.h * 4)` and `renderPremultiplied` (`:106`) `FloatArray(rect.w * rect.h * 4)` — Int arithmetic, unchecked. `requireSize` (`:192-194`) rejects only negatives.
2. `DocOps.validate` permits any `w > 0` board rect, so `render(doc, tiles, board.rect, …)` is callable with hostile-but-valid dimensions — and every export (PNG, OpenRaster, GIF, video, sheet, thumbnail) funnels through here by design.
3. Crash: 30000×30000 → 900,000,000 × 4 = 3,600,000,000 → wraps to −694,967,296 → `NegativeArraySizeException` (unchecked, out of the documented error contract — there is none).
4. OOM without overflow: 20000×20000 = 400M px → 1.6 GB byte array + 6.4 GB float array → `OutOfMemoryError` on any phone; even 11000² (≈121M px) needs ~0.5 GB + ~1.9 GB.
5. Secondary silent-wrong: `rect.x + rect.w − 1` (`:134,137`) overflows near `Int.MAX` → inverted tile range → empty loops → transparent output, no error.
No spec test names huge rects (confirmed: `RegionRendererTest` has no large/overflow case), and the kdoc's "must fit inside Int coordinate range" (`:46-47`) assumes what nothing enforces. Fix direction (needs Lead ruling, export-wide): refuse rects whose `w*h` (in Long) exceeds a pixel budget with a readable error — the same "declared size is a wish" posture JB-0.08a takes. Until then every exporter inherits a crash on hostile files.

## Finding 2 (MAJOR): the kdoc's GPU-parity claim is false for 7 of 8 modes
Proof:
1. `RegionRenderer.kt:33-37` states the GPU "composites with the same rules … blended over (`jb_tile.frag`)".
2. `joybrush/shaders/jb_tile.frag:12-14` does `texture(u_layer) * u_layerOpacity` into `(ONE, ONE_MINUS_SRC_ALPHA)` — pure source-over. There is no per-layer mode uniform, no mode branch, no second program.
3. So any non-NORMAL layer renders source-over on the phone but W3C-correct in every export: the user sees one picture and saves another. Grep confirms latency, not absence: no non-NORMAL `BlendMode` producer exists in `commonMain` yet (only `Blend.kt` itself), so nothing diverges *today* — but the kdoc will mislead JB-2.04 (layers panel, which owns GPU blend support) into assuming parity is done.
Either the kdoc gains "NORMAL only — modes need JB-2.04 GPU work" or the parity sentence goes. Filed MAJOR (a code contract asserting a falsehood about wrong results) with latency noted; orchestrator may keep it open against JB-2.04 instead.

## Finding 3 (MINOR): invalid paper crashes with `IllegalArgumentException`; render never validates (undocumented precondition)
Proof: `parsePaper` (`RegionRenderer.kt:227-234`) `require`s `#RRGGBB` — an invalid `doc.paper.color` (which `render` accepts without calling `DocOps.validate`) throws an unchecked exception mid-export, while the sibling edge (NaN/out-of-range layer opacity) is carefully neutralised (`opacityOf`, `:214-220`, with the `coerceIn`-passes-NaN rationale written down). Callers must validate first — true of every exporter via JB-0.08a's read path — but the precondition is nowhere stated. One kdoc sentence ("callers pass a valid document; invalid paper throws") or a catch-and-wrap; do not silently default the paper (the kdoc correctly explains why guessing exports a black background).

## Verified (proof — the arithmetic was hand-checked, not just test-counted)
- W3C source-over shared by all seven separable modes (`Blend.compositeChannel`, `Blend.kt:80-81`): NORMAL reduces to `s + (1−sa)d ✓`; MULTIPLY/SCREEN/OVERLAY(backdrop-tested ✓, tie at 0.5 exact both branches)/ADD (Joy-Brush-clamped-`B`, keeping `co ≤ a` per the kdoc proof sketch)/DARKEN/LIGHTEN (`</>` ties return the shared value) all match the spec's named formulas; `term`'s exhaustive `when` makes a ninth mode a compile error (`:91-110`), ERASE_BELOW listed-unreachable rather than defaulted.
- ERASE_BELOW is destination-out on all four channels incl. alpha (`:53-60`), matching `jb_commit.frag` (`dst * (1−a)` on the vec4) and `RefCanvas.endStroke` — the grey-smear failure mode named in the kdoc is structurally impossible here.
- Opacity scales the whole premultiplied pixel incl. alpha (`RegionRenderer.kt:171-174`, with the MULTIPLY-by-white counterexample written down); zero-alpha guards at both ends (`Blend.kt:66-71`, `RegionRenderer.kt:82-84`); ADD's clamp placement keeps un-premultiply bounded (`toByte255`, `:207`); hostile-archive `color > alpha` bytes clamp to white rather than wrap.
- Structural: cel addressed (layer, cel, tile) with the cross-layer cel-id collision documented (`TileSource` kdoc `:11-26`); `celFor` owns frame mapping; locked renders; invisible skipped; missing tile transparent; static-on-animation-frame shows (held background); wrong-size tile `require`s with the numbers in the message (`:154-156`); empty rect returns empty, negative throws (`:46-50, :192-194`).
- Ink layers render empty (no tiles ever supplied): correct until JB-5.01 owns ink rasterisation — known phase gap, not filed.

## Recommendation
Finding 1 must be ruled before any exporter ships (it is the export path crashing on valid input). Finding 2 belongs open until JB-2.04 or until the kdoc is corrected. Finding 3 is one sentence. The CPU flattening itself (the spec's contract) is correct and thoroughly pinned (34/34).

## Addendum 2026-09-29 — all three findings fixed (`bbc34640`), verified
- Finding 1 (BLOCKER) fixed as prescribed: `MAX_REGION_PX` pixel budget (Long arithmetic) with a `RegionException` refusal — the readable error exporters catch — on BOTH doors (`render` and the premultiplied door; the fix notes a guard on one door alone could be walked around via the other). The Int-truncation arithmetic the builder got wrong in a comment was corrected rather than the code.
- Finding 2 (MAJOR) fixed deeper than asked: the parity claim now names exactly which modes each side has, and `theParityClaimNamesExactlyTheModesEachSideHas` checks the two halves partition the enum BY NAME — a 28th mode turns the suite red instead of quietly making the paragraph a lie. (The fix also notes the engine's layer record has NO blend field at all — parity is 27-on-CPU vs 1-on-GPU, worse than my review knew.)
- Finding 3 (MINOR) fixed as prescribed: both doors check paper; "the document itself is not validated" is now the stated caller contract (a per-call whole-document validation would tax every export for a check belonging to the door before it).
- Fresh run in a clean HEAD worktree: `RegionRendererTest` 47/47 (34 old + 13 new, incl. non-vacuity pins), 0 failures. All three findings closed.
