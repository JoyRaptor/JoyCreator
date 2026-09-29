# JB-3.04 — Onion skin as ONE shared component, extracted from SpriteLab, same settings

| | |
|---|---|
| **Tier** | T1 (the ROADMAP row says T1, and it is right: this moves code between modules and edits two app files) |
| **Status** | 🟨 **Draft — blocked on a module that does not exist.** Everything below is decided and pinned, and the **`:core` maths half can be built and tested today by any T2 model**. The other half cannot: R23 says *share, don't copy*, the shared home is `:studiokit`, **and `:studiokit` is created by D.02, which is Lead-owned, `⚪ Outline`, and explicitly marked "do not dispatch"**. On top of that this row edits `app/…/sprite/SpriteSheetEditorActivity.java` (4 823 lines), and the ROADMAP's app-file order (R14/§6 note) reserves app files for the Lead, one task at a time. **Read Q1 before starting anything.** |
| **Needs** | 3.01 (as the ROADMAP row states). In practice it also needs **D.02**, and that is not in the row — see Q1. |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/OnionSkin.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/OnionSkinTest.kt` · NEW `studiokit/src/main/java/com/fadcam/ui/faditor/tools/OnionSkinView.java` (only after D.02 creates `:studiokit`) · EDIT `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java` (**only** the five call sites listed in Step 4) |
| **Estimated size** | ~180 lines of Kotlin + ~200 lines of tests; ~260 lines of Java for the shared view |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures; then the watcher builds `:studiokit`, `:app` and `:joybrush-android` green. |

## Goal

Blueprint §1 idea 10, change (c), verbatim:

> **"Onion skin is extracted from SpriteLab into one shared component, same settings, never a
> copy."**

And R23 is the owner's modular rule that makes it non-negotiable: *"When Joy Brush needs something
the Studio has, it moves into `:studiokit` (same package names, pure move) and both use it;
improvements land once."*

The consequence that the rest of this spec is about: **the settings are not Joy Brush's.** There is
one set of numbers — `on`, `past`, `future`, `pastColour`, `futureColour`, `strength` — and it is
*the Studio's*, because SpriteLab has been shipping it since S2b. Joy Brush reads them. A second set
of numbers with the same names and slightly different values is precisely the failure R23 exists to
prevent, and it would show up as "the onion skin behaves differently in the two places", which is
the kind of bug nobody reports and everybody feels.

## Contract — the maths half (`:core`, pure, testable today)

```kotlin
package cc.joycreator.joybrush.core.anim

/**
 * Onion skin as arithmetic: which frames ghost, how loud each one is, and what colour it comes out.
 *
 * THE NUMBERS IN HERE ARE THE STUDIO'S. They are transcribed from
 * `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java` (the fields
 * `onionPast`/`onionFuture`/`onionStrength`, `CellCyclePreview.alphaFor`, `CellCyclePreview.ghostPaint`,
 * `Activity.neighbourCell`) and are held here as a *golden pair*, proven equal by
 * `OnionSkinParityTest` — which is the R23 pattern, the one JB-2.20a used for the blend modes:
 * **never copied by eye, proven by a test that fails if either side moves.** If this file and the
 * Java ever disagree, the suite goes red and the Lead decides which one is wrong. There is no
 * version of "we will remember to keep them in step".
 */
object OnionSkin {

    /** Ghosts shown on each side. 0 turns that side off. */
    const val MAX_PER_SIDE: Int = 6

    /** Strength floor and ceiling, from the Studio's `Math.max(0.05f, Math.min(1f, v / 100f))`. */
    const val MIN_STRENGTH: Float = 0.05f
    const val MAX_STRENGTH: Float = 1f
    const val DEFAULT_STRENGTH: Float = 0.40f

    /** Default ghost counts, from the Studio's field initialisers: `onionPast = 1`, `onionFuture = 1`. */
    const val DEFAULT_PAST: Int = 1
    const val DEFAULT_FUTURE: Int = 1

    /**
     * How loud the ghost [distance] steps away is, 0..255.
     *
     * The Studio's own formula, transcribed: `round(255 × strength × 0.62^(distance − 1))`, clamped
     * to 12..255. The floor of 12 is the Studio's, and it is load-bearing: a ghost at zero alpha is
     * an invisible control.
     */
    fun alphaFor(distance: Int, strength: Float): Int

