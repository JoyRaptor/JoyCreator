# JB-2.03a — the adversarial audit's findings, answered (space-bunny-alpha)

Row: JB-2.03a, colour pill + drag-off eyedropper + long-press with cancel. The JB-2.02c pen-button
findings in the same audit are another row and another lane; `PenButtons.kt` and its tests were not
opened for editing and are untouched.

Worktree: `C:\Users\JOYRAP~1\AppData\Local\Temp\jb-2.03afix`, branch `bunny/JB-2.03a-audit-fixes`,
off `44b4f31c`. **No Gradle was run, not once.** Every symbol below was checked by reading the file at
the line given. Nothing was executed except `git apply --check` and `git`, which do not compile
anything.

**One thing to read first.** The BLOCKER is **not fixed in the shipped app**, and nothing I could
write in my own files would fix it: the rule lives in `JbCanvasView.kt`, which LEAD_DESK rule 3 and
ROADMAP R30 item 2 reserve for the owner. I have put the exact change in
`tasks/joybrush/held/JbCanvasView_JB-2.03a-audit.patch` (verified to apply to this tree with
`git apply --check`, every hunk at its stated line, zero offsets) and in Question 1 below. The same is
true of the drag-off tell, the recent-colours entry and the ring's z-order, which are in
`tasks/joybrush/held/JoyBrushActivity_JB-2.03a-audit.patch` — also `git apply --check` clean, and
marked there as "after the lane holding that file has landed".

The audit's line numbers are against an older revision of `JbCanvasView.kt`; the file has moved since
(it is 1522 lines and JB-2.04, 2.12, 2.23 and the paper rows have all edited it). Every claim below
re-verified, with the line it is at **now**.

---

## Finding by finding

### BLOCKER — a long-press eyedropper takes NOTHING if the pen lifts without first moving 12 dp — **the rule is FIXED and tested; the view that calls it is DEFERRED to the owner (Question 1)**

Verified, and it is worse than a 12 dp dead zone in one way: the ring also lies about the colour for the
whole of it (that is the next finding, and it is the same line of code).

- `JbCanvasView.kt:754-762` `startEyedrop` sets `cancelX = x; cancelY = y` and `cancelR = 12 dp ×
  density` from the touch point; `:765-766` `eyedropMove` sets `fingerX = x; fingerY = y` from the same
  point. So `dx = dy = 0` and `insideCircle` (`Eyedropper.kt:146-150`, `<= r * r`) is true on the frame
  the ring appears.
- `:775-776` `overCancel()` is `cancelR > 0f && insideCircle(fingerX, fingerY, cancelX, cancelY, cancelR)`
  — true. `:783-784` `eyedropEnd` computes `took = take && !overCancel()` = false, so `:791` never
  samples, `colorArgb` is untouched, `onColorPicked` never fires and the ring just goes away.
- The spec's own acceptance bullet, "long-press again and lift → red"
  (`specs/JB-2.03a_colour_pill_and_eyedropper.md:65`), cannot pass. Decision 2's "lifting takes the
  colour" (`spec:41`) is contradicted. **The audit is right.**

**The rule I chose, and why it is the one the spec describes**

> The cancel circle is a way BACK to where the hold began, so it means nothing until the touch has left
> it. The circle therefore carries two pieces of state, not one: its place (fixed at the hold) and
> whether the touch has been outside it once (`armed`, sticky). `overCancel` = there is a circle, AND
> it is armed, AND the touch is inside it.

A touch that has never left the start point is *at* the start point; there is nothing to have gone back
from, so "slide back to the circle" cannot mean "is inside the circle" at t = 0. That is the whole of
the fix: it turns a position comparison that is degenerate at t = 0 into a rule about a journey.

The threshold is the circle's own edge — `armed` becomes true at the first move that is **outside** it
— and not "moved at all". I considered the alternative and rejected it: arming on "moved more than the
hold slop" (`PEN_SLOP_PX` 6 px, `FINGER_SLOP_PX` 10 px) would let the ring show a cancel while the touch
is still *inside* the circle it never left, so the tell and the rule would disagree for the first 6–10 px
of every pick, and a one-pixel wobble at the start of a deliberate pick would discard it. The circle is
the affordance's own boundary; using any other threshold makes the two halves of the affordance lie about
each other. The spec's "slide back into the small circle where the hold began … and lift there" only
works if "back" means "away and returned".

