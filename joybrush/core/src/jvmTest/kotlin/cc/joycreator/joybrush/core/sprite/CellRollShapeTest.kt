package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.anim.FrameStepper
import cc.joycreator.joybrush.core.anim.PlayMode
import cc.joycreator.joybrush.core.anim.PlaybackClock
import cc.joycreator.joybrush.core.anim.Step
import cc.joycreator.joybrush.core.brush.joybrushRoot
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.DocJson
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.RectPx
import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two SHAPE guards, J1 and J2, in `jvmTest` because they use Java reflection (`::class.java`),
 * which does not exist in a multiplatform `commonTest` source set — the Lead's slip 3. A `commonTest`
 * that opened a file, or that called `::class.java`, would not build.
 *
 * Neither of these tests asserts anything a human could read off the source. They assert things only
 * a LOADER can see: what the bytecode says `CellRoll` and `CellRollGrammar` are made of, what the
 * document model's signature contains, and what two source files' import lists hold. That is the
 * whole reason they are here rather than in the file they are about.
 *
 * ## THE THINGS TO KNOW BEFORE EDITING EITHER OF THEM
 *
 * 1. **THE MODEL TYPES ARE LOOKED UP BY NAME, NOT IMPORTED.** `docClass("Layer")` rather than
 *    `Layer::class.java`, so that the list of names J1 walks and the list of names J2 forbids are the
 *    same strings, and so that this file — which is *about* the roll not reaching the model — does
 *    not itself import the model. A type renamed in `doc` makes this test say so in words.
 * 2. **THE PROHIBITED LIST IS A DENY LIST, AND THE ALLOW LIST IS A PERMISSION.** The deny list is the
 *    load-bearing claim: no `JbDocument`, no `SpriteBoard`, no `SpriteGrid`, no `Layer`, no `Cel`,
 *    in a field, a parameter, a return type or an import. The allow list is a second, weaker guard
 *    that turns any NEW type red — but it is deliberately a SUPERSET check and not an equality, for
 *    the reason in the note on [ALLOWED] below.
 * 3. **A KOTLIN `val … get() = …` IS A REAL `getSize()` METHOD IN THE BYTECODE.** So the walk is
 *    restricted to PARAMETERS, and the data-class boilerplate is skipped: `component1`,
 *    `component2`, `copy`, `equals`, `hashCode`, `toString` and anything the compiler marked
 *    synthetic (`copy$default` and friends) would otherwise match a search that is not looking for
 *    them. A `commonMain` cannot ask any of this without reflection, which is the point.
 */
class CellRollShapeTest {

    // ── J1. the roll is not in the document ────────────────────────────────────

