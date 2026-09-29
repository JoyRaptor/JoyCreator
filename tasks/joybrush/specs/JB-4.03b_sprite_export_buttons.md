# JB-4.03b — "Export" and "Export and open in SpriteLab" on the sprite board

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟨 **Draft.** The owner's ruling is already in writing and the packer is Built, so the *shape* of this row is settled — but two things are not mine to decide. **(1)** Writing **two** files (a PNG and a `.sprite.json`) through SAF needs a folder, not a file picker, and SAF's folder grant is a one-way door on some providers (Q1). **(2)** "Open in SpriteLab" means starting `SpriteSheetEditorActivity` with a `content://` URI, and that activity is `android:exported="false"` in the manifest, takes a **project id** and reads through `ProjectStorage` — so a URI alone does not open it (Q2). Neither changes the maths; both change the button. |
| **Needs** | 4.01, 4.03a (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/SpriteSheetExport.kt` · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/SpriteSheetExportTest.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the two buttons and the folder picker) |
| **Estimated size** | ~200 lines of Kotlin + ~180 lines of tests, ~120 lines of activity |
| **Command** | `./gradlew -p joybrush :core:jvmTest :androidkit:test` — 0 failures. Then the watcher compiles `:joybrush-android:compileDebugKotlin` green. |

## Goal

`OWNER_CONSTRAINTS.md`, 2026-09-28, in the owner's own words:

> **"Sprite export = two buttons: 'Export' (save the sheet + .sprite.json) and 'Export and open in
> SpriteLab'. A file is always written first."**

Blueprint §4 Phase 4's owner check: *"A sheet drawn in Joy Paint opens in SpriteLab and plays in
the Studio."* So this row is the last step of Phase 4, and its whole reason to exist is that the
sheet Joy Brush writes must be **the same bytes** that SpriteLab opens.

Everything needed is Built: `SpritePacker.pack` (JB-4.03a) produces the pixels and the sidecar,
`PngWriter.encode` (JB-2.14a) writes the PNG, `RegionRenderer` (JB-2.13a) flattens the board's cells,
and `SpriteGridMath` (JB-4.01a) says where the cells are. This row is **plumbing and two refusals
that say what is wrong.**

## Contract

```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.export.Clip
import cc.joycreator.joybrush.core.export.PackedSheet
import cc.joycreator.joybrush.core.render.TileSource

/**
 * Writing a sprite board out as the two files SpriteLab reads. **A FILE IS ALWAYS WRITTEN FIRST**
 * (the owner's ruling): both buttons run this, and "open in SpriteLab" only runs after it returns
 * without throwing. There is no path that opens SpriteLab with something in memory.
 */
object SpriteSheetExport {

    /** The bytes of the two files, named. Nothing is written; nothing is read. */
    data class Written(val sheetFileName: String, val sheetPng: ByteArray, val sidecarFileName: String, val sidecarJson: String) {
        // ByteArray fields: == compares by IDENTITY. Compare with contentEquals.
    }

    /**
     * Renders the board's cells and packs them.
     *
     * @param clips the animations, normally ONE — the roll from JB-4.02, in cell order, with
     *   `type` from `PlayMode` and `fps` = the board's. Every clip frame index must be a cell this
     *   board has, or `SpritePacker.pack` refuses.
     * @param cellNames sparse aliases; omitted entirely when empty, so a sheet that gains nothing
     *   does not churn the file.
     * @throws RegionException if one cell is over `RegionRenderer.MAX_REGION_PX`.
     * @throws IllegalArgumentException from `SpritePacker.pack`, unchanged.
     */
    fun prepare(
        doc: JbDocument,
        board: Board,
        tiles: TileSource,
        clips: List<Clip>,
        cellNames: Map<Int, String> = emptyMap(),
        name: String,
        includePaper: Boolean,
    ): Written

    /**
     * The name the sidecar's `sheetUri` carries, and the name the PNG is written under: ONE
     * `name` for both, `<name>.png` and `<name>.sprite.json`, so the relative reference in the
     * sidecar and the file beside it cannot be different names.
     */
    fun fileNamesFor(name: String): Pair<String, String>
}
```

## Decisions

1. **Both buttons run `prepare` and write BOTH files. "Export and open in SpriteLab" differs only
   in what it does afterwards.** *Why:* the owner's own sentence, and it is the right shape — two
   buttons that can disagree about what was written are two exports.
2. **A file is always written first — the second button never opens SpriteLab without the files on
   disk.** If the write fails, the button says so and **does not** open anything. *Why:* verbatim
   owner ruling, and it is the same instinct as R11: a person who pressed "open" and got an empty
   SpriteLab has learned that Joy Brush lies.
3. **The sheet is rendered cell by cell through `RegionRenderer`, using `SpriteGridMath.cellRect`
   for the rectangles — never one render of the whole board and a slice.** *Why:* `cellRect` is
   the packer's own reading-order formula, proven equal to `SpritePacker.pack` by JB-4.01a test 8.
   Slicing a whole-board render would be a second copy of that formula in a place with no test, and
   a board whose art does not fill its grid would put the wrong pixels in the wrong cells.
4. **Every cell of the grid is packed, including the empty ones**, and an empty cell is a
   transparent rectangle of the right size. *Why:* `SpritePacker`'s rows come from the cell COUNT and
   the sidecar's grid claims all of them; a sheet that packed only the used cells would have a
   sidecar describing a grid the image does not have. The alternative — packing only used cells and
   renumbering — is what `cellNames` already refuses ("a name on a transparent slot is a name on
   nothing").
5. **`cols` defaults to the grid's own `cols`** — the board's own column count, not a packing
   choice. *Why:* a sheet exported from a 8 × 4 grid comes back as an 8 × 4 grid, which is the whole
   point of the grid existing. Re-flowing to 8 columns for tidiness would surprise the person whose
   art they lined up.
6. **The clips come from the roll (JB-4.02) in cell order, with `type` from `PlayMode` and `fps`
   from the board.** The roll's cell index is the cell index. *Why:* R23's vocabulary, and it means
   the order the person built and the order SpriteLab plays are the same list, not two.
7. **⚠️ A roll entry's HOLD is written as a `weights` array, and `SpritePacker` cannot write one
   today.** See Q2 — this is a finding about a Built contract, and until it is ruled this row
   **refuses a roll that has any hold above 1, in words**, rather than exporting a sheet that plays
   the right cells at the wrong speed. *Why:* a silent timing loss in a file contract is the worst
   outcome available, and "refuse in words" is the house answer.
8. **`includePaper` comes from `doc.paper.includeInExport` and the button's checkbox writes back to
   it**, so the next export starts where the person left off. *Why:* blueprint §2 and JB-2.13b's
   precedent; the setting is the document's, not the dialog's.
9. **`assertEncodedSize` is called on the encoded PNG BEFORE either file is written.** It is the
   packer's own method and its own rule, already there. *Why:* a disagreeing pair of files is worse
   than a message, and the check costs one call.
10. **The sheet and the sidecar go into a FOLDER the person picks once, with
    `ACTION_OPEN_DOCUMENT_TREE`; the "Export" button is offered again with a file picker as a
    fallback** that writes the PNG to the chosen file and the sidecar beside it when the provider
    supports it, and otherwise says exactly that. *Why:* two files need two names, and one SAF
    picker returns one `Uri`. This is Q1 and it is the one place I have invented a mechanism,
    because leaving it blank would be worse.
11. **Both files are built in full in `cacheDir` first, then streamed to the folder** — R11's rule,
    verbatim, for R11's reason. *Why:* a SAF `Uri` has no rename, so writing straight into it leaves
    a half sheet at a name the person chose. Both files or neither.
12. **The file name is `<name>.png` / `<name>.sprite.json`, from the board's `name`, sanitised by
    `AnimExport.safeBaseName`.** *Why:* one name for both, so the sidecar's relative `sheetUri` and
    the file beside it cannot differ, and one sanitiser rather than two.
13. **"Open in SpriteLab" writes first, then starts `SpriteSheetEditorActivity` with the sheet's
    content `Uri` and the project the sheet belongs to.** *Why, and it is the whole of Q2:*
    `SpriteSheetEditorActivity.onCreate` reads `EXTRA_PROJECT_ID`, calls `storage.load(projectId)`
    and **`finish()`es with a toast when the project is null**. A `Uri` alone opens nothing, and
    `android:exported="false"` means no other app may be launched into it at all. This row cannot
    make that work; it can only do the part that is its own.
14. **If "open in SpriteLab" cannot be honoured, the button says so BEFORE the write and offers
    only "Export".** *Why:* the house rule — refuse in words, in the moment, rather than writing
    two files and then showing an empty screen.
15. **No progress dialog, no overwrite prompt beyond SAF's own, and no "share" intent.** *Why:*
    none of the three is in the owner's ruling and each is a decision somebody should make
    deliberately.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (one write path) | `bothButtonsRunTheSamePrepare` — the view exposes one function and the second button calls it |
| 2 (file first, always) | `theOpenButtonNeverFiresWhenTheWriteFails` — a failing destination, and the intent count is **0** |
| 3 (cell by cell) | `cellsAreRenderedThroughCellRectAndNotSlicedFromAWholeBoardRender` — a board whose art fills only part of the grid, and each cell's pixels are checked |
| 4 (empty cells packed) | `anEmptyCellIsATransparentRectangleOfTheRightSize` and the sidecar's `cols`/`rows` equal the grid's |
| 5 (`cols` = the grid's) | `anEightByFourGridPacksAsEightByFour` |
| 6 (clips from the roll) | `theClipsAreTheRollInCellOrder` |
| 7 (a hold is refused) | `aRollWithAHoldIsRefusedInWordsAndNothingIsWritten` |
| 8 (paper from the document) | `includePaperComesFromTheDocumentAndWritesBack` |
| 9 (`assertEncodedSize` first) | `theEncodedSizeIsCheckedBeforeEitherFileIsWritten` — asserted by the order of the runner's own log |
| 10 (a folder, with a file-picker fallback) | `bothFilesLandInTheChosenFolder` and `theFallbackSaysWhatItCouldNotDo` |
| 11 (build in `cacheDir`, then stream) | `aFailedWriteLeavesTheTargetWithZeroBytes` |
| 12 (one sanitised name) | `theSheetAndTheSidecarShareOneBaseName` and `aNameWithAPathSeparatorIsRefused` |
| 13 (write, then start SpriteLab) | `theOpenButtonWritesBothFilesBeforeItStartsAnything` |
| 14 (say so before the write) | `theOpenButtonRefusesInWordsWhenItCannotOpen` — and the write count is **0** |
| 15 (nothing extra) | `noProgressDialogNoOverwritePromptAndNoShareIntent` |

## Tests

`joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/SpriteSheetExportTest.kt`
(JVM, with an in-memory `TileSource` and a fake `Destination` that records bytes)

1. `aGridOfThirtyTwoCellsBecomesASheetWithThirtyTwoCells`: a 4 × 8 grid of 32 × 32 at
   `(100, −40)`, one opaque red tile at the origin of cell 5 only → `prepare` returns a
   `cols = 4`, `rows = 8`, 128 × 256 PNG; the sidecar parses and says `"cols": 4, "rows": 8`;
   **every cell except 5 is transparent** (read out of the real bytes), and cell 5 is red. Proved
   against the packer's own output, not against a description of it.
2. `anEightByFourGridPacksAsEightByFour` (Decision 5): the sidecar's `cols` is the grid's `cols`,
   not 8. A board of 3 × 1 packs as 3 × 1.
3. `cellsAreRenderedThroughCellRectAndNotSlicedFromAWholeBoardRender` (Decision 3): the art
   occupies a 40 × 40 square at document (0, 0) inside a 4 × 4 grid of 64 × 64 with the board at
   `(−7, 3)` — a negative origin and an off-grid start. Cell 0's rectangle is checked pixel by
   pixel against what `RegionRenderer` renders for `SpriteGridMath.cellRect(board, 0)`, and the same
   for cell 6 and cell 15. A whole-board render sliced at the wrong offset would fail here and pass
   every other test.
4. `anEmptyCellIsATransparentRectangleOfTheRightSize`: a grid with nothing drawn at all → every
   cell is `0,0,0,0`, the sheet is still `cols × rows`, and the sidecar is written. The sheet is not
   skipped and it is not zero-sized.
5. `theClipsAreTheRollInCellOrder`: a roll of cells `[5, 6, 5]` → the sidecar's `presets[0].frames`
   is `[5, 6, 5]`, its `type` is `loop`, and its `fps` is the board's. Parsed out of the JSON, so
   the file is checked and not the `Clip` object.
6. `aRollWithAHoldIsRefusedInWordsAndNothingIsWritten` (Decision 7): a roll `[(0, 3), (1, 1)]` →
    `IllegalArgumentException` whose message contains `3` and the word `hold`, and the fake
    destination's byte count is **0**. This test is the point of Q2 and it fails on purpose until
    the contract can express a hold.
7. `theEncodedSizeIsCheckedBeforeEitherFileIsWritten` (Decision 9): the runner's recorded call
   order is `[assertEncodedSize, write, write]`. Asserted as an ordering, not as a count.
8. `aFailedWriteLeavesTheTargetWithZeroBytes` (Decision 11): the fake destination throws on the
   second stream; the target's total is **0** and the temporary in `cacheDir` is deleted (asserted
   by the runner's own report, which the test reads).
9. `theSheetAndTheSidecarShareOneBaseName`: `fileNamesFor("Walk cycle")` →
   `("Walk cycle.png", "Walk cycle.sprite.json")`, and the sidecar's `sheetUri` is **exactly**
   `"Walk cycle.png"` — the relative name, checked by parsing the JSON.
10. `aNameWithAPathSeparatorIsRefused`: `"../evil"`, `"a/b"`, `"C:x"` all reduce to a safe token
    and the two names still share it.
11. `includePaperComesFromTheDocumentAndWritesBack`: `Paper(includeInExport = true)` → the pixels
    at a cell with nothing on it are the paper colour and opaque; `false` → alpha 0. And the
    checkbox's value is written back into the returned document.
12. `bothFilesLandInTheChosenFolder` / `theFallbackSaysWhatItCouldNotDo`: the fake folder records
    two writes and both names match; a provider that refuses a second write produces a message that
    names the sidecar, and the sheet's own bytes are **not** rolled back silently — the message says
    what is on disk.
13. `theOpenButtonWritesBothFilesBeforeItStartsAnything` and
    `theOpenButtonRefusesInWordsWhenItCannotOpen` and
    `theOpenButtonNeverFiresWhenTheWriteFails`: the intent-launcher is a fake that counts; the
    three counts are **2, 0, 0** respectively, and in the second case the write count is 0 too.
14. `noProgressDialogNoOverwritePromptAndNoShareIntent`: the fake context records every intent and
    every dialog it is asked to show, and the assertion is on those two lists.
15. **A round trip that is not a description:** the `prepare`d sidecar is parsed with kotlinx
    `JsonObject` and checked key by key against the key set `SpriteSheet.toJson()` writes — the same
    discipline JB-4.03a's own test 2 uses. A key Joy Brush invents is a file SpriteLab does not
    read, and the only way to know is to list the keys.

**Non-vacuity the builder must run and paste:** change `cols` in Decision 5 from `grid.cols` to `8`
and watch test 2 fail; then change Decision 3 to render the whole board once and slice it, and
watch test 3 fail.

Command: `./gradlew -p joybrush :core:jvmTest :androidkit:test` — 0 failures.

## Do not

- **Do not write one file and hope.** Both files or neither (R11).
- Do not open SpriteLab before the bytes are on disk. Not in any failure path.
- Do not add a key to the sidecar. It is a **closed format** (`SpritePacker`'s own KDoc: "Adding a
  key the app does not know is out of scope") and the app is the parser.
- Do not re-flow the sheet's columns, skip the empty cells, or renumber them.
- Do not add a "share", an "export as", a scale, a background colour, a quality slider or a progress
  dialog. None is in the owner's ruling.
- Do not edit `SpriteSheetEditorActivity.java`, the manifest, or `ProjectStorage`. That is Q2 and
  it is the Lead's, and it is an app file under the serialised order.
- Do not add `Clip.weights` to `SpritePacker` from this row. JB-4.03a is reviewed and cleared; Q2
  asks the Lead to rule on it and a re-review follows.
- No new dependencies.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest :androidkit:test` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (re-flowed columns → test 2 red; sliced render → test 3 red)
- [ ] watcher `build.log` shows `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] **owner check, Note 9:** on a 4 × 4 sprite board with three drawn cells, Export → a folder
      holding `<name>.png` and `<name>.sprite.json`; open the PNG — the cells must be where the grid
      said; then open the sidecar in SpriteLab by hand and the grid must come back with the right
      column count
- [ ] committed `JB-4.03b: sprite export buttons`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. Everything that writes a file is
decided and pinned. Two things are not mine, and one of them is a real gap in a Built contract.)_

### Q1 — for the Lead: two files, one picker

`ACTION_CREATE_DOCUMENT` returns one `Uri`. This export writes **two** files and the sidecar's
`sheetUri` is a **relative name beside the PNG**, so they must land in the same folder. Three ways:

- **(a) `ACTION_OPEN_DOCUMENT_TREE` — "pick a folder", then write both into it.** The only approach
  that always works. **The catch, and it is not small:** a tree grant is persisted, and some
  providers (and Android's own behaviour after uninstall) make it awkward to revoke; a person who
  grants a whole folder to an art app is being asked for more than they think. It is also a
  permission the person has to understand, on a screen with no explanation.
- **(b) Two `ACTION_CREATE_DOCUMENT` pickers, one per file, into whatever folder the person picked
  each time.** Two prompts, and the two files can land in different folders — which produces exactly
  the broken pair `SpritePacker.assertEncodedSize` exists to prevent, in a way that check cannot
  see. **I ruled this out.**
- **(c) A ZIP.** The `.joybrush` archive already does this and the code is Built. But SpriteLab
  reads a folder, not a zip, so this is a format the receiver does not accept. **Ruled out.**

I have implemented **(a)** in Decision 10, with a file-picker fallback that writes the PNG and
**says in words** that the sidecar could not go beside it. **I want that confirmed, because (a) is
a permission the owner is being asked to grant on a phone, and he is the one who should decide
that.** My alternative, if you would rather not ask for a tree: **write both files into the app's
own `getExternalFilesDir("joybrush")` and hand SpriteLab a `file://`** — no permission at all, but
the files live in the app's private-ish folder and a person has to find them. That is a worse
product and a safer prompt, and I do not know which the owner prefers.

### Q2 — 🔴 for the Lead: "open in SpriteLab" cannot work as the button is worded, and it is not this row's to fix

I read `SpriteSheetEditorActivity.onCreate` (lines 131–162) and the manifest entry (line 129). Three
facts, each of which is fatal on its own to "hand it a `Uri` and it opens":

1. **`android:exported="false"`.** No other application may start that activity. `JoyBrushActivity`
   and `SpriteSheetEditorActivity` are in the same app, so an explicit `Intent` **would** work — but
   that makes this a question about the *Studio's* screen, not about Joy Brush's button.
2. **It requires a project.** `EXTRA_PROJECT_ID` → `storage.load(projectId)` → `if (project == null)
   { Toast "no project"; finish(); }`. **A Joy Brush drawing is not a Faditor project.** There is no
   `projectId` to pass, and no code path that makes one.
3. **It reads the sheet out of the project's own bundle**, via `importSheetImage`, which copies the
   picked image into `projectDir/assets/sheet-<uuid>.png` and then `setSheetUri(Uri.fromFile(dest))`.
   So the file is not *read where it is*; it is **copied into a project**. A `content://` URI from
   SAF would have to survive that copy, and the activity's new-sheet flow opens
   `ACTION_OPEN_DOCUMENT` itself — meaning a person would be asked to pick the file **a second
   time**.

**So "Export and open in SpriteLab" is really "Export, and then make SpriteLab import the export",
and the making is a change to the Studio's screen.** Three options:

- **(a) Add an intent extra to `SpriteSheetEditorActivity` that takes a sheet `Uri` + sidecar `Uri`
  and imports them into a named project it creates for the purpose.** That is a real feature in
  the Studio's screen, it is app-file work, and it is the Lead's under the serialised order. **It
  also has a product question inside it: does a Joy Brush export become a new project, or does it
  join the project the person is currently in?**
- **(b) Leave the button as "Export and open SpriteLab" and have it write the files, then start the
  **SpriteLab lobby room** rather than the sheet editor** — the person then does the import. Honest,
  one less picker, and not what the button says.
- **(c) Ship "Export" only, and open SpriteLab's entry point from the lobby** — which is exactly
  what exists today.

My recommendation: **(c) now, and (a) as a new row owned by the Lead** — because (a) changes a
4 823-line screen in the Studio, and the owner's own rule is that Joy Brush hands over **files** and
"then one intent opens the receiving screen" (blueprint §3.1), which describes (a) as *the thing
that should be built*, not as something this row can assume. **Until it exists, Decision 14 says so
in words before the write, which is the only honest thing this row can do.**

### Q3 — for the Lead, and it is JB-4.02's Q2 arriving on this row's doorstep

`SpritePacker` (JB-4.03a, reviewed and cleared) writes a preset's `frames` and **never writes
`weights`** — while `SpriteSheet.toJson()` writes `weights` whenever `hasWeights()`, and
`SequenceTiming` reads them back. **So a cell held for three ticks exports as a sheet that plays it
for one, with nothing indicating the loss.** The full finding, with the file and line evidence, is
in **JB-4.02's Q2**; I am repeating it here because this is the row that would ship the broken file.

Decision 7 refuses a roll with a hold rather than exporting a wrong-timing sheet. That is the right
interim behaviour and it is also a button that says "no" to something the person just built, so it
is not a long-term answer. **What a ruling needs to cover:** whether `Clip` gains a `weights` field
(defaulted, omitted when empty or all 1, range 1..9999 to match `SequenceTiming`), and whether
`SpritePacker` writes it. That is an edit to a reviewed file, so it needs the Lead's word and a
re-review, and this row is downstream of it.
