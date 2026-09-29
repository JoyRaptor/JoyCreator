# JB-2.15 — Autosave and crash safety: work is never lost, and a save is never lost either

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready — with the contract below corrected. **One dispatch warning:** the owner area edits `JoyBrushActivity.kt`, an app file, and **three specs in flight name it (this one, JB-3.06b, JB-2.13b) plus JB-0.10's Q2 wants a fourth.** The board's app-file order is the Lead's; this row is otherwise complete. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29 · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — re-derived all three MAJORs against the landed `JoyBrushActivity.kt` and **all three are true**: `saveAsync` at `:383` drops its arguments on `compareAndSet` (`:388-391`); `saveOwed` is set at `:428`, consumed at `:449-451`, and `onHistoryChanged` calls `onStrokeFinished()` at `:165`; `saveCopyTo` at `:489` calls `saveAsync` without the `strokeInProgress` check `saveWorkingFile` has at `:427`. Also verified Q3's data-loss claim: `readContents` (`JbCanvasView.kt:543-581`) builds a **fresh** `DocOps.newDocument` and copies only `paper.color`, `visible`, `opacity` and `cel.tiles`. **Fixed two things that could not compile or run:** the contract's `perform(reason, target: SaveTarget2)` referenced a type that exists nowhere and never passed the `SaveDestination` the queue was given, and Test 12 was a `:core:jvmTest` reading a file in another module. **Corrected** the owner-area path (`src/main/java` → `src/main/kotlin`) and the "never run gradle on the owner's PC" line, which contradicted this spec's own command. |
| **Needs** | JB-0.08a (`JbArchive`) + JB-0.08b (the save/open wiring) — both Built 🟧. **There is no row called "JB-0.08";** it is `0.08a` and `0.08b`, and this spec needs both. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/io/SaveQueue.kt`; NEW `.../commonTest/.../io/SaveQueueTest.kt`; EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the save section, as listed in Decisions 2–6). **🔴 that last path is an APP FILE — see "Do not".** |
| **Estimated size** | ~180 lines of core + ~200 lines of tests; ~120 lines of wiring |

## Goal

Blueprint §3.5, and the only line in it that is not a design instruction:

> *"**Autosave that never loses work** — the #1 complaint across 34 000 reviews of competitors was
> crashes and lost work, ahead of any UI issue."*

JB-0.08b built the mechanism and it is good: the archive's **atomic** write (tmp + fsync + rename +
`.bak`), a refusal in words before any GL work, a save-owed flag so a save never interrupts a
stroke. **And then three MAJOR findings landed against it, and all three are the same missing thing.**

## 🔴 What this row exists to fix, quoted from `reviews/JB-0.08b__muse-spark.md`

1. **MAJOR: an explicit "Save a copy…" tapped during any autosave is silently discarded.**
   `saveAsync` starts `if (!saving.compareAndSet(false, true)) { repost idleSave; return }` — it
   **drops its `write`/`failurePrefix`/`done` arguments**. So a copy tapped while `saving == true`
   never writes its Uri, shows no toast, and the retry writes the *working file*, not the copy. The
   same path drops a pause autosave, and "kill before resume" then loses work.
2. **MAJOR: the R11 save-owed flag is consumed by any history event and can be dropped mid-stroke.**
   `saveOwed` is set when `strokeInProgress`; it is consumed in `onStrokeFinished`, which is called
   from `onHistoryChanged` — i.e. undo/redo/clear as well as stroke end. Pause mid-stroke →
   `saveOwed = true` → the person taps Undo → `saveOwed = false` and the save is gone. **The
   promised save disappears because of an undo.**
3. **MAJOR: "Save a copy…" bypasses the R11 guard and reports success on a pre-stroke snapshot.**
   `saveWorkingFile` checks `strokeInProgress`; `saveCopyTo → saveAsync` does not. The live stroke
   lives in the engine's stroke buffer, not in a tile, so the copy is **missing exactly the stroke
   the person is looking at** — and the toast says "Copy saved".

> **The reviewer wrote: "All three are the same missing serialisation of save requests, and all
> three are work-loss." That sentence is this spec.** Everything below is a way of making a save
> request a **thing that is queued, never a thing that is dropped**.

## Contract

```kotlin
package cc.joycreator.joybrush.core.io

