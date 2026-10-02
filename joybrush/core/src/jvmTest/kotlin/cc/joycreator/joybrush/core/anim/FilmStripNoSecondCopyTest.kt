package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.brush.joybrushRoot
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
 * A type variable, which is the only way this file can talk about a GENERIC class at all — and the
 * only way to ask whether a generic walk works.
 *
 * Private to the test source set and to this file's own use: the strip is not generic and must not
 * become generic, which is exactly why this declaration is the only one in the package and why the
 * type walk in `typesReachableFrom` has to be PROVED on it rather than assumed.
 */
private interface Provider<T>

/**
 * **A USE SITE, and that is the whole point of this class.** `Provider<String>` is not a type
 * anything holds: a type ARGUMENT only exists where some class says `Provider<String>`, and that
 * place is this one. `Provider::class.java` on its own carries no argument at all, so a walk seeded
 * from it can never see `String` — and a canary that expects it to is a canary that has misunderstood
 * reflection, not one that has found a bug.
 *
 * Its `genericSuperclass` is the `ParameterizedType` `Provider<String>`, so a walk seeded HERE sees
 * the argument. That is why the seed is this class and not the interface.
 */
private class StringHolder : Provider<String>

/**
 * The two canaries that are SOURCE and BYTECODE level rather than arithmetic, and the reason they
 * are here and not in `commonTest` is mechanical: both need `::class.java`, which does not exist in
 * a multiplatform `commonTest` source set. That is not a style note — a source-level test written
 * in `commonTest` is a build failure, and putting one there has happened eight times in this project
 * (JB-1.07, JB-2.04, JB-2.12, JB-2.16, JB-2.17, JB-2.23, JB-3.04 and this row's own draft).
 *
 * **J1 is the seam.** The strip's whole geometry is in ticks, and a caller with an elapsed time
 * converts it to an index through `FrameStepper.frameOnScreen` first. The reason that one-way door
 * exists is `PlaybackClock.nextChangeMs`: at a backward boundary of a PING_PONG leg it hands back
 * the boundary ITSELF as an infimum, so a caller that sleeps until it and then re-asks without
 * comparing frame indices spins forever. That is correct behaviour of the clock and a bug in the
 * caller, and this row is the caller most likely to have one — which is why a cut that removed every
 * line of clock code still kept the ruling, and why J1 is asked here rather than forgotten.
 *
 * **J2 is the no-second-copy rule.** `MAX_HOLD_FRAMES` and `MIN_HOLD_FRAMES` are `private` in
 * `AnimOps`, so a local copy in the strip could drift from the model's and nothing in the arithmetic
 * would catch it — the same trap R19 fixed for `SizeOpacityDrag.MAX_SIZE_PX`. The strip's answer to
 * that is to CALL the model and read the hold back out of the document that comes back, and J2 is
 * what notices if somebody "optimises" that into a constant.
 */

/** The class the seam is about, by simple name, so a failure can name it in a sentence. */
private const val CLOCK = "PlaybackClock"

/** The names `AnimOps` keeps to itself, and which therefore must not appear as a field here. */
private val CLAMP_NAMES = listOf("MAX_HOLD", "MIN_HOLD")

class FilmStripNoSecondCopyTest {

