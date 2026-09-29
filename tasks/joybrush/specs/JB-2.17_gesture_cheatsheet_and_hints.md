# JB-2.17 — The gesture cheat-sheet and the first-run hints

| | |
|---|---|
| **Tier** | T2-V |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.02 (`CanvasGestures` — Built 🟧) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/tool/CheatSheet.kt`; NEW `.../commonTest/.../tool/CheatSheetTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/HintView.kt`; EDIT `joybrush-android/.../JoyBrushActivity.kt` (a "?" affordance and the first-run wiring) |
| **Estimated size** | ~140 lines of core + ~130 lines of tests; ~180 lines of view + wiring |

## Goal

Blueprint §3.5: *"A gesture cheat-sheet exists **because users never find gestures by accident**."*

That sentence is the whole spec. Every other app in the research makes people read a help page; this
one exists because the alternative is a feature nobody finds. And a cheat-sheet that is WRONG is
worse than none: a person reads "two-finger tap undoes", tries it, it does something else because
JB-2.02b reassigned it, and now the app is lying to them in a place they went specifically to trust it.

## Contract

```kotlin
package cc.joycreator.joybrush.core.tool

import cc.joycreator.joybrush.core.brush.BrushPreset

/** One row of the sheet. [fingers] is 1..5; [gesture] names it the way a person would. */
data class CheatRow(
    val fingers: Int,
    val gesture: Gesture,          // TAP, DRAG, TWO_FINGER_DRAG, THREE_FINGER_DRAG
    val what: String,               // "Undo", "Redo", "Hides the drawing controls", …
    val note: String? = null,       // the one caveat worth knowing, or null
)

enum class Gesture { TAP, DRAG, PINCH, THREE_FINGER_DRAG }

/**
 * The sheet's CONTENT, derived — never typed out by hand. Everything a row says comes from a
 * constant that already exists or from the [GestureMap] that is actually in force.
 */
object CheatSheet {
    /** Every row, in the order a person should read them. */
    fun rows(map: GestureMap, brush: BrushPreset?): List<CheatRow>

    /** The first-run hints, in order. A hint is a sentence, never a dialog with a button. */
    fun hints(): List<Hint>

    /**
     * Which hint (if any) to show now: the first one that has not been seen, or null when all have
     * been. Pure, so "shows each exactly once, in order" is a test and not a hope.
     */
    fun nextHint(seen: Set<String>): Hint?

