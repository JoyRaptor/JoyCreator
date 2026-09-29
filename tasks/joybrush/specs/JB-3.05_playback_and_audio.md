# JB-3.05 — Playback: the transport, the frame loop, and one audio track

| | |
|---|---|
| **Tier** | T2 (the loop and the audio contract are pure `:core`; the transport buttons are a thin view) |
| **Status** | 🟨 **Draft.** The **playback half is complete and a T2 builder can build and test it today.** The **audio half cannot be written at all**: `JbDocument`/`Board` have **no audio field** and `JbContents` carries **no audio bytes**, so there is nowhere for a track to live — and putting one there is a document-format change, which R3 governs and which belongs to JB-0.02's owner area, not this row's. See **Q1**. The file is written as one spec with the two halves separated so the split is a copy-and-paste if you want it. |
| **Needs** | 3.03, 3.05a (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FrameStepper.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FrameStepperTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/anim/PlaybackController.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the play/pause pill and the loop-mode chip — nothing else in that file) |
| **Estimated size** | ~200 lines of Kotlin in `:core` + ~280 lines of tests, ~200 lines of Android |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` green. |
| **Naming** | The ROADMAP row is **JB-3.05**. JB-3.05a's Questions §4 calls this row "JB-3.05b" (JB-3.05a being the clock). **One name only — the file is the ROADMAP row's number.** A builder reading 3.05a's spec will see "3.05b" and should read this file. |

## The trap this spec is built around

`PlaybackClock.nextChangeMs` is, at a backward boundary of a PING_PONG leg, the **infimum** of the
times at which the frame differs — not a minimum. The set is an open interval with no smallest
member, so the boundary itself is what the function hands back. JB-3.05a's Questions §4 says exactly
this and says the consequence:

> A player that sleeps until `nextChangeMs(t)` and then asks again without comparing frames will
> spin at every backward boundary.

That is why the row below is not "a player loop". It is a **pure function whose signature makes the
frame comparison impossible to forget**, and its test is written so that the spin is a failing test
rather than a hang on a phone.

## Contract

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.Board

/**
 * What the screen must DO, at one instant, given what is currently on it. Pure: no clock, no
 * thread, no Android, no sound. A player that owns one of these and a Handler is a player; a
 * player that owns arithmetic is a bug waiting for a review.
 */
sealed class Step {
    /** Put frame [index] on screen. Emitted ONLY when it differs from what [FrameStepper.step] was told is up. */
    data class ShowFrame(val index: Int) : Step()

    /**
     * The sound must be at [boardMs] of the BOARD's timeline, or SILENT when null.
     *
     * This is `PlaybackClock.audioPositionMs` verbatim, and it is emitted on every change of that
     * value with **no tolerance, no smoothing and no interpolation anywhere in this file** — see
     * Decision 6. The device side decides whether a change of 0.4 ms is worth a `seekTo`.
     */
    data class Audio(val boardMs: Double?) : Step()

    /** ONCE has reached the end of its range. The last frame stays up. */
    object Finish : Step()

    /** Nothing changed. The overwhelmingly common answer, and it is not an error. */
    object Nothing : Step()
}

/**
 * Playback as a PURE FUNCTION of elapsed time.
 *
 * [step] takes the frame that is CURRENTLY on screen and never emits [Step.ShowFrame] for it. That
 * one parameter is the whole of the fix for the backward-boundary spin: a caller cannot ask "what
 * should I draw?" without also saying "here is what is drawn", so there is no code path in which
 * "something changed" is decided without comparing it to something.
 *
 * IMMUTABLE, like the clock. Changing the board, the range, the mode or the speed builds a new
 * clock and a new stepper, which restarts playback at 0 — the clock's own KDoc promises this and
 * this class does not work around it.
 */
class FrameStepper(val clock: PlaybackClock) {

    /** [frameIndex] = the frame showing. Rejected (in words) unless it is a frame of the range. */
    fun step(elapsedMs: Double, frameIndex: Int, audioAtMs: Double?): List<Step>

    /** The frame the clock says is up at [elapsedMs], whatever is on screen. For the strip's marker. */
    fun frameOnScreen(elapsedMs: Double): Int

    /**
     * How long until the next step that would EMIT something, wall ms — a floor for the handler's
     * delay, not a schedule. A caller that sleeps until this and then calls [step] again is
     * correct; a caller that treats it as "the frame changes here" is not, and at a backward
     * boundary the difference is the spin.
     *
     * Clamped to [MAX_SLEEP_MS] so a stopped or one-frame clock cannot park a Handler forever.
     */
    fun nextWakeMs(elapsedMs: Double): Double

    companion object {
        /** The longest a player may sleep before looking again, wall ms. 250 = the tap threshold. */
        const val MAX_SLEEP_MS: Double = 250.0
    }
}
```