    /**
     * Decision 15 (R36 Q1), in two halves, because one half is not enough.
     *
     * **The field walk says the model cannot HOLD a roll.** A `CellRoll` in the signature of no type
     * in `cc.joycreator.joybrush.core.doc` means no save, no load and no board can ever grow one.
     *
     * **The encode says the model does not accidentally SAY one.** A document that had a roll built
     * against it, previewed through the real clock for a whole ping-pong cycle, encodes to
     * byte-identical JSON — no `roll` key, no `order` key, no `weights` key. A field walk alone
     * would pass against a `CellRoll` reached only by a global or a side table, and this is the half
     * that would notice.
     */
    @Test
    fun theRollIsNotInTheDocument() {
        // ── half one: the model's own signature ──
        val found = sortedMapOf<String, String>()
        for (simple in MODEL_TYPES) {
            val type = docClass(simple)
            assertTrue(
                type.declaredFields.isNotEmpty() || type.declaredMethods.isNotEmpty(),
                "$simple has nothing to walk, so the walk cannot have looked at it",
            )
            // From the type ITSELF as well as from its members, so the walk also covers a model's own
            // supertypes and interfaces — a class that hid a roll one level up would be caught there.
            collect(type, "$simple (the type itself)", found)
            for (field in type.declaredFields) {
                collect(field.genericType, "$simple.${field.name}", found)
            }
            for (method in type.declaredMethods) {
                if (method.name in BOILERPLATE || method.isSynthetic) continue
                for (p in method.genericParameterTypes) {
                    collect(p, "${simple}.${method.name}(…)", found)
                }
                collect(method.genericReturnType, "${simple}.${method.name}():", found)
            }
        }
        assertFalse(
            "CellRoll" in found,
            "the document model mentions a CellRoll at ${found["CellRoll"]}, which is Decision 15 undone",
        )
        // The walk really walked. Every one of these is a field of a type in the list above, so a
        // walker that had stopped at the first class would come back short.
        for (proved in listOf("Board", "Layer", "Cel", "Frame", "SpriteGrid", "Paper", "RectPx",
            "String", "int", "boolean", "float", "List", "Map")) {
            assertTrue(proved in found, "the walk never reached a $proved, so it proved nothing: $found")
        }
        // And the model is unchanged by the roll: a document that has never seen one.
        assertEquals(MODEL_TYPES.size, loaded.size, "the walk visited every type in the list")

        // ── half two: the encode ──
        var n = 0
        val fresh = DocOps.newDocument("d", "D", 512, 256) { "id${n++}" }
        val canvas = fresh.boards.single()
        val sheet = canvas.copy(
            // A NEW id, not the canvas board's: two boards sharing one id is rule 2, and a fixture
            // that is itself a broken document would make the encode half prove nothing.
            id = "id3",
            name = "Sheet",
            kind = BoardKind.SPRITE,
            grid = SpriteGridMath.byCount(canvas.rect, 4, 2),
        )
        val doc = fresh.copy(boards = listOf(canvas, sheet), activeBoardId = sheet.id)
        assertEquals(emptyList(), DocOps.validate(doc), "the fixture itself must be a valid document")

        val roll = CellRoll(
            entries = (0..5).map { CellRoll.Entry(cell = it, hold = 3) },
            cursor = 5,
        )
        val rollBoard = roll.asBoard(12f, RectPx(0, 0, 256, 256))
        assertFalse(
            doc.boards.any { it.id == rollBoard.id },
            "the board a roll IS must not be findable among a document's boards",
        )

        val before = DocJson.encode(doc)
        val clock = PlaybackClock(rollBoard, mode = PlayMode.PING_PONG)
        val stepper = FrameStepper(clock)
        var shown = 0
        for (ms in 0..2500) {
            val t = ms.toDouble()
            for (step in stepper.step(t, shown, clock.audioPositionMs(t))) {
                if (step is Step.ShowFrame) shown = step.index
            }
            assertEquals(clock.frameIndexAt(t), shown, "the preview is not the clock's own frame at $t")
        }
        assertEquals(0, shown, "a whole ping-pong cycle really was played, and it ends on frame 0")

        val after = DocJson.encode(doc)
        assertEquals(before, after, "a document a roll was played against must encode byte-identically")
        for (word in listOf("roll", "order", "weights")) {
            assertFalse(after.contains(word), "the encoded document must not contain \"$word\":\n$after")
        }
    }

