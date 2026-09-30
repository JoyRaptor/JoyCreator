# JB-3.02b — Animation paper VIEW: `PaperOverlayView`, the peg bar's placement, `JoyBrushActivity` wiring

| | |
|---|---|
| **Tier** | T2-V (the two views are UI; the placement maths is pure and T2-tested). T3 owed a device check that **cannot happen yet** — read *Owner's check* before starting, it changes what you may claim. |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Depends on** | **JB-3.02** (core, 🟧 Built — `PaperGeometry.kt`, 22 tests) · **JB-2.01** (chrome, 🟧 Built 2026-09-30, owner-approved mockup v2) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PaperLayout.kt`<br>NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperLayoutTest.kt`<br>NEW `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/anim/PaperOverlayView.kt`<br>NEW `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/anim/PegBarView.kt`<br>EDIT `joybrush-android/.../JoyBrushActivity.kt` — **FIVE named regions only, listed in its own section at the end**<br>**Five files. Nothing else.** |
| **Estimated size** | ~190 lines of core + ~290 lines of tests + ~340 lines of views + **~50 lines in the Activity, in five places** |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. The `joybrush-android` half is proved by the **watcher's** `build.log` (`:joybrush-android:compileDebugKotlin` EXECUTED); never run gradle on the app build. |

## Why this row is unblocked, and what that changes

Its `needs` were **3.02, 2.01**. Both are `🟧 Built`:

- **JB-3.02** landed its **core half only** (`core/anim/PaperGeometry.kt`, 289 lines, 22 tests). Its own
  *Cut from this spec* table names **this row** as the owner of `PaperOverlayView`, the peg bar's
  placement, the `JoyBrushActivity.kt` edit, the rulers' 24 dp strips and their hiding, and the
  `PegStyle` → colour-token binding.
- **JB-2.01 landed today (2026-09-30)**, built by the Lead to the owner's approved mockup v2 and
  walked through on the Note 9. **The chrome your view has to live inside is not the chrome JB-2.01's
  original spec drafted** — the two-row bottom cluster, the thumb rail and the 600 dp drawer/popover
  switch were *not built and are not coming back*. What exists is:

| Landed, in `joybrush-android/.../chrome/` | What it is | Read it at |
|---|---|---|
| `ToolStripView` | a slim column of 40 dp buttons hugging **one** edge, **draggable**, snapping LEFT/RIGHT (`StripPlacement`), with the recent-colour hair beside it | `ToolStripView.kt:91-105` |
| `TopButton` | 40 dp, a **solid** icon straight over the picture, no chip; its ink follows the picture (`IconContrast`); `on = true` rings it cyan | `TopButton.kt:59-72` |
| `Popovers` | one panel at a time beside what was tapped; `showSheet` = a bottom sheet (full width up to 600 dp); a tap outside closes it **and is consumed** | `Popovers.kt:33-94` |
| `ValueHud` | the size/colour readout while a strip drag is in progress; a decorative `View` that takes **no** touches | `ValueHud.kt:33-40` |
| `ReferenceView` | the pinned reference picture, full-bleed, **under** the chrome | — |
| `JbIcon` | nine **solid** 24-unit icons: BRUSH, SMUDGE, ERASER, HOME, UNDO, REDO, PIN, MORE, LAYERS. **No PLAY, MODE, ONION, CADENCE or EXPORT.** | `JbIcon.kt:17-26` |
| `ChromeKit` | the shared look: `dp`, `dpi`, `ink(alpha)`, `chrome()`, `chromeSurface()`, `label()`, `drawSelected()`, `TOUCH_DP = 40f`, `CHROME_ALPHA = 230` | `ChromeKit.kt:20-78` |

**R30 item 1** therefore names you: you are the next claimant of `JoyBrushActivity.kt` after JB-2.01,
so you may take the file — **and only the five named regions of it** (five *edit sites*, which is
not three: two of them are second anchors inside a region). `JbCanvasView.kt` and
`GlPaintEngine.kt` are the Lead's in a different order (R30 item 2) and you do not touch them, and
**JB-1.05b's two-line dedup in `JbCanvasView` is explicitly not yours.**

## Goal

The "animation paper" of blueprint §1 idea 10, the half that is a screen and not a number: a **peg bar
docked to the bottom edge** whose five pegs are the animation board's board-level buttons, and **two
pixel rulers** in the top and start edges measuring the board in **document pixels** — never an
export, like every other helper (blueprint §3).

The maths already exists and is tested. What is left is the part that can only be got right by
someone who reads the landed chrome first: **where the bar goes, what the rulers are made of, what a
peg is allowed to look like, and the five places in `JoyBrushActivity` that change.**

## Cut from JB-3.02, and where it now lives

Read this before anything else — it names your job in the row's own words.

| Cut from JB-3.02 | Where it is now |
|---|---|
| `PaperOverlayView` — the transparent `View`, its draw list, `OnPeg`, `refresh()` | **This row.** |
| The `JoyBrushActivity.kt` edit (host the overlay, wire the callbacks) | **This row.** Five named regions, five edit sites. |
| "Where the peg bar sits" — the draft's Decision 13, *"one 56 dp row docked to the bottom, above whatever JB-2.01 puts there"* | **This row, and this row decides it.** The draft's **56 dp is superseded**: the bar's height is `PaperGeometry.PEG_PITCH_DP` = **44 dp**, because a peg's touch target IS the 44 dp pitch (JB-3.02 test 1 derives it) and a 56 dp row would be 12 dp of nothing. The bar's *width* is decided here for the first time: **the pegs' block plus one more pitch — 248 dp — and no wider** (Decision 3). |
| The rulers' 24 dp strips and hiding them while a stroke is in progress (draft Decision 14) | **This row**, drawing and hiding. The *number* stays in core as `PaperGeometry.RULER_THICKNESS_DP` — **use it, do not re-type 24f**. That number was *published* by JB-3.02 precisely because the strip is not drawn there: **JB-3.02 Question 10**, and the Cut table row that sent it here. |
| The colour mapping — which token each `PegStyle` becomes | **This row**, by name. Two of the draft's three names do not exist; see *The colour binding*. |
| Q2 (rulers): *"should the ruler be draggable to move the board's origin"*, *"should a centre line be drawn heavier"* | Q2's **first** half is answered here — **no gesture on a ruler, ever** (Decision 9). The second half is answered here too: **the zero tick is heavier, and there is no centre line** (Decision 10). The underlying product question stays in Questions, because it is the Lead's. |
| Q5 (the token pair) | **This row names every token**; the *pair itself* is the Lead's, and it is the one thing this row cannot finish (Questions 1). |
| The film strip's ± actions, drag-to-hold and scrub | **JB-3.03 / 3.03b.** Do not draw a strip. |
| The onion ghosts | **JB-3.04a / 3.04b.** `Peg.ONION` exists; its ghosts do not. |

---

## Contract (verbatim)

Every signature below is **read out of the landed tree** by the spec writer, with the file and line
after each one. Do not re-type them from memory; read the file.

### 1. What you must NOT re-declare (already landed — call these)

```kotlin
// joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/PaperGeometry.kt
// JB-3.02, Built. Every constant below is DP and is multiplied by density AT THE USE SITE (R32).
const val PEG_PITCH_DP = 44f          // PaperGeometry.kt:45
const val PEG_RADIUS_DP = 14f         // PaperGeometry.kt:49
const val PEG_HIT_RADIUS_DP = 22f     // PaperGeometry.kt:54
const val MIN_LABEL_DP = 48f          // PaperGeometry.kt:57
const val RULER_THICKNESS_DP = 24f    // PaperGeometry.kt:60

enum class PegStyle { IDENTITY, STATE_RING, ACTION }                    // :14
enum class PegPress { TAP, LONG_PRESS, HOVER }                          // :17
enum class Peg { PLAY, MODE, ONION, CADENCE, EXPORT }                   // :23

fun pegCentres(count: Int, barWidthPx: Float, density: Float = 1f): FloatArray   // :96
fun pegAt(xPx: Float, count: Int, barWidthPx: Float, density: Float = 1f): Int   // :134
fun ticks(viewStartDoc: Double, viewEndDoc: Double, boardOriginDoc: Double,
          screenPerDoc: Float, density: Float = 1f): LongArray                   // :168
fun label(tickDocRelToOrigin: Long): String                                      // :259
fun style(peg: Peg, active: Boolean): PegStyle                                    // :270-274  ← read ONION's arm. Decision 23.
```

```kotlin
// joybrush/androidkit/.../JbCanvasView.kt — read-only for you (R30 item 2: the Lead's file)
val view = ViewTransform()                       // :118  "Public so the screen can fit the board"
val strokeInProgress: Boolean                    // :196
var onViewMoved: (() -> Unit)?                   // :130
```

```kotlin
// joybrush/core/.../view/ViewTransform.kt
var zoom: Float                                    // :33  screen px per DOCUMENT px, clamped MIN_ZOOM..MAX_ZOOM
var rotation: Float                                // :40  radians, + is clockwise
var panX: Float; var panY: Float                   // :44, :48  screen position of document (0,0)
fun screenToDoc(x: Float, y: Float): Pair<Float, Float>   // :65  the exact inverse of docToScreen
const val MIN_ZOOM = 0.05f; const val MAX_ZOOM = 64f      // :156, :159
```

```kotlin
// joybrush/androidkit/.../tools/EyedropperRingView.kt is NOT the model. The model is:
// joybrush-android/.../chrome/ChromeKit.kt
class ChromeKit(val context: Context) {
    val p: Palette = JbColors.palette(context)       // :22
    fun dp(v: Float): Float                          // :25
    fun dpi(v: Float): Int                           // :26
    fun ink(alpha: Float): Int                       // :29   drawerInk at a strength
    fun chrome(): Int                                // :44   near-solid panel, CHROME_ALPHA
    fun chromeSurface(view: View, radius: FloatArray)// :47
    fun label(v: View, text: String)                 // :56   hover = TalkBack = tooltip
    fun drawSelected(c: Canvas, cx: Float, cy: Float, r: Float, paint: Paint)  // :62
    companion object { const val TOUCH_DP = 40f; const val CHROME_ALPHA = 230 } // :74, :77
}
```

### 2. NEW in core — the only pure file you create