    /**
     * No member of the strip mentions the clock — and not only in its SIGNATURE, which is what
     * reflection can see, but anywhere in its BYTECODE, which is what a call inside a method body
     * looks like. Both are asked, and the second is the one that matters: `edgeAt` calling
     * `PlaybackClock.frameIndexAt` and throwing the answer away has no `PlaybackClock` in any
     * signature and is exactly the mistake this test exists for.
     *
     * The bytecode read is the class FILE's own constant pool as bytes, matched as ISO-8859-1 so
     * that one byte is one character and a substring search is a substring search. A class file that
     * so much as NAMES the clock carries `PlaybackClock` in a `CONSTANT_Utf8` entry, and a call to
     * `nextChangeMs` carries that name in a `NameAndType` entry — there is no way to hold either
     * reference without those characters being in the file.
     *
     * The source is asked too, for the half reflection cannot see at all: the prose. The file's own
     * KDoc names `PlaybackClock.nextChangeMs` in order to forbid it, so a grep for the word would be
     * a test that forbids the documentation of the rule; the import lines are therefore read on
     * their own (the spec's own claim — and a redundant import of a class in the SAME PACKAGE is
     * itself proof somebody meant to use it), and every NON-COMMENT line is checked for the word as
     * well, which is a strictly stronger version of it.
     */
    @Test
    fun theStripNeverConsultsThePlaybackClock() {
        for (klass in listOf(FilmStrip::class.java, FilmStripGesture::class.java)) {
            for (method in klass.declaredMethods) {
                val named = describe(method)
                assertFalse(
                    method.name.contains("nextChangeMs") || method.name.contains(CLOCK),
                    "${klass.simpleName} declares $named, and the seam is that no member of the " +
                        "strip asks the clock anything",
                )
                assertNoClock(method.returnType, "${klass.simpleName}'s $named returns")
                for (parameter in method.parameterTypes) {
                    assertNoClock(parameter, "${klass.simpleName}'s $named takes")
                }
            }
            for (field in klass.declaredFields) {
                assertFalse(
                    field.name.contains("clock", ignoreCase = true),
                    "${klass.simpleName} holds a field called \"${field.name}\": a field with the " +
                        "clock's name in it is the clock, whatever its type is",
                )
                assertNoClock(field.type, "${klass.simpleName}'s field \"${field.name}\" is")
            }
            val text = classFileText(klass)
            assertFalse(text.contains(CLOCK), "${klass.name}.class names $CLOCK in its constant pool")
            assertFalse(
                text.contains("nextChangeMs"),
                "${klass.name}.class names nextChangeMs in its constant pool, so something in it calls the clock",
            )
        }

        val source = File(
            joybrushRoot(),
            "core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt",
        )
        assertTrue(
            source.isFile,
            "the strip's own source must be where the spec says it is: ${source.absolutePath}",
        )
        val lines = source.readLines()
        val imports = lines.filter { it.trimStart().startsWith("import ") }
        assertTrue(
            imports.none { it.contains(CLOCK) },
            "$CLOCK is in the SAME PACKAGE as the strip, so it never needs an import — and an " +
                "import is proof somebody meant to use it: $imports",
        )
        val code = lines.filterNot { isCommentLine(it) }
        assertTrue(
            code.none { it.contains(CLOCK) },
            "no line of code in FilmStrip.kt may mention $CLOCK; these do: " + code.filter { it.contains(CLOCK) },
        )
        assertTrue(
            code.none { it.contains("nextChangeMs") },
            "no line of code in FilmStrip.kt may mention nextChangeMs; these do: " +
                code.filter { it.contains("nextChangeMs") },
        )
    }

