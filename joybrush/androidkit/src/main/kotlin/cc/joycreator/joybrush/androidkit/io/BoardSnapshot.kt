package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*

/** Physical cel readback, independent of the currently displayed board/frame. Never saves a projection. */
object BoardSnapshot {
    fun capture(doc: JbDocument, keys: (String,String)->List<Long>,
                read: (String,String,Long)->ByteArray?, paper: Paper = doc.paper): JbContents {
        val errors=DocOps.validate(doc)
        require(errors.isEmpty()){errors.joinToString("; ")}
        require(doc.layers.all{it.kind==LayerKind.PAINT && it.animatedIn==null}) { "Raster snapshot cannot drop vector strokes" }
        val payload=LinkedHashMap<Triple<String,String,String>,ByteArray>()
        fun cel(layer: String,value: Cel,mask: Boolean=false): Cel {
            val listed=ArrayList<String>()
            for(key in keys(layer,value.id)) {
                val rgba=read(layer,value.id,key) ?: throw JbArchiveException("A stored frame tile could not be read")
                if(rgba.size!=TILE_BYTES)throw JbArchiveException("A stored frame tile has the wrong size")
                // A missing mask means full coverage; a zero mask is real content, never blank paint.
                if(!mask && cc.joycreator.joybrush.core.paint.Tiles.isBlank(rgba))continue
                val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                listed.add(name); payload[Triple(layer,value.id,name)]=rgba
            }
            return value.copy(tiles=listed.sorted())
        }
        val current=doc.copy(paper=paper,layers=doc.layers.map{layer->layer.copy(
            cels=layer.cels.map{cel(layer.id,it)}, mask=layer.mask?.let{cel(layer.id,it,true)})})
        return JbContents(current,payload,emptyMap(),null)
    }
}
