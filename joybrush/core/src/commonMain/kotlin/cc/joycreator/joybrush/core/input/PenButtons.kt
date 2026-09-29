package cc.joycreator.joybrush.core.input

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * One physical control on a pen. A value, so it can be a key in a saved map.
 *
 * A button is a VALUE, never a name, because the owner's hardware varies (LEAD_RULINGS R42): a
 * Samsung Note S Pen has one, some legacy Wacoms four, off-brand USB pens report mouse-style bits,
 * and nobody can list in advance what a pen will send. So [Kind] covers every way a control can
 * arrive — a bit in `buttonState`, a tablet "express key" keycode, or a whole pen tool (the
 * back-end eraser) — and the app shows a slot for each control it has actually SEEN.
 *
 * **APPEND-ONLY.** `Kind` is named by saved settings (`penbuttons.json`), so a value is added at the
 * end and never reordered, renamed or removed. [PenButtonsTest] freezes the names the way
 * `ToolOrdinalFreezeTest` freezes [Tool].
 *
 * Android's `MotionEvent.BUTTON_*` numbers are copied here as plain literals because core has no
 * Android types (blueprint §3.1); the shells pass the raw ints through. If Android ever renumbers
 * one of these, this file is where the change lands.
 */
data class PenButton(val kind: Kind, val code: Int) {

    /** How a control is reported. Never a bit mask: one value names exactly one control. */
    // APPEND-ONLY (a saved setting names it)
    enum class Kind { STATE_BIT, KEY, ERASER_END }

    companion object {
        /**
         * The tip touching the screen. **NEVER a button** (R42): it is set by contact, not by a
         * finger on the barrel, so binding it would let one physical control mean two things. Every
         * path that reads a `buttonState` mask removes it first — [PenButtonDetector.newlyPressed]
         * and [PenButtonMap.holdActionFor] both do.
         */
        const val BIT_PRIMARY = 0x01
        const val BIT_SECONDARY = 0x02
        const val BIT_TERTIARY = 0x04
        const val BIT_BACK = 0x08
        const val BIT_FORWARD = 0x10
        const val BIT_STYLUS_PRIMARY = 0x20
        const val BIT_STYLUS_SECONDARY = 0x40

        /** The other end of the pen, flipped round. A tool, not a bit. */
        val ERASER_END = PenButton(Kind.ERASER_END, 0)

        /** One bit of a `buttonState` mask. [mask] is a single bit; use [bitsOf] to enumerate. */
        fun bit(mask: Int) = PenButton(Kind.STATE_BIT, mask)

        /** A tablet "express key", named by its platform keycode (any int). */
        fun key(keyCode: Int) = PenButton(Kind.KEY, keyCode)
    }

    /**
     * A name for a person: "Pen button 1" etc. (Decision 3).
     *
     * **Always non-empty**, including for a bit this build has never heard of — a settings screen
     * that renders an empty row is the silent-drop bug wearing a different hat, so the fallback is
     * built from the number itself (`"Button 0x100"`). The hex is unpadded, which is why 0x100
     * reads `0x100` and not `0x0100`; it is also the only place a control gets a name it was not
     * given, and the name still identifies it exactly.
     */
    fun displayName(): String = when (kind) {
        Kind.ERASER_END -> "Eraser end"
        Kind.KEY -> "Key $code"
        Kind.STATE_BIT -> when (code) {
            BIT_STYLUS_PRIMARY -> "Pen button"
            BIT_STYLUS_SECONDARY -> "Second pen button"
            BIT_SECONDARY -> "Right-click button"
            BIT_TERTIARY -> "Middle button"
            BIT_BACK -> "Back button"
            BIT_FORWARD -> "Forward button"
            else -> "Button 0x" + code.toUInt().toString(16)
        }
    }
}

/**
 * What one button does.
 *
 * [Style] is the whole assignment rule in one word: [Style.HOLD] actions work only while the button
 * is down, [Style.TAP] actions only on a press-and-release with no drawing between, and
 * [Style.EITHER] (just [NONE]) fits both slots. `Pan` and an eyedropper genuinely cannot both be
 * true of the same gesture, so the rule is written down rather than left to the screen.
 *
 * **APPEND-ONLY** and **name-stable**: a saved `penbuttons.json` stores these names as text, so
 * this enum is the on-disk vocabulary. Renaming a value silently unbinds every person who used it.
 */