Consequences, all of them intended: lift at the hold point **takes**; lift after a return to the circle
**cancels**; a second finger still cancels outright (`:721`, `eyedropEnd(take = false)`); a drag-off has
`r = 0` and is untouched by all of it.

Landed in `Eyedropper.kt`: `armsCancel` (`:98-99`), `overCancel` (`:105-106`), `insideCircle`'s boundary
now stated in its own KDoc (`:145-149`), and the `overCancel` semantics written into `EyedropState`'s
property KDoc (`:10-12`). Tests: `aHoldThatLiftsWhereItBeganTakesTheColour`,
`slidingBackToTheStartCircleAfterLeavingItCancels`, `aDragOffHasNoCircleAndSoNoCancel`.

**What this does not prove.** These three tests pin the RULE. They cannot prove the view calls it: a
`MotionEvent` cannot be built off-device (R27 records the same for `CanvasGestures`), and I will not add
a test that greps `JbCanvasView.kt`'s text — without an input declaration in `androidkit/build.gradle.kts`
it would be a false green the moment Gradle cached the test task (R44 item 1 is that bug), and a test
that matches a line of source breaks on reformatting. So the wiring is unproven and the owner's Note 9
check is what settles it: long-press, ring appears, lift without moving → the colour is taken.

**One extra defect I found in the same predicate, not in the audit's list.** `eyedropTouch` calls
`eyedropEnd(take = true)` on `ACTION_UP` (`:719`) without ever reading the UP's own point, so the cancel
is decided at the last **MOVE**, up to one frame of travel stale. A slide back into the circle that
happens entirely inside the final 16 ms is missed and the colour is taken instead. Fix is hunk 3 of the
patch. Unproven on a device; the reasoning is the whole of the evidence.

### MAJOR — inside the start circle the ring paints `oldArgb` on both halves, so it shows no new colour — **same root cause; DEFERRED with the patch, and the remaining one-frame gap is now documented as true**

Verified. `publishEyedrop` (`:778-781`) publishes `overCancel = true` at t = 0 and
`EyedropperRingView.kt:79` paints the top arc `oldArgb` whenever `overCancel` is true, so both halves are
the old colour. With `overCancel` false at t = 0 (patch hunk 6) the top half is the new colour from the
first screen read on.

There is a second, smaller truth the audit's version of the finding does not separate, and it is now
written down instead of papered over (`Eyedropper.kt:7-8`, `EyedropperRingView.kt:15-19`): the read of
the screen is a frame behind, and `startEyedrop:758` seeds `eyedropNew = eyedropOld`, so for exactly one
frame after the ring appears the top half still matches the bottom half. That is not the audit's
finding (which is 12 dp of travel, not a frame) and it is not fixable in the ring: the colour genuinely
is not known yet. It is now a comment that says so.

### MAJOR — the drag-off has no "over the pill" tell (`cancelR = 0`, so `overCancel` is permanently false) — **DEFERRED to the owner (Question 2); the hit rule is FIXED and tested in my files**

Verified. `dragEyedropMove` (`:812-813`) starts with `withCancelCircle = false`, so `cancelR = 0f`
(`:760`) and `overCancel()` (`:775-776`) is false for the whole gesture. The *behaviour* exists — the
screen's own `overPill` decides, and `dragEyedropEnd(take = !overPill(v, ev))`
(`JoyBrushActivity.kt:1419`) — but Decision 2's "the ring shows the old colour on both halves while over
the pill so the cancel is visible" (`:36-37`) has nothing to switch on. The audit is right that the one
gesture whose lift can silently do nothing gives no warning.

It needs two things and both are in files I may not touch: a way for the screen to tell the canvas where
the pill is, in canvas px (the canvas is full-bleed, so the pill's place is a valid canvas coordinate —
the ring would land exactly on it), and the ring above the chrome, or the tell is drawn under the strip
and is not a tell. The geometry of the pill as a *circle* is the Lead's call, not mine: the circle must
contain the whole pill or the ring will show a cancel on a lift that the screen then takes.

