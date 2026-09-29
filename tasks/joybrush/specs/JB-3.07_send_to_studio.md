# JB-3.07 — "Send to Studio": hand an animation board to the Studio timeline

| | |
|---|---|
| **Tier** | T1 (T1 review + T3 phone check) — **the receiving half is a Studio feature, not a Joy Brush wire-up**, and it is app-file work under the Lead's serialised order |
| **Status** | 🟨 **Draft.** The handing-over half is completely specified and testable in `:core` today, because it is arithmetic over a file that does not exist yet. The receiving half does not exist at all: the Studio's sequence import is `SequenceDetector` + `SequenceImportDialog`, whose own governing instruction is **"OFFER, never assume"** — it needs a *pick* and a confirmation, so there is no drop target to drop on. That half is Q1, and it is a real feature in a 27 000-line file. |
| **Needs** | 3.06 — **which is 3.06b** (the export, formats and `AnimExportPlan`) **and 3.06a** (the GIF encoder). Nothing here re-derives either. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/StudioHandoff.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/StudioHandoffTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/StudioHandoffRunner.kt` · NEW `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/SendToStudio.kt` (the button + the sheet, **no** `JoyBrushActivity` edit — see Step 6) |
| **Estimated size** | ~200 lines of Kotlin in `:core` + ~220 of tests, ~200 in `androidkit` |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`. |

> **The app-file reality, stated plainly so nobody is surprised at Step 6.** The Studio half of this
> row edits `FaditorEditorActivity.java`, the timeline model and `ProjectStorage`. Those are **app
> files**, and the Lead's serialised order is `D.02a → D.02 → D.02c / D.05`, one at a time, never
> two in the tree together. **This row cannot be completed until the Lead schedules the receiving
> half.** Steps 1–5 below are the Joy Brush half, are complete on their own, and are what a builder
> should do now; Step 6 is the gate.

## Goal

Blueprint §4 Phase 3, owner check: *"You animate a short loop and drop it on a Studio timeline."* And
§3.1: **"Hand-offs to the rest of Joy Creator are file contracts the app already reads"** — so this
row invents no format and adds no shared class. It writes what the Studio already reads and opens
the screen that shows it.

The whole design in one sentence: **the hand-off is a folder of numbered PNGs plus a `fps` and one
`weights` number per frame, and nothing else** — because that is the Studio's own timing model
(`SequenceTiming`: `frame_duration = total × weight / Σ weights`, `fps` the stored authority), and it
is the *only* hand-off that can carry a held frame. The GIF, the sprite sheet and the MP4 are export
formats, not a hand-off format, and this row does not use them.

## Contract — the hand-off description (`:core`, pure, buildable today)