    /**
     * The shape of the two types this row is, walked from the bytecode — the cross-row coupling
     * guard. A roll that reached for the sprite board type, or for the exporter's clip type, would
     * make JB-4.02 depend on a row it does not need, which is how a row blocks on another.
     *
     * **THE TWO THINGS THIS WALK DELIBERATELY DOES NOT FORBID**, said out loud so that nobody
     * tightens it into a false red:
     *
     *  - (a) `asBoard`'s RETURN type **is** `Board` — a transient value this class builds out of its
     *    own entries, never one it was handed. The walk is over PARAMETERS, which is why the draft's
     *    version of this test (it forbade `Board` in a declared method's signature, and `asBoard`
     *    returns one) would have gone red against the spec's own code.
     *  - (b) A Kotlin `val … get() = …` is a real `getSize()` in the bytecode, and `copy`/`component1`
     *    /`equals` and the rest are the data class's own boilerplate. They are skipped by name, and
     *    synthetic methods are skipped outright.
     */
    @Test
    fun theRollHasNoIdeaWhatAGridIs() {
        // ── the fields, and the PARAMETERS of every method, of both types ──
        val drawnFrom = sortedMapOf<String, String>()
        // …and the return types too, but only for the DENY list below: `Board` and `PlayMode` are
        // legitimate return types and are in ALLOWED, while nothing on the deny list is.
        val returned = sortedMapOf<String, String>()
        for (owner in listOf<Class<*>>(CellRoll::class.java, CellRollGrammar::class.java)) {
            for (field in owner.declaredFields) {
                collect(field.genericType, "${owner.simpleName}.${field.name}", drawnFrom)
                collect(field.genericType, "${owner.simpleName}.${field.name}", returned)
            }
            for (method in owner.declaredMethods) {
                if (method.name in BOILERPLATE || method.isSynthetic) continue
                for (p in method.genericParameterTypes) {
                    collect(p, "${owner.simpleName}.${method.name}(…)", drawnFrom)
                }
                collect(method.genericReturnType, "${owner.simpleName}.${method.name}():", returned)
            }
        }

        // THE DENY LIST. A field, a parameter or a return type naming any of these is a red, and the
        // message says where it was seen, because "the roll knows about layers" is not a sentence a
        // reviewer can act on and `Board.frames` is.
        for (banned in PROHIBITED) {
            assertFalse(
                banned in drawnFrom,
                "a $banned is a PARAMETER or a FIELD of the roll: ${drawnFrom[banned]}",
            )
            assertFalse(banned in returned, "a $banned is in the roll's SIGNATURE: ${returned[banned]}")
        }

        // THE ALLOW LIST, as a subset check and NOT as an equality — and the reason is worth writing
        // down, because the spec asked for an equality and the equality is not available: the six
        // types the spec names (`Int`, `Float`, `String`, `Boolean`, `CellRoll`, `CellRoll.Prune`) do
        // not include the ones the spec's OWN pasted contract already uses — `asBoard` takes a
        // `RectPx`, the grammar takes a `CellGesture`, and the data class takes a `List<Entry>`.
        // An equality against the spec's list would go red on the spec's own code, which is the one
        // outcome a shape test must never produce. So: everything found must be PERMITTED, and the
        // permission is written out, so a new type is still a red.
        for ((name, where) in drawnFrom) {
            assertTrue(name in ALLOWED, "the roll's signature draws on a $name, which nothing here allows: $where")
        }
        // And the walk really walked: these are the types the contract's own signatures name, and a
        // walker that had returned nothing would otherwise pass the loop above on an empty set.
        for (proved in listOf("int", "float", "String", "List", "CellRoll", "Entry", "CellGesture", "RectPx")) {
            assertTrue(proved in drawnFrom, "the walk never reached a $proved, so it proved nothing: $drawnFrom")
        }
        assertTrue(returned.containsKey("Board"), "asBoard's return type really is Board, and is allowed")
        assertTrue(returned.containsKey("Prune"), "and the counts come back as CellRoll.Prune")

        // ── AND THE WALK ITSELF, PROVED BOTH WAYS, because a guard that has quietly stopped naming
        //    types would sail through the loop above on anything at all, including nothing ──
        //
        // POSITIVE, a PERMITTED signature asked for by hand. `CellRoll.entries` is the most
        // parameterised signature this row has — `java.util.List<CellRoll$Entry>` — and it is exactly
        // the shape whose rendered form a string-splitter mangles. Named, never printed: the tokens
        // are `List` and `Entry`, plus the `Object` that every project type's superclass is, because
        // the walk follows superclasses of the project's own types and stops at anything else.
        val entriesField = CellRoll::class.java.getDeclaredField("entries")
        val entriesWalked = sortedMapOf<String, String>()
        collect(entriesField.genericType, "CellRoll.entries", entriesWalked)
        assertEquals(setOf("List", "Entry", "Object"), entriesWalked.keys, "a List AND an Entry, both named")
        assertTrue(
            entriesWalked.keys.none { it.contains('<') || it.contains('>') || it.contains('$') },
            "no token may carry a bracket, a generic argument or a binary-name dollar: ${entriesWalked.keys}",
        )
        assertTrue(
            entriesWalked.keys.all { it in ALLOWED },
            "and every one of them is PERMITTED, so the allow loop above accepts a real " +
                "parameterised signature rather than rejecting everything: ${entriesWalked.keys}",
        )
        // And the two lists really are disjoint, so "permitted" and "forbidden" cannot both be true
        // of one name and this file's two guards are not quietly the same test.
        assertTrue(
            ALLOWED.none { it in PROHIBITED },
            "the allow list and the deny list overlap on ${ALLOWED.filter { it in PROHIBITED }}",
        )

        // NEGATIVE, a genuinely forbidden type, and the walk is pointed straight at one. `Board.grid`
        // is a real field of the model whose type is on the deny list, so the deny list is shown to
        // fire on a type this walk can see — proved rather than assumed, and the reason the allow
        // check above is not the only thing standing between this file and a false green.
        val gridWalked = sortedMapOf<String, String>()
        collect(docClass("Board").getDeclaredField("grid").genericType, "Board.grid", gridWalked)
        assertEquals(
            setOf("SpriteGrid", "Object"),
            gridWalked.keys,
            "a nullable field's type is the class itself, plus the superclass the walk follows",
        )
        assertTrue("SpriteGrid" in PROHIBITED, "so the deny list names it, or the guard is a no-op")
        assertFalse("SpriteGrid" in ALLOWED, "and the allow list must NOT, or nothing could ever red")
        assertTrue(
            gridWalked.keys.any { it !in ALLOWED },
            "so this walk really does produce a token the allow loop would reject: $gridWalked",
        )

        // ── the gestures: eight, and no long-press ──
        // `declaredClasses` is in no particular order, hence the sort. The compiler's own
        // `…$WhenMappings` table is filtered by name rather than counted, because whether it is a
        // nested class or a sibling of the file is a detail of the compiler rather than of this row.
        val cases = CellGesture::class.java.declaredClasses
            .map { it.simpleName }
            .filterNot { it.contains("WhenMappings") }
            .sorted()
        assertEquals(
            listOf("BadgeHeld", "BadgeTapped", "Cancel", "Clear", "Focused", "HoldBumped", "PlayAll", "Tapped"),
            cases,
            "eight gestures, and no ninth: a long-press opens the view half's menu and must not also " +
                "fire a tap (Decision 16)",
        )
        assertEquals(8, cases.size, "2n - 2 is for a roll; a gesture count is just a count")
        assertTrue(cases.none { it.contains("LongPressed") }, "a CellLongPressed case would be a double-fire: $cases")

        // ── the import lists ──
        // ONE source file holds both `CellRoll` and `CellRollGrammar`, so there is one import list
        // to read rather than two — and reading it is the only way to catch a `Clip` or a
        // `SpritePacker` that has been imported and not used yet, which is the drift this catches.
        val source = cellRollSource()
        val imports = importSimpleNamesOf(source)
        assertTrue(imports.isNotEmpty(), "$source has no import lines, so the walk read nothing")
        for (banned in PROHIBITED + listOf("Clip", "SpritePacker")) {
            assertFalse(
                banned in imports,
                "$source imports a $banned, which is a cross-row coupling this row does not have (Decision 13)",
            )
        }
        // The names the file legitimately needs, so the import walk is not vacuous: a `RectPx` and a
        // `Board` are how the roll becomes playable, and neither is on the deny list.
        assertTrue("Board" in imports, "the roll builds a Board, so it must import one: $imports")
        assertTrue("RectPx" in imports, "and a RectPx to build it in: $imports")
    }

