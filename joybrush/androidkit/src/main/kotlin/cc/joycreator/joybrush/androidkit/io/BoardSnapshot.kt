package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*

/** Physical cel readback, independent of the currently displayed board/frame. Never saves a projection. */
object BoardSnapshot {
    /**
     * [mediaKeys] and [mediaRead] give a MEDIA layer's stores (store name to tile keys, and one store tile's half-float
     * bytes); a media layer's cel lists the keys that hold any state in [Cel.floatTiles], and blank state is not saved,
     * for the same reason a blank paint tile is not.
     */
    fun capture(doc: JbDocument, keys: (String,String)->List<Long>,
                read: (String,String,Long)->ByteArray?, paper: Paper = doc.paper,
                mediaKeys: (String)->Map<String,List<Long>> = { emptyMap() },
                mediaRead: (String,String,Long)->ByteArray? = { _, _, _ -> null }): JbContents {
        val errors=DocOps.validate(doc)
        require(errors.isEmpty()){errors.joinToString("; ")}
        require(doc.layers.all{it.kind.hasPixels && it.animatedIn==null}) { "Raster snapshot cannot drop vector strokes" }
        val payload=LinkedHashMap<Triple<String,String,String>,ByteArray>()
        val media=LinkedHashMap<MediaTileKey,ByteArray>()
        fun mediaState(layer: Layer, cel: Cel): Cel {
            if(layer.kind!=LayerKind.MEDIA)return cel
            if(layer.cels.size!=1)throw JbArchiveException("A pencil, watercolour or oil layer on animation frames cannot be saved yet")
            val listed=LinkedHashSet<String>()
            for((store,storeKeys) in mediaKeys(layer.id)) for(key in storeKeys) {
                val bytes=mediaRead(layer.id,store,key) ?: throw JbArchiveException("A pencil, watercolour or oil tile could not be read")
                if(bytes.size!=cc.joycreator.joybrush.core.media.MediaStores.tileBytes(TILE_SIZE))throw JbArchiveException("A pencil, watercolour or oil tile has the wrong size")
                if(cc.joycreator.joybrush.core.paint.Tiles.isBlank(bytes))continue
                val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                listed.add(name); media[MediaTileKey(layer.id,cel.id,name,store)]=bytes
            }
            return cel.copy(floatTiles=listed.sorted())
        }
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
            cels=layer.cels.map{mediaState(layer,cel(layer.id,it))}, mask=layer.mask?.let{cel(layer.id,it,true)})})
        return JbContents(current,payload,emptyMap(),null,media)
    }
}
