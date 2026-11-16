package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*

/** Physical cel readback, independent of the currently displayed board/frame. Never saves a projection. */
object BoardSnapshot {
    /**
     * [mediaKeys] and [mediaRead] give one CEL's media payload (layer and cel to store name to tile keys, and one store
     * tile's bytes: half floats, or RGBA8 for the ground); the cel lists the keys that hold any of it in [Cel.floatTiles],
     * and a blank store tile is not saved, for the same reason a blank paint tile is not. Per cel since JB-2.40 slice 3:
     * an animation frame keeps its own pencil, watercolour and oil.
     */
    fun capture(doc: JbDocument, keys: (String,String)->List<Long>,
                read: (String,String,Long)->ByteArray?, paper: Paper = doc.paper,
                mediaKeys: (String,String)->Map<String,List<Long>> = { _, _ -> emptyMap() },
                mediaRead: (String,String,String,Long)->ByteArray? = { _, _, _, _ -> null }): JbContents =
        captureSparse(doc, keys, read, paper, mediaKeys, mediaRead).contents

    /** What is remembered of a tile that need not be read again: whether it was blank, and its length and checksum. */
    class Seen(val blank: Boolean, val unread: Unread)

    /**
     * [capture], reading only the tiles that must be read (JBX-36). [seen] and [mediaSeen] answer for a tile (layer, cel,
     * key; and store) that holds the bytes it held when it was last read: it is listed as it was then (a blank tile is
     * not, as always; a mask tile is, blank or not) and comes back without bytes ([SparseContents]). Null: read it.
     */
    fun captureSparse(doc: JbDocument, keys: (String,String)->List<Long>,
                read: (String,String,Long)->ByteArray?, paper: Paper = doc.paper,
                mediaKeys: (String,String)->Map<String,List<Long>> = { _, _ -> emptyMap() },
                mediaRead: (String,String,String,Long)->ByteArray? = { _, _, _, _ -> null },
                seen: (String,String,Long)->Seen? = { _, _, _ -> null },
                mediaSeen: (String,String,String,Long)->Seen? = { _, _, _, _ -> null },
                print: (String,String,Long)->cc.joycreator.joybrush.core.paint.TileFingerprint.Print? = { _, _, _ -> null },
                mediaPrint: (String,String,String,Long)->cc.joycreator.joybrush.core.paint.TileFingerprint.Print? = { _, _, _, _ -> null }): SparseContents {
        val errors=DocOps.validate(doc)
        require(errors.isEmpty()){errors.joinToString("; ")}
        require(doc.layers.all{it.kind.hasPixels && it.animatedIn==null}) { "Raster snapshot cannot drop vector strokes" }
        cc.joycreator.joybrush.core.render.RegionRenderer.unsupportedContentReason(doc)?.let { reason ->
            throw IllegalArgumentException(reason)
        }
        require(doc.brushes.isEmpty()) { "Raster snapshot cannot drop frozen brush copies" }
        val payload=LinkedHashMap<Triple<String,String,String>,ByteArray>()
        val media=LinkedHashMap<MediaTileKey,ByteArray>()
        val unreadTiles=LinkedHashMap<Triple<String,String,String>,Unread>()
        val unreadMedia=LinkedHashMap<MediaTileKey,Unread>()
        val tilePrints=HashMap<Triple<String,String,String>,cc.joycreator.joybrush.core.paint.TileFingerprint.Print>()
        val mediaPrints=HashMap<MediaTileKey,cc.joycreator.joybrush.core.paint.TileFingerprint.Print>()
        fun mediaState(layer: Layer, cel: Cel): Cel {
            val stores=mediaKeys(layer.id,cel.id).filterValues{it.isNotEmpty()}
            // No payload now: the cel lists none either. The document's list is from the LAST save; a plain write that
            // settled the paint since (or an undo) left it stale, and the archive rightly refuses a listed tile with no
            // state ("the document lists media tile ... and no state was given for it", owner's autosave, 2026-10-11).
            if(stores.isEmpty())return cel.copy(floatTiles=emptyList())
            val listed=LinkedHashSet<String>()
            for((store,storeKeys) in stores) for(key in storeKeys) {
                val known=mediaSeen(layer.id,cel.id,store,key)
                if(known!=null) {
                    if(known.blank)continue
                    val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                    listed.add(name); unreadMedia[MediaTileKey(layer.id,cel.id,name,store)]=known.unread
                    continue
                }
                val bytes=mediaRead(layer.id,cel.id,store,key) ?: throw JbArchiveException("A pencil, watercolour or oil tile could not be read")
                if(bytes.size!=cc.joycreator.joybrush.core.media.MediaStores.tileBytes(TILE_SIZE,store))throw JbArchiveException("A pencil, watercolour or oil tile has the wrong size")
                if(cc.joycreator.joybrush.core.paint.Tiles.isBlank(bytes))continue
                val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                listed.add(name); media[MediaTileKey(layer.id,cel.id,name,store)]=bytes
                mediaPrint(layer.id,cel.id,store,key)?.let{mediaPrints[MediaTileKey(layer.id,cel.id,name,store)]=it}
            }
            return cel.copy(floatTiles=listed.sorted())
        }
        fun cel(layer: String,value: Cel,mask: Boolean=false): Cel {
            val listed=ArrayList<String>()
            for(key in keys(layer,value.id)) {
                val known=seen(layer,value.id,key)
                if(known!=null) {
                    if(!mask && known.blank)continue
                    val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                    listed.add(name); unreadTiles[Triple(layer,value.id,name)]=known.unread
                    continue
                }
                val rgba=read(layer,value.id,key) ?: throw JbArchiveException("A stored frame tile could not be read")
                if(rgba.size!=TILE_BYTES)throw JbArchiveException("A stored frame tile has the wrong size")
                // A missing mask means full coverage; a zero mask is real content, never blank paint.
                if(!mask && cc.joycreator.joybrush.core.paint.Tiles.isBlank(rgba))continue
                val name=DocOps.key(cc.joycreator.joybrush.core.paint.Tiles.tx(key),cc.joycreator.joybrush.core.paint.Tiles.ty(key))
                listed.add(name); payload[Triple(layer,value.id,name)]=rgba
                print(layer,value.id,key)?.let{tilePrints[Triple(layer,value.id,name)]=it}
            }
            return value.copy(tiles=listed.sorted())
        }
        val current=doc.copy(paper=paper,layers=doc.layers.map{layer->layer.copy(
            cels=layer.cels.map{mediaState(layer,cel(layer.id,it))}, mask=layer.mask?.let{cel(layer.id,it,true)})})
        return SparseContents(JbContents(current,payload,emptyMap(),null,media),unreadTiles,unreadMedia,tilePrints,mediaPrints)
    }
}