enum class PenAction(val style: Style) {
    NONE(Style.EITHER),
    LASSO(Style.HOLD), // a path drawn while held is a lasso; it closes itself on lift
    ERASE(Style.HOLD), // a stroke drawn while held erases with the current brush
    PAN(Style.HOLD), // dragging the pen pans the page
    EYEDROPPER(Style.TAP),
    UNDO(Style.TAP),
    REDO(Style.TAP),
    TOGGLE_CHROME(Style.TAP),
    BRUSH_PREV(Style.TAP),
    BRUSH_NEXT(Style.TAP);

    enum class Style { HOLD, TAP, EITHER }
}

/** What one button does while held and when tapped. Both `NONE` is the same as "not bound". */
data class PenBinding(val hold: PenAction = PenAction.NONE, val tap: PenAction = PenAction.NONE)

/**
 * The tool that produced an event — core's own copy of the three that matter here.
 *
 * Deliberately NOT [Tool]: that enum's ordinals are frozen into stroke files (JB-0.04) and has four
 * values, of which the button map cares about one. A copy with its own three values means this
 * settings surface cannot be dragged into changing a stroke file's tool ordinals by accident. The
 * router (the Lead's row) maps `Tool` to this; the map itself never sees a [Tool].
 */
enum class PenTool { STYLUS, ERASER, OTHER }

/**
 * The assignment table: which action each pen button does, and which buttons have been seen at all.
 *
 * Immutable — every "change" returns a new map and never touches the receiver, which is what makes
 * "a refused assignment leaves the map alone" true by construction rather than by a careful `else`.
 *
 * **Nothing is ever dropped (Decision 7, R42).** A newer Joy Brush can name a button kind or an
 * action this build has never heard of, and a person's setting has to survive them downgrading.
 * Such an entry is kept verbatim in [unknownRaw] and written back out unchanged, so the text is
 * still there when they upgrade again. This is the same "silently discard a setting" family the
 * document format refuses (R31), and it is why an unknown entry is preserved *whole*: a detector
 * that replaced the unknown name with a default on the way IN would have thrown away the only copy
 * there is.
 */
