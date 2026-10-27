# JB-3.06c — adversarial audit (muse, for bunny)

- Auditor: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta). Read-only: no gradle,
  no edits (only `git log/show/status`, read-only). Spec
  `tasks/joybrush/specs/JB-3.06c_audit_fixes.md` read whole (782 lines). Local HEAD `7bc60742`.
- Target: 🟧 Built 2026-09-30 (sibling orchestrator's builder). Landed commit verified:
  `04855a6e` (4 files; 9 `@Test` added — spec header "four new tests" is stale, code impact zero).

**AUDIT-VERDICT: CLEAN. No findings filed.**

## Verified claim by claim (code wins)

- **V1 Finding 1 (7-of-8 + guard below loop): EXACTLY RIGHT, fixed.** Landed
  `androidkit/.../io/AnimExport.kt:509-517`: `if (bytes.size < WIDTH_AT+8)` FIRST
  (`WIDTH_AT=16` at :539 → 24), then `for (i in PNG_SIGNATURE.indices)` (8 bytes at :536).
  Mental execution: 0-byte → sentence, no throw (old: `bytes[0]` OOB); 3-byte → sentence
  (old: OOB at i=3 — confirms the log's crash); 7-byte → sentence (old: false path); widening
  to 8 without moving the guard → `bytes[7]` OOB (confirms spec p.53). 8-byte `…0x41`
  (`AnimExportTest.kt:522-536`) → `“…Board.png…signature”` sentence (old: false-accept).
  Pinned by three tests (:522/:539/:554). Matches spec Site 1 After block verbatim.
- **V2 Finding 2 (stale Clip-weights comment): RIGHT, fixed in owner area.** `Clip.weights`
  exists (`SpritePacker.kt:35-41`), validated (:220-232), written (:285-289). All three owner
  sites fixed (`AnimExportPlan.kt:151-158`, `AnimExport.kt:290-293`,
  `AnimExportPlanTest.kt:455-457`); `has no.*weights` grep in `commonMain`+`androidkit/main`
  returns zero. Behavioural pin: `sheetClip` still folds repeats (`:160-166`); new test
  `:480-512` asserts `"weights" !in clip` + positive control + length-mismatch refusal.
  Outside-owner three untouched per spec Q4.
- **V3 Finding 3 (unbounded retention → ceiling): premise RIGHT, R44 remedy unreachable,
  derived ceiling landed and binds.** Retention premise holds (`AnimExport.kt:142-146`,
  `GifEncoder.kt:413-415,443,456`, `SpritePacker.pack` + `:248`); `SpritePacker`/`GifEncoder`
  untouched per Do-not; `writeSequence` (:201-224) streams. Ceiling
  `MAX_EXPORT_PX = MAX_REGION_PX*2L` (`AnimExport.kt:395`; `RegionRenderer.kt:89`
  `MAX_REGION_PX=8_388_608L` → cap `16_777_216L` = 64 MiB RGBA/4), test pins both expressions
  (:576-584). Re-derived: GIF 256³ accepts / 257³ refuses (:587-627); 7-frame 1024² sheet
  accepts exactly at cap, 8-frame refuses (:629-673, `sheetCols(7)=3`, rows 3); 40-frame
  sequences refuse with `onFrame==0` (:751-794). Peak held RGBA ≤ 64 MiB. Decision 6
  PROVISIONAL multiple stays Lead's, as marked.
- **V4 xr defect A (sequence cost `n*cell`): FIXED.** Landed `:423` returns `cell` for
  PNG_SEQUENCE before `:424-426`; 400-frame 1024² sequence accepted with 401 files
  (:803-814). Ordering + cols-invariance pinned (:675-716).
- **V5 xr defect B (rows ÷ pixel total): FIXED.** Landed `:435`
  `rows=(count+cols-1)/cols` with `count=plan.frameCount` (:424), matching packer
  (`SpritePacker.kt:235`). Buggy form gave 2 446 678 rows → 7.7 TB need → every sheet refused;
  exact-cap test only lands with rows=3.
- **V6 Finding 4 (roundHalfUpMs unused): log's WRONG verdict CONFIRMED; untouched, holds live.**
  Body `:254` unchanged; callers `:222` (`build`) and `:269` (`ticksOf`→`cellIndices`→`sheetClip`);
  `floor` import still used; inlined copy gone. Witness tests `:88,:108` spell `floor(…`
  independently. Deleting it would delete holds.
- **V7 deleted vacuous assertion: CONFIRMED vacuous, nothing lost.**
  `assertEquals("Clip", Clip::class.simpleName, …)` always true; replaced by the real
  `:481` test. Grep for it now zero.
