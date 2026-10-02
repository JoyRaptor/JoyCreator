# JB-9.06b — cross-review of `b436e9cb0`

Reviewer: space-bunny-alpha (OpenCode). 2026-10-02. Read-only against `C:\Temp\jb-region-routing`
(`codex/region-routing`, HEAD `7b97d8ee`).

**Verdict: the change is right and I recommend landing it.** The arithmetic the row exists to fix is
correct, the bounded-block design does what it claims, and the two Lead-only ordering promises hold.
Four findings, none of them behavioural bugs: two are claims that outrun the code, two are test gaps.
One commit-message number I could not reproduce.

Base for this review is the file as it stands at HEAD. `RegionRenderer.kt` and `PaperBackdropTest.kt`
are unchanged between `b436e9cb0` and HEAD (`git diff --name-only b436e9cb0..HEAD` lists neither), so
the test XML on disk does describe the reviewed code.

---

## What I checked and it held

**The ordering promise is real.** `requireSize` (`RegionRenderer.kt:426`) holds the `MAX_REGION_PX`
refusal, and it is called at `:243` and `:287` — before the allocations at `:246`/`:290` and before
the callback at `:309`. So "the `paperRenderer` is NOT called for a refused region" is **true**, and it
is proven twice rather than asserted once: `PaperBackdropTest.kt:299-311` and
`PaperExportBlendTest.kt:124-128` both use a 4096×4096 rect and `assertEquals(0, calls)`.

**Blocks tile exactly, and the short last block is pinned rather than merely bounded.**
`PaperBackdropTest.kt:142` builds the expected request list with `minOf` and asserts list equality,
over `RectPx(-37,-11,300,100)` — width 300 = 256+44 and height 100 = 3·32+4, so *both* last blocks
are short and the test would fail if anything were clipped or padded.

**No seam.** `PaperBackdropTest.kt:181` compares all 30 000 pixels against a per-pixel oracle, with
the fixture's hard edge at document x=128 — deliberately **off** the 256 grid. A gap, an overlap or a
region-relative request all go red.

**Negative document origin** is proven by that same test, plus
`PaperRasterTest.kt:46 localFramePreservesGlobalHashAndSamplesAtLargeSignedCoordinates` for the real
`PaperResources` rasteriser rather than only a synthetic callback.

**The post-stack composition really is gone.** `rg overPaper` finds no call site in any source file.
`CanvasPng.kt`, `AnimExport.kt` (`encodeOne` `:133`/`:154-157` and `writeSequence` `:216`/`:225-228`)
and `OraExport.kt` (`:177`/`:250-253`) each pass the renderer through. The only remaining hit is a
*test name* at `RegionRendererTest.kt:677`, which uses the flat `paper` string and references no
deleted symbol.

**The ORA memory claim is accurate.** The old `val backdrop = renderer?.invoke(rect)` is gone; the
`Entry` now holds the *lambda* (`OraExport.kt:286`) and the array is created inside the `put(...)`
statement at `:244`, which completes before the merged composite is rendered at `:250`. So no
region-sized paper array is alive beside the 160 MiB composite. Worth being precise about what that
does *not* save: a transient full-region array is still allocated (≈33 MB at 4K) alongside that
statement's own PNG output. The Paper layer is still a real whole-region `data/0.png` and still the
bottom layer (`OraExport.kt:198-213` adds it first; `stackXml:456` iterates `asReversed()`), which is
what the spec permits.

**Both doors refuse two backdrops,** with `assertEquals(0, calls)`
(`PaperBackdropTest.kt:219-232`, guard at `RegionRenderer.kt:481-486`).

**Wrong length, translucent block, throwing renderer** each have their own named test with a message
assertion (`PaperBackdropTest.kt:241`, `:253`, `:275`; guards at `:562` and `:571`). The opaque rule
is applied to the *input* only, and `PaperBackdropTest.kt:120` proves ERASE_BELOW may still leave the
output translucent — which is correct, and the KDoc is right to say so.

**Paper excluded never decodes,** for PNG and animation: `CanvasPng.kt:68`/`:59` return null when
`!includePaper || screenTransparent`, `AnimExport.kt:132`/`:214` gate the same way, and the
`if (renderer == null) paper else null` argument means `RegionRenderer.kt:306` is never entered.
Proven by `PaperExportBlendTest.kt:102` (throwing renderer, byte-identical output),
`CanvasPngTest.kt:19`, `PaperNoneExportTest.kt:17,20` and `AnimExportTest.kt:141-142`.

---

## Findings

### MAJOR — contract 2's "never writes a partial file" is unproven, and is false for ORA as written

Spec contract 2 says: *"One renderer failure aborts the export, never writes a partial file or
silently changes the paper."* The **aborts** half holds — `aFailingRendererAbortsTheRender`
(`PaperBackdropTest.kt:275`) proves the exception propagates. The **no partial file** half has no
exporter-level test at all: `rg 'error\('` across the four `io` test files finds only the
*excluded-paper* throwers, never a failing renderer inside a real export.

