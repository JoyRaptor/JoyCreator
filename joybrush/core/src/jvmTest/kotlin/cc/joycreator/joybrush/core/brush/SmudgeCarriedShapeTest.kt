package cc.joycreator.joybrush.core.brush

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The machine-checkable half of "there is nowhere else for a smudge brush's colour to live" (blueprint §5, R8):
 * [SmudgeCarried] holds exactly ten numbers, every one final, and no collection. A reservoir or a second pickup store
 * would need a new field or a mutable one, and this fails first.
 */
class SmudgeCarriedShapeTest {

    @Test
    fun theClassHasTenFinalNumbersAndNothingElse() {
        val instanceFields = SmudgeCarried::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(
            setOf("loadR", "loadG", "loadB", "strength", "pickup", "load", "carriedR", "carriedG", "carriedB", "carriedA"),
            instanceFields.map { it.name }.toSet(),
            "a new field on the carried colour is a new place for paint to live: that is the patent rule",
        )
        assertTrue(instanceFields.all { Modifier.isFinal(it.modifiers) }, "a mutable field would be a store")
        assertTrue(instanceFields.all { it.type == Float::class.javaPrimitiveType }, "only numbers: no list, no second object")
    }
}
