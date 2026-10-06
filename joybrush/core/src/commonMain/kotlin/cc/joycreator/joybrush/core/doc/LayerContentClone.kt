package cc.joycreator.joybrush.core.doc

/** Physical content copy addresses, independent of raster tiles or vector stroke records. */
data class CelClone(val fromCelId: String, val toCelId: String)
data class LayerContentClone(val layer: Layer, val copies: List<CelClone>) {
    companion object {
        /** Clone every stored cel once; linked frames remain linked within the new layer. */
        fun plan(source: Layer, id: String, name: String, ids: () -> String): LayerContentClone {
            require(id.isNotBlank() && id != source.id)
            val used = DocOps.storedCels(source).mapTo(HashSet()) { it.id }
            val copies = DocOps.storedCels(source).map { cel ->
                val fresh = ids()
                require(fresh.isNotBlank() && used.add(fresh)) { "A cloned cel needs a fresh ID" }
                CelClone(cel.id, fresh)
            }
            val addresses = copies.associate { it.fromCelId to it.toCelId }
            fun remap(cel: Cel) = Cel(addresses.getValue(cel.id))
            return LayerContentClone(source.copy(id = id, name = name,
                cels = source.cels.map(::remap), mask = source.mask?.let(::remap),
                sharedCelId = source.sharedCelId?.let(addresses::getValue),
                frameCel = source.frameCel.mapValues { addresses.getValue(it.value) },
                regions = source.regions.map { region -> region.copy(
                    frameCel = region.frameCel.mapValues { addresses.getValue(it.value) }) }), copies)
        }
    }
}
