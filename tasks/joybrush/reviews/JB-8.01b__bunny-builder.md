# JB-8.01b — builder's report (opencode, `space-bunny-alpha`)

**Row:** make `.abr` read a real `.abr`. **Branch:** `bunny/JB-8.01b`. **Worktree:** `%TEMP%\jb-8.01b`.
**Gradle was NOT run** — this machine has ~2.5 GB of free RAM and the orchestrator owns the single Gradle
JVM (R43/R44). **The run is owed.** Nothing below is a claim that a test passed; every "imported" is a
prediction derived from a byte-level analysis of the four real files, cross-checked against ag-psd's own
`data.json`, and labelled as such.

---

## 1. What was wrong, and what the truth turned out to be

The lead's diagnosis (`VlLs` has no byte length) is right, and it is **not the only wrong thing**. I ported
ag-psd's `readAbr` into a throwaway Python reader under `%TEMP%\abr-analysis\`, ran it against all four
real files, and checked every number it produced against `data.json`. Five things were wrong, and **all
five make a real file fail**:

| # | What this build read | What a real file has | Evidence |
|---|---|---|---|
| 1 | `VlLs` = type, `count:u32`, **`byteLength:u32`**, items | type, `count:u32`, items — each self-describing | `ag-psd-descriptor.ts:488-499`; in all four real files the `u32` after the count is `Objc` (`0x4f626a63` ≈ 1.3 GB), so the reader hit its own caps or said "truncated" |
| 2 | `samp` bounds found at a hard-coded offset from the entry's first byte (47 / 301) | `u32 entryLength` (padded to 4) → the entry ends there; then Pascal id; then 10 (sub 1) or 264 (sub 2) bytes of preamble | `ag-psd-abr.ts:536-582`; `sample-and-pattern` entryLength 7808 and `special` 720 land exactly on the end of their pixels |
| 3 | `depth` `u32`, `compression` `u32` (8 bytes) | `depth` **`i16`**, `compression` **`u8`** (3 bytes) | `ag-psd-abr.ts:554-555`; reading 8 where the file has 3 puts the pixel start 5 bytes past the truth |
| 4 | PackBits = each row's `u16` count immediately in front of that row | `height` `u16` counts **first**, then that many bytes per row | `ag-psd-abr.ts:562` (`readDataRLE`); proved by decoding: with the table, all 105 rows of `sample-and-pattern` decode to exactly 112 bytes and all 20 rows of `special` to exactly 23, consuming each row's declared bytes exactly. With the interleaved form, row 0 of `sample-and-pattern` declares 8 bytes and decodes to 4 pixels instead of 112 |
| 5 | Unicode strings kept as stored | NUL-terminated, and **the terminator is counted** | every `Nm  ` in all four files is one char longer than the name (`"Soft Round\0"`), the descriptor's own name is a single `\0`, and ag-psd's `data.json` says `"Soft Round"` |

Two more corrections that no real file exercises but that were lies in the same code:

- `GlbO` was read as a length-prefixed blob; ag-psd reads it as a **descriptor** (`descriptor.ts:485-487`).
  Now read as a descriptor.
- `type` / `GlbC` were read as a bare class id; ag-psd reads a **class structure** (Unicode name, then
  class id, `descriptor.ts:530-532`). Now read as that.
- `ObAr` was read as a length-prefixed blob, which is not what it is (`descriptor.ts:541-562`). Now a
  **refusal with a sentence** — strictly less information, never a wrong number, and never a cursor
  desynchronised by an unknown amount. See Q3.
- A `samp` **1-bit** tip was "supported": `bytesPerSample(1) = 1`, but a 1-bit row is `ceil(width / 8)`
  bytes, so it read an image eight times too wide that decoded cleanly. ag-psd refuses it. Now refused by
  name, and the KDoc that claimed 1-bit support is gone.
- A 16-bit tip claimed in the KDoc to "contribute its high byte" contributed **both** bytes. Now it does
  contribute the high byte, which is ag-psd's rule (`alpha[i] = readUint16() >> 8`), so a mean over a
  16-bit tip means the same thing as over an 8-bit one.
