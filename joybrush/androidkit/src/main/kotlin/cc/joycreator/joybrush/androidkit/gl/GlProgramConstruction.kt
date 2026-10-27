package cc.joycreator.joybrush.androidkit.gl

/** The real constructor's narrow driver seam, so faults exercise its actual ownership flow. */
internal interface GlProgramDriver {
    fun current(): Boolean
    fun createShader(type: Int): Int
    fun compile(shader: Int, source: String)
    fun shaderCompiled(shader: Int): Boolean
    fun shaderLog(shader: Int): String
    fun createProgram(): Int
    fun attach(program: Int, shader: Int)
    fun link(program: Int)
    fun linked(program: Int): Boolean
    fun programLog(program: Int): String
    fun deleteShader(shader: Int)
    fun deleteProgram(program: Int)
}

internal object GlProgramConstruction {
    fun build(vertex: String, fragment: String, label: String, driver: GlProgramDriver): Int {
        val owned = GlInitOwnership(driver::current, { kind, name ->
            when (kind) {
                GlInitOwnership.Kind.SHADER -> driver.deleteShader(name)
                GlInitOwnership.Kind.PROGRAM -> driver.deleteProgram(name)
                else -> error("Unexpected shader construction resource")
            }
        }, programs = 1, shaders = 2)
        try {
            check(driver.current()) { "No current context for program $label" }
            for (i in 0..1) {
                val shader = driver.createShader(if (i == 0) 0x8B31 else 0x8B30)
                owned.shaderNames[i] = shader
                check(shader != 0) { "Could not create shader ($label)" }
                driver.compile(shader, if (i == 0) vertex else fragment)
                check(driver.shaderCompiled(shader)) {
                    "compile failed ($label, ${if (i == 0) "vertex" else "fragment"}): ${driver.shaderLog(shader)}"
                }
            }
            val program = driver.createProgram()
            owned.programNames[0] = program
            check(program != 0) { "Could not create program ($label)" }
            driver.attach(program, owned.shaderNames[0]); driver.attach(program, owned.shaderNames[1])
            driver.link(program)
            check(driver.linked(program)) { "link failed ($label): ${driver.programLog(program)}" }
            for (i in owned.shaderNames.indices) {
                check(driver.current()) { "Program context changed during initialization" }
                val shader = owned.shaderNames[i]
                owned.shaderNames[i] = 0
                driver.deleteShader(shader)
            }
            check(driver.current()) { "Program context changed during initialization" }
            owned.programNames[0] = 0 // Transfer only a fully linked program.
            return program
        } catch (failure: Throwable) {
            owned.rollback(failure)
            throw failure
        }
    }
}
