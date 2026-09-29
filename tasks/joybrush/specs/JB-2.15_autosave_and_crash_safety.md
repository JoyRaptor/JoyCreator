# JB-2.15 — Autosave and crash safety: work is never lost, and a save is never lost either

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-0.08 (`JbArchive`, the save/open wiring — Built 🟧) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/io/SaveQueue.kt`; NEW `.../commonTest/.../io/SaveQueueTest.kt`; EDIT `joybrush-android/src/main/java/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the save section, as listed in Decisions 2–6) |
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
     * @return the words to show on success, or null on success with nothing to say.
     * @throws Exception on failure; the queue reports it against THIS request and moves on.
     */
    fun perform(reason: SaveReason, target: SaveTarget2): String?
}

/** Where a request's bytes go. */
sealed class SaveDestination {
    /** The working file, through `JbArchive.save` (atomic). */
    object Working : SaveDestination()
    /** A SAF Uri the person chose. Never the working file, ever (R11). */
    data class Copy(val uri: android.net.Uri, val failurePrefix: String) : SaveDestination()
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
4. **R11 is honoured at the point of the snapshot, for EVERY destination including a copy.** This is
   the fix for MAJOR 3: the stroke check is inside `SaveTarget.perform`, so it cannot be bypassed by
   arriving through a different door. And because the request waits rather than proceeding, a copy
   taken with the pen down is taken **after the stroke ends** and therefore contains it. **A copy
   never reports success on a pre-stroke snapshot, because the only snapshot that exists was taken
   at a moment when no stroke was in flight.**
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
12. **A test that the wiring uses this queue and not a flag**: a source-level check that
    `JoyBrushActivity.kt` contains no `saveOwed` variable and calls `SaveQueue`. **The finding was
    a variable's lifecycle; the fix has to remove the variable, or the next reader will find it and
    use it.**

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher green.

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
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] **all five owner checks done** (this row is not done without them — the three findings were
      found by reading, and only the device can prove they are gone)
- [ ] committed `JB-2.15: autosave and crash safety`
- [ ] ROADMAP row → 🟧 Built

## Questions

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