```kotlin
package cc.joycreator.joybrush.core.anim

/**
 * The measured board, in DOCUMENT px, as the HOST reports it. This is the row's whole answer to
 * "where does an animation board's paper come from": the view never learns about boards, it is
 * handed one of these or null. (`PaperFrame` is a NEW name — `doc.Paper` already exists and is the
 * document's paper *setting*, `DocModel.kt:38`; see JB-3.02's Question 2.)
 */
data class PaperFrame(
    val originXDoc: Double = 0.0,
    val originYDoc: Double = 0.0,
    val widthDoc: Int = 0,
    val heightDoc: Int = 0,
) {
    /** A frame with no area or a non-finite origin is not something you can measure. */
    val measurable: Boolean
        get() = widthDoc > 0 && heightDoc > 0 && originXDoc.isFinite() && originYDoc.isFinite()
}

/**
 * Where the animation paper's furniture goes, and when it is on screen. Pure: no Android, no clock,
 * no file, no View. Every LENGTH constant below is **dp** and is multiplied by density **here, at
 * the use site** (R32) — a `const val` cannot be "already scaled", because density is a runtime
 * value. The three ALPHA constants are 0-255 and are not lengths, so R32 does not apply to them;
 * they are named here for the same reason everything else is: **a colour written in a Markdown
 * table is a colour nobody can test.**
 */
object PaperLayout {

    /** The bar's height, dp. Equals [PaperGeometry.PEG_PITCH_DP] and a test says so. */
    const val BAR_HEIGHT_DP = 44f

    /** The gap under the bar, dp: it never sits on the screen's own edge. */
    const val BAR_MARGIN_DP = 8f

    /** The gap between the bar (or a ruler) and the screen edge, or the tool strip, dp. */
    const val BAR_EDGE_GAP_DP = 8f

    /**
     * The surface **outboard of the bar's outer discs, each end**, dp.
     *
     * **This is NOT `PEG_RADIUS_DP`, and the difference is the whole of Decision 3.**
     * `pegCentres(5, 248f, 1f) == [36, 80, 124, 168, 212]`, so the first disc's left edge is at
     * `36 − 14 = 22`, not at 14. A previous draft of this spec called 14 dp "the bar's horizontal
     * padding" and it was simply not the number that ships. Test 7 pins the 22.
     */
    const val BAR_END_MARGIN_DP = PaperGeometry.PEG_PITCH_DP / 2f   // 22f

    /**
     * The bar's two **top** corner radii, dp. Its bottom two are square — it is docked to the
     * screen's own edge, which is the landed strip's exact idiom (`ToolStripView.kt:95-97`).
     *
     * 14 dp because that is the house value the strip uses on the same two corners; the *number*
     * is asserted here and the "it matches the strip" half is an eyeball item, not a test.
     */
    const val BAR_CORNER_RADIUS_DP = 14f

    /** The identity line along the bar's top edge, dp. The screen's own hairline is `kit.dpi(2f)` (`JoyBrushActivity.kt:272`). */
    const val ACCENT_LINE_DP = 2f

    /**
     * A ruler whose **measured axis** is shorter than this is not drawn at all (Decision 8).
     *
     * **It is a LENGTH, never a thickness.** The thickness is
     * [PaperGeometry.RULER_THICKNESS_DP] = 24 by definition and every strip is exactly that thick,
     * so testing the 24 dp thickness against a 64 dp floor would refuse every ruler on every
     * screen. The floor is on the top strip's width and the start strip's height — the directions
     * the ticks run in — and nothing else. Test 9 probes it at exactly 64 and at 40.
     */
    const val RULER_MIN_SPAN_DP = 64f

    /**
     * How far a page may be turned and still count as straight, radians.
     *
     * **7°, which is [ViewTransform.SNAP_DEGREES] (`ViewTransform.kt:161-162`) turned to
     * radians.** The same window the page snaps in, so the rulers are up on exactly the pages the
     * page itself calls square and they are gone the moment it stops calling it that — which is
     * what Owner's check step 7 describes, and it costs nothing extra to share the number.
     *
     * Written as the literal rather than imported because `PaperLayout` keeps to one package (see
     * the stop rule) and `PI` is not a constant expression so `const val` cannot hold it anyway.
     * **Test 13 pins this to `ViewTransform.SNAP_TOLERANCE` (`:168`), so the two copies of 7°
     * cannot drift** — the same discipline as test 6 holds over the two density guards.
     */
    const val PAGE_STRAIGHT_RAD = 0.12217305f      // 7 x PI / 180, to Float

    // ── the ruler's ink, every number named (Decision 10) ────────────────────

    /** The veil over the picture behind a strip: `p.ground` at black **20 %**. */
    const val RULER_VEIL_ALPHA = 51

    /** A tick line: `p.ink` at **35 %**. */
    const val RULER_TICK_ALPHA = 89

    /** The **zero** tick's line: `p.ink` at **85 %**. */
    const val RULER_ZERO_ALPHA = 217

    /** Every other tick's line width, dp. */
    const val RULER_TICK_DP = 1f

    /** The zero tick's line width, dp — heavier, and the only reason anyone can find the origin. */
    const val RULER_ZERO_TICK_DP = 1.5f

    /** A tick's number, dp, in `Typeface.MONOSPACE`. PROVISIONAL — Questions 6. */
    const val RULER_LABEL_DP = 10f

    // ── PLAY's two glyphs (Decision 17) ──────────────────────────────────────

    /** The triangle PLAY wears while it is playing, dp. */
    const val PLAY_TRIANGLE_DP = 10f

    /** One of the two bars PLAY wears while it is not, dp. */
    const val PLAY_BARS_DP = 2.5f

    /** A screen rectangle in **screen px**, y down. Four floats and nothing else. */
    data class Bar(val leftPx: Float, val topPx: Float, val rightPx: Float, val bottomPx: Float) {
        val widthPx: Float get() = rightPx - leftPx
        val heightPx: Float get() = bottomPx - topPx
    }

    /** The three ruler strips. The vertical one is on the **start** edge, whichever side that is. */
    data class Rulers(val corner: Bar, val top: Bar, val start: Bar)

    /**
     * How wide a bar of [count] pegs must be, in px at [density]: **one
     * [PaperGeometry.PEG_PITCH_DP] per peg, plus one [PaperGeometry.PEG_RADIUS_DP] of surface each
     * end** — `count × pitch + 2 × radius`, which is **248** for the five-peg bar and **72** for a
     * single peg at density 1, and those are the two numbers tests 1 and 8 pin.
     *
     * **Why a whole pitch per peg, and not the pegs' own block.** The block the discs occupy is
     * `(count − 1) × pitch + 2 × radius` — **204 dp** for five pegs, and that is exactly the width
     * `PaperGeometry.pegCentres` treats as "fits" (`PaperGeometry.kt:104`). At 204 the outer pegs'
     * centres are at 14 and 190, so each one's 44 dp touch target hangs **8 dp off the end of the
     * surface**, and a touch target that runs off the end of the control is not a touch target
     * (Decision 13 places the children at `centre − pitch / 2`). One more pitch moves each end
     * [BAR_END_MARGIN_DP] = 22 dp outboard of the outer disc and puts every child wholly on the
     * surface. Decision 3.
     *
     * So **248 is not "the pegs' width"** — 204 is — it is the pegs' width plus one pitch. The
     * earlier wording of Decision 3 ("exactly as wide as its pegs, and never wider") was false at
     * every count above one, and it is the *number*, not the wording, that reaches the tier table,
     * the worked table, the Owner's check and Questions 4.
     */
    fun pegBarWidthPx(count: Int, density: Float = 1f): Float

    /** Where the peg bar goes, or **null** when the free band cannot hold even one peg (Decision 4). */
    fun pegBarRect(screenWidthPx: Float, screenHeightPx: Float, stripWidthPx: Float,
                   stripOnStart: Boolean, density: Float = 1f): Bar?

    /**
     * The centre of peg [index] in **BAR** coordinates — the same coordinates and the same
     * [barWidthPx] that [PaperGeometry.pegAt] is given, so a peg's drawn centre and its hit test can
     * never be two different numbers. An index outside `0 until count` is clamped, never an exception.
     *
     * Note what is NOT here: the end margin is **not** added again. `pegCentres` already centres the
     * block in [barWidthPx] and [pegBarWidthPx] is that width *including* the margin, so the first
     * centre falls at `pitch / 2 + radius` = **36** on its own. Adding the margin again gives 58 and
     * is the obvious bug; test 7 is what catches it.
     */
    fun pegCentreX(index: Int, count: Int, barWidthPx: Float, density: Float = 1f): Float

    /**
     * The three ruler strips, or **null** when one of them would be a sliver (Decision 8).
     *
     * **[stripOnStart] is a parameter, never something the view works out from the strip's
     * geometry.** The strip's `left` reads `0` when the strip is `GONE` (`left == right == 0`) and
     * on every frame before the first layout pass — which is the exact frame Decision 12's
     * `OnPreDrawListener` exists to cover. A sidedness inferred from a coordinate that is zero for
     * two different reasons is a sidedness that will be wrong once, silently. Decision 7.
     *
     * The parameter is load-bearing in the other direction too: **when it is false the start edge
     * reserves nothing, whatever [startInsetPx] says**, because the strip is on the other side and
     * the ruler is on the start edge either way.
     *
     * **There is no `screenHeightPx`.** The vertical strip ends at [barTopPx], so the screen's
     * height never enters the answer; a parameter no branch reads is a parameter one day gets used.
     */
    fun rulerRects(screenWidthPx: Float, topInsetPx: Float, startInsetPx: Float, stripOnStart: Boolean,
                   barTopPx: Float, density: Float = 1f): Rulers?

    /**
     * Whether a piece of the animation paper's **chrome** is up at all. **ONE predicate, TWO
     * consumers**, so the peg bar and the rulers cannot disagree about a panel or a four-finger tap
     * (Decisions 19, 20).
     *
     * - [shown] — the host has an animation board. This is `PegBarView.shown` /
     *   `PaperOverlayView.shown`, and it is **false on this screen** (Decision 22).
     * - [panelOpen] — a popover or sheet is up (Decision 19).
     * - [chromeShown] — the four-finger toggle has not taken the chrome away (Decision 20).
     */
    fun chromeUp(shown: Boolean, panelOpen: Boolean, chromeShown: Boolean): Boolean

    /**
     * Whether the rulers are on screen. **ALL SIX must hold:** [shown], a measurable [frame], the
     * page straight to within [PAGE_STRAIGHT_RAD], no stroke in progress, no panel open, and the
     * chrome showing. The last three are [chromeUp] — which is the "one condition, two consumers,
     * one pure function" Decision 19 claims, and it is now actually built rather than half-built.
     */
    fun rulersVisible(shown: Boolean, frame: PaperFrame?, rotationRad: Float, strokeInProgress: Boolean,
                      panelOpen: Boolean, chromeShown: Boolean): Boolean

    /**
     * The ONE `active` boolean [PaperGeometry.style] wants, per peg, from the bar's two states.
     *
     * **ONION IS INVERTED HERE, AND THAT IS DELIBERATE — read Decision 23 before "fixing" it.**
     * Landed `PaperGeometry.style` gives ONION `IDENTITY` when active and `STATE_RING` when not
     * (`PaperGeometry.kt:272`, pinned by JB-3.02 test 14 at
     * `JB-3.02_animation_paper.md:311-314`), so handing it the onion state straight round puts the
     * cyan *selected* ring on the ONION peg while onion skin is **off**. This function passes the
     * **negation**, so a ring means what a ring means everywhere else in the app.
     *
     * The inversion lives in **this row's file** because this row's file is the only one it may
     * touch; Questions 9 asks for `style()` to be corrected at source, and the day it is, the `!`
     * goes and **test 11 goes red**, which is the intended alarm.
     */
    fun active(peg: Peg, playing: Boolean, onionOn: Boolean): Boolean

    /** The hover label / TalkBack name / tooltip for a peg. The five are distinct and none is blank. */
    fun label(peg: Peg): String

    /**
     * Everything the ruler's answer depends on. The view caches its ticks against this and rebuilds
     * when it changes — so this type is the cache's correctness, not an optimisation (Decision 12).
     */
    data class RulerKey(
        val viewStartDoc: Double,
        val viewEndDoc: Double,
        val boardOriginDoc: Double,
        val screenPerDoc: Float,
        val density: Float,
    )

    fun rulerKey(viewStartDoc: Double, viewEndDoc: Double, boardOriginDoc: Double,
                 screenPerDoc: Float, density: Float = 1f): RulerKey
}
```