## Decisions

1. **`step` takes the frame that is on screen, and emits `ShowFrame` only for a DIFFERENT one.**
   This is the entire reason this class exists. *Why:* at a backward boundary `nextChangeMs` is an
   **infimum** (JB-3.05a's Decision 3 and its Questions §4), so a player that wakes on it, draws
   unconditionally, and re-asks will draw the same frame and re-ask at the same time, for ever.
   Putting the comparison in the signature means there is no version of this player that can spin.
2. **`step` is idempotent: `step(t, i, a) == step(t, i, a)` for any `t`, `i`, `a`.** Same inputs, same
   output, no hidden state, no accumulated "last frame". *Why:* a stepper with hidden state cannot be
   tested against a table of expected timelines, and a table of expected timelines is the only thing
   that catches a seam.
3. **`ShowFrame` comes first in the returned list, then `Audio`, then `Finish`, then `Nothing`.** A
   caller that draws before it sounds never shows a picture a frame ahead of its sound.
   *Why:* the sound is the thing a person notices late, so it is the thing to be right about last.
4. **`Finish` is emitted only in `ONCE`, only once, and only while the last frame stays up.** After
   it, `step` returns `Nothing` forever (LOOP and PING_PONG never finish; `isFinished` says so).
   *Why:* the transport has to know to stop, and a player that emits `Finish` every tick is a player
   that toasts.
5. **`Audio` carries `clock.audioPositionMs(elapsed)` EXACTLY — including the corrected PING_PONG
   formula.** On the forward leg it is `rangeStartMs + cyclePos`; on the backward leg it is **null**.
   It is **never** `rangeStartMs + (elapsed × speed mod rangeMs)`, which was Decision 5 as first
   written in JB-3.05a and was **wrong**: those two agree only inside the first cycle, so the sound
   desyncs from the picture after one loop. *Why:* I am restating a correction rather than trusting
   that everyone read the correction.
6. **No epsilon, no smoothing, no `±` anywhere in the emitted value.** The stepper emits the clock's
   number, and the DEVICE decides whether a 0.4 ms difference is worth a `seekTo` — that tolerance
   is a playback-engine fact (`MediaPlayer.seekTo` has millisecond granularity and a cost), not a
   timing fact, and putting it here would put a playback tolerance into the one function whose whole
   claim is that it never drifts. *Why:* the same reasoning JB-3.05a used to refuse a `seam − ε`.
7. **`nextWakeMs` is a FLOOR, never a schedule, and never `nextChangeMs` itself.** It is
   `min(clock.nextChangeMs(elapsed), elapsed + MAX_SLEEP_MS)`, and `MAX_SLEEP_MS` is 250 — the app's
   own tap threshold (JB-2.02), chosen so a Handler can never be parked past the point where a
   person would tap Stop. *Why:* `nextChangeMs` is `∞` for a one-frame range and for a finished
   ONCE clock, and a Handler posted to `∞` never runs again; a floor with a cap has no such state.
8. **Pressing Play while at the end starts from the beginning** (owner, R18, 2026-09-29). The
   transport therefore builds a **new** `PlaybackClock` and a new `FrameStepper` on every press, so
   elapsed restarts at 0 — including when the person paused at the last frame, and including in
   `ONCE` (which would otherwise be un-replayable). *Why:* R18 is the owner's own answer and it is
   the only sensible one for a play button.
9. **Pressing Play while PAUSED mid-range resumes from the frame on screen, not from 0.** The
   clock is built with `firstFrame = <the frame showing>`; `lastFrame`, `mode` and `speed` are
   unchanged. *Why:* resuming is what pause means. (This is the one asymmetry with Decision 8 and it
   is deliberate: "at the end" is a position, and the end is the only position with no next frame.)
10. **Scrubbing the strip while playing PAUSES, and does not resume on lift.** A finger on the
    strip is a person steering; the strip is the authority for where the playhead is and the player
    is the authority for what time it is, and two authorities over one playhead is how they drift
    apart. *Why:* and JB-3.03 Decision 2 says the strip never consults the clock, so the pause has
    to come from this side or nowhere.
11. **The loop-mode chip is `LOOP / PING_PONG / ONCE`, in that order, wrapping, and it is the ONLY
    place the mode changes.** Changing it while stopped rebuilds the clock. Changing it while
    playing **stops first and does not auto-resume**. *Why:* a mode change is a range change, and
    `PlaybackClock` is immutable by design (Decision 8's reasoning). Auto-resume would be a new
    behaviour nobody asked for.
12. **A speed control is NOT in this spec.** `PlaybackClock` takes `speed` and the contract is
    settled, but nothing in the blueprint asks for a speed slider, and adding one means deciding
    where it lives in a chrome that does not exist yet. The constructor parameter is honoured the
    day someone supplies one. *Why:* not shipping an undesigned control.
13. **The transport is ONE play/pause pill and ONE loop-mode chip, docked to the film strip's left
    end.** Not on the peg bar (JB-3.02's five pegs are fixed and do not include these) and not in a
    drawer. *Why:* the blueprint wants the transport next to the thing it transports, and the peg
    bar's contents are frozen by JB-3.02 Decision 1 — this is the note that keeps the two specs from
    each claiming the same pixels.
14. **The play/pause pill is `studio_action_pill` (aqua→lime) while playing and `jb_raised` with a
    `jb_line` ring while paused**, and it is the one saturated control on the board. The loop chip
    wears the board gradient. *Why:* D.01's rule and the visual language's "one saturated control per
    screen" — and the peg bar's own `PLAY` peg is a *different* control on a *different* row; if both
    end up visible at once there are two saturated controls, which is a design bug the Lead should
    see rather than a builder should resolve. See Q2.
15. **The whole loop runs on a `Handler(Looper.getMainLooper())`**, posting itself with
    `nextWakeMs` as the delay, and it is torn down in `onPause` and rebuilt in `onResume` with the
    playhead preserved and playback PAUSED. *Why:* a Handler that survives a backgrounded screen is
    a Handler drawing to a surface that is not there. (This is the same discipline as
    `JoyBrushActivity`'s GL-thread pause, for the same reason.)

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (comparison in the signature) | `aFrameAlreadyOnScreenIsNeverEmittedAgain`, `theStepperCannotEmitTheFrameItWasToldIsUp` |
| 2 (idempotent) | `stepIsAFunctionOfItsArgumentsAlone` — 5 000 random triples, each twice, equal lists |
| 3 (order) | `thePictureIsDrawnBeforeTheSoundIsMoved` |
| 4 (`Finish` once, ONCE only) | `finishIsEmittedExactlyOnceAndOnlyForOnce`, `afterFinishOnlyTheLastFrameIsUp` |
| 5 (corrected audio) | `theSoundIsTheCorrectedFormulaAndNotTheModulo` — the two disagree from cycle 2 and the test says so |
| 6 (no epsilon) | `theEmittedAudioIsBitIdenticalToTheClock` — exact equality, no tolerance anywhere in the file |
| 7 (`nextWakeMs` a floor) | `nextWakeNeverParksTheHandlerForever` (one-frame range, finished ONCE), `nextWakeIsTheClocksAnswerCappedAt250` |
| 8 (play at the end restarts) | `pressingPlayAtTheEndStartsFromTheBeginning` |
| 9 (pause resumes) | `pressingPlayMidRangeResumesFromTheFrameOnScreen` |
| 10 (scrub pauses) | `aScrubDuringPlaybackPausesAndDoesNotResume` |
| 11 (mode chip) | `theModeChipWrapsInOrderAndStopsPlaybackOnAChange` |
| 12 (no speed control) | `noSpeedControlIsOffered` — the transport's action list contains no speed action |
| 13 (docked to the strip) | `theTransportIsTwoControlsAndNotOnThePegBar` |
| 14 (one saturated control) | `onlyTheTransportWearsTheActionGradient` |
| 15 (Handler lifetime) | `theLoopIsTornDownWithTheScreen` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/FrameStepperTest.kt`

**The two rules for this file, before anything else** (both are JB-3.05a's, and they apply here
unchanged):

1. **Every boundary is derived by indexing `AnimOps`, never by accumulating `hold × 1000 / fps` a
   second time.** `2 × (1000.0 / 12.0)` is not bit-identical to `2000.0 / 12.0`.
2. **`frameStartsMs` returns one start per frame and no trailing end.** The end of a board is
   `totalDurationMs`. Use the same `edge(k)` accessor `PlaybackClockTest` uses.

Fixture: the same 12 fps board with holds `[1, 2, 1, 3]` and ids `f0..f3`, plus an eight-frame uneven
board `[1,2,1,3,2,1,2,1]` for the ping-pong work.

1. `aFrameAlreadyOnScreenIsNeverEmittedAgain`: for every board edge `e` and every frame `i` in the
   range, `step(e, i, null)` contains **no** `ShowFrame` naming `i`. Structure, not a count.
2. `theStepperCannotEmitTheFrameItWasToldIsUp`: same as 1 over 2 000 random `(t, i, a)` triples.
3. **`theStepperDoesNotSpinAtABackwardBoundary` — the test this row exists for.** On a six-frame
   board in PING_PONG, walk the whole cycle in 1 ms steps carrying the shown frame forward exactly
   as a player would (`shown = the last ShowFrame, else the initial one`). Assert:
   - the walk **terminates** (it is a bounded `for`, so this is about the *count*, below);
   - `ShowFrame` is emitted at most `2n − 2` times per cycle (the number of real slots), **not**
     once per step;
   - the sequence of emitted indices is exactly `0 1 2 3 4 3 2 1 0 …` with **no repeats**;
   - and `nextWakeMs` returns a value **strictly greater** than the current elapsed at every step
     where the frame does not change, so a player sleeping on it always makes progress.
   The failure mode this catches is a stepper that emits on every call, and its symptom in the
   field is a phone at 100% battery playing a two-frame animation.
4. `stepIsAFunctionOfItsArgumentsAlone`: 5 000 random triples, `step` called twice with each, the
   two lists equal. No hidden state, no "last frame" field.
5. `thePictureIsDrawnBeforeTheSoundIsMoved`: at a forward boundary where both fire, the list is
   `[ShowFrame, Audio]` in that order — asserted as an index comparison, not as a string.
6. `theSoundIsTheCorrectedFormulaAndNotTheModulo`: on a PING_PONG clock, for `elapsed` in the
   second cycle, the emitted `Audio.boardMs` equals `rangeStartMs + cyclePos` **exactly**, and a
   hand-written `rangeStartMs + (t mod rangeMs)` is asserted to **differ** (it does, from cycle 2 on
   — and the comment says where the two agree, which is the first cycle only). On the backward leg
   the emitted value is `null`, from the seam onward.
7. `theEmittedAudioIsBitIdenticalToTheClock`: for 3 000 probes across both legs, three speeds and a
   sub-range, `emitted == clock.audioPositionMs(t)` with **no tolerance argument at all**. If anyone
   adds an epsilon this test fails, which is the point.
8. `finishIsEmittedExactlyOnceAndOnlyForOnce`: an ONCE clock past its end emits `Finish` on the
   first `step` and `Nothing` on every one after. A LOOP and a PING_PONG clock never emit it, at
   3 600 001 ms or anywhere else.
9. `afterFinishOnlyTheLastFrameIsUp`: `frameOnScreen` is the last frame at every `t` past the end.
10. `nextWakeNeverParksTheHandlerForever`: a one-frame range and a finished ONCE clock both return
    `elapsed + MAX_SLEEP_MS`, not `∞`. Assert the value, not merely "finite".
11. `nextWakeIsTheClocksAnswerCappedAt250`: inside a frame, `nextWakeMs` equals
    `clock.nextChangeMs(t)` exactly; when that is more than 250 ms away it is exactly
    `t + 250`.
12. `pressingPlayAtTheEndStartsFromTheBeginning`: at the last frame in every mode, a fresh
    `FrameStepper` built the way Decision 8 says puts frame 0 on screen at `elapsed = 0`, and
    `ONCE` — which has already finished — plays again rather than reporting `Finish` immediately.
13. `pressingPlayMidRangeResumesFromTheFrameOnScreen`: a clock built with `firstFrame = 2` shows
    frame 2 at 0, and its `rangeMs` is the remainder, so the last frame is still the last frame.
14. `aScrubDuringPlaybackPausesAndDoesNotResume`: the transport's state after a scrub is PAUSED,
    and one `nextWakeMs` later it is STILL paused — the assertion is on the *absence* of a
    `ShowFrame` without a new press, which is what "does not resume" means operationally.
15. `theModeChipWrapsInOrderAndStopsPlaybackOnAChange`: the action list of the loop chip is exactly
    `[LOOP, PING_PONG, ONCE]` in that order, tapping ONCE wraps to LOOP, and every tap while playing
    emits a `Stop` **before** the new clock's first step.
16. `noSpeedControlIsOffered`: the transport's action list contains no speed action, and
    `FrameStepper`'s declared API contains no `speed` setter. A test that can be failed by adding
    the control.
17. `theTransportIsTwoControlsAndNotOnThePegBar` / `onlyTheTransportWearsTheActionGradient` /
    `theLoopIsTornDownWithTheScreen`: asserted against the Android layer's own reported control list
    and lifecycle, so they are checks on a contract and not on a comment.

**Non-vacuity proof the builder must run and paste:** delete the `if (index != frameIndex)` guard
from Decision 1, run the suite, watch test 3 fail, put it back. Then **delete the `min(250.0, …)`
from Decision 7** and watch test 10 fail. A seam test that has never been seen to fail is a
suspicion, not a test.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not sleep on `nextChangeMs` and draw unconditionally.** That is the spin. `nextWakeMs` is a
  floor, and the comparison lives in `step`'s signature.
- Do not re-derive `holdFrames * 1000.0 / fps`. Index `AnimOps`.
- Do not put an epsilon, a tolerance or a `±` into anything `PlaybackClock` produced.
- Do not reintroduce `rangeStartMs + (t mod rangeMs)` for PING_PONG. It was wrong as written and it
  is corrected in JB-3.05a; the correction is in Decision 5 above precisely so it cannot be.
- Do not add a speed control, a frame counter, a loop-count or a "reverse" button (Decision 12).
- Do not add a second audio track, a volume control or a mute. One track, no mixing.
- Do not touch `DocModel.kt`, `DocJson.kt`, `JbArchive.kt` or `DOC_VERSION`. That is Q1, and it is
  the Lead's.
- No new dependencies. `Handler`/`Looper` are the platform; `MediaPlayer` is the platform.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (remove the guard → test 3 red; remove the cap → test 10 red)
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] **device check, owner, Note 9:** a 6-frame ping-pong with a sound plays, the sound is silent
      on the way back, and Home → reopen leaves it paused on the frame it was on
- [ ] committed `JB-3.05: playback`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The loop is complete and pinned.
The audio track has nowhere to live, and that is the whole of Q1.)_

### Q1 — for the Lead, and it blocks the audio half outright: **the document has no audio field**

The row says "Playback **+ an audio track**". I have read `DocModel.kt` and `JbArchive.kt` line by
line looking for where one lives. There is nowhere:

- `Board` is `id, name, kind, rect, clipToBoard, fps, frames, grid`. **No audio.**
- `JbContents` is `doc, tiles, strokes, thumbnailPng`. **No audio bytes, and no path to any.**
- `JbArchive`'s entry table is `mimetype / document.json / layers/… / strokes.jbs / thumbnail.png`.
  **No audio entry, and no `MAX_AUDIO_BYTES` bound.**

So "add an audio track" is a **document-format change**, and R3 is explicit that a serialised change
carries a version obligation, and `DocJson.kt` / `JbArchive.kt` / `DocModel.kt` are **JB-0.02's and
JB-0.08a's owner area**, not this row's. I will not edit them from here, and I will not write a spec
that pretends the track can be attached to a path that is not in the model.

**What a ruling needs to cover — six things, and I have thought about each:**

1. **Where does the reference live?** `Board.audioPath: String?` (a path inside the `.joybrush`
   zip, beside `strokes.jbs` in the model's own idiom) is the obvious answer and I would take it. It
   also means the file is **inside the archive**, so a track survives a copy of the drawing — which
   is the whole point of the archive and matches how ink strokes are stored.
2. **What is the entry, and what bounds it?** `audio/<something>.<ext>` stored or deflated, with a
   `MAX_AUDIO_BYTES` next to `MAX_STROKES_BYTES`. The archive's own rule 2 says no allocation is
   sized from a number the archive declared, so the bound is mandatory, and the question is only its
   value. A 5 MB track is generous; 50 MB is a drawing that no longer fits in memory to open.
3. **Does adding the field bump `DOC_VERSION`?** R3's letter is about enum constants; a new optional
   field with a default is the additive case the archive already handles (it reads unknown keys and
   tolerates them). **My reading: no bump is needed, and I would rather have the Lead say so in
   writing than have me guess**, because the answer changes what an old build does with a new file.
4. **Which formats, and is transcoding allowed?** Decoding to play needs a decoder; Android has
   `MediaPlayer` for anything the device supports and Joy Brush has no codec of its own. My
   inclination is **store what the person picked, play it with `MediaPlayer`, and refuse in words a
   container the device cannot open** — never silently transcode, never silently drop (the house
   rule). But "the device cannot open it" is a runtime answer, so the refusal has to happen at play
   time with a sentence, not at open time.
5. **Does the track belong to the BOARD or to the DOCUMENT?** A board, in my reading — a document
   with two animation boards and two tracks is a real thing and the model has room for it. But
   `Board` is a value class copied by every operation, and a `String?` in it is nothing; if the
   answer is "document", the field goes on `JbDocument` and every board shares one track.
6. **Is the track copied into the `.joybrush`, or referenced?** Copy, per 1 — otherwise a document
   that opens on another device has a dead reference, which is the "refuse rather than half-open"
   rule in JB-0.08b's own vocabulary.

**My recommendation, for what it is worth:** open **JB-0.02c — audio in the archive** (T1, owner area
`DocModel.kt` + `DocJson.kt` + `JbArchive.kt` + its tests, `DOC_VERSION` ruling included) and make
JB-3.05 depend on it. Then this row's audio half is a `MediaPlayer` and a `seekTo`, which is what it
should have been.

### Q2 — for the Lead: there are now two Play controls on the animation board

JB-3.02 Decision 1 freezes the peg bar at five pegs and Play is one of them; Decision 14 of this
spec puts a play/pause pill on the film strip. Both are live at once, and the visual language's rule
is **one saturated control per screen**. I ruled the transport pill to be the saturated one and the
peg to wear the board gradient, but that is me picking between two controls the Lead put there.

1. **Should the peg bar's `PLAY` peg be dropped** (which means amending JB-3.02's frozen list), or
   **should the strip's transport be dropped**? My recommendation is to drop the peg's, because the
   transport belongs beside the thing it transports — but the peg bar was the owner's own idea and
   the pegs are supposed to be *the* buttons.
2. Related: **should the loop-mode chip live on the peg bar** for the same reason? If the peg bar is
   the button row, `MODE` is already one of its five pegs, and a second mode control is a duplicate.

I have written the spec assuming the peg bar keeps `PLAY` and `MODE` and the strip keeps **only**
prev/next, because that is what "the strip is the transport" means in every other app. Say if you
want it the other way and it is a two-line change here.

### Q3 — for the Lead, and it is a small one I did not want to decide alone

**A ping-pong with a sound is silent for half of every cycle.** That is Decision 5 and it is
JB-3.05a's ruling, and it is right ("sound played backwards is never wanted"). But a person who
records a two-step footstep and plays it ping-pong will hear a click at the seam every cycle, because
the audio is paused and resumed at a non-zero offset with no crossfade.

**Options: (a) accept the click** (the current ruling, and correct); **(b) ramp the volume to zero
over the last 20 ms of the forward leg and back up over the first 20 ms of the next** — a device-side
ramp, not a change to the clock; **(c) do not offer a sound at all in PING_PONG.** I ruled (a),
because a ramp is a design decision about how the app sounds and this project does not make those in
a spec's Decisions. But you should know the click is there.