    /**
     * The frames to ghost for [currentIndex] over a sequence of [count] frames, as indices into that
     * sequence.
     *
     * [past] ghosts go backwards from [currentIndex] and [future] forwards, each skipping an index
     * that is not in range. A frame may appear on BOTH sides only if the sequence is shorter than
     * the two counts together, in which case the earlier side wins the slot — a cell cannot be
     * drawn twice at two different alphas, or the far ghost shows through the near one.
     *
     * @return past ghosts first (nearest first), then future ghosts (nearest first).
     */
    fun ghostIndices(currentIndex: Int, count: Int, past: Int, future: Int): List<Ghost>

    data class Ghost(val index: Int, val past: Boolean, val distance: Int)

    /** Clamp a slider's value into the Studio's range. A non-finite strength is [DEFAULT_STRENGTH]. */
    fun strengthOf(raw: Float): Float

    /** Clamp a count into 0..[MAX_PER_SIDE]; anything else is 0 (that side off). */
    fun countOf(raw: Int): Int
}
```

## Contract — the shared half (`:studiokit`, **after D.02**)

```java
package com.fadcam.ui.faditor.tools;   // the same package, per R23's "same package names"

/**
 * The onion-skin overlay, shared by SpriteLab and Joy Brush. ONE component, ONE set of settings.
 *
 * The host draws a cell; this view ghosts the frames either side of it and nothing else. It owns
 * the settings (they were SpriteLab's and stay SpriteLab's — Joy Brush reads them through
 * {@link #settings()}), the falloff maths, the ghost paint, and the hit-testing of its own pills.
 * It knows nothing about sheets, boards, cells or documents.
 */
public class OnionSkinView extends View {
    public interface FrameSource {
        /** Straight ARGB of the frame [distance] steps away, or null when there is none. */
        @Nullable Bitmap frameAt(int distance, boolean past);
    }
    public interface Listener {
        void onSettingsChanged(OnionSettings s);
        void onSettingsPicked(boolean past, int argb);
    }
    public void setFrameSource(FrameSource src);
    public void setCurrent(boolean past, int distance);   // which frame is "now"
    public OnionSettings settings();                       // THE settings, shared
    public void setOnListener(Listener l);
    public void invalidate();
}

/** Immutable. The one copy of these numbers in the app. */
public final class OnionSettings {
    public final boolean on;
    public final int past;        // 0..6
    public final int future;      // 0..6
    public final int pastColour;  // ARGB
    public final int futureColour;
    public final float strength;  // 0.05..1
}
```

## Decisions

1. **The settings live in the shared component and both screens read them. There is no Joy Brush
   copy of `onionPast`, `onionFuture`, `onionStrength`, `onionPastColour` or
   `onionFutureColour`.** *Why:* R23. A second copy is how "the same setting" stops being the same
   setting, and the symptom is a bug nobody files.
2. **The defaults are the Studio's existing field initialisers, unchanged: `past = 1`,
   `future = 1`, `strength = 0.40`, past = `SpriteTheme.LIVE` (pink `#F43F8E`), future =
   `SpriteTheme.SELECTED` (cyan `#22D3EE`).** *Why:* the Studio has been shipping these since S2b and
   its users know them; changing them is a product decision, not an extraction. (Both are state
   colours, and `JOYBRUSH_VISUAL_LANGUAGE.md` §5.6 already records that this reuse exists — see Q2.)
3. **The falloff is the Studio's own formula, transcribed exactly:**
   `alpha = clamp(round(255 × strength × 0.62^(distance − 1)), 12, 255)`. The `0.62` and the floor
   of `12` are **not** ours to improve. *Why:* see Q2 — they are the numbers the parity test pins, and
   improving them belongs to whoever owns SpriteLab's look, which is the Lead.
4. **`0.62^(distance − 1)` makes the NEAREST ghost the loudest and falls off geometrically**, so
   with `strength = 0.40` and `past = 3` the alphas are 102, 63, 39 — and the third is within 4 of
   the floor, which is why the floor exists. *Why:* recorded because "3 ghosts" is not obviously
   "3 nearly-invisible ghosts" and the floor is what stops the last one disappearing.
5. **A frame cannot ghost on both sides.** When the sequence is shorter than `past + future`, the
   side that is **further from the current frame yields**: for `currentIndex = 2` in a 3-frame
   sequence, `past = 3, future = 3` gives past = 1, future = 0. *Why:* a cell drawn twice at two
   alphas composites to something that is neither, and the near ghost stops reading as "near".
6. **Distances are counted in ROLL steps, not cell indices.** The Studio's `neighbourCell` walks
   the sequence it is playing when there is one, and the enabled cells of the sheet when there is
   not. *Why:* on a sprite board the frames either side of the one you are looking at are the frames
   either side of it **in the animation you are building**, not whatever happens to sit next on the
   grid — that is the Studio's own comment and it is right, and getting it wrong ghosts the wrong
   picture the moment a cell repeats, which is most of the time.
