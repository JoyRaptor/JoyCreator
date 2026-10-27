package cc.joycreator.joybrush.core.doc

/** Sampling plan for an atomic Flatten. Original cels remain immutable until every result is ready. */
data class AnimationFlattenPlan(val doc: JbDocument,
    val samples: Map<Pair<String,String>,Map<String,String>>, val combinedNames: List<String>) {
    companion object {
        fun build(source: JbDocument, merged: Set<String>, ids: () -> String): AnimationFlattenPlan {
            val clips=source.boards.filter { it.kind == BoardKind.ANIMATION }
            val participating=source.layers.filter { it.id in merged }.flatMap { it.regions.filterNot { r -> r.held }.map { r -> r.boardId } }.toSet()
            val groups=clips.map { mutableListOf(it) }.toMutableList()
            // Union rectangles can meet a third clip that neither original touched. Close the geometry before validating.
            var changed=true
            while(changed) {
                changed=false
                outer@ for(i in groups.indices) for(j in 0 until i) {
                    val a=groups[i]; val b=groups[j]
                    val bothParticipate=a.any { it.id in participating } && b.any { it.id in participating }
                    // A union can reach a hidden layer's third clip; include it only when ownership would conflict.
                    // An unrelated clip on another layer keeps its independent board and clock.
                    val ownershipConflict=(a.size>1 || b.size>1) && source.layers.any { layer ->
                        val mine=layer.regions.filterNot { it.held }.map { it.boardId }.toSet()
                        a.any { it.id in mine } && b.any { it.id in mine }
                    }
                    if(!bothParticipate && !ownershipConflict) continue
                    if(AnimationBoardOps.overlaps(a.map { it.rect }.reduce(AnimationBoardOps::union),b.map { it.rect }.reduce(AnimationBoardOps::union))) {
                        b.addAll(a); groups.removeAt(i); changed=true; break@outer
                    }
                }
            }
            val fresh=RegionDocumentOps.generator(source,ids)
            var doc=source
            val samples=LinkedHashMap<Pair<String,String>,Map<String,String>>()
            val names=ArrayList<String>()
            for(group in groups) {
                if(group.size == 1) {
                    val b=group.single()
                    for(f in b.frames) samples[b.id to f.id]=mapOf(b.id to f.id)
                    continue
                }
                val fps=group.maxOf { it.fps }; val times=group.associate { it.id to ClipTimeline(it,fps) }
                val length=times.values.maxOf { it.length }
                if(length>100_000) throw DocException("These clips need more than 100,000 frames together; shorten their holds first")
                val largest=group.maxBy { it.rect.w.toLong()*it.rect.h }
                val rect=group.map { it.rect }.reduce(AnimationBoardOps::union)
                BoardDocumentOps.checkRect(rect)
                val frames=List(length) { Frame(fresh()) }; val boardId=fresh()
                val board=Board(boardId,largest.name,BoardKind.ANIMATION,rect,fps=fps,frames=frames,currentFrameId=frames.first().id,end=largest.end)
                val groupIds=group.map { it.id }.toSet()
                frames.forEachIndexed { tick,f -> samples[boardId to f.id]=times.mapValues { (_,t) -> t.frameAt(tick) ?: RegionDocumentOps.BLANK_PREVIEW } }
                val layers=doc.layers.map { l ->
                    val old=l.regions.filter { it.boardId in groupIds }; val rest=l.regions-old.toSet()
                    val animated=old.filterNot { it.held }; val cels=ArrayList<Cel>()
                    val tuples=HashMap<List<String?>,String>()
                    val mapping=frames.associate { f ->
                        val sample=samples.getValue(boardId to f.id)
                        val tuple=animated.map { region -> sample[region.boardId]?.takeIf { it.isNotEmpty() }?.let { region.frameCel.getValue(it) } }
                        f.id to tuples.getOrPut(tuple) { fresh().also { cels += Cel(it) } }
                    }
                    val regions=rest+RegionFrames(boardId,mapping,held=animated.isEmpty())
                    val referenced=CelParts.reachable(l.copy(cels=l.cels+cels),regions.flatMap { it.frameCel.values } + listOfNotNull(l.sharedCelId))
                    val removed=CelParts.reachable(l,old.flatMap { it.frameCel.values })-referenced
                    l.copy(cels=l.cels.filterNot { it.id in removed }+cels,regions=regions,sharedCelId=l.sharedCelId ?: l.cels.single().id)
                }
                doc=doc.copy(layers=layers,boards=doc.boards.filterNot { it.id in groupIds }+board,
                    activeBoardId=if(doc.activeBoardId in groupIds) boardId else doc.activeBoardId)
                names += group.map { it.name }
            }
            val errors=DocOps.validate(doc)
            if(errors.isNotEmpty()) throw DocException(errors.joinToString("; "))
            return AnimationFlattenPlan(doc,samples,names)
        }
    }
}