What I did land: `Eyedropper.overPill` (`Eyedropper.kt:114-115`) and its test. The screen's hit test is
the other half of the same fix and is in the Activity patch (hunk 4).

### MAJOR — `longPressEyedropper` is never stored and has no setting — **DEFERRED, and the finding is right; my decision on what belongs in this row is below**

Verified. `JbCanvasView.kt:263` is `var longPressEyedropper = true`, and the only assignment in the app
is `= false` on a throwaway preview view (`JoyBrushActivity.kt:753`). There is no `PREF_` key, no read,
no write and no menu row for it; the settings *screen* that would own it is chrome row JB-2.01, which
R39 and R42 put after this row.

**What belongs in JB-2.03a, decided:** the default and the storage path, not a UI. The default is right
where it is (ON, in the view, so a person with no preference gets the gesture Decision 2 promises). What
is missing is that the storage path does not exist and the KDoc claims it does. So:

- The **storage path** is two lines in the Activity: a `PREF_LONG_PRESS_EYEDROPPER` const beside the
  others (`:135-150`) and `canvas.longPressEyedropper = prefs.getBoolean(PREF_LONG_PRESS_EYEDROPPER, true)`
  in `onCreate` beside `:360`. `prefs.getBoolean` is what this file already does for
  `PREF_LAYERS_OPEN` (`:356`), so there is no new pattern to invent. The **write** belongs to JB-2.01,
  when there is a switch to write — and until then nothing can reach the value, which is honest: a
  default of ON that a person cannot change yet, rather than a setting that pretends to exist.
- The **KDoc at `:262` is the false claim** and it is the only part I would call a defect rather than an
  omission. It says "a setting can turn it off". As of this commit nothing in the app can. Question 3.
- I deliberately did **not** add a codec or a `Eyedropper.LONG_PRESS_DEFAULT` constant. A boolean has no
  encoding, and a constant with no reader is the dead code the next audit will find.

### MAJOR — `GlPaintEngine.readPixel` and `Eyedropper.seen` are dead code, and the board claims the read is `readPixel` — **half FIXED, half DEFERRED (Question 4)**

