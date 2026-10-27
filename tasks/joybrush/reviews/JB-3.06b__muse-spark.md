# Adversarial review — JB-3.06b Animation export (GIF + PNG sequence + sprite sheet; MP4/WebP out per R35)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `53908eba`.
- Spec reviewed: `tasks/joybrush/specs/JB-3.06b_anim_export.md` (contract, R35 scope, decisions, tests, Questions).
- §5b checks: diff touches only NEW `core/export/AnimExportPlan.kt` + test, NEW `androidkit/io/AnimExport.kt` + test — inside the owner area; no app file, no build file, no `document.json` field; MP4/WebP absent as ruled. Suites run by me in a clean HEAD worktree: `AnimExportPlanTest` 20/20 + `AnimExportTest` 15/15 (incl. `javax.imageio` oracle assertions), 0 failures.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): `pngSize` checks 7 of the 8 PNG signature bytes — off by one, AND the length guard sits below the indexing loop
Proof: `AnimExport.kt:383-387` — `for (i in 0 until 7)` compares `bytes[i]` to `PNG_SIGNATURE[i]`; the 8th byte (`0x0A`) is never compared. Narrow (a lying header is still caught by the imageio oracle in tests and by `assertEncodedSize` mismatch downstream), but the loop exists to refuse non-PNGs and it checks 7/8 of its own constant. `0 until 8`. **Second defect beside it (found by the spec writer, confirmed):** the short-file guard (`bytes.size < WIDTH_AT + 8`, `:388`) sits BELOW the loop that indexes `bytes`, so a 3-byte array throws `ArrayIndexOutOfBoundsException` instead of the sentence — and widening the loop to 8 without moving the guard first turns a 7-byte array from quietly-accepted into a crash. Guard must move above the loop in the same edit (spec JB-3.06c makes the order mandatory).

## Finding 2 (MINOR): unbounded frame retention — all cells in memory, twice on the GIF path
Proof: `encodeOne` renders EVERY frame into `cells` before encoding; the GIF path then hands them to `GifEncoder`, which retains every frame again for its two-pass palette (my JB-3.06a Finding 1). Per-frame size is budgeted (`checkRegionFits` vs `MAX_REGION_PX` in Long — good), but frame COUNT is not: a long board is N RGBA arrays × 2 with no refusal. Same footprint class as 3.06a's, one layer up. Spec-silent; either a count budget or a documented wiring-layer cap before the export button ships.

## Finding 3 (MINOR): the rounding rule is written twice — WITHDRAWN as filed, refiled correctly below
~~`roundHalfUpMs` defined but not called — two spellings of one rule.~~ **Wrong — I misread the call graph.** `roundHalfUpMs` IS live: `AnimExportPlan.kt:255`, reached through `ticksOf` → `cellIndices` → `sheetClip` → `AnimExport.kt:269`, and DELETING IT WOULD DELETE EVERY HOLD FROM EVERY EXPORTED SIDECAR (the spec writer proved this rather than working around it; my "call the function" advice would have been harmless but my premise was false — apologies to the builder). The real defect is the MIRROR image: the same expression is ALSO inlined at `:218` (`floor(end - starts[i] + 0.5).toInt().coerceAtLeast(1)`), so the drift is a rule written twice, which is the JB-1.08a lesson exactly. Deduplicate toward the named function.

## Finding 4 (MINOR): stale "Clip has no weights" comment — 4.03c landed after this row
Proof: `AnimExport.kt:266-268` — "`Clip` has no `weights` field today and this row must not start writing one". `Clip.weights` now exists (JB-4.03c, landed later). Repeats remain a valid choice (a repeat is a repeat) and the plan KDoc already says so prospectively — update this comment to the deliberate choice (repeats over weights) rather than the stale fact.

## Verified (proof — the seam design checked, not just the tests)
- **One plan, three encoders, honestly:** delays flow `AnimOps.frameStartsMs` → plan → all three consumers with no re-derivation anywhere (GIF hands ms to the encoder that owns cs; manifest writes the same ints; sheet folds them into repeats). The ulp rule (JB-3.05a's lesson) is observed in code, not just KDoc.
- **Range == clock by construction:** swap-then-clamp in the same order as `PlaybackClock`'s constructor, pinned against the clock's own answers; empty board delegates to the same refusal (never `0 until -1`).
- **Refusals before frame 1:** board-exists, plan-fits-board (names board AND frame — downstream would render a holey file silently), region budget in Long, paper-colour sentence — all before `onFrame` can fire (pinned: progress never precedes refusal).
- **All four plan refusals are `DocException`** (kind + fps via `AnimOps` unchanged — single "may this board play" answer; empty + dup-id own sentences); `AnimOps`-first ordering (kind-before-emptiness) preserved.
- **GIF/sheet/sequence mechanics:** `safeBaseName` restatement documented as restatement (refuse-vs-sanitise scope difference stated, not hidden); sheet columns via integer walk (no ulp-short grid); sheet PNG self-checked against its OWN IHDR (non-tautological) + imageio oracle; manifest last (incomplete sequence recognisable); sink failures in counted words (`Exported 19 of 40`); sequence names pad-without-truncate; `AnimFile` non-data-class (the `ByteArray.equals` trap, already documented twice elsewhere, avoided a third time); no `android.net.Uri`/`Context` in signatures (JVM-testable by construction); `onFrame` progress hooks.
- R35 scope respected: no MP4 entry, no WebP path, no scale factor, no range picker in the plan object.

## Recommendation
No send-back. Four MINORs (one off-by-one, one stale comment, one footprint note, one dedup). The plan/encoder seam is the durable achievement — three formats physically cannot disagree on timing.
