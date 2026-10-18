    /**
     * Rasterize Down (editable lines v1.2 H3): each chosen line becomes a pixel slab at its own seq and the record goes, as
     * ONE undo step. The tiles do not change: a slab composes to exactly what its line drew. The units rendered here are
     * taken over by the slabs, so nothing is copied.
     */
    private fun bakeLines(ids: Set<LineAddress>) {
        val byPlane = LinkedHashMap<LinePlane, MutableList<StrokeRecord>>()
        for (a in ids) {
            val layer = layers[a.layerId] ?: throw IllegalArgumentException("That layer is gone")
            require(!layer.isMask) { "Masks hold no lines" }
            val plane = LinePlane(a.layerId, a.celId ?: layer.sharedCel)
            val record = nativeLines.record(plane, a.recordId) ?: throw IllegalArgumentException("That line is gone (undone or deleted)")
            byPlane.getOrPut(plane) { ArrayList() } += record
        }
        if (byPlane.isEmpty()) return
        val units = ArrayList<RecordedLineUnit>()
        var committed = false
        try {
            var items = LineItems.Changes.NONE
            for ((plane, baked) in byPlane) {
                val layer = layers.getValue(plane.layerId)
                val rendered = HashMap<String, LineBake.Unit>()
                for (r in baked) {
                    val brush = nativeLines.brush(r.brushId) ?: throw IllegalArgumentException("This line's brush is missing from the drawing")
                    val unit = prepareRecordedLine(layer.id, r, brush).also { units += it }
                    rendered[r.id] = LineBake.Unit(unit.op, unit.tiles)
                }
                items += LineBake.plan(nativeLines, plane, layer.id, engineCel(layer, plane), baked.sortedBy { it.seq }, rendered)
            }
            applyItemChanges(items.slabs, items.lines, forward = true)
            try {
                undo.push(UndoLog.Step(emptyList(), lines = items.lines, slabs = items.slabs))
            } catch (e: Throwable) {
                applyItemChanges(items.slabs, items.lines, forward = false)
                throw e
            }
            committed = true
            // The slabs own these textures now; the working-tile ledger must not count them as staged units any more.
            for (u in units) for (tex in u.tiles.values) recordedWorkingTiles.forget(physicalContextEpoch, tex)
        } finally {
            if (!committed) units.forEach(::discardRecordedLine)
        }
    }