**The density guard is written twice, on purpose, and a test holds the two copies together.**
`PaperGeometry.scaleOf` is **private** (`PaperGeometry.kt:287`) and `PaperGeometry.kt` is JB-3.02's
landed file — not yours to edit. So `PaperLayout` applies the same one-line rule ("not finite or ≤ 0
becomes `1f`") and **test 6 proves the two files agree** by comparing answers across a density sweep
that includes `0f` and `-1f`. If you ever find yourself wanting to edit `PaperGeometry.kt`, that is
the signal to write a Question instead.

### 3. NEW in `joybrush-android` — the two views

```kotlin
package cc.joycreator.joybrush.android.anim

/**
 * The pixel rulers (JB-3.02's second half, this row's first): two strips of ticks and labels along
 * the top and **start** edges, in document pixels measured from the board's own corner.
 *
 * IT TAKES NO TOUCHES. There is no gesture on a ruler (Decision 9), so the view has no `onTouchEvent`
 * at all and a finger anywhere on it falls straight through to the canvas — the same decorative-view
 * shape as the landed `ValueHud` (`ValueHud.kt:33-35`).
 */
@SuppressLint("ViewConstructor")
class PaperOverlayView(private val kit: ChromeKit, private val source: Source) : View(kit.context) {

    /** Everything the host knows and the view does not. Four one-line answers. */
    interface Source {
        /** The page's transform, or null when there is no page. */
        fun transform(): ViewTransform?
        /** The measured board, or **null** when there is none. This is the show/hide of the paper. */
        fun frame(): PaperFrame?
        fun strokeInProgress(): Boolean
        fun panelOpen(): Boolean
    }

    /**
     * The host has an animation board. **Default false: this screen has none** (see the Activity
     * section), and the setter OWNS `visibility` — that is the load-bearing half of the sentence.
     *
     * A `shown` that only stopped the drawing would leave a `MATCH_PARENT` view sitting over the
     * canvas, invisible but still there, eating the canvas's first `ACTION_DOWN` and returning
     * `false` for ever. So the setter writes `visibility = VISIBLE / GONE` itself and calls
     * `invalidate()` (the landed [TopButton.on] idiom, `TopButton.kt:21-22`), `init` calls it once,
     * and a future row that writes `shown = true` gets rulers; a future row that forgets gets
     * nothing at all, which is what this screen is supposed to look like.
     */
    var shown: Boolean = false
        set(value) { if (field != value) { field = value; applyVisibility() } }

    /** The four-finger toggle (JB-2.01's `toggleChrome`). Default true; the host writes it. Same shape. */
    var chromeVisible: Boolean = true
        set(value) { if (field != value) { field = value; applyVisibility() } }

    /** The top reserve, px — the top bar's height plus its margin (`TOP_RESERVE_DP`). */
    var topInsetPx: Float = 0f
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * The tool strip, or **null** when there is none. **Add it to the parent BEFORE this view.**
     * See Decision 5.
     *
     * Typed `ToolStripView?`, not `View?`, because the overlay reads the strip's **live side** off
     * it on every layout pass. That is what puts the vertical ruler's sidedness on a fact
     * (`stripOnStart`) instead of on a coordinate that is zero for two different reasons, and it
     * is why dragging the strip takes the ruler with it for free (Decision 7).
     */
    var strip: ToolStripView? = null

    /** The peg bar, if any. **Add it to the parent BEFORE this view.** See Decision 5. Bounds only. */
    var pegBar: View? = null

    /**
     * Which edge the vertical ruler is on — **read from [strip]'s own `edge`, never inferred from
     * its geometry.** `true` when there is no strip. Written by `onLayout` and by nothing else,
     * which is why the Activity does not set it and why the spec never says "infer".
     */
    var stripOnStart: Boolean = true
        private set
}

/**
 * The peg bar (JB-3.02's first half, this row's second): one row of five pegs, **PLAY · MODE · ONION ·
 * CADENCE · EXPORT** in that order, on a neutral chrome surface with the animation board's identity as
 * a 2 dp line along its top edge. The pegs ARE the buttons (R33): the bar owns PLAY and MODE, and the
 * film strip has prev/next and nothing else, so this is the ONE place on the animation board that can
 * start playback.
 */
@SuppressLint("ViewConstructor")
class PegBarView(private val kit: ChromeKit, private val host: Host) : LinearLayout(kit.context) {

    interface Host {
        fun pegTapped(peg: Peg)
        fun pegLongPressed(peg: Peg)
        /**
         * Whether a panel is open **right now**. Read on every window frame and never stored, so no
         * call site of `Popovers.show` / `showSheet` has to be touched for Decision 19 to work.
         *
         * It is here, and not a `panelOpen` property, for two reasons: `popovers` is **`private`** to
         * `JoyBrushActivity.kt` and a view cannot reach it, and the two hosts already exist as this
         * row's Region 3 — so this costs the Activity **one line inside a region it already owns**
         * instead of five edits at five `Popovers` call sites (`:520`, `:576`, `:628`, `:661`, `:750`)
         * which is precisely the serialised-file churn R30 item 1 exists to prevent.
         */
        fun panelOpen(): Boolean
    }

    /**
     * The host has an animation board. **Default false: this screen has none.** The setter owns
     * `visibility`, and here that is not tidiness, it is the difference between a correct screen and
     * a broken one:
     *
     * - `onLayout` can only tie `GONE` to `pegBarRect` being null, and on the Note 9 that rect is
     *   **not** null (548 dp × 2.625 = 1439 px wide; the bottom band is 1292 px, far more than the
     *   bar's 189 px), so a bar whose only hide were the null rect would lay out **VISIBLE**.
     * - `init` applies the surface and adds five children unconditionally, and `kit.label(child, …)`
     *   (`ChromeKit.kt:56-59`) gives each a **live TalkBack node and a tooltip** reading "Play",
     *   "Mode", "Onion skin", "Cadence", "Export" — over listeners that are `= Unit`.
     *
     * That is the defect `JoyBrushActivity.kt:355` calls *"a button that does nothing is worse than
     * none"*, this project's own rule (Decision 4). So: the setter writes `visibility` itself and
     * calls `invalidate()` (the `TopButton.on` idiom, `TopButton.kt:21-22`), and `init` calls
     * `applyVisibility()` once so the bar is `GONE` from the first frame.
     */
    var shown: Boolean = false
        set(value) { if (field != value) { field = value; applyVisibility() } }

    /**
     * The four-finger toggle (JB-2.01's `toggleChrome`). Default true; the host writes it.
     *
     * Its setter runs **the same 170 ms shape the Activity's own loop runs** and then calls
     * `applyVisibility()`, so the bar fades with the chrome and still ends up obeying `shown` and
     * `host.panelOpen()`. That is what lets the Activity's edit be **three lines** instead of a
     * rebuilt list (Region 4), and it is why the bar cannot be revealed by a four-finger tap that
     * was correctly hiding it.
     */
    var chromeVisible: Boolean = true
        set(value) { if (field != value) { field = value; fade() } }

    /**
     * The tool strip, or **null** when there is none. **Add it to the parent BEFORE this view.**
     * See Decision 5.
     *
     * Typed, not `View`, for the same reason `PaperOverlayView.strip` is: the bar re-reads the
     * strip's **live** width and side every layout pass and re-places itself when either changed,
     * under the landed "only if changed" guard (`JoyBrushActivity.kt:480-484`). Owner's check
     * step 2 — *drag the strip to the other edge and the bar re-centres* — therefore works with no
     * wiring at all. Written once at construction it would not.
     */
    var strip: ToolStripView? = null

    /** PLAY is doing something; ONION is on. Nothing else has a state (JB-3.02 Decision 4). */
    fun showStates(playing: Boolean, onionOn: Boolean)

    /**
     * The one saturated control's fill, or **null** until the token pair lands (Questions 1). With it
     * null the ACTION peg draws as `ChromeKit.drawSelected`, which is a fallback, not the design.
     */
    var actionGradient: GradientDrawable? = null
}
```

---

## The colour binding, by name

**Every token in this table was read out of the landed files. The method is stated so you can repeat
it; a token you guessed is a build error on somebody else's machine.**

| Element | Asks for | By what name | Verified at |
|---|---|---|---|
| A peg that is `PegStyle.IDENTITY` | the neutral raised fill | `kit.p.raised` | `JbColors.kt:143` (`jb_raised` → `s_raised`) |
| A peg that is `PegStyle.STATE_RING` | the shared selected ring, verbatim | `kit.drawSelected(canvas, cx, cy, r, paint)` — **do not re-implement it** | `ChromeKit.kt:62-70`; uses `p.stateSelected` (`JbColors.kt:131`) and `ink(0.12f)` |
| The peg's glyph on a neutral fill | the drawer ink, as the strip's own buttons do | `kit.p.drawerInk` | `JbColors.kt:152`; the landed precedent is `ToolStripView.kt:136,159` |
| The peg's glyph on the ACTION fill | ground | `kit.p.ground` | `JbColors.kt:138`. The app's own ink on the GO gradient is `Studio.ON_GO = 0xFF050507` (`studiokit/.../Studio.java:143`) and `#000000` vs `#050507` is invisible on a disc; **Questions 1** asks whether a proper `s_on_go` mirror is wanted |
| The bar's surface | the near-solid chrome panel | `kit.chromeSurface(this, cornerRadii)` | `ChromeKit.kt:47-53`; same idiom as `ToolStripView.kt:97` |
| The bar's **identity line**, `ACCENT_LINE_DP` along its top edge | the animation board's gradient | `JbColors.boardGradient(context, BoardKind.ANIMATION)` | `JbColors.kt:62-72`; the pixels are `jb_board_animation_start/end` (`jb_tokens.xml:58-59`) |
| A ruler strip's veil over the picture | `ColorUtils.setAlphaComponent(kit.p.ground, PaperLayout.RULER_VEIL_ALPHA)` | **51** | black 20 %; the landed idiom is the same call at `ValueHud.kt:88` (153) and `:53` (128); the 20 % figure is `JOYBRUSH_VISUAL_LANGUAGE.md` §1.6. **Test 13 asserts 51 == round(0.20 × 255).** |
| A tick line | `ColorUtils.setAlphaComponent(kit.p.ink, PaperLayout.RULER_TICK_ALPHA)`, `strokeWidth = kit.dp(RULER_TICK_DP)` | **89** at **1 dp** | `p.ink` is `JbColors.kt:146`. **Test 13 asserts 89 == round(0.35 × 255).** |
| The **zero** tick's line | `ColorUtils.setAlphaComponent(kit.p.ink, PaperLayout.RULER_ZERO_ALPHA)`, `strokeWidth = kit.dp(RULER_ZERO_TICK_DP)` | **217** at **1.5 dp** | the house's two weights are the 1 dp hairline ring and the 1.5 dp selection, `JOYBRUSH_VISUAL_LANGUAGE.md` §1.8 `:143` and `:145`. **Test 13 asserts 217 == round(0.85 × 255) and that 1.5 > 1.** |
| A tick's number | `kit.p.ink` at `PaperLayout.RULER_LABEL_DP` = **10 dp**, `Typeface.MONOSPACE` | **10** | `ValueHud.kt:24-30` is the landed mono-in-Joy-Brush precedent (`Typeface.MONOSPACE` at `:27`, `kit.dp(13f)` at `:29`); 10 dp vs 13 dp is **PROVISIONAL — Claude to confirm**; §1.7 puts numbers in Plex Mono (`:107`) |
| PLAY's triangle, while playing | `PaperLayout.PLAY_TRIANGLE_DP` | **10 dp**, filled in `kit.p.ground` | Decision 17 |
| PLAY's two bars, while not | `PaperLayout.PLAY_BARS_DP` | **2.5 dp** | Decision 17 |
| A peg's name | `PaperLayout.label(peg)` | — | in core, test 12 |

**Every number in the right-hand columns above is a `PaperLayout` constant, not prose.** An earlier
draft of this table carried all nine of them here and only here — a 51, an 89, a 217, a 1.5, a 1, a
10, a 10, a 2.5 — where the arithmetic was correct and **untestable**, because the only thing a
Markdown table can assert about `round(0.20 × 255)` is that a human read it right once. They are
constants now and **test 13 pins each one to the arithmetic it claims to be.** The percentages still
read `20 % / 35 % / 85 %` in Decision 10, because that is the sentence a reader wants; the numbers
that ship are the constants, and test 13 is what holds the two together.

**⛔ The one pair this row cannot supply: the `ACTION` fill.** `PegStyle.ACTION` is the app's action
pill, and the app's action pill is a **drawable** (`@drawable/studio_action_pill`) that
`joybrush-android` cannot see — its own resources cannot reference the consuming app's, which is the
whole reason `jb_tokens.xml` exists (`jb_tokens.xml:5-24`). `JOYBRUSH_VISUAL_LANGUAGE.md` §1.4 names
the two stops: `#35F6BF → #97FE8B`, i.e. `s_go` / `s_go_end` (`studio_tokens.xml:81-82`, `Studio.java:141-142`).

**Do NOT write a hex literal, and do NOT widen `JbColors.Palette`.** Take the nullable
`actionGradient` property as it is, draw the fallback, and leave the one-line seam.

### The finding that makes this a question and not a decision

`jb_board_animation_start` / `jb_board_animation_end` are `#FF35F6BF` / `#FF97FE8B`
(`jb_tokens.xml:58-59`). `s_go` / `s_go_end` are `#FF35F6BF` / `#FF97FE8B` (`studio_tokens.xml:81-82`).
**The animation board's identity pair and the app's action pair are byte-equal today.**

The project already knows this and made a rule of it — `Studio.java:156-162`:

> `ROOM_STUDIO` … `ROOM_STUDIO_END = 0xFF97FE8B` — *"Equal to GO_END by value and deliberately a
> separate name: GO is an ACTION, the Studio room is a PLACE, and video tapes wear the [Studio room]."*

So identity and action are allowed to be the same two values **as long as they are not the same
control.** Which is exactly why this row does *not* fill four IDENTITY pegs with the board gradient:
`JOYBRUSH_VISUAL_LANGUAGE.md` §1.4 says **"One saturated control per screen"**, and §4.3:297-298 says
**"The board's edge stays neutral (LINE / white 10 %)."** Decision 4 below applies both.

---

## Placement: the peg bar and the rulers, in dp

**R32's rule applies to every number in this section: it is dp, and it is multiplied by density at the
use site, never pre-scaled.** A `const val` cannot be "already × density" — density is a runtime
value. This is the exact slip the JB-3.02 review caught in its draft.

### The peg bar

| | dp | From |
|---|---|---|
| Height | **44** | `PaperGeometry.PEG_PITCH_DP` — a peg's touch target *is* the pitch |
| Gap under the bar (to the screen's bottom edge) | **8** | `BAR_MARGIN_DP` |
| Gap to the screen edge, or to the tool strip | **8** | `BAR_EDGE_GAP_DP` |
| Surface outboard of each outer disc | **22** | `BAR_END_MARGIN_DP` = `PEG_PITCH_DP / 2` — **not** `PEG_RADIUS_DP`; see below |
| Top corner radii (bottom two are square) | **14** | `BAR_CORNER_RADIUS_DP` — the house value, the landed strip's own (`ToolStripView.kt:95`) |
| Identity line along the top edge | **2** | `ACCENT_LINE_DP` — the screen's own hairline is `kit.dpi(2f)` (`JoyBrushActivity.kt:272`) |
| Width | **`5 × 44 + 2 × 14 = 248`**, or the free band if that is less | Decision 3 |
| Horizontally | **centred on the free band**, which is the screen minus the strip's width on its own edge | Decision 4 |

**On 248, precisely — the number is easy to mis-attribute and it used to be.** The pegs' discs occupy
`(5 − 1) × 44 + 2 × 14 = 204` dp, and **204 is the width `PaperGeometry.pegCentres` treats as
"fits"** (`PaperGeometry.kt:104`). 248 is that **plus one more pitch**: `248 = 204 + 44`, which is
`5 × pitch + 2 × radius`, half a pitch of surface at each end. The reason for the extra 44 is not
elegance — at 204 the outer centres are at 14 and 190, so each outer peg's **44 dp touch target
hangs 8 dp off the end of the surface**, and Decision 13 places children at `centre − pitch / 2`.
204 is the pegs' width; 248 is the control's. **Decision 3 says so, and test 5 proves the clipping
that 248 exists to avoid.**

Worked, at three densities. Every expected value carries its arithmetic in the test, not just the
answer.

| | density 1 | density 2.625 (the Note 9's dense setting — JB-2.01 measured **548 dp** wide) | density 3 |
|---|---|---|---|
| Bar width | 248 px | 651 px | 744 px |
| Bar height | 44 px | 115.5 px | 132 px |
| Gap under | 8 px | 21 px | 24 px |
| Share of a 548 dp screen | — | **248 of 548 dp = 45 %** | — |

The proportion is the number that matters and it is stated in dp on purpose: **the bar is 45 % of the
Note 9's width**, which leaves the whole rest of the bottom edge for the picture and for nothing else.
It is a bar of five buttons, not a toolbar.

### The rulers

| | dp | From |
|---|---|---|
| Strip thickness (both axes) | **24** | `PaperGeometry.RULER_THICKNESS_DP` — **read it, do not re-type 24f** |
| Top strip | sits **below** the top reserve, full width of the free band | the top bar is 40 dp of buttons plus 6 dp of margins |
| Vertical strip | on the **start** edge (left in LTR), **inboard of the tool strip when the strip is there** | Decision 7 |
| Its bottom end | stops **above the peg bar** | they must never overlap |
| Corner | the square where the two meet | one `Bar`, drawn like the others |
| Minimum span **along the strip's own axis** | **64**, else not drawn at all | Decision 8 |
| Ticks | `PaperGeometry.ticks(...)`, board-relative, `PaperGeometry.label(...)` | JB-3.02's own maths |
| Every tick's screen x | `(boardOrigin + tick) − pan` scaled by zoom, i.e. **`transform.screenToDoc` in reverse** — never a re-derived formula | Decision 11 |

**The vertical ruler does not change sides when the strip is dragged.** A ruler that jumped edges
when you moved the strip would be a ruler you had to re-find; it hugs the **start** edge always and
moves *inboard of the strip* only when the strip is on that edge. So `stripOnStart` decides exactly
one thing — whether the start edge's strip reserves its width inboard of the ruler or reserves
nothing — and `sidedness` is a constant of the layout, not a function of the chrome. **It is passed
in, never inferred:** see Decision 7 and the `rulerRects` KDoc for why `strip.left == 0` is not a
fact.

---

## Decisions already made

1. **The maths goes in `core/anim/PaperLayout.kt`; the views hold layout, touch and drawing only.**
   This is the project's established shape (JB-2.02's `ViewTransform`, JB-3.02's `PaperGeometry`,
   JB-4.01a's `SpriteGridMath`): a pure function in core is a `jvmTest` case; a `View.onDraw` is a
   phone. Six decisions live in core because they are arithmetic — the bar's rect, the peg centres,
   the rulers' rects, when the rulers are up, the pegs' labels, and the cache key.

2. **The peg bar is the BOTTOM edge, centred, and 44 dp tall** — the draft's "56 dp row" is
   superseded because 56 is not any of the numbers this bar is made of. The bottom is the only free
   edge: JB-2.01's landed chrome takes the top (`topBar`), one **side** (the strip, which is
   *draggable to either side*), and the middle (`ValueHud`). A bar on the top would fight the top bar;
   a bar on a side would fight a control that moves.

3. **The bar is the pegs' block plus ONE MORE PITCH, and never wider** — `pegBarWidthPx(count, d) =
   (count × pitch + 2 × radius) × d`, so **248 dp** for five pegs at density 1. `pegCentres` keeps
   the pitch at `PEG_PITCH_DP × density` whenever the block fits, so a *full-width* bar would place
   the pegs identically — but its **surface** would stretch across a tablet, and a 1500 dp bar of
   five buttons is a toolbar, not a peg bar. When the free band is narrower than the pegs need, the
   width is the free band and **`pegCentres` shrinks the pitch** (its Decision 6) rather than
   clipping.
   **Why the extra pitch, because the old sentence here was simply wrong.** "Exactly as wide as its
   pegs, and never wider" is false for every count above one: five pegs' discs occupy
   `(5 − 1) × 44 + 2 × 14 = 204` dp, and at 204 the outer pegs' centres are at 14 and 190, so **each
   outer peg's 44 dp touch target hangs 8 dp off the end of the surface** (Decision 13 places
   children at `centre − pitch / 2`). 248 puts 22 dp of surface outboard of each outer disc, which
   is `BAR_END_MARGIN_DP` and **not** `PEG_RADIUS_DP`. Test 5 pins the clipping; test 7 pins the 22.

4. **The bar is centred on the FREE BAND, and returns `null` rather than sit under the strip.** The
   band runs from `stripWidth + 8 dp` to `screenWidth − 8 dp` when the strip is on the start edge, and
   the whole screen otherwise. A bar narrower than one peg (`pegBarWidthPx(1, density)` = 72 dp) is
   **not drawn at all** — a control under another control is a control that cannot be pressed, and
   this project's rule is "no button that does nothing" (`JoyBrushActivity.kt:355`).

5. **Three siblings, added to the parent in the order strip → peg bar → overlay, and the overlay and
   the bar read the others' measured bounds in their own `onLayout`.** `FrameLayout.onLayout` lays
   children out in the order they were added, so a view added later sees the earlier ones' final
   `left`/`top`/`right`/`bottom` — that is the whole mechanism, and it is why **`JoyBrushActivity`
   adds them last** (`Decision 3` in the Activity section). The alternative — the Activity pushing
   insets on every layout pass — is four more lines in the file R30 serialises.
   **If you swap the add order the insets are one frame stale and nobody will notice for a week.**

6. **`PegStyle.IDENTITY` is a NEUTRAL raised fill, not the board gradient.** §1.4 allows one
   saturated control per screen; the animation board's identity pair is byte-equal to the app's action
   pair (see the finding above), so four gradient-filled pegs would be four saturated controls. The
   board's identity reaches the bar through a **2 dp accent line along its top edge** instead — the
   screen's own hairline idiom, `JoyBrushActivity.kt:271-272` — which is identity without saturation.
   JB-3.02's own Decision 5 is honoured: a peg is a **fill**, never a ring, unless it is showing a
   state.

7. **The vertical ruler lives on the start edge and is inboard of the tool strip — and its side is
   a PARAMETER, not an inference.** See the table above for why it does not change sides when the
   strip is dragged. `rulerRects` takes `stripOnStart` as an argument, `PaperOverlayView` refreshes
   it from `strip.edge` on every layout pass, and **nothing anywhere infers it from a coordinate.**
   The reason is not fussiness: a `GONE` strip reports `left == right == 0`, and so does every frame
   before the first layout pass — **the exact frame Decision 12's `OnPreDrawListener` exists to
   cover**, and the one frame where a ruler most needs to be right. A sidedness read off
   `strip.left == 0` is right on the start edge and wrong on the end edge *and on the frame before
   anything has been measured*, which is precisely the class of bug that survives to a phone.

8. **A ruler that would be a sliver is not drawn at all** (`RULER_MIN_SPAN_DP = 64`), and **the 64 is
   a LENGTH, never a thickness.** Every strip is `RULER_THICKNESS_DP` = 24 dp thick by definition,
   so a rule that tested thickness would refuse every ruler on every screen; the 64 is measured
   against the direction the ticks run in — the top strip's width and the start strip's height.
   **The honest derivation, because the old one was backwards:** 64 is `MIN_LABEL_DP` (48) plus a
   margin, so a strip always has room for one number in it, and 64 is below `2 × 48`, so two can
   never collide. (An earlier draft claimed 64 was "well under the 48 dp minimum label spacing".
   64 is a third **above** 48 — the argument inverted itself.) Test 9 probes the floor at exactly 64
   and at 40, and test 13 pins both halves of the inequality, so a retune of either constant is red.
   **The second way a ruler is not drawn, and this one is a straight edge rather than a sliver: a
   TURNED page.** `rulersVisible` requires `abs(rotationRad) <= PAGE_STRAIGHT_RAD` — **7°**, the
   same window `ViewTransform` snaps in (`ViewTransform.kt:161-162`). A measuring scale on a page
   that is being turned is a scale whose numbers move while you read them, and past 7° the top strip's
   own endpoints are no longer horizontal, so the ladder would have to lie about the axis it draws
   along. Sharing the snap window means the rulers are up on exactly the pages the page itself calls
   square. Test 10 probes both signs of the boundary, one float ULP outside it, `0.001f`, and
   `NaN` — so no epsilon in `(0, 0.001]` can be slipped in, which is what happened when the threshold
   had no number at all.

9. **There is NO gesture on a ruler, and none may be added by this row.** JB-3.02's Q2 asked whether
   a ruler should be draggable to move the board's origin; the answer here is **no, and never on the
   ruler either** — this project has already ruled that fingers navigate and never draw (owner,
   2026-09-28), and a draggable ruler is a second, invisible canvas gesture. The underlying product
   question is referred, not answered (Questions 5). `PaperOverlayView` therefore has **no
   `onTouchEvent`**, and any touch on it falls through to the canvas exactly as it does over
   `ValueHud` today (`ValueHud.kt:33-35`).

10. **The zero tick is heavier; there is no centre line.** Zero gets ink at 85 % and 1.5 dp; every
     other tick 35 % and 1 dp — the two are `RULER_ZERO_ALPHA` / `RULER_TICK_ALPHA` and
     `RULER_ZERO_TICK_DP` / `RULER_TICK_DP` in core, **not** four numbers typed into a `ColorUtils`
     call, and **test 13 pins each to the percentage it claims**. The board's centre is **not**
     drawn, because a board that is not an odd number of cells has no middle, and a line drawn at
     the geometric centre would be a lie about where the board is. This answers the second half of
     JB-3.02's Q2 and is the view statement that row explicitly cut to you.

11. **Every tick's screen position comes from `ViewTransform.screenToDoc`, never from a re-derived
    formula.** The view takes the two screen x (or y) values at the strip's ends, calls
    `screenToDoc` to get the document range, hands that plus `frame.originXDoc` and `view.zoom` to
    `PaperGeometry.ticks(...)`, and puts each tick back with the same transform. R19: the zoom is
    screen px per document px, **never its inverse** (JB-2.16a's finding).

12. **The overlay re-renders when the window draws; it does not run a clock.** The view registers a
    `ViewTreeObserver.OnPreDrawListener` on itself and calls `postInvalidateOnAnimation()` whenever it
    is up. `onPreDraw` fires once per frame of the whole window — which is exactly when the canvas has
    just panned, zoomed, turned, committed a stroke or closed a panel, and **does not fire at all when
    the screen is idle**. So the rulers re-ladder on every zoom with **no callback wiring in the
    Activity** (four `invalidate()` calls in four existing lambdas would have been the alternative),
    no battery cost while nothing moves, and every state change self-settles. The deliberate cost is
    **one frame of lag**, invisible on a measuring scale. Remove the listener in
    `onDetachedFromWindow`, guarding `viewTreeObserver.isAlive`.

13. **The peg bar's children are five real `View`s, positioned from `pegCentreX`.** Five children is
    how five controls get five TalkBack nodes, five hover tooltips and five content descriptions from
    the one `kit.label(...)` call (`ChromeKit.kt:56-59`) — a single custom-drawn view would need an
    `AccessibilityNodeProvider` and would have none. Each child is `PEG_PITCH_DP × density` wide and
    sits at **`pegCentreX(i) − pitch / 2`**, never at `padding + i × pitch`: on a narrow bar the pitch
    **shrinks** (core's Decision 6) and a pitch-laid-out row would put the children somewhere
    `pegAt` does not look.

14. **Each peg child asks `pegAt` before it claims a touch, in BAR coordinates with the bar's full
    width, and returns `false` when the answer is not its own index.** At the normal 44 dp pitch the
    hit radius (22 dp) is exactly half the pitch, so every point of a child is inside its own radius
    and the check always passes. **When the pitch has shrunk the hit radius is larger than the
    pitch**, two children's zones overlap, and Android's "last child whose bounds contain the point"
    would give the wrong peg. `pegAt` is the arbiter, and its "an exact tie goes to the LOWER index"
    rule (`PaperGeometry.kt:142`) is what makes that deterministic. A child that loses the check
    returns `false`, and the event falls through to the canvas — never swallowed.

15. **The five labels are the bare peg names.** `"Play"`, `"Mode"`, `"Onion skin"`, `"Cadence"`,
    `"Export"`. No `"— tap to …"` clause, because the clauses belong to rows that do not exist yet
    (`Peg.ONION` is JB-3.04's, `Peg.EXPORT` is JB-3.06b's, and **nothing in the tree has ever
    defined what `Peg.MODE` is** — Questions 3). Inventing copy here is how three rows end up
    describing three different buttons. `Peg.PLAY`'s label is pinned by a test to be exactly `"Play"`.

16. **`PegPress.HOVER` is served by the tooltip, so there is no hover menu.** `kit.label()` sets the
    content description, the TalkBack name *and* the tooltip; that is the hover. JB-3.02's Decision 3
    says so, and the landed `ToolStripView` is the model (`ToolStripView.kt:71-84`).

17. **No glyphs except PLAY's.** `JbIcon` has nine icons and **none of the five pegs is one of them**
    (`JbIcon.kt:17-26`); its KDoc says the icons were *"Drawn by the Joy Brush Lead for this app"*,
    so inventing five path strings here is both out of area and off-house. A peg is a plain disc —
    which is what a music-box peg is — and **PLAY carries a 10 dp triangle** (three `Path` lines,
    filled in `kit.p.ground`) while it is playing and **two 2.5 dp bars** while it is not, because a
    saturated play control with no play mark reads as decoration. The four remaining glyphs are a
    Lead item (Questions 8).
    **Both glyph sizes are constants** — `PLAY_TRIANGLE_DP` = 10, `PLAY_BARS_DP` = 2.5 — and test 13
    pins them, because a glyph drawn at `kit.dp(10f)` in a `when` arm is a glyph nobody can change
    without finding every copy.

18. **`ON_GO` is not a colour this row can use, and it was never one.** The draft dressed pegs in
    `ON_GO`; read the app: `Studio.ON_GO = 0xFF050507` is **the ink that sits ON the gradient**
    (`Studio.java:143`, mirrored as `s_on_go` at `studio_tokens.xml:85`), used as a *text colour* at
    `SheetKit.java:599` and `tools/TextOverlayDrawer.java:141`. The draft wanted the **fill** and named the
    ink. That is why this row asks for `kit.p.ground` and refers the pair (Questions 1).

19. **The bar hides whenever a panel is open — and this row builds the only hook it needs, inside a
    region it already owns.** `Popovers.showSheet` puts a full-screen touch catcher over the window
    when `modal` (`Popovers.kt:70-75`) and `Popovers.show` puts one over the whole host
    (`:36-40`), so a peg under either could not be pressed even if it were visible.
    **The rejected alternative, and why:** the only *event* available is the `onClosed` lambda
    `show` and `showSheet` both take and fire in `close()` (`:33`, `:66`, `:96-107`), which would
    mean editing **five** call sites in the Activity — `:520` (brush drawer), `:576` (size and
    opacity sliders), `:628` (⋯ menu), `:661` (tuning sheet), `:750` (reference menu) — i.e. five
    regions in a file R30 item 1 serialises, which is the exact failure that rule exists to prevent.
    **`PegBarView.Host` gains `panelOpen(): Boolean` instead.** `PegBarView.Host` is a type this row
    *creates*, in this row's file; `popovers` is `private` to the Activity, so a view cannot reach it
    either way — but the two `Host` objects already exist as this row's Region 3, and the Activity
    already reads `popovers.isOpen` for the rulers (`:677`). So the whole cost is **one added line
    inside a region this spec already owns**, and the bar re-reads it on every window frame under
    Decision 12's listener, so nothing has to *tell* it anything.
    **One condition, two consumers, one pure function — now actually true:** `PaperLayout.chromeUp`
    takes `(shown, panelOpen, chromeShown)`; `PegBarView` calls it with its own `shown` and
    `host.panelOpen()`, `rulersVisible` calls it in its own body, and test 10 reddens if either
    consumer stops agreeing with it.

20. **The bar and the rulers are chrome and hide on a four-finger tap** with everything else. JB-3.02's
    Question 1 asked this and its own draft ruled "no: the peg bar is chrome, and hidden chrome is
    hidden chrome." That stands. **`PegBarView.chromeVisible`'s setter runs the same 170 ms fade the
    Activity's own loop runs and then reapplies every other reason the bar has for being hidden**, so
    the Activity's edit is three added lines (Region 4) and the bar cannot be revealed by a tap that
    was correctly hiding it — the defect the earlier `if (pegBar.shown) chrome.add(pegBar)` shape
    was working around.

21. **This row adds nothing to the document.** No field, no `DOC_VERSION` bump (R30 item 3 assigns
    versions at landing, and there is nothing here to version), no `EnumFreezeTest` change (`Peg`
    never reaches a file). JB-3.02's test 16 already pins "helpers are never in an export" from core;
    do not delete it, and do not add a layer.

22. **The paper does not exist on this screen today, and that is a fact, not a stub — and it is only
    true because both `shown` setters own `visibility`.** `JoyBrushActivity` holds **no
    `JbDocument` at all**; the only document anywhere is the one `JbCanvasView` builds at snapshot
    time from `DocOps.newDocument(...)` (`JbCanvasView.kt:876`), which is a single **CANVAS** board.
    *(Correction to an earlier draft of this spec: that document's `activeBoardId` **is** set — to
    that CANVAS board's own id, `DocOps.kt:49`. The substantive point is untouched and does not
    depend on it: there is no animation board to point at.)* So `PaperOverlayView.shown` and
    `PegBarView.shown` default `false`, both setters put the views `GONE` on the spot, and **nothing
    on the phone shows this row's work** — including nothing inert: no visible bar, no live TalkBack
    node over a dead listener, no `MATCH_PARENT` overlay quietly eating the canvas's first touch.
    That last clause is the reason the setters exist, and it is why Decision 4's own rule is
    satisfied rather than merely quoted. Questions 2 is the row that changes it, and it is a gap in
    the board, not in this spec.

23. **ONION's `active` is INVERTED, because landed `PaperGeometry.style` is — and this row fixes it
    in its own file rather than referring a control that lies.** Read `PaperGeometry.kt:270-274`:
    ONION is `IDENTITY` when active and `STATE_RING` when not. Fed `active(ONION, _, onionOn)`
    straight, the ONION peg would wear the cyan *selected* ring **while onion skin is off** and be
    indistinguishable from MODE/CADENCE/EXPORT **while it is on** — a backwards control, and one no
    amount of drawing in this row's views can un-ring.
    - **What the peg looks like in each state, as this row ships it:** onion **off** →
      `PaperGeometry.style` gets `active = true` → `IDENTITY` → the neutral `p.raised` disc, the same
      as MODE/CADENCE/EXPORT. Onion **on** → `active = false` → `STATE_RING` → `p.raised` plus
      `ChromeKit.drawSelected`'s 1.5 dp `p.stateSelected` ring (`ChromeKit.kt:62-70`), so the ring
      means "onion skin is on", which is what a ring means everywhere else in this app.
    - **The `!` lives in `PaperLayout.active`, this row's file**, because `style()` is landed and
      **JB-3.02 test 14 pins both of its ONION arms** (`JB-3.02_animation_paper.md:311-314`) — so
      changing it is a JB-3.02 conversation, not a decision this spec gets to make. **Questions 9**
      asks for it to be corrected at source, and test 11 is the alarm that fires if it ever is.
    - **Test 11 also asserts the two states DIFFER**, so "the ONION peg is a plain disc like three
      others" can never pass as a decision again.

---

## Steps

1. **`core/anim/PaperLayout.kt`** — the contract above, in this order: `PaperFrame`, then the dp and
   alpha constants, then `Bar`, `Rulers`, then `pegBarWidthPx`, `pegCentreX`, `pegBarRect`,
   `rulerRects`, `chromeUp`, `rulersVisible`, `active`, `label`, `RulerKey` + `rulerKey`. Every
   guard first (density, non-finite geometry, count coerced the way `pegCentres` coerces it), every
   function pure. **`BAR_PAD_DP` does not exist and must not be created** — the margin that ships is
   `BAR_END_MARGIN_DP` = 22 dp (Decision 3).
2. **`PaperLayoutTest.kt` — tests 1 to 13 below, written before the code.** Tests run in
   `commonTest`: **none of them opens a file**, so none belongs in `jvmTest` (the standing rule,
   JB-1.07's Decision 8).
3. **`PaperOverlayView.kt`** — `init` copies `ValueHud`'s decorative-view shape (`isClickable = false`,
   `importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO`, `ValueHud.kt:33-35`) **and then calls
   `applyVisibility()` once**, so the overlay is `GONE` from the first frame rather than sitting over
   the canvas invisible. Setters (`shown`, `chromeVisible`) write `visibility` and `invalidate()`;
   `topInsetPx` `invalidate()`s — all exactly as `TopButton.on` does (`TopButton.kt:21-22`).
   `onLayout` reads the strip's and the peg bar's bounds, **refreshes `stripOnStart` from
   `strip?.edge`**, and `invalidate()`s if it changed. `onDraw` asks
   `PaperLayout.rulersVisible(shown, source.frame(), …)` first and returns immediately if false; then
   the three strips, then the ticks. The tick list is cached against a `PaperLayout.RulerKey` and
   rebuilt only when the key changes. Register/unregister the `OnPreDrawListener` (Decision 12).
4. **`PegBarView.kt`** — `init`: horizontal, `chromeSurface` with the **bottom two corners square and
   the top two at `BAR_CORNER_RADIUS_DP` = 14 dp** (it is docked to the screen's own edge; this is the
   strip's exact idiom, `ToolStripView.kt:95-97`), `kit.label(child, PaperLayout.label(peg))` per
   child, `setOnClickListener` / `setOnLongClickListener` per child, the child `onTouch` that asks
   `pegAt` first, **and `applyVisibility()` once.**
   **It sets its own size, it does not use `WRAP` and it does not pad itself:** `onMeasure` measures
   to `PaperLayout.pegBarRect(...)`'s width and height, and `onLayout` places each child at
   `pegCentreX(i) − pitch / 2` with width `PEG_PITCH_DP × density`. A `LinearLayout` that measured
   itself would be 248 dp wide — right on a wide screen and wrong on a narrow one, where the rect is
   the band. The end margin is already inside `pegBarRect`'s width; adding `setPadding` as well would
   make the bar 44 dp too wide and clip the outer pegs' touch targets.
   **`onLayout` then does the same computation for its position**: `x`/`y` from the rect (the landed
   `Popovers.place` idiom, `Popovers.kt:144-145`, **not** `layoutParams` — changing layout params from
   inside `onLayout` is a layout loop), `GONE` when the rect is null, and the "only if changed" guard
   that both `Popovers` and `placeStrip` already use (`JoyBrushActivity.kt:480-484`) — **keyed on the
   strip's width and edge as well**, so a dragged strip re-places the bar (Owner's check step 2) with
   no wiring. `applyVisibility()` composes `shown`, `chromeVisible` and `host.panelOpen()`; the
   accent line is a `drawRect` before the pegs.
5. **`JoyBrushActivity.kt` — five regions (five edit sites), and nothing else.** The code is written
   out verbatim in the next section. Do not "while you're in there" anything, **and do not sort the
   import block** (Region 1a says why not).
6. **Run `./gradlew -p joybrush :core:jvmTest`** in a worktree of your own (R43; never on the owner's
   PC), paste the output, and let the watcher prove `:joybrush-android:compileDebugKotlin`.

---

## The `JoyBrushActivity.kt` part — five named regions, five edit sites

**R30 item 1 makes this file one row at a time, and you are the next claimant after JB-2.01.** So this
is the shape `JB-1.21` used: regions named by their anchor, each one to five lines of *code*, nothing
else in the file. The whole Activity part is **about fifty lines in five places.**

**Why five and not three — the number matters, because "three" is what a serialised file is guarded
against.** An earlier draft of this spec said "three regions" in four places and there were five
edit sites: Regions 1 and 2 each have a **second anchor**, and Region 3's anchor is **outside
`buildOverlays()` entirely** (it follows the `stripHost` property, at file scope). FIVE REGIONS, five
edit sites:

| | Anchor | What |
|---|---|---|
| 1a | the import block, two insertion points | four imports |
| 1b | the chrome field block, after `:192` | two fields |
| 2 | before `return overlays` (`:430`) | construct and add the two views |
| 3 | after `stripHost` (`:466`) — **not in `buildOverlays()`** | the two `Host` objects |
| 4 | `toggleChrome()` (`:718-730`) | three added lines |

**Touch nothing else. In particular: not `JbCanvasView.kt`, not `GlPaintEngine.kt`, not
`buildOverlays`' existing children, not the save queue, not the top bar, not the diagnostics door, not
`Popovers.kt` itself, and not `ToolStripView.kt`.**

### Region 1a — two lines into the import block

*Anchors: one line at `:34`/`:35` (immediately before
`import cc.joycreator.joybrush.android.chrome.BrushDrawerView`, `:35`) and one at `:56`/`:57`
(immediately before `import cc.joycreator.joybrush.core.brush.BrushPreset`, `:57`). Four lines, two
places.*

**⛔ THE IMPORT BLOCK IS NOT IN ALPHABETICAL ORDER, AND THIS SPEC IS NOT ASKING YOU TO SORT IT.**
An earlier draft said it was, and it is not: `com.fadcam.*` sits at `:54-56`, *between*
`cc.joycreator.joybrush.androidkit.*` (`:44-53`) and `cc.joycreator.joybrush.core.*` (`:57-70`).
Sorting the block would be roughly forty lines of churn in the file R30 serialises, and churn outside
a named region is the thing R30 item 1 exists to prevent. **Insert; do not reorder.**

```kotlin
// at :34-35, above cc.joycreator.joybrush.android.chrome.BrushDrawerView
import cc.joycreator.joybrush.android.anim.PaperOverlayView
import cc.joycreator.joybrush.android.anim.PegBarView

// at :56-57, above cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.anim.PaperFrame
import cc.joycreator.joybrush.core.anim.Peg
```

`StripPlacement` is already imported at `:62` and `ChromeKit` at `:36`; **no fifth import is needed
and none is wanted.**

### Region 1b — the paper's two fields

*Anchor: the chrome field block, immediately after `private var chromeShown = true` (`:192`). Ten
lines, one place.*

```kotlin
    // JB-3.02b: the animation paper. The rulers measure the board in document pixels and the peg bar
    // is the board's button row. Both are chrome: a four-finger tap takes them with the rest.
    //
    // BOTH `shown` FLAGS ARE FALSE AND STAY FALSE, and each flag's setter OWNS `visibility`, so both
    // views are GONE from the first frame rather than laid out and left sitting there inert. This
    // screen has no animation board: it holds no JbDocument, and the only document anywhere is the
    // single CANVAS board JbCanvasView builds at snapshot time (JbCanvasView.kt:876), whose
    // activeBoardId is that board (DocOps.kt:49). The paper is therefore correctly INVISIBLE today,
    // not broken, and NOT inert: no visible bar, no live TalkBack node over a dead listener, no
    // MATCH_PARENT overlay quietly eating the canvas's first touch. The row that gives the screen an
    // animation board turns the paper on by writing `shown = true` and nothing else, because the
    // setters do the rest. See the spec's Questions 2.
    private lateinit var paper: PaperOverlayView
    private lateinit var pegBar: PegBarView
```

### Region 2 — the end of `buildOverlays()`

*Anchor: immediately before `return overlays` (`:430`), after the hidden diagnostics block. Twelve
lines, one place. **The add order is load-bearing** — the strip is added at `:403`, so anything added
after it sees its final bounds.*

```kotlin
        // ── the animation paper (JB-3.02b) ──
        // Added AFTER the strip (:403) and AFTER the peg bar, so their onLayout runs first and this
        // overlay reads their settled bounds (see the spec's Decision 5). Changing the order makes
        // the rulers' insets one frame stale.
        pegBar = PegBarView(kit, pegHost)
        pegBar.strip = strip
        // WRAP/WRAP because the bar measures ITSELF from PaperLayout.pegBarRect; the gravity is the
        // starting point it moves away from, because it places itself with x/y.
        overlays.addView(pegBar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
        paper = PaperOverlayView(kit, paperSource)
        paper.strip = strip
        paper.pegBar = pegBar
        paper.topInsetPx = kit.dp(TOP_RESERVE_DP)     // the top bar's reserve (:127)
        overlays.addView(paper, FrameLayout.LayoutParams(MATCH, MATCH))
```

**Note what is NOT written here: `stripOnStart`.** Both views hold the `ToolStripView` and re-read
its live `edge` on every layout pass, so the vertical ruler's sidedness follows a dragged strip for
free — one line at construction would have been wrong the first time the strip moved. Likewise
`pegBar`'s re-centring. That is why Region 2 is twelve lines and not twenty.

### Region 3 — the two hosts

*Anchor: immediately after the `stripHost` property (which closes at `:466`), **at file scope, not
inside `buildOverlays()`**. Eighteen lines, one place.*

```kotlin
    /** What the peg bar asks of the screen. The bar knows geometry; only the screen knows the document. */
    private val pegHost = object : PegBarView.Host {
        // Both empty, and that is the honest state: no animation board exists on this screen, so no
        // peg has an owner yet. `Peg.PLAY` is JB-3.05a's and `Peg.EXPORT` JB-3.06b's; the two calls
        // are the contract those rows fill in — one `when` with five arms, no new wiring here.
        override fun pegTapped(peg: Peg) = Unit
        override fun pegLongPressed(peg: Peg) = Unit
        // Decision 19, and the ONLY line this spec adds for it. `popovers` is private to this file,
        // which is exactly why the bar asks its host rather than reaching for it; and because the
        // bar re-reads it every window frame, none of the five `show`/`showSheet` call sites
        // (:520, :576, :628, :661, :750) has to be touched.
        override fun panelOpen() = popovers.isOpen            // Popovers.kt:26
    }

    /** Everything the rulers ask about the page. Four one-line answers, all read-only. */
    private val paperSource = object : PaperOverlayView.Source {
        override fun transform() = canvas.view                    // JbCanvasView.kt:118
        override fun frame(): PaperFrame? = null                  // no animation board exists yet
        override fun strokeInProgress() = canvas.strokeInProgress // JbCanvasView.kt:196
        override fun panelOpen() = popovers.isOpen                // Popovers.kt:26
    }
```

### Region 4 — `toggleChrome()`

*Anchor: `JoyBrushActivity.kt:718-730`. **Three lines added; the loop body is untouched** — it goes
back to the landed `for (v in listOf<View>(topBar, strip, hairline))` with no mutable copy, because
the bar now fades itself.*

```kotlin
    private fun toggleChrome() {
        chromeShown = !chromeShown
        popovers.close()
        for (v in listOf<View>(topBar, strip, hairline)) {
            v.animate().cancel()
            if (chromeShown) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(170L).start()
            } else {
                v.animate().alpha(0f).setDuration(170L).withEndAction { if (!chromeShown) v.visibility = View.GONE }.start()
            }
        }
        // The animation paper goes with the chrome, in its OWN views: each setter runs this same
        // 170 ms fade and then reapplies every other reason the view has for being hidden
        // (Decision 20). `popovers.close()` above has already run, so a panel closing can never
        // leave a bar behind, and a bar this screen has not got is never revealed.
        pegBar.chromeVisible = chromeShown
        paper.chromeVisible = chromeShown
    }
```

**Why this is smaller than the shape it replaces.** The earlier draft had to rebuild the list as a
`mutableListOf` and append `pegBar` behind an `if (pegBar.shown)`, because the list *was* the only
thing that faded the bar — and that guard was exactly the fragile part: it read a flag the view
never wrote and could therefore resurrect a bar the tap had hidden. Moving the fade into
`chromeVisible`'s setter deletes the branch, keeps the landed loop verbatim, and makes the answer to
"is the bar wanted?" live in one predicate (`PaperLayout.chromeUp`).

**Nothing else in the file changes.** In particular there is **no `invalidate()` added to
`canvas.onViewMoved`, `onHistoryChanged` or `onStrokeEnded`** — Decision 12's `OnPreDrawListener`
is why. If you find yourself wanting one, read Decision 12 again before adding it.

---

## Tests

**`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/PaperLayoutTest.kt`** —
`commonTest`, because **no test here opens a file**. Density is 1 unless a test says otherwise, and
**every expected value below carries its arithmetic in the test**, because a corrected number with no
derivation is a number the next reader has to trust.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures, in a worktree of your own (R43).

1. **`pegBarWidthPx` at four densities, and the formula that produces every one of them.**
   `pegBarWidthPx(5, 1f) = 248` (`count × pitch + 2 × radius` = `5 × 44 + 2 × 14`),
   `, 2.625f) = 651` (248 × 2.625), `, 3f) = 744`, `, 4f) = 992`. And the density guard: `0f`, `-1f`
   and `Float.NaN` each give **248**, the density-1 answer. **`pegBarWidthPx(1, 1f) = 72`** in the same
   breath — `1 × 44 + 2 × 14` — because the KDoc's formula and that number are one formula, and a
   builder reading only test 1 would otherwise never see that `count` is in it. Then the constant
   identities: `BAR_HEIGHT_DP == PaperGeometry.PEG_PITCH_DP`, `BAR_END_MARGIN_DP ==
   PaperGeometry.PEG_PITCH_DP / 2f == 22f`, and `RULER_MIN_SPAN_DP` is a named constant — so if
   JB-3.02's numbers ever move, this is red.
2. **`pegBarRect`, density 3, screen 1080 × 2160, the strip on the start edge 120 px wide →
   `(228, 2004, 972, 2136)`.** Derivation: the gap is `8 × 3 = 24`, so the band is
   `[120 + 24, 1080 − 24] = [144, 1056]`, 912 px wide; the pegs need `248 × 3 = 744 ≤ 912`, so the
   width is 744; the band's centre is `(144 + 1056) / 2 = 600`, so `left = 600 − 372 = 228` and
   `right = 972`; the top is `2160 − (8 + 44) × 3 = 2160 − 156 = 2004` and the bottom
   `2160 − 8 × 3 = 2136`.
3. **The same bar on the other edge is its exact mirror.** Strip on the **end** edge, same width:
   `left = 1080 − 972 = 108`, `right = 1080 − 228 = 852`, **and `top == 2004` and `bottom == 2136`
   — asserted, not just asserted-mirror**; a margin that only ever moved the bar sideways would slip
   through a mirroring-only test. Assert the mirroring property (`left + right' == 2 × screenWidth`)
   over a sweep as well, not just these two numbers.
