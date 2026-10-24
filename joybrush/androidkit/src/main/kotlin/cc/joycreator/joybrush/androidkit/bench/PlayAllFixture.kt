package cc.joycreator.joybrush.androidkit.bench

import cc.joycreator.joybrush.androidkit.io.JbContents
import cc.joycreator.joybrush.core.doc.*

/** Three visibly changing, overlapping clips: 256 / 512 / 1024 px; sparse linked cels bound fixture RAM. */
object PlayAllFixture {
    fun contents(): JbContents {
        val boards=(0..2).map { i ->
            val frames=List(listOf(25,50,12)[i]) { Frame("clip-$i-frame-$it") }
            Board("clip-$i",listOf("Background","Walker","Bouncer")[i],BoardKind.ANIMATION,
                RectPx(i*64,i*64,256 shl i,256 shl i),fps=listOf(25f,50f,12f)[i],frames=frames,currentFrameId=frames.first().id,
                end=if(i==2) "pingpong" else "loop")
        }
        val tiles=LinkedHashMap<Triple<String,String,String>,ByteArray>()
        val layers=boards.mapIndexed { i,b ->
            val layer="layer-$i"; val shared="$layer-shared"; val cels=arrayListOf(Cel(shared))
            val regions=boards.map { clip ->
                val still=clip.id != b.id
                val ids=if(still) listOf("$layer-${clip.id}-still") else listOf("$layer-a","$layer-b")
                for((drawing,id) in ids.withIndex()) {
                    val keys=ArrayList<String>()
                    if(!still) for(y in Math.floorDiv(b.rect.y,256)..Math.floorDiv(b.rect.y+b.rect.h-1,256))
                        for(x in Math.floorDiv(b.rect.x,256)..Math.floorDiv(b.rect.x+b.rect.w-1,256)) {
                            val key=DocOps.key(x,y); keys += key
                            tiles[Triple(layer,id,key)]=ByteArray(256*256*4).also { bytes ->
                                for(at in bytes.indices step 4) {
                                    val stripe=((at/4)%256/32+drawing)%2 == 0
                                    bytes[at+i]=if(stripe) 200.toByte() else 80.toByte(); bytes[at+3]=220.toByte()
                                }
                            }
                        }
                    cels += Cel(id,tiles=keys)
                }
                RegionFrames(clip.id,clip.frames.mapIndexed { n,f -> f.id to ids[n%ids.size] }.toMap(),held=still)
            }
            Layer(layer,b.name,LayerKind.PAINT,cels=cels,sharedCelId=shared,regions=regions)
        }
        val canvas=Board("bench-canvas","Canvas",BoardKind.CANVAS,RectPx(0,0,1152,1152))
        val doc=JbDocument(id="play-all-bench",name="Play all bench",boards=listOf(canvas)+boards,layers=layers,activeLayerId=layers.last().id)
        check(DocOps.validate(doc).isEmpty()) { DocOps.validate(doc).joinToString() }
        return JbContents(doc,tiles,emptyMap())
    }
}