Verified. `readPixel` (`gl/GlPaintEngine.kt:665-678`, the audit's `:701` is stale) has no caller outside
its own file, and its KDoc at `:661-664` claims "JB-2.03a: the eyedropper reads one pixel per move, not a
whole 256 KB tile". The live path is `JbCanvasView.sampleScreen` (`:184-212`), which binds framebuffer 0
and reads the composited surface after the next frame. `Eyedropper.seen` (`:128-143`) is called only from
`EyedropperTest`.

- `seen` is **FIXED**: its KDoc now says plainly that it is NOT the shipped read, that it is the one-layer
  rule kept as the reference the screen read is checked against, and that no production code calls it. The
  audit's second half is fixed too: `rgba[pixelIndex + 3]` was indexed with no bound, so any index but 0
  would have thrown if it were ever wired; `seen` now `require`s a whole pixel (`:133-135`) and
  `aPixelIndexOutsideTheBufferIsRefusedNotClamped` pins it.
- I did **not** delete `seen` or its four tests. Deleting four passing tests that pin a correct colour rule
  is a loss, and it would not have made the finding go away anyway: `readPixel` is Lead-only and would still
  be dead, so the "4 of 8 tests exercise a function nothing calls" complaint would survive half-fixed.
- `readPixel` and the board text are the owner's (Question 4). The board sentence to correct is that the
  one-pixel read is `JbCanvasView.sampleScreen` over the composited framebuffer, not
  `GlPaintEngine.readPixel`; `readPixel` is an unused tile reader.
- **The true test count you asked for: `EyedropperTest` is 8 tests at `44b4f31c` and 14 after this commit.**
  The 8 were `EyedropperTest.kt:14,19,25,34,41,49,56,64` before this commit (counted from the file at
  HEAD, not from a run) and the 14 are at `:20,25,31,40,47,59,75,91,109,126,136,149,156,164` now. There
  is no `TEST-*.xml` for `EyedropperTest` in this worktree's `build/` to check the old 8 against, so both
  numbers are counts in the source.

---

## The MINORs

- **`CANCEL_CIRCLE_DP = 24f` used as a diameter, and the test around it never touches it — FIXED.**
  `Eyedropper.kt:45-56` now says the constant is a DIAMETER (Decision 2's "the small circle … (24 dp)" is
  a whole circle) and that R10 multiplies it at the use site, and `cancelRadiusPx(density)` is that
  multiply in one function. `theCancelCircleIsTwentyFourDpAcrossAtEveryDensity` pins the number.
  I did **not** rename the constant to `CANCEL_CIRCLE_DIAMETER_DP`: `JbCanvasView.kt:760` uses it and I
  may not edit that file, so a rename would break the app build. Patch hunk 4 switches that call to
  `cancelRadiusPx`, which is the same arithmetic and one source of truth.
- **The pill hit test is inclusive — FIXED as a rule, DEFERRED at the call site.**
  `Eyedropper.overPill` + `thePillHitTestStopsOnePixelBeforeTheEdge`; the Activity patch hunk 4 wires it
  and leaves the two call sites (`:1419`, `:1424`) alone.
- **A long-press that becomes an eyedropper pushes a recents entry for a colour that painted nothing —
  DEFERRED (Activity patch hunk 3).** Verified: `holdFired` (`:690-695`) calls `cancelStroke()`,
  `cancelStroke` (`:989-996`) fires `onStrokeEnded`, and `JoyBrushActivity.kt:376-385` pushes
  `canvas.strokeColor` from that signal. **`cancelStroke` must keep firing it** — R26 makes
  `onStrokeEnded` the level-triggered release of a save that was waiting for the pen, and a cancelled
  stroke owes that release just as much as a finished one — so the fix is a second signal
  (`onStrokeCommitted`, JbCanvasView patch hunks 1 and 7) with the push moved onto it, not a
  narrowing of the existing one. No test can name this: it is a device check (long-press, lift, the bar
  must not grow).
- **A sample point off the surface reports opaque black, and a lift there takes black — DEFERRED, not
  fixed, and I could not fix it in my files.** `JbCanvasView.kt:194-212`: the out-of-range branch at
  `:202` answers `OPAQUE_BLACK` (`:1504`, `-0x1000000`), and `answerOne` cannot tell the eyedropper that
  the answer is not a colour. `sampleScreen` is also what the top icons read, so "no colour" needs a
  second answer shape for every caller, not a special case for the eyedropper. Question 5. I agree with
  the audit that it is mostly unreachable: `dragEyedropMove` is only called after 8 dp of travel
  (`JoyBrushActivity.kt:1410-1412`) and the long-press's point is a real touch on the view.
- **The ring view sits under the chrome, and `samplePoint` guards only the top edge — one half FIXED as
  truth, one half DEFERRED (Activity patch hunks 1 and 2).** Verified: `root.addView(ring, …)` is at
  `JoyBrushActivity.kt:334`, before the reference (`:337`), the guides (`:342`) and the chrome (`:343`).
  `samplePoint` (`Eyedropper.kt:79-84`) flips above/below on the top edge only, and its KDoc claimed "so
  the ring is never cut off", which is not true: with the ring 92 dp across and the sample point 76 dp
  above the fingertip, a finger near the left or right edge puts half the ring off the screen, and the
  "below" fallback for a finger at the very top lands the ring 30–122 dp down, which is where the top
  strip is. That KDoc now says what is and is not promised, and says that the chrome's geometry is
  JB-2.01's to give. A finger under the strip samples what is under the strip; no arithmetic in this file
  can know where the strip is.
- **Board says "EyedropperTest 5/5", the suite is 8 — the true number is in the MAJOR above: 8 now, 14
  after this commit.**

### Not my lane, unchanged, one line so the orchestrator knows I saw it

The audit's MAJOR that holding the S Pen barrel button and drawing now produces nothing
(`JbCanvasView.kt:698-715` consumes the DOWN and every later event, and the UP only acts for a quick
unmoved tap) is real and still present. It is JB-2.02c's routing, which R42 gives to the owner, and I
did not touch it.

---

## Derivations, for every number I assert

| Number | Where | Derivation |
|---|---|---|
| cancel radius 12 dp | `Eyedropper.kt:51,56` | Decision 2 (`spec:42`) says a 24 dp circle. A circle's size is its diameter, so the radius is 24 ÷ 2 = 12 dp. |
| 12 px at density 1, 36 px at density 3 | `EyedropperTest.kt:60-61` | R10: the use site multiplies dp by density. 12 dp × 1 = 12 px; 12 dp × 3 = 36 px. Density 3 is a 480 dpi screen (`densityDpi` 480 ÷ 160), the Note 9 class of device. |
| 72 px across at density 3 | `EyedropperTest.kt:64` | 2 × 36 px = 72 px, and 72 px = 24 dp × density 3. The constant is a DIAMETER, so this line is what says so if it ever stops being one. |
| 36 px is the boundary, 37 px arms it | `EyedropperTest.kt:94-95` | `insideCircle` is `dx² + dy² <= r²` with r = 36, so 36² = 1296 ≤ 1296 is inside and does not arm; 37² = 1369 > 1296 is outside and does. |
| 10 px from the centre is inside | `EyedropperTest.kt:98` | 10² = 100 ≤ 1296. |
| 100 px from the centre is outside | `EyedropperTest.kt:100` | 100² = 10000 > 1296. |
| 119.9 in, 120 out | `EyedropperTest.kt:127-131` | A 120 px view has columns 0 … 119; `width` is 120, one past the last column. `119.9 < 120` is in, `120 < 120` is not. |

Every expected value in the new tests was written from one of those rows before the assertion was typed;
none was read off a run, because there was no run.

## Mutation reasoning: which named test goes red if a guard I added or changed is removed

I cannot run any of these. The reasoning is the evidence, and it is the only evidence there is.

| Guard | Mutation | Named test that goes red |
|---|---|---|
| `overCancel`'s `armed &&` (`Eyedropper.kt:106`) | drop it | `aHoldThatLiftsWhereItBeganTakesTheColour` — the touch is on the centre, so `insideCircle` is true and `overCancel` would be true; the `assertFalse` on the lift fails. |
| `armsCancel`'s `alreadyArmed \|\| !insideCircle(…)` (`:99`) | reduce it to `alreadyArmed` | `slidingBackToTheStartCircleAfterLeavingItCancels` — the `assertTrue(armed)` after the move to 437 px fails, because the circle would never arm. |
| `armsCancel`'s `r > 0f &&` (`:99`) | drop it | `aDragOffHasNoCircleAndSoNoCancel` — at (500, 900) with r = 0, `!insideCircle` is true, so `armsCancel` returns true and the `assertFalse` fails. |
| `overCancel`'s `r > 0f &&` (`:106`) | drop it | `aDragOffHasNoCircleAndSoNoCancel` — with `armed` forced true and the touch on the centre, `insideCircle` with r = 0 is true, so `overCancel` returns true and the `assertFalse` fails. |
| `insideCircle`'s `<=` (`Eyedropper.kt:149`) | change to `<` | `slidingBackToTheStartCircleAfterLeavingItCancels` — 36 px would be "outside", so the first `assertFalse(armsCancel(436f, …))` fails. |
| `cancelRadiusPx`'s `/ 2f` (`:56`) | drop it | `theCancelCircleIsTwentyFourDpAcrossAtEveryDensity` — 24 dp × 1 would be 24, not 12. |
| `cancelRadiusPx`'s `* density` (`:56`) | drop it | the same test — at density 3 it would be 12, not 36. This is the R10 guard: a raw dp constant is half size on a 2× screen. |
| `overPill`'s `<` on x and y (`:115`) | change to `<=` | `thePillHitTestStopsOnePixelBeforeTheEdge` — (120, 60) and (60, 120) would be "on the pill" and both `assertFalse`s fail. |
| `seen`'s `require` (`:133-135`) | drop it | `aPixelIndexOutsideTheBufferIsRefusedNotClamped` — index 1 of a 4-byte buffer throws `ArrayIndexOutOfBoundsException`, which is not an `IllegalArgumentException`, so `assertFailsWith<IllegalArgumentException>` fails. |

What is **not** covered by any test, and I will not pretend otherwise: that `JbCanvasView` calls these
functions at all, that the ring is above the chrome, that the recents bar does not grow on a hold, and
that nothing takes black off the surface. The first is the BLOCKER's actual fix; the other three are in
the two patches. All four are the owner's Note 9.

## Every symbol I checked, and where I read it

`JbCanvasView.kt` (HEAD `44b4f31c`, 1522 lines): `:184-212` `sampleScreen` / `answerOne`; `:202` and
`:208` the out-of-range black; `:1504` `OPAQUE_BLACK`; `:249` `onStrokeEnded`; `:251` the colour block;
`:263` `longPressEyedropper`; `:690-695` `holdFired`; `:698-715` the barrel-button branch (not my lane);
`:716-723` the eyedropper's own event branch, `:719` the `ACTION_UP`; `:738-747` `watchForHold`;
`:754-762` `startEyedrop`, `:760` the cancel radius; `:765-772` `eyedropMove`; `:775-776` `overCancel`;
`:778-781` `publishEyedrop`; `:783-792` `eyedropEnd`; `:804-806` `sampleAt`; `:812-818` the drag-off
pair; `:977-987` `finishStroke`; `:989-996` `cancelStroke`.
`Eyedropper.kt` (the two files I changed are given at the line they are at **after** this commit):
`:14-30` `EyedropState` and its three property KDocs; `:34-69` the constants; `:45-56` the cancel circle's
diameter and `cancelRadiusPx`; `:79-84` `samplePoint`; `:86-106` the cancel rule
(`armsCancel`, `overCancel`); `:114-115` `overPill`; `:128-143` `seen` and its `require`; `:145-150`
`insideCircle`.
`EyedropperRingView.kt`: `:41-104` `onDraw`; `:47-56` the cancel circle; `:79` the top arc's colour.
`JoyBrushActivity.kt`: `:60` the `Eyedropper` import; `:135-150` the prefs keys; `:316-323` and `:356`
and `:360` the pattern of a prefs read; `:332-346` the view order; `:376-385` `onStrokeEnded` and the
recents push; `:446` the ring's construction; `:495` the swatch's touch listener; `:753` the preview's
`longPressEyedropper = false`; `:1389-1430` `ColourPillTouch`, `:1400-1401` `overPill`, `:1419` and
`:1424` its two callers.
`GlPaintEngine.kt`: `:661-678` `readPixel` and its KDoc.
`EyedropperTest.kt`: all 8 at HEAD; the 6 new ones at `:53-142`.
Rulings read in full: `LEAD_RULINGS.md` R10 (`:67-73`), **R22** (`:212-215`), R26 (`:266-289`),
R30 item 2 (`:318-324`), R32 (`:346-349`), R39 (`:445-473`), R42 (`:519-543`), R43 (`:545-564`),
R44 items 1 and 5 (`:573-586`). `LEAD_DESK.md` rule 3 (`:17`) and rule 6 (`:20`).
`specs/JB-2.03a_colour_pill_and_eyedropper.md`: Decisions 1–6 (`:27-57`) and Verification (`:60-66`).

## Files I changed, and what is in the commit

- `joybrush/androidkit/src/main/kotlin/…/tools/Eyedropper.kt` — the cancel rule, `cancelRadiusPx`,
  `overPill`, the `seen` bound, and three KDocs that were untrue.
- `joybrush/androidkit/src/main/kotlin/…/tools/EyedropperRingView.kt` — a dark hairline under the cancel
  circle (a white circle is invisible on white paper, so the way out was not visible), and the two KDocs
  that described a cancel the rule had not defined.
- `joybrush/androidkit/src/test/kotlin/…/tools/EyedropperTest.kt` — 8 → 14 tests. No existing test was
  weakened, loosened or deleted.
- `tasks/joybrush/held/JbCanvasView_JB-2.03a-audit.patch` and
  `tasks/joybrush/held/JoyBrushActivity_JB-2.03a-audit.patch` — the owner's half, `git apply --check`
  clean against this tree, not applied.
- `.gitattributes` — one line, `*.patch -text`, and it is not cosmetic. I measured it: a patch file checked
  out with CRLF (this repo has `* text=auto` and `core.autocrlf` true) is **refused** by `git apply` with
  "corrupt patch", so the two patches above would have been dead on arrival on any Windows checkout. With
  the line they come out LF and apply; I checked that by writing the checked-out copies through
  `git checkout-index` and running `git apply --check` on them. No other file in the repo is a `.patch`.
- `tasks/joybrush/reviews/JB-2.03a__bunny-fixes.md` — this file.

Not touched: `ROADMAP.md`, `ORCHESTRATOR_LOG.md`, `PenButtons.kt`, `PenButtonsTest.kt`,
`GlPaintEngine.kt`, `JbCanvasView.kt`, `JoyBrushActivity.kt`, and anything under `joybrush/shaders/`.

---

## Questions for the Lead

1. **`JbCanvasView.kt` — apply `tasks/joybrush/held/JbCanvasView_JB-2.03a-audit.patch`?** Seven hunks:
   `onStrokeCommitted` (new, beside `:249`, fired at the end of `finishStroke`); the `cancelArmed` field;
   the `ACTION_UP` reading its own point; `cancelR = Eyedropper.cancelRadiusPx(density)`; the `armsCancel`
   call in `eyedropMove`; `overCancel()` reading `Eyedropper.overCancel(…, cancelArmed)`. It needs my
   `Eyedropper.kt` (same branch) and it is the difference between a working long-press eyedropper and one
   that silently takes nothing. **Hunk 3 is the one I would not compromise on**: the cancel must be decided
   where the pen actually lifted, not at the last move.
2. **`JoyBrushActivity.kt` — apply `tasks/joybrush/held/JoyBrushActivity_JB-2.03a-audit.patch` after the
   lane holding it lands?** Hunks 1 and 2 move the ring above the chrome (the trade: for the second or
   two the eyedropper is up, its ring is over an open popover — your call, not mine). Hunk 3 moves the
   recents push to `onStrokeCommitted` and needs Question 1. Hunk 4 uses `Eyedropper.overPill`.
3. **`JbCanvasView.kt:262` — the KDoc claims "a setting can turn it off" and nothing can.** May I change it
   to name the state truthfully ("no screen writes this until JB-2.01's settings row"), and may the row add
   the one `prefs.getBoolean(PREF_LONG_PRESS_EYEDROPPER, true)` in `onCreate` so the storage path exists
   before there is anything to write it? I have not added a constant for the key: your file's `PREF_`
   consts are private, and a second copy of the name in `androidkit` is the drift R32 warns about.
4. **`GlPaintEngine.kt:661-664` — `readPixel`'s KDoc names JB-2.03a as its reason, and the shipped eyedropper
   reads `JbCanvasView.sampleScreen` instead.** Either the KDoc changes to say what it is (an unused
   single-tile pixel reader, kept for a future CPU sampler) or `readPixel` goes. It is yours; I have left it.
   The board sentence to correct with it: the one-pixel read is the composited framebuffer, not
   `readPixel`. And the test count is 8 today, 14 after this commit.
5. **`JbCanvasView.kt:202` — a sample off the surface answers opaque black, and the eyedropper takes it.**
   `sampleScreen` also feeds the top icons, so the fix is a shape for "there is no colour here" that every
   caller understands, not a special case in `take()`. Do you want that in this row's follow-up, or is
   "mostly unreachable" a fair place to stop? I have not touched it.
6. **R22's drag-off tell — who owns the pill's geometry?** For the ring to show both halves old while the
   finger is back over the pill, the canvas has to be told the pill's place in canvas px. A circle around
   the pill has to CONTAIN the pill, or the ring will offer a cancel that the screen then refuses to give.
   Tell me the shape (the pill's own rect, or a circle of half its diagonal) and it is a few lines in the
   patch above; until then the tell cannot exist.