- `patt` was parsed against a **guessed record layout**. On the two real files that carry a `patt` payload
  it read a record length out of the pattern's own pixels and refused the whole `.abr`
  (`sample-and-pattern`: the single record's declared length is 1 065 937, and 3 bytes are left over;
  `special`: 16 714, then the next "length" reads as 197 240 with 196 990 left). The spec for this row says
  to skip `patt` by its declared length; patterns are JB-1.05d's business. `readPatterns`, `AbrPattern` and
  `AbrFile.patterns` are **deleted** — nothing outside `AbrReader.kt` ever read them (grepped), and the
  field was dead weight built on a guess. See Q1.

---

## 2. Per-file verdicts — **DERIVED FROM A BYTE-LEVEL ANALYSIS, NOT FROM A RUN**

Everything below comes from my Python port of ag-psd's `readAbr` run over the real bytes, with every
descriptor key, every `samp` entry and every row decoded, and every number checked against that file's
`data.json`. **`AbrRealFilesTest` has not been executed.** These are predictions.

### `simple/src.abr` (662 B, version 6 sub 2, sections `samp` 0 / `patt` 0 / `desc` 622)

| brush | tip class | outcome |
|---|---|---|
| `Soft Round` | `computedBrush`, `Dmtr` 30, `Hrdn` 0, `Rndn` 100, `Spcn` 25 | **imported** — size 30, hardness 0, spacing 0.25 |

Oracle agrees: `size 30, hardness 0, spacing 0.25, roundness 1`. Section `desc` consumed to the byte
(40 → 662 = its declared 622) with no padding guess. **1 converted, 0 refused.**

### `tilt/src.abr` (1162 B, version 6 sub 2, `samp` 0 / `patt` 0 / `desc` 1122)

| brush | tip class | outcome |
|---|---|---|
| `test2` | `computedBrush`, `Dmtr` 30, `Hrdn` 100, `Spcn` 25; `szVr` `bVTy` 3 (pen tilt) | **imported** — size 30, hardness 1, spacing 0.25, one tilt input curve at `(0,0)`→`(1,1)` |

Oracle agrees on every one of those. `tiltScale` is 200 % → the importer clamps to 1 with both numbers in a
warning; ag-psd reports `2`, and `TipSpec` has nowhere to put 2. **1 converted, 0 refused.**

### `sample-and-pattern/src.abr` (1 076 827 B, version 6 sub 2, `samp` 7812 / `patt` 1 065 944 / `desc` 2976 / `phry` 43)

| brush | tip class | outcome |
|---|---|---|
| `Bobbys Brush 743` | `sampledBrush`, `Dmtr` 112, `Spcn` 2, `sampledData` `a8f34db0-4814-634e-807c-c61aecde18fd` | **imported** — size 112 (the bitmap's own width, also 112), spacing 0.02, tip carries `abr.tipImage` |

The one real `samp` entry: id 36 chars, sub-2 preamble 264, bounds `top 1, left 2, bottom 106, right 114`
→ 112 × 105 (oracle: `x 2, y 1, w 112, h 105`), `depth` 8, `compression` 1, entryLength 7808 with the last
9 bytes zero padding behind the pixels. The row-count table is `0008 000f 0016 001b 001d 001f 0021 0027 …`;
table + rows = 2 100 + 7 269 = 7 479 bytes, and every row decodes to exactly 112 pixels with no byte left
over — which is the proof of both the layout and the PackBits walk. The oracle carries **no** alpha array,
so the alpha sum cannot be compared (the test prints that). **1 converted, 0 refused.**

### `special/src.abr` (226 125 B, version **10** sub 2, `samp` 724 / `patt` 213 708 / `desc` 11 653)

| # | brush | tip class | outcome | why |
|---|---|---|---|---|
| 1 | `Round Point Stiff` | `dBrush` | **refused** | bristle tip — a strand simulation with no Joy Brush equivalent |
| 2 | `Flat Blunt Short Stiff` | `dBrush` | **refused** | bristle |
| 3 | `Square Charcoal` | `dTips`, `Shp ` 3, has `dtipsErodibleTipHeightMap` | **refused** | erodible tip |
| 4 | `Watercolor Wash` | `dTips`, `Shp ` 5, no height map | **imported** | airbrush → soft round dab; size 71, hardness 1, spacing 0.01 |
| 5 | `Watercolor Brush on Dry` | `dBrush` | **refused** | bristle |
| 6 | `Pencil` | `dTips`, `Shp ` 0, has `dtipsErodibleTipHeightMap` | **refused** | erodible |

**1 converted, 5 refused = 6 = `data.json`'s `brushes.length`.** Oracle agrees on every size and spacing
(25, 36, 25, 71, 36, 9 px; 0.02, 0.02, 0.02, 0.01, 0.06, 0.08). The file's one `samp` entry (23 × 20,
depth 8, PackBits, entryLength 720, table 40 + rows 350 = 390 with 10 bytes of zero padding behind it) is
referenced only by the *dual-brush* tips of brushes 4 and 5, both of which are refused, so it is decoded
and checked but not imported.

