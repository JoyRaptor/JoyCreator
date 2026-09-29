package cc.joycreator.joybrush.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-2.02c — the pen button map and the detector (LEAD_RULINGS R42).
 *
 * Every expected value below is derived in a comment from the bit arithmetic or the JSON text, not
 * read off a run: a number that only says what the code happens to do is not a test of anything.
 */
class PenButtonsTest {

    // ---- the detector -----------------------------------------------------------------------

    @Test
    fun newBitsAreFoundAscending() {
        // The rule: a bit is new when it is set now and was not set before, so the new mask is
        // `state and prevState.inv()`, tip bit (0x01) removed.
        //
        // 0x20 = 0b0010_0000, 0x40 = 0b0100_0000, 0x60 = 0b0110_0000.
        //   (0, 0x20):     0x20 and 0x20.inv() = 0x20                       -> bit 5
        //   (0x20, 0x20):  0x20 and 0x20.inv() = 0                         -> nothing
        //   (0x20, 0x60):  0x60 and 0x20.inv() clears bit 5                -> bit 6 = 0x40
        //   (0, 0x60):     both bits are new; ascending means 0x20 before 0x40 (bit 5 before bit 6)
        assertEquals(listOf(bitOf(0x20)), PenButtonDetector.newlyPressed(0, 0x20, PenTool.STYLUS, false))
        assertEquals(emptyList(), PenButtonDetector.newlyPressed(0x20, 0x20, PenTool.STYLUS, false))
        assertEquals(listOf(bitOf(0x40)), PenButtonDetector.newlyPressed(0x20, 0x60, PenTool.STYLUS, false))
        assertEquals(
            listOf(bitOf(0x20), bitOf(0x40)),
            PenButtonDetector.newlyPressed(0, 0x60, PenTool.STYLUS, false),
        )
    }

    @Test
    fun theTipBitIsNeverAButton() {
        // 0x01 is the tip touching the screen, not a control (Decision 2). It is removed before
        // anything else happens, so a pen simply landing on the glass reports nothing at all.
        assertEquals(emptyList(), PenButtonDetector.newlyPressed(0, 0x01, PenTool.STYLUS, false))
        // 0x21 = 0b0010_0001 = tip + the S Pen button. One button, not two.
        assertEquals(listOf(bitOf(0x20)), PenButtonDetector.newlyPressed(0, 0x21, PenTool.STYLUS, false))
        // Same rule for the eraser tool: its tip bit is still not a button.
        assertEquals(
            listOf(PenButton.ERASER_END),
            PenButtonDetector.newlyPressed(0, 0x01, PenTool.ERASER, false),
        )
    }

    @Test
    fun theEraserEndIsOfferedOnce() {
        // The back end of the pen is a tool, not a bit, so no bit ever fires it. The caller's
        // memory of whether the slot already exists is what stops it being offered every stroke.
        assertEquals(
            listOf(PenButton.ERASER_END),
            PenButtonDetector.newlyPressed(0, 0, PenTool.ERASER, false),
        )
        assertEquals(emptyList(), PenButtonDetector.newlyPressed(0, 0, PenTool.ERASER, true))
        // With an eraser that IS down as well as held, the bit still fires and the tool comes last:
        // the tool is not a bit and so has no place in the ascending order.
        assertEquals(
            listOf(bitOf(0x20), PenButton.ERASER_END),
            PenButtonDetector.newlyPressed(0, 0x20, PenTool.ERASER, false),
        )
    }

    @Test
    fun chatterIsEdgesAndThisApiHasNoClock() {
        // A chattering button, 0x20 -> 0x00 -> 0x20 -> 0x00 -> 0x20, is FIVE readings and therefore
        // three genuine press edges. That is not a defect here: `newlyPressed` is a pure function of
        // two readings and has no timestamps, so suppressing chatter is a timing decision that
        // belongs to the router (JB-2.02c Decision 8 — the routing in JbCanvasView is the Lead's).
        // This test exists so that decision stays visible in this file rather than implied.
        var prev = 0
        val presses = mutableListOf<PenButton>()
        for (state in listOf(0x20, 0x00, 0x20, 0x00, 0x20)) {
            presses += PenButtonDetector.newlyPressed(prev, state, PenTool.STYLUS, false)
            prev = state
        }
        // 3 rising edges of 0x20, 2 falling edges of nothing: 3 presses, 0 releases reported.
        assertEquals(listOf(bitOf(0x20), bitOf(0x20), bitOf(0x20)), presses)
        // And a single press, the common case, is exactly one press.
        assertEquals(listOf(bitOf(0x20)), PenButtonDetector.newlyPressed(0, 0x20, PenTool.STYLUS, false))
    }