    data class Hint(val id: String, val text: String)
}
```

## Decisions

1. **The sheet is GENERATED from the live configuration, so it cannot go stale.** A row's `what` is
   read out of the `GestureMap` in force (JB-2.02b), so a person who reassigned the two-finger tap
   sees the sheet that describes *their* app. **A hand-typed list of gesture strings is the failure
   mode this decision exists to prevent**, and test 1 is the check.
2. **The numbers come from the constants that already exist, never from a copy.** `TAP_MS = 250`
   and `TAP_SLOP_PX = 20` are `CanvasGestures`'s. The sheet's "quick tap" wording is derived from
   them, and a test asserts the sheet contains the live values — so if somebody changes the tap
   window, the sheet follows. **A shared constant is never copied** (house rule), and here it is
   also a correctness rule: a sheet that says "a quick tap" when the window is 250 ms is right, and
   one that says "under half a second" when it is 250 ms is also right, but only one of them can be
   right forever.
3. **Fingers do not exist before a pen is seen, and the sheet says so.** Blueprint + R5: fingers
   DRAW until a pen is detected. A cheat-sheet that lists "one finger: pan" to a person who has never
   touched a pen to the screen would be describing the app they are not in. **The sheet has a
   leading line whose text changes with `penSeen`**, and it is the only part that does.
4. **The sheet is a panel, not a screen.** It goes through JB-2.01's drawer/popover component, in
   the same form as every other panel — one component, two forms (blueprint §3.5). It is opened by a
   "?" in the header and by the first-run hint's own tap, and **nothing else**, so there is one
   place to find it.
5. **First-run hints: each exactly once, in order, dismissible by doing the thing, never a modal.**
   * Dismiss by gesture: performing the gesture dismisses the hint about it and shows nothing —
   **that is the point of a hint, it must not interrupt the act it describes.**
   * Dismiss by tap: tapping the hint's "×" dismisses it permanently.
   * **Never a dialog**, because a dialog has to be answered and a drawing app should never make a
     person answer anything mid-stroke.
   * Maximum **three** hints, ever. A hint that arrives on the fourth drawing has become noise and
     the person has stopped reading; the rule is "three, then never".
6. **The three hints, in order, and each is about ONE thing:**
   1. `"Hold the pen still at the end of a shape and it becomes a perfect one."` (JB-2.11)
   2. `"Drag the brush circle to change its size; drag up for more opacity."` (JB-2.16)
   3. `"Two fingers move and turn the page. Tap two to undo."` (JB-2.02)
   Order is deliberate: the first two are the app's own ideas and nobody else's, the third is the
   platform-standard one people already half-know.
7. **Hints are per install, not per document, and are stored in the app's preferences under one
   key.** A person who has drawn for an hour does not want the first-run hint again because they
   opened a new drawing. The reverse — a hint on a fresh install for a person who already knows the
   app — is a cost of 1 tap on the "×".
8. **Never in the way of the canvas.** Hints are a single line at the BOTTOM of the screen, above
   the cluster, in the drawer ink over a `Kit.ctl` fill, and they never sit over the drawing's
   middle. They are dismissed by the gesture, so their lifetime is one stroke long.
9. **The sheet is available forever, not only on first run.** A person who forgot a gesture in
   month three is exactly the person blueprint §3.5's sentence is about.

## Tests

`CheatSheetTest` (JVM, `:core:jvmTest`):
1. **The staleness test (Decision 1):** with `map.twoFingerTap = FRAME_NEXT`, the row for a
   two-finger TAP says what a frame-next action says — and with the default map it says "Undo".
   **Both halves, or the test is vacuous.**
2. **The numbers test (Decision 2):** the sheet's text contains the current `CanvasGestures.TAP_MS`
   and `TAP_SLOP_PX` as rendered strings. The test reads the constants (they are `const val`s in the
   androidkit module, so it reads them as text and compares with what the sheet's KDoc-formatted
   strings produce) — a change to either constant without a change to the sheet is a red test.
3. **`everyRowNamesItsFingerCount`** (1..5, each at most once per gesture kind) and **`theFingerRow
   ForPanningIsMarkedNavigationNotDrawing`** — the sheet's one-finger row says pan/select, never
   "draw", and the leading line (Decision 3) differs between `penSeen = true` and `false`.
4. **`nextHint` is exactly-once and in order**: from an empty `seen`, the three hints come out in
   the order of `hints()`; adding one to `seen` advances; a `seen` holding all three returns null
   forever (and `hints().size == 3`, asserted, so a fourth hint cannot be added silently — Decision 5).
5. **An unknown id in `seen`** is ignored rather than throwing — a preference written by a newer
   build must not break an older one (the same forward-compat posture as `DocJson`'s unknown-key
   rule, and R3's family).
6. **`theSheetHasNoHandTypedActionNames`**: a source-level check that `CheatSheet.kt` contains no
   string literal equal to `"Undo"`, `"Redo"`, `"Hides the drawing controls"` or any other action's
   display name — those come from `FingerAction`'s own description. **This is the test that makes
   Decision 1 structural rather than aspirational.**

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher green.

## Owner check (Note 9, then Tab S8)

Fresh install (clear the app's data): draw a rough circle and hold → the perfect one appears and the
first hint is gone, with no dialog. Drag the brush swatch → hint 2 goes. Two-finger tap → undo works
and hint 3 goes. Draw a fourth stroke → nothing appears. Tap "?" in the header → the sheet; on the
phone a drawer, on the Tab a popover, **with the same rows**. Reassign the two-finger tap in settings
→ reopen the sheet → the row now describes the reassigned action.

## Do not

- **Do not hand-type an action's name in the sheet** (Decision 1, test 6). One list of names, read by
  both the settings screen and the sheet.
- Do not copy `TAP_MS` or `TAP_SLOP_PX` into core. Read them (Decision 2).
- Do not add a fourth hint, a modal, a tooltip-on-everything, or an onboarding carousel. Three
  hints, then never (Decision 5).
- Do not build the panel yourself — JB-2.01's component, in its form (Decision 4). If D.02 has not
  landed, use the plain `AlertDialog` JB-2.13b uses and say so in the commit.
- Do not describe a finger as drawing anything once a pen has been seen (Decision 3).
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted (phone + Tab S8)
- [ ] committed `JB-2.17: gesture cheat-sheet and first-run hints`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **The three hints' WORDS are mine** (Decision 6), and hint 1 tells the person about hold-to-shape
   — a feature that is JB-2.11, i.e. **may not be on the phone when this row lands**. A hint for a
   feature that does not exist is worse than no hint. **I have assumed this row ships after 2.11 and
   2.16** (its two hints), and 2.16 is Draft behind 2.01. Options: (a) land this row last, after
   2.11/2.16/2.02b; (b) make the hint list **conditional on what the build has**, which means the
   list is not a list. I have specified (a) and said so here; the ROADMAP's "Needs" column for
   JB-2.17 says only JB-2.02, so **this row as the board has it can be taken too early and would
   ship two lies.** That is a board row that wants a second "Needs" entry.
2. **Hints are per install, not per document (Decision 7).** The alternative is per document, so a
   person who opens a new drawing is reminded — which is right for a person who draws every day and
   insulting for the owner, who drew for an hour before this existed. I chose per install.
3. **"Three hints, then never" (Decision 5).** A fourth is one more line of code and one more
   chance to interrupt a stroke. If you want a hint for the tool finger (JB-2.02b's four modes) or
   for the selection loop (JB-2.05, which is a *hidden* gesture — a loop with the barrel button
   held is not something anybody discovers), then the count is 5 and it should be decided now, not
   by whoever notices.
4. **The one-finger row's wording depends on `penSeen` (Decision 3)** and that means the sheet is a
   live thing, not a static picture. Confirm that is acceptable — a screenshot in the docs would go
   stale, but a hand-typed list would go stale sooner.

### Low-risk, ruled provisionally

5. **The sheet is a panel, never a separate screen** (Decision 4).
6. **Hints dismiss by doing, not by acknowledging** (Decision 5) — no dialog, ever.
7. **Hints sit at the bottom, never over the middle of the canvas** (Decision 8).