### Any brush that still does not import

Five, all in `special`, all refused **in words** and all for a stated reason (bristle ×3, erodible ×2).
None of them is a failure of this row: ag-psd reads them too, and the refusal is the project's documented
MAPPED/LOSSY/REFUSED rule, not a parse failure.

---

## 3. Guard tests: what goes red if the guard is removed

I could not run these, so each line names **the assertion in the named test** that fails. Each guard is
counted, and the arithmetic behind the count is written into the test.

| Guard | Test that holds it | If the guard is removed, this fails |
|---|---|---|
| `MAX_LIST_ITEMS` on a `VlLs` count | `AbrReaderTest.aListWithTooManyItemsIsRefusedAndNamed` | `boolList(20 001)` has count 20 001 and 100 005 bytes of body; with the cap gone the list reads, the file reads, and `assertFailsWith<BrushException>` on line 120 fails with "Expected an exception of class BrushException to be thrown, but was completed successfully." The `count > MAX_LIST_ITEMS` message assertion on line 123 never runs. (The 200 000-node budget is *not* what catches it: 20 001 bools is 20 002 nodes.) |
| `MAX_DESCRIPTOR_NODES` | `AbrReaderTest.aDescriptorTreeOverTheNodeBudgetIsRefusedAndNamed` | 11 × 20 000 bools = 220 001 nodes. With the budget gone, all eleven lists are parsed and the file reads; `assertFailsWith` on line 109 fails the same way. The list cap does not catch it either — each list is at its own edge of 20 000. |
| `MAX_DESCRIPTOR_DEPTH` | `AbrReaderTest.aDescriptorTooDeepIsRefusedAndNamed` | `nestedDescriptors(40)` reaches depth ≈ 44. With the check gone the chain reads to the bottom and the file reads; `assertFailsWith` on line 93 fails. The "a real depth is not a refusal" half (`nestedDescriptors(8)`, line 96) would still pass, which is why the negative half is asserted first. |
| `MAX_BRUSHES` on the `Brsh` count | `AbrReaderTest.moreBrushesThanTheCapIsRefusedAndNamed` | 2 049 brushes; with the check gone every one is parsed and `assertFailsWith` on line 147 fails. Note this guard is **not** redundant with `MAX_LIST_ITEMS`: 2 049 < 20 000, so without the brush cap this file would be accepted. |
| Truncation (`AbrCursor.need`) | `AbrReaderTest.aTruncatedFileIsARefusedAndNotAPartialBrush`, `aSectionLengthThatOverrunsTheFileIsRefused`, `AbrImportTest.malformedInputIsARefusalOrAnExceptionAndNeverAPartialPreset` | with `need` unbounded the reader indexes past the array and throws `ArrayIndexOutOfBoundsException` (not a `BrushException`) — so `assertFailsWith<BrushException>` on line 175 fails on the *type*, not just on "did it throw". The truncation that the new `Brsh` count test leans on (`aBrushListCountThatDisagreesWithTheSectionIsRefused`, asserting `reason.contains("truncated")`) depends on it too: entry 3's `cur.u16()` is what produces the sentence. |
| `MAX_STRING_BYTES` | `AbrReaderTest.aStringOverTheCapIsRefusedAndNamed` | `"x".repeat(32 769)` is 65 538 bytes; with the cap gone the string is read and `assertFailsWith` on line 134 fails. Its own edge (32 768 chars = exactly 65 536 bytes, the cap) must still read, which is what the second half of the test pins. |
| `MAX_TIP_DIM` / `MAX_TIP_PIXELS` / `MAX_TIP_BYTES` | `AbrImportTest.aTipOverTheDimensionCapIsRefusedWithASentenceAboutItsSize`, `aTipWithImpossibleBoundsIsRefusedWithASentenceAboutItsSize` | 2 049 × 1: with the cap gone the tip is "read" from 2 049 bytes and `assertEquals(0, library.brushes.size)` on line 653 fails. The 2 048 × 2 048 edge (= exactly `MAX_TIP_PIXELS`) must still convert — asserted at line 676. |
| File / section / node budgets in general | unchanged by this row except `MAX_SECTION_BYTES` and `MAX_LIST_ITEMS`, whose `patt` users are gone | — |