7. **`:core` gets the maths and the Studio keeps the paint.** `OnionSkin` is pure: alphas and
   indices, no colour matrix, no Bitmap. The ghost paint (`ghostPaint` — a `ColorMatrix` that
   desaturates to `k = 0.70` and tints `0.30` back) is Android graphics and stays in
   `OnionSkinView`. *Why:* core is Kotlin Multiplatform and platform-neutral (owner's iOS door); a
   `ColorMatrix` in `:core` would close that door for a colour transformation.
8. **Parity is proven by a test, not by a promise.** `OnionSkinParityTest` transcribes the Java a
   **second, independently** (a third reading of `alphaFor` and `neighbourCell`, written from the
   Java source, not copied from the Kotlin) and asserts the two agree across the whole domain:
   every `distance` in 1..6 × seven strengths × every position in sequences of 1..12 frames. *Why:*
   R23's exact instruction — "proven equal by a generated golden table + drift check, never copied by
   eye" — and the reason it is worth the effort is JB-2.20a's Finding: a check that cannot see a
   change is a check that always passes.
9. **The extraction is a PURE MOVE.** `OnionSkinView` keeps the same field names, the same
   falloff, the same swatch list (`SWATCHES`, state colours first), the same 11 dp pill geometry and
   the same 13 dp drag step. SpriteLab's screen is edited at **five call sites** and nowhere else —
   Step 4 lists them. *Why:* R23 says "pure move", and every line changed at the call site is a line
   that could change the behaviour instead of relocating it.
10. **A "diff mode" (SpritesLab's `DIFFERENCE` blend, which answers "did ANYTHING move") is NOT part
    of this component.** It is one `onionStrength`-shaped idea about a different question, it needs
    API 29 (`BlendMode.DIFFERENCE`) and the Studio already guards it twice, and it draws nothing at
    all on API 24. *Why:* a second mode in the shared component is two settings and one more thing
    to keep identical. Recorded here so nobody adds it "while they are in there".
11. **The pills' behaviour is preserved exactly**: sliding a pill changes the count on that side,
    tapping it opens the ghost-colour swatches, and a half-opaque pill would be a lie — so when
    onion skin is off the pills go plain grey rather than a wash of their own colour. *Why:* the
    Studio's own comment says it, and it is the kind of detail that is lost in a move and never
    found again.
12. **The overlay draws the NEAREST ghost over the far ones** (far first, near last), so the near
    ghost is never tinted by the far one. *Why:* the Studio's `onDraw` loops `for (int k = past; k
    >= 1; k--)` — far to near — and that order is load-bearing, not incidental.

## Decision → Test map

| Decision | Pinned by |
|---|---|
| 1 (one settings home) | `thereIsExactlyOneOnionSettingsType` — reflection over `:studiokit`: exactly one class named `OnionSettings`, and neither screen declares a field of it |
| 2 (the Studio's defaults) | `theDefaultsAreTheStudiosExistingOnes` (1, 1, 0.40, LIVE, SELECTED) |
| 3 (the falloff, transcribed) | `theFalloffIsTheStudiosOwnFormula` + the parity test |
| 4 (geometric, floor 12) | `threeGhostsAtFortyPercentAre10263And39` |
| 5 (no frame on both sides) | `aShortSequenceGivesItsSidesToTheNearerFrame` |
| 6 (roll steps) | `ghostsWalkTheSequenceNotTheGrid` — a 6-frame roll whose cells are 40 apart in index order |
| 7 (maths in core, paint in the view) | `theCoreHalfHoldsNoAndroidType` — reflection over `OnionSkin`'s members |
| 8 (parity by test) | `OnionSkinParityTest` — full domain, second transcription |
| 9 (pure move) | `theStudiosNumbersDidNotChange` — the parity test, which fails if either side moves |
| 10 (no diff mode) | `theSharedComponentHasNoDiffMode` |
| 11 (pill behaviour) | `anOffPillIsGreyAndNotAWashOfItsOwnColour` |
| 12 (near over far) | `theNearestGhostIsDrawnLastSoItIsNeverTinted` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/OnionSkinTest.kt`

1. `theFalloffIsTheStudiosOwnFormula`: `alphaFor(1, 0.40f)` = 102; `(2, 0.40f)` = 63;
   `(3, 0.40f)` = 39. Hand-derived in the test comment: `255 × 0.40 = 102.0`;
   `102 × 0.62 = 63.24 → 63`; `63.24 × 0.62 = 39.2 → 39`.
2. `theFloorIsTwelveAndItBites`: `alphaFor(20, 0.05f)` = **12**, not 0. And
   `alphaFor(1, 1f)` = 255 (the ceiling, exactly).
3. `distanceZeroIsClampedToOneNotAnError`: `alphaFor(0, 0.4f)` = 102 (the same as distance 1),
   `alphaFor(-5, 0.4f)` = 102. The caller counts from 1; a 0 must not be an exponent of `0.62^-1`.
4. `threeGhostsAtFortyPercentAre10263And39` — test 1 again as its own name, because it is the
   number a person actually sees and it deserves a line of its own.
5. `aShortSequenceGivesItsSidesToTheNearerFrame`: `currentIndex = 2, count = 3, past = 3,
   future = 3` → `[(1, past, 1)]` and **no** future ghost. And the same with `future = 5` → the
   future still gets nothing, because there is no future frame at all.
6. `noIndexAppearsTwiceInTheResult`: for every `currentIndex` in 0..11, `count` in 1..12,
   `past, future` in 0..6, the returned indices are distinct. A property test over the whole domain,
   ~5 000 cases.
7. `ghostsAreOrderedNearToFarOnEachSide`: past ghosts come first, distances 1, 2, 3…; then future
   ghosts, distances 1, 2, 3… . Assert the *order*, not just the set — a view that draws near-last
   needs the list in a known order (Decision 12).
8. `ghostsWalkTheSequenceNotTheGrid`: a roll of 6 whose `frameAt` deliberately returns cells in the
   order 5, 2, 9, 0, 7, 3 — the ghosts are positions in the roll, and the caller maps them through
   `frameAt`. Asserts Decision 6 is about *positions*, and that `:core` has no idea what a cell is.
9. `strengthOf`: 0.5f → 0.5; 0f → 0.05; 2f → 1; NaN → 0.40; ±∞ → 0.40; −1f → 0.05.
10. `countOf`: −1 → 0; 0 → 0; 6 → 6; 7 → **6**; `Int.MIN_VALUE` → 0.
11. `aZeroCountTurnsThatSideOffEntirely`: `past = 0` returns no past ghosts whatever `currentIndex`
    is, including at index 0.
12. `theCoreHalfHoldsNoAndroidType`: no declared field, parameter, return type or thrown type of
    `OnionSkin` mentions `android.`, `Bitmap` or `ColorMatrix`. This is what keeps the iOS door open
    and it is the mechanical form of Decision 7.

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/OnionSkinParityTest.kt`
(JVM source set, because it reads the Java)

13. `theTwoTranscriptionsAgreeOnTheWholeDomain`: an independent second transcription of
    `alphaFor` and `neighbourCell` — written from the Java source, in Python, run as a generated
    table, compared — over `distance` 1..6 × `strength` in {0.05, 0.1, 0.2, 0.4, 0.6, 0.8, 1.0} ×
    `currentIndex` 0..11 × `count` 1..12 × `past, future` 0..6. Every disagreement fails with both
    numbers printed.
14. `theGoldenTableIsCurrent`: regenerating the table and byte-comparing it (`--check`) fails the
    moment either side moves. This is JB-2.20a's drift check, and it is the half that a "compare
    two implementations" test does not do on its own.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. **Non-vacuity proof, which the
builder must run and paste:** change the `0.62` in `OnionSkin` to `0.63`, run the suite, watch
tests 1, 3, 4 and 13 go red, change it back. A parity check that cannot fail is a comment.

## Do not

- **Do not create `:studiokit`.** That is D.02, and D.02 is Lead-owned and marked "do not
  dispatch". This row waits for it.
- **Do not edit `SpriteSheetEditorActivity.java` before the Lead has scheduled it.** It is an app
  file, and the ROADMAP's app-file order (R14) is one task at a time, Lead-coordinated.
- Do not copy the settings into Joy Brush. One home (Decision 1).
- Do not improve `0.62`, the floor of 12, the default counts or the default strength. They are
  SpriteLab's, they are pinned by a parity test, and changing them is the Lead's call (Q2).
- Do not bring the `DIFFERENCE` diff mode across (Decision 10).
- Do not put a `Bitmap` or a `ColorMatrix` in `:core` (Decision 7, test 12).
- No new dependencies.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (break `0.62`, watch it go red, put it back)
- [ ] watcher `build.log` shows `:studiokit`, `:app` and `:joybrush-android` EXECUTED in `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] SpriteLab's onion skin is **visually identical** before and after — a screenshot pair, or the
      owner confirming in the Studio
