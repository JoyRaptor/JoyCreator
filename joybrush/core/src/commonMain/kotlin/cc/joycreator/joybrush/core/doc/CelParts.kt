package cc.joycreator.joybrush.core.doc

/** One disjoint piece of a logical drawing, addressed by its actual writable physical cel. */
data class CelFragment(val celId: String, val rect: RectPx)

/** Component links are a bounded acyclic graph. All rectangles and paint stay in world space. */
object CelParts {
    const val MAX_DEPTH = 64
    const val MAX_FRAGMENTS = 100_000

    fun errors(layer: Layer): List<String> {
        val errors=ArrayList<String>(); val cels=layer.cels.associateBy { it.id }
        val state=HashMap<String,Int>(); val depths=HashMap<String,Int>()
        if(layer.mask?.parts?.isNotEmpty()==true) errors += "A mask cannot contain linked drawings"
        if(layer.cels.any { it.id==layer.sharedCelId && it.parts.isNotEmpty() }) errors += "The shared canvas cannot contain linked drawing pieces"
        if(layer.cels.sumOf { it.parts.size.toLong() }>MAX_FRAGMENTS) return listOf("Too many linked drawing pieces in \"${layer.name}\"")
        fun visit(id:String, level:Int=0): Int {
            if(level>MAX_DEPTH) { errors += "Linked drawings are too deeply nested"; return MAX_DEPTH+1 }
            if(state[id]==1) { errors += "Circular linked drawings in \"${layer.name}\""; return MAX_DEPTH+1 }
            if(state[id]==2) return depths[id] ?: 0
            state[id]=1
            var depth=0
            val c=cels[id] ?: return 0
            for((i,p) in c.parts.withIndex()) {
                if(p.celId !in cels || p.celId == layer.sharedCelId) errors += "A linked drawing is missing or borrows the shared canvas in \"${layer.name}\""
                val r=p.rect
                if(r.w<=0 || r.h<=0 || r.x.toLong()+r.w>Int.MAX_VALUE || r.y.toLong()+r.h>Int.MAX_VALUE) errors += "Invalid linked drawing bounds in \"${layer.name}\""
                if(c.parts.take(i).any { intersection(it.rect,r)!=null }) errors += "Linked drawing pieces overlap in \"${layer.name}\""
                if(p.celId in cels) depth=maxOf(depth,1+visit(p.celId,level+1))
            }
            state[id]=2; depths[id]=depth
            if(depth>MAX_DEPTH) errors += "Linked drawings are too deeply nested in \"${layer.name}\""
            return depth
        }
        for(c in layer.cels) visit(c.id)
        return errors.distinct()
    }

    fun reachable(layer: Layer, roots: Collection<String>): Set<String> {
        val cels = layer.cels.associateBy { it.id }
        val found = LinkedHashSet<String>()
        val pending = roots.toMutableList()
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.lastIndex)
            if (!found.add(id)) continue
            val cel = cels[id] ?: throw DocException("A linked drawing is missing from \"${layer.name}\"")
            pending.addAll(cel.parts.map { it.celId })
        }
        return found
    }

    /** Includes the containing cel's physical fallback in the complement of its parts. */
    fun resolve(layer: Layer, root: String, crop: RectPx): List<CelFragment> {
        val cels = layer.cels.associateBy { it.id }
        val result = ArrayList<CelFragment>()
        val path = HashSet<String>()
        fun visit(id: String, area: RectPx, depth: Int) {
            if (depth > MAX_DEPTH || !path.add(id)) throw DocException("A drawing has circular or excessively deep component links")
            val cel = cels[id] ?: throw DocException("A linked drawing is missing from \"${layer.name}\"")
            var outside = listOf(area)
            for (part in cel.parts) {
                val inside = intersection(area, part.rect) ?: continue
                visit(part.celId, inside, depth + 1)
                outside = outside.flatMap { subtract(it, inside) }
            }
            for (rect in outside) result += CelFragment(id, rect)
            if (result.size > MAX_FRAGMENTS) throw DocException("This drawing has too many component pieces")
            path.remove(id)
        }
        visit(root, crop, 0)
        return result
    }

    /** Copies a logical drawing through both source and destination ownership partitions.
     * The resulting transfers contain ONLY physical cel addresses, ready for a snapshot executor. */
    fun transfers(layer: Layer, from: String, to: String, source: RectPx,
                  destination: RectPx = source, destinationLayer: Layer = layer): List<RegionTransfer> {
        require(source.w == destination.w && source.h == destination.h)
        val dx = destination.x.toLong() - source.x
        val dy = destination.y.toLong() - source.y
        val targets = resolve(destinationLayer, to, destination)
        return resolve(layer, from, source).flatMap { piece ->
            val moved = translate(piece.rect, dx, dy)
            targets.mapNotNull { target ->
                intersection(moved, target.rect)?.let { area ->
                    RegionTransfer(layer.id, piece.celId, target.celId, translate(area, -dx, -dy), area)
                }
            }
        }
    }

    fun clears(layer: Layer, root: String, area: RectPx): List<RegionClear> =
        resolve(layer, root, area).map { RegionClear(layer.id, it.celId, it.rect) }

    /** Remap an entire drawing graph, preserving aliases inside the clone but detaching the original. */
    fun clone(layer: Layer, roots: Collection<String>, ids: () -> String): Pair<Map<String, String>, List<Cel>> {
        val mapping = reachable(layer, roots).associateWith { ids() }
        val cels = layer.cels.filter { it.id in mapping }.map { cel ->
            Cel(mapping.getValue(cel.id), parts = cel.parts.map { it.copy(celId = mapping.getValue(it.celId)) })
        }
        return mapping to cels
    }

    fun translate(rect: RectPx, dx: Long, dy: Long): RectPx {
        val x = rect.x.toLong() + dx; val y = rect.y.toLong() + dy
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            x + rect.w > Int.MAX_VALUE || y + rect.h > Int.MAX_VALUE)
            throw DocException("This drawing would move beyond the canvas coordinate limit")
        return rect.copy(x = x.toInt(), y = y.toInt())
    }

    fun intersection(a: RectPx, b: RectPx): RectPx? {
        val x = maxOf(a.x.toLong(), b.x.toLong()); val y = maxOf(a.y.toLong(), b.y.toLong())
        val r = minOf(a.x.toLong() + a.w, b.x.toLong() + b.w)
        val d = minOf(a.y.toLong() + a.h, b.y.toLong() + b.h)
        return if (r <= x || d <= y) null else RectPx(x.toInt(), y.toInt(), (r-x).toInt(), (d-y).toInt())
    }

    fun subtract(a: RectPx, b: RectPx): List<RectPx> {
        val cut = intersection(a, b) ?: return listOf(a)
        val result = ArrayList<RectPx>(4)
        val right = a.x.toLong() + a.w; val bottom = a.y.toLong() + a.h
        val cr = cut.x.toLong() + cut.w; val cb = cut.y.toLong() + cut.h
        fun add(x: Long, y: Long, r: Long, d: Long) {
            if (r > x && d > y) result += RectPx(x.toInt(), y.toInt(), (r-x).toInt(), (d-y).toInt())
        }
        add(a.x.toLong(), a.y.toLong(), right, cut.y.toLong())
        add(a.x.toLong(), cb, right, bottom)
        add(a.x.toLong(), cut.y.toLong(), cut.x.toLong(), cb)
        add(cr, cut.y.toLong(), right, cb)
        return result
    }
}