4. **Density 1, screen 600 × 900, no strip → `(176, 848, 424, 892)`.** Band `[8, 592]`, centre 300,
   width `min(248, 584) = 248`, `left = 300 − 124 = 176`, `right = 424`; top `900 − 52 = 848`.
5. **A narrow bar shrinks the peg pitch instead of clipping one — and 248 is not 204.** Density 1,
   screen 340 × 900, the strip on the start edge 120 px wide: the band is `[128, 332]`, so 204 px.
   The pegs need 248, so **the bar's width is 204** — and 204 is the FULL bar width, margin included,
   which is the number `PaperGeometry.pegCentres` is given. Assert against the landed core:
   - at width **204** the block fits exactly (`4 × 44 + 2 × 14 = 204 ≤ 204`, `PaperGeometry.kt:104`),
     so the pitch stays 44 and `pegCentres(5, 204f, 1f) == [14, 58, 102, 146, 190]` — the first peg's
     left edge is at 0 and the last one's right edge at exactly 204: **flush, not clipped**;
   - at width **203** it does not (`203 − 176 = 27 < 28`), so the pitch shrinks to `203 / 5 = 40.6`
     and the centres come back at `[20.3, 60.9, 101.5, 142.1, 182.7]` — assert the first centre minus
     `PEG_RADIUS_DP` is **≥ 0**, which is the property that matters and is the one a clipping layout
     fails.
   - and assert **every** peg of both is inside the rect: `first − 14 ≥ 0` and `last + 14 ≤ width`.
   - **and the half of Decision 3 that justifies 248 over 204**, which is the reason this test is
     about clipping and not only about pitch: at width **204** the first peg's centre is 14, so its
     **44 dp touch target spans `14 − 22 = −8 .. 36`** and **8 dp of it hangs off the end of the
     surface**; the same at the other end. Assert `pegCentreX(0, 5, 204f, 1f) - PEG_PITCH_DP / 2f <
     0` and `pegCentreX(0, 5, pegBarWidthPx(5, 1f), 1f) - PEG_PITCH_DP / 2f >= 0`, and
     `pegBarWidthPx(5, 1f) - 204f == PEG_PITCH_DP`. **Without those three lines a builder can
     "simplify" the width down to the pegs' block and clip every outer touch target, and no other
     test notices.**