    // ── the small parts ────────────────────────────────────────────────────────

    /**
     * The types of the document model, by simple name, in the order the spec lists them. Looked up
     * by name rather than imported, so that the list J1 walks and the list J2 forbids are the same
     * strings — and so that this file, which is *about* the roll not reaching the model, does not
     * itself reach for the model. A type renamed in `doc` makes [docClass] say so in words.
     */
    private val MODEL_TYPES = listOf(
        "JbDocument", "Board", "Layer", "Cel", "Frame", "SpriteGrid", "Paper", "RectPx",
    )

    /** The [MODEL_TYPES] [docClass] has actually loaded, so a test can say the walk VISITED them. */
    private val loaded = sortedSetOf<String>()

    private fun docClass(simple: String): Class<*> {
        val name = "cc.joycreator.joybrush.core.doc.$simple"
        val found: Class<*>? = Class.forName(name)
        loaded.add(simple)
        return found ?: fail("there is no $name on the classpath, so this test cannot walk it")
    }

    /**
     * The types this row's own signature is allowed to draw on, and NOT ONE MORE.
     *
     * `Object`, `Enum` and `Companion` are here because the walk reaches them from the row's own
     * types: every project type's superclass is `Object`, every enum's is `Enum<It>`, and the
     * serialization plugin puts a `Companion` on a `@Serializable` class. `List` is here because
     * `CellRoll.entries` is a `List<Entry>` and J1 exists precisely because the document's
     * collections are `List`s. Everything else is a type the spec's pasted contract already names,
     * plus the three it leaves implicit (`Entry`, `CellGesture`, `RectPx`) — see the note on the
     * subset check in the test above.
     *
     * Note what is NOT here and could not be added to make a test pass: `JbDocument`, `SpriteBoard`,
     * `SpriteGrid`, `Layer`, `Cel`, `Clip` and `SpritePacker`. Those are [PROHIBITED], and the two
     * lists are asserted disjoint rather than merely written side by side.
     */
    private val ALLOWED = setOf(
        "int", "float", "long", "double", "boolean", "byte", "short", "char",
        "String", "List", "Map", "Set", "Pair", "Object", "Enum", "Void", "Unit", "Companion",
        "CellRoll", "Entry", "Prune", "CellRollGrammar", "CellGesture", "RectPx", "Board", "Frame", "PlayMode",
    )

