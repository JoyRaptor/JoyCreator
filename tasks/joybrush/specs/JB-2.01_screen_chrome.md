# JB-2.01 — Screen chrome: the control cluster, the thumb rail, and one panel that is a drawer on a phone and a popover on a tablet

| | |
|---|---|
| **Tier** | T2-V (vision + design) |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-0.09 (lobby entry + first chrome), D.02 (`:studiokit`) — **both ⚪ Outline; D.02 is Lead-owned, "do not dispatch"** |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/ChromeRules.kt` + `.../commonTest/.../chrome/ChromeRulesTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/chrome/ControlClusterView.kt`, `.../chrome/ThumbRailView.kt`, `.../chrome/ChromePanel.kt`; EDIT `joybrush-android/.../JoyBrushActivity.kt` (re-host the existing pills, add the rail and the panel) |
| **Estimated size** | ~120 lines of rules + tests; ~450 lines of views; ~120 lines of activity wiring |

## Goal

Blueprint §3.5: *"Screen-size agnostic: every panel is one piece of content that shows as a drawer on
a phone and a small popover on a tablet. Nothing is designed twice."* And §5b: Joy Brush wears
Joy Creator's own look — tokens, pills, frosted see-through panels, one action gradient.

This row builds the **frame**: the control cluster that sits at the bottom of the canvas, the thumb
rail that runs down one edge, and the one panel component every later row (layers, guides, gradient
editor, settings) opens. It is also the row where the pills that earlier tasks scattered around
`JoyBrushActivity` — smoothing, Undo/Redo/Clear, Eraser (JB-0.05), Brush (JB-1.05b), Tool (JB-2.06b),
Colour (JB-2.03a), the brush swatch (JB-2.16) — get **re-hosted into the cluster**, so the screen
stops being a bag of pills.

## Contract

```kotlin
package cc.joycreator.joybrush.core.chrome

/**
 * The pure decisions this row makes, in core so they can be tested on a computer. No Android, no
 * View, no pixels — the shell reads these and lays itself out.
 */
object ChromeRules {
    /** How a panel presents itself. ONE piece of content, two presentations. */
    enum class PanelForm { DRAWER, POPOVER }

    /** Joy Brush's own two-colour identity (§4.2 of the visual language: indigo → bright blue). */
    const val SECTION_COLOR_START = 0xFF5C43FD.toInt()
    const val SECTION_COLOR_END = 0xFF4397FD.toInt()

    /**
     * SMALLEST width (dp) at which a panel is a popover rather than a drawer. Below it a drawer
     * hangs from the top edge and takes the full width; at or above it the panel floats beside its
     * anchor at a fixed width and never covers the drawing.
     */
    const val POPOVER_MIN_WIDTH_DP = 600

    /** The window's smallest width, in dp. */
    fun panelForm(smallestWidthDp: Int): PanelForm

    /** Whether the chrome is showing. Four fingers tap toggles it (JB-2.02 wires onToggleUi). */
    enum class Chrome { SHOWN, HIDDEN }

    /**
     * The chrome state after [taps] four-finger taps. Pure, so the toggle is a test rather than a
     * field: a tap is a tap however many of them there are.
     */
    fun afterToggle(current: Chrome, taps: Int): Chrome

    /**
     * The one action gradient's two stops, as ARGB. Gradient means ACTION and is app-wide
     * (visual language §1.4): the house GO gradient, never Joy Brush's own section colour. The
     * section colour is identity and appears on the header hairline, the board tab and the marquee,
     * never on a button.
     */
    fun actionGradientStops(): IntArray