    /**
     * The file owns EXACTLY two numbers, and they are 44 and 24.
     *
     * **WHERE THE FIELDS ACTUALLY LAND, since asking `Companion.declaredFields` was wrong and cost a
     * run.** The two `const val`s are declared in `FilmStrip`'s companion object, and Kotlin does NOT
     * put their fields there: `javap` on the built classes says
     *
     * ```
     * public final class FilmStrip$Companion {
     *   private FilmStrip$Companion();                        // nothing else. No fields at all.
     * }
     * public final class FilmStrip {
     *   public static final FilmStrip$Companion Companion;
     *   public static final float TICK_PX_DP;
     *   public static final float EDGE_GRAB_PX_DP;
     *   ...
     * }
     * ```
     *
     * The names also appear in the Companion's constant pool, because its `@Metadata` lists its own
     * declarations — which is exactly why a constant-pool search and a `declaredFields` search
     * disagree, and why the first version of this test read as "the companion holds []".
     *
     * So this does not ask the Companion, and it does not ask one class either: it CENSUSES every
     * class the file compiles to — the strip, the companion, the gesture, the sealed families and
     * their members, and the file facade `FilmStripKt` that a top-level `const val` would land in —
     * and takes the static final PRIMITIVE fields of all of them. Instance fields are excluded
     * (`board`, `density`, `strip`, `downX`, `holdAtDown`, `holdFrames` are all instance fields and
     * none of them is a number this class owns), and a static field whose type is a class is excluded
     * too, which is what keeps `Companion` and the two `INSTANCE` fields of `Grab.Scrub` and
     * `StripStep.Nothing` out of the list.
     *
     * The census is the assertion, and it is deliberately NOT a function of where the compiler puts
     * a constant: a `const val MAX_HOLD = 999` added in the companion, at the top of the file, or as
     * a class-level constant is a static final primitive in one of those classes, and all three are
     * caught. The claim that would matter — a copy that is neither `const` nor static — is not
     * catchable by reflection at all, and the honest place for that is the behaviour: the strip must
     * ASK the model for its limit, which `draggingRightStopsAtTheModelsOwnLimit` in `FilmStripTest`
     * does by comparing against `AnimOps.setHold` rather than against a number written here.
     *
     * The last block is that same rule by NAME, over every field of every class in the file: nothing
     * may be called after the model's private constants. It is a check on two exact names rather than
     * on the words "max"/"min"/"hold" because those words are the contract's own vocabulary
     * elsewhere — `Grab.Edge.holdAtDown` and `StripStep.HoldChanged.holdFrames` are in the spec, and
     * a rule that forbade them would forbid the contract. `MAX_HOLD_FRAMES`/`MIN_HOLD_FRAMES` both
     * contain `MAX_HOLD`/`MIN_HOLD`, so a copy under either spelling is caught.
     */
    @Test
    fun theStripHasNoCopyOfTheHoldClamp() {
        val constants = primitiveConstantsInThisFile()
        assertEquals(
            listOf("EDGE_GRAB_PX_DP", "TICK_PX_DP"),
            constants.map { it.name },
            "the file owns exactly two numbers, 24 and 44, and a third is a number this class has " +
                "stopped asking the model about. Every class in the file was censused: $constants",
        )
        for (field in constants) {
            assertTrue(
                field.type == Float::class.javaPrimitiveType,
                "${field.name} is a screen distance, so it is a primitive float and not a boxed one",
            )
            assertTrue(
                Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers),
                "${field.name} is static and final, as a const must be",
            )
        }
        assertEquals(44f, FilmStrip.TICK_PX_DP, "44 dp per tick (R32), and density is applied at the use site")
        assertEquals(24f, FilmStrip.EDGE_GRAB_PX_DP, "24 dp of one-sided edge grab (Q1), and not 12")