class PenButtonMap private constructor(
    /** Bound buttons. A button absent here is unbound and reads as [PenBinding]. */
    val bindings: Map<PenButton, PenBinding>,
    /** Buttons detected at least once. Empty on [DEFAULT]; a slot appears by detection, never by list. */
    val seen: Set<PenButton>,
    /**
     * Raw JSON entries this build could not interpret — an unknown [PenButton.Kind], an unknown
     * [PenAction] name, or an entry too malformed to read. Held as the exact text that will be
     * written back, so nothing depends on re-serialising something that was never understood.
     */
    private val unknownRaw: List<String>,
) {

    /** What [b] does. An unbound button is not an error; it is [PenBinding], both slots [PenAction.NONE]. */
    fun bindingOf(b: PenButton): PenBinding = bindings[b] ?: PenBinding()

    /**
     * Marks buttons as seen, so their slots appear. Never removes anything: a button that was
     * detected once stays a slot even after the pen is put away, because otherwise a user's
     * assignment would silently vanish from a screen after a reboot.
     */
    fun withSeen(found: Collection<PenButton>): PenButtonMap =
        if (found.isEmpty()) this else PenButtonMap(bindings, seen + found, unknownRaw)

    /** The new map with [a] bound to [b] while held, or the sentence saying why not. */
    fun withHold(b: PenButton, a: PenAction): Assigned =
        if (a.style == PenAction.Style.HOLD || a.style == PenAction.Style.EITHER) {
            Assigned.Ok(rebinding(b) { it.copy(hold = a) })
        } else {
            Assigned.Refused(refusal(a, forHold = true))
        }

    /** The new map with [a] bound to [b] on a tap, or the sentence saying why not. */
    fun withTap(b: PenButton, a: PenAction): Assigned =
        if (a.style == PenAction.Style.TAP || a.style == PenAction.Style.EITHER) {
            Assigned.Ok(rebinding(b) { it.copy(tap = a) })
        } else {
            Assigned.Refused(refusal(a, forHold = false))
        }

    /**
     * The action in force right now for a pen touching the screen with [buttonState] down.
     *
     * The tip's own bit is skipped (Decision 2), then the bits are read **lowest first** and the
     * first one with a hold action other than [PenAction.NONE] decides. Lowest-first is a rule and
     * not an accident: `buttonState` is a set, not a list, so "which of three held buttons wins"
     * has no answer of its own, and a fixed order means the same three buttons always resolve the
     * same way on every device. The tip bit is 0x01, the lowest of all, so skipping it by value
     * also happens to be skipping it by position — but it is skipped by name as well, in case a
     * future pen reports the tip in a higher bit.
     *
     * A tool of [PenTool.ERASER] ignores the state bits entirely and uses the eraser end's own hold
     * binding (ERASE by default): the pen's other end is not a button being pressed, it is a
     * different tool, and the bits describing it are whatever the shell felt like sending.
     */
    fun holdActionFor(tool: PenTool, buttonState: Int): PenAction {
        if (tool == PenTool.ERASER) return bindingOf(PenButton.ERASER_END).hold
        val bits = buttonState and PenButton.BIT_PRIMARY.inv()
        for (i in 1..31) {
            val mask = 1 shl i
            if (bits and mask != 0) {
                val hold = bindingOf(PenButton.bit(mask)).hold
                if (hold != PenAction.NONE) return hold
            }
        }
        return PenAction.NONE
    }

    /** The action for a press-and-release of [b] with no drawing in between. */
    fun tapActionFor(b: PenButton): PenAction = bindingOf(b).tap

    /** The file. See the class KDoc: nothing is dropped, and output is byte-stable. */
    fun toJson(): String = JSON.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put(KEY_FORMAT, FORMAT_TAG)
            put(KEY_VERSION, VERSION)
            put(
                KEY_SEEN,
                buildJsonArray { ordered(seen).forEach { add(buttonJson(it)) } },
            )
            put(
                KEY_BINDINGS,
                buildJsonArray {
                    ordered(bindings.keys).forEach { button ->
                        val binding = bindings.getValue(button)
                        add(
                            buildJsonObject {
                                put(KEY_KIND, button.kind.name)
                                put(KEY_CODE, button.code)
                                put(KEY_HOLD, binding.hold.name)
                                put(KEY_TAP, binding.tap.name)
                            },
                        )
                    }
                },
            )
            // Written back exactly as it was read. Each string came from JsonElement.toString(), so
            // re-parsing it here cannot fail; a failure would be a bug, not a corrupt setting.
            put(KEY_UNKNOWN, buildJsonArray { unknownRaw.forEach { add(JSON.parseToJsonElement(it)) } })
        },
    )

    /** The result of an assignment: a new map, or one sentence a screen can show verbatim. */
    sealed class Assigned {
        data class Ok(val map: PenButtonMap) : Assigned()
        data class Refused(val reason: String) : Assigned()
    }

    private fun rebinding(b: PenButton, change: (PenBinding) -> PenBinding): PenButtonMap =
        PenButtonMap(bindings + (b to change(bindingOf(b))), seen, unknownRaw)

    /**
     * A stable order for the file: buttons sorted by kind then code.
     *
     * Without it, two saves of one map can differ byte for byte purely because a `Map`/`Set` was
     * built in a different order, and `penbuttons.json` is a file a person may well diff when a
     * setting "changes by itself" — the exact question DocJson answers the same way.
     */
    private fun ordered(buttons: Collection<PenButton>): List<PenButton> =
        buttons.sortedWith(compareBy({ it.kind.name }, { it.code }))

    private fun buttonJson(b: PenButton): JsonObject = buildJsonObject {
        put(KEY_KIND, b.kind.name)
        put(KEY_CODE, b.code)
    }

    companion object {
        private const val FORMAT_TAG = "joybrush.penButtons"

        /** Version 1: `seen`, `bindings` and `unknown` as described on [toJson]. Append-only fields. */
        private const val VERSION = 1

        private const val KEY_FORMAT = "format"
        private const val KEY_VERSION = "version"
        private const val KEY_SEEN = "seen"
        private const val KEY_BINDINGS = "bindings"
        private const val KEY_UNKNOWN = "unknown"
        private const val KEY_KIND = "kind"
        private const val KEY_CODE = "code"
        private const val KEY_HOLD = "hold"
        private const val KEY_TAP = "tap"

        // `prettyPrint` / `prettyPrintIndent` are still experimental in the pinned
        // kotlinx-serialization 1.8.1, the same opt-in DocJson takes. The file is written pretty
        // so a person can read and hand-edit a settings file without a JSON viewer.
        @OptIn(ExperimentalSerializationApi::class)
        private val JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /**
         * What a person gets before they touch anything (Decision 5, R42): the S Pen button is most
         * useful as a lasso, so it is the lasso, and it is still an eyedropper if tapped. The
         * secondary button erases. Every other control starts unassigned — and [seen] starts EMPTY,
         * because a slot appears when a button is detected, not when it is listed (Decision 1):
         * a phone with one button shows one row and a four-button Wacom shows four, with nobody
         * maintaining the list.
         */
        val DEFAULT: PenButtonMap = PenButtonMap(
            bindings = mapOf(
                PenButton.bit(PenButton.BIT_STYLUS_PRIMARY) to
                    PenBinding(PenAction.LASSO, PenAction.EYEDROPPER),
                PenButton.bit(PenButton.BIT_STYLUS_SECONDARY) to
                    PenBinding(PenAction.ERASE, PenAction.NONE),
                PenButton.ERASER_END to PenBinding(PenAction.ERASE, PenAction.NONE),
            ),
            seen = emptySet(),
            unknownRaw = emptyList(),
        )

        /**
         * Reads a `penbuttons.json`. **Never throws.** Anything this cannot read — broken JSON, a
         * file that is not an object, someone else's file, a missing or nonsensical version — gives
         * [DEFAULT], because refusing to start over a settings file that is not yet understood is
         * how a person loses a configured pen to a crash on launch.
         *
         * A file from a NEWER Joy Brush is read, not refused: its unknown parts are what [unknownRaw]
         * exists for. Refusing it would make downgrading a one-way door, which is the opposite of
         * keeping a newer build's setting alive.
         */
        fun fromJson(text: String): PenButtonMap = try {
            read(text)
        } catch (e: Exception) {
            // kotlinx raises SerializationException and IllegalArgumentException here, and a cast or
            // a lookup can raise others. All of them mean the same thing to the person waiting for
            // the app to open, so all of them get the same answer.
            DEFAULT
        }

        private fun read(text: String): PenButtonMap {
            val root = JSON.parseToJsonElement(text) as? JsonObject ?: return DEFAULT
            val format = (root[KEY_FORMAT] as? JsonPrimitive)?.contentOrNull
            if (format != null && format != FORMAT_TAG) return DEFAULT
            val version = (root[KEY_VERSION] as? JsonPrimitive)?.intOrNull ?: return DEFAULT
            if (version < 1) return DEFAULT

            // The `unknown` array is read FIRST, and that order is load-bearing rather than
            // incidental. Everything this build saved into it last time comes back before anything
            // newly discovered here, so saving and loading repeatedly reproduces the file byte for
            // byte. (It is also the whole reason the array is read at all: the first version of
            // this function only looked at `seen` and `bindings`, which silently DISCARDED every
            // preserved entry on the second save — the exact failure Decision 7 exists to stop,
            // found by the round-trip test on its first run.)
            val unknown = ArrayList<String>()
            for (element in elementsOf(root[KEY_UNKNOWN])) unknown += element.toString()
            val seen = LinkedHashSet<PenButton>()
            for (element in elementsOf(root[KEY_SEEN])) {
                val button = buttonFrom(element)
                if (button == null) unknown += element.toString() else seen += button
            }
            val bindings = LinkedHashMap<PenButton, PenBinding>()
            for (element in elementsOf(root[KEY_BINDINGS])) {
                val button = buttonFrom(element)
                val obj = element as? JsonObject
                val hold = actionFrom(obj?.get(KEY_HOLD))
                val tap = actionFrom(obj?.get(KEY_TAP))
                // An entry is EITHER understood or kept verbatim, never both. Normalising an entry
                // whose action name we do not know would write a second, different entry for the
                // same button — and that second entry says "NONE", which is a claim we cannot make
                // about someone else's file.
                if (button == null || hold == null || tap == null) {
                    unknown += element.toString()
                    continue
                }
                bindings[button] = PenBinding(hold, tap)
            }
            return PenButtonMap(bindings, seen, unknown)
        }

        private fun elementsOf(node: JsonElement?): List<JsonElement> = node as? JsonArray ?: emptyList()

        private fun buttonFrom(element: JsonElement): PenButton? {
            val obj = element as? JsonObject ?: return null
            val kindName = (obj[KEY_KIND] as? JsonPrimitive)?.contentOrNull ?: return null
            val kind = PenButton.Kind.entries.firstOrNull { it.name == kindName } ?: return null
            val code = (obj[KEY_CODE] as? JsonPrimitive)?.intOrNull ?: return null
            return PenButton(kind, code)
        }

        private fun actionFrom(node: JsonElement?): PenAction? {
            val name = (node as? JsonPrimitive)?.contentOrNull ?: return null
            return PenAction.entries.firstOrNull { it.name == name }
        }

        /**
         * The sentence a refusal shows. It names the action and says which way that action works,
         * because "not allowed" alone leaves the person guessing which of the two slots they got
         * wrong — and it is the same sentence for every action of a style, so there is one thing
         * to translate rather than one per action.
         */
        private fun refusal(action: PenAction, forHold: Boolean): String {
            val label = label(action)
            return if (forHold) {
                "$label works when you tap the button, not while you hold it."
            } else {
                "$label works while you hold the button, not when you tap it."
            }
        }

        /**
         * How an action is written in that sentence, and the display name the settings screen will
         * want for the same word. `when` over every constant on purpose: adding an action without
         * naming it is a compile error here, which is the "no action without a test" rule enforced
         * by the compiler instead of by discipline.
         */
        private fun label(action: PenAction): String = when (action) {
            PenAction.NONE -> "None"
            PenAction.LASSO -> "Lasso"
            PenAction.ERASE -> "Erase"
            PenAction.PAN -> "Pan"
            PenAction.EYEDROPPER -> "Eyedropper"
            PenAction.UNDO -> "Undo"
            PenAction.REDO -> "Redo"
            PenAction.TOGGLE_CHROME -> "Toggle the panels"
            PenAction.BRUSH_PREV -> "Previous brush"
            PenAction.BRUSH_NEXT -> "Next brush"
        }
    }
}