/** Why a save was asked for. The ONLY difference between the saves is this. */
enum class SaveReason {
    /** onPause, or 30 s idle. May be coalesced with another IDLE already pending. */
    IDLE,
    /** The person asked for it — "Save a copy…", a future explicit "Save". NEVER coalesced, NEVER
     *  dropped, and it reports its own outcome in its own words. */
    EXPLICIT,
}

/** What the queue is allowed to ask the shell to do, one at a time, in order. */
interface SaveTarget {
    /**
     * Take a snapshot and save it. Called on the caller's thread, once per request, IN ORDER and
     * NEVER CONCURRENTLY with another request.
     *
     * @param reason why this save was asked for. The ONLY difference between the saves.
     * @param destination where THIS request's bytes go. It is passed straight through from
     *   [SaveQueue.request] — the queue never substitutes one destination for another, and in
     *   particular a `Copy` is never quietly redirected to the working file.
     * @return the words to show on success, or null on success with nothing to say.
     * @throws Exception on failure; the queue reports it against THIS request and moves on.
     */
    fun perform(reason: SaveReason, destination: SaveDestination): String?
}

/**
 * Where a request's bytes go.
 *
 * **[Cross-reviewer, 2026-09-29: `uri` is a `String`, not an `android.net.Uri`.]** The original draft
 * said `data class Copy(val uri: android.net.Uri, …)`. That does not compile: this file lives in
 * `joybrush/core/src/commonMain`, the core module declares **no `android.jar`** (`core/build.gradle.kts`
 * is a bare `kotlin("multiplatform")`), and the module's own header says *"NO Android, NO Java-only
 * APIs in commonMain"*. The wiring parses the string with `android.net.Uri.parse` on the Android side
 * and a `Uri?` in a test that runs in `:core:jvmTest` is not expressible either. The string is also
 * what a queue should hold: it is a value to compare and to print in a message, not a live handle.
 * Converting back is one line at the call site and is named in Decision 2.
 */
sealed class SaveDestination {
    /** The working file, through `JbArchive.save` (atomic). */
    object Working : SaveDestination()
    /** A SAF Uri the person chose, as `Uri.toString()`. Never the working file, ever (R11). */
    data class Copy(val uri: String, val failurePrefix: String) : SaveDestination()
}

class SaveQueue(private val target: SaveTarget) {
    /** Number of requests not yet performed. */
    val pending: Int
    /** Requests dropped because a newer EXPLICIT one replaced them. ALWAYS 0 — see Decision 3. */
    val dropped: Int

    /**
     * Asks for a save. Returns the request's id, or 0 when [reason] was IDLE and one was already
     * pending (coalescing — the person did not ask, so there is nothing to lose).
     *
     * A save asked for while `strokeInProgress` is NOT performed and NOT dropped: it stays queued
     * and the queue is told when the stroke ends.
     */
    fun request(reason: SaveReason, destination: SaveDestination): Int

    /** The stroke just ended (or was cancelled). Runs everything that was waiting on the stroke. */
    fun strokeFinished()

