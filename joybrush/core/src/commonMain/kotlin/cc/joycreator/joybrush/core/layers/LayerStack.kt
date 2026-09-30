package cc.joycreator.joybrush.core.layers

import cc.joycreator.joybrush.core.doc.BlendMode

/** One layer as the stack knows it: everything about a layer except its pixels. */
data class LayerState(
    val id: String,
    val name: String,
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val blend: BlendMode = BlendMode.NORMAL,
    /** It has a mask (JB-2.23). The mask's pixels live with the engine; the stack only says it is there. */
    val hasMask: Boolean = false,
    /** Clipped to the nearest unclipped layer below (JB-2.23). Never true of the bottom layer: see [LayerStack.normalized]. */
    val clip: Boolean = false,
)

/**
 * The layer stack, bottom → top, and which layer the brush paints on (JB-2.04, built by the Lead to the compact
 * thumbnail column of the approved JB-2.01 mockup). Immutable and pure: every operation returns a new stack, so the
 * screen, the engine's undo steps and the tests all speak about the same thing. A refused operation returns null or the
 * stack unchanged, never a half-made stack.
 *
 * The stack is never empty: there is always a layer to paint on.
 */
data class LayerStack(val layers: List<LayerState>, val activeId: String) {

    init {
        require(layers.isNotEmpty()) { "a stack always has a layer" }
        require(layers.map { it.id }.toSet().size == layers.size) { "layer ids are unique" }
        require(layers.any { it.id == activeId }) { "the active layer is in the stack" }
    }

    val active: LayerState get() = layers.first { it.id == activeId }
    val activeIndex: Int get() = indexOf(activeId)
    val size: Int get() = layers.size

    fun indexOf(id: String): Int = layers.indexOfFirst { it.id == id }
    operator fun get(id: String): LayerState? = layers.firstOrNull { it.id == id }

    /** Paint on [id]. An id not in the stack changes nothing. */
    fun select(id: String): LayerStack = if (this[id] == null) this else copy(activeId = id)

    /** A new empty layer directly ABOVE the active one, and it becomes the active one (what every painting app does). */
    fun add(id: String, name: String = nextName()): LayerStack {
        require(this[id] == null) { "layer id $id is taken" }
        val at = activeIndex + 1
        val list = layers.toMutableList()
        list.add(at, LayerState(id, name))
        return LayerStack(list, id)
    }

    /** A copy of [sourceId] (its settings; the engine copies its pixels) directly above it, active. Null if no such layer. */
    fun duplicate(sourceId: String, id: String): LayerStack? {
        require(this[id] == null) { "layer id $id is taken" }
        val src = this[sourceId] ?: return null
        val list = layers.toMutableList()
        list.add(indexOf(sourceId) + 1, src.copy(id = id, name = copyName(src.name)))
        return LayerStack(list, id)
    }

    /**
     * Without [id]. Refused (null) for the last layer: a drawing always has somewhere to paint. The layer below takes the
     * brush; with none below, the one that was above.
     */
    fun delete(id: String): LayerStack? {
        val i = indexOf(id)
        if (i < 0 || layers.size == 1) return null
        val list = layers.toMutableList()
        list.removeAt(i)
        val active = if (id != activeId) activeId else list[(i - 1).coerceAtLeast(0)].id
        return LayerStack(list, active).normalized()
    }

    /** [id] moved to position [toIndex] (0 = bottom), clamped. The active layer stays active. */
    fun move(id: String, toIndex: Int): LayerStack {
        val i = indexOf(id)
        if (i < 0) return this
        val list = layers.toMutableList()
        val item = list.removeAt(i)
        list.add(toIndex.coerceIn(0, list.size), item)
        return copy(layers = list).normalized()
    }

    fun withOpacity(id: String, opacity: Float): LayerStack =
        update(id) { it.copy(opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else it.opacity) }

    fun withBlend(id: String, blend: BlendMode): LayerStack = update(id) { it.copy(blend = blend) }
    fun withVisible(id: String, visible: Boolean): LayerStack = update(id) { it.copy(visible = visible) }

    /** With or without a mask. Removing one is the engine's to record (its pixels go into the undo step). */
    fun withMask(id: String, on: Boolean): LayerStack = update(id) { it.copy(hasMask = on) }

    /** Clipped or not. The bottom layer has nothing to clip to, so asking to clip it changes nothing. */
    fun withClip(id: String, on: Boolean): LayerStack = if (on && indexOf(id) == 0) this else update(id) { it.copy(clip = on) }

    /**
     * The bottom layer is never clipped: a file with a clipped bottom layer is refused at open (DocOps.validate), so a
     * move or a delete that would leave one there unclips it instead of making a drawing that cannot be saved.
     */
    fun normalized(): LayerStack = if (layers.first().clip) copy(layers = listOf(layers.first().copy(clip = false)) + layers.drop(1)) else this

    /** A new name, trimmed; a blank one keeps the old name (a layer with no name cannot be found in a list). */
    fun rename(id: String, name: String): LayerStack {
        val clean = name.trim().take(MAX_NAME)
        return if (clean.isEmpty()) this else update(id) { it.copy(name = clean) }
    }

    /**
     * [target] with every layer this stack also has keeping ITS visibility — what undo applies: hiding a layer changes no
     * pixel and is not an undo step (JB-2.04 Decision 7), so undoing an opacity change must not bring a hidden layer back.
     * The active layer stays where it is when it survives; otherwise it is [target]'s.
     */
    fun restoring(target: LayerStack): LayerStack {
        val list = target.layers.map { t -> this[t.id]?.let { t.copy(visible = it.visible) } ?: t }
        val active = if (list.any { it.id == activeId }) activeId else target.activeId
        return LayerStack(list, active)
    }

    /** "Layer N", one more than the highest N already used, so names never repeat as layers come and go. */
    fun nextName(): String {
        val n = layers.mapNotNull { NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "Layer ${n + 1}"
    }

    /** An id not in the stack: "layer-N". */
    fun freshId(): String {
        var n = layers.size + 1
        while (this["layer-$n"] != null) n++
        return "layer-$n"
    }

    private fun update(id: String, f: (LayerState) -> LayerState): LayerStack =
        copy(layers = layers.map { if (it.id == id) f(it) else it })

    companion object {
        const val MAX_NAME = 40
        private val NAME = Regex("Layer (\\d+)")

        /** "Hair" → "Hair copy", "Hair copy" → "Hair copy 2", "Hair copy 2" → "Hair copy 3". */
        fun copyName(name: String): String {
            val m = Regex("^(.*) copy(?: (\\d+))?$").matchEntire(name)
            return if (m == null) "$name copy".take(MAX_NAME)
            else "${m.groupValues[1]} copy ${(m.groupValues[2].toIntOrNull() ?: 1) + 1}".take(MAX_NAME)
        }

        /** A new drawing: one layer. */
        fun single(id: String = "layer-1", name: String = "Layer 1"): LayerStack = LayerStack(listOf(LayerState(id, name)), id)
    }
}
