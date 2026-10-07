package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteGrid
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two shape claims of JB-4.01 that can only be made by LOOKING at the classes, and they are in
 * `jvmTest` for the reason the other seven such rows are: `::class.java` does not exist in a
 * multiplatform `commonTest` source set, and a test there that reaches for it does not build (Lead
 * ruling, slip 3 — it bit JB-1.07, JB-2.04, JB-2.12, JB-2.16, JB-2.17, JB-2.23 and JB-3.04).
 *
 * Both are claims about the FORMAT rather than about one implementation, which is the only reason
 * they are worth two tests of their own: R36 ruled that "used" is derived, and this is what that
 * ruling looks like as a property of a document nobody here can change.
 */
class SpriteBoardHoldsNoGridArithmeticTest {

    // ── J1 ─────────────────────────────────────────────────────────────────────

    /**
     * Every `const` this class and its companion declare.
     *
     * A `const val` compiles to a static field whose type is a primitive or a String, and that type
     * is what keeps the two fields which are NOT constants out of this list: the `Companion` instance
     * on [SpriteBoard], and anything a data class would add. Synthetic fields (`$VALUES`, a lambda's
     * bridge) are not constants either.
     */
    private fun constants(): List<Field> =
        (SpriteBoard::class.java.declaredFields + SpriteBoard.Companion::class.java.declaredFields)
            .filter { !it.isSynthetic }
            .filter { Modifier.isStatic(it.modifiers) }
            .filter { it.type.isPrimitive || it.type == String::class.java }
            .sortedBy { it.name }