**One guard changed rather than loosened:** `tipError`'s PackBits sanity check. It used to compare
`remaining` against one row's `width × bytesPerSample`, which was the *interleaved* layout's question. It
now compares against `height × 2`, the row-count table, which is the cheapest thing the real layout needs
first. The rest of the walk is `rowByteCounts`'s, and each count is still refused if the entry cannot
honour it. No cap was widened anywhere in this row — `grep -n "MAX_" ` over the diff shows only
`MAX_LIST_ITEMS`/`MAX_SECTION_BYTES`/`MAX_SECTIONS`/`MAX_BRUSHES`/`MAX_TIP_*` unchanged in value.

---

## 4. Every symbol I was unsure of, and how I resolved it

| Symbol / assumption | How I resolved it |
|---|---|
| `VlLs` layout | read `ag-psd-descriptor.ts:481-575` (`readOSType`), case at 488. Confirmed against all four files' bytes. |
| `samp` entry layout | `ag-psd-abr.ts:536-582`, line by line. Confirmed by decoding both real tips with exact row consumption. |
| PackBits row counts — table or interleaved | Tried all three candidate layouts in Python (`samp.py`) against both real tips; only "table first" decoded every row to exactly `width` pixels. |
| `depth`/`compression` width | `ag-psd-abr.ts:554-555`: `readInt16` then `readUint8`. Not guessable. |
| Preamble size | `ag-psd-abr.ts:546`: `minorVersion === 1 ? 10 : 264`, skipped *after* the Pascal id. |
| Minor-version validity | `ag-psd-abr.ts:527`: refuses anything but 1 or 2. Added the matching refusal. |
| `patt` record layout | **Could not resolve it.** See Q1. The section is skipped by its declared length, which is what the spec asked for. |
| `patt` header fields | `u32 1, u32 1, u16 x, u16 y, unicode name, pascal id` — this part I *did* confirm against ag-psd's oracle (x 597 / y 595 and 128/128 and 256/256 all match), but the record-length framing around it does not reconcile on `special` (the second record's name-count field sits 18 bytes after where the first record's declared length ends). So I did not model it. |
| Unicode string padding | Proved there is **none** in an `.abr`: the `desc` section of `simple` parsed to the byte (40 + 622 = 662 = the file's size) with no padding skipped anywhere. |
| NUL stripping vs. stopping at the first NUL | ag-psd's `data.json` says `"Soft Round"` for `"Soft Round\0"`, so at least trailing ones go. Stopping at the first NUL would shorten a name that legitimately contains one, so only trailing NULs are dropped. |
| `abrupt`: `GlbO`, `type`, `GlbC`, `ObAr` | `ag-psd-descriptor.ts:481-562`. No real file uses any of them, so each is fixed to ag-psd's shape or refused. |
| `readReference`'s `'name'` variant | ag-psd reads a class structure then a Unicode string; this build reads a length-prefixed ASCII run. **Left as is** — it is outside this row's owner area and no real file uses it. See Q4. |
| `BrushValidate` rule set the real files could trip | read `BrushValidate.kt:56-303`; the only rule a real value can reach is rule 14 (a grain is ranged even when disabled) and rule 19 (`tiltScale` 200 % clamps to a `(1,1)` curve point, `x = 1.0` is in 0..1). |
| `TipSpec`/`GrainSpec` defaults | not needed: the existing synthetic tests already assert `BrushValidate.validate(p) == []` for computed and sampled brushes, and the real files' values are inside the same ranges. |
| `ImportLibrary` / `RefusedBrush` / `MAX_EXTENSION_BYTES` / `brushId` | read `ImportSupport.kt:13-45`. `converted + refused` is therefore the right count to compare with `data.json`'s `brushes.length`. |
| `kotlinx-serialization-json` on the `jvmTest` classpath | `core/build.gradle.kts:30-31` puts it in `commonMain` as `api`, so a JVM test sees it. Used it to read `data.json` rather than hand-scanning. |
| `org.junit.Assume` on the classpath | `commonTest` depends on `kotlin("test")` and nothing calls `useJUnitPlatform()`, so Kotlin 2.2.0's JVM default (JUnit 4, `kotlin-test-junit`) applies. **This is the one thing I could not verify by reading** — if the fix round reports an unresolved `org.junit`, it is a one-line swap to `throw org.junit.AssumptionViolatedException`. |
| `internal` visibility from `jvmTest` | `AbrRealFilesTest` calls `AbrReader.read`, `AbrReader.normaliseId`, `AbrFile.grayBytes` and `AbrTip` — all `internal` in `commonMain`. `commonTest` already does this (`AbrReaderTest` uses `AbrReader`, `AbrFile`, `AbrCursor`), and `jvmTest` compiles the same `commonTest` sources into the same target compilation with `-Xfriend-paths` to main, so this should resolve. **Second residual compile risk**, and the only reason the decoded-`w × h` check reads the reader directly instead of going round the importer (the importer's observable for a sampled tip is `Dmtr`, which is not the bitmap's width; on `special` that tip is used only by two refused brushes, so there is no importer route to it at all). If the fix round reports unresolved `internal` here, the fix is to move that one test into `commonTest` with the corpus path passed in. |

---

## 5. Questions for the Lead

**Q1 — `patt`: is skipping it the right answer, and who owns a real parser?**
The spec says skip it, so I did, and I deleted `AbrPattern`/`readPatterns` because nothing read them and
the parser was a guess that **crashed two of the four real files**. But I can confirm from the bytes that a
record header is `u32 1, u32 1, u16 x, u16 y, unicode name (count includes a NUL), pascal id` — that part
ag-psd's oracle corroborates exactly. What I cannot reconcile is the framing: on `special` the first
record declares 16 714 bytes and the second record's name-count field appears 18 bytes later than that
implies; on `sample-and-pattern` 3 bytes are left over after the only record. I did **not** invent a
reconciliation. Is `patt` genuinely needed before JB-1.05d, and does anyone want me to derive it properly
from the four files plus ag-psd's `psdReader.ts` (which is not in the local reference folder — only
`abr.ts` and `descriptor.ts` are)?

