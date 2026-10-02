# JB-9.06b — textured paper participates in export layer compositing

Worktree `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b`, branch `codex/jb-9.06b`, rebased onto
`origin/joy-creator` at `0dd712ed` (the Lead's v7 UI/IO at `9ff49d81`/`017cd4ed` and the JB-9.06c
filtering fix at `fbcd48fd` are both in that base, so the v7 landing gate was open before the first
edit). The unverified `region-preparation.patch` left in the worktree was discarded unread, as the
handoff instructed, and the row was implemented from the approved spec.

## The defect, and the fix

A MULTIPLY (or any separable) layer was composited over TRANSPARENCY and the paper was then pasted
over the finished picture. `dst` in the blend formula was `(0,0,0,0)` instead of the paper, so the
exported file disagreed with the screen. The screen has always cleared to paper and composited over
it.

`RegionRenderer.render`/`renderPremultiplied` take an optional
`paperRenderer: ((RectPx) -> ByteArray)? = null` and lay it down as the FLOOR of the stack, before the
first layer, in blocks of at most `PAPER_BLOCK_W` x `PAPER_BLOCK_H` (256 x 32, 32 KiB) requested in
exact global document coordinates. `CanvasPng`, `AnimExportRunner.encodeOne`, `writeSequence` and
`OraExport.write` pass their already-resolved renderer through; the post-stack `overPaper` composition
is deleted.

| Contract point | Where it is enforced |
|---|---|
| Both doors refuse a flat colour AND a renderer | `requireOneBackdrop`, before allocation |
| Callback invoked only after region/allocation guards | `renderPremultiplied`, after `requireSize` + the float allocation |
| Block bound 256 x 32 | `PAPER_BLOCK_W`/`PAPER_BLOCK_H`, pinned by `blocksAreBoundedTiledAndInDocumentCoordinates` |
| Every block's shape and alpha validated | `requirePaperBlock` — length refused, never padded or truncated; alpha below 255 refused, naming the document pixel |
| A renderer failure aborts the export | the exception propagates; nothing is written |
| Reused decoded material, no second whole-region backdrop | `paperRendererFor` resolves once per export; blocks are per render |

## Two things this row deliberately did NOT do

**It did not add a streaming PNG row writer for the OpenRaster paper layer.** `data/0.png` is the
Paper LAYER — a real image a reader opens directly — so it genuinely needs a whole region of paper. It
is now produced inside the write loop, where the array is a temporary of one statement and is
unreachable by the time `merged` is rendered. That is one transient region-sized array instead of one
held for the whole method beside the composite. `PngWriter.encode` is a JB-3.06a file and a streaming
entry point is that row's to add; the shape of the saving is asserted instead (see the ORA test).

**It did not touch the flat-paper path's arithmetic.** For a document with no look, texture or tint,
`AnimExport`/`OraExport` already passed the colour to `RegionRenderer` as the pre-layer `paper`
argument, and they still do. `CanvasPng` always resolved through `PaperResources` and still does (a
document can carry a surface with no look, texture or tint, and a bare colour would drop its relief);
only WHERE it is composited changed.

## Commands and evidence