    // ---- names ------------------------------------------------------------------------------

    @Test
    fun displayNamesAreForPeopleAndNeverEmpty() {
        assertEquals("Pen button", bitOf(0x20).displayName())
        assertEquals("Second pen button", bitOf(0x40).displayName())
        assertEquals("Right-click button", bitOf(0x02).displayName())
        assertEquals("Middle button", bitOf(0x04).displayName())
        assertEquals("Back button", bitOf(0x08).displayName())
        assertEquals("Forward button", bitOf(0x10).displayName())
        assertEquals("Eraser end", PenButton.ERASER_END.displayName())
        assertEquals("Key 131", PenButton.key(131).displayName())
        // 0x100 = 256 = 16^2, so its unpadded hex is exactly "100" — the fallback prints the number
        // it was given, which is also what makes it identify the button uniquely.
        assertEquals("Button 0x100", bitOf(0x100).displayName())
        // A bit from a pen nobody has met, and a keycode from the future: still a name.
        assertEquals("Button 0x8000", bitOf(0x8000).displayName())
        assertEquals("Key 0", PenButton.key(0).displayName())
        assertEquals("Key -3", PenButton.key(-3).displayName())
        // "Never empty" is the actual requirement (Decision 3): a settings row with no text is the
        // silent-drop bug wearing a different hat, so every control must render as SOMETHING.
        for (b in allButtons()) {
            assertTrue(b.displayName().isNotBlank(), "blank displayName for $b")
        }
    }

    // ---- assignment rules -------------------------------------------------------------------

    @Test
    fun refusalsNameTheActionAndChangeNothing() {
        // Decision 4: `hold` takes only HOLD or EITHER actions, `tap` only TAP or EITHER.
        val holdRefusal = PenButtonMap.DEFAULT.withHold(bitOf(0x02), PenAction.EYEDROPPER)
        assertTrue(holdRefusal is PenButtonMap.Assigned.Refused, "expected a refusal, got $holdRefusal")
        // The sentence names the action AND which way that action works, so the person can tell
        // which of the two slots they filled in wrongly. Asserted in full: the spec gives the
        // second of these verbatim, and a refusal is a sentence a screen shows, not a code.
        assertEquals("Eyedropper works when you tap the button, not while you hold it.", holdRefusal.reason)

        val tapRefusal = PenButtonMap.DEFAULT.withTap(bitOf(0x02), PenAction.LASSO)
        assertTrue(tapRefusal is PenButtonMap.Assigned.Refused, "expected a refusal, got $tapRefusal")
        assertEquals("Lasso works while you hold the button, not when you tap it.", tapRefusal.reason)

        // NONE is EITHER, so it is allowed on both sides and clears a binding.
        assertTrue(PenButtonMap.DEFAULT.withHold(bitOf(0x02), PenAction.NONE) is PenButtonMap.Assigned.Ok)
        assertTrue(PenButtonMap.DEFAULT.withTap(bitOf(0x02), PenAction.NONE) is PenButtonMap.Assigned.Ok)
        // A HOLD action is accepted while held.
        assertTrue(PenButtonMap.DEFAULT.withHold(bitOf(0x02), PenAction.ERASE) is PenButtonMap.Assigned.Ok)
        // A TAP action is accepted on tap.
        assertTrue(PenButtonMap.DEFAULT.withTap(bitOf(0x02), PenAction.UNDO) is PenButtonMap.Assigned.Ok)
    }