    @Test
    fun thisClassHoldsNoGridArithmeticOfItsOwn() {
        val constants = constants()

        // Exactly one number, and the count is the whole claim: R19 made `SpriteGridMath.MAX_CELLS`
        // and `MAX_CELL_PX` PUBLIC precisely so a second copy could not appear, and a second copy
        // that can drift is the one failure nothing else in this row can catch.
        assertEquals(1, constants.size, "the class publishes exactly one number, and this is it: ${constants.map { it.name }}")
        val only = constants.single()
        assertEquals("EDGE_GRAB_PX_DP", only.name)
        assertEquals("float", only.type.name, "a Float, because the grab is a touch distance")
        assertEquals(22f, only.get(null), "22 dp, from Decision 5 and the spec's Q1")

        // No Int constant AT ALL is the teeth of the count above: `MAX_CELLS`, `MAX_CELL_PX` and the
        // 2..8 sub-grid range are all Ints in `SpriteGridMath`, and restating any of them here
        // lands as a second constant. (`MIN_SUB_GRID_DIV` / `MAX_SUB_GRID_DIV` being `private` is
        // exactly why such a restatement would be a COPY rather than a shared rule.)
        assertTrue(
            constants.none { it.type == Integer.TYPE },
            "an Int constant in this class is a restated grid rule",
        )

        // A copy of a rule does not have to be a constant, though: `private fun subGridDiv(div: Int)
        // = div.coerceIn(2, 8)` is two literals and no field at all, and the count above would stay
        // green. What every hidden copy has in common is that it is HIDDEN, and there is nothing for
        // one to hide behind here: every function in this file is either public API or a call into
        // `SpriteGridMath`, so a private method of its own would have no reason to exist. Synthetic
        // members are excluded because a lambda inlined by `require` can leave a bridge behind.
        val hidden = SpriteBoard::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .filter { Modifier.isPrivate(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .map { it.name }
        assertEquals(emptyList(), hidden, "a private method in this class is arithmetic nobody can see")
    }

    // ── J2 ─────────────────────────────────────────────────────────────────────

    /**
     * Is this field the class-object reference the Kotlin compiler generates for a `companion
     * object`?
     *
     * A `@Serializable` class gets one whether it declares a companion or not — for [SpriteGrid] it
     * is the generated `serializer()` holder — and it is not data: it is a handle on the class, and
     * it is always STATIC, because a class object belongs to the class rather than to an instance.
     * That is the whole predicate: a class-typed static. ([constants] above drops the same kind of
     * field for the same reason, which is why J1 stayed green when this census did not.)
     *
     * THE TRAP, and the reason this is not written as "is not an `int`": a census that ignored
     * every non-`int` field would be BLIND to a `Set<Int>` or a `List<Int>` added to
     * [SpriteGrid] to cache a computed "used" set, which is the one field this test exists to catch.
     * Nothing is dropped here for being a collection, so a collection field is still in `data` and
     * still reddens the census above and the assertion below it.
     */
    private fun Field.isClassObject(): Boolean =
        Modifier.isStatic(modifiers) && !type.isPrimitive && !type.isArray

    /**
     * Is this field one a stored "used" could hide in — a `Collection` **or** a `Map`?
     *
     * The `Map` half is not decoration and the reason is a JDK shape, not a preference: `Map` and
     * `Collection` are SIBLING interfaces, so `Collection.isAssignableFrom(Map)` is **false** and a
     * census filtered on `Collection` alone drops every `Map` field without an error anywhere.
     * `Layer.frameCel: Map<String, String>` (DocModel.kt:149) has been one since JB-0.02, and it is
     * load-bearing, so a census that cannot see it reports a perfectly good model as one name short.
     *
     * And it matters for the claim rather than merely for the count: a stored "used" set could just
     * as reasonably be a `Map<String, Boolean>` as a `Set<Int>`, so filtering on `Collection` alone
     * leaves a hole in exactly the place this test exists to close. `usedIsComputedAndNotStored`
     * asserts the `Map`-typed fields on their own as well, so narrowing this predicate again goes
     * red on that line first rather than quietly shortening a list.
     */
    private fun Field.isCensusField(): Boolean =
        Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type)

    /**
     * The data classes of `document.json`. The enums are left out on purpose: they hold no data, and
     * their `$VALUES` / `$ENTRIES` fields are compiler bookkeeping rather than format.
     */
    private val dataTypes = listOf(
        RectPx::class.java,
        Paper::class.java,
        Frame::class.java,
        SpriteGrid::class.java,
        Board::class.java,
        Cel::class.java,
        Layer::class.java,
        JbDocument::class.java,
    )

    @Test
    fun usedIsComputedAndNotStored() {
        // The DATA of a grid is four Ints, so there is nowhere in one for a used flag to live. The
        // one field that is not data is the class object, and it is dropped by `isClassObject` and
        // by nothing else — see that predicate for the trap it has to walk past.
        val data = SpriteGrid::class.java.declaredFields
            .filter { !it.isSynthetic }
            .filterNot { it.isClassObject() }
        assertEquals(
            listOf("cellH", "cellW", "cols", "rows"),
            data.map { it.name }.sorted(),
            "SpriteGrid's data is four Ints; the only other field is its class object",
        )
        assertTrue(data.all { it.type == Integer.TYPE }, "and all four are `int`")
        // Asked again, locally, on the very set the name census just looked at: a `Set<Int>` or a
        // `List<Int>` added here to cache a computed "used" set would be an ordinary instance field
        // of a collection type, and it reddens THIS line as well as the two at the bottom. A census
        // that only ever looked at `int` fields would call that grid clean.
        assertTrue(
            data.none { Collection::class.java.isAssignableFrom(it.type) },
            "a collection field on SpriteGrid is a stored 'used', not a derivation of it",
        )

        // R36 Q1 as a property of the FORMAT: a flag that could disagree with the picture would be
        // some field somewhere, and no such field exists. This wider census is deliberately NOT
        // filtered by `isClassObject`: a class object is neither a Set nor a Collection, so it
        // cannot affect either assertion, and leaving it unfiltered keeps this one looking at every
        // field the format actually has.
        val fields = dataTypes.flatMap { type -> type.declaredFields.map { type.simpleName to it } }
        assertTrue(
            fields.none { (_, field) -> Set::class.java.isAssignableFrom(field.type) },
            "a Set anywhere in DocModel is a second truth about the picture",
        )

        // The spec's own wording for this — "no field of any DocModel type is a Set or a List" — is
        // NOT what is asserted, and cannot be: `JbDocument.boards`, `JbDocument.layers`,
        // `Board.frames`, `Cel.tiles`, `Layer.cels` and `Layer.frameCel` have been collections since
        // JB-0.02 and are load-bearing. What is asserted is the checkable form of the claim: the
        // collection-typed fields are EXACTLY those six, so a field added to hold "which cells are
        // used" — a List of indices, a Set of them, a Map of them, a Boolean per cell — reddens this
        // test even though it would be a perfectly ordinary addition to the file.
        //
        // **THE MAP HALF IS ASSERTED SEPARATELY FIRST**, because it is the half that fails silently.
        // `Map` is a sibling of `Collection` in the JDK, not a subtype, so a filter written on
        // `Collection` alone loses `Layer.frameCel` with nothing to show for it — the census simply
        // returns five names where six are expected and the failure reads like a stale list rather
        // than a blind spot. See [isCensusField].
        val mapTyped = fields
            .filter { (_, field) -> Map::class.java.isAssignableFrom(field.type) }
            .map { (type, field) -> "$type.${field.name}" }
            .sorted()
        assertEquals(
            listOf("Layer.frameCel"),
            mapTyped,
            "the only Map-typed field in the format, and the census below has to be able to SEE it",
        )

        val collections = fields
            .filter { (_, field) -> field.isCensusField() }
            .map { (type, field) -> "$type.${field.name}" }
            .sorted()
        assertEquals(
            listOf(
                "Board.frames",
                "Cel.floatTiles", // v8 media layer: the tiles holding float state; never a sprite count
                "Cel.tiles",
                "JbDocument.boards",
                "JbDocument.layers",
                "Layer.cels",
                "Layer.frameCel",
                "Layer.regions", // v6 board-region frame mapping; never the derived sprite `used`
            ),
            collections,
            "a NEW collection or map field is where a stored 'used' would go",
        )
    }
}