**Q2 — `TxtC` → `paperGrain.enabled` looks wrong, and the real files prove it.**
`sample-and-pattern`'s one brush has `useTexture: true` and `TxtC: true`, and `special` has three brushes
with `TxtC: true`. ag-psd reads `TxtC` as `texture.textureEachTip` — "texture each tip", a *modality*,
not an on/off switch. This importer maps it to `paperGrain.enabled`, which turns the grain **on**, while the
warning printed two lines above it says *"so paperGrain stays off"*. The code and its own sentence
contradict each other on a real file. I did **not** change it: the warning is right, the mapping is
doubtful, and turning it off would change `paperGrain.enabled` for every textured brush — which is a
mapping decision, not a format fix. Your call?

**Q3 — `ObAr` is now a refusal where it used to be a (wrong) blob.**
Reading it as a length-prefixed blob was certainly wrong, but refusing it is a behaviour change: a PSD-ish
descriptor carrying `ObAr` now refuses the brush it sits on instead of half-reading it. None of the four
`.abr` files carries one. Do you want a real `ObAr` port (it is ~20 lines against `descriptor.ts:541-562`),
or is "refuse with a sentence" the standing answer?

**Q4 — `readReference`'s `'name'` variant still does not match ag-psd.**
ag-psd reads a class structure and then a Unicode string; this build reads a length-prefixed ASCII run. I
left it because an `'obj '` value is outside what this row's four files exercise and I could not test the
change. It is the same class of bug as items 1–5 above, in the same file, so I would rather it were either
fixed or explicitly deferred than left undocumented.

