# JB-2.02c — Pen buttons: detect them, name them, and let the user assign what they do (the map and the detector)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | Ready (Lead-written, LEAD_RULINGS R42) |
| **Depends on** | none (JB-0.06's diagnostics already print `buttonState`; this row does not touch it) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/input/PenButtons.kt`; NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/input/PenButtonsTest.kt`. Nothing else. |
| **Estimated size** | ~200 lines + ~220 lines of tests |

## Goal
The owner wants the S Pen button to be a lasso, but every stylus button — and a back-end eraser, and the
extra buttons on legacy Wacoms and off-brand USB pens — must be assignable in Settings, appearing as slots
whenever the hardware is detected (R42). This row is the pure part: what a button IS, how a new one is noticed,
which actions exist, and the assignment table with its rules. The routing (JbCanvasView) and the settings
screen are other rows; this one has no Android in it.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.input

/** One physical control on a pen. A value, so it can be a key in a saved map. */
data class PenButton(val kind: Kind, val code: Int) {
    enum class Kind { STATE_BIT, KEY, ERASER_END }   // APPEND-ONLY (a saved setting names it)

    companion object {
        // MotionEvent.BUTTON_* values, copied as plain numbers because core has no Android types.
        const val BIT_PRIMARY = 0x01      // the tip touching the screen: NEVER a button (Decision 2)
        const val BIT_SECONDARY = 0x02
        const val BIT_TERTIARY = 0x04
        const val BIT_BACK = 0x08
        const val BIT_FORWARD = 0x10
        const val BIT_STYLUS_PRIMARY = 0x20
        const val BIT_STYLUS_SECONDARY = 0x40
        val ERASER_END = PenButton(Kind.ERASER_END, 0)
        fun bit(mask: Int) = PenButton(Kind.STATE_BIT, mask)
        fun key(keyCode: Int) = PenButton(Kind.KEY, keyCode)
    }

    /** A name for a person: "Pen button 1" etc. — Decision 3. Never empty. */
    fun displayName(): String
}

enum class PenAction(val style: Style) {           // APPEND-ONLY
    NONE(Style.EITHER),
    LASSO(Style.HOLD),          // a path drawn while held is a lasso; it closes itself on lift
    ERASE(Style.HOLD),          // a stroke drawn while held erases with the current brush
    PAN(Style.HOLD),            // dragging the pen pans the page
    EYEDROPPER(Style.TAP),
    UNDO(Style.TAP),
    REDO(Style.TAP),
    TOGGLE_CHROME(Style.TAP),
    BRUSH_PREV(Style.TAP),
    BRUSH_NEXT(Style.TAP);
    enum class Style { HOLD, TAP, EITHER }
}

/** What one button does while held and when tapped. */
data class PenBinding(val hold: PenAction = PenAction.NONE, val tap: PenAction = PenAction.NONE)

/** The tool that produced an event. Core's own copy of the three that matter here. */
enum class PenTool { STYLUS, ERASER, OTHER }

class PenButtonMap private constructor(
    val bindings: Map<PenButton, PenBinding>,
    val seen: Set<PenButton>,
    /** Names this build did not understand, kept so a re-save does not lose them (Decision 7). */
    private val unknownRaw: List<String>,
) {
    fun bindingOf(b: PenButton): PenBinding                     // unbound → PenBinding()
    /** Marks buttons as seen (slots appear). Never removes anything. */
    fun withSeen(found: Collection<PenButton>): PenButtonMap
    /** The new map, or the sentence saying why not (Decision 4). The original is never changed. */
    fun withHold(b: PenButton, a: PenAction): Assigned
    fun withTap(b: PenButton, a: PenAction): Assigned
    /** The action in force right now for a pen touching the screen with [buttonState] down. */
    fun holdActionFor(tool: PenTool, buttonState: Int): PenAction
    /** The action for a press-release of [b] (no drawing in between). */
    fun tapActionFor(b: PenButton): PenAction

    sealed class Assigned {
        data class Ok(val map: PenButtonMap) : Assigned()
        data class Refused(val reason: String) : Assigned()
    }

    fun toJson(): String
    companion object {
        val DEFAULT: PenButtonMap
        fun fromJson(text: String): PenButtonMap   // never throws: bad text → DEFAULT
    }
}

object PenButtonDetector {
    /**
     * Buttons newly pressed between two `buttonState` readings, ascending by bit. The tip's contact
     * bit is removed first. An ERASER tool that has not been seen yet yields [PenButton.ERASER_END].
     */
    fun newlyPressed(prevState: Int, state: Int, tool: PenTool, eraserAlreadySeen: Boolean): List<PenButton>
}
```

## Decisions
1. **Buttons are found, not listed.** A button is a slot from the first moment it is seen and stays one. The
   settings screen shows seen buttons only — a phone with one button shows one row; a Wacom with four shows
   four. Reason: the owner's hardware varies and nobody can list what a USB pen will send.
2. **`BIT_PRIMARY` (0x01) is never a button.** It is the tip touching. `newlyPressed` removes it before doing
   anything else, for every tool. (Device check: confirm with the pen diagnostics on the Note 9 that the bit
   pattern with the tip down and no button is what this assumes; if the tip sets another bit, the builder
   reports it — it does not guess.)
3. **Names for people.** 0x20 → "Pen button", 0x40 → "Second pen button", 0x02 → "Right-click button",
   0x04 → "Middle button", 0x08 → "Back button", 0x10 → "Forward button", eraser end → "Eraser end", a key →
   "Key <code>", any other bit → "Button 0x<hex>". Always non-empty.
4. **Assignment rules, in words.** `hold` accepts only Style HOLD or EITHER actions (NONE, LASSO, ERASE, PAN);
   `tap` only TAP or EITHER (NONE, EYEDROPPER, UNDO, REDO, TOGGLE_CHROME, BRUSH_PREV, BRUSH_NEXT). Anything else
   returns `Refused("Lasso works while you hold the button, not when you tap it.")`-style sentences naming the
   action. Two buttons MAY share an action (unlike finger taps, nothing becomes unreachable).
5. **Defaults** (`DEFAULT`): STYLUS_PRIMARY hold = LASSO, tap = EYEDROPPER; STYLUS_SECONDARY hold = ERASE;
   ERASER_END hold = ERASE; everything else unbound. `seen` starts EMPTY (a slot appears only once detected).
6. **`holdActionFor`.** A pen tool ERASER uses the ERASER_END binding's hold (default ERASE) whatever the state
   bits; otherwise the lowest set non-tip bit that has a hold action other than NONE decides; none → NONE.
7. **Persistence never loses.** `toJson`/`fromJson` are a small versioned JSON. An action name this build does
   not know reads as NONE and its raw text is kept in `unknownRaw` and written back; a button of an unknown
   `Kind` is kept the same way. `fromJson` of garbage returns `DEFAULT`, never throws.
8. **Core only.** No Android types; hover-clicks are the router's problem (a button acts only while the pen
   touches — R42) and are not modelled here.

## Tests (each expected value derived in the test)
1. `newlyPressed(0, 0x20, STYLUS, false)` → `[bit(0x20)]`; `(0x20, 0x20)` → `[]`; `(0x20, 0x60)` → `[bit(0x40)]`;
   `(0, 0x60)` → `[bit(0x20), bit(0x40)]` (ascending). Derivation: the new bits are `state and prev.inv()`.
2. The tip bit: `(0, 0x01, STYLUS, false)` → `[]`; `(0, 0x21, STYLUS, false)` → `[bit(0x20)]` (0x21 = tip + button).
3. Eraser: `(0, 0, ERASER, false)` → `[ERASER_END]`; with `eraserAlreadySeen = true` → `[]`.
4. `displayName()`: 0x20 → "Pen button", 0x40 → "Second pen button", 0x100 → "Button 0x100", key 131 → "Key 131",
   eraser end → "Eraser end"; none blank.
5. Refusals: `withHold(b, EYEDROPPER)` → Refused naming Eyedropper; `withTap(b, LASSO)` → Refused naming Lasso;
   `withHold(b, ERASE)` → Ok. The refused case leaves the original map `==`.
6. Defaults: primary hold LASSO, tap EYEDROPPER; secondary hold ERASE; unseen bit 0x08 → `PenBinding()`; `DEFAULT.seen` empty.
7. `holdActionFor(STYLUS, 0x20)` = LASSO; `(STYLUS, 0x60)` = LASSO (lowest bit wins); `(STYLUS, 0x40)` = ERASE;
   `(STYLUS, 0x01)` = NONE (tip only); `(ERASER, 0)` = ERASE; after rebinding ERASER_END hold to PAN, `(ERASER, 0)` = PAN.
8. Sharing: two buttons both set to ERASE is accepted.
9. Round trip: bind 0x08 hold = PAN, mark seen, `fromJson(toJson())` equals the original; JSON with
   `"hold":"WARP_DRIVE"` reads as NONE for that slot and a re-`toJson()` still contains `WARP_DRIVE`; `fromJson("{{")` = DEFAULT.
10. Append-only guard: `PenAction.entries.map { it.name }` and `PenButton.Kind.entries.map { it.name }` equal the
    lists in the Contract (a reorder or rename is a red test, the way `Tool` is frozen).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
- Do not read `MotionEvent` or any Android type. Do not edit `PenDiagnosticsView`, `JbCanvasView` or `JoyBrushActivity`.
- Do not bind the tip bit. Do not invent a hover action. Do not add an action without a test.

## Definition of done
Tests pass (paste) · only owner-area files changed · commit `JB-2.02c: pen buttons map and detector` · board row → 🟧 Built.

## Questions
(Builders: write here, never guess.)
