package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.SlabOps
import cc.joycreator.joybrush.core.doc.SlabRef
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules

/**
 * JB-5.20a: what must hold of a cel's line records, in words. [DocOps.validate] checks what `document.json` can say on
 * its own (slab keys, operators, version); these need the RECORDS (which live in `strokes.jbs`), so the archive calls
 * them on write and on read. Pure: no archive, no GL.
 *
 * Every rule returns one sentence per problem, never a crash, and the whole list, like `DocOps.validate`.
 */
object LineRules {

    /** Source-kind structure only; never derive a historical domain from today's board geometry. */
    fun sourceProblems(documentVersion: Int, layer: Layer, cel: Cel, records: List<StrokeRecord>): List<String> {
        val out = ArrayList<String>()
        for(record in records) {
            val captured = record.capturedPhysicalSource
            if(captured != null && documentVersion < 11) out += "Captured physical source requires document version11"
            if(layer.mask?.id == cel.id) out += "A mask cannot own recorded lines"
            if(documentVersion >= 11 && layer.regions.isNotEmpty() && captured == null)
                out += "Regional line ${record.id} needs its captured physical source"
            out += CapturedStrokeRules.problems(record)
        }
        return out
    }

    /**
     * Structural rules for one cel's [records] against its [cel] and the ids of the document's frozen brush copies:
     *
     * - record ids are unique within the cel (the picker does not rely on it, JB-5.02, but edits and undo address a line
     *   by id);
     * - every record has a `seq` of 1 or more, and no two records share one (D1: time order is the only order);
     * - no slab has the `seq` of a line that still exists (a baked line's slab takes its seq and the line goes);
     * - a `groupId`, when there is one, is not blank (a group of ONE member is legal: the others were deleted);
     * - every record's brush id resolves to a frozen copy in the drawing (D5);
     * - a cel with records says where they are ([Cel.strokesFile]).
     */
    fun problems(layerId: String, cel: Cel, records: List<StrokeRecord>, frozenBrushIds: Set<String>): List<String> {
        val out = ArrayList<String>()
        val where = "cel \"${cel.id}\" of layer \"$layerId\""
        if (records.isNotEmpty() && cel.strokesFile == null) out += "$where has ${records.size} lines and does not say where they are kept"
        if (records.isEmpty() && cel.looks.isNotEmpty()) out += "$where keeps looks for ${cel.looks.size} tiles and has no lines"

        val ids = HashSet<String>()
        val repeated = LinkedHashSet<String>()
        for (r in records) if (!ids.add(r.id)) repeated.add(r.id)
        for (id in repeated) out += "$where has two lines with the id \"$id\""

        val bySeq = HashMap<Long, String>()
        val sharedSeq = LinkedHashMap<Long, MutableList<String>>()
        for (r in records) {
            if (r.seq < 1) out += "line \"${r.id}\" of $where has seq ${r.seq} (a line in a cel has a seq of 1 or more)"
            val first = bySeq[r.seq]
            if (first == null) bySeq[r.seq] = r.id
            if (first != null && r.seq >= 1) sharedSeq.getOrPut(r.seq) { mutableListOf(first) }.add(r.id)
        }
        for ((seq, who) in sharedSeq) out += "lines ${who.joinToString(", ") { "\"$it\"" }} of $where all have seq $seq (each line has its own)"

        for (s in cel.slabs) bySeq[s.seq]?.let { line ->
            if (s.seq >= 1) out += "a slab of $where on tile \"${s.key}\" has seq ${s.seq}, which is line \"$line\"'s (a seq belongs to one thing)"
        }

        for (r in records) {
            val g = r.groupId
            if (g != null && g.isBlank()) out += "line \"${r.id}\" of $where is in a group with no name"
            if (r.brushId !in frozenBrushIds) {
                out += "line \"${r.id}\" of $where is drawn with brush \"${r.brushId}\", and the drawing keeps no copy of that brush"
            }
        }
        return out
    }

    /**
     * D3: a look is saved only for a tile some line crosses. This is the EXACT test, by replaying the cel's lines once
     * ([CelComposer.draw]); it is as costly as drawing the cel, so the archive runs it when SAVING and not when opening
     * (opening never replays, D3). A line that cannot be replayed (its brush is not one a CPU replay draws; see
     * [CelComposer.Drawn.refusals]) makes the answer unknowable, so the cel is passed.
     *
     * @param brushLookup resolves a brush id to its frozen copy.
     */
    fun lookProblems(layerId: String, cel: Cel, records: List<StrokeRecord>, brushLookup: (String) -> BrushPreset?): List<String> {
        // A generic CPU replay refusal is not geometric proof. This new kind must never use
        // the legacy GPU-only/unknown shortcut, including a cel whose saved look index is empty.
        if(records.any { it.capturedPhysicalSource != null })
            return listOf("Captured physical source needs a clipping-aware saved-look validator")
        if (cel.looks.isEmpty()) return emptyList()
        val where = "cel \"${cel.id}\" of layer \"$layerId\""
        if (records.isEmpty()) return emptyList() // [problems] says a look with no lines is wrong
        val drawn = try {
            CelComposer.draw(CelComposer.Items(records.map { CelComposer.Line(it.seq, it) }, emptyMap()), brushLookup)
        } catch (e: IllegalArgumentException) {
            return emptyList() // the seq rules above already said what is wrong; do not say it twice
        }
        if (drawn.refusals.isNotEmpty()) return emptyList()
        val crossed = CelComposer.tiles(drawn)
        val out = ArrayList<String>()
        for (k in cel.looks) {
            if (!DocOps.isTileKey(k)) continue // DocOps.validate said so
            val (tx, ty) = k.split('_', limit = 2).let { it[0].toInt() to it[1].toInt() }
            if (Tiles.key(tx, ty) !in crossed) out += "$where keeps a look for tile \"$k\", and no line crosses that tile"
        }
        return out
    }
}