**Q5 — the spec says the `.abr` files in the corpus are "ag-psd's own test corpus, MIT" and asks the
review to carry "the per-file verdict lines from the probe before and after".** I cannot produce "before"
verdict lines from a run — the probe that produced them (JB-8.05) was never run against these files by me,
and `RealFilesProbeTest` swallows outcomes into a text file. My "before" is therefore a derivation: which
guard each real file trips, which I can state exactly (`special`/`sample-and-pattern` → `patt` record
length; all four → `Brsh` byte length `0x4f626a63`). Is that acceptable as the "before", or do you want
`RealFilesProbeTest`'s output from a run on the pre-change commit?

**Q6 — the spec's item 3 says `depth:int16, compression:u8` but also says to keep the entry's 8-byte
trailer handling implicitly via "R4 §B.1: some versions append 8 trailing bytes".** The real files say
otherwise: an entry ends at its own `u32` length, and the two real entries have 9 and 10 bytes of zero
padding behind their pixels, not 8. I removed `TIP_TRAILER` and made the declared length authoritative. I
believe that is right and the R4 §B.1 sentence is the same class of guess as the rest, but it is a change
to a documented rule and the Lead should own it.

---

## 6. Files changed

| File | Change |
|---|---|
| `joybrush/core/src/commonMain/.../imports/AbrReader.kt` | the five format fixes above; `patt` parsing deleted; `GlbO`/`type`/`GlbC`/`ObAr` corrected; 1-bit and 16-bit-RLE refused by name; 16-bit raw takes the high byte; sub-version validated; `patt`/`phry` skipped by declared length; the file KDoc's four untruths rewritten |
| `joybrush/core/src/commonMain/.../imports/AbrImport.kt` | one comment only (`MAX_LIST_ITEMS` no longer bounds `patt` records) — **no behavioural change**, and that is the honest answer to spec item 5 |
| `joybrush/core/src/commonTest/.../imports/AbrReaderTest.kt` | builders rewritten to the real layouts; 6 new tests (`aBrushAfterAFailedEntrySaysTheOnesAfterItCannotBeFound`, `aListIsACountAndThenItsItemsAndHasNoLength`, `aSampEntryEndsAtItsOwnLengthAndThePaddingIsNotPixels`, `packBitsRowCountsAreATableThatComesFirst`, `aSixteenBitTipContributesItsHighByte`, `anUnknownSubVersionIsRefusedAndNamed`, `aNulTerminatedNameIsReadWithoutItsTerminator`); the stale "this build has no `.abr` to paste" gap note removed |
| `joybrush/core/src/commonTest/.../imports/AbrImportTest.kt` | `packBitsTip` and the hand-laid `Brsh` list rebuilt without the invented length; the end-to-end gap note corrected to point at `AbrRealFilesTest` |
| `joybrush/core/src/jvmTest/.../imports/AbrRealFilesTest.kt` | **new** — acceptance A, against `data.json`, skipping loudly with a printed reason, writing `%TEMP%/joybrush_abr_real.txt` |

## 7. What is still unproven

1. **Nothing has been compiled or run.** Not one line. The first `:core:jvmTest` after this commit is the
   real test of all of the above.
2. Every "imported"/"refused" in §2 is a prediction from a Python port of ag-psd, cross-checked against
   `data.json` — not an observation of this build.
3. `AbrValidate` is never called for a real brush in a way I have observed; the real-file test asserts the
   mapped numbers but not `BrushValidate.validate(p) == []` on a real file. `AbrImport` already refuses any
   brush that fails validation, so a converted brush is validate-clean by construction — but that is
   reasoning, not evidence.
4. The 16-bit path, the 1-bit refusal, the 16-bit-RLE refusal, the sub-version refusal, the NUL rule and
   the `GlbO`/`type`/`ObAr` changes are all covered by new unit tests I could not execute.
5. Two things in `AbrRealFilesTest` depend on a classpath fact I could not verify by reading: that
   `org.junit.Assume` resolves (JUnit 4 is Kotlin's JVM default here) and that `commonMain`'s `internal`
   declarations are visible from `jvmTest`. Both are one-line fixes if the fix round says otherwise, and
   both are called out in §4 rather than buried.
6. `RealFilesProbeTest` (JB-8.05) walks the whole corpus, so it will now be the second reader of these four
   files in one run; if it disagrees with `AbrRealFilesTest` that is worth reading.