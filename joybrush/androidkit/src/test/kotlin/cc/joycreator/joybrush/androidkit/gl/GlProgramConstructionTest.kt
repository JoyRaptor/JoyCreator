package cc.joycreator.joybrush.androidkit.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Actual constructor control flow with a faulting driver; not a GPU/compiler result. */
class GlProgramConstructionTest {
    private class Driver : GlProgramDriver {
        var active = true
        var nextShader = 10
        var badShader = 0
        var linkOk = true
        var failAt: String? = null
        var loseContextOnLink = false
        val failure = IllegalStateException("injected driver fault")
        val deleted = mutableListOf<Pair<String, Int>>()
        private fun fault(at: String) { if (failAt == at) throw failure }
        override fun current() = active
        override fun createShader(type: Int) = nextShader++
        override fun compile(shader: Int, source: String) = fault("compile:$shader")
        override fun shaderCompiled(shader: Int) = shader != badShader
        override fun shaderLog(shader: Int): String { fault("shaderLog"); return "compile diagnostic" }
        override fun createProgram() = 20
        override fun attach(program: Int, shader: Int) = fault("attach")
        override fun link(program: Int) { if (loseContextOnLink) active = false; fault("link") }
        override fun linked(program: Int) = linkOk
        override fun programLog(program: Int) = "link diagnostic"
        override fun deleteShader(shader: Int) { deleted += "shader" to shader; fault("delete:$shader") }
        override fun deleteProgram(program: Int) { deleted += "program" to program; fault("delete:$program") }
        fun build() = GlProgramConstruction.build("vertex source", "fragment source", "fault test", this)
    }

    @Test fun fragmentCompileExceptionReclaimsPreviouslyCompiledVertexAndFragment() {
        val driver = Driver().apply { failAt = "compile:11" }
        assertSame(driver.failure, assertFailsWith<IllegalStateException> { driver.build() })
        assertEquals(setOf("shader" to 10, "shader" to 11), driver.deleted.toSet())
        assertEquals(2, driver.deleted.size)
    }

    @Test fun compileStatusAndDiagnosticFailureDoNotLeakShader() {
        val driver = Driver().apply { badShader = 10; failAt = "shaderLog" }
        assertSame(driver.failure, assertFailsWith<IllegalStateException> { driver.build() })
        assertEquals(listOf("shader" to 10), driver.deleted)
    }

    @Test fun linkRefusalReclaimsProgramAndBothShaders() {
        val driver = Driver().apply { linkOk = false }
        val failure = assertFailsWith<IllegalStateException> { driver.build() }
        assertTrue(failure.message!!.contains("link diagnostic"))
        assertEquals(setOf("shader" to 10, "shader" to 11, "program" to 20), driver.deleted.toSet())
        assertEquals(3, driver.deleted.size)
    }

    @Test fun attachExceptionReclaimsAlreadyCreatedProgram() {
        val driver = Driver().apply { failAt = "attach" }
        assertSame(driver.failure, assertFailsWith<IllegalStateException> { driver.build() })
        assertEquals(setOf("shader" to 10, "shader" to 11, "program" to 20), driver.deleted.toSet())
    }

    @Test fun successfulLinkTransfersProgramAndRetiresOnlyShaders() {
        val driver = Driver()
        assertEquals(20, driver.build())
        assertEquals(listOf("shader" to 10, "shader" to 11), driver.deleted)
    }

    @Test fun contextLossDuringLinkNeverDeletesReusedNamesOnAnotherContext() {
        val driver = Driver().apply { loseContextOnLink = true; failAt = "link" }
        assertSame(driver.failure, assertFailsWith<IllegalStateException> { driver.build() })
        assertTrue(driver.deleted.isEmpty())
    }

    @Test fun cleanupFailureIsSuppressedWhileRemainingShadersAreStillRetired() {
        val driver = Driver().apply { linkOk = false; failAt = "delete:20" }
        val failure = assertFailsWith<IllegalStateException> { driver.build() }
        assertSame(driver.failure, failure.suppressed.single())
        assertEquals(setOf("shader" to 10, "shader" to 11, "program" to 20), driver.deleted.toSet())
        assertEquals(3, driver.deleted.size)
    }
}
