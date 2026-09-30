package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag

/**
 * The three tools at the top of the strip (JB-2.01): painting, smudging, erasing. Each one REMEMBERS its own brush,
 * size and opacity, the way every painting app a painter already knows does: a big soft eraser and a fine pencil are
 * two settings, and switching between them must not trade one for the other.
 */
enum class ToolSlot { BRUSH, SMUDGE, ERASER }

/** One tool's memory: which brush (by [BrushPreset.id]), how big (diameter, document px), how opaque (0.01..1). */
data class SlotState(val brushId: String, val sizePx: Float, val opacity: Float)

/**
 * Every tool's memory and which tool is in the hand. Immutable: each change is a new value, so the screen can save the
 * whole thing in one line of preferences and a test can say exactly what a tap did.
 */
data class ToolMemory(val active: ToolSlot, val slots: Map<ToolSlot, SlotState>) {

    /** The tool in the hand, or null when there is no brush for it (the strip hides a tool it cannot offer). */
    val current: SlotState? get() = slots[active]

    /** Take up [slot], if it has a brush. A tool with none is ignored rather than leaving the hand empty. */
    fun activate(slot: ToolSlot): ToolMemory = if (slots.containsKey(slot)) copy(active = slot) else this

    /**
     * A brush picked in the drawer goes to the tool it belongs to ([slotFor]) and that tool is taken up, at the brush's
     * OWN size and opacity: a new brush starts as its file says, and from then on remembers what the person sets.
     */
    fun pick(preset: BrushPreset): ToolMemory {
        val slot = slotFor(preset)
        val state = SlotState(preset.id, preset.size.base, preset.opacity.base)
        return ToolMemory(slot, slots + (slot to state))
    }

    /** The tool in the hand at a new size (clamped like the drag clamps it). */
    fun withSize(sizePx: Float): ToolMemory = update { it.copy(sizePx = clampSize(sizePx, it.sizePx)) }

    /** The tool in the hand at a new opacity (clamped like the drag clamps it). */
    fun withOpacity(opacity: Float): ToolMemory = update { it.copy(opacity = clampOpacity(opacity, it.opacity)) }

    private fun update(f: (SlotState) -> SlotState): ToolMemory {
        val now = slots[active] ?: return this
        return copy(slots = slots + (active to f(now)))
    }

    /**
     * The preset the canvas should draw with for the tool in the hand: its brush from [library], at its remembered size
     * and opacity. Null when that brush is no longer in the library (an imported brush that was deleted), which the
     * screen answers by falling back to [defaults].
     */
    fun presetFrom(library: List<BrushPreset>): BrushPreset? {
        val s = current ?: return null
        val p = library.firstOrNull { it.id == s.brushId } ?: return null
        return sized(p, s.sizePx, s.opacity)
    }

    /** Several lines of text for the preferences file; [decode] reads them back. The brush id goes LAST: it may hold spaces. */
    fun encode(): String = buildString {
        append("active ").append(active.name)
        for ((slot, s) in slots) append('\n').append(slot.name).append(' ').append(s.sizePx).append(' ').append(s.opacity).append(' ').append(s.brushId)
    }

    companion object {

        /**
         * Which tool a brush belongs to. An erasing brush is the eraser; a brush that moves paint rather than laying it
         * (smudge, push) is the smudge tool; everything else paints.
         */
        fun slotFor(p: BrushPreset): ToolSlot = when {
            p.blend == "erase" -> ToolSlot.ERASER
            p.engine == ENGINE_SMUDGE || p.engine == ENGINE_PUSH -> ToolSlot.SMUDGE
            else -> ToolSlot.BRUSH
        }

        /**
         * [p] at [sizePx] and [opacity]. Only the BASES change: a size that follows pressure still follows it, around the
         * new size, so the brush keeps its character at every size.
         */
        fun sized(p: BrushPreset, sizePx: Float, opacity: Float): BrushPreset =
            p.copy(size = p.size.copy(base = clampSize(sizePx, p.size.base)), opacity = p.opacity.copy(base = clampOpacity(opacity, p.opacity.base)))

        /** Each tool's first brush in [library] order, at the file's own size; the brush tool in the hand. */
        fun defaults(library: List<BrushPreset>): ToolMemory {
            val slots = LinkedHashMap<ToolSlot, SlotState>()
            for (p in library) {
                val slot = slotFor(p)
                if (!slots.containsKey(slot)) slots[slot] = SlotState(p.id, p.size.base, p.opacity.base)
            }
            val active = if (slots.containsKey(ToolSlot.BRUSH)) ToolSlot.BRUSH else slots.keys.firstOrNull() ?: ToolSlot.BRUSH
            return ToolMemory(active, slots)
        }

        /**
         * Reads [encode]'s text against [library]. A line naming a brush the library no longer has is dropped and that
         * tool falls back to its default; a tool the library cannot fill at all is left out. Unreadable text is [defaults].
         */
        fun decode(text: String?, library: List<BrushPreset>): ToolMemory {
            val base = defaults(library)
            if (text.isNullOrBlank()) return base
            var active = base.active
            val slots = LinkedHashMap(base.slots)
            for (line in text.lines()) {
                val parts = line.trim().split(' ', limit = 4)
                if (parts.size == 2 && parts[0] == "active") {
                    ToolSlot.entries.firstOrNull { it.name == parts[1] }?.let { active = it }
                    continue
                }
                if (parts.size != 4) continue
                val slot = ToolSlot.entries.firstOrNull { it.name == parts[0] } ?: continue
                val size = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                val opacity = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
                val brush = library.firstOrNull { it.id == parts[3] } ?: continue
                // A brush that has moved tools (a file that changed) is not put in the wrong hand.
                if (slotFor(brush) != slot) continue
                slots[slot] = SlotState(brush.id, clampSize(size, brush.size.base), clampOpacity(opacity, brush.opacity.base))
            }
            if (!slots.containsKey(active)) active = base.active
            return ToolMemory(active, slots)
        }

        private fun clampSize(v: Float, fallback: Float): Float =
            if (v.isFinite()) v.coerceIn(SizeOpacityDrag.MIN_SIZE, SizeOpacityDrag.MAX_SIZE) else fallback

        private fun clampOpacity(v: Float, fallback: Float): Float =
            if (v.isFinite()) v.coerceIn(SizeOpacityDrag.MIN_OPACITY, 1f) else fallback
    }
}