    @Test
    fun aRefusedAssignmentLeavesTheOriginalMapExactlyAsItWas() {
        // The map is immutable, so this is true by construction — but "by construction" is exactly
        // the kind of claim a test exists to pin, because the next person may add an in-place path.
        val before = PenButtonMap.DEFAULT
        val jsonBefore = before.toJson()
        before.withHold(bitOf(0x02), PenAction.EYEDROPPER)
        before.withTap(bitOf(0x02), PenAction.LASSO)
        assertEquals(jsonBefore, before.toJson())
        assertEquals(PenBinding(), before.bindingOf(bitOf(0x02)))
    }

    @Test
    fun twoButtonsMayShareAnAction() {
        // Unlike finger taps (JB-2.02b), nothing becomes unreachable if two buttons share an
        // action, so there is no uniqueness rule to break here — only the absence of one to pin.
        val step = PenButtonMap.DEFAULT.withHold(bitOf(0x02), PenAction.PAN)
        assertTrue(step is PenButtonMap.Assigned.Ok, "expected Ok, got $step")
        val both = step.assigned().withHold(bitOf(0x08), PenAction.PAN)
        assertTrue(both is PenButtonMap.Assigned.Ok, "expected Ok, got $both")
        assertEquals(PenAction.PAN, both.assigned().bindingOf(bitOf(0x02)).hold)
        assertEquals(PenAction.PAN, both.assigned().bindingOf(bitOf(0x08)).hold)
    }

    @Test
    fun defaultsMatchTheOwnRuling() {
        // Decision 5 / R42: primary = lasso held, eyedropper tapped; secondary = erase held;
        // eraser end = erase held; every other control unassigned.
        assertEquals(
            PenBinding(PenAction.LASSO, PenAction.EYEDROPPER),
            PenButtonMap.DEFAULT.bindingOf(bitOf(0x20)),
        )
        assertEquals(
            PenBinding(PenAction.ERASE, PenAction.NONE),
            PenButtonMap.DEFAULT.bindingOf(bitOf(0x40)),
        )
        assertEquals(
            PenBinding(PenAction.ERASE, PenAction.NONE),
            PenButtonMap.DEFAULT.bindingOf(PenButton.ERASER_END),
        )
        // 0x08 (Back) is detected on plenty of pens but starts unbound, not defaulted.
        assertEquals(PenBinding(), PenButtonMap.DEFAULT.bindingOf(bitOf(0x08)))
        // A slot appears when a button is DETECTED, not when it is listed (Decision 1), so a
        // default map shows nothing at all until something is pressed.
        assertTrue(PenButtonMap.DEFAULT.seen.isEmpty(), "DEFAULT.seen must start empty")
    }

    @Test
    fun seenIsAdditiveAndNeverRemoves() {
        // The pen is put away and the app restarts: the slots stay, or a person's assignment would
        // silently vanish from the screen after a reboot.
        val once = PenButtonMap.DEFAULT.withSeen(listOf(bitOf(0x08), bitOf(0x20)))
        assertEquals(setOf(bitOf(0x08), bitOf(0x20)), once.seen)
        val again = once.withSeen(listOf(bitOf(0x08)))
        assertEquals(setOf(bitOf(0x08), bitOf(0x20)), again.seen)
        assertEquals(once.seen, again.withSeen(emptyList()).seen)
        assertTrue(PenButtonMap.DEFAULT.seen.isEmpty(), "withSeen must not write through")
    }

    // ---- what is in force right now ----------------------------------------------------------

