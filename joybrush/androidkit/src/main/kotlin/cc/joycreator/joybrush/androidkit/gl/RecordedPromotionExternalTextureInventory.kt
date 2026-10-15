package cc.joycreator.joybrush.androidkit.gl

/** Diagnostic inventory separate from the scene/native/history families checked by the probe. */
internal object RecordedPromotionExternalTextureInventory {
    data class Check(
        val sourceConflicts: Set<Int>,
        val cacheConflicts: Set<Int>,
        val independentLineConflicts: Set<Int>,
        val independentLineDuplicates: Boolean,
        val groups: Map<String, Set<Int>>,
    ) {
        val passed: Boolean get() = sourceConflicts.isEmpty() && cacheConflicts.isEmpty() &&
            independentLineConflicts.isEmpty() && !independentLineDuplicates
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(owner)

    private fun handles(owner: Any?, methodName: String): Set<Int> {
        if (owner == null) return emptySet()
        val method = owner.javaClass.declaredMethods.single {
            (it.name == methodName || (it.name.startsWith("$methodName\$") &&
                !it.name.removePrefix("$methodName\$").contains('$'))) && it.parameterCount == 0
        }.also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (method.invoke(owner) as Collection<Int>).filter { it != 0 }.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun collection(owner: Any, name: String): Set<Int> =
        (field(owner, name) as Collection<Int>).filter { it != 0 }.toSet()

    private fun mapValues(owner: Any, name: String): Set<Int> =
        ((field(owner, name) as Map<*, *>).values).filterIsInstance<Int>().filter { it != 0 }.toSet()

    /**
     * Collects pool, transient, and engine-global owners without calling
     * protectedRecordedTextureHandles(), whose result includes the current scene and would hide
     * precisely the source/cache collisions this check intends to expose.
     */
    fun check(engine: GlPaintEngine, sources: Collection<Int>, caches: Collection<Int>,
              independentLines: Collection<Int>): Check {
        val groups = linkedMapOf<String, Set<Int>>()
        for (name in listOf("freeLayerTex", "freeStrokeTex", "freeSmudgeTex", "freeFloatTex",
                "floatNames", "strokeTiles")) {
            groups[name] = if (name == "strokeTiles") mapValues(engine, name) else collection(engine, name)
        }
        for (name in listOf("clearTex", "whiteTex", "thumbTex", "tuftPaperTex", "lookTexture", "surfaceTexture", "fluidTexture")) {
            val value = field(engine, name)
            groups[name] = (value as? Int)?.let { if (it == 0) emptySet() else setOf(it) }
                ?: (value as? Number)?.toInt()?.let { if (it == 0) emptySet() else setOf(it) }.orEmpty()
        }
        groups["agreedLook"] = mapValues(engine, "agreedLook")
        for ((fieldName, prefix) in listOf("compositor" to "compositor", "boardCompositor" to "boardCompositor")) {
            val owner = field(engine, fieldName) ?: continue
            groups["$prefix.targets"] = listOf("target", "backdrop").mapNotNull { child ->
                (field(owner, child) as? Number)?.toInt()?.takeIf { it != 0 }
            }.toSet()
        }
        for ((fieldName, groupName) in listOf(
            "grains" to "grains", "boardPreview" to "boardPreview",
            "paperBackground" to "paperBackground", "wcPass" to "watercolourPass",
        )) groups[groupName] = handles(field(engine, fieldName), "heldTextureHandles")
        val look = field(engine, "look")
        if (look != null) {
            groups["lookAssets"] = listOf("tipGrainTex", "paperGrainTex", "fluidTexture").mapNotNull { name ->
                (field(look, name) as? Number)?.toInt()?.takeIf { it != 0 }
            }.toSet()
        }
        // Media stroke ownership should be absent at a checkpoint; still report its handles if a
        // caller accidentally evaluates this while active, so the check cannot silently pass.
        val media = field(engine, "mediaStroke") as? Collection<*>
        groups["mediaStroke"] = media.orEmpty().flatMap { change ->
            val value = change ?: return@flatMap emptyList<Int>()
            listOfNotNull(field(value, "before"), field(value, "after")).filterIsInstance<Int>()
        }.filter { it != 0 }.toSet()

        val external = groups.values.flatten().toSet()
        return Check(sources.filter { it in external }.toSet(), caches.filter { it in external }.toSet(),
            independentLines.filter { it in external }.toSet(),
            independentLines.distinct().size != independentLines.size, groups)
    }
}