`jb-gradle.lock` taken atomically (`FileMode::CreateNew`) for every Gradle call and released in
`finally`. Always `--no-daemon --no-watch-fs --max-workers=1`, in-process Kotlin, `-Xmx768m`.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*PaperBackdropTest' --tests '*RegionRendererTest' --tests '*PaperMipTest' --tests '*PaperRasterTest' --tests '*HexTileTest'
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :androidkit:test --tests '*PaperExportBlendTest' --tests '*CanvasPngTest' --tests '*OraExportTest' --tests '*AnimExportTest' --tests '*PaperNoneExportTest' --tests '*PaperResourcesTest' --tests '*PngWriterTest' --tests '*JbArchiveTest'
```

Own XML, after the mutations were reverted:

- `joybrush/core/build/test-results/jvmTest` — **82 tests, 0 failures, 0 errors, 0 skips, 5 suites**
  (PaperBackdropTest, RegionRendererTest, PaperMipTest, PaperRasterTest, HexTileTest); newest
  `PaperBackdropTest` XML **2026-10-02T15:55:04.403-04:00**.
- `joybrush/androidkit/build/test-results/test` — **132 tests, 0 failures, 0 errors, 0 skips, 8
  suites** (PaperExportBlendTest, CanvasPngTest, OraExportTest, AnimExportTest, PaperNoneExportTest,
  PaperResourcesTest, PngWriterTest, JbArchiveTest); newest `AnimExportTest` XML
  **2026-10-02T15:56:47.018-04:00**.

Two assertions were fixed during the work rather than after it: a `Byte` 200 read as `-56` in a test's
expected value, and two `CanvasPng.encode(…) { … }` calls where the trailing lambda bound to
`onWarning` instead of `paperRenderer` — which compiled, and would have left a renderer-supplied test
silently asserting nothing. Both are named arguments now.

## Mutations

1. **Paper moved back to post-stack compositing in `CanvasPng`** (re-render the region with no paper,
   then blend the finished art over a whole-region sheet). `:androidkit:test --tests
   '*PaperExportBlendTest'` → **2 failed / 4** — `thePngMultipliesThePaperInsteadOfPastingItOverTheTop`
   (line 46) and `everyExporterPutsTheSamePixelsUnderTheSamePaper` (line 85). The two tests that do
   not depend on ordering stayed green, which is the shape one wants from a mutation.
2. **Block-length guard removed from `requirePaperBlock`.** `:core:jvmTest --tests
   '*PaperBackdropTest'` → **1 failed / 11** — `aBlockOfTheWrongLengthIsRefused`, and the failure
   mode is the argument for the guard: an unchecked `ArrayIndexOutOfBoundsException` from inside the
   renderer walk instead of a sentence naming the block.

Both mutations were reverted and the suites re-run green; the counts above are from the restored run.

## What the tests actually pin

`PaperBackdropTest` (core, 11 tests) — a MULTIPLY layer over a two-tone paper equals the hand-worked
product and is NOT the old paste-over; all 27 blend modes agree with the already-trusted flat
backdrop of the same colour, over a region at a negative origin spanning several blocks; blocks are
bounded, tile the region exactly, and carry document coordinates; a paper that varies with position
comes back byte-identical across block edges (300x100, no layers, every pixel asserted); blocks and
tiles are never mutated; two backdrops, wrong length, translucent block, a throwing renderer, an
oversized region, a negative side and an empty region are all refused, and the last three ask for no
paper at all.

`PaperExportBlendTest` (androidkit, 4 tests) — decoded PNG, decoded animation-sequence frame and
decoded `mergedimage.png` are compared pixel for pixel over the whole board and must agree, and must
agree on the MULTIPLY answer rather than the paste-over; excluded paper is never read and changes no
byte; an oversized board is refused before any paper block is asked for.

`OraExportTest` and `AnimExportTest` were updated to the new contract, not deleted: the ORA test now
asserts that exactly ONE request is a whole region (the Paper layer's own PNG) and that the merge is
blocks; the animation test now asserts the blocks tile the board, that each frame re-asks rather than
replaying one shared array, and that an excluded export asks for nothing.

## Not claimed

- No APK was built and no phone was touched. Export parity on a device is the Lead's/owner's check.
- The 160 MiB figure is unchanged and still describes the REGION; the paper's contribution to it is
  now 32 KiB of temporary rather than a second region. That is an argument from the code, not a
  measured peak on a Note 9.
- The animation path re-rasterises the paper per frame instead of once per export. That is a
  deliberate CPU cost traded for not holding a region-sized paper array beside `cells`, which for a GIF
  is the whole animation. It has not been timed on a device.
- `CanvasSnapshot` and the screen compositor were not touched; they already drew paper first, which is
  the behaviour this row makes the exporters match.

## House rules

Worktree only; the exploratory patch discarded; lock taken and released around every Gradle call;
`--no-daemon`; no Gradle in the main folder; no phone installation; no hot app or shader file edited,
so no `LEAD_DESK.md` question was owed. One honest note for the Lead: the main folder's continuous
`:app:assembleDefaultDebug` watcher was running throughout and does not take `jb-gradle.lock`; it was
measured idle (0.02 s CPU over 10 s) before each run, and no attempt was made to stop another lane's
process.