6. **The two density guards cannot drift apart, and the width and the pitch agree about where the
   switch-over is.** For each density in `{0f, -1f, 1f, 2f, 2.625f, 3f, 4f, Float.NaN}`, and with
   `d'` the density after the guard both files apply ("not finite or ≤ 0 becomes 1"):
   - `pegBarWidthPx(5, d) == 248 × d'`;
   - **the pitch boundary, which is the load-bearing half and is NOT vacuous:** at width
     `pegBarWidthPx(5, d)` the gaps between `pegCentres(5, …, d)` are **all exactly
     `PEG_PITCH_DP × d'`** (so the block never distorts and the two files cannot be arguing about
     the pitch); at width `pegBarWidthPx(5, d) − 1` **they are not**; and the exact switch-over —
     `(5 − 1) × PEG_PITCH_DP × d' + 2 × PEG_RADIUS_DP × d'` = 204·`d'` — still fits at
     `204·d'` and does not at `204·d' − 1`. **Assert the switch-over as a boundary rather than
     "the four gaps are equal": equal gaps are an arithmetic progression for any input at or above
     the block, so the obvious phrasing of this bullet passes no matter what the width function
     returns, and it did in an earlier draft of this spec.** `pegBarWidthPx` exceeding the
     switch-over by exactly `PEG_PITCH_DP × d'` is Decision 3, checked.
   - `pegCentres(5, pegBarWidthPx(5, d), d).first() − PaperGeometry.PEG_RADIUS_DP × d' >=
     BAR_END_MARGIN_DP × d'` — the first peg's disc is not clipped, whatever the guard did, and the
     margin outboard of it is the 22 and not the 14.
   This is the cross-file test that catches a guard written differently in the two files. It is also
   why `pegBarWidthPx` never calls `PaperGeometry`: there is nothing there to call.