- [ ] committed `JB-3.04: shared onion skin`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The maths is decided, derived from
the shipped Java and pinned by a parity test. The blocker is structural, and it is not mine to
clear.)_

### Q1 — for the Lead, and it blocks two thirds of this row: `:studiokit` does not exist

R23 says the answer to "Joy Brush needs something the Studio has" is to move it into `:studiokit`,
"same package names, pure move". **`:studiokit` is created by D.02**, whose row reads:

> **Lead-owned, do not dispatch.** REVISED (R20–R24) … **App-file work — order one at a time:
> D.02a → D.02 → D.02c / D.05**

So this row as written — "extracted from SpriteLab into one shared component" — cannot be built
until D.02 lands, and D.02 cannot land until D.02a (four gesture bugs in the Studio's transform
tool) lands, and D.02a is a Lead task in app files. The ROADMAP's "Needs" column for JB-3.04 says
`3.01`, which is not true: 3.01 is Built and irrelevant to this row, and the real dependency is
missing from the column.

**What I need ruled:**

1. **Should the row's "Needs" become `3.01, D.02`?** I cannot edit the ROADMAP. If it stays `3.01`,
   a builder will take this row, find `:studiokit` absent, and either create it (which is D.02's job
   and its owner area) or copy the code (which is the R23 violation the row exists to prevent).