```kotlin
package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.Board

/**
 * What the Studio needs in order to play a Joy Brush loop on a timeline, as DATA. Nothing here
 * touches a pixel, a file or a Uri — it is the numbers and the names, so the whole of it is a
 * test with no canvas and no phone (JB-3.06b Decision 6, verbatim, and the same reason).
 *
 * **The unit for a held frame is a WEIGHT, not a millisecond.** See Decision 1; it is the one
 * decision in this file that a builder would otherwise get wrong.
 */
data class StudioHandoff(
    /** Folder name the frames are written into, safe on every filesystem. */
    val folderName: String,
    /** Frame file names in play order, e.g. ["0001.png", …]. `AnimExportPlan` numbers them. */
    val frameNames: List<String>,
    /**
     * One weight per frame, in the same order as [frameNames]. **Every entry is >= 1.**
     * A frame held for three ticks is the weight `3`, NOT three copies of the index — the
     * Studio's `Clip` carries a `FrameTrack` of `Key(cellIndex)`, and a repeated index would
     * read back as three separate one-tick frames.
     */
    val weights: List<Int>,
    /** The board's own fps, verbatim from the plan. The Studio stores fps as the authority. */
    val fps: Float,
    /** The suggested clip name — the board's own name, through `AnimExport.safeBaseName`. */
    val clipName: String,
) {
    init {
        require(frameNames.isNotEmpty())
        require(weights.size == frameNames.size)
        require(weights.all { it >= 1 })
        require(fps.isFinite() && fps > 0f)
        require(folderName.isNotEmpty())
    }

    /** Σ weights — the Studio's `SequenceTiming.totalWeight`, computed here for the check. */
    val totalWeight: Int get() = weights.sum()

    /** How long the clip runs, ms, at this fps. A VIEW, never a second answer. */
    val totalMs: Int get() = AnimExport.totalMsForFps(fps)  // see Decision 2
}

object StudioHandoff {

    /**
     * The hand-off for [plan] — the whole board, or 3.06b's sub-range, exactly as it stands.
     *
     * @throws IllegalArgumentException if the plan's frame count and its weight count disagree,
     *   which can only happen if a caller built the weights by hand. Refused in words (the house
     *   rule), never padded and never truncated: a clip whose timing is silently one frame short
     *   is a bug report with no cause anybody can find.
     */
    fun describe(plan: AnimExportPlan, holds: List<Int>, boardName: String): StudioHandoff

    /**
     * Weights from a frame-duration list in ms — the shape a person thinks in — rounded to whole
     * ticks, ties away from zero, every weight >= 1.
     *
     * The conversion is by the plan's OWN `fps`, never by a literal: a hold of 250 ms on a 12 fps
     * board is 3 ticks and on a 24 fps board is 6, and a builder who divides by 1000 has silently
     * assumed 1 fps. This is the same lesson as JB-3.05a Decision 1 and JB-3.06b Decision 2.
     */
    fun weightsFromDelaysMs(delaysMs: List<Int>, fps: Float): List<Int>

    /**
     * The ONE sentence the Studio will show when the loop arrives, and the one the refusal uses.
     * Pure, so the wording is asserted rather than eyeballed.
     */
    fun arrivalSentence(handoff: StudioHandoff): String
}
```

### The wire — what Joy Brush actually hands over

```kotlin
package cc.joycreator.joybrush.androidkit.io

/**
 * Writes the hand-off and starts the Studio. **A FILE IS ALWAYS WRITTEN FIRST**, verbatim from the
 * owner's ruling on the SpriteLab button (JB-4.03b Decision 2): if the write fails, nothing is
 * started and nothing is claimed.
 */
object StudioHandoffRunner {
    /**
     * @param onFrame (written, total) so the sheet can say "frame 7 of 40" instead of freezing.
     * @return the folder the frames are in, and the handoff that describes them.
     * @throws StudioHandoffException with a sentence, on any refusal. Never a partial result.
     */
    fun send(
        board: Board,
        plan: AnimExportPlan,
        holds: List<Int>,
        tiles: TileSource,
        paper: String?,
        name: String,
        launch: (HandoffPayload) -> Unit,
        onFrame: (Int, Int) -> Unit = { _, _ -> },
    ): StudioHandoff
}

/** What crosses into the Studio: a file path the Studio may read, and the numbers. */
data class HandoffPayload(
    val folderPath: String,
    val handoff: StudioHandoff,
    /** `FaditorEditorActivity`'s class name, so the runner never names an app class from a kit. */
    val targetActivity: String,
    val projectIdExtra: String,
)
```

## Decisions

1. **A held frame crosses as a WEIGHT, and only as a weight.** `holds` → `weights` via
   `weightsFromDelaysMs`, so a frame held three ticks on a 12 fps board arrives as `3` and not as
   three repeats of its index. *Why:* the Studio's `FrameTrack` is a list of `Key(timeMs, cellIndex)`
   and `SequenceTiming` divides `total × weight / Σ weights`. A repeated index is a *different*
   animation that looks right at 12 fps and wrong the moment anything is resized — and, per
   JB-4.03b Q3, the **sprite-sheet** path cannot express a hold at all, so this is the only hand-off
   that can. It is also the reason this row does not use the sheet: a hold that cannot cross is a
   hold the person silently loses.

