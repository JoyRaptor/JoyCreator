# JB-0.08c — The rest of save/open: re-save keeps its metadata, destroy is clean, no wrong-paper frame, and Open… never eats your drawing

| | |
|---|---|
| **Tier** | T2 (+ **T3 device check**, §Tests → "Device check") |
| **Status** | 📝 Draft spec (board row is the orchestrator's; **do not edit `ROADMAP.md`**) |
| **Depends on** | JB-0.08b 🟧, JB-2.15 / R26 `SaveQueue` 🟧, JB-0.08a 🟧, JB-0.02d 🟧 |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/io/RecentFiles.kt`<br>NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocMerge.kt`<br>NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/io/RecentFilesTest.kt`<br>NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/DocMergeTest.kt`<br>**EDIT** `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` — **19 edit sites, all named and anchored in §Steps.** Nothing else. |
| **Estimated size** | ~90 lines of core + ~190 lines of tests + ~85 lines of app wiring |
| **Spec writer** | `openrouter/stealth/space-bunny-alpha` 2026-09-30 |

---

## Goal

Four things the owner does not have to notice, in order of how much they cost him when they are wrong.

1. **A drawing that is opened and saved again keeps its settings.** Today `JbCanvasView.readContents`
   builds a brand-new document and copies the paper colour and each layer's name, visible, opacity,
   blend and clip onto it — and nothing else. So `paper.textureScale`, `paper.includeInExport`,
   `board.name`, `board.clipToBoard`, the document's own `id`, the board's `id` and the file's
   `thumbnail.png` are all silently gone after one autosave, under a *newer* version number that
   says the file is current. (Layer ids are safe: `load` hands the file's own layer ids to the
   engine, so the snapshot writes those same ids back.)
2. **Leaving the screen releases everything it posted**, and a save that is in flight when the
   screen dies is *not* stranded: the queue is released, the file is written atomically, and nothing
   is left holding a dead `GLSurfaceView`.
3. **Opening a drawing does not flash one frame of the wrong paper.** Today a frame can be drawn
   with the new file's paper over the previous drawing's tiles.
4. **"Open…" cannot eat the drawing you had.** Before Open… replaces what is on the canvas, the
   current drawing is silently written to `recent/<timestamp>.joybrush` and the last five are kept.
   No dialog, no question, no toast on success.

---

## 🔴 The four things, and where each one actually lives

| # | Finding | Root cause, verified against the landed tree | Where the fix goes |
|---|---|---|---|
| **F6** | Re-save drops document metadata | `JbCanvasView.readContents` (`:1130-1194`) builds a **fresh** `DocOps.newDocument` (`:1173`) and copies only `paper.color` (`:1189`), `visible`/`opacity`/`blend`/`clip`/name (`:1176-1187`, from the engine's live stack) and the tiles. Everything else comes back at its default. `thumbnailPng = null` is hard-coded (`:1193`). | **core** (`DocMerge`) + the screen (carry the document, apply the merge at save time). **No view edit.** |
| **F5** | No `onDestroy` cleanup | The file has `onCreate/onResume/onPause/onActivityResult` only. `idleSave` (`:268`, 30 s, captures `this`), `pauseView` (`:271`), `thumbsNow` (`:865`), `iconCheck` (`:1121`) are never unhooked, and seven `ui.post` continuations have no liveness check. | The screen only, §Decisions 8–11. |
| **F7** | One wrong-paper frame on open | `JbCanvasView.load` (`:1031-1067`) queues `resetDocument` + `writeTile` on the GL thread (`:1055-1062`) and then sets `paperArgb` **on the UI thread** (`:1063`), whose setter calls `requestRender()`. The paper change therefore takes effect *immediately* while the tile writes are still queued, and the GL thread can draw a frame in that window. | **The view.** R30 item 2 — the patch is written out verbatim in §Step 3, and whether a T2 builder may apply it is Question 1. |
| **Open…** | Open… overwrites the drawing you had | `openFrom` (`:1414`) replaces the canvas; the next `IDLE` save writes `current.joybrush` and `JbArchive.save` (`:137-172`) moves the old one to `current.joybrush.bak`. One generation, then gone. | **core** (`RecentFiles`, the retention rule) + the screen. |

> **The review's `JbCanvasView` line numbers are stale.** `reviews/JB-0.08b__muse-spark.md` cites
> `readContents` at `:570-579` and `load` at `:472-484` — from before JB-2.04 added layers, masks
> and clipping to that file. All four findings are still true; only the numbers moved. **Every
> `JbCanvasView.kt` line number in this spec was re-read from the landed file today**, as were the
> `JoyBrushActivity.kt` ones (HEAD, 1613 lines).

### F6 and the JB-0.02 unknown-key finding are **TWO defects**, not one

This was the question to settle before writing anything, so it is settled here, with the code on
both sides.

| | JB-0.02's finding (and JB-0.02d) | F6 |
|---|---|---|
| **Where the information is lost** | The **codec**. `DocJson.decode` used to build a `JbDocument` with `ignoreUnknownKeys = true`, so a key the reader did not know was already gone by the time anything could look at it — and `DocJson.encode` then wrote a *newer* `DOC_VERSION` over content it had discarded. | The **view's snapshot path**. `readContents` never even looks at the opened document: it calls `DocOps.newDocument` and copies the paper colour and each layer's five stack fields. Every key is *known*; it is simply never read. |
| **The fix that landed** | **JB-0.02d / R31 is already in the tree**: `DocJson.refuseUnknownKeys` (`DocJson.kt:112-180`) walks the whole descriptor and **refuses** a same-version file carrying an unknown key, in words, at the parse. Board: 🟧 Built. | **This row.** Nothing exists. |
| **Is the version stamp safe today?** | Yes, *because of* JB-0.02d: a file this build could read has no key it would drop, so re-writing `DOC_VERSION` over it discards nothing. | Independently safe for the same reason — see `DocMerge`'s KDoc, which says exactly this and cites `DocJson.kt`. |

**They are different defects in different files, with different fixes, and the row owes exactly one of
them.** Two fixes are NOT written here for one bug: this spec writes the F6 fix (carry the document,
merge) and no `DocJson` change at all. The one line in `DocMerge`'s KDoc that mentions the version
stamp is a *reason the merge is safe*, not a second fix.

---

## Contract (verbatim)

Everything below is the landed code, pasted. If any of it differs when you open the file, **STOP** —
that is a changed contract, not a typo.

### `core/io/SaveQueue.kt` — what already exists (READ IT, DO NOT EDIT IT)

```kotlin
package cc.joycreator.joybrush.core.io

enum class SaveReason {
    /** The screen leaving, or 30 s of quiet. Nobody asked, so two of these in a row are one. */
    IDLE,

    /**
     * The person asked ("Save a copy…"). NEVER merged with anything, NEVER dropped, and it says how
     * it went in its own words.
     */
    EXPLICIT,
}

interface SaveTarget<D> {
    val strokeInProgress: Boolean
    fun start(reason: SaveReason, destination: D, finished: (problem: String?) -> Unit)
}

class SaveQueue<D>(private val target: SaveTarget<D>) {
    /** Requests that have not started yet. Not counting the one in flight. */
    val pending: Int get() = waiting.size
    /** True while a save has started and not finished. */
    val busy: Boolean get() = inFlight != null
    fun request(reason: SaveReason, destination: D): Int
    fun strokeFinished() = drain()
    fun drain()
}
```

Three facts you build on, all in `drain()` (`SaveQueue.kt:99-126`):

* **One at a time.** `while (inFlight == null && waiting.isNotEmpty() && !target.strokeInProgress)`.
* **`finished` is called at most once per request** — there is a per-entry `answered` guard at
  `:106-115`, so a watchdog and a late real answer cannot both release the next entry.
* **A `start` that throws clears `inFlight` and rethrows** (`:116-121`) — the queue is never left
  `busy` by an exception.

### `core/doc/DocModel.kt` — the four fields this row carries

```kotlin
const val DOC_VERSION = 3   // DocModel.kt:8 — read the current number, never assume it

@Serializable data class Paper(
    val color: String = "#FFFFFF",          // #RRGGBB
    val textureId: String? = null,          // a grain asset id, null = plain
    val textureScale: Float = 1f,
    val includeInExport: Boolean = false,   // the "Include paper" checkbox default
)

@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, in play order
    val grid: SpriteGrid? = null,           // SPRITE only
)

@Serializable data class Layer(
    val id: String,
    val name: String,
    val kind: LayerKind,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val animatedIn: String? = null,
    val cels: List<Cel>,
    val frameCel: Map<String, String> = emptyMap(),
    val mask: Cel? = null,
    val clip: Boolean = false,
)

@Serializable data class JbDocument(
    val format: String = DOC_FORMAT,
    val version: Int = DOC_VERSION,
    val id: String,
    val name: String,
    val paper: Paper = Paper(),
    val boards: List<Board>,
    val layers: List<Layer>,                // bottom -> top
    val activeLayerId: String? = null,
    val activeBoardId: String? = null,
)
```

`Layer.locked` is **never** read or written by the engine: `core/layers/LayerStack.kt`'s
`LayerState` has no `locked` field (verified — `grep locked joybrush/core/.../layers` returns
nothing). That is exactly why `locked` is lost, and why it must be *carried* rather than read.

### `androidkit/io/JbArchive.kt` — what a save is

```kotlin
data class JbContents(
    val doc: JbDocument,
    /** (layerId, celId, "tx_ty") to one tile of exactly [TILE_BYTES] bytes. */
    val tiles: Map<Triple<String, String, String>, ByteArray>,
    /** (layerId, celId) to the stroke records of that INK cel. */
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,
    val thumbnailPng: ByteArray? = null,
)

object JbArchive {
    /** `<file>.tmp` → fsync → `<file>` becomes `<file>.bak` → `<file>.tmp` becomes `<file>`. */
    fun save(file: File, contents: JbContents)
    fun write(out: OutputStream, contents: JbContents)
    fun read(input: InputStream): JbContents
    fun open(file: File): JbContents
}
```

**`save` only ever creates `<file>.bak` when `<file>` already exists** (`:147-154`). Every recent
name in this row is unique, so a recent file **never** has a `.bak`. That is the whole of the
ruling's interaction with the existing `.bak` generation — derived in Decision 7, not guessed.

### `JoyBrushActivity.kt` — the pieces this row edits, verbatim

```kotlin
// :90-92
private const val WORKING_DIR = "joybrush"
private const val WORKING_FILE = "current.joybrush"
// :99
private const val COPY_TEMP = "joybrush-copy.tmp"
// :102
private const val AUTOSAVE_AFTER_MS = 30_000L
// :116
private const val SNAPSHOT_TIMEOUT_MS = 15_000L
// :162-169 — ONE writer thread, shared by every instance of the screen. "never shut down by design"
private val fileIo = Executors.newSingleThreadExecutor()

// :239-246
private val ui = Handler(Looper.getMainLooper())
private var changes = 0
private sealed class SaveDest {
    object Working : SaveDest()
    class Copy(val uri: Uri) : SaveDest()
}

// :255-260
private val saves = SaveQueue(object : SaveTarget<SaveDest> {
    override val strokeInProgress: Boolean get() = canvas.strokeInProgress
    override fun start(reason: SaveReason, destination: SaveDest, finished: (String?) -> Unit) {
        beginSave(destination, finished)
    }
})

// :262-271
private var activeSave: ((String?) -> Unit)? = null
private var viewPausePending = false
private val idleSave = Runnable { if (changes > 0) saves.request(SaveReason.IDLE, SaveDest.Working) }
private val pauseView = Runnable { pauseViewNow() }

// :1273-1277
private fun workingFile(): File? {
    val dir = getExternalFilesDir(WORKING_DIR) ?: return null
    if (!dir.isDirectory && !dir.mkdirs()) return null
    return File(dir, WORKING_FILE)
}

// :1280-1284
private fun noteChange() {
    changes += 1
    ui.removeCallbacks(idleSave)
    ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)
}

// :1375-1382
private fun pauseViewIfDrained() {
    if (viewPausePending && saves.pending == 0) pauseViewNow()
}
```

`minSdk` is **24** (`joybrush-android/build.gradle.kts`), so `isDestroyed` is available — though
this row does not use it (Decision 9).

---

## Decisions already made

Numbered. Each has a one-line reason. No open choices.

1. **F6 is fixed by carrying the opened document on the screen and merging at save time — not by
   touching `JbCanvasView.kt`.** `DocMerge.carried(fresh, carried)` is a pure function in `core`; the
   screen holds the last `JbContents` it put on the canvas and applies the merge inside the snapshot
   callback. R30 item 2 says T2 rows do not edit that file, and this way they do not have to.
2. **"Fresh wins where the engine is the truth; carried wins where the engine has no opinion; nothing
   is invented."** The exact field list is in `DocMerge`'s KDoc and is not negotiable per field.
3. **`paper.textureId` is never carried.** A document with a paper texture is *refused at open*
   (`JbCanvasView.refusalFor`, `:1087-1089`), so a document that ever reaches this path has no
   texture; carrying the id would stamp a texture onto a file whose own screen would then refuse it.
4. **The screen's re-save writes `version = DOC_VERSION` and changes nothing about that.** Since
   JB-0.02d, `DocJson.decode` refuses a same-version file carrying a key this build does not know
   (`DocJson.kt:112-120`), so a document this build could read has no key it would drop.
5. **The thumbnail is carried verbatim, and nothing in this row renders a new one.** JB-0.08b's Q7
   left the write side open; this row closes the *strip* side only, because a stale thumbnail that
   is still there beats no thumbnail at all. Rendering one is a different row.
6. **"Current" means the LIVE canvas, not the last save and not the working file.** A fresh
   `canvas.snapshot` is taken at the moment Open… is called. The working file can be 30 s stale
   (`AUTOSAVE_AFTER_MS`) and the last save may have been a *copy* to a folder somewhere else
   entirely; neither is "the drawing you had".
7. **The recent copy is written through `JbArchive.save`, out of band — NOT through `SaveQueue`.**
   The queue takes its snapshot at *perform* time (`:102-105` + `beginSave`'s `canvas.snapshot`), by
   which time the load has replaced the canvas, so a queued recent save would keep the *new*
   drawing. Out of band it is ordered correctly because `GLSurfaceView.queueEvent` is FIFO: the
   snapshot is queued before `load` is called, so the readback sees the pre-open tiles. There is no
   concurrency risk either: both events run on the *same* GL thread, one after the other.
8. **The recent copy is written before the chosen file is even read**, so a refused open leaves a
   redundant copy in the ring. A refusal is exactly when a safety copy is worth having, the copy is
   a valid drawing either way, and the cost is at most one slot out of five. The alternative (a
   flag handshake so the write only fires once the load has succeeded) is a two-flag state machine
   in a hot file for a benefit worth nothing.
9. **`alive()` is `!dead` and nothing else. `isFinishing` is deliberately NOT part of it.** The
   screen's own Home button calls `finish()` (`JoyBrushActivity.kt:405`), so `isFinishing` is true
   during the exact pause that must still save. Using it would suppress the Home autosave, which is
   the regression this row exists to prevent. `dead` is set on the first line of `onDestroy`.
10. **`onDestroy` unhooks four named callbacks and NOTHING else.** In particular it must never call
    `ui.removeCallbacksAndMessages(null)`: that would also kill `beginSave`'s 15 s `watchdog`
    (`:1327-1329`), which is the only thing that releases the queue when the GL thread never answers
    — the **watchdog single-flight break**. Leaving it armed is what makes "a save in flight at
    destroy" safe rather than stranded.
11. **A save in flight at destroy is NOT cancelled and NOT rolled back.** `onDestroy` skips the
    *UI* side (toasts, `canvas.onPause`, `reference.setPicture`, `showContents`) and lets the *data*
    side finish: the snapshot is allowed to answer, the write runs on `fileIo`, and `finished` is
    called so the queue is never left busy. This is the **snapshot-fail stranding** and the
    **load-completion debt wipe** both answered by name: the first is why the watchdog stays armed,
    the second is why `onSnapshotFailed` always calls `activeSave?.invoke` even when dead.
12. **Opening a drawing by hand arms an autosave; restoring one at launch does not.** After Open…,
    the working file still holds the *previous* drawing, so the newly opened one is genuinely
    unsaved work. At launch the file on disk **is** the canvas (`restoreWorkingFile` only loads when
    `changes == 0`), so arming there would rewrite an identical file every launch and rotate its
    `.bak` for nothing. Today both paths do `changes = 0`, which is the **load-completion debt wipe**:
    an Open… followed by a stroke before the load finished loses the stroke's autosave, and an
    Open… followed by a force-stop loses the newly opened drawing entirely.
13. **No recent copy on the launch path.** The ruling is about Open… *replacing* a drawing. At launch
    nothing is replaced: an empty canvas is filled from the file, only when nothing has been drawn.
14. **The recent folder is `getExternalFilesDir("joybrush")/recent/`.** Same folder as the working
    file, so it inherits its lifetime (deleted on uninstall) and its START_HERE rule 3 warning.
15. **One timestamp shape: `yyyyMMdd-HHmmss-SSS`**, e.g. `20260930-141203-482.joybrush`. Fixed
    width, so lexicographic order *is* chronological order and the retention rule is a `sorted()`.
    Milliseconds are there so that **no collision rule is needed at all** — a collision would mean
    `JbArchive.save` rotating a `.bak` over a safety copy, which is the exact class of silent
    destruction this row exists to stop, and inventing a `-2`, `-3` suffix ladder is a branch that
    can run out.
16. **The ring is pruned after every write, and only recent drawings are candidates.** A
    `current.joybrush` or a `.tmp` that happens to sit in the folder is never a candidate; a
    `thumbnail` sibling of a pruned file is deleted with it.
17. **Silent on success, ONE toast on failure.** The ruling says no dialog, and a success toast on
    every Open… would be noise. R11 says never silently drop anything, and a safety copy that failed
    to be written *is* something dropped. So the failure is reported in the existing save-failure
    voice and the Open… still happens.
18. **This row does not shut down `fileIo`.** Its own KDoc (`:162-168`) says why, and an executor
    that is ever shut down turns a snapshot landing after `onDestroy` into a
    `RejectedExecutionException` on the UI thread. The leak is the process, and the process ends.
19. **F7's fix is a two-line move in `JbCanvasView.kt` and is the Lead's to apply** (R30 item 2).
    The patch is written out verbatim in §Step 3 so that nobody has to design it — only to say yes.

---

## Steps

> **Anchor convention, so the count can be checked rather than believed.** An **edit site** is *one
> contiguous run of changed lines* — which is exactly one `@@` hunk in `git diff -U0`, and exactly
> one row of the A-table in §Step 2. The "run today" column there is the inclusive line range that
> changes, counted in the file **as it stands today** (HEAD, 1613 lines). If a number is a line or
> two off because someone else moved code, that is fine and the range still identifies the site;
> **but the number of hunks must be 19**, and `git diff -U0 … | grep -c '^@@'` (§Definition of done)
> is how a reviewer checks that without reading the file.
>
> The two previous view specs got their count wrong in four places each and could not be checked.
> That is why this spec states a convention, states the run for every site, and gives a command
> that counts them.

### Step 1 — the two core files, and their tests, first

**1a. NEW** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/io/RecentFiles.kt`

```kotlin
package cc.joycreator.joybrush.core.io

/**
 * The recent-drawings ring (JB-0.08c, R26): "Open…" writes the drawing it is about to replace to
 * `recent/<timestamp>.joybrush` and the last [KEEP] are kept. The rule is here, in pure Kotlin, so
 * it can be tested; the file I/O is the screen's.
 */
object RecentFiles {

    /** How many recent drawings are kept. The Lead's number (R26). The ONLY copy of it. */
    const val KEEP = 5

    /**
     * One shape only: `yyyyMMdd-HHmmss-SSS.joybrush`, e.g. `20260930-141203-482.joybrush`.
     * Fixed width, so sorting by name sorts by time. A shape with an optional `-2` suffix would
     * sort wrong (`-482` < `-2` is not the order they were made in) and a `.tmp`/`.bak` sibling is
     * not a drawing.
     */
    private val NAME = Regex("^\\d{8}-\\d{6}-\\d{3}\\.joybrush$")

    /** True when [name] is a recent drawing this object owns. */
    fun isRecent(name: String): Boolean = NAME.matches(name)

    /**
     * The names to DELETE so that at most [keep] recent drawings are left: the oldest first, and
     * never one that is not a recent drawing. Pure — it opens nothing and decides nothing about
     * disk, which is what makes it testable.
     *
     * Order of [names] does not matter; the result is always oldest-first.
     */
    fun prune(names: List<String>, keep: Int = KEEP): List<String> {
        require(keep >= 0) { "keep must not be negative, it is $keep" }
        val mine = names.filter { isRecent(it) }.sorted()
        return if (mine.size <= keep) emptyList() else mine.subList(0, mine.size - keep).toList()
    }
}
```

**1b. NEW** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocMerge.kt`

```kotlin
package cc.joycreator.joybrush.core.doc

/**
 * Carries the parts of a document the GPU engine never owns, from the document that was **opened**
 * to the document a **snapshot** just built (JB-0.08c; the JB-0.08b review's finding 6).
 *
 * The engine's own snapshot path (`JbCanvasView.readContents`) calls `DocOps.newDocument` and
 * copies four fields onto it — paper colour, and each layer's visible/opacity/blend/clip/name. That
 * is CORRECT: those are the engine's truth. Everything else came back at its default, so a drawing
 * opened and saved again silently lost `paper.textureScale`, `paper.includeInExport`,
 * `board.name`, `board.clipToBoard`, every id and the file's own `thumbnail.png` — and was stamped
 * with the current `DOC_VERSION`, which reads as "this file is current".
 *
 * The rule, in one line: **fresh wins where the engine is the truth, carried wins where the engine
 * has no opinion, and nothing is invented.**
 */
object DocMerge {

    /**
     * [fresh] with every field the engine does not own taken from [carried].
     *
     * **Carried** (the engine has no opinion, so only the file knows):
     * `id` · `boards[0].id` · `boards[0].name` · `boards[0].clipToBoard` ·
     * `paper.textureScale` · `paper.includeInExport` · `layers[i].locked` (matched by layer id) ·
     * `activeBoardId` (only when it is the carried board's id).
     *
     * **Fresh** (the engine IS the truth): `name` · `paper.color` · `boards[i].rect` ·
     * `activeLayerId` · every layer's `visible`/`opacity`/`blend`/`clip`/`cels`/`mask`/`animatedIn`/
     * `frameCel` · every tile.
     *
     * **Never carried, on purpose:**
     *  - `paper.textureId`. A document with a paper texture is REFUSED at open
     *    (`JbCanvasView.refusalFor`), so one that reaches here has no texture; copying the id
     *    through would stamp a texture onto a file this screen would then refuse to open.
     *  - `version` and `format`, which stay as [fresh] has them. This is safe and it is safe
     *    *because of* JB-0.02d: `DocJson.decode` now REFUSES a same-version file carrying a key
     *    this build does not know (`DocJson.kt:112-120`), so a document this build could read has
     *    no key that re-encoding would drop. That is the JB-0.02 unknown-key finding, and it is a
     *    DIFFERENT defect in a different file — this merge is not its fix and does not repeat it.
     *
     * [carried] null, or with no board, returns [fresh] unchanged. There is no third source.
     */
    fun carried(fresh: JbDocument, carried: JbDocument?): JbDocument {
        if (carried == null) return fresh
        val from = carried.boards.firstOrNull() ?: return fresh
        if (fresh.boards.isEmpty()) return fresh
        val locked = carried.layers.associate { it.id to it.locked }
        val activeBoard = carried.activeBoardId?.takeIf { it == from.id }
        return fresh.copy(
            id = carried.id,
            paper = fresh.paper.copy(
                textureScale = carried.paper.textureScale,
                includeInExport = carried.paper.includeInExport,
            ),
            boards = fresh.boards.mapIndexed { i, b ->
                if (i == 0) b.copy(id = from.id, name = from.name, clipToBoard = from.clipToBoard) else b
            },
            layers = fresh.layers.map { it.copy(locked = locked[it.id] ?: false) },
            activeBoardId = activeBoard ?: fresh.activeBoardId,
        )
    }
}
```

**1c.** Write `RecentFilesTest.kt` and `DocMergeTest.kt` from §Tests **before** touching the Activity.
Both go in **`commonTest`**, not `jvmTest`: neither opens a file, and `commonTest` is the source set
`jvmTest` actually runs today (only the JVM target is enabled). The R44 objection — "source-level
tests in `commonTest` cannot open files" — does not apply to a suite that opens nothing.

**1d.** Run the core suite (§Tests) and paste the numbers. **This is the only thing in this row that
may be proved by running gradle.**

### Step 2 — the app wiring: 19 edit sites in `JoyBrushActivity.kt`

Nothing outside this table. **Do not reorder, do not "tidy" a neighbouring line, do not fix
anything you notice** — the file is R30's lock.

| # | Run today | Anchor | What |
|---|---|---|---|
| **A1** | insert at `:78` | after the `…core.io.SaveTarget` import (`:77`) | add `import cc.joycreator.joybrush.core.doc.DocMerge` and `import cc.joycreator.joybrush.core.io.RecentFiles`. **No other import is needed**: `File`, `SimpleDateFormat`, `Date`, `Locale` are already imported (`:78-81`). |
| **A2** | insert at `:117` | after `SNAPSHOT_TIMEOUT_MS` (`:116`) | two new top-level constants:<br>`private const val RECENT_DIR = "recent"`<br>`private const val RECENT_STAMP = "yyyyMMdd-HHmmss-SSS"` — each with a one-line KDoc saying it is the R26 ruling. **The count 5 is NOT here**: it is `RecentFiles.KEEP` and this file must not restate it. |
| **A3** | insert at `:266` | after `private var viewPausePending = false` (`:265`) | two new fields:<br>`private var dead = false` — *true from the first line of `onDestroy`*<br>`@Volatile private var carried: JbContents? = null` — *the last document this screen put on the canvas; null for a drawing that was never opened* |
| **A4** | `:268` | `private val idleSave = Runnable { … }` | `if (changes > 0 && !dead) saves.request(SaveReason.IDLE, SaveDest.Working)` |
| **A5** | `:350` | `pauseViewNow()` inside the `canvas.onSnapshotFailed` lambda | `if (!dead) pauseViewNow()` — and the `activeSave?.invoke(…)` line below it stays **unconditional**, with a one-line comment saying why (a request nobody answers never happens). The order of the two lines is unchanged. |
| **A6** | `:1058` | the `ui.post {` inside `loadReference` | add `if (dead) return@post` as the first line of the posted block. Nothing else in that function changes — the `quietIfGone` toast and `removeReference()` come under the same guard. |
| **A7** | `:1306-1316` | the `if (problem != null) { … } else if (dest is SaveDest.Copy) {` arm inside `beginSave`'s `answer` | open that arm with `if (!dead) {` and close it before the `} else if (dest is SaveDest.Copy) {`. It covers the `toast(…)` and the `if (dest is SaveDest.Working) { ui.removeCallbacks(idleSave); ui.postDelayed(…) }`. `ui.removeCallbacks(watchdog)`, `activeSave = null`, `changes = 0`, `finished(problem)` and `pauseViewIfDrained()` are **left exactly as they are** — they are the data side, and they are what keeps the queue from stranding. |
| **A8** | `:1319` | `toast("Copy saved")`, two comment lines below A7's run — and therefore a **separate** site | `if (!dead) toast("Copy saved")`. The two comment lines on `:1317-1318` are **not** changed, and that is exactly what makes this a second run rather than part of A7. |
| **A9** | `:1336-1351` | `canvas.snapshot { contents ->` inside `beginSave` | inside the callback, after the existing `pauseViewIfDrained()` line, add `val out = carriedInto(contents)` — and pass **`out`** to `JbArchive.save(file!!, …)` and to `writeCopy(dest.uri, …)`. The `fileIo.execute { … }` block is otherwise unchanged. **Read the carried document here, on the UI thread**, not in `beginSave`'s body: `load` posts its completion from the same GL thread and its block was queued first, so `carried` is always already the document these tiles came from. |
| **A10** | insert at `:1374` | after the closing `}` of `writeCopy` (`:1373`) | one new private function, verbatim:<br>`private fun carriedInto(contents: JbContents): JbContents {`<br>`    val from = carried ?: return contents`<br>`    return contents.copy(doc = DocMerge.carried(contents.doc, from.doc), thumbnailPng = from.thumbnailPng)`<br>`}`<br>plus a KDoc saying: the fields the engine does not own come from the last document this screen opened, the file's own thumbnail is put back, and a null in gives a null out. |
| **A11** | `:1381` | `if (viewPausePending && saves.pending == 0) pauseViewNow()` | `if (viewPausePending && saves.pending == 0 && !dead) pauseViewNow()`. One line. |
| **A12** | `:1395` | `ui.post { toast("Your last drawing could not be opened: …") }` | `ui.post { if (!dead) toast(…) }` |
| **A13** | `:1400-1403` | the `ui.post { … showContents(contents) }` block in `restoreWorkingFile` | `if (!dead && changes == 0) showContents(contents, openedByPerson = false)` |
| **A14** | `:1413-1415` | `openFrom`'s KDoc and the `fileIo.execute {` beneath it | the KDoc gains a sentence saying that the recent copy's snapshot is queued ahead of `load`'s, so the bytes are the drawing being replaced, and that the write can never fail the Open…; and **one line is added** above the `fileIo.execute {`: `keepCurrentDrawing()`. |
| **A15** | `:1421` | `ui.post { toast("That drawing could not be opened: …") }` | `ui.post { if (!dead) toast(…) }` |
| **A16** | `:1426` | `ui.post { showContents(contents) }` | `ui.post { if (!dead) showContents(contents, openedByPerson = true) }` |
| **A17** | `:1431-1441` | the KDoc, the signature, `try {`, and the whole `canvas.load(contents) { … }` callback in `showContents` | **one run, three edits.** (a) the KDoc gains `[openedByPerson]`, and says the flag is the only difference and why; the signature becomes `private fun showContents(contents: JbContents, openedByPerson: Boolean)`; (b) `if (dead) return` becomes the first statement; (c) the callback body becomes `carried = contents` (F6 — from this moment the canvas **is** this document) followed by `if (openedByPerson) noteChange() else { changes = 0; ui.removeCallbacks(idleSave) }`. **The old `changes = 0` + `ui.removeCallbacks(idleSave)` pair is replaced, not kept alongside.** Lines `:1442-1445` — the `catch` arm and the closing braces — do **not** change, which is what keeps this one run. |
| **A18** | insert at `:1446` | after the closing `}` of `showContents` (`:1445`) | the four new recent-drawing functions, verbatim in §Code below. |
| **A19** | insert at `:384` | after the closing `}` of `onPause` (`:383`), before the `// ── the chrome (JB-2.01) ──` banner on `:385` | `override fun onDestroy()` **and** `private fun alive(): Boolean = !dead`, verbatim in §Code below. |

**Total: 19 edit sites = 19 contiguous changed runs = 19 `@@` hunks.** If you have edited the file 19
times, stop and re-read this table before the next hunk — and if the `@@` count comes out at 18 or
20, two of your sites have merged or split, and that is the thing to look at.

The table is in file order except **A19**, which is at `:384` and is listed last because it is the
lifecycle hook. Two more boundaries worth knowing, because they are what keep the runs separate: the
KDoc on `:267` above `idleSave` is **not** changed by A4, and the two comment lines on `:1317-1318`
are **not** changed by A7.

### Step 3 — F7: the paper frame (a two-line move in a Lead-only file)

**Read R30 item 2 again before touching anything.** `JbCanvasView.kt` is Lead-only and T2 rows do not
edit it. This spec therefore does the next-best thing: it writes the patch out so that no one has
to design it, and leaves the authority question as Question 1.

**`JbCanvasView.kt`, `load()` — the whole change is one line moved.**

From (`:1055-1066` as landed):

```kotlin
        onGl {
            // resetDocument() empties EVERY layer; the file's stack is put back whole, then its pixels.
            engine.resetDocument()
            engine.setStack(stack)
            for (item in wanted) engine.writeTile(item.first, item.second, item.third)
            reportHistory()
            post { onDone() }
        }
        paperArgb = paper                       // ← :1063  DELETE THIS LINE
        pageW = board.w
        pageH = board.h
        documentName = if (name.isBlank()) "Joy Brush" else name
```

To:

```kotlin
        onGl {
            // F7: the paper changes INSIDE the same GL-thread block as the pixels, immediately
            // before the first write, so no frame can be drawn with the file's paper over the
            // previous drawing's tiles (or over an empty page). `paperArgb`'s setter calls
            // `requestRender`, and asking for a render from the GL thread is fine.
            paperArgb = paper
            engine.resetDocument()
            engine.setStack(stack)
            for (item in wanted) engine.writeTile(item.first, item.second, item.third)
            reportHistory()
            post { onDone() }
        }
        pageW = board.w
        pageH = board.h
        documentName = if (name.isBlank()) "Joy Brush" else name
```

**Why that and not something else.** `paperArgb` is `@Volatile` and its setter fires
`requestRender()` (`:113-114`), and `engine.draw(…, paperArgb)` reads it on the GL thread
(`:488`). Setting it on the UI thread while the `onGl` block is still queued means the GL thread can
pick up a render request and draw **before** the block runs — new paper, old tiles. Moving the
assignment inside the block puts the paper change and `resetDocument()` in the same GL-thread region
with no `onDrawFrame` in between, so the first frame after the call is already correct. `pageW`,
`pageH` and `documentName` stay on the UI thread: none of them is read by `engine.draw`, and
`readContents` reads `pageW`/`pageH` only when a snapshot is taken, which cannot be until the load's
GL block has run.

**Nothing else in that file changes.** If your instructions forbid this file, skip Step 3 entirely,
say so in your report, and F7 stays open — the rest of the row does not depend on it.

---

## Code (verbatim — the four recent-drawing functions and `onDestroy`)

Paste these exactly. They are the whole of A18 and A19.

```kotlin
    /**
     * JB-0.08c F5. The screen is closing.
     *
     * Four callbacks are unhooked, by name, and **nothing else** — in particular never
     * `ui.removeCallbacksAndMessages(null)`, which would also kill [beginSave]'s 15 s watchdog and
     * strand every save queued behind it. The watchdog staying armed is exactly what makes a save
     * that is in flight here safe: the queue is released in words and the file is written, or the
     * watchdog releases it 15 s later. A save in flight is never cancelled here.
     *
     * What is skipped after this is the **UI** side only — toasts, `canvas.onPause`,
     * `reference.setPicture`, `showContents` — all of which check [alive]. The data side is left to
     * finish on its own.
     */
    override fun onDestroy() {
        dead = true
        ui.removeCallbacks(idleSave)
        ui.removeCallbacks(pauseView)
        ui.removeCallbacks(thumbsNow)
        ui.removeCallbacks(iconCheck)
        super.onDestroy()
    }

    /**
     * False once [onDestroy] has run. Every continuation checks it before touching a view.
     *
     * Deliberately NOT `isFinishing`: this screen's own Home button calls `finish()` (`:405`), so
     * `isFinishing` is true during the very pause that must still save, and including it would
     * suppress the Home autosave.
     */
    private fun alive(): Boolean = !dead
```

```kotlin
    /**
     * The drawing on the canvas right now, written to `recent/<timestamp>.joybrush` and the ring
     * trimmed to the last five. Silent, no dialog, and it can never fail the Open… that follows.
     *
     * **The LIVE canvas, not the last save and not the working file**: the working file can be 30 s
     * stale and the last save may have been a copy to a folder somewhere else.
     *
     * **Why it is not a `SaveQueue` request:** the queue takes its snapshot at *perform* time, by
     * which time the load has replaced the canvas, so a queued recent save would keep the NEW
     * drawing. Out of band, the ordering is `GLSurfaceView.queueEvent`'s own FIFO — this snapshot
     * is queued before `load` is called — so the readback sees the pre-open tiles. Two snapshots
     * racing is not a hazard either: both run on the one GL thread, in order.
     *
     * Runs on the writer thread once the readback lands; the ring is trimmed on the same thread.
     */
    private fun keepCurrentDrawing() {
        if (dead) return
        canvas.snapshot { contents ->
            if (dead) return@snapshot
            val out = carriedInto(contents)
            fileIo.execute {
                val problem = try {
                    val dir = recentDir() ?: throw JbArchiveException("this device has nowhere to keep a recent drawing")
                    val file = recentFileIn(dir)
                    JbArchive.save(file, out)
                    pruneRecent(dir)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                // Silent on success. One toast on failure, because a safety copy that was not
                // written IS something dropped, and the Open… still happens either way.
                if (problem != null) runOnUiThread { if (!dead) toast("Your drawing could not be kept: $problem") }
            }
        }
    }

    /** `<external files>/joybrush/recent/`, created if need be. Null when the device gives us nothing. */
    private fun recentDir(): File? {
        val parent = workingFile()?.parentFile ?: return null
        val dir = File(parent, RECENT_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    /**
     * A free name in [dir]. The stamp is millisecond-resolution and fixed width, so a name already
     * taken can only mean two opens inside one millisecond — which no file picker allows — and this
     * reuses the name rather than growing a suffix ladder. `JbArchive.save` would rotate a `.bak`
     * over a safety copy, which is the one thing a safety copy must never do.
     */
    private fun recentFileIn(dir: File): File {
        val stamp = SimpleDateFormat(RECENT_STAMP, Locale.US).format(Date(System.currentTimeMillis()))
        return File(dir, "$stamp.joybrush")
    }

    /** The names [RecentFiles.prune] says go, and their `.tmp`/`.bak` siblings with them. Writer thread. */
    private fun pruneRecent(dir: File) {
        val names = dir.list() ?: return
        for (name in RecentFiles.prune(names)) {
            File(dir, name).delete()
            File(dir, "$name.tmp").delete()
            File(dir, "$name.bak").delete()
        }
    }
```

**`thumbnailPng`** is carried by `carriedInto`, so the recent file keeps the opened drawing's
thumbnail, and so does every autosave of it. Nothing in this row renders a new one (Decision 5).

---

## Tests

16 new JVM tests, in two new files. **Write them first.** Neither touches disk, so `commonTest` is
right for both (see 1c).

### `RecentFilesTest` — 7 tests, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/io/RecentFilesTest.kt`

All the names below are **written out literally**, never built by a helper, so a reviewer can read
the expected answer straight off the test. They all use the one legal shape
`yyyyMMdd-HHmmss-SSS.joybrush`; test 7 uses names of the wrong shape deliberately.

| # | Test | Input → expected |
|---|---|---|
| 1 | `fiveDrawingsAreAllKept` | `prune` of exactly five recent names → `emptyList()` |
| 2 | `aSixthDrawingPushesTheOldestOut` | six names, ascending, `keep = 5` → exactly the first (oldest) name, list of size 1 |
| 3 | `theOldestGoWhateverOrderTheyArriveIn` | twelve names in **reverse** order, `keep = 5` → list of size 7, equal to the seven lexicographically smallest, in ascending order |
| 4 | `keepZeroPrunesEveryRecentDrawing` | three recent names, `keep = 0` → all three, ascending |
| 5 | `keepLargerThanTheFolderPrunesNothing` | three recent names, `keep = 10` → `emptyList()` |
| 6 | `onlyRecentDrawingsAreCandidates` | six recent names **plus** `current.joybrush`, `notes.txt`, `20260930-141203-482.joybrush.tmp`, `20260930-141203-482.joybrush.bak`, `joybrush-copy.tmp`, and a subfolder entry `notes` → the result is the single oldest **recent** name and nothing else, list of size 1 |
| 7 | `isRecentAcceptsExactlyOneShape` | `isRecent("20260930-141203-482.joybrush")` is `true`; **the six wrong-shaped names from test 6** — `current.joybrush`, `notes.txt`, `20260930-141203-482.joybrush.tmp`, `20260930-141203-482.joybrush.bak`, `joybrush-copy.tmp`, `notes` — plus `20260930-141203.joybrush` (no milliseconds) and `20260930-141203.joybrush.tmp` are all `false` |

**Non-vacuity (do both, and say so in the report):**
* change `sorted()` to `sortedDescending()` → test **3** goes red (test 2 still passes — that is
  exactly why test 3 exists).
* drop the `isRecent` filter → tests **6** and **7** go red.

### `DocMergeTest` — 9 tests, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/DocMergeTest.kt`

One shared pair of documents, written out literally, so every expected value is derivable by
reading the test:

```kotlin
private val carried = JbDocument(
    id = "doc-7", name = "Carried name",
    paper = Paper(color = "#FF0000", textureId = "grain-a", textureScale = 3.5f, includeInExport = true),
    boards = listOf(Board(id = "board-x", name = "Sketch", kind = BoardKind.CANVAS,
        rect = RectPx(0, 0, 10, 20), clipToBoard = true)),
    layers = listOf(Layer(id = "l-1", name = "Carried layer", kind = LayerKind.PAINT, locked = true,
        cels = listOf(Cel("cel-1")))),
    activeLayerId = "l-1", activeBoardId = "board-x",
)

private val fresh = JbDocument(
    id = "joy-brush", name = "Fresh name",
    paper = Paper(color = "#112233", textureScale = 1f, includeInExport = false),
    boards = listOf(Board(id = "board-1", name = "Board 1", kind = BoardKind.CANVAS,
        rect = RectPx(0, 0, 100, 200))),
    layers = listOf(
        Layer(id = "l-1", name = "Live layer", kind = LayerKind.PAINT, opacity = 0.5f, cels = listOf(Cel("cel-1"))),
        Layer(id = "l-2", name = "Second", kind = LayerKind.PAINT, cels = listOf(Cel("cel-2"))),
    ),
    activeLayerId = "l-2", activeBoardId = "board-1",
)
```

| # | Test | Input → expected |
|---|---|---|
| 1 | `carryingNothingReturnsTheFreshDocument` | `carried(fresh, null)` is **the same object** (`assertSame`) — the null path, and the one that would catch a merge that ignores its argument |
| 2 | `theFieldsTheEngineOwnsComeFromFresh` | `name == "Fresh name"` · `paper.color == "#112233"` · `boards[0].rect == RectPx(0,0,100,200)` · `activeLayerId == "l-2"` · `layers[0].opacity == 0.5f` · `layers[0].name == "Live layer"` · `boards[0].kind == BoardKind.CANVAS` — and **`version`/`format` are still fresh's** (`version == DOC_VERSION`). **Not** `id`: `id` is a *carried* field, and test 7 says so. |
| 3 | `paperScaleAndIncludeInExportAreCarried` | `paper.textureScale == 3.5f` and `paper.includeInExport == true`, while `paper.color` is still `"#112233"` |
| 4 | `boardNameIdAndClipAreCarriedAndItsRectIsNot` | `boards[0].id == "board-x"` · `name == "Sketch"` · `clipToBoard == true` · `rect == RectPx(0,0,100,200)` (fresh) |
| 5 | `layerLockedIsCarriedByLayerId` | `layers[0].locked == true` (both documents call it `l-1`) and `layers[1].locked == false` (fresh's `l-2` is not in the carried document) |
| 6 | `paperTextureIdIsNeverCarried` | `paper.textureId == null`, even though the carried document says `"grain-a"` |
| 7 | `theDocumentIdAndTheActiveBoardAreCarried` | `id == "doc-7"` and `activeBoardId == "board-x"` — and it is the **board id that moved**, so the two are consistent |
| 8 | `theResultIsAValidDocument` | `DocOps.validate(carried(fresh, carried))` is **empty**. This is the test that says the merge cannot manufacture an unsaveable file. |
| 9 | `theRoundTripIsIdempotent` | `carried(carried(fresh, carried), carried) == carried(fresh, carried)` — merging twice changes nothing, so a screen that merges over a merged document is not walking a slope. |

**Non-vacuity (do both, and say so in the report):**
* replace `locked = locked[it.id] ?: false` with `locked = false` → test **5** goes red.
* drop the `boards = …` line → tests **4**, **7** and **8** go red.

**Command:** `./gradlew -p joybrush :core:jvmTest` from inside your own worktree (ROADMAP §2 rule 2,
R43: `$TEMP/jb-0.08c`, `cd` in before the wrapper). "Passing" = BUILD SUCCESSFUL and **0** failures
in `joybrush/core/build/test-results/jvmTest/`, with the 16 new tests named in the XML. Paste the
counts *and* the new count, and say what the suite total was before you started.

> If your instructions forbid running gradle at all, **stop and ask** — the core half cannot be
> proved any other way, and this row's whole point is that a save path is the last thing you want
> verified by reading.

### The app half: a definition-of-done checklist, not a test

`:core:jvmTest` cannot see `JoyBrushActivity.kt` (it is in another module and a test that greps a
file in another module is a build script wearing a test — JB-2.15's test 12 says so at length).
These are mechanical and mechanical is the point. Paste all of them.

> **Count code, not prose.** The words `saveOwed` and `onDestroy` both already occur in this file —
> at `:253` and `:166`, **inside KDoc**. A bare `grep -c saveOwed` returns **1 today** and would
> read as a failure. Every grep below is anchored to a declaration, which is why it is written the
> way it is. (Counts in brackets are what each returns on HEAD today.)

- [ ] `grep -cE 'var saveOwed' joybrush-android/.../JoyBrushActivity.kt` → **0** (today: 0)
- [ ] `grep -cE 'compareAndSet' joybrush-android/.../JoyBrushActivity.kt` → **0** (today: 0)
- [ ] `grep -cE 'class SaveQueue|SaveQueue<' joybrush-android/.../JoyBrushActivity.kt` → **> 0** (today: 2)
- [ ] `grep -cE 'removeCallbacksAndMessages' joybrush-android/.../JoyBrushActivity.kt` → **0** (today: 0) ← the watchdog single-flight break, Decision 10
- [ ] `grep -cE '^\s*override fun onDestroy' joybrush-android/.../JoyBrushActivity.kt` → **1** (today: 0) — the *declaration*, not the KDoc mention on `:166`
- [ ] `grep -cE '^\s*private fun alive\(\): Boolean' joybrush-android/.../JoyBrushActivity.kt` → **1** (today: 0)
- [ ] `grep -nE 'RecentFiles|DocMerge' joybrush-android/.../JoyBrushActivity.kt` → **exactly 4** lines (today: 0): the two `import` lines, `DocMerge.carried(…)` in `carriedInto`, and `RecentFiles.prune(names)` in `pruneRecent`
- [ ] `git diff -U0 -- joybrush-android/.../JoyBrushActivity.kt | grep -c '^@@'` → **19**. That is the mechanical form of "19 edit sites" under this spec's convention (one contiguous run of changed lines = one `@@`), so a reviewer can count without reading. `git diff --stat` shows per *file*, which is not what is being claimed.
- [ ] `git status --short` lists only the four new core files, `JoyBrushActivity.kt`, and this spec — **and nothing under `joybrush/androidkit/` or anywhere else under `joybrush-android/`.**

**And the watcher, which is the only thing that can prove an app file compiles.** The owner's PC runs
a watcher whose log is `C:\+Projects\Screenrecorder\FadCam\build.log`. Do not run gradle on the app
build yourself. What to look at, exactly:

1. the last `BUILD SUCCESSFUL` **after** your commit;
2. inside it, `:joybrush-android:compileDebugKotlin` reading **`EXECUTED`**, not `UP-TO-DATE` and
   not `NO-SOURCE` — if it is `UP-TO-DATE` the watcher has not seen your change yet, so wait and
   look again;
3. zero lines matching `^e: ` anywhere in that run — Kotlin's error prefix, and the one thing a
   BUILD SUCCESSFUL cannot hide if you do not look for it;
4. `:joybrush:androidkit:compileKotlin` also `EXECUTED` (it is in the same watcher run and a
   `JbCanvasView` edit, if the Lead applied Step 3, shows up here).

### Device check (T3 — the owner, on the Note 9; no adb anywhere in this harness)

Name it, and do not mark the row done without it.

1. **Open… keeps what you had.** Draw a recognisable thing, Open… a *different* `.joybrush`, draw a
   recognisable other thing, press Home, force-stop, reopen → **the second drawing is there.** Then
   pull `<external files>/joybrush/recent/` off the device onto a PC. The `applicationId` is
   **flavour-dependent** (`app/build.gradle.kts:40` is `com.fadcam`, and `:92`, `:116`, `:131`,
   `:148`, `:153`, `:158` add `.beta` / `.pro` / `.proplus` / `.notes` / `.calc` / `.weather`), so
   the path is `…/Android/data/<the id of the build on the phone>/files/joybrush/recent/` — look at
   the installed package, do not guess. It must hold a `.joybrush` that opens in a zip viewer and
   shows **the first drawing**. Open six different files in one sitting and the folder holds
   **five**, and they are the five most recent.
2. **F6's own claim, the one that is hard to see.** Open a `.joybrush` whose `document.json` you
   have edited by hand to carry `"textureScale": 3.5`, `"includeInExport": true` and
   `"clipToBoard": true`; draw one stroke; wait 30 s; open the file again — **the three values are
   still there.** Before this row they are all `1`, `false`, `false`.
3. **Home still saves.** Draw, press Home, force-stop, reopen → the drawing is back. This is the
   one that catches a bad `alive()` (Decision 9: `isFinishing` is true on this path).
4. **R26's device check, unchanged and still owed** — draw → Home → force-stop → reopen; pen down →
   "Save a copy…" → the copy has the stroke; start a stroke → Undo mid-stroke → lift → Home →
   force-stop.
5. **F7, only if the Lead applied Step 3.** Open… a drawing whose paper colour differs from the
   current one and watch the transition at arm's length. One frame of the wrong paper is one frame;
   you are looking for it, and if you cannot see it, say so.

---

## Do not

- **Do not edit `JbCanvasView.kt` or `GlPaintEngine.kt`** — R30 item 2, the Lead's. Step 3 is written
  out so the Lead can apply it in thirty seconds; a builder applying it is a rules violation, and
  saying "the spec told me to" will not help either of you. If your instructions say you may, this
  line says you may not; ask the orchestrator, do not decide.
- **Do not edit `ROADMAP.md`.** The orchestrator alone edits the board. Put the row status in your
  report.
- **Do not touch `SaveQueue.kt`, `SaveQueueTest.kt`, `JbArchive.kt`, `DocOps.kt`, `DocJson.kt` or
  `DocModel.kt`.** In particular: **no `DocJson` change.** The unknown-key defect is JB-0.02d's and
  it has already landed; writing a second fix for it is how one bug becomes two patches and a
  regression nobody can bisect. If you think `DocMerge` is wrong about the version stamp, that is
  Question 4, not an edit.
- **Do not add a `SaveDest.Recent`.** The recent copy is deliberately out of band (Decision 7);
  putting it in the queue keeps the *new* drawing instead of the old one.
- **Do not shut down `fileIo`.** Its KDoc at `:162-168` says why, and it is not yours to overrule.
- **Do not use `ui.removeCallbacksAndMessages(...)` anywhere in this row.** See Decision 10 — it is
  the watchdog single-flight break and it strands the queue for ever.
- **Do not add `isFinishing` to `alive()`.** It suppresses the Home autosave (Decision 9).
- **Do not cancel, roll back or "flush" a save in `onDestroy`.** Let it finish. A save that is
  reported as done and was not is the single worst outcome this codebase can produce, and a save
  that is *not* reported at all on a screen nobody is looking at is fine.
- **Do not prune before writing.** Prune after, so the ring is `KEEP - 1` before the new one lands.
- **Do not put `java.io`, `android.*` or a `Uri` in `RecentFiles` or `DocMerge`.** They live in
  `core/commonMain`, which has no `android.jar` and no `java.io` in common code — the retention rule
  takes **names**, the screen does the I/O.
- **Do not add a dialog, a confirmation, or a success toast to the recent write.** The ruling is
  "silently, no dialog".
- **Do not restate the 5 in `JoyBrushActivity.kt`.** `RecentFiles.KEEP` is the only copy; a second
  literal is the drift R23 exists to forbid, and `JB-3.06c` is the row that learned that lesson.
- **Do not "improve" a neighbouring line.** 19 sites, 19 sites.

## Definition of done

- [ ] `RecentFilesTest` 7/7 and `DocMergeTest` 9/9, and `:core:jvmTest` BUILD SUCCESSFUL with **0**
      failures — output pasted, with the suite total before and after.
- [ ] Both mutations run and reported, per test file (two each).
- [ ] All nine greps pasted, `git diff -U0 … | grep -c '^@@'` = **19**, and `git status --short` showing
      only the owner-area files.
- [ ] Watcher `build.log`: last `BUILD SUCCESSFUL`, `:joybrush-android:compileDebugKotlin` `EXECUTED`,
      zero `^e: ` lines. Pasted.
- [ ] Committed as `JB-0.08c: save/open fixes` and pushed. **ROADMAP.md untouched** — the status
      goes in the report and the orchestrator writes the board.
- [ ] The five device checks above stated in the report, with what actually happened. Items 1 and 3
      are not optional: they are the two this row was written for.

## Stop rule

Inherited from `specs/README.md` §3 item 7 and made stricter, because this row is about work loss.

**STOP, write it in *Questions* under `for the cross-reviewer`, set the row ⛔ Blocked, commit, push,
and take other work** if any of these is true:

* a line anchor in §Step 2 is more than three lines off, or the code at an anchor is not what this
  spec quotes;
* the `@@` count comes out at anything but **19** — that is a merge or a split in §Step 2, and it is
  this spec's bug, not yours; say which two sites and stop;
* `:core:jvmTest` is not 0-failure on a tree you did not touch (someone else is mid-flight — say
  so, do not fix it);
* applying the merge to a real opened document produces a `DocOps.validate` complaint;
* you cannot make a save in flight at destroy finish without removing the watchdog;
* you find yourself wanting to touch `JbCanvasView.kt` for anything other than pasting Step 3.

**Never guess a retention number, a file name, a timestamp format, a count, or a signature.** They
are all above, derived or quoted. A wrong one is a wrong drawing.

---

## Questions

> **What actually stops a builder: nothing.** All seven questions below are decisions the *Lead* has
> to make, and every one of them has a written answer this spec builds to. A builder who reads only
> this line can start and finish the row: build the core, wire the 19 sites, skip Step 3, and report
> what happened on the phone. Q1 alone decides whether F7 is closed by this row or by the next one.

### ⛔ Blocked for Claude (a Lead decision, not a builder's — none of these blocks a builder)

**Q1 — F7's patch. May a T2 builder apply Step 3, or is it the Lead's edit?**
Step 3 is a one-line move in `JbCanvasView.kt:1055-1066`, written out and proved above. R30 item 2
says T2 rows do not edit that file, and the board lists `JB-0.08c` under `JoyBrushActivity.kt` only.
I have written the patch so this is an authority question and not a design one, and the spec tells
the builder to **skip Step 3 and say so** rather than apply it. But F7 is a visible first-frame bug
and it will stay open if the Lead does not land the line. **Which is it: a) the Lead applies Step 3
alongside this row, b) this row is dispatched with a one-row R30 exception for that single line, or
c) F7 becomes its own tiny Lead row?** (a) is my reading. *(Blocks: F7 only. Nothing else in the row
depends on it.)*

**Q2 — `GlPaintEngine.release()` is never called (the other half of F5), and it is a Lead decision.**
Verified: `JoyBrushActivity.kt` has no `onDestroy` today; `JbCanvasView` has no `release()` and no
`onDetachedFromWindow`; `GlPaintEngine.release()` (`GlPaintEngine.kt`, `fun release()`) calls
`glDeleteTextures`/`glDeleteBuffers`/`glDeleteVertexArrays`/`glDeleteFramebuffers` and then
`ready = false`, so **it is only meaningful while the EGL context is current** — and by the time an
Activity is destroyed its GL thread has already been paused by `GLSurfaceView.onPause`, so
`queueEvent` would defer and `release()` would never run. Reaching it needs either a
`onDetachedFromWindow` override that releases before `super`, or nothing. `openSettings`'s preview
canvas (`:689`) is a second `JbCanvasView` with the same story. **Do you want this ridden with the
next row that has the file, as a separate Lead patch, or is the EGL context teardown on this device
already freeing it?** I have not guessed, and this row does not need the answer. *(Blocks: nothing
here. It is why F5 is stated as "the screen's own callbacks", not "every GL resource".)*

**Q3 — the recent copy is written even when the Open… is refused.** Decision 8. The ruling says
"before Open… **replaces** a drawing", and a refused open replaces nothing, so a strict reading
would write nothing. I chose to write anyway, because a refusal is the moment a safety copy is
cheapest to have and the cost is at most one of five slots holding a copy of a drawing the person
still has. The alternative costs a two-flag handshake across a GL readback and a `ui.post`, i.e. a
race between "the snapshot answered" and "the load succeeded", in a file R30 is trying to keep
small. **Confirm the loose reading, or tell me to gate it** — if you want it gated, say so and I
will rewrite Step 2's A17 and A18 before anyone builds it. *(Blocks: nothing. PROVISIONAL — the
builder builds it as written; if the answer is "gate it", A17 and A18 are the only two sites that
change.)*

**Q4 — is re-stamping `DOC_VERSION` really safe? (`DocMerge`'s KDoc asserts it is.)**
The argument is one line: JB-0.02d made `DocJson.decode` **refuse** a same-version file carrying an
unknown key, so any file this build decoded has no key it did not know, so re-encoding loses
nothing. I checked `DocJson.kt:112-120` and the exemption is narrow (only a root `version` that is
an integer **strictly greater** than `DOC_VERSION` skips the scan), so a same-version file is always
scanned. **Do you accept that as the reason, or do you want `DocMerge` to refuse to write when the
opened document's `version` is not `DOC_VERSION`?** Nothing needs the second half today, and adding
it would put a rule in the save path for a case no file can reach. *(Blocks: nothing. It is a
KDoc sentence, not a branch.)*

### PROVISIONAL — Claude to confirm (low risk, the builder builds it as written)

**Q5 — `20260930-141203-482.joybrush` is a filename no human will enjoy reading.** The millisecond
is there to make a collision impossible (Decision 15) rather than to be helpful. If you want
`20260930-141203.joybrush`, the collision rule has to be decided too, and the obvious one (a
`-2`, `-3` ladder) sorts wrong against the milliseconds. *As written, the milliseconds are correct
and the ladder is refused.*

**Q6 — `RecentFiles.prune` orders by FILE NAME, so a backwards clock step could prune the copy it
just wrote.** Millisecond stamps do not help: if the wall clock jumps backwards between two Opens,
the second file sorts older than the first and the next prune removes it. The working file and its
`.bak` are untouched, so this costs a safety copy and never a drawing. Accepted and documented, not
fixed — fixing it means either a monotonic sequence number in the name or a `lastModified` sort, and
both are worse for a person browsing the folder. **Tell me if you want it sorted by `lastModified`
instead**, which is a two-line change to the screen and none to `RecentFiles` (the test would then
need a real file, and would move to `jvmTest`).

**Q7 — should the recent ring be shown anywhere?** The ruling says write and keep, and says nothing
about a way back to them. The only way to reach one today is a file manager, which is fine. **But a
person who loses a drawing needs to be able to get it back, and a folder of five files with no
index is not a recovery UI.** I have built exactly what was ruled and nothing more; this is a
Question, not a request, and it belongs to whichever row lands the vault.
