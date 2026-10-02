# JB-8.01b builder ledger — muse (muse-spark)

Worktree `jb-muse-8.01b`, base `origin/joy-creator @ 0b7546b5`.
Command (from the worktree root, `JOYBRUSH_TESTDATA` pointing at the main checkout's
git-ignored `joybrush/testdata-local`): `./gradlew -p joybrush :core:jvmTest --no-daemon`.
Final run: BUILD SUCCESSFUL — 1450 tests, 0 failures, 0 errors, 0 skipped (98 XML files,
newest `TEST-...GuideSettingsTest.xml` 2026-10-01T23:43:27-04:00).
`AbrReaderTest` 27/27, `AbrImportTest` 29/29, `AbrRealFilesTest` 4/4 executed, 0 skipped.

## What was wrong (all confirmed byte-for-byte against the real files + ag-psd)

1. `VlLs` carried an invented `u32 byteLength` (both in `readBrushListHeader` and in
   `readValue`). Real lists are `count:u32` then `count` self-describing values — proven by
   `simple/src.abr` offset 0x44 (`VlLs`, count 1, immediately `Objc`). The first item's tag
   bytes (`Objc` = 1 311 849 827) were read as the length, so every real brush read as
   "truncated" or tripped the section cap.
2. `samp` entries missed the framing `u32 brushLength` (padded to 4), read `depth`/`compression`
   as `u32`/`u32` (real: `int16` + `u8`), assumed a 47/301-byte preamble from the entry start and
   an 8-byte trailer. Verified on `sample-and-pattern` (minor 2: Pascal id with NO padding,
   264 skip, bounds top/left/bottom/right `i32`, `0008`/`01`) and `special` (v10, same shape).
3. PackBits rows were read interleaved (a `u16` count before every row). Real files store one
   `u16` table for all rows first, then the rows back to back — proven by the smooth count
   ramp at `sample-and-pattern` 0x154+ and `special` 0x155+ (row 3 of an interleaved walk reads
   data bytes `3A 00` as count 14848). Same layout as Photoshop RLE image data.
4. Unicode strings (`TEXT`, descriptor names) count a trailing NUL in the length
   (`simple`: "Soft Round" stored as 11 units). The reader now strips trailing NULs, matching
   the `data.json` oracle; interior NULs are kept.
5. `patt` records were parsed as VMA lists (invented layout — tripped on `special`);
   `patt`/`phry` are now skipped by declared length. `AbrPattern`/`readPatterns`/`patternDetails`
   deleted; nothing read `AbrFile.patterns`.
6. Depths are 8/16 only now (ag-psd throws on anything else); 1-bit support dropped as
   un-oracled invention. 16-bit RLE is refused (ag-psd: "not implemented").
7. New: minor version must be 1 or 2 (ag-psd refuses the rest); the `Brsh` list keeps
   one-unreadable-brush semantics, and entries after a failed one say outright that they
   "cannot be found without reading it" (no length to resync by).

`AbrImport.kt` needed no change — the model (`AbrBrush`/`AbrTip`/budgets) survived; only the
byte layout was wrong. No new dependency (`AbrRealFilesTest` uses the already-present
kotlinx-serialization-json; the skip uses JUnit's `Assume`, already the runner — kotlin.test
on this toolchain has no `assumeTrue`).

## Per-file verdicts

BEFORE (RealFilesProbeTest, same corpus):
- `simple`: EXCEPTION — a list claims 1331849827 bytes (`Objc` read as length)
- `tilt`: EXCEPTION — same 1331849827
- `special`: EXCEPTION — a pattern record claims 1904713903 bytes
- `sample-and-pattern`: EXCEPTION — truncated at offset 1073781 (samp mis-framing)

AFTER (AbrRealFilesTest, all executed, none skipped):
- `simple: 1/1 accounted (1 imported, 0 refused)` — Soft Round, computed size=30.0
  hardness=0.0 spacing=0.25
- `tilt: 1/1 accounted (1 imported, 0 refused)` — test2, computed size=30.0 hardness=1.0
  spacing=0.25
- `special: 6/6 accounted (1 imported, 5 refused)` — Watercolor Wash [tips] converts (airbrush
  Shp==5 lossy path); 3 dBrush refused as bristle, 2 dTips refused as erodible, each with its
  sentence. Sample 23x20 present and clean.
- `sample-and-pattern: 1/1 accounted (1 imported, 0 refused)` — Bobbys Brush 743, sampled
  112x105 image=tip.packbits spacing=0.02, decoded to 112x105.
- No cap was tripped by any real file, so no bound was loosened. No brush still fails to
  import that should: every refusal is a MAPPED/LOSSY/REFUSED bucket working as specified.

## Mutation checks (each guard removed, its test(s) red, guard restored)

- List count cap (`readValue` `VlLs` `MAX_LIST_ITEMS` check) removed →
  `aListWithTooManyItemsIsRefusedAndNamed` FAILED, 26/27 passed. Restored.
- Node budget (`NodeBudget.spend`) neutered →
  `aDescriptorTreeOverTheNodeBudgetIsRefusedAndNamed` FAILED, 26/27 passed. Restored.
- Truncation (`AbrCursor.need`) neutered → `aBrushListCountThatDisagreesWithTheSectionIsRefused`
  and `brushesAfterAFailedEntrySayTheyCannotBeFound` FAILED with `IndexOutOfBoundsException`
  instead of a "truncated" refusal (25/27 passed). Restored.
- Nesting depth (`readDescriptor` `MAX_DESCRIPTOR_DEPTH` check) removed →
  `aDescriptorTooDeepIsRefusedAndNamed` FAILED, 26/27 passed. Restored.
- Final full run after all restores: green (above). `git grep MUTATION` clean.

## Synthetic-test rewrites (real layout, diff says why at each site)

- `listV`/`boolList` and the two hand-laid `Brsh` lists: byte length deleted.
- `TipEntry`/`sampSection`: `u32` length framing with 4-padding, unpadded Pascal id,
  10/264 skip by sub-version, `i16` depth + `u8` compression, no trailer; payload builders
  table-first.
- New tests: `VlLs` reads exactly its count; failed-entry follow-ons name the consequence;
  minor-version refusal; minor-2 tip; `patt`/`phry` skipped by length; 16-bit raw reads,
  16-bit RLE refused.

## Questions for the Lead

None — not blocked.