        for (klass in classesInThisFile()) {
            for (field in klass.declaredFields) {
                for (clamp in CLAMP_NAMES) {
                    assertFalse(
                        field.name.contains(clamp, ignoreCase = true),
                        "${klass.simpleName} holds a field called \"${field.name}\". The hold clamp is " +
                            "the model's (MAX_HOLD_FRAMES, MIN_HOLD_FRAMES) and it is private there; a " +
                            "field with that name here is the copy R19's lesson is about",
                    )
                }
            }
        }
    }

    /**
     * Every `static final` field of a PRIMITIVE type declared by any class this file compiles to.
     *
     * The file facade is fetched by name rather than written as `FilmStripKt` in source: if a future
     * edit ever leaves the file with no top-level declaration the facade class does not exist, and a
     * test that could not compile because of an *absent* class would be a worse failure than one that
     * simply scans one class fewer.
     */
    private fun primitiveConstantsInThisFile(): List<Field> = classesInThisFile()
        .flatMap { it.declaredFields.toList() }
        .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type.isPrimitive }
        .sortedBy { it.name }

    /**
     * The two classes the strip's rules live in, and the sealed families they hand out, PLUS the file
     * facade — every class `FilmStrip.kt` compiles to, which is the only set a constant can hide in.
     */
    private fun classesInThisFile(): List<Class<*>> {
        val listed = listOf(
            FilmStrip::class.java,
            FilmStrip.Companion::class.java,
            FilmStripGesture::class.java,
            Grab::class.java,
            Grab.Edge::class.java,
            Grab.Scrub::class.java,
            StripStep::class.java,
            StripStep.PlayheadTo::class.java,
            StripStep.HoldChanged::class.java,
            StripStep.Nothing::class.java,
        )
        // A top-level `const val` in this file would land HERE rather than on any of the ten classes
        // above, and "somebody moved MAX_HOLD to the top of the file" is exactly the edit J2 is for.
        val facade = runCatching { Class.forName("cc.joycreator.joybrush.core.anim.FilmStripKt") }.getOrNull()
        return if (facade == null) listed else listed + facade
    }

    /**
     * No type reachable from a member's signature, or from a field's type, may be the clock — and the
     * failure says WHICH EDGE, because "X reaches the clock" is not a thing anybody can act on.
     *
     * There is deliberately **no allow-list**, and that was a decision rather than an omission. A
     * suite once went red here claiming that `AnimResult` reaches `PlaybackClock`, on the reasoning
     * that the model's result type is a legitimate door. The compiled output says otherwise and it
     * is worth writing down, because it is the whole answer: of every class in `core`'s main output,
     * **four** name the clock in their constant pool — `PlaybackClock`, `PlaybackClock$Companion`,
     * `PlayMode` and `FrameStepper` — and none of them is in the `doc` package. `AnimResult` is a
     * data class with no supertype but `Object` (`javap` on the built class), so no chain of
     * supertypes runs from it to the clock, and it does not have to be excused because it was never
     * connected. An allow-list naming `AnimResult` would have been a false statement in a KDoc, and
     * worse, a real hole: `Pair<AnimResult, PlaybackClock>` would have walked straight through it.
     * So the check stays strict, and the route is printed instead.
     *
     * The authoritative form of the claim is the constant-pool scan in the caller — a call to the
     * clock puts the name in the class file whether or not any signature mentions it. This function
     * is the type-level tripwire for the day a signature does.
     */
    private fun assertNoClock(type: Class<*>, where: String) {
        val route = routeTo(type, CLOCK)
        val edges = if (route.isEmpty()) "" else route.joinToString(" -> ") { it.simpleName }
        assertTrue(
            route.isEmpty(),
            "$where reaches the clock, and here is the route: $edges. Every step of that chain is a " +
                "type this file's own signature hands to somebody, and none of them may be a clock.",
        )
    }

    /**
     * The shortest chain of classes from [from] to a class whose simple name is [targetSimpleName],
     * inclusive of both ends, or an empty list when there is none.
     *
     * A BFS over an owner/`Type` pair rather than over `Type` alone, so the PARENT of every class is
     * known and the route can be printed: a failure that says `AnimResult -> JbDocument ->
     * PlaybackClock` is a bug report and a failure that says `AnimResult reaches PlaybackClock` is a
     * puzzle. BFS and not DFS so the printed chain is the SHORTEST one and not merely the first
     * found; a `HashSet` of visited classes, so a cyclic type graph cannot hang the suite.
     */
    private fun routeTo(from: Class<*>, targetSimpleName: String): List<Class<*>> {
        val parent = HashMap<Class<*>, Class<*>>()
        // The guard is a `HashSet<Type>` of nodes ALREADY EXPANDED, not of classes already seen. The
        // difference is not a style choice: `java.lang.Enum<E extends Enum<E>>` puts a `TypeVariable`
        // whose `bounds` contain `Enum<E>`, whose `actualTypeArguments` contain `E` again - so a walk
        // that re-expands every non-class node re-enqueues `(Enum, E)` forever and never terminates.
        // A class-only guard cannot see that, because the repeated node is a `TypeVariable` and not a
        // class. Expanding each distinct `Type` at most once does terminate: the set of types
        // mentioned by a finite program is finite, and each expansion adds finitely many more.
        val expanded = HashSet<Type>()
        val pending = ArrayDeque<Pair<Class<*>, Type>>()
        pending.addLast(from to from)
        while (pending.isNotEmpty()) {
            val (owner, node) = pending.removeFirst()
            if (!expanded.add(node)) continue
            if (node is Class<*>) {
                // Never record a SELF-parent. The seed is enqueued as `(from to from)`, so the
                // first pop has `owner == node == from` and an unconditional store writes
                // `parent[from] = from` — and the unwind below (`at = parent[step]`, breaking only
                // on null) then walks `from -> from -> ...` forever, growing `chain` without
                // bound. The green path never reaches the unwind, so the suite stays green while
                // the first red hangs CI instead of failing. Skipping the self-edge leaves `from`
                // parentless, which is exactly right: it is the START of every route, so the
                // unwind ends there and the red path FAILS instead of hanging.
                if (node != owner) parent[node] = owner
                if (node.simpleName == targetSimpleName) {
                    val chain = ArrayList<Class<*>>()
                    var at: Class<*>? = node
                    while (true) {
                        val step: Class<*> = at ?: break
                        chain.add(step)
                        at = parent[step]
                    }
                    chain.reverse()
                    return chain
                }
                for (edge in edgesOf(node)) pending.addLast(node to edge)
            } else {
                // A `Type` that is not a class (a `ParameterizedType`, a `TypeVariable`, a wildcard)
                // is not a place the route can stop, but it has classes inside it: keep the OWNER, so
                // the printed chain names the class that actually carries the edge.
                for (edge in edgesOf(node)) pending.addLast(owner to edge)
            }
        }
        return emptyList()
    }

    /** The types one node of the walk hands over to: supertypes, array elements, and type arguments. */
    private fun edgesOf(node: Type): List<Type> = when (node) {
        is Class<*> -> {
            val out = ArrayList<Type>()
            node.componentType?.let { out.add(it) }
            node.genericSuperclass?.let { out.add(it) }
            out.addAll(node.genericInterfaces.toList())
            out
        }
        is ParameterizedType -> {
            val out = ArrayList<Type>()
            out.add(node.rawType)
            out.addAll(node.actualTypeArguments.toList())
            out
        }
        is TypeVariable<*> -> node.bounds.toList()
        is GenericArrayType -> listOf(node.genericComponentType)
        is WildcardType -> node.upperBounds.toList()
        else -> emptyList()
    }

    /**
     * Every class reachable from [type] by descending generics and array element types, INCLUDING
     * [type] itself — so a caller can assert about the one it was handed without a second lookup.
     *
     * The seed is a class, so a type ARGUMENT is only ever seen where some class actually writes it
     * down (a `genericSuperclass` or a `genericInterfaces` entry that is a `ParameterizedType`); see
     * [StringHolder] for why that matters and `theTypeWalkCanStillSeeTheClock` for the proof that
     * this walk does descend.
     *
     * A guard against a cyclic type graph, because this is a `HashSet` keyed on a class and a walk
     * that met one would not terminate. No two classes here can be mutually generic today, so the
     * guard is not exercised; it is there because the alternative is a test that hangs.
     */
    private fun typesReachableFrom(type: Class<*>): List<Class<*>> {
        val seen = HashSet<Class<*>>()
        val out = ArrayList<Class<*>>()
        val expanded = HashSet<Type>()
        // The queue holds `Type`, not `Class`, and that is the whole fix. A class's own generic
        // parameters are `TypeVariable`s, and asking a `Class` for them needs `getTypeParameters()`,
        // whose Kotlin-visible shape is not the `TypeVariable` this walk wants. Reading the generic
        // SUPERTYPES instead — `genericSuperclass` and `genericInterfaces`, both plain `Type[]` —
        // reaches the same edges, because a generic edge on a class is by definition an argument or
        // a bound somewhere in its supertypes. The `TypeVariable` case below is still needed: a
        // `TypeVariable` arrives as an `actualTypeArguments` element, and its BOUNDS are the types
        // that can mention a class.
        val pending = ArrayDeque<Type>()
        pending.addLast(type)
        while (pending.isNotEmpty()) {
            // Expand each distinct `Type` AT MOST ONCE, and not each distinct class: `Enum<E extends
            // Enum<E>>` makes a `TypeVariable` whose bounds hand back the `ParameterizedType` whose
            // arguments hand back the variable, so a guard that only counts classes re-enqueues the
            // pair forever. See the same guard in `routeTo`.
            val next = pending.removeFirst()
            if (!expanded.add(next)) continue
            when (next) {
                is Class<*> -> {
                    if (!seen.add(next)) continue
                    out.add(next)
                    next.componentType?.let { pending.addLast(it) }
                    next.genericSuperclass?.let { pending.addLast(it) }
                    pending.addAll(next.genericInterfaces.toList())
                }
                is ParameterizedType -> {
                    next.rawType?.let { pending.addLast(it) }
                    pending.addAll(next.actualTypeArguments.toList())
                }
                is TypeVariable<*> -> pending.addAll(next.bounds.toList())
                is GenericArrayType -> pending.addLast(next.genericComponentType)
                is WildcardType -> pending.addAll(next.upperBounds.toList())
                else -> Unit
            }
        }
        return out
    }

    /**
     * The walk of [typesReachableFrom] proved on a type that IS generic, so the check it is guarding
     * is known to be able to fail before it is trusted to pass.
     *
     * The positive half: the walk seeded at a USE SITE — [StringHolder], whose `genericSuperclass`
     * is the `ParameterizedType` `Provider<String>` — must come back holding `String`. Seeded at
     * `Provider::class.java` it could not, and could not for a reason worth pinning rather than
     * deleting, which is the line below: a type argument belongs to the place that writes it down,
     * not to the interface that is parameterised.
     *
     * The negative half, and it is the half that matters: an anonymous `Provider<PlaybackClock>` must
     * be REJECTED. If it came back clean, the walk is not looking where the seam would be, and the
     * clean bill of health J1 gives the strip would be worth nothing — so this asserts the walk is
     * broken before J1 is allowed to say it is not.
     */
    @Test
    fun theTypeWalkCanStillSeeTheClock() {
        val reached = typesReachableFrom(StringHolder::class.java)
        assertTrue(
            reached.any { it == String::class.java },
            "a Provider<String> USE SITE must reach String through its type argument, so the walk " +
                "descends generic supertypes: $reached",
        )
        assertTrue(
            reached.any { it.simpleName == "Provider" },
            "and it must come back through the interface it was seeded past: $reached",
        )
        assertNoClock(StringHolder::class.java, "the probe's own type is")

        assertTrue(
            typesReachableFrom(Provider::class.java).none { it == String::class.java },
            "and the interface ALONE must NOT reach String, because nobody wrote Provider<String> " +
                "down anywhere: a walk that found it here would be inventing an edge",
        )

        val smuggled = object : Provider<PlaybackClock> {}
        assertFalse(
            typesReachableFrom(smuggled::class.java).none { it.simpleName == CLOCK },
            "a Provider<PlaybackClock> must be REJECTED, or the walk is not looking where the seam is",
        )
    }

    /**
     * The route to the clock TERMINATES, so the red path fails instead of hanging.
     *
     * The previously-hanging path: the seed is enqueued as `(from to from)`, so the first pop
     * wrote `parent[from] = from` and the unwind (`at = parent[step]`, breaking only on null)
     * walked `from -> from -> ...` forever on ANY route that reaches the clock — including the
     * two seeds below, a one-edge type graph (`Provider<PlaybackClock>`) and the clock itself.
     * The green path never enters the unwind, so the suite was green while the first red would
     * have hung CI rather than failing.
     *
     * The bound is a 10-second join on a daemon worker, and reaching it means the bug: a correct
     * unwind visits each class at most once and both seeds finish in milliseconds (1 step for the
     * clock itself, 2 for the smuggled provider), so a worker still alive after 10 s is spinning
     * on a parent cycle. The worker is daemon so a spinning unwind cannot wedge the suite or the
     * JVM exit — the test FAILS instead. `routeTo` returning a NON-EMPTY route is the assertion,
     * because that is what makes `assertNoClock` fail in words rather than hang in silence.
     */
    @Test
    fun aRouteToTheClockTerminatesInsteadOfHanging() {
        val smuggled = object : Provider<PlaybackClock> {}
        assertClockRouteTerminates(smuggled::class.java)
        assertClockRouteTerminates(PlaybackClock::class.java)
    }

    /** `routeTo(seed, "PlaybackClock")` must come back holding the clock, within the bound above. */
    private fun assertClockRouteTerminates(seed: Class<*>) {
        var route: List<Class<*>>? = null
        val worker = Thread { route = routeTo(seed, CLOCK) }
        worker.isDaemon = true
        worker.start()
        worker.join(10_000)
        if (worker.isAlive) {
            fail(
                "routeTo(${seed.simpleName}, $CLOCK) still running after 10 s: the parent-chain " +
                    "unwind is spinning instead of terminating",
            )
        }
        val found = route ?: emptyList()
        assertFalse(
            found.isEmpty(),
            "routeTo(${seed.simpleName}, $CLOCK) came back empty: the seed reaches the clock, " +
                "so an empty route is the walk not looking where the seam is",
        )
        assertEquals(seed, found.first(), "every route starts where it was seeded")
        assertEquals(CLOCK, found.last().simpleName, "and this one must end at the clock: $found")
    }

    /** A member as a line of text, so a failure says WHICH member and not just which class. */
    private fun describe(method: Method): String = buildString {
        append(method.name)
        append('(')
        append(method.parameterTypes.joinToString(", ") { it.simpleName })
        append("): ")
        append(method.returnType.simpleName)
    }

    /**
     * A class's own bytecode as text, one byte per character.
     *
     * Read out of the code source the class was LOADED from, at the one path that can be right or
     * wrong, and nothing else. There used to be a recursive walk of the code source as a fallback
     * for a class file that was not where the package path says it is — and that fallback was worse
     * than no fallback at all: `walkTopDown().firstOrNull { it.name == "FilmStrip.class" }` would
     * have found a copy of the class under a *test* output directory, or under a stale
     * `build/classes` left from an older compile, and reported its bytes as the ones under test. So
     * a wrong answer was available and it was the answer a confused test would have taken. The walk
     * is gone: if the file is not where the package says, this FAILS, loudly, and names the path it
     * looked for. A canary that reads the wrong bytecode is worse than a canary that reports it
     * could not read any.
     */
    private fun classFileText(klass: Class<*>): String {
        val location = klass.protectionDomain?.codeSource?.location
            ?: throw IllegalStateException("${klass.name} has no code source, so its bytecode cannot be read")
        val root = File(location.toURI())
        val file = File(root, "${klass.name.replace('.', '/')}.class")
        if (!file.isFile) {
            throw IllegalStateException(
                "no ${klass.name} bytecode at ${file.absolutePath}: the code source is " +
                    "${root.absolutePath}, which is not a directory layout this test can read, and " +
                    "reading some OTHER copy of the class instead would be worse than failing",
            )
        }
        // ISO-8859-1: one byte to one character, so `contains` is a byte-for-byte substring search
        // and a CONSTANT_Utf8 entry cannot hide behind an encoding.
        return String(file.readBytes(), Charsets.ISO_8859_1)
    }

    /** A KDoc, block-comment or line-comment line, as opposed to a line of code. */
    private fun isCommentLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
    }
}