    /**
     * The five names a roll must not reach, in a field, a parameter, a return type or an import
     * (J2). `SpriteBoard` is named as a STRING and not as a type because it belongs to a row that
     * has not landed: a coupling to it is a coupling, whether or not the class exists yet.
     */
    private val PROHIBITED = listOf("JbDocument", "SpriteBoard", "SpriteGrid", "Layer", "Cel")

    /**
     * The data-class boilerplate, which the spec says to skip: `component1`, `component2`, `copy`,
     * `equals`, `hashCode`, `toString`. A generated `copy$default` is caught by
     * [java.lang.reflect.Method.isSynthetic] instead, because a name list cannot enumerate the
     * compiler's own bridges.
     */
    private val BOILERPLATE = setOf(
        "component1", "component2", "copy", "equals", "hashCode", "toString",
    )

    /**
     * Every type inside [type], the GENERIC ARGUMENTS INCLUDED: `List<Frame>` names two types, and
     * the erasure alone would hide the one that matters. The first place each name was seen is
     * recorded, so a failure says `Board.frames` rather than "somewhere".
     *
     * ## WHY THIS DOES NOT SPLIT `Type.getTypeName()`
     *
     * `getTypeName()` on a `ParameterizedType` returns the whole **rendered signature** —
     * `"java.util.List<cc.joycreator.joybrush.core.sprite.CellRoll$Entry>"` — and splitting that on
     * `.` and `$` produces the token `Entry>`, loses `List` entirely, and yields `Board>` for a
     * `List<Board>`. A previous version of this file did exactly that, and the suite caught it twice:
     * once as a false red on a permitted shape, and once as a hole — the whole point of J1 is that
     * the document's collections are `List`s, and the string-splitter could never name one.
     *
     * So each `Type` is asked **its own question**: a [Class] for its `simpleName`, a
     * [ParameterizedType] for its `rawType` and then each actual argument, a [TypeVariable] for its
     * bounds. Nothing is ever inferred from a printed signature, so a token can only be a type.
     *
     * ## WHY THE SUPERTYPE WALK STOPS AT THE FIRST NON-PROJECT TYPE
     *
     * Recursion into `getGenericSuperclass()` and `getGenericInterfaces()` continues **only while
     * the type is one of the project's own**. That is not a shortcut around the claim: a class that
     * hides a forbidden type one level up (`class Board : Rollish<CellRoll>`) IS followed, because
     * `Board` is a project type. What it buys is that the JDK's own hierarchy stays out — walking
     * `java.lang.String`'s interfaces would drag `Serializable`, `Comparable`, `CharSequence`,
     * `Constable` and `ConstantDesc` into a set whose whole job is to describe the roll's own
     * signature, and would make the allow list a list of JDK trivia.
     */
    private fun collect(
        type: Type?,
        where: String,
        into: MutableMap<String, String>,
        seen: MutableSet<Type> = HashSet(),
    ) {
        if (type == null) return
        // Each distinct `Type` is expanded at most once, and not each distinct name. This walk is
        // otherwise unbounded recursion, and it terminates today only because no model type happens
        // to have a generic parameter: `Enum<E extends Enum<E>>` would put a `TypeVariable` whose
        // bounds hand back the `ParameterizedType` whose arguments hand back the variable, forever.
        // Guarding by name would not help - the cycle is through two *types*, and it is the
        // expansion that costs, not the recording. `into` already keeps the first place a name was
        // seen, so an unvisited duplicate changes nothing a reader can observe.
        if (!seen.add(type)) return
        when (type) {
            is Class<*> -> {
                // `getSimpleName()`, never a substring of `getName()`: `CellRoll$Entry`'s simple
                // name is `Entry`, and `getName()` would hand back the binary name.
                record(type.simpleName, where, into)
                // Bound to a local because a Java getter is not something Kotlin can smart-cast.
                val component = type.componentType
                if (component != null) record(component.simpleName, where, into)
                if (!isProjectType(type)) return
                collect(type.genericSuperclass, "superclass of $where", into, seen)
                for (supertype in type.genericInterfaces) collect(supertype, "supertype of $where", into, seen)
            }

            is ParameterizedType -> {
                // The raw type FIRST, then the arguments: that is what puts `List` in the set for a
                // `List<Board>` and `Board` in it for the argument, and neither is derived from text.
                collect(type.rawType, where, into, seen)
                for (argument in type.actualTypeArguments) collect(argument, where, into, seen)
            }

            is GenericArrayType -> collect(type.genericComponentType, where, into, seen)
            is TypeVariable<*> -> for (bound in type.bounds) collect(bound, where, into, seen)
            is WildcardType -> for (bound in type.upperBounds) collect(bound, where, into, seen)

            // A `Type` implementation this walk has no vocabulary for. It is named rather than
            // waved through, because a guard that can fail open is not a guard. This is the only
            // place a name is derived from a printed signature, and it is a FALLBACK: every type the
            // document model and this row actually use is a [Class] or a [ParameterizedType], and the
            // two branches above are what found `List` after the string-splitter lost it.
            else -> record(type.typeName.substringAfterLast('.'), where, into)
        }
    }