And `OraExport.kt:263-266` catches `Exception` and rethrows `JbArchiveException` **after**
`mimetype`/`stack.xml` and possibly some `data/n.png` entries are already in the caller's `out`. So a
mid-export paper failure leaves a truncated archive in the buffer the caller handed in. `writeSequence`
has the same property by design (earlier frames already accepted, Decision 16).

This may well be the *caller's* contract rather than `RegionRenderer`'s, and the callers may discard
`out` on exception — but neither the spec nor the code says so, and "never writes a partial file" is
currently an unbacked claim. **Needed:** either a test that a throwing renderer inside
`OraExport.write` raises, plus a statement of who owns discarding `out`, or the claim reworded to what
is actually guaranteed ("the export aborts; a partially written archive may already be in the
caller's stream").

### MINOR — the class KDoc claims the per-block guards run before the region is allocated

`RegionRenderer.kt:180` lists all four textured-paper refusals together — "two backdrops at once, a
block of the wrong length, a block that is not opaque" — and then says *"they are checked BEFORE the
region is allocated and before the first block is asked for, so an impossible request never costs a
decode."*

That is exactly right for **two backdrops** and for the **region refusal** (both in `requireSize`/
`requireOneBackdrop`, before allocation). It is **not possible** for the other two: a block's length
and alpha can only be checked once the callback has returned it, so those guards necessarily run
*after* the `FloatArray` at `:290` and *after* the decode. The in-function comment at `:307-308` is
correct and narrower ("AFTER the allocation and after every guard above") — it is the class KDoc
that over-claims.

No behaviour is wrong. But this project has been bitten by exactly this before (LEAD_DESK records
"fixed KDocs that were still false" as a past false claim), and a future reader will trust the class
doc over the inline one. **Needed:** split it into two sentences — the request-level guards before
allocation, the block-level guards per block as it arrives.

### MINOR — no test for the ORA excluded-paper path

`OraExport.kt:175` gates correctly by inspection:

```
val paper = if (includePaper && !doc.paper.screenTransparent) checkedPaper(…) else null
```

but `OraExportTest` only calls `OraExport.write(…, false)` *without* a renderer (`:211`, `:1030`,
`:1047`). Every other exporter has a test that pairs "excluded" with a recording or throwing
renderer. The ORA path is correct but unproven, and it is the one path where a regression would be
silent. **Needed:** the same `{ error(…) }` renderer assertion the other three have.

### MINOR — the no-seam fixture varies only in x

`PaperBackdropTest.kt:181` compares against a `paperOf` that splits on `block.x + x >= 128` and is
constant in y. The three failure modes the KDoc names are genuinely caught — a one-run shear shifts
the pattern by 44 and a region-stride read runs off the end of the block — so I am not claiming a
live gap. But a **row mis-ordering inside a block** would be invisible to this oracle, and
`layPaperBlocks` is exactly the code that had to be written row-by-row for that reason (the comment
at `RegionRenderer.kt:~540` says so). **Needed, if cheap:** make the fixture vary in y as well.

---

## Cannot verify

**The commit message's "core 82/0 over 5 suites."** The five plausible suites
(PaperBackdrop 11 + RegionRenderer 47 + RegionTileSource 6 + LayerMask 10 + BlendParity 9) sum to
**83**, not 82. Either the message names a different set of five or it predates a test added since.
Not resolvable from the artifacts on disk. Not a defect — but per the board's standing rule that a
Who-cell is a claim and not evidence, the number should be re-derived before it is quoted anywhere.

**I did not run the suite myself.** `%TEMP%/jb-gradle.lock` was held by `JB-LEAD-APK
ab3b5b6b-36e8-4a00-8a4e-d891d58ce88e` for the whole review. I did not delete it and I did not build.
Corroborating artifacts already on disk, from an earlier run and **not** mine: `core` 110 suites /
1546 tests / 0 failures / 0 errors / 4 skipped, newest XML written `2026-10-02T16:18:54-04:00`; and
`androidkit` 23 suites / 241 tests / 0 failures. `PaperBackdropTest` reports `tests="11"
failures="0" errors="0"` and all eleven names above are present as testcases; `PaperExportBlendTest`
reports `tests="4" failures="0"`. Treat those as supporting evidence for the code under review, not
as a verification I performed.

---

## Recommendation

Land it. The row does what it says: a MULTIPLY layer now multiplies the paper on export exactly as it
does on screen, the paper costs bounded 32 KiB blocks in exact document coordinates, and the 160 MiB
worst-case region claim is unchanged. Nothing I found would stop a merge.

Close the two MINOR test gaps and reword the two over-reaching claims. The MAJOR is a decision for the
Lead rather than a code change: either state who discards a partially written archive, or drop the
guarantee. None of the four blocks the row from landing.

**Still owed and not claimed by anyone here:** device export parity. Every check above is CPU-side and
test-fixture-side. Nobody has compared a real exported PNG against the screen for a Multiply layer on
the Note 9, and that comparison is the Lead's and the owner's to make.