2. **`totalMs` is a VIEW of `fps` and the weights, never an input.** `fps` is the stored authority
   (the Studio's own §2) and the total is derived. *Why:* two answers to "how long is this clip" is
   how a clip's playback length stops matching its own frames after a resize. If `AnimExportPlan`
   does not already expose this, add it **there** and call it — do not compute a second one here.

3. **The hand-off format is `NNNN.png` + `NNNN.txt` + `fps` + `weights`, produced by 3.06b's
   sequence writer, not by a second writer.** `AnimExportPlan` already fixes the naming (four digits,
   from `0001`) and the manifest (`filename<TAB>delayMs`). This row adds **no file format of its
   own** and re-derives no name. *Why:* R23, and the second-writer argument from JB-3.06b Decision 1
   in a new hat. The manifest's per-frame delays are *also* written, because they are the only
   thing that makes the folder playable by something that is not this app — and the Studio's
   importer is not the only reader.

4. **"Send to Studio" is refused in words, before any frame is rendered, when the plan is not a
   GIF-able shape.** Specifically: a board with **more frames than `SequenceDetector.MAX_OFFERED`
   (600)** is refused with the count in the sentence, and a board with **one frame** is refused
   because there is nothing to loop. *Why:* the Studio's detector caps at 600 and truncates above it
   ("offering the first 600"), and a hand-off that is silently truncated is a loop that plays its
   first 600 frames forever. The cap is **read from the class**, never copied as a literal.

5. **A file is always written first, and the folder is the app's own external files directory —
   no SAF, no folder picker, no tree grant.** `getExternalFilesDir("joybrush/handoff/<name>")`.
   *Why:* the owner's rule from JB-4.03b ("a file is always written first"), plus R11's other half:
   a SAF `Uri` has no rename, so a sequence written into one is a sequence that can be left half
   written. And unlike the sprite export, **this hand-off has a single, known receiver inside the
   same app** — it is the one case in Joy Brush where asking the person for a folder would be pure
   friction. JB-4.03b Q1 stays exactly where it is; it is not this row's problem.

6. **Nothing is deleted after the Studio has read it, and nothing is overwritten silently.** The
   folder name gets a suffix when it already exists (`Walk cycle`, `Walk cycle 2`, …) and the
   arrival sentence says which one. *Why:* the alternative is a hand-off that overwrites the last
   loop the person sent, and nobody finds out until the Studio shows the wrong one.

7. **The launch is a plain `Intent` with two extras and nothing else — no `FileProvider`, no
   `ACTION_SEND`, no share sheet, no `Uri`.** The Studio reads the folder **path** because it is in
   the same app and the same process family; the blueprint's "one intent opens the receiving screen"
   is satisfied by an explicit intent. *Why:* a `content://` hand-off would need a `FileProvider`
   entry in the manifest, and the manifest is an app file the Lead's order covers for a worse
   reason. The extras are `EXTRA_PROJECT_ID` (`"faditor_project_id"`, the constant
   `FaditorEditorActivity` already declares) and one new `EXTRA_ANIM_HANDOFF` — see Q1.

8. **The receiver is where all the risk is, and this row does not write it.** The Studio must (i)
   read the folder, (ii) build a `FrameTrack` of `Key(cellIndex)` with the weights, (iii) add a
   `TrackKind.SPRITE` item to the timeline, and (iv) offer it — and `TrackKind.SPRITE`'s own javadoc
   still says *"payload not yet implemented"*. *Why:* saying so in the spec is the honest shape. A
   row that quietly assumed a working drop target would be built against a screen that does not
   exist, and the failure would surface on the owner's phone, on the `📱` row, with no trace back
   here.

9. **No GIF, no sprite sheet, no MP4, and no "also save a copy" checkbox.** "Send to Studio" sends
   the frames. If a person wants a file they have four other buttons, added by 3.06b. *Why:* one
   button, one thing, and the R11/owner precedent that the two are different decisions. Q3 asks
   whether a person who taps "Send to Studio" should be offered the file as well.

10. **A refused hand-off leaves the folder it was writing, or nothing at all — and says which.**
    Mirrors JB-3.06b Decision 14: a partial sequence is recognisable by its **absent** manifest, and
    a single-file export is never half-written. *Why:* the same reasoning, and the same lesson
    (a file that announces itself as complete and is not is the worst outcome available).

11. **A sub-range is offered, and it is the same sub-range 3.06b already plans.** The sheet shows
    "frames 3–7 of 12" and calls `AnimExport.planRange`. *Why (PROVISIONAL — I am ruling this, not
    the owner):* 3.06b's Q3 ruled *against* a range picker on the **export** sheet, because the
    playback range already exists on the board. On **this** button there is no other range picker
    and no playback chip in reach, so the same reasoning does not apply. Q3 asks the owner to
    confirm, because the two sheets are visibly similar and one of them having a picker is a
    decision, not an accident.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (weights, not repeats) | `aHeldFrameArrivesAsAWeightAndNotAsARepeatedIndex` — weights `[1, 3, 1]` for holds `[1, 3, 1]`, and the `frameNames` list has **3** entries, not 5 |
| 2 (total is a view) | `totalMsIsDerivedFromFpsAndWeightsAndIsNeverAnInput` — the handoff's constructor takes no duration; changing only `fps` changes `totalMs`, and changing only a weight changes it too |
| 3 (no second writer) | `theHandoffUsesThePlanOwnFrameNames` — the names are exactly `AnimExportPlan`'s, and `StudioHandoff`'s declared members mention no writer, no `File` and no `Uri` (reflection, JB-3.06b test 18's trick) |
| 4 (refuse in words) | `aSixHundredAndOneFrameLoopIsRefusedWithBothNumbers` and `aOneFrameBoardIsRefusedInWords` — the render callback's count is **0** in both, and the 601 case names `600` because the cap is read, not typed |
| 5 (file first, own directory) | `aFailedWriteStartsNothingAndLeavesNoManifest` · `theHandoffFolderIsTheAppsOwnAndNoSafIsEverAsked` (a fake context that **counts** SAF intents; the count is 0) |
| 6 (never overwrite) | `aSecondSendToTheSameNameGetsItsOwnFolderAndSaysWhichOne` |
| 7 (a plain intent) | `theLaunchCarriesAPathAndTwoExtrasAndNoUri` — the recorded intent's data is `null`, its `ClipData` is `null`, and its action is not `ACTION_SEND` |
| 8 (the receiver is not written here) | `noAppFileIsInTheOwnerArea` — the test reads this spec's own owner-area line and asserts it contains no `app/src/main` path. Cheap, and it is the assertion that stops a builder from "just adding the receiver" |
| 9 (nothing else) | `thereIsNoAlsoSaveACopyAndNoFormatChoice` — the sheet's offered actions are exactly `["send"]` |
| 10 (no half-hand-off) | `anInterruptedSendLeavesNoManifestAndTheFolderIsNamed` |
| 11 (the sub-range is the plan's) | `theOfferedSubRangeIsThePlanOwnSubRange` — the frames handed to `describe` are `AnimExport.planRange(board, 3, 7).frameIds`, compared against the class, not against a hand-written list |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/StudioHandoffTest.kt`

Fixture: the same 12 fps board 3.06b uses — holds `[1, 2, 1, 3]`, frames `f0..f3`, rect `(0, 0, 64, 48)`
— plus a 24 fps board with the same holds, and a 601-frame board. **Every expected weight below is
written as an expression over the plan's own `fps` and `AnimOps`, never as a typed integer**, and
the fixture's KDoc says why (JB-3.05a Decision 1, JB-3.06b Decision 2 — one rounding rule).

1. `aHeldFrameArrivesAsAWeightAndNotAsARepeatedIndex`: holds `[1, 2, 1, 3]` at 12 fps → weights
   `[1, 2, 1, 3]` (each hold is a whole number of ticks at 12 fps — 83 ms ≈ 1, 167 ≈ 2, 250 ≈ 3);
   `frameNames.size == 4`; and the total is `7`, not `4`. The test's comment names the bug this
   prevents: "four frames expanded to seven lines of JSON, which is not what a hold is".
2. `theSameHoldsAtTwentyFourFpsAreDoubleTheWeights`: the same holds at 24 fps → `[2, 4, 2, 6]`,
   `totalWeight == 14`. **This is the case that fails if anyone divides by 1000.**
3. `aFractionalTickRoundsTiesAwayFromZeroAndNeverReachesZero`: at 3 fps, delays `[333, 667]`
   → weights `[1, 2]`; over fps 1..60 × hold 1..50 — **3 000 cases, every weight ≥ 1**, and no
   `0` anywhere. The `0` is the one that would delete a frame from the Studio's loop.
4. `totalMsIsDerivedFromFpsAndWeightsAndIsNeverAnInput`: `StudioHandoff`'s primary constructor
   parameters are read by reflection and the set is exactly
   `{folderName, frameNames, weights, fps, clipName}` — **there is no duration parameter**, so a
   second answer to "how long" cannot be passed in. Then: doubling `fps` halves `totalMs`, and
   doubling one weight raises it.
5. `theHandoffUsesThePlanOwnFrameNames`: `frameNames == plan.frameIds.map { "%04d.png".format(i + 1) }`
   — i.e. **the same list 3.06b's Decision 8 fixes**, computed here by calling the plan's own rule
   and compared name by name. And the reflection assertion: `StudioHandoff`'s and `StudioHandoff`'s
   companion's members mention no `File`, no `Uri`, no `TileSource`, no `ByteArray`.
6. `aSixHundredAndOneFrameLoopIsRefusedWithBothNumbers`: 601 frames → the message contains `601`
   and `600`, and names the frames. `aOneFrameBoardIsRefusedInWords`: the message says there is
   nothing to loop. **Both are checked against `SequenceDetector.MAX_OFFERED` read from the class
   where it is reachable and against a named constant in `:core` otherwise** — and whichever way it
   is done, the test fails if the number is typed twice in two places (reflection over the two
   files' text, the trick of JB-3.08 test 19).
7. `weightsAndNamesOfDifferentLengthsAreRefused`: `weights` shorter and longer than `frameNames`,
   a `0` weight, a `0` fps, a `NaN` fps, an empty `frameNames` — all `IllegalArgumentException`
   with the reason in the message. **Never padded and never truncated** (Decision 1's companion).
8. `theClipNameIsTheBoardNameThroughThePlanSanitiser`: `"../evil"`, `"a/b"`, `"C:x"`, `""` all
   produce a `clipName` that `AnimExport.safeBaseName` accepts unchanged — asserted by calling the
   plan's own sanitiser on the result and checking it is a fixed point, **not** by comparing to a
   hand-typed string.
9. `theArrivalSentenceNamesTheLoopTheFramesAndTheLength`: for a 4-frame 7-tick loop the sentence
   contains the clip name, `4` and the total ms; and for a refused hand-off it is a **different**
   sentence that says what was wrong. The two are asserted to be different strings, because one
   sentence reused for both is how a refusal gets read as a success.

`joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/StudioHandoffRunnerTest.kt`
(a fake `TileSource`, a fake launcher, a fake SAF-counting context, a temporary directory)

10. `aFailedWriteStartsNothingAndLeavesNoManifest`: the writer throws at frame 7 of 12 → the
    launcher's count is **0**, the folder holds 6 PNGs and **no** `NNNN.txt`.
11. `theHandoffFolderIsTheAppsOwnAndNoSafIsEverAsked`: the fake context's SAF-intent counter is
    **0** after a whole successful send, and the folder's absolute path starts with the context's
    external-files directory.
12. `aSecondSendToTheSameNameGetsItsOwnFolderAndSaysWhichOne`: two sends of `"Walk cycle"` → two
    different folders, and the second arrival sentence contains `"Walk cycle 2"`.
13. `theLaunchCarriesAPathAndTwoExtrasAndNoUri`: the recorded intent has `data == null`,
    `clipData == null`, `action != ACTION_SEND`, and exactly the two extras named in Decision 7 —
    **asserted as a set, so a third extra fails**.
14. `thereIsNoAlsoSaveACopyAndNoFormatChoice`: the sheet's action list is exactly `["send"]`.
15. `theOfferedSubRangeIsThePlanOwnSubRange`: with the sheet showing `3–7 of 12`, the frames handed
    to `describe` are `AnimExport.planRange(board, 3, 7).frameIds` — the class's answer, not a list.
16. `noAppFileIsInTheOwnerArea`: reads this spec's own header table and asserts the owner-area row
    contains no `app/src/main` path. It is a silly test and it is the one that stops Step 6 from
    quietly happening.
17. **Non-vacuity the builder must run and paste.** (a) Change Decision 1 to expand a hold into
    repeated indices and watch tests 1 and 2 go red. (b) Add a `totalMs` constructor parameter and
    watch test 4's reflection assertion go red — the parameter is exactly how a second answer gets
    in.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Steps

1. Read Q1. **If the Lead has not scheduled the receiving half, do steps 2–5 and stop** with the row
   at `🟧 Built — Joy Brush half` and this spec's §Definition of done half ticked.
2. `StudioHandoffTest.kt` **first**, in full.
3. `StudioHandoff.kt` until case 9 is green. If `AnimExportPlan` has no `totalMsForFps`, **add it
   there** (that file is 3.06b's; see Q2) and call it.
4. `StudioHandoffRunnerTest.kt`, then `StudioHandoffRunner.kt`, until case 16 is green.
5. `SendToStudio.kt`: the button, the sheet with the frame count, the length, the sub-range offer and
   the launch. **This row does not add the button to `JoyBrushActivity`** (Step 6 / Q4).
6. **The gate.** The receiving half is not in this row's owner area and cannot be (Q1). The row is
   complete only when a `FaditorEditorActivity` intent extra `EXTRA_ANIM_HANDOFF` exists, the folder
   is read, a `FrameTrack` is built from the weights, and a `TrackKind.SPRITE` item lands on the
   timeline. Until then the arrival sentence must not be shown to anyone (Decision 10's discipline
   applied to the button: if the launch cannot be honoured, **say so before the write** — JB-4.03b
   Decision 14, verbatim).

## Do not

- Do not write a GIF, an MP4, a sprite sheet or a `.sprite.json` as the hand-off. They are exports
  (3.06b), and the sheet cannot carry a hold at all (JB-4.03b Q3, and the ROADMAP's JB-4.02 note).
- Do not re-derive a frame's length, a delay's rounding, a file name or a sanitiser. 3.06b owns all
  four; ask it.
- Do not hold a copy of `SequenceDetector.MAX_OFFERED` as a literal. Read it, or name one constant
  in `:core` and pin the equality with a test that fails if the two files disagree.
- Do not implement the Studio receiver "just to finish the row". That is `FaditorEditorActivity`,
  the timeline model and `ProjectStorage` — app files, on the Lead's serialised order, in a
  27 000-line file, and a product decision (Q1).
- Do not ask for a folder, a tree grant, a SAF `Uri` or a `FileProvider`. Decision 5 and Decision 7.
- Do not add a `FileProvider` to the manifest, and do not touch the manifest at all.
- Do not add a `Clip.weights` field, change `SequenceTiming`, or change `TrackKind`. Those are the
  Studio's, they are reviewed, and Q1 is the Lead's.
- Do not offer "also save a copy", a format choice, a scale, a background or a share intent (Q3).
- Do not edit `JoyBrushActivity.kt` (Q4), `AnimOps`, `PlaybackClock`, `GifEncoder`, `PngWriter` or
  any app file. No new dependencies.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (repeated indices → tests 1 and 2 red; a `totalMs` parameter →
      test 4 red)
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin`
      EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the four owner-area paths
- [ ] **the Lead has scheduled the receiving half** (Q1) — until this is ticked the row is
      **half-built**, not built, and says so in the ROADMAP
- [ ] **owner check, Note 9:** a 6-frame loop with one frame held three ticks → Send to Studio →
      the Studio opens with the loop on a SPRITE track; the held frame visibly dwells; resizing the
      clip's length does not change the loop's timing; and the arrival sentence names the clip
- [ ] committed `JB-3.07: send to Studio`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The hand-off is specified, pinned and
buildable today. The receiving half is a real missing feature and it is the Lead's.)_

### Q1 — 🔴 for the Lead: the Studio has no drop target, and building one is a Studio feature

I read the receiving code. Four facts, and together they mean "drop it on the timeline" is a feature
that does not exist yet:

1. **`SequenceDetector`'s own governing instruction is "OFFER, never assume."** Its class javadoc
   names the exact failure it refuses to commit — a camera roll of `IMG_0001…IMG_0400` matches the
   pattern perfectly and is not a sequence — so it only ever produces a `Candidate` for a person to
   accept, and `SequenceImportDialog` is that conversation. **There is no programmatic import path,
   and adding one means adding a way to skip a check that was written on purpose.**
2. **`SequenceDetector.MAX_OFFERED` is 600**, and a longer run is *truncated* ("offering the first
   600"). A hand-off above that would arrive as a loop playing its first 600 frames forever — which
   is why Decision 4 refuses it in words rather than letting it through.
3. **The timing model is `fps` + per-frame `weights`** (`SequenceTiming`: `frame_duration = total ×
   weight / Σ weights`, `fps` the stored authority, total duration a derived view). Good news: it
   carries a hold exactly, and Decision 1 uses it. Bad news: it is not what 3.06b's manifest holds,
   so the importer needs a second reader.
4. **`TrackKind.SPRITE` — "a keyframed sprite layer" — still says `payload not yet implemented` in
   its own javadoc.** `FrameTrack` and `LayerRowRenderer`'s frame-swap diamonds exist, so the
   rendering is there; the *payload* that builds a `FrameTrack` from an imported folder is the gap.

**What I need scheduled and ruled:** the receiving half, as its own row, T1 review, app-file work
under the serialised order. It needs to cover (i) read the folder, (ii) build the `FrameTrack` from
the weights, (iii) add the `SPRITE` item, and (iv) **what it does about the "offer, never assume"
rule** — because a Joy Brush folder is named by us, written by us, and is not a camera roll, so
skipping the offer is defensible, but skipping it *in general* is not, and the difference has to be
written down rather than implied. **My recommendation:** the offer dialog is skipped **only** for a
folder that carries the manifest this row writes (which is proof of provenance, not a guess), and is
kept for every other folder. That is a real product decision about someone else's screen, and it is
not mine.

**And the product question inside it, which I have deliberately not answered:** does the loop join
**the project the person is currently in**, or does it arrive as a **new project**? The Studio's
timeline lives inside a project (`SpriteSheetEditorActivity` `finish()`es with a toast when
`EXTRA_PROJECT_ID` names nothing — JB-4.03b Q2), and a Joy Brush drawing is not a Faditor project.

### Q2 — for the Lead: `AnimExportPlan` needs a `totalMs`-equivalent, and it is 3.06b's file

Decision 2 wants the hand-off's length to be a *view* of `fps` and the weights, computed once. The
Studio's formula is `fps = Σweights / total_seconds` inverted, and 3.06b's `AnimExportPlan` has
`totalMs` as a **property of its own `delayMs` list**. Two files, two derived lengths, one concept.
**I have ruled "3.06b owns it and this row calls it"** rather than compute a second one, which means
this row needs a one-line addition to a file another row owns. Say the word and I will write it as
3.06b's work; or tell me to keep it local and I will, with a test that pins the two against each
other so they cannot drift.

### Q3 — for the owner (small): "Send to Studio" — and the file?

Three things I ruled out and would like confirmed or overturned:

1. **No "also save a copy" checkbox.** The hand-off is into the app's own folder, invisible to the
   person, so someone who wants the loop as a file has to press Export separately. I think that is
   right (one button, one thing). If you would rather "Send to Studio" also dropped the GIF in their
   Pictures, that is a second format decision and it re-opens 3.06b's Q1.
2. **A sub-range IS offered on this sheet** (`frames 3–7 of 12`), where 3.06b's export sheet has no
   range picker. That is not an inconsistency I introduced — it is the same reasoning applied to two
   different sheets — but two visibly similar sheets behaving differently is a decision, so I am
   flagging it as mine.
3. **The loop arrives playing, and the playhead is not moved.** The arrival sentence names the clip
   so the person can see it landed. Should the Studio's playhead jump to the new clip instead? I
   ruled no: moving someone's playhead is the Studio's business, and the sentence is honest either
   way.