/**
 * Turns raw `buttonState` readings into buttons, so the router never has to think about masks.
 *
 * Deliberately stateless: it is a pure function of two consecutive readings, the tool and what the
 * caller already knows. That is what makes it testable on a PC with no device attached, and it is
 * why there is no clock anywhere in this file — **any debounce belongs to the router**, which is
 * the Lead's row, because only the router has timestamps (JB-2.02 Decision 8, and this file's
 * stop rule). A button that chatters therefore arrives here as several genuine edges, and each one
 * is a real press; suppressing them is a timing decision this API deliberately cannot make.
 */
object PenButtonDetector {
    /**
     * Buttons newly pressed between two `buttonState` readings, ascending by bit.
     *
     * The derivation is the whole rule: a bit is new when it is set NOW and was not set BEFORE, so
     * the mask of new bits is `state and prevState.inv()`. Ascending order is not cosmetic — the
     * caller may fire actions in this order, and a list that changed order between runs would fire
     * them in a different order on a different device.
     *
     * The tip's contact bit is removed BEFORE anything else, for every tool (Decision 2): putting
     * the pen down is not pressing a button, and a `buttonState` that only has the tip bit must
     * yield nothing at all.
     *
     * An eraser tool that has not been seen yet also yields [PenButton.ERASER_END], so the eraser
     * end gets a slot from the first stroke drawn with it rather than needing a settings trip to
     * be assignable. It is appended after the bits because it is a tool and not a bit, so it has no
     * place in the ascending order; [eraserAlreadySeen] is the caller's memory of whether that
     * slot already exists.
     */
    fun newlyPressed(
        prevState: Int,
        state: Int,
        tool: PenTool,
        eraserAlreadySeen: Boolean,
    ): List<PenButton> {
        val added = state and prevState.inv() and PenButton.BIT_PRIMARY.inv()
        val out = ArrayList<PenButton>(4)
        for (i in 1..31) {
            val mask = 1 shl i
            if (added and mask != 0) out += PenButton.bit(mask)
        }
        if (tool == PenTool.ERASER && !eraserAlreadySeen) out += PenButton.ERASER_END
        return out
    }
}
