    // ── editable lines v1 (tasks/joybrush/specs/NATIVE_LINES_V1_20261016.md; v1.2 adds slabs) ──

    /** The planes' items (lines, pixel slabs) and bases beside the ordinary tiles; the tiles stay the pixel truth. */
    internal val nativeLines = NativeLines()

    /** A live stroke's record, handed over before [endStroke]; consumed by the commit that makes its step. */
    private class PendingLine(val record: StrokeRecord, val brushes: Map<String, BrushPreset>)
    private var pendingLine: PendingLine? = null

    /** The stroke being painted becomes an editable line when it commits ([record]'s seq is assigned then). */
    fun setPendingLine(record: StrokeRecord?, newBrushes: Map<String, BrushPreset> = emptyMap()) {
        pendingLine = record?.let { PendingLine(it, newBrushes) }
    }

    fun lineSnapshot(): LineSnapshot = nativeLines.snapshot()

    /** A layer's plane in line terms: the engine's celId, with the shared plane under its document cel id once known. */
    private fun linePlane(layer: Layer, celId: String?): LinePlane = LinePlane(layer.id, celId ?: layer.sharedCel)

    /** The plane a recorded change names (the layer may not exist yet while an undo is putting it back). */
    private fun changePlane(layerId: String, celId: String?): LinePlane = LinePlane(layerId, celId ?: layers[layerId]?.sharedCel)

    /** The engine's own celId for a line plane (null = the layer's tiles while it has no document cel yet). */
    private fun engineCel(layer: Layer, plane: LinePlane): String? = if (layer.sharedCel == null) null else plane.celId

    /** Where a plane owns pixels inside [key]; null = the whole tile (no regions). */
    private fun lineClips(layer: Layer, plane: LinePlane, key: Long): List<RegionTileRect>? {
        val plan = layer.plan ?: return null
        return plan.tileSlices(key).filter { it.plane.celId == plane.celId }.map { it.rect }
    }

    private fun copyOfTile(source: Int): Int {
        val copy = newLayerTile()
        initializeTile(copy, source, false)
        return copy
    }

    /**
     * One tile's items in time order, ready for [composeRecordedTile]: the [base] (seq 0), the given [lines] (each as its
     * rendered unit on this tile) and every slab the plane has on the tile, all clipped to the plane's own regions.
     */
    private fun tileItems(layer: Layer, plane: LinePlane, key: Long, base: Int?, lines: List<StrokeRecord>,
                          unitOf: (StrokeRecord) -> RecordedLineUnit?, withSlabs: Boolean = true): List<OrderedRecordedUnit> {
        val items = ArrayList<OrderedRecordedUnit>()
        base?.let { items += OrderedRecordedUnit(0L, it, "normal") }
        val clips = lineClips(layer, plane, key)
        for (r in lines) {
            val unit = unitOf(r) ?: continue
            val tex = unit.tiles[key] ?: continue
            items += OrderedRecordedUnit(r.seq, tex, unit.op, clips)
        }
        if (withSlabs) for (s in nativeLines.slabsAt(plane, key)) items += OrderedRecordedUnit(s.seq, s.texture, s.op, clips)
        items.sortBy { it.seq }
        return items
    }

    /**
     * [endStroke]'s part for a stroke that is a line: one record per plane it wrote (each keeps the whole raw recipe; the
     * plane's region clips it), seq on top, and the bases of tiles it is first to cross. Returns the step's slab and line
     * changes; the bookkeeping is updated here, in the same GL turn as the tiles.
     */
    private fun commitPendingLine(layer: Layer, written: List<UndoLog.TileChange<Int>>): Pair<List<UndoLog.SlabChange<Int>>, List<UndoLog.LineChange>> {
        val line = pendingLine ?: return emptyList<UndoLog.SlabChange<Int>>() to emptyList()
        pendingLine = null
        if (layer.isMask || written.isEmpty()) return emptyList<UndoLog.SlabChange<Int>>() to emptyList()
        nativeLines.addBrushes(line.brushes)
        val slabs = ArrayList<UndoLog.SlabChange<Int>>()
        val lines = ArrayList<UndoLog.LineChange>()
        for ((celId, planeWrites) in written.groupBy { it.celId }) {
            val plane = linePlane(layer, celId)
            var id = line.record.id
            while (nativeLines.record(plane, id) != null) id = line.record.id + "-" + (1..99999).random()
            val record = line.record.copy(id = id, seq = nativeLines.nextSeq(plane))
            val footprint = nativeLines.estimate(record) + planeWrites.map { it.key }
            val before = planeWrites.associate { it.key to it.before }
            for (key in footprint) {
                if (nativeLines.isCrossed(plane, key)) continue   // its base is already kept
                val source = if (key in before) before[key] else planeTiles(layer, celId)[key]
                val copy = source?.let(::copyOfTile) ?: continue   // nothing under it: the base is transparent
                slabs += UndoLog.SlabChange(layer.id, celId, key, 0L, null, UndoLog.SlabValue(copy, "normal"))
                nativeLines.setBase(plane, key, copy)
            }
            nativeLines.put(plane, id, record, footprint)
            lines += UndoLog.LineChange(layer.id, celId, id, null, CelComposer.Line(record.seq, record))
        }
        return slabs to lines
    }

    /**
     * Undo ([forward] false) or redo of a step's lines, slabs and bases. The step's tiles are put back by the caller, so
     * nothing is replayed. Removals go first: a baked line's slab takes the seq of the line that goes in the same step.
     */
    private fun applyNativeLineStep(s: UndoLog.Step<Int>, forward: Boolean) = applyItemChanges(s.slabs, s.lines, forward)

    private fun applyItemChanges(slabs: List<UndoLog.SlabChange<Int>>, lines: List<UndoLog.LineChange>, forward: Boolean) {
        fun lineTarget(c: UndoLog.LineChange) = if (forward) c.after else c.before
        fun slabTarget(c: UndoLog.SlabChange<Int>) = if (forward) c.after else c.before
        for (c in lines) if (lineTarget(c) == null) nativeLines.put(changePlane(c.layerId, c.celId), c.id, null)
        for (c in slabs) if (slabTarget(c) == null) setItemTexture(c, null)
        for (c in slabs) slabTarget(c)?.let { setItemTexture(c, it) }
        for (c in lines) lineTarget(c)?.let { nativeLines.put(changePlane(c.layerId, c.celId), c.id, it.record) }
    }

    /** One slab change on NativeLines: seq 0 is the tile's base, anything above is a slab of the plane. */
    private fun setItemTexture(c: UndoLog.SlabChange<Int>, value: UndoLog.SlabValue<Int>?) {
        val plane = changePlane(c.layerId, c.celId)
        if (c.seq == 0L) nativeLines.setBase(plane, c.key, value?.texture)
        else nativeLines.setSlab(plane, c.seq, c.key, value?.texture, value?.op ?: "normal")
    }

    /** Clear on a layer with lines or slabs: its pixels, its items and their bases go together, as ONE step (nothing comes back). */
    private fun clearLayerWithLines(layer: Layer) {
        settleWatercolourFade()
        val tiles = ArrayList<UndoLog.TileChange<Int>>()
        val slabs = ArrayList<UndoLog.SlabChange<Int>>()
        val lines = ArrayList<UndoLog.LineChange>()
        for (plane in nativeLines.itemPlanes().filter { it.layerId == layer.id }) {
            val cel = engineCel(layer, plane)
            ownedItemChanges(layer, plane, cel, slabs, lines)
        }
        val planes: Map<String?, HashMap<Long, Int>> =
            if (layer.sharedCel == null) mapOf(null to layer.tiles) else layer.cels.mapKeys { it.key }
        for ((cel, map) in planes) for ((key, tex) in map) tiles += UndoLog.TileChange(layer.id, key, tex, null, cel)
        for (c in tiles) put(c.layerId, c.key, null, c.celId)
        applyItemChanges(slabs, lines, forward = true)
        undo.push(UndoLog.Step(tiles, lines = lines, slabs = slabs))
    }

    /**
     * The removal of everything a plane holds (its records, bases and slabs) as step changes; nothing is applied. The
     * step that carries them owns the textures from then on, and undo puts the plane back exactly.
     */
    private fun ownedItemChanges(layer: Layer, plane: LinePlane, cel: String?,
                                 slabs: MutableList<UndoLog.SlabChange<Int>>, lines: MutableList<UndoLog.LineChange>) {
        for (r in nativeLines.lines(plane)) lines += UndoLog.LineChange(layer.id, cel, r.id, CelComposer.Line(r.seq, r), null)
        for ((key, tex) in nativeLines.bases(plane).toSortedMap()) {
            slabs += UndoLog.SlabChange(layer.id, cel, key, 0L, UndoLog.SlabValue(tex, "normal"), null)
        }
        for (seq in nativeLines.slabSeqs(plane)) {
            val slab = nativeLines.slab(plane, seq) ?: continue
            for ((key, tex) in slab.tiles) slabs += UndoLog.SlabChange(layer.id, cel, key, seq, UndoLog.SlabValue(tex, slab.op), null)
        }
    }

    /** Does the layer hold editable lines or pixel slabs on any plane? (Other strokes there become slabs.) */
    fun layerHasLines(layerId: String): Boolean = nativeLines.hasItemsOnLayer(layerId)

    /** What a save keeps of the lines: each live plane's records, the tiles they cross, and those tiles' bases. */
    class LineSave(val records: Map<LinePlane, List<StrokeRecord>>, val crossed: Map<LinePlane, Set<Long>>,
                   val bases: Map<Pair<LinePlane, Long>, ByteArray>, val brushes: Map<String, BrushPreset>)

    /** GL thread. A deleted layer's or frame's lines are history only and are not saved. */
    fun lineSave(): LineSave {
        settleWatercolourFade()
        settleLift()
        val records = LinkedHashMap<LinePlane, List<StrokeRecord>>()
        val crossed = LinkedHashMap<LinePlane, Set<Long>>()
        val bases = LinkedHashMap<Pair<LinePlane, Long>, ByteArray>()
        for (plane in nativeLines.itemPlanes()) {
            val layer = layers[plane.layerId] ?: continue
            if (layer.sharedCel != null && plane.celId !in layer.cels) continue
            // Pixel slabs reach disk with the v1.2 save (H13); until then a save must not silently drop them.
            if (nativeLines.hasSlabs(plane)) throw IllegalStateException("Pixel slabs can't be saved yet")
            val lines = nativeLines.lines(plane)
            records[plane] = lines
            val keys = nativeLines.crossedKeys(plane)
            crossed[plane] = keys
            for (key in keys) nativeLines.base(plane, key)?.let { bases[plane to key] = readTexture(it) }
        }
        return LineSave(records, crossed, bases, nativeLines.brushes())
    }

    /** GL thread, after a drawing's tiles (its looks) are in: its lines, their brushes and the bases under them. */
    fun loadLines(records: Map<LinePlane, List<StrokeRecord>>, bases: Map<Pair<LinePlane, Long>, ByteArray>,
                  brushes: Map<String, BrushPreset>) {
        nativeLines.addBrushes(brushes)
        for ((plane, list) in records) for (r in list.sortedBy { it.seq }) nativeLines.put(plane, r.id, r)
        for ((at, bytes) in bases) nativeLines.setBase(at.first, at.second, uploadTile(newLayerTile(), bytes))
    }

    /**
     * One Select edit as ONE undo step: the affected tiles (old and new footprints) are re-rendered as
     * base ∘ every item crossing them (lines from the frozen brushes, slabs as they are), in seq order; bases follow the
     * crossing rule.
     */
    internal fun editLines(edit: LineEdit) {
        requireLocalPaint()
        settleLift()
        settleWatercolourFade()
        check(!strokeInProgress && !wcFading && !mediaStrokeInProgress) { "Finish the stroke before editing lines" }
        check(framePreviews.isEmpty()) { "Stop playback before editing lines" }
        require(tileBoardId == null) { "Editable lines don't work on a tiled board yet" }
        val brushes = when (edit) { is LineEdit.Replace -> edit.brushes; is LineEdit.Add -> edit.brushes; else -> emptyMap() }
        for ((id, preset) in brushes) {
            val known = nativeLines.brush(id)
            require(known == null || known == preset) { "Frozen brush \"$id\" already means another brush" }
        }
        fun brushOf(r: StrokeRecord): BrushPreset =
            brushes[r.brushId] ?: nativeLines.brush(r.brushId) ?: throw IllegalArgumentException("This line's brush is missing from the drawing")
        // The next list of every plane the edit touches.
        val next = LinkedHashMap<LinePlane, MutableList<StrokeRecord>>()
        val nextSeq = HashMap<LinePlane, Long>()
        fun list(p: LinePlane) = next.getOrPut(p) { nativeLines.lines(p).toMutableList() }
        fun planeOf(a: cc.joycreator.joybrush.core.stroke.LineAddress): LinePlane {
            val layer = layers[a.layerId] ?: throw IllegalArgumentException("That layer is gone")
            require(!layer.isMask) { "Masks hold no lines" }
            return LinePlane(a.layerId, a.celId ?: layer.sharedCel)
        }
        when (edit) {
            is LineEdit.Replace -> for (a in edit.after) {
                val p = planeOf(a.address); val l = list(p)
                val i = l.indexOfFirst { it.id == a.address.recordId }
                require(i >= 0) { "That line is gone (undone or deleted)" }
                l[i] = a.record.copy(seq = l[i].seq)
            }
            is LineEdit.Add -> for (a in edit.records) {
                val p = planeOf(a.address); val l = list(p)
                require(l.none { it.id == a.address.recordId }) { "A line with that name is already there" }
                // One past every item of the plane (a slab holds a seq too), and past the lines this edit already added.
                val seq = maxOf(nextSeq[p] ?: 0L, nativeLines.nextSeq(p))
                nextSeq[p] = seq + 1L
                l += a.record.copy(seq = seq)
            }
            is LineEdit.Delete -> for (a in edit.ids) {
                val p = planeOf(a); val l = list(p)
                require(l.removeAll { it.id == a.recordId }) { "That line is gone (undone or deleted)" }
            }
        }
        if (brushes.isNotEmpty()) nativeLines.addBrushes(brushes)
        val tileChanges = ArrayList<UndoLog.TileChange<Int>>()
        val slabChanges = ArrayList<UndoLog.SlabChange<Int>>()
        val lineChanges = ArrayList<UndoLog.LineChange>()
        val units = ArrayList<RecordedLineUnit>()
        val made = ArrayList<Int>()          // fresh textures, recycled if the edit fails
        val baseUpdates = ArrayList<Triple<LinePlane, Long, Int?>>()
        val newFootprints = HashMap<Pair<LinePlane, String>, Set<Long>>()   // fresh lines: what they may draw on
        try {
            for ((plane, newList) in next) {
                val layer = layers.getValue(plane.layerId)
                val cel = engineCel(layer, plane)
                val oldList = nativeLines.lines(plane)
                newList.sortBy { it.seq }
                val oldById = oldList.associateBy { it.id }
                val newById = newList.associateBy { it.id }
                val gone = oldList.filter { o -> newById[o.id] != o }
                val fresh = newList.filter { n -> oldById[n.id] != n }
                if (gone.isEmpty() && fresh.isEmpty()) continue
                // Render every line of the new list that reaches an affected tile; fresh lines first, they widen it.
                val affected = HashSet<Long>()
                gone.forEach { affected += nativeLines.footprint(plane, it.id) }
                val rendered = HashMap<String, RecordedLineUnit>()
                val freshFootprint = HashMap<String, Set<Long>>()
                for (r in fresh) {
                    val unit = prepareRecordedLine(layer.id, r, brushOf(r)).also { units += it }
                    rendered[r.id] = unit
                    val footprint = nativeLines.estimate(r) + unit.tiles.keys
                    freshFootprint[r.id] = footprint
                    newFootprints[plane to r.id] = footprint
                    affected += footprint
                }
                for (key in affected) for (r in nativeLines.linesAt(plane, key)) {
                    if (newById[r.id] != r || r.id in rendered) continue   // gone or replaced: not drawn any more
                    rendered[r.id] = prepareRecordedLine(layer.id, r, brushOf(r)).also { units += it }
                }
                val tiles = planeTiles(layer, cel)
                for (key in affected) {
                    val before = nativeLines.linesAt(plane, key)
                    val crossedBefore = nativeLines.isCrossed(plane, key)
                    // The lines that will cross it: the ones standing as they are, and the fresh ones that reach it.
                    val after = (before.filter { newById[it.id] == it } + fresh.filter { key in freshFootprint.getValue(it.id) }).sortedBy { it.seq }
                    val crossedAfter = after.isNotEmpty() || nativeLines.slabsAt(plane, key).isNotEmpty()
                    if (!crossedBefore && !crossedAfter) continue
                    val current = tiles[key]
                    var base = nativeLines.base(plane, key)
                    if (!crossedBefore) {
                        // First crossed now: the tile as it is IS its base.
                        base = current?.let { copyOfTile(it).also(made::add) }
                        if (base != null) {
                            slabChanges += UndoLog.SlabChange(layer.id, cel, key, 0L, null, UndoLog.SlabValue(base, "normal"))
                            baseUpdates += Triple(plane, key, base)
                        }
                    }
                    val out: Int? = if (crossedAfter) {
                        composeRecordedTile(tileItems(layer, plane, key, base, after) { rendered[it.id] })?.also(made::add)
                    } else {
                        // No item crosses it any more: the tile is its base again, and the base goes.
                        val kept = base
                        if (kept != null) {
                            slabChanges += UndoLog.SlabChange(layer.id, cel, key, 0L, UndoLog.SlabValue(kept, "normal"), null)
                            baseUpdates += Triple(plane, key, null)
                        }
                        kept?.let { copyOfTile(it).also(made::add) }
                    }
                    if (out != current) tileChanges += UndoLog.TileChange(layer.id, key, current, out, cel)
                }
                for (o in gone) if (newById[o.id] == null) lineChanges += UndoLog.LineChange(layer.id, cel, o.id, CelComposer.Line(o.seq, o), null)
                for (n in fresh) {
                    val o = oldById[n.id]
                    lineChanges += UndoLog.LineChange(layer.id, cel, n.id, o?.let { CelComposer.Line(it.seq, it) }, CelComposer.Line(n.seq, n))
                }
            }
        } catch (e: Throwable) {
            made.forEach(::recycleLayerTex)
            throw e
        } finally {
            units.forEach(::discardRecordedLine)
        }
        if (lineChanges.isEmpty()) { made.forEach(::recycleLayerTex); return }
        // Commit: pixels, bases and lines in the same GL turn, then ONE step.
        for (c in tileChanges) put(c.layerId, c.key, c.after, c.celId)
        for ((plane, key, tex) in baseUpdates) nativeLines.setBase(plane, key, tex)
        for (c in lineChanges) {
            val plane = changePlane(c.layerId, c.celId)
            nativeLines.put(plane, c.id, c.after?.record, newFootprints[plane to c.id])
        }
        undo.push(UndoLog.Step(tileChanges, lines = lineChanges, slabs = slabChanges))
    }