    /** Runs the queue now, if it can. Safe to call from anywhere, including twice. */
    fun drain()
}
```

`SaveQueue` is **pure**: it holds an interface, a list and a few booleans, and it knows nothing
about Android, GL, files or threads. That is the whole point — a serialisation bug is exactly the
kind of bug a JVM test can catch and a device cannot.

## Decisions

1. **A request is a queue entry, and a queue entry is performed exactly once, in order, never
   concurrently.** Not a `compareAndSet` that returns. Not a "repost and hope". The finding's own
   word: **serialise**.
2. **IDLE coalesces; EXPLICIT never does.** Two idle autosaves are one autosave — nobody asked for
   either. An explicit "Save a copy…" is never coalesced with anything, never dropped, never
   replaced, and **never shares a gate with an autosave**. `dropped` is a field so the test can
   assert it is `0` forever; a design that needed a non-zero `dropped` would be wrong here.
3. **A request made while `strokeInProgress` waits, and is released by `strokeFinished()` — and by
   NOTHING ELSE.** This is the fix for MAJOR 2. `strokeFinished()` is called from the engine's
   **stroke-end and stroke-cancel** paths and from nowhere else: **not** from `onHistoryChanged`,
   not from undo, not from redo, not from a clear, not from a layer edit. Undo is a history event
   and it does not pay a debt owed by a stroke. (An undo DOES set an IDLE debt of its own — the
   document changed — which is a different, correctly-owned debt.)
4. **R11 is honoured at the point of the snapshot, for EVERY destination including a copy, and it is
   checked in BOTH places on purpose.** This is the fix for MAJOR 3. Decision 3 makes the queue *hold*
   a request until the stroke ends; this decision makes `SaveTarget.perform` **re-check
   `canvas.strokeInProgress` itself and refuse** if one is somehow in flight. The two are not
   redundant: the queue's check is the behaviour, and the target's check is what makes a *second*
   door into `perform` — the copy path, which is the exact door MAJOR 3 came through — incapable of
   bypassing it. So a copy taken with the pen down is taken **after** the stroke ends and therefore
   contains it. **A copy never reports success on a pre-stroke snapshot, because the only snapshot
   that was ever taken was taken at a moment when no stroke was in flight.** Test 5 pins the
   property from the target's side, which is the side that can catch the bypass.
5. **A failure is reported against its own request and the queue keeps going.** The person sees
   "Couldn't save the copy. Your drawing is safe." (R11's exact wording for the copy path) and the
   *working file* is untouched by a failed copy, because a copy never writes the working file.
6. **A failure does not stop the queue, and a success does not clear a failed one.** If a save
   fails, the debt stands and the next `drain()` tries again — bounded by the 30 s idle timer, so
   it is a retry, not a spin.
7. **The snapshot itself is never taken twice for two requests.** Each `perform` takes its own
   snapshot (they can be at different document states and both are right), but the queue guarantees
   no two are in flight — so there is one `queueEvent` on the GL thread at a time and no interleaving
   of `readContents`.
8. **`onPause` is a request, not a save.** A pause enqueues; the queue drains when it can. The old
   code's "pause autosave skipped while saving, retry via idleSave — which cannot complete while GL
   is paused, so kill-before-resume loses work" is fixed by: the pause request is **never skipped**,
   so when the GL thread comes back the queue still holds it, and the first `drain()` after resume
   performs it. **The loss window is now bounded by the idle timer, not by the kill.**
9. **Nothing new about the atomic write, the `.bak` rotation, the refusal, or the paper/name
   round-trip.** Those are JB-0.08b/0.08a and they are correct. This row changes *when* a save
   happens and *whether it can be lost*, and touches nothing else. `readContents`' metadata churn
   (MINOR 6 of the same review: `textureScale`, `includeInExport`, `locked`/`blend`/`name`, ids) is
   **out of scope and stays open** — see Questions, because a document that silently loses
   `textureScale` on resave is the same family of work-loss and the Lead should know I saw it.
10. **The 30 s idle timer is armed by an IDLE request and disarmed by nothing but the screen
    closing.** A stroke is not an idle event: the timer measures quiet, and drawing is not quiet.

## Tests (`SaveQueueTest`, JVM, `:core:jvmTest`) — one per MAJOR, named for the finding

1. **`anExplicitSaveDuringAnAutosaveIsNotDiscarded`** (MAJOR 1): queue an IDLE, drain it into a
   `perform` that blocks; while it is in flight queue an EXPLICIT copy; release. **Both performed,
   in order, `dropped == 0`, `pending == 0`**, and the copy's `SaveDestination.Copy` reached
   `perform` with the Uri it was given.
2. **`aPauseSaveIsNotSkippedWhileAnotherSaveIsInFlight`** (MAJOR 1's second half): the same with
   `IDLE` for the second request — it is coalesced only if one is *pending*; a save *in flight* is
   not pending, so the pause request is queued and performed afterwards. The test asserts
   `pending == 1` while the first is in flight.
3. **`anUndoDoesNotPayASaveOwedByAStroke`** (MAJOR 2): `strokeInProgress` true → request →
   `pending == 1`; the test then calls **only the undo path** — which in production is
   `onHistoryChanged`; here the test asserts the queue's own API offers no other way to release it,
   **and** that calling `drain()` while the stroke is still in progress leaves `pending == 1`.
   Then `strokeFinished()` → `pending == 0` and the save happened.
4. **`strokeFinishedReleasesBothTheEndAndTheCancel`** (MAJOR 2's converse): the old code's
   `cancelStroke` never reported history, so a debt lingered forever. Both paths release.
5. **`anExplicitCopyDuringAStrokeWaitsAndThenContainsIt`** (MAJOR 3): `strokeInProgress` true,
   queue an EXPLICIT copy, assert `perform` has NOT been called; `strokeFinished()`; assert it has,
   and that the target was asked for a snapshot at a moment the test records as "no stroke in
   flight". The fake target records the `strokeInProgress` value it saw — **and the test asserts it
   was `false`, on every single call.** That is the MAJOR-3 assertion stated as a property: *no
   snapshot is ever taken with a stroke in flight, whatever asked for it.*
6. **`nothingIsEverPerformedConcurrently`**: a fake target whose `perform` records entry and exit
   and asserts it is not already inside; 20 requests in a row; the recorded sequence is strictly
   non-overlapping and in submission order.
7. **Coalescing:** 5 IDLE requests with none in flight → `pending == 1`; and the coalesced one is
   the LATEST destination, not the earliest (the newest intent wins, and for the working file the
   two are the same bytes anyway). An EXPLICIT between two IDLEs prevents coalescing across it.
8. **`aFailureIsReportedAgainstItsOwnRequestAndTheQueueContinues`**: the first `perform` throws →
   the words reach the caller for THAT request, the second request still runs, `pending == 0`.
9. **`failedRequestsAreRetriedNotForgotten`**: a failure with the idle timer still armed → the next
   `drain()` attempts it again (and the test counts two attempts).
10. **`droppedIsAlwaysZero`**: after the whole suite's scenarios, `dropped == 0`. A design that
    needed a drop would be a different design; this asserts the one we have is not that design.
11. **`aRequestSurvivesTheQueueBeingIdle`** — no spinning: `drain()` with `pending == 0` calls
    `perform` zero times. (A queue that retries in a loop is a battery bug on a phone.)
12. **`theWiringUsesTheQueueAndNotAFlag`** — this test was going to live in `:core:jvmTest` and read
    `JoyBrushActivity.kt` off disk, which **cannot work**: `:core` is a `kotlin("multiplatform")`
    module with no access to the repo layout, and a test that greps a file in another module is not a
    test, it is a build script wearing one. **It is a Definition-of-done line instead**, and it is
    mechanical:
    - [ ] `grep -c saveOwed joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt`
          returns **0** (paste the output),
    - [ ] `grep -c 'compareAndSet' …/JoyBrushActivity.kt` returns **0** for the save path,
    - [ ] `grep -c 'SaveQueue' …/JoyBrushActivity.kt` is **> 0**,
    pasted into the report. The finding was a variable's lifecycle; the fix has to remove the
    variable, and this is how the next reader finds out if it came back. **[Cross-reviewer,
    2026-09-29 — moved out of the JVM test suite because it cannot run there.]**

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures in
`joybrush/core/build/test-results/jvmTest/`. **That command runs tests 1–11 only.** It does not and
cannot see `JoyBrushActivity.kt` (see test 12). The wiring half is proved by the watcher's
`:joybrush-android:compileDebugKotlin` inside `BUILD SUCCESSFUL` plus the three greps above — paste
all four. Never run gradle on the app build; only `-p joybrush` is permitted (ROADMAP §2 rule 2).

## Owner check (Note 9 — this row's verification is a device test, and it is the one that matters)

1. Draw a stroke, press Home, force-stop from Recent, reopen → the drawing is back.
2. Start a stroke, keep the pen down, press Home, force-stop, reopen → the drawing is back
   **including the stroke that was in progress** (this is MAJOR 3's shape at its worst, and R11's
   "never end the person's stroke to save" means it can only be right if the save waited).
3. Start a long stroke; mid-stroke tap Undo; lift; press Home; force-stop; reopen → the drawing is
   **exactly what is on screen** (MAJOR 2: an undo must not eat the save).
4. Start drawing and, without lifting, tap "Save a copy…" → a toast appears, the Uri is written, and
   **the copied file contains the stroke**. (MAJOR 1 + 3 together.)
5. Fill the disk (or revoke a folder permission) and tap "Save a copy…" → a toast in words, and the
   working file is still the last good one.

## Do not

- **Do not keep a `saveOwed` boolean.** Test 12 exists to make that a build failure (Decision 1/3).
- Do not touch `JbArchive`, `JbContents`, the atomic write, the `.bak` rotation, or the refusal
  logic. They are correct and they are JB-0.08a's and JB-0.08b's.
- Do not end or cancel a stroke to make a save easier. R11: *"Never end or cancel the person's
  stroke to save."* The queue waits instead.
- Do not add a second file, a `previous.joybrush`, or a "recover my drawing" screen. That is
  JB-0.08b's open Q2 and it is not this row.
- Do not make the queue aware of threads. It is a queue, not a `Handler`; the wiring owns the
  executor that already exists (`fileIo`).
- **Do not put `android.*` in `SaveQueue.kt`.** It is in `core/commonMain`, which has no
  `android.jar`. The Uri is a `String` (see the contract) and `Uri.parse` happens on the Android
  side. A `Uri` here does not compile, and the failure message will not say why.
- **🔴 Do not edit `JoyBrushActivity.kt` in this row without the Lead naming the slot.** It is an app
  file, and the board's own note (Lead, 2026-09-29) is that app-file work is serialised, one at a
  time, never beside `JB-0.09`. **Four rows want this one file: this one, JB-3.06b, JB-2.13b, and
  JB-0.10's Q2.** If this row cannot have the file, the queue half is still worth landing on its own
  — it is pure and fully tested — and the wiring becomes a two-line follow-up once a slot is free.
  Say so and I will split the spec that way.
- Never run gradle on the app build. **Only** `./gradlew -p joybrush …` is permitted, and only the
  `:core` / `:androidkit` tasks (ROADMAP §2 rule 2). Anything in `:app` or `joybrush-android/` is
  proved by the watcher's `build.log`, never by running it.

## Stop rule

This row exists because work was lost, so the stop rule is stricter than usual. If anything here is
ambiguous, or a claim about `JoyBrushActivity.kt` turns out to be false when you open the file,
**STOP**: write the question in *Questions* under a heading `for the cross-reviewer`, set this row
`⛔ Blocked`, commit, push, and take another task. Specifically: **never end or cancel a stroke to
make a save easier** (R11), **never write a file directly to a SAF `Uri` incrementally** (build the
whole archive in `cacheDir` and stream it), and **never let a request be dropped** — if a request
cannot be performed, it stays queued and the queue says why. A save that is *reported* as done and
was not is the single worst outcome this codebase can produce.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] **all five owner checks done** (this row is not done without them — the three findings were
      found by reading, and only the device can prove they are gone)
- [ ] committed `JB-2.15: autosave and crash safety`
- [ ] ROADMAP row → 🟧 Built

## Questions

### Rulings on the writer's four questions below (cross-reviewer, 2026-09-29)

All four are ruled, so the row is dispatchable. The writer's own text follows for the argument.

**Verified against the tree first, because all three findings are worth nothing if they are not
real.** All three are. Every line reference below was opened and read:

| This spec says | `JoyBrushActivity.kt` says |
|---|---|
| MAJOR 1: `saveAsync` drops its arguments and re-posts | `:383` `private fun saveAsync(`, `:388` `if (!saving.compareAndSet(false, true)) {`, `:390-391` `ui.removeCallbacks(idleSave); ui.postDelayed(idleSave, AUTOSAVE_AFTER_MS)` — and `return`, so `write` / `failurePrefix` / `done` are gone. True. |
| MAJOR 2: `saveOwed` is consumed by any history event | `:132` `private var saveOwed = false`; `:427-428` `if (canvas.strokeInProgress) { saveOwed = true }` inside `saveWorkingFile`; `:449-451` `if (!saveOwed) return; saveOwed = false; …`; and `:165` `onStrokeFinished()` called **from `canvas.onHistoryChanged`**, whose comment says it reports "after every committed stroke, undo, redo and clear". True. |
| MAJOR 3: `saveCopyTo` bypasses the R11 guard | `:427` the `strokeInProgress` check lives in `saveWorkingFile`; `:489-490` `private fun saveCopyTo(uri: Uri) { saveAsync( …` — no check on that path. True. |
| Q2: "I do not know whether the owner's screen has a background/foreground split" | There is **no `override fun onStop`** in the file, and `onPause` is at `:192`. So there is no split to worry about. |
| Q3: `readContents` loses metadata on re-save | `JbCanvasView.kt:543-581`: `readContents` calls `DocOps.newDocument(DOC_ID, documentName, w, h)` — a **fresh** document — then copies only `paper.color` (`:571`), `visible` (`:574`), `opacity` (`:575`) and `cel.tiles` (`:576`). `paper.textureScale`, `paper.includeInExport`, `layer.locked`, `layer.blend`, `layer.name`, `board.name`, `board.clipToBoard` and every id come back at their defaults. True, and it is data loss. One precision the spec writer did not have: a document with a paper **texture** never reaches this code at all, because `refusalFor` refuses it at open (`JbCanvasView.kt:519-521`) — so the loss is `textureScale` and `includeInExport`, not a texture. |

**Rulings:**

1. **Q1 (an undo now raises its own IDLE debt) — confirmed, as Decision 3 already says.** The visible
   consequence is one extra save some seconds after an undo, which is free and correct. **PROVISIONAL
   — Claude to confirm.**
2. **Q2 (`onStop`) — do NOT add it in this row.** The spec's Decision 8 already bounds the loss
   window by the idle timer, and adding a second save trigger is a behaviour change to the thing the
   owner complained about, decided inside a row about serialisation. It is a one-line follow-up and
   belongs as its own small row once the device check in *Definition of done* has said whether the
   window is acceptable. **PROVISIONAL — Claude to confirm.**
3. **Q3 (MINOR 6, `readContents` metadata loss) — stays out of this row, and it is a real bug.** The
   fix is inside `JbCanvasView.kt`, which is one of the two hottest files in the project and is not
   this spec's owner area. My recommendation is that it rides in the **same pass** as whichever row
   next has that file, not as a new row — but that is the Lead's scheduling call. **Referred, not
   dismissed; please do not lose it.**
4. **Q4 (MINOR 5, `onDestroy` cleanup, the leaked `idleSave`, `GlPaintEngine.release()`) — also
   referred, same reason.** Note one interaction with this row: `idleSave` is `:136`
   `Runnable { if (changes > 0) saveWorkingFile() }` and this row rewrites the save section, so a
   builder should at least not make the leak *worse* — Decision 10's idle timer is the same 30 s
   `AUTOSAVE_AFTER_MS` and must keep being armed and disarmed through the queue rather than around it.

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29, after reading
`reviews/JB-0.08b__muse-spark.md` in full. This is the spec I am least sure should be dispatched
without you reading the review first, because three of its decisions are mine and they change
behaviour the owner has already used.)_

### 🔴 For the Lead

1. **Decision 3 changes what an undo costs.** Today an undo is a history event that happens to
   clear a flag. After this row, an undo raises its **own** IDLE debt (the document changed, so it
   will be saved) **and does not touch the stroke's debt**. The visible consequence: pressing Undo
   mid-stroke now always produces a save a few seconds later, where before it might have produced
   none. I think that is right and free, but it is a behaviour change to the thing the owner
   complained about in the first place. **Confirm.**
2. **Decision 8 changes the loss window on kill.** A pause save is no longer *skipped* while
   another save runs; it is queued and runs after resume. The consequence: **force-stopping the app
   while the GL context is gone loses the strokes made since the last successful save, which is
   what the 30 s idle timer bounds** — and that is strictly better than the current behaviour, where
   the pause save was skipped and the retry could not complete. But it is still a window, and the
   blueprint says "never loses work". **Do you want the interval shortened, or an `onStop` request
   in addition?** `onStop` is the last callback Android reliably delivers before a kill, and it is
   one line in the wiring. I have not specified it because I do not know whether the owner's screen
   has a background/foreground split that makes `onStop` fire mid-session.
3. **MINOR 6 of the same review is out of scope and I want that on the record.**
   `JbCanvasView.readContents` copies only `color`/`visible`/`opacity`/`tiles`, so opening a
   document that has `textureScale = 2` and re-saving writes scale 1; the same for
   `includeInExport`, layer `locked`/`blend`/`name`, board `name`/`clipToBoard`, and every id.
   **That is data loss of exactly the kind this row exists to prevent, and it is a DIFFERENT bug
   from the three MAJORs.** I have left it out because fixing it is inside `JbCanvasView`, which is
   JB-0.08b's file and is being edited by whoever fixes the three MAJORs. **It should be a fourth
   fix in that same pass, not a new row** — the fix is "carry the document, change only the engine's
   own fields", which is small. Your call, and I did not want to write a spec that edits a file
   another agent is about to edit.
4. **MINOR 5 (`onDestroy` cleanup, a 30 s `idleSave` leak, `GlPaintEngine.release()` never called)
   is also out of scope** for the same reason. `saveOwed` is gone after this row, but the leaked
   `idleSave` Runnable and the orphaned GL textures on rotation are not. Also JB-0.08b's file.

### Low-risk, ruled provisionally

5. **IDLE coalesces, EXPLICIT never does** (Decision 2), and `dropped` is a field that must stay 0.
6. **A failed save is retried by the next idle tick, not by a spin** (Decision 6/11).
7. **One snapshot per request, taken at perform time** (Decision 7) — two requests at two document
   states are both correct; interleaving them is not.
8. **`strokeFinished()` fires on cancel as well as on end** (Decision 4) — the review found the
   old code's cancel path never reported anything, so a debt could linger forever.