    /** The first place [name] was seen, so a failure names a field rather than a class. */
    private fun record(name: String, where: String, into: MutableMap<String, String>) {
        if (!into.containsKey(name)) into[name] = where
    }

    /** Whether [type] is one of ours, which is the rule the supertype walk stops on. */
    private fun isProjectType(type: Class<*>): Boolean =
        type.name?.startsWith(PROJECT_PACKAGE) == true

    /** Kept as a constant so the rule above is one name rather than a literal in a condition. */
    private val PROJECT_PACKAGE = "cc.joycreator.joybrush.core."

    /**
     * The one source file that holds `CellRoll` and `CellRollGrammar`, found from the repo root.
     *
     * [joybrushRoot] already answers a [File] (`DefaultPresetsTest.kt:458`), so the walk starts from
     * that value and NOT from `File(joybrushRoot())` — `java.io.File` has no one-argument `File(File)`
     * constructor, and the compiler says "None of the following candidates is applicable" rather than
     * anything a reader would recognise as a wrapper it could drop.
     */
    private fun cellRollSource(): File {
        val path = listOf("core", "src", "commonMain", "kotlin", "cc", "joycreator", "joybrush", "core", "sprite", "CellRoll.kt")
        val found: File = path.fold(joybrushRoot()) { dir, part -> File(dir, part) }
        if (!found.isFile) {
            fail("this test cannot read $found, so the import check below would pass on nothing")
        }
        return found
    }

    /** The SIMPLE names on this file's `import` lines, in order, with duplicates kept out. */
    private fun importSimpleNamesOf(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(' ').substringAfterLast('.') }
        .distinct()
}
