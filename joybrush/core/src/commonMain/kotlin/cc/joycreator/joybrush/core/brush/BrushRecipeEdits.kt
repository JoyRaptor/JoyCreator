package cc.joycreator.joybrush.core.brush

/**
 * Public editing boundary for platform settings: use the existing per-word version table,
 * without serializing a recipe on every slider move. No schema or rendering behavior here.
 * DRAFT — not compiled/tested. Place in core alongside BrushJson, whose table is internal.
 */
object BrushRecipeEdits {
    fun withRequiredVersion(recipe: BrushPreset): BrushPreset {
        val required = BrushJson.wordsNeedingVersion(recipe).maxOfOrNull { it.minVersion } ?: recipe.version
        return if (recipe.version >= required) recipe else recipe.copy(version = required)
    }
}
