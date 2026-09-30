# JB-8.01b — The `.abr` reader has never read a real `.abr`; make it (Lead-written)

| | |
|---|---|
| **Tier** | T2 (Lead-written, so it is Ready) |
| **Status** | 🟦 **Ready** — written by the Lead 2026-09-29 from a real-file probe (LEAD_RULINGS R44 item 3) |
| **Needs** | JB-8.01 (🟧), JB-8.05 (`RealFilesProbeTest`, already landed) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/.../brush/imports/AbrReader.kt` · EDIT `.../imports/AbrImport.kt` only if the model changes · EDIT `joybrush/core/src/commonTest/.../imports/AbrReaderTest.kt`, `AbrImportTest.kt` · NEW `joybrush/core/src/jvmTest/.../imports/AbrRealFilesTest.kt`. **NOT** `ImportSupport.kt`, **NOT** any app file. |
| **Command** | `cd` INTO your worktree, then `./gradlew -p joybrush :core:jvmTest` (R43) |

## Why this row exists

On 2026-09-29 the Lead downloaded four real `.abr` files (ag-psd's own test corpus, MIT) into the git-ignored
`joybrush/testdata-local/abr/{simple,tilt,special,sample-and-pattern}/`. Each folder holds `src.abr` and
`data.json` — **ag-psd's own parse of the same file, an independent oracle**. `RealFilesProbeTest` imported all
four. **All four failed**, and the first failure is a single invented rule:

> `AbrReader` reads a `VlLs` (list) as `type, count:u32, byteLength:u32, items…`. **There is no `byteLength` in
> a real file.** A real list is `type, count:u32, items…` and each item carries its own type tag
> (`Objc`, `doub`, `long`, …). Reading the first item's bytes as a length is why every real brush reads as
> "truncated" or as garbage.

That was written from a spec that guessed, tested against synthetic files built from the same guess, and
reviewed by models that could not see a real file. The unit tests are green and the importer does not work.
This row fixes it against the truth.

## The truth (read these; they are local, MIT, and not committed)

- `joybrush/testdata-local/reference/ag-psd-abr.ts` — ag-psd's `readAbr` (774 lines, MIT, LICENSE beside it).
  **It is the format specification for this row.** Port it, do not re-derive it.
- `joybrush/testdata-local/reference/ag-psd-descriptor.ts` — its descriptor reader: `readVersionAndDescriptor`
  and the value types. Line ~488 `case 'VlLs'` is the list reader: `count = readInt32; repeat(count) readOSType`.
  (Search the file for `'VlLs'`, `'Objc'`, `'UntF'`, `'TEXT'`, `'enum'`, `'tdta'`, `'obj '`.)
- `joybrush/testdata-local/abr/*/data.json` — the expected parse of each file.

If `testdata-local/` is missing in your worktree (it is git-ignored, so worktrees do not have it), set
`JOYBRUSH_TESTDATA=C:\+Projects\Screenrecorder\FadCam\joybrush\testdata-local` for the run. `core/build.gradle.kts`
declares that folder as a test input, so a change in it re-runs the tests (R44).

## What to change

1. **`VlLs`** (both places in `AbrReader.kt`: `readBrushListHeader` and `readValue`): remove the invented
   `byteLength`. A list is `count:u32` then `count` self-describing values. Keep the item-count cap
   (`MAX_LIST_ITEMS`) and the node budget — they are the bomb defence and stay. The "list region" bound goes away;
   the section's own end is the bound, and the `AbrCursor` already refuses to read past it.
2. **The `Brsh` list** is then just a list of `Objc` values inside the `desc` descriptor: parse it with the same
   `readValue`. The special `BrushListHeader`/`readBrushList` split existed only to honour the invented length —
   delete it if nothing else needs it. Keep the behaviour that ONE unreadable brush is reported as
   `AbrBrush.Unreadable("brush N cannot be read: …")` and the rest still import, but note a real list cannot be
   skipped by length any more, so after a failure the remaining brushes are reported unreadable too (say so in
   the message: "the brushes after it cannot be found without reading it").
3. **The `samp` section** (`readTips`): compare entry by entry to ag-psd (`brushLength` u32 padded to 4, Pascal
   id, then `10` bytes for minor version 1 or `264` for minor 2 of preamble, then bounds as **top, left, bottom,
   right** `int32`, then `depth:int16`, `compression:u8`, then the pixels). Fix whatever differs. ag-psd accepts
   versions 6, 7, 9, 10 with minor 1 or 2 and refuses 1/2 — match it. Its RLE is Photoshop PackBits with a per-row
   `u16` byte count table — the file already has a PackBits reader; reuse it.
4. **`patt`/`phry`** sections: skip by their length (they are `8BIM`+key+length blocks). Patterns are not
   imported (JB-1.05d), but they must not stop the file being read.
5. **Everything else in `AbrImport.kt`** (the mapping to a `BrushPreset`) stays as is unless the real data proves it
   wrong — and if it does, fix the mapping and write the reason in the test.

## Acceptance (this is the whole point)

**A. `AbrRealFilesTest` (jvmTest).** For every `testdata-local/abr/*/src.abr` present:
`AbrImport.convert(bytes, "abr")` must NOT throw, and against `data.json`:
- the number of imported brushes plus the number of `Unreadable` brushes equals `data.json`'s `brushes.length`;
- for each brush, the imported name equals `brushes[i].name`; the tip kind matches (`shape.type` `computed` ⇒ a
  computed tip, `sampled` ⇒ a tip carrying an image and the `samples` entry with that id); `spacing` (a fraction:
  `data.json` says 1.0 = 100 %) agrees within the importer's own conversion; computed `size` and `hardness` agree.
- `sample-and-pattern`: the sampled tips decode to `w × h` matching `samples[i].bounds` (compare sizes and the sum of
  the alpha bytes to `data.json` if it carries them; otherwise sizes only).
When the folder is empty or the files are absent, the test **skips with a printed reason** (never passes silently;
`assumeTrue` with a message). It also writes its verdict lines to `%TEMP%/joybrush_abr_real.txt`.

**B. Synthetic unit tests are rewritten to the real layout.** Every byte fixture in `AbrReaderTest`/`AbrImportTest`
that writes a `VlLs` with a byte length is a lie about the format: fix each builder to write `count` then the items.
A test that only passed because of the invented field is deleted or re-derived, and the diff says why. The security
tests (list count cap, node budget, truncation, nesting depth) stay and must still fail if their guard is removed
(**mutation-check each one and say so in the report**).

**C. Report** in `tasks/joybrush/reviews/JB-8.01b__<model>.md` (the builder writes the ledger; the orchestrator
reviews): the per-file verdict lines from the probe **before and after**, and any brush that still does not import
with the reason. Do not claim a file imports unless `AbrRealFilesTest` ran on it in your run and printed it.

## Do not

- Do not commit anything from `testdata-local/` (R8). Do not copy `ag-psd-*.ts` into the tree. Port the logic in
  Kotlin, keep the attribution comment already in `AbrReader.kt`.
- Do not "fix" a real file by loosening a bound so it passes. A cap that a real file trips is a cap set wrong: say
  which file, which cap, what number, and stop for the Lead.
- Do not touch the `.brush`/`.brushset` (Procreate) importer or the Krita importer: they have their own rows.
- Do not add a dependency.

## Definition of done

`./gradlew -p joybrush :core:jvmTest` green in your worktree, with `JOYBRUSH_TESTDATA` set, and the run's own
output shows `AbrRealFilesTest` executed (not skipped) on all four files.