2. **Should the `:core` maths half be split out now as its own row?** `OnionSkin.kt` and its tests
   are pure Kotlin, need nothing from `:studiokit`, and are buildable **today** by a T2 model — and
   the parity test needs the Java to compare against, which it can read today even though it cannot
   move it. My recommendation is **yes: JB-3.04a = `OnionSkin` maths + parity (T2, buildable now),
   JB-3.04b = the `:studiokit` extraction (T1, after D.02)**. I have written this spec as one
   document with the two halves clearly separated, so the split is a copy-and-paste, not a rewrite.
3. **If D.02 slips, is a Joy-Brush-only onion view acceptable as an interim?** My answer is **no**,
   and I want it on the record: it is the copy R23 forbids, and the first copy is the one that gets
   improved on its own and turns into two components. But it is your call and I am not going to
   quietly build one.

### Q2 — for the Lead: three of SpriteLab's numbers are strange and I have pinned them anyway

Pinned, not changed, because they are the Studio's and the parity test is what makes them safe to
move later. Recorded so the strangeness is a decision rather than an accident:

- **`0.62` per step is a fast falloff.** At the default `strength = 0.40` the third ghost is at
  alpha 39 out of 255 — 15%. Four ghosts would be at 24, i.e. below the floor of 12 is never reached
  but it is close, and a person asking for four ghosts mostly gets two. `0.75` or `0.8` would make
  four ghosts legible. **Is 0.62 deliberate?**
- **The floor of 12** means a distant ghost is never *quite* invisible, which is right (an invisible
  control is a broken control) but it also means `MAX_PER_SIDE = 6` promises six ghosts of which
  the last two are near-nothing. **Should `MAX_PER_SIDE` be 4, or should the falloff be gentler?**
- **Past is pink and future is cyan** — two STATE colours used as identity. The visual-language
  document already flags this as a known exception (§5.6: "onion past/future = LIVE/SELECTED"), and
  it is the Studio's long-standing choice, and the blueprint says onion follows SpriteLab "for
  consistency". So it stays. I am recording that I checked the house rule and the house rule has
  already been excepted here on purpose.

### Q3 — for the Lead: is "the frames either side" the ROLL or the GRID, on the ANIMATION board?

Decision 6 says the Studio's rule, which is *the roll if there is one, otherwise the sheet's enabled
cells in index order*. On a sprite board that translates cleanly (a roll or the grid in reading
order). On an **animation** board there is no roll and no enabled-cell concept: the frames are
`Board.frames` and every one of them shows. So on the animation board the two candidates are:

- **(a) The board's play order, all frames, no skipping.** Simple, always true, and identical to what
  playback will do.
- **(b) The play RANGE** (`PlaybackClock`'s `firstFrame..lastFrame`), so onion skin shows what
  looping will actually show.

I ruled (a) and marked it provisional, because a range can be a sub-range of four frames out of
twelve and ghosting eight frames that will never play is noise. **But (b) means the strip has to
know the playback range, which couples JB-3.03 to JB-3.05** — and Decision 2 of JB-3.03 is
specifically that the strip must not know about the clock. My resolution if you pick (b) would be:
the HOST passes the ghost list to `OnionSkinView`, so the view never learns where the range came
from. **Which one?**