    /** Every touch target in the cluster and the rail, in dp, the SMALLEST it may be. */
    const val MIN_TOUCH_DP = 40
}
```

## Decisions

1. **One component, two forms, one breakpoint.** `panelForm` switches at 600 dp of *smallest
   width* (not current width), so rotating a tablet never re-forms a panel mid-gesture and a phone
   in landscape still gets a drawer. 600 dp is the conventional Android "large" boundary and is
   above the Tab S8 in either orientation **except landscape** — flagged in Questions, because on a
   Tab S8 in landscape the smallest width is ~1279 dp, so this is safe; but a large foldable inner
   display is the case the number is really choosing.
2. **A panel is never designed twice.** `ChromePanel` holds the content as a `ViewGroup` and swaps
   only its own frame: bottom-rounded 18 dp + full width + a 16 dp grip strip on a phone; a
   floating rounded surface at a fixed max width beside its anchor on a tablet. The content, the
   scroll behaviour and the accent are identical. A test asserts the two forms produce the same
   content view instance and differ only in layout params.
3. **Drawers are never opaque and the fill is the shared one.** `ObjectDrawer.Kit.drawerFill(ctx)`
   (black at 50 % see-through, the `studio_drawer/see_through_pct` preference) or
   `TextOverlayDrawer.Kit.surface` for the popover. Joy Brush does not write a second scrim.
4. **The cluster is the bottom of the screen; the rail is one edge, and it is a MOVING not a
   duplicating.** The rail's items are the *same view instances* the cluster holds, re-parented on
   layout. A control that is in both places is the same object, so a value shown on it cannot
   disagree with itself. Test: `everyControlIdHasExactlyOneLiveView`.
5. **The rail is the THUMB edge and it is chosen once, from handedness settings, not from the
   rotation.** `android.view.ViewConfiguration` has no handedness, so the side is a setting
   (right-hand default). A rebuild re-uses the stored side. Rotating the device does not move the
   rail across the screen under the person's thumb.
6. **Every tappable thing gets a hover label = TalkBack name = tooltip**
   (`Kit.describe(v, name)` / `SheetKit.label(v, what)`), per visual language §2. Mandatory here,
   stylus-first. A test walks the built view tree and fails on any clickable with no
   `contentDescription`.
7. **Chrome visibility is one enum and one toggle.** `afterToggle` is a pure function so a
   four-finger tap, the "×" and a future gesture cannot each keep their own copy. HIDDEN hides the
   cluster, the rail and every panel; the canvas is untouched and the drawing is never affected.
8. **Motion**: drawer/popover open 220 ms from scale 0.95 (never 0), the tool-swap cross-fade 170 ms,
   and **0 ms for brush pick, colour pick, undo, redo** (visual language §1.10: 100×-a-day
   controls). Reduced motion keeps opacity/colour and drops travel/scale.
9. **Tokens only.** No hex literal in a Joy Brush view (D.01 + `tools/check_joybrush_tokens.py`
   fails on a mirror that drifts by hand). The two values in the contract above are the only
   exceptions and they are `const` in ONE place with a comment naming the XML token they mirror —
   and a test asserts they still equal `Studio.ROOM_BRUSH` / `ROOM_BRUSH_END`.

## Tests (`ChromeRulesTest`, JVM, `:core:jvmTest`)

1. `panelForm(599) == DRAWER`, `panelForm(600) == POPOVER`, and negative / 0 → `DRAWER`.
2. `afterToggle(SHOWN, 1) == HIDDEN`, `(HIDDEN, 1) == SHOWN`, `(HIDDEN, 0) == HIDDEN`,
   `(SHOWN, 3) == HIDDEN`.
3. `actionGradientStops()` returns two distinct ARGB values, and they are NOT the section colour
   stops (a section colour on a button is the house's stated error).
4. `MIN_TOUCH_DP >= 40` and every constant the spec quotes is a named constant (a reflective
   walk over `ChromeRules`, so adding a magic number later is a red test).

   The rest of the decisions are checked on the device and by `JoyBrushActivity`'s own
   instrumentation-free assertions, which are listed in the **owner check** below rather than
   pretended into a JVM test.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles
`:joybrush-android` green.

## Owner check (Note 9, then Tab S8 — the Tab is where this row is actually proved)

Phone: draw with a finger (no pen), tap the pills — Undo, Redo, Clear, Brush, Tool, Colour, Paper,
Layers; four-finger tap hides everything, tap again brings it back; open Layers → a drawer hangs
from the top, the picture shows through it, the art behind is not black. Rotate → still a drawer.
Tablet: same taps, Layers opens as a popover beside its anchor and does not cover the drawing;
rotating keeps it a popover. S Pen hover over every pill → a label appears. Layer opacity: the
cluster's drag and the panel's slider move the SAME number.

## Do not

- **Do not move any file in `app/`.** D.02 (`:studiokit`) and D.02c are the app-file work, one at a
  time, never beside JB-0.09. This spec's views are in `joybrush/` and `joybrush-android/`.
- Do not add a colour picker, a layer list or any panel CONTENT — later rows own those. This is
  the frame, and it is dispatchable with every one of them unbuilt.
- Do not write a second scrim, a second press-feedback helper or a second `Toast`. Record 05's
  toast is listed as a GAP in visual language §2 and is **not** this row's; see Questions.
- Do not move the pills' behaviour — only where they live. A re-host that changes a value is a
  bug, not a refactor.
- Never run gradle on the owner's PC (the watcher builds).

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green with `:joybrush-android:compileDebugKotlin` EXECUTED
- [ ] owner check noted (phone **and** Tab S8)
- [ ] committed `JB-2.01: screen chrome`, pushed
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. Written from the blueprint, the
visual language, LEAD_RULINGS R20–R24 and the specs of every pill this row re-hosts.)_

### 🔴 For the Lead — this row cannot be dispatched, and the reason is a product decision, not a missing file

1. **Both of its "Needs" rows are unbuilt and one of them is yours.** JB-0.09 is ⚪ Outline and R12
   marks it ⛔ Blocked on the owner's `LobbyFragment` (R14 reclassified those six files as agent
   leftovers, which unblocks it — but that is your call and your procedure). D.02 is Lead-owned,
   "do not dispatch", and R14's serialisation order is D.02a → D.02 → D.02c / D.05, one at a time.
   **What the ruling has to cover, at minimum:** (a) is this row allowed to start as soon as D.02
   lands, or does it also wait for JB-0.09's chrome to exist so the entry and the screen are one
   decision? (b) the cluster's *shape* — how many rows, what order, what lives in the rail versus
   the panel — is a design decision this spec has made provisionally (below) and a T2-V builder
   will execute it literally; if the owner has a picture in mind, it is cheaper to change it here.
2. **The provisional shape, so it can be overruled in one line rather than discovered on the phone.**
   Bottom cluster, two rows: row 1 = **Undo · Redo · Tool · Brush swatch · Colour swatch** (the
   five a painter touches constantly); row 2 = **Paper · Layers · Helpers · Gradient · Export ·
   ⋯ (the drawer: smoothing, Eraser, Clear, Settings)**. The rail carries **Brush · Colour ·
   Layers** only — the three that must be reachable while the other hand is on the page, and
   nothing that could grow. I would rather be wrong here in a spec than on a phone.
3. **The 600 dp breakpoint and the Tab S8.** Safe as argued (the S8's smallest width is ~1279 dp
   landscape, ~800 dp portrait), so the breakpoint is really choosing for a **large foldable inner
   display**, where a floating popover beside the drawing is right. Confirm 600, or say 840 (the
   classic "expanded" width) if a tablet in portrait should still get a drawer.
4. **Is the toast this row's?** Visual language §2 lists a shared toast as a GAP with 451 raw
   `Toast.makeText` calls in the app. Joy Brush needs refusals in words constantly (JB-2.15, the
   save wiring, every budget refusal), so this row will want a toast/hud. R23 says share, don't
   copy — but a shared toast means an app-file change, which the serialisation order does not
   currently have a slot for. **Three options:** (a) a `JbToast` in `joybrush-android` now, and a
   later shared one supersedes it; (b) hold the refusals until a shared toast is scheduled; (c) add
   the shared toast to the D.02c/D.05 order now. I have specified (a) as the minimum, because a row
   that must "say so in words" and cannot is a row that will be silent.

### Low-risk, ruled provisionally — reverse any of these in a line

5. **The rail follows handedness, not rotation** (Decision 5). There is no system handedness on
   Android, so it is a setting. A person who switches hands has to say so.
6. **Only the three most-used controls go in the rail** (Decision 2's shape). A rail with eight
   items is a second cluster and the screen stops having one place to look.
7. **Hiding the chrome never hides a modal.** A dialog or a popover in flight is dismissed first.

## Lead note 2026-09-30 — the shape is being redesigned with the owner (supersedes Questions 1–2)

The owner sent Infinite Painter and Concepts screenshots as the target: compact chrome. The proposal now on the table
(https://claude.ai/artifact/3WvEvXr9fxVWREpMbU4mA9, v2) replaces the two-row bottom cluster with: a slim draggable **tool strip**
on one edge (Brush, Smudge, Eraser, Size, Colour, Opacity) with the D.02c recent-colour hair beside it; four icons top-right
(guides, pinned reference, layers, more) and Home/Undo top-left; **layers as a thumbnail column** on the right edge with per-layer
options in a popover (opacity, 27 blend modes, mask, clip); a **brush drawer** with kinds on the left and stroke samples on the right;
a **pinned reference picture**; a Concepts-style **dial** later as a setting; every piece movable with a reset.
**Do not build this row until the owner answers the five decisions on that page.**

## Lead build 2026-09-30 — built by the Lead to the owner-approved mockup v2 (supersedes the Contract and Decisions above)

The owner approved v2 ("I think that is good design … you should do the ui, not a cheap model") and all five picks on it:
strip on the left edge and draggable; layers as a thumbnail column; a pinned reference in the top bar; the strip now and the
dial later as a setting; everything movable with a "Put everything back". The two-row cluster, the thumb rail and the 600 dp
drawer/popover switch above are NOT built and are not coming back.

**What is built.**
- Core, pure and tested (`core/chrome/`, `ChromeCoreTest` 15/15): `StripPlacement` (snap to the nearer edge, clamp on screen,
  one-line preferences), `ToolMemory` (brush, smudge and eraser each remember their own brush, size and opacity; a brush
  picked in the drawer goes to the tool it belongs to), `BrushShelf` (the drawer's kinds; only non-empty shelves are shown),
  and `SampleStroke` (a drawer row's sample is a real stroke from the brush's own dabber, placer and scatter).
- Views in `joybrush-android/.../chrome/`: `ToolStripView`, `TopButton`, `Popovers` (one panel at a time; a tap outside
  closes it and is consumed, so it never leaves a dot), `BrushDrawerView`, `ValueHud` (the size ring at the true width and
  zoom, or the colour at its opacity, while dragging), `ReferenceView` (move, turn and scale; never saved into the drawing),
  `JbIcon`, `ChromeKit`.
- `studiokit/.../tools/DrawerFill.java`: the see-through drawer fill, moved out of `ObjectDrawer.Kit` (R23); Kit delegates.

**Design calls made on the phone (Note 9, 2026-09-30), each seen in a screenshot first.**
1. The strip and the top chips use a near-solid panel (`ChromeKit.chrome()`, 90%), as the mockup drew them. At the drawer's
   50% they read as flat grey over white paper and looked switched off. Drawers and panels keep `DrawerFill` (the owner's rule).
2. Opacity is a gauge ring with the colour inside, never a plain disc: at 100% it was indistinguishable from the colour button.
3. The drawer is full width on a phone. The Note 9 at its dense setting is 548 dp wide, not 411 dp; the first cap at 460 dp
   stopped it short. Now capped at 600 dp.
4. A brush's name sits in a band above its sample, never over it: a name in the corner hid the eraser's stroke.
5. The drawer opens on All until the tool's own shelf holds two brushes. A shelf of one was a nearly empty drawer.
6. A disabled Undo dims its icon, not its chip. Sliders are drawer ink on a faint track, not the platform's lavender.
   "Clear drawing" is ink text with a red dot, because red text over a see-through panel was hard to read.
7. The pen diagnostics' hidden door moved from holding the old × to holding ⋯.

**Checked on the Note 9 by the Lead:** open from the lobby room; draw; Undo turns on; the stroke's colour joins the hair;
tap the brush again → drawer; pick Marker; drag size (the readout shows the true width); draw at the new size and opacity;
the eraser tool erases at its own size; drag the strip → it snaps to the right edge with the hair on the inside; the strip's
place and the drawing survive a reinstall; ⋯ → Put everything back; tap Size → slider; tap Colour → the Studio's picker; a
tap outside a panel closes it with no mark on the drawing.

**Not checked (needs the owner):** the pinned reference, which needs a picture picked from his files; the four-finger hide
(adb cannot tap with four fingers); a real pen.

**Still to come, in their own rows:**
- The layer column (JB-2.04, with the multi-layer view).
- The guides button (JB-2.12).
- The small colour-wheel popover beside the strip. For now the Studio's full picker opens, and it dims the drawing.
- The dial, as a later setting.

### Lead 2026-09-30 (later): solid icons that ink themselves from the picture (owner ruling)

Owner: *"the icons along the top and any icon that would expand into a menu should be solid and … auto detect what's behind
them"*: a little lighter than middle grey means a black icon, a little darker means white with a drop shadow. Built:
- Every `JbIcon` is solid; the top bar has no chips.
- `core/chrome/IconContrast` picks the ink (`IconContrastTest` 7/7). Middle grey is L* 50 (sRGB 119, not 128). There is a ±4 L*
  hold band so an icon does not flicker. An icon over mixed black and white (samples 45 L* or more apart) is white with a shadow.
  Samples are averaged in luminance.
- `JbCanvasView.sampleScreen` reads the real screen right after a frame (every layer, the paper, the grey outside the page). The
  pinned reference answers for any point it covers. Each icon reads a 3 × 3 grid, re-read at most every 120 ms after a pan,
  zoom, turn, stroke, undo, load or reference move.
- Checked on the Note 9: white icons over marker paint, black over white paper, and back to black after undo.