7. **`pegCentreX` is the centre of its own cell, follows `pegCentres` when the pitch shrinks, and
   never throws.** At every density in the sweep, with the bar at its natural width
   `pegBarWidthPx(5, d)`:
   `pegCentreX(i, 5, pegBarWidthPx(5, d), d) == PaperGeometry.pegCentres(5, pegBarWidthPx(5, d), d)[i]`
   for `i` in 0..4 — i.e. **`[36, 80, 124, 168, 212]` px at density 1**, whose first entry is
   `248 / 2 − 4 × 44 / 2 = 36`, each peg's centre on the middle of its own 44 dp cell to the bit.
   **The end margin is not added twice:** the value is
   `pegCentres(5, 248f, 1f)[0] == 36`, **not** `BAR_END_MARGIN_DP + 36 = 58`. And the margin that
   ships is asserted, because the earlier draft's `BAR_PAD_DP = 14f` was not it:
   `pegCentreX(0, 5, 248f, 1f) − PaperGeometry.PEG_RADIUS_DP == BAR_END_MARGIN_DP`, i.e. `36 − 14 ==
   22`.
   Then the trap half: at a bar of **203 px** (test 5's shrunk case) `pegCentreX(i, 5, 203f, 1f)`
   equals `pegCentres(5, 203f, 1f)[i]` — `[20.3, 60.9, 101.5, 142.1, 182.7]` — and is **not** on a
   44 dp pitch, so a builder who laid the children out on a pitch fails this and the bar's children
   disagree with `pegAt` on the phone.
   Then the edges: `pegCentreX(7, 5, …) == pegCentreX(4, …)` and `pegCentreX(-1, 5, …) ==
   pegCentreX(0, …)`, and neither throws.
8. **`pegBarRect` returns null rather than a bar that cannot be pressed — on a band that makes the
   one-peg threshold load-bearing.** (An earlier fixture used a 44 px band, where **both** the
   one-peg 72 and the five-peg 248 exceed it, so a null check written against either width passed:
   the mutation it exists to catch was invisible in the very test named for it. The band here is
   chosen so `72 ≤ band < 248`.)
   - **Density 1, screen 600 × 900, the strip on the start edge 384 px wide**: the band is
     `[384 + 8, 600 − 8] = [392, 592]` = **200 px**. One peg needs 72 and fits; five need 248 and do
     not. So the answer is **a bar 200 px wide**, and the fixture proves the threshold is doing work:
     `rect != null`, `rect.widthPx == 200f`, and the pitch has shrunk —
     `pegCentres(5, 200f, 1f) == [20, 60, 100, 140, 180]`, first centre `20 − 14 = 6 ≥ 0`, last
     `180 + 14 = 194 ≤ 200`.
   - **The same screen, strip 513 px**: band `[521, 592]` = **71 px**, and one peg needs
     `pegBarWidthPx(1, 1f) = 1 × 44 + 2 × 14 = 72 > 71`, so **null**.
   - Also null for `screenWidthPx` of `0f`, `NaN` and `POSITIVE_INFINITY`, for a negative width, and
     for a `density` of `0f` (which becomes 1). And: `pegBarRect(600, 900, 0f, true, 1f)` with a
     zero-width strip is the same answer as with the strip on the other edge — a hidden strip reserves
     nothing.
9. **`rulerRects`, density 3, screen 1080 × 2160, `topInsetPx = 162` (54 dp × 3), the strip on the
   start edge 120 px (`startInsetPx = 120`, `stripOnStart = true`), `barTopPx = 2004`.**
   Thickness `24 × 3 = 72`. Assert
   - `corner == (120, 162, 192, 234)`;
   - `top == (120, 162, 1080, 234)` — 960 × 72, and **its 72 dp thickness is deliberately below
     `RULER_MIN_SPAN_DP`**, because the floor is on the measured axis and not on the thickness
     (Decision 8); its measured span, 960, is what clears it;
   - `start == (120, 234, 192, 2004)` — 72 × 1770, and **its bottom is the peg bar's top**, so the
     two can never overlap;
   - and that the top ruler starts **below** `topInsetPx`, i.e. the top bar's icons are never behind
     a ruler.
   - **The strip's side, load-bearingly: the same call with `startInsetPx = 120f` and
     `stripOnStart = false` puts the vertical strip at `x = 0 .. 72`.** An earlier draft passed
     `startInsetPx = 0f` here, which would come back the same whether or not `stripOnStart` was read
     at all — a parameter nothing could catch. **A non-zero width with `stripOnStart = false` is
     the only form of this case that means anything.**
   - **The `RULER_MIN_SPAN_DP` floor, on its own axis and at its own boundary.** Density 1, screen
     600 × 900, `topInsetPx = 8f`, no strip, so the top strip is `(0, 8, 600, 32)` and the strips'
     inner edge is `y = 32`. Then `barTopPx = 32 + 64f = 96f`: the vertical strip's **measured span is
     exactly 64**, it is **drawn**, and `start == (0, 32, 24, 96)`. Then `barTopPx = 32 + 40f = 72f`:
     the span is 40, under the floor, and the answer is **null**. (An earlier draft probed the floor
     only at `barTopPx = 234f` → a 0 px strip, which every implementation refuses for the trivial
     reason and which a 100 px-tall ruler — a real sliver at 64 — would sail straight through.)
   - Also null for a non-finite screen.
10. **`rulersVisible` — all six conditions, and none of them optional.** A `null` frame → false; a
     frame with `widthDoc = 0` → false; a frame with `originXDoc = Double.NaN` → false (`measurable`);
     rotation `0f` → true; **`rotationRad = PAGE_STRAIGHT_RAD` → true and
     `rotationRad = −PAGE_STRAIGHT_RAD` → true** (the boundary is inclusive, and the sign is
     symmetric because a page turned 7° anticlockwise is as straight as one turned 7° clockwise);
     `rotationRad = PAGE_STRAIGHT_RAD × 1.0001f` → false, **`rotationRad = 0.001f` → false** (that is
     0.06°, and an earlier draft expected it to be a *turned* page only because it had no threshold
     pinned at all, so any epsilon in `(0, 0.001]` passed and the builder chose the number);
     `rotationRad = Float.NaN` → false. Then with everything else true: `shown = false` → false,
     `strokeInProgress = true` → false, `panelOpen = true` → false, `chromeShown = false` → false.
     **And the property:** over all **64** combinations of those six booleans (and a fixed good
     frame), the answer is true **iff** all six hold — enumerate rather than assert a table, so a
     dropped condition is red.
     **And the shared predicate:** `rulersVisible` agrees with `PaperLayout.chromeUp(shown, panelOpen,
     chromeShown)` on every one of those 64 rows, so the bar and the rulers cannot drift apart
     (Decision 19's "one condition, two consumers, one pure function", now actually asserted).
11. **`active` maps the bar's two states onto the ONE `active` `PaperGeometry.style` wants — and
    ONION is inverted on purpose.** All `Peg.entries` × 4 combinations:
    `active(PLAY, playing, _) == playing`, `active(ONION, _, onionOn) == !onionOn`, and
    `active(MODE | CADENCE | EXPORT, _, _) == false` always. Then **the ONION peg's two states,
    spelled out, because "inverted" is not a shape a test can check**:
    `PaperGeometry.style(ONION, active(ONION, false, false)) == PegStyle.IDENTITY` (onion **off**,
    a plain neutral disc) and `PaperGeometry.style(ONION, active(ONION, false, true)) ==
    PegStyle.STATE_RING` (onion **on**, the cyan selection ring — which is what Decision 23 exists to
    achieve, because landed `PaperGeometry.kt:272` is the other way round). **And that the two
    differ:** `style(ONION, active(ONION, _, true)) != style(ONION, active(ONION, _, false))`, so
    "ONION looks like MODE, CADENCE and EXPORT in both states" can never pass as a decision again.
    Then **the pair of them together**: for every peg and every one of the four combinations,
    `PaperGeometry.style(peg, active(peg, playing, onionOn))` yields at most one `PegStyle.ACTION`,
    and it is `style(PLAY, true)` — JB-3.02's test 14 restated through this row's table, so a sixth
    peg or a second state is red here too. `Peg.entries` is enumerated, so a rename or a reorder
    fails.
12. **`label` — five distinct, none blank, none over 40 characters**, and `label(Peg.PLAY) == "Play"`
    exactly (Decision 15: no invented clauses). Assert against the whole `Peg.entries` list, so a
    sixth peg without a label is red.
13. **Every named ink, and both thresholds derived from another constant.** The nine numbers an
    earlier draft carried in a Markdown table, where the arithmetic was right and untestable:
    - `RULER_VEIL_ALPHA == round(0.20f × 255) == 51`, `RULER_TICK_ALPHA == round(0.35f × 255) == 89`,
      `RULER_ZERO_ALPHA == round(0.85f × 255) == 217` — **each pinned to the percentage Decision 10
      states**, so the two cannot drift;
    - `RULER_TICK_DP == 1f` and `RULER_ZERO_TICK_DP == 1.5f` and `RULER_TICK_DP < RULER_ZERO_TICK_DP`
      — the zero tick is heavier, which is the whole of Decision 10's first clause;
    - `RULER_LABEL_DP × 2f <= PaperGeometry.RULER_THICKNESS_DP` (two lines of a number fit a 24 dp
      strip: 20 ≤ 24);
    - `RULER_MIN_SPAN_DP >= PaperGeometry.MIN_LABEL_DP` **and**
      `RULER_MIN_SPAN_DP < 2f × PaperGeometry.MIN_LABEL_DP` — Decision 8's real argument, written so
      a retune of either constant is red;
    - `BAR_CORNER_RADIUS_DP == 14f`, `ACCENT_LINE_DP == 2f`, `ACCENT_LINE_DP < BAR_HEIGHT_DP`;
    - `PLAY_TRIANGLE_DP == 10f`, `PLAY_BARS_DP == 2.5f`, `PLAY_BARS_DP × 2f < PLAY_TRIANGLE_DP`;
    - **`PAGE_STRAIGHT_RAD == ViewTransform.SNAP_TOLERANCE`** — the two copies of 7°, held together,
      which is the same discipline as test 6 holds over the two density guards. This test may import
      `cc.joycreator.joybrush.core.view.ViewTransform`; `PaperLayout.kt` may not, which is why the
      constant is written rather than referenced.
    **The two the suite can only pin the literal of are `BAR_CORNER_RADIUS_DP` (14 dp because the
    landed strip's is, `ToolStripView.kt:95`) and `ACCENT_LINE_DP` (2 dp because the screen's own
    hairline is, `JoyBrushActivity.kt:272`).** Neither has a landed *constant* to be compared
    against — the strip re-types `14f` and the Activity re-types `2f` — so the "matches" half of
    both is an eyeball item on the Owner's check and this table does not pretend otherwise.

### Non-vacuity — twelve mutations, and which tests must go red

A green suite that cannot fail is worse than none (this project's standing note on the benchmark
harness). Check these before you report, and paste the result. (An earlier draft said "six" over a
table of seven rows; the count and the table now agree.)

| Mutation | Must redden |
|---|---|
| `BAR_MARGIN_DP = 8f` → `0f` | 2, 3, 4 |
| `pegBarRect` drops `stripWidthPx` from the band's start | 2, 3, 8 |
| the null check uses `pegBarWidthPx(5, …)` instead of `pegBarWidthPx(1, …)` | 8 (**the 200 px band**, where 72 fits and 248 does not — a 44 px band would pass either way, which is why that fixture changed) |
| `rulersVisible` forgets `rotationRad` | 10 |
| `rulersVisible` forgets `chromeShown` | 10 |
| `pegCentreX` returns `BAR_END_MARGIN + PEG_PITCH × i` (cell left, not centre) | 7 |
| `rulerRects` uses the screen's height where it should use `barTopPx` | 9 |
| `pegBarWidthPx` drops the last peg — `(count − 1) × pitch + 2 × radius` instead of `count ×` | 1, 5, 6 |
| `active` stops inverting ONION | 11 |
| `rulerRects` ignores `stripOnStart` and always insets by `startInsetPx` | 9 |
| `PAGE_STRAIGHT_RAD` 7° → 2° | 10, 13 |
| `RULER_MIN_SPAN_DP` compared against the strip's **thickness** instead of its measured axis | 9, 13 |

### What is JVM-testable, and what honestly is not

**JVM-tested here (`:core:jvmTest`, 13 tests, no phone):** the bar's width and the formula behind it,
its rect at four densities, its mirroring, its null cases and the band that makes them load-bearing,
the pitch shrink and the clipping 248 exists to avoid, the two density guards agreeing across files
**and the pitch switch-over agreeing with both**, the peg centres and the 22 dp end margin, the
rulers' three rects, the strip's side, the `RULER_MIN_SPAN_DP` floor at its own boundary, all six
visibility conditions and the predicate the two chrome consumers share, the peg→state→style table
including the ONION inversion, the five labels, and every named ink against the percentage it
claims. **That is every decision this row makes.** Nothing in the "Decisions" section above is
decided inside `onDraw`.

**Device-only, with the reason:**

| What | Why no test can cover it |
|---|---|
| `FrameLayout`'s child order ⇒ the overlay sees the strip's settled bounds (Decision 5) | it is a statement about Android's layout pass |
| `OnPreDrawListener` firing once per window frame and never when idle (Decision 12) | needs a real window |
| a touch outside the peg bar falling **through** to the canvas and drawing a mark | a `MotionEvent` cannot be built off-device — the same reason JB-2.02's `handOver()` has no JVM test |
| `onPreDraw` returning `true` and the listener being removed on detach | as above |
| a TalkBack node per peg, and the tooltip on hover | accessibility services |
| the tick number at 10 dp being legible at density 3 | a font rasteriser |

### Owner's check (T3) — read this before you claim anything

**This row cannot reach 📱 today, and saying otherwise would be false.** There is no animation board
on this screen to look at (Decision 22, Questions 2), so **the honest state of this row after a green
build is 🟧 Built with no phone check run, and every one of the eight steps below is owed to the row
that supplies the board.** Do not invent a fake board to photograph.

**What makes that claim honest rather than a formality — the thing a builder must not break:**
both `shown` setters write `visibility`, so on today's screen the peg bar is **`GONE` from the first
frame** and the overlay is **`GONE` from the first frame**. There is no visible bar carrying five
live TalkBack nodes over `= Unit` listeners, and no `MATCH_PARENT` overlay quietly eating the
canvas's first `ACTION_DOWN`. That is the difference between *the paper is correctly not here yet*
and *this row shipped something broken and the review will not hear about it until a board exists*.
Steps 1, 3, 4 and 5 are the ones a broken `shown` would have failed, and none of them can be run
today — which is exactly why the setters are the contract rather than a nicety.

**What the owner checks once an animation board exists, in this order:**

1. Open an animation board. **A peg bar sits at the bottom, centred, five pegs, on a neutral panel with
   a 2 dp aqua→lime line along its top edge**, the bottom two corners square and the top two at
   14 dp. It does not reach the tool strip on either side. **The four neutral pegs (MODE, ONION,
   CADENCE, EXPORT) are plain discs; PLAY wears the gradient only while it is playing, and the ONION
   peg grows a cyan ring while onion skin is ON** — the ONION ring is the one place this row
   inverts a landed rule (Decision 23), and this step is where the owner sees whether that reads
   right.
2. **Drag the tool strip to the other edge.** The bar re-centres on the new free band, and **the
   vertical ruler moves inboard of the strip** — and the vertical ruler does **not** change sides.
   Both of these happen with no wiring at all, because both views re-read `strip.edge` every layout
   pass.
3. **Tap a peg.** It presses, the hover label/TalkBack name is the peg's name, and nothing else
   happens yet (no peg has an owner). Tap between two pegs: the peg bar never swallows a tap that
   should have drawn — **drag on the picture under the bar's band and a stroke appears.**
4. **Four-finger tap.** The bar and the rulers go with the top bar and the strip. Tap again: they come
   back, and **a bar that was not wanted does not appear.**
5. **Start a stroke.** The rulers go; they come back when the pen lifts. **Open the brush drawer**:
   the bar and the rulers go; they come back when it closes. *(Step 5 is the one the cross-review
   caught this spec not being able to deliver — Decision 19 now has a mechanism in this row's own
   regions rather than a wish about five `Popovers` call sites.)*
6. **Two-finger pinch** through eight zoom steps. The tick step re-ladders (`50 → 100 → 200 → 500 →
   1000 …`), no two numbers ever collide, and the **0 tick is visibly heavier**. Nothing stutters
   while panning, and **nothing redraws while the screen sits still** (Decision 12 — this is the one
   worth watching, with the screen on and nothing happening).
7. **Rotate the page** even slightly. The rulers go at 7° — the same window the page snaps in, so a
   page that has snapped square has its rulers and a page 2° off does not. Straighten it: they come
   back.
8. Turn the paper and the strip over the same places. **Readability is the owner's call**; the three
   numbers they may overrule are the tick label size (10 dp), the ruler's 20 % veil, and the bar's
   14 dp top corners.

---

## Do not

- **Do not touch `JbCanvasView.kt` or `GlPaintEngine.kt`.** R30 item 2 makes them the Lead's in a
  different order. **JB-1.05b's two-line dedup is not yours either.**
- **Do not touch `Popovers.kt` or `ToolStripView.kt`.** Decision 19 needed a panel-open signal and
  `PanelOpen`/`panelOpen()` on this row's own `Host` type gets it without touching either; `strip.edge`
  is already public (`ToolStripView.kt:62`) and reading it is not an edit.
- **Do not touch any part of `JoyBrushActivity.kt` outside the five named regions**, and **do not
  reorder its import block** — it is not alphabetical today (`com.fadcam.*` at `:54-56`, between two
  `cc.joycreator.*` blocks) and sorting it is ~40 lines of churn in a serialised file. Not the top
  bar, not the strip, not the save queue, not `moreMenu`, not the diagnostics door, not the pinned
  reference. If something there genuinely must change, it goes in **Questions**, not in the diff.
- **Do not add a `View` to `core`, a `Context` to `commonMain`, or a hex literal anywhere.** A
  `PegStyle` maps to a **token**, and `tools/check_joybrush_tokens.py` is the thing that keeps the
  mirrors honest. If you feel the need to widen `JbColors.Palette`, that is the signal to write
  Questions 1 instead.
- **Do not write a `const val` in pixels.** Every LENGTH constant is dp and is multiplied by density
  **at the use site** (R32). This is the exact slip the JB-3.02 draft made and its review caught.
  (The three ruler ALPHAs are 0-255 and are not lengths — R32 does not apply to them.)
- **Do not re-type `44f`, `14f`, `22f`, `24f` or `48f`.** They are public in `PaperGeometry` so that
  this row can read them.
- **Do not invent `BAR_PAD_DP`, or name the bar's end margin 14.** The margin that ships is 22 dp
  (`BAR_END_MARGIN_DP`); 14 is the drawn radius and appears nowhere in the bar's width (Decision 3).
- **Do not leave `shown` as a plain field.** Its setter must write `visibility`, on both views, and
  `init` must call `applyVisibility()`. A `shown` that only stops drawing leaves a live TalkBack node
  over a dead listener, which is the defect Decision 4's own rule forbids (Decision 22).
- **Do not infer the vertical ruler's side from a coordinate.** `rulerRects` takes `stripOnStart`;
  `PaperOverlayView` reads `strip.edge` (Decision 7).
- **Do not add a gesture to a ruler**, a drag-to-move-origin, a long-press, or a second ruler (the
  JB-3.02 Q2 half that is still open). `PaperOverlayView` takes no touches at all.
- **Do not draw a film strip, a sprocket, a frame number or a thumbnail.** That is JB-3.03 / 3.03b,
  and its row on the board is ⚪ Outline with no spec. **JB-3.03b has no spec file at all** — its board
  link is dead.
- **Do not add a sixth peg**, and do not reorder the five. `Peg` is APPEND-ONLY and a growth needs a
  layout review (JB-3.02's Decision 2); test 11 enumerates the enum so both are red.
- **Do not build a hover menu, a transport control, a stop button or prev/next.** R33: the peg bar
  owns PLAY and MODE; the strip has prev/next. **One saturated control on the board** — test 11.
- **Do not invent an icon.** `JbIcon` has nine and none is one of these five (Questions 8).
- **Do not invent user-visible copy.** No `"— tap to …"` labels, no refusals, no toasts. The five
  labels are the five names.
- **Do not add a field to the document, a layer, or an export.** No `DOC_VERSION` bump (R30 item 3),
  and do not touch `DocModel.kt`, `DocJson.kt` or `JbArchive.kt`.
- **Do not run gradle on the app build.** `:core:jvmTest` is the sanctioned command; `joybrush-android`
  is the watcher's. Never on the owner's PC.
- **Do not run `git`.**

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, **0 failures**
- [ ] the **twelve** mutations run, each with the tests it reddened pasted in the report
- [ ] `git status --short` shows **exactly five paths**: the two core files (`A`), the two
      `joybrush-android/.../anim/` files (`A`), and `JoyBrushActivity.kt` (`M`)
- [ ] the Activity diff is **five regions / five edit sites** and `git diff` for it is pasted whole,
      so it can be read in one sitting — **and the import block is inserted into, not sorted**
- [ ] `PaperLayout.kt` imports nothing outside `cc.joycreator.joybrush.core.anim` and `kotlin.math`
      (this is why `PAGE_STRAIGHT_RAD` is written rather than taken from `ViewTransform`, and test 13
      is what holds the two copies together)
- [ ] the watcher is green with `:joybrush-android:compileDebugKotlin` **EXECUTED** (build.log pasted)
- [ ] committed `JB-3.02b: animation paper view (overlay, peg bar, activity wiring)`, pushed
- [ ] `INDEX.md` → **Built — awaiting T1 review**; the ROADMAP row is the orchestrator's, not yours
- [ ] the report says plainly that **no phone check was run**, why, and **which of the Owner's-check
      steps are therefore all still owed**

## Stop rule

**Stop and write in Questions; do not guess, if any of these is true when you start:**

- `PaperLayout.kt` needs an import outside `cc.joycreator.joybrush.core.anim` — it means a decision
  has crept into core, and JB-3.02's stop rule says the same thing about a `View`.
- `pegBarRect` cannot return `null` on a screen you were given (a phone in landscape, a foldable).
  **Write down the screen size where it breaks** and stop; JB-3.02's stop rule asks for the same thing
  about a narrow bar.
- `pegCentreX` and `PaperGeometry.pegCentres` disagree for any count other than five. The bar is
  five pegs, and a disagreement means one of the two files has changed its mind about the margin.
- The bar lands **under** the strip or the top bar on any size you can construct. Placement is this
  row's whole first decision; a bar under another control is a control that cannot be pressed.
- You want to make `shown = true` work on the phone today. **You cannot**, and the honest answer is a
  Question, not a stub. What you *can* do is get the setters right, so that the day a board exists the
  bar appears and does not appear as a broken control first.
- A peg's **44 dp touch target** cannot be laid out wholly on the bar's surface at the bar's natural
  width. It can (22 dp of margin each end); if your implementation says otherwise, Decision 3's
  arithmetic is wrong and **stop** — the clipping it reintroduces is invisible in every other test.
- `PAGE_STRAIGHT_RAD` and `ViewTransform.SNAP_TOLERANCE` ever disagree. One is 7°; if yours is not,
  something has been rounded twice and the page will stop squaring where the rulers do.
- A ruler has to appear on a **turned** page, or the rulers have to move sides when the strip moves.
  Both were decided above (Decisions 7, 11) and both would change a spec, not a view.
- `JoyBrushActivity.kt` seems to need a sixth region. It does not — and if you are certain it does,
  that is exactly what a Question is for.

---

## Questions

_(Spec writer: **`openrouter/stealth/space-bunny-alpha`**, 2026-09-30. Written from the landed
JB-3.02 core, the landed JB-2.01 chrome, `JbColors`/`jb_tokens.xml`/`studio_tokens.xml`/`Studio.java`,
`check_joybrush_tokens.py`, the blueprint and the visual language. **Every token name, function
signature and line number in this spec was read out of the file it is attributed to**; nothing is
recalled from an earlier draft. I ran no gradle and no git.)_

### ⛔ Blocked for Claude

1. **The `ACTION` pair — the one thing this row cannot supply.** §1.4 says the saturated control is
   `@drawable/studio_action_pill` = `#35F6BF → #97FE8B` = `s_go` / `s_go_end`
   (`studio_tokens.xml:81-82`, `Studio.java:141-142`), and `joybrush-android` cannot see an app
   drawable (`jb_tokens.xml:5-24`). So the pair must be **added**, in **three files that are not
   mine**: two `<color>` lines in `joybrush-android/src/main/res/values/jb_tokens.xml` carrying
   `<!-- mirror: s_go -->` and `<!-- mirror: s_go_end -->`; two rows in `MIRRORS` in
   `tools/check_joybrush_tokens.py:50-75` (`EXPECTED` at `:77` is derived from it, and an unlisted
   token is a hard failure at `:170-175`); and two fields on `Palette` in `JbColors.kt:116-155` plus
   their `load(...)` reads at `:74-108`. Say the word and it is fifteen minutes.
   - **The finding behind it:** `jb_board_animation_start/end` are already `#FF35F6BF`/`#FF97FE8B`
     (`jb_tokens.xml:58-59`) — **byte-equal to the action pair**. `Studio.java:156-162` says this
     equality is *deliberate* ("GO is an ACTION, the Studio room is a PLACE"), which is fine while
     they are different controls and not fine the moment four pegs wear it: §1.4 says **one saturated
     control per screen**. **I have ruled provisionally that `PegStyle.IDENTITY` is the neutral
     `p.raised` and the board's identity reaches the bar as a 2 dp accent line instead** (Decision
     6). If you would rather the pegs wear the board gradient, then it is the ACTION pair that has
     to be a *different* pair, and that is a colour decision, not a plumbing one.
   - **And the ink on it:** the app's own is `Studio.ON_GO = 0xFF050507` (`Studio.java:143`). I use
     `p.ground` (`#000000`), which is invisible from it on a 14 dp disc, and `TopButton.kt:39` sets
     exactly that for its dark icons. **Or should a third token, `jb_action_ink`, mirror `s_on_go`?**
2. **Which row gives the phone an animation board? ⛔ This is the gap that stops **nine** rows being
   checked, and one of the nine is worse than unprovable — it is dead code today.**
   `JoyBrushActivity` holds **no `JbDocument`**; the only document anywhere is the one `JbCanvasView`
   builds at snapshot time from `DocOps.newDocument(...)` (`JbCanvasView.kt:876`), a single **CANVAS**
   board whose `activeBoardId` is its own id (`DocOps.kt:49`). Nothing in the tree has ever made an
   ANIMATION board on a phone — `BoardKind.ANIMATION` appears only in `core/`, in tests, and at
   `JbColors.kt:66`. I could not find a row on the board that owns creating one: JB-3.01 is the
   *model*, JB-3.05 is the stepper, JB-3.08 is the context-aware swipe and is ⚪ Outline.
   **The rows this blocks, complete:**
   | Row | What is blocked |
   |---|---|
   | **JB-3.02b** (this row) | the whole phone check; all eight Owner's-check steps |
   | **JB-3.03b** film-strip thumbnails | has **no spec file at all** — its board link is dead |
   | **JB-3.04a** onion-skin core maths | waits on R34's `OnionMath` extraction first (D.02) |
   | **JB-3.04b** onion-skin view | its ghosts have nothing to ghost |
   | **JB-3.05** playback | core half ✅ Built; the transport is R33'd to the peg bar this row builds |
   | **JB-3.05a** playback clock | ✅ Built, but nothing on a phone can show a frame changing |
   | **JB-4.01** sprite board | **view half** — core ✅ Built, and the view half waits behind JB-2.01 |
   | **JB-4.02** cell order & play | **view half** — same |
   | **JB-3.08** context-aware 3-finger swipe | **DEAD CODE, not merely unprovable** — see below |
   **JB-3.08 deserves its own line because it is a stronger claim than "unprovable".** Its entire
   predicate is `frameCountOf(doc)`: `doc.activeBoardId ?: return 0`, then
   `if (board.kind != BoardKind.ANIMATION) return 0` (`ThreeFingerSwipe.kt:122-127`). Since the only
   board ever made is the CANVAS one, `frameCountOf` is **always 0**, `automatic(...)` always answers
   `SwipeMode.BRUSH`, and the context-aware swipe **always** takes the brush-size branch. Its
   `activeBoardId` read **has no caller anywhere** — a tree-wide grep finds `ThreeFingerSwipe` only in
   its own file and its own test. So a ✅ Built row reads a document field, takes a branch that can
   never be taken, and no phone would ever notice.
   Until a row owns "make an animation board", that is **nine rows** on the board worth one row,
   rather than nine dead ends. I am not opening it: it is not my row, and a board is a document
   decision.
3. **`Peg.MODE` — what is it?** `Peg` is frozen (`PaperGeometry.kt:23`, test 11 enumerates it), and
   nothing in the blueprint, the core or the board says what the animation board's "mode" switches
   between. `Peg.PLAY` is JB-3.05a's, `Peg.ONION` is JB-3.04's, `Peg.CADENCE` is the clock's, and
   `Peg.EXPORT` is JB-3.06b's — **`MODE` has no owner and no meaning I could find.** I have labelled
   it `"Mode"` and left it there. If it is a duplicate of something, delete it *before* it is built;
   after the bar ships it is a public name.

### 🔴 For the Lead

4. **The bar's numbers are mine and they are all `PROVISIONAL — Claude to confirm`, and three of them
   the owner will move on the phone:** the **248 dp width — which is the pegs' 204 dp block plus one
   more pitch, not "the pegs' width"** (Decision 3; the 22 dp of surface at each end is what keeps
   every outer 44 dp touch target on the control, and that is a judgement, not a constant someone
   else picked) — and with it that 248 is **45 % of the Note 9's 548 dp**; the 8 dp gap under it; and
   the 44 dp height inherited from `PEG_PITCH_DP` (that one is *not* free — it is the touch pitch,
   and changing it changes JB-3.02's test 1). The **14 dp top corner radii** are the house value the
   landed strip uses on the same two corners (`ToolStripView.kt:95`), and are the fourth number the
   owner may overrule. A seventh peg would need 44 more dp of width; a narrower screen shrinks the
   pitch rather than clipping (test 5), which is the behaviour to look at first on a Galaxy Tab S8 in
   portrait.
5. **JB-3.02's Q2, first half: no gesture on a ruler, ever.** I ruled it (Decision 9) because this
   project has already ruled that fingers navigate and never draw, and a draggable ruler is a second
   invisible canvas gesture. The owner should confirm, because Photoshop's is the behaviour people
   have a muscle memory for. Second half answered: **the zero tick is heavier, and there is no centre
   line** — a board that is not an odd number of cells has no middle.
6. **The tick number at 10 dp and the ruler's 20 % black veil.** §1.7 puts numbers in Plex Mono and
   `joybrush-android` cannot reach the app's font, so I used the landed precedent
   (`ValueHud.kt:24-30`: `Typeface.MONOSPACE`, `kit.dp(13f)`) and dropped it to 10 dp for a 24 dp
   strip. The 20 % veil is `kit.p.ground` at alpha 51, the same construction as `ValueHud`'s 153 and
   128. Both are eyeball numbers; the owner's check reads them over white paper, marker paint and
   black.
7. **`PaperFrame` is a new name in `core.anim`**, one hop from `doc.Paper` (`DocModel.kt:38`), which is
   the document's paper *setting*. JB-3.02 renamed its own `Paper` to `PaperGeometry` for exactly
   this reason (its Question 2, still open). The animation board's host will already be touching
   `doc.paper.color`. **I would rather you renamed `doc.Paper` once than have `Paper` and `PaperFrame`
   in the same import block forever.**
8. **What do the other four pegs look like? Decision 17 defers them and there was no Question for
   it**, which is how a deferral turns into an unowned gap. PLAY gets a triangle and two bars because
   a saturated play control with no play mark reads as decoration; MODE, ONION, CADENCE and EXPORT get
   **nothing but a disc**, because `JbIcon` has nine icons and **none of these five is one of them**
   (`JbIcon.kt:17-26`) and its KDoc says the icons were drawn by the Lead for this app, so five path
   strings are out of area as well as off-house. Five bare discs is honest and is what ships; whether
   that is what the **owner** wants is a different question and it is open. Options I can see, none of
   which I am choosing: letters (`M` `O` `C` `E`) in the drawer ink; the strip's own existing marks
   (`Kind.SIZE`'s circle-with-dot, `Kind.OPACITY`'s gauge arc, `ToolStripView.kt:156-183`); or `S`
   (stacked) and `⇥` glyphs drawn as `Path` triples the way PLAY's are.

### Low-risk, ruled provisionally — reverse any of these in a line

9. **ONION's `active` is passed INVERTED, because `PaperGeometry.style` has ONION backwards** — for
   JB-3.02's owner, not for me. Landed `PaperGeometry.kt:270-274` gives ONION `IDENTITY` when active
   and `STATE_RING` when not, and JB-3.02's own test 14 **pins both arms**
   (`JB-3.02_animation_paper.md:311-314`), so I cannot fix it and this spec works around it by
   passing `!onionOn` in `PaperLayout.active` (Decision 23). **The workaround ships a correct
   control; the root cause stays in a landed row.** Test 11 asserts both the corrected behaviour and
   that the two states differ, so a fix at source makes this spec go red rather than silently
   double-inverting. Correct `style()` and this row loses one `!`.
10. **The bar docks to the BOTTOM**, not the top and not a side. The top belongs to the top bar and a
    side belongs to a control that is *draggable to either side*. This supersedes the draft's "56 dp
    row docked to the bottom, above whatever JB-2.01 puts there" — JB-2.01 built no bottom cluster, so
    the bottom is free, and 56 dp is not a number this bar is made of.
11. **The bar is 248 dp — the pegs' block plus one pitch — centred on the free band** (Decision 3).
12. **The vertical ruler is on the start edge always** and moves inboard of the strip; it does not
      change sides when the strip moves, and its side is a parameter, never an inference (Decision 7).
13. **The four `IDENTITY` pegs are neutral, and the board's identity is a 2 dp accent line along the
      bar's top edge** (Decision 6). Reverse this one and the §1.4 "one saturated control per screen"
      argument has to be answered again.
14. **The overlay re-renders on the window's draw pass** (`OnPreDrawListener`), not on a clock and not
     from four callbacks in the Activity (Decision 12). The cost is one frame of lag.
15. **The five labels are the bare peg names**, with no clause, because the clauses belong to rows
      that do not exist (Decision 15).