/**
 * JB-5.20a: how a cel's slab stacks sit in the document ([Cel.tiles], [Cel.slabs]) and in [CelComposer]'s terms, both
 * ways, so the engine (5.20d) and export (5.20f) can load and save a cel without writing the layout rules again.
 */
object CelLayout {

    /** The implicit `seq` of a bottom slab the document does not list: before any line. */
    const val BASE_SEQ = 0L

    /**
     * Every tile's full slab stack in `seq` order, from the document's lists: a listed tile has its [SlabRef]s, any
     * other tile of [Cel.tiles] has one plain bottom slab. The first of a stack is the tile's `.rgba` file.
     */
    fun stacks(cel: Cel): Map<String, List<SlabRef>> {
        val listed = cel.slabs.groupBy { it.key }.mapValues { (_, v) -> v.sortedBy { it.seq } }
        val out = LinkedHashMap<String, List<SlabRef>>()
        for (key in cel.tiles) out[key] = listed[key] ?: listOf(SlabRef(key, BASE_SEQ, SlabOps.NORMAL))
        return out
    }

    /**
     * The composer's view of a cel: its lines (their own `seq`s) and its slabs, with each slab's bytes from [bytesOf]
     * (`key`, then the slab's ref, `bottom` = it is the tile's `.rgba` file). Every declared slab must have bytes:
     * a missing bottom or upper slab is refused by tile and seq, rather than compositing a plausible incomplete tile.
     */
    fun items(cel: Cel, records: List<StrokeRecord>, bytesOf: (String, SlabRef, Boolean) -> ByteArray?): CelComposer.Items {
        val slabs = HashMap<Long, List<CelComposer.Slab>>()
        for ((key, stack) in stacks(cel)) {
            val (tx, ty) = key.split('_', limit = 2).let { it[0].toInt() to it[1].toInt() }
            slabs[Tiles.key(tx, ty)] = stack.mapIndexed { i, ref ->
                val bytes = requireNotNull(bytesOf(key, ref, i == 0)) {
                    "cel \"${cel.id}\" is missing ${if (i == 0) "bottom" else "upper"} slab bytes on tile \"$key\" at seq ${ref.seq}"
                }
                CelComposer.Slab(ref.seq, bytes, ref.op)
            }
        }
        return CelComposer.Items(records.map { CelComposer.Line(it.seq, it) }, slabs)
    }

    /**
     * The other direction: the [Cel.tiles] and [Cel.slabs] that describe [d]'s slabs. A tile with ONE normal slab that
     * no line reaches is written as a plain tile, whatever `seq` the engine gave it (its `seq` only matters against a
     * line that crosses it), so a cel of pure pixels describes itself exactly as it did before lines existed. Every other
     * tile lists its whole stack.
     */
    fun describe(d: CelComposer.Drawn): Pair<List<String>, List<SlabRef>> {
        val tiles = ArrayList<String>()
        val refs = ArrayList<SlabRef>()
        for (tileKey in d.items.slabs.keys.sortedWith(compareBy({ Tiles.tx(it) }, { Tiles.ty(it) }))) {
            val stack = d.items.slabs.getValue(tileKey)
            val key = DocOps.key(Tiles.tx(tileKey), Tiles.ty(tileKey))
            tiles += key
            // A refused (unreplayable) line has no known reach, so with one in the cel nothing is called plain.
            val plain = stack.size == 1 && stack[0].op == SlabOps.NORMAL && d.refusals.isEmpty() &&
                CelComposer.linesOn(d, tileKey).isEmpty()
            if (!plain) for (s in stack) refs += SlabRef(key, s.seq, s.op)
        }
        return tiles to refs
    }
}