    @Test
    fun theLowestHeldBitDecides() {
        // Decision 6: the lowest set non-tip bit with a hold action other than NONE decides.
        assertEquals(PenAction.LASSO, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x20))
        // 0x60 = 0b0110_0000: 0x20 (bit 5) is lower than 0x40 (bit 6), so LASSO wins.
        assertEquals(PenAction.LASSO, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x60))
        assertEquals(PenAction.ERASE, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x40))
        // Tip only: no button is held, so nothing is in force.
        assertEquals(PenAction.NONE, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x01))
        // A bit with no hold binding does NOT decide; the search carries on upwards.
        // 0x24 = 0b0010_0100 = unbound 0x04 + the S Pen button at 0x20 -> LASSO.
        assertEquals(PenAction.LASSO, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x24))
        assertEquals(PenAction.NONE, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x00))
        // 0x21 = tip + button: the tip is skipped, so the button still decides.
        assertEquals(PenAction.LASSO, PenButtonMap.DEFAULT.holdActionFor(PenTool.STYLUS, 0x21))
    }

    @Test
    fun lowestBitWinsEvenWhenItIsNotTheDefaultOne() {
        // The ordering is by BIT, not by "which binding is older", so it survives rebinding.
        // 0x22 = 0b0010_0010 = right-click (bit 1, 0x02) + S Pen button (bit 5, 0x20).
        val map = PenButtonMap.DEFAULT
            .withHold(bitOf(0x02), PenAction.PAN).assigned()
            .withTap(bitOf(0x02), PenAction.UNDO).assigned()
        // 0x02 is bit 1 and is bound to PAN, so it decides ahead of the LASSO default on 0x20.
        assertEquals(PenAction.PAN, map.holdActionFor(PenTool.STYLUS, 0x22))
        // With only 0x20 down, the lower bit is not present and LASSO stands.
        assertEquals(PenAction.LASSO, map.holdActionFor(PenTool.STYLUS, 0x20))
        // The tap binding is a separate slot and never answers a held question.
        assertEquals(PenAction.UNDO, map.tapActionFor(bitOf(0x02)))
        assertEquals(PenAction.EYEDROPPER, PenButtonMap.DEFAULT.tapActionFor(bitOf(0x20)))
        assertEquals(PenAction.NONE, PenButtonMap.DEFAULT.tapActionFor(bitOf(0x40)))
    }

    @Test
    fun theEraserToolUsesTheEraserEndsOwnBindingAndIgnoresTheBits() {
        // Decision 6: an ERASER tool uses the ERASER_END binding whatever the state bits say, so
        // that is why the default is ERASE with no button pressed at all.
        assertEquals(PenAction.ERASE, PenButtonMap.DEFAULT.holdActionFor(PenTool.ERASER, 0))
        // Stray bits that would otherwise resolve to LASSO are irrelevant to the eraser end.
        assertEquals(PenAction.ERASE, PenButtonMap.DEFAULT.holdActionFor(PenTool.ERASER, 0x20))
        val rebound = PenButtonMap.DEFAULT.withHold(PenButton.ERASER_END, PenAction.PAN).assigned()
        assertEquals(PenAction.PAN, rebound.holdActionFor(PenTool.ERASER, 0))
        // And rebinding the eraser end does not leak into the tip end of the same pen.
        assertEquals(PenAction.LASSO, rebound.holdActionFor(PenTool.STYLUS, 0x20))
    }

    @Test
    fun unassigningTheEraserEndMeansNothingHappens() {
        // PINS A READING OF DECISION 6 rather than leaving it ambiguous. Decision 6 says the eraser
        // tool "uses the ERASER_END binding's hold (default ERASE)" — the default is in DEFAULT's
        // binding, not a hidden floor in the lookup. So a person who deliberately binds NONE there
        // gets NONE, exactly as they would on any other button. If the Lead meant an unconditional
        // ERASE, this is the one line to change.
        val unbound = PenButtonMap.DEFAULT.withHold(PenButton.ERASER_END, PenAction.NONE).assigned()
        assertEquals(PenAction.NONE, unbound.holdActionFor(PenTool.ERASER, 0))
        // Every other bit still behaves; only this one slot is affected.
        assertEquals(PenAction.LASSO, unbound.holdActionFor(PenTool.STYLUS, 0x20))
    }

    @Test
    fun theTipCannotActEvenIfSomebodyStoresABindingForIt() {
        // Decision 2 says 0x01 is NEVER a button, and the guarantee is enforced where it can be
        // enforced without inventing a refusal the assignment rules do not describe: the lookup
        // starts at bit 1, so a stored binding on 0x01 is simply never read. Decision 4's rules are
        // about the ACTION and every refusal sentence names an action, so there is no wording for
        // refusing a bit — which is why the guarantee lives here rather than in `withHold`.
        val stored = PenButtonMap.DEFAULT.withHold(bitOf(0x01), PenAction.LASSO)
        assertTrue(stored is PenButtonMap.Assigned.Ok, "storing is not refused; acting on it is impossible")
        // Tip alone: the stored LASSO is unreachable.
        assertEquals(PenAction.NONE, stored.assigned().holdActionFor(PenTool.STYLUS, 0x01))
        // Tip plus the real S Pen button: LASSO — from 0x20's default, never from the 0x01 binding.
        // The two would be indistinguishable here, so the assertion that matters is the one above;
        // this one pins that the tip being set does not suppress the button beside it.
        assertEquals(PenAction.LASSO, stored.assigned().holdActionFor(PenTool.STYLUS, 0x21))
    }

    // ---- persistence ------------------------------------------------------------------------

    @Test
    fun aKnownSettingRoundTripsUnchanged() {
        val bound = PenButtonMap.DEFAULT
            .withSeen(listOf(bitOf(0x08), PenButton.ERASER_END))
            .withHold(bitOf(0x08), PenAction.PAN)
            .assigned()
            .withTap(bitOf(0x08), PenAction.UNDO)
            .assigned()

        val round = PenButtonMap.fromJson(bound.toJson())

        // `PenButtonMap` is not a data class and its constructor is private, so "equal" is compared
        // on the two public collections a screen can see — plus the strongest claim of all.
        assertEquals(bound.bindings, round.bindings)
        assertEquals(bound.seen, round.seen)
        assertEquals(bound.toJson(), round.toJson(), "writing a file twice changed it")
        // The slot survived as a bound slot, not merely as an entry: it still answers with PAN.
        assertEquals(PenBinding(PenAction.PAN, PenAction.UNDO), round.bindingOf(bitOf(0x08)))
    }

    @Test
    fun aNameThisBuildHasNeverHeardOfSurvivesTheRoundTrip() {
        // THE property R42 turns on: a newer build's setting has to survive an older build, so an
        // unknown action name is KEPT, not quietly replaced by a default. `WARP_DRIVE` is the
        // spec's own example of an action name this file has no constant for.
        assertTrue(
            PenAction.entries.none { it.name == "WARP_DRIVE" },
            "the test is only meaningful if the name really is from the future",
        )
        val future = """
            {
              "format": "joybrush.penButtons",
              "version": 1,
              "seen": [ { "kind": "STATE_BIT", "code": 8 } ],
              "bindings": [ { "kind": "STATE_BIT", "code": 8, "hold": "WARP_DRIVE", "tap": "NONE" } ],
              "unknown": []
            }
        """.trimIndent()

        val map = PenButtonMap.fromJson(future)

        // It reads as NONE for that slot...
        assertEquals(PenBinding(), map.bindingOf(bitOf(0x08)))
        // ...but the button is still SEEN, so the slot still appears and the person can go and fix
        // it. A dropped entry would have taken the slot with it.
        assertEquals(setOf(bitOf(0x08)), map.seen)

        // ...and the name itself is still there after a re-save. This is the load-bearing line:
        // the raw text went in and the raw text came back.
        val again = map.toJson()
        assertTrue(again.contains("WARP_DRIVE"), "the unknown action name was lost: $again")

        // Written exactly ONCE. The entry is either understood or kept verbatim, never both, so a
        // re-save cannot grow a second entry claiming this button is set to NONE.
        assertEquals(1, countOccurrences(again, "WARP_DRIVE"), "the unknown entry was duplicated: $again")

        // And a second round trip still has it, so loading and saving repeatedly is stable.
        val third = PenButtonMap.fromJson(again).toJson()
        assertTrue(third.contains("WARP_DRIVE"), "the name did not survive a second round trip: $third")
        assertEquals(again, third)
    }

    @Test
    fun aButtonKindThisBuildHasNeverHeardOfSurvivesTheRoundTrip() {
        // The other half of R42's keep rule: a control this build cannot even NAME. `BARREL_BUTTON`
        // is the obvious future kind — a Wacom Art Pen has barrel buttons, which is hardware that
        // exists today and has no name yet.
        assertTrue(
            PenButton.Kind.entries.none { it.name == "BARREL_BUTTON" },
            "the test is only meaningful if the kind really is from the future",
        )
        val future = """
            {
              "format": "joybrush.penButtons",
              "version": 1,
              "seen": [ { "kind": "STATE_BIT", "code": 8 }, { "kind": "BARREL_BUTTON", "code": 5 } ],
              "bindings": [ { "kind": "BARREL_BUTTON", "code": 5, "hold": "PAN", "tap": "NONE" } ],
              "unknown": []
            }
        """.trimIndent()

        val map = PenButtonMap.fromJson(future)

        // The half of that file we DO understand is still understood.
        assertEquals(setOf(bitOf(0x08)), map.seen)
        assertEquals(PenBinding(), map.bindingOf(bitOf(0x08)))
        // The half we do not is kept whole, both entries of it.
        val again = map.toJson()
        assertEquals(2, countOccurrences(again, "BARREL_BUTTON"), "an unknown entry was lost: $again")
        assertTrue(again.contains("\"code\": 5"), again)
        assertEquals(again, PenButtonMap.fromJson(again).toJson(), "a second round trip changed the file")
    }

    @Test
    fun aPartlyUnknownEntryIsKeptWholeRatherThanHalfRead() {
        // One unknown name in an otherwise readable entry. The entry is kept VERBATIM rather than
        // normalised: writing the known half back as a separate entry would put two records for one
        // button in the file, and the second would claim the unknown slot is NONE — a claim about
        // somebody else's file that we cannot make.
        val future = """
            {
              "format": "joybrush.penButtons",
              "version": 1,
              "seen": [],
              "bindings": [ { "kind": "STATE_BIT", "code": 8, "hold": "PAN", "tap": "HYPER_SHAKE" } ],
              "unknown": []
            }
        """.trimIndent()

        val map = PenButtonMap.fromJson(future)
        assertEquals(emptyMap(), map.bindings)
        val again = map.toJson()
        assertTrue(again.contains("HYPER_SHAKE"), again)
        assertTrue(again.contains("PAN"), again)
        assertEquals(1, countOccurrences(again, "HYPER_SHAKE"), again)
        // Same rule for an entry that is not even the right SHAPE: kept, not dropped.
        val malformed = PenButtonMap.fromJson(
            """{ "format": "joybrush.penButtons", "version": 1, "seen": [ "nonsense" ], "bindings": [] }""",
        )
        assertTrue(malformed.toJson().contains("nonsense"), "a malformed entry was dropped: ${malformed.toJson()}")
    }

    @Test
    fun rubbishReadsAsTheDefaultsAndNeverThrows() {
        // fromJson never throws, whatever it is handed.
        val rubbish = listOf(
            "{{",                    // the spec's example: truncated JSON
            "",                      // empty
            "[]",                    // valid JSON, wrong shape
            "\"hello\"",             // valid JSON, wrong shape
            "null",
            "{\"version\": \"one\"}",          // version is not an integer
            "{}",                              // no version at all
            "{\"format\": \"joybrush.document\", \"version\": 1}", // somebody else's file
        )
        for (text in rubbish) {
            val map = PenButtonMap.fromJson(text)
            assertEquals(PenButtonMap.DEFAULT.bindings, map.bindings, "for $text")
            assertEquals(PenButtonMap.DEFAULT.seen, map.seen, "for $text")
            assertEquals(PenButtonMap.DEFAULT.toJson(), map.toJson(), "for $text")
        }
    }

    @Test
    fun aFileFromTheFutureIsReadRatherThanRefused() {
        // Downgrading must not be a one-way door: version 99 is read, the parts this build
        // understands are understood, and the rest is kept.
        val fromTheFuture = """
            {
              "format": "joybrush.penButtons",
              "version": 99,
              "seen": [ { "kind": "STATE_BIT", "code": 8 } ],
              "bindings": [ { "kind": "STATE_BIT", "code": 8, "hold": "PAN", "tap": "NONE" } ],
              "unknown": []
            }
        """.trimIndent()
        val map = PenButtonMap.fromJson(fromTheFuture)
        assertEquals(PenBinding(PenAction.PAN, PenAction.NONE), map.bindingOf(bitOf(0x08)))
        assertEquals(setOf(bitOf(0x08)), map.seen)
    }

    @Test
    fun writingIsDeterministic() {
        // Two maps with the same content written in a different insertion order must produce the
        // same bytes, or a setting "changes by itself" in a diff and there is no way to trust it.
        val a = PenButtonMap.DEFAULT.withSeen(listOf(bitOf(0x10), bitOf(0x02)))
        val b = PenButtonMap.DEFAULT.withSeen(listOf(bitOf(0x02), bitOf(0x10)))
        assertEquals(a.toJson(), b.toJson())
        assertTrue(a.toJson().contains("joybrush.penButtons"), "the format tag is not in the file")
    }

    // ---- append-only guards -------------------------------------------------------------------

    @Test
    fun actionAndKindNamesAreFrozen() {
        // These names ARE the saved format (Decision 7). A rename or a reorder silently unbinds
        // every person who ever used the feature, and there is no error to notice — the same reason
        // `ToolOrdinalFreezeTest` freezes `Tool`.
        assertEquals(
            listOf(
                "NONE", "LASSO", "ERASE", "PAN", "EYEDROPPER",
                "UNDO", "REDO", "TOGGLE_CHROME", "BRUSH_PREV", "BRUSH_NEXT",
            ),
            PenAction.entries.map { it.name },
        )
        assertEquals(
            listOf("STATE_BIT", "KEY", "ERASER_END"),
            PenButton.Kind.entries.map { it.name },
        )
    }

    @Test
    fun theAndroidButtonBitsAreCopiedExactly() {
        // These are MotionEvent.BUTTON_* values written out as literals because core has no Android
        // types. A typo would not fail anywhere — a pen would just have an unassignable button — so
        // the numbers themselves are pinned here rather than left to review.
        assertEquals(0x01, PenButton.BIT_PRIMARY)
        assertEquals(0x02, PenButton.BIT_SECONDARY)
        assertEquals(0x04, PenButton.BIT_TERTIARY)
        assertEquals(0x08, PenButton.BIT_BACK)
        assertEquals(0x10, PenButton.BIT_FORWARD)
        assertEquals(0x20, PenButton.BIT_STYLUS_PRIMARY)
        assertEquals(0x40, PenButton.BIT_STYLUS_SECONDARY)
    }

    @Test
    fun aButtonIsAValueNotAName() {
        // The contract's reason for making PenButton a data class: it is a MAP KEY in a saved file,
        // so two controls that mean the same thing must be the same key, and two that do not must
        // not collide. 0x01 as a STATE_BIT and 0x01 as a KEY are different controls.
        assertEquals(bitOf(0x20), PenButton.bit(0x20))
        assertEquals(PenButton.key(131), PenButton.key(131))
        assertTrue(PenButton.bit(0x01) != PenButton.key(1))
        assertTrue(PenButton.ERASER_END != PenButton.bit(0))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun bitOf(mask: Int): PenButton = PenButton.bit(mask)

    /** The map an assignment produced. Fails loudly if it was refused, which is the point. */
    private fun PenButtonMap.Assigned.assigned(): PenButtonMap =
        (this as? PenButtonMap.Assigned.Ok)?.map
            ?: error("expected the assignment to be accepted, got $this")

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** Every kind of control, for the "never a blank name" sweep. */
    private fun allButtons(): List<PenButton> = listOf(
        PenButton.ERASER_END,
        PenButton.key(0),
        PenButton.key(131),
        PenButton.key(-3),
        PenButton.bit(0x01),
        PenButton.bit(0x02),
        PenButton.bit(0x04),
        PenButton.bit(0x08),
        PenButton.bit(0x10),
        PenButton.bit(0x20),
        PenButton.bit(0x40),
        PenButton.bit(0x100),
        PenButton.bit(0x8000),
        PenButton.bit(0),
    )
}
