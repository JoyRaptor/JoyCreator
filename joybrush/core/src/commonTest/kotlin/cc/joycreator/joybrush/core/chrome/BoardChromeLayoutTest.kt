package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class BoardChromeLayoutTest {
    @Test fun liftedFrameMovesItsNumberAndHoldTogether() {
        val i=input().copy(selected=true,holds=listOf(2),liftedFrame=1,reducedMotion=true)
        val cell=element(i,"cell-0")
        for(id in listOf("cell-0","cell-number-0","hold-0")) {
            val e=element(i,id)
            assertEquals(BoardChromeLayout.Point(cell.rect.cx,cell.rect.cy),e.transformOrigin)
            assertEquals(-2f,e.rotationDeg); assertEquals(1.1f,e.scale)
        }
        val sprite=input(BoardKind.SPRITE).copy(selected=true,armed=true,liftedCell=0,reducedMotion=true)
        assertEquals(-3f,element(sprite,"sprite-lift").rotationDeg)
        assertEquals(1.08f,element(sprite,"sprite-lift").scale)
        assertEquals(0f,element(sprite,"sprite-cell-0").rotationDeg)
    }
    private val b=BoardChromeLayout.Rect(100f,100f,324f,226f)
    private fun input(kind:BoardKind=BoardKind.ANIMATION)=BoardChromeLayout.Input(b,1f,kind)
    private fun element(i:BoardChromeLayout.Input,id:String)=BoardChromeLayout.layout(i).elements.first { it.id==id }
    @Test fun restIsIconOnlyForEveryKind(){for(k in listOf(BoardKind.ANIMATION,BoardKind.CANVAS,BoardKind.SPRITE)){
        val l=BoardChromeLayout.layout(input(k));assertEquals(listOf("kind"),l.elements.map {it.id});val r=l.elements.single().rect
        assertEquals(18f,r.width);assertEquals(73f,r.cx);assertEquals(119f,r.cy)
    }}
    @Test fun onlyAnimationPastFrameOneHasRestNumber(){assertEquals("3",element(input().copy(currentFrame=3),"rest-frame").text)
        assertEquals(131f,element(input().copy(currentFrame=3),"rest-frame").rect.top)
        assertFalse(BoardChromeLayout.layout(input(BoardKind.SPRITE).copy(currentFrame=3)).elements.any {it.id=="rest-frame"})}
    @Test fun bracketsAt48DpIncludingBoundary(){for(k in listOf(BoardKind.ANIMATION,BoardKind.CANVAS,BoardKind.SPRITE)){
        val near=input(k).copy(pen=BoardChromeLayout.Point(52f,100f));val arms=BoardChromeLayout.layout(near).elements.filter {it.id.startsWith("bracket")}
        assertEquals(8,arms.size);assertEquals(.45f,arms.first().alpha);assertEquals(1f,arms.first().strokePx);assertEquals(14f,arms.first().rect.width);assertEquals(98f,arms.first().rect.left)
        assertEquals(1,BoardChromeLayout.layout(near.copy(pen=BoardChromeLayout.Point(51.99f,100f))).elements.size)
    }}
    @Test fun bracketFadeAfterLeaving(){val i=input().copy(approachWasNear=true,approachExitElapsedMs=150);assertEquals(.225f,element(i,"bracket-0-h").alpha)
        assertEquals(1,BoardChromeLayout.layout(i.copy(approachExitElapsedMs=300)).elements.size)}
    @Test fun pillExactSizesAndNoHop(){val i=input().copy(selected=true);val p=element(i,"pill");assertEquals(34f,p.rect.width);assertEquals(148f,p.rect.height);assertEquals(56f,p.rect.left);assertEquals(100f,p.rect.top);assertEquals(17f,p.radiusPx)
        assertEquals(128f,element(input(BoardKind.CANVAS).copy(selected=true),"pill").rect.height)
        assertEquals(p.rect,element(i.copy(penDown=true),"pill").rect)}
    @Test fun typographyAndBaselines(){val i=input().copy(selected=true,currentFrame=2,holds=listOf(1,1,1));val t=element(i,"title");assertEquals(93f,t.baselinePx);assertEquals(12f,t.sizeSp);assertEquals(700,t.weight);assertEquals(BoardChromeLayout.Font.ARCHIVO,t.font)
        val size=element(i,"size");assertEquals(92f,size.baselinePx);assertEquals(b.right,size.rect.right);assertEquals(9f,size.sizeSp);assertEquals(.6f,size.alpha)
        assertEquals(18f,element(i,"frame").sizeSp);assertEquals(900,element(i,"frame").weight);assertEquals(8.5f,element(i,"frame-prev").sizeSp);assertEquals(.45f,element(i,"frame-next").alpha)}
    @Test fun densityConvertsOnce(){val i=input().copy(board=BoardChromeLayout.Rect(200f,200f,648f,452f),density=2f,selected=true);assertEquals(68f,element(i,"pill").rect.width);assertEquals(112f,element(i,"pill").rect.left);assertEquals(272f,element(i,"peg-tab").rect.width)}
    @Test fun selectionAndHandlesNeverCoverPixels(){val i=input(BoardKind.CANVAS).copy(selected=true,locked=false);val l=BoardChromeLayout.layout(i)
        l.elements.filter {it.id.startsWith("handle")}.forEach {assertFalse(it.rect.intersects(b));if(it.id.startsWith("handle-ring"))assertEquals(1.5f,it.strokePx) else assertEquals(8f,it.rect.width)}
        for(id in listOf("keyline","selection","selection-halo")){val e=l.elements.first {it.id==id};assertTrue(e.rect.left+e.strokePx/2<=b.left);assertTrue(e.rect.top+e.strokePx/2<=b.top)}
        assertEquals(8,l.controls.count {it.id.startsWith("handle-")})}
    @Test fun fixedAnimationHasNailAndNoHandles(){val i=input().copy(selected=true,locked=false,holds=listOf(1,1));assertEquals(5f,element(i,"nail").rect.width);assertEquals("lockC",element(i,"lock").glyph)
        assertFalse(BoardChromeLayout.layout(i).elements.any {it.id.startsWith("handle")});assertEquals(BoardChromeLabels.FIXED,BoardChromeLayout.layout(i).controls.first {it.id=="lock"}.tooltip)}
    @Test fun penFadeAndFoldNumbers(){assertEquals(1f,BoardChromeLayout.drawingAlpha(true,0));assertEquals(.56f,BoardChromeLayout.drawingAlpha(true,60),.00001f);assertEquals(.12f,BoardChromeLayout.drawingAlpha(true,120),.00001f)
        assertEquals(.12f,BoardChromeLayout.drawingAlpha(false,0));assertEquals(.56f,BoardChromeLayout.drawingAlpha(false,150),.00001f);assertEquals(1f,BoardChromeLayout.drawingAlpha(false,300))
        val i=input().copy(selected=true,penDown=true);val l=BoardChromeLayout.layout(i);assertTrue(l.folded);assertFalse(l.elements.any {it.id=="peg-tab"});assertEquals(.35f,element(i,"cell-0").alpha);assertEquals(2f,element(i,"cell-0").rect.width);assertEquals(12f,element(i,"cell-0").rect.height)
        assertFalse(BoardChromeLayout.layout(i.copy(penDown=false,penLiftElapsedMs=300)).folded)}
    @Test fun wheelEveryNineDpWithBounds(){assertEquals(2,BoardChromeLayout.wheelFrame(1,8.99f,1f,9)+1);assertEquals(2,BoardChromeLayout.wheelFrame(1,9f,1f,9));assertEquals(3,BoardChromeLayout.wheelFrame(4,-18f,2f,9));assertEquals(1,BoardChromeLayout.wheelFrame(1,-99f,1f,9));assertEquals(9,BoardChromeLayout.wheelFrame(1,999f,1f,9))}
    @Test fun typedSizeKeepsTopLeft(){assertEquals(RectPx(3,7,500,800),BoardChromeLayout.typedSize(RectPx(3,7,20,40),500,800));assertFailsWith<IllegalArgumentException>{BoardChromeLayout.typedSize(RectPx(0,0,1,1),0,10)}}
    @Test fun pegTabAndControlsExact(){val i=input().copy(selected=true,onion=true,playing=true);val p=element(i,"peg-tab");assertEquals(136f,p.rect.width);assertEquals(24f,p.rect.height);assertEquals(234f,p.rect.top);assertEquals(listOf(11f,11f,3f,3f),p.cornerRadiiPx)
        assertEquals(32f,element(i,"peg-onion").rect.width);assertEquals(12f,element(i,"peg-onion").rect.height);assertEquals(17f,element(i,"peg-play").rect.width);assertEquals(44f,element(i,"peg-play").rect.cx-element(i,"peg-onion").rect.cx);assertEquals(9f,element(i,"onion").rect.width)
        assertEquals(255f,element(i,"sprocket-0-0").clip!!.top);assertEquals(BoardChromeLayout.Colour.CYAN,element(i,"peg-onion").colour);assertEquals("pause",element(i,"play").glyph)}
    @Test fun stripHoldsSprocketsAndInsertion(){val i=input().copy(selected=true,holds=listOf(1,2,1),currentFrame=2,insertionIndex=2,liftedFrame=3);assertEquals(40f,element(i,"cell-0").rect.width);assertEquals(84f,element(i,"cell-1").rect.width);assertEquals(44f,element(i,"cell-1").rect.left-element(i,"cell-0").rect.left);assertEquals(26f,element(i,"cell-0").rect.height)
        assertEquals(4f,element(i,"sprocket-0-0").rect.width);assertEquals(9f,element(i,"sprocket-0-1").rect.left-element(i,"sprocket-0-0").rect.left);assertEquals("×2",element(i,"hold-1").text);assertEquals(1.5f,element(i,"cell-1").strokePx);assertEquals(2f,element(i,"insertion").rect.width);assertEquals(1.1f,element(i,"cell-2").scale);assertEquals(-2f,element(i,"cell-2").rotationDeg)
        assertEquals(b.left+b.width*.82f,BoardChromeLayout.layout(i).stripFadeStartPx);assertTrue(element(i,"add-cell").dashed)}
    @Test fun fpsHoldPanelAbovePeg(){val i=input().copy(selected=true,showFps=true);assertTrue(element(i,"fps-panel").rect.bottom<element(i,"peg-tab").rect.top);assertEquals("12 fps",element(i,"fps").text)}
    @Test fun tileModeSuppressesEverythingExceptRingAndSwitch(){val l=BoardChromeLayout.layout(input(BoardKind.CANVAS).copy(selected=true,armed=true));assertTrue(l.tileCanvas);assertTrue(l.suppressOtherBoards);assertEquals(listOf("feature"),l.controls.map {it.id});assertFalse(l.elements.any {it.id=="title"||it.id=="kind"||it.id=="size"})
        assertTrue(BoardChromeLayout.layout(input().copy(otherTileArmed=true)).elements.isEmpty())}
    @Test fun tileSwitchDocksEightDpFromEachNearestEdge(){val s=BoardChromeLayout.Rect(0f,0f,548f,1000f);for((r,edge) in listOf(BoardChromeLayout.Rect(-300f,200f,-200f,300f) to 0,BoardChromeLayout.Rect(700f,200f,800f,300f) to 1,BoardChromeLayout.Rect(200f,-300f,300f,-200f) to 2,BoardChromeLayout.Rect(200f,1200f,300f,1300f) to 3)){
        val pill=element(input(BoardKind.CANVAS).copy(board=r,armed=true,screen=s),"tile-pill").rect;assertEquals(8f,when(edge){0->pill.left;1->s.right-pill.right;2->pill.top;else->s.bottom-pill.bottom})}}
    @Test fun tileFlagChangesOnlyOnWrappedStrokeAndSurvivesDisarm(){val b=Board("b","B",BoardKind.CANVAS,RectPx(0,0,20,20));assertFalse(BoardChromeLayout.afterWrappedStroke(b,false).tiled);val tiled=BoardChromeLayout.afterWrappedStroke(b,true);assertTrue(tiled.tiled);assertTrue(BoardChromeLayout.afterWrappedStroke(tiled,false).tiled);assertFalse(BoardChromeLayout.afterWrappedStroke(b.copy(kind=BoardKind.ANIMATION),true).tiled);assertEquals("seam",element(input(BoardKind.CANVAS).copy(tiled=true),"kind").glyph)}
    @Test fun hoverStylusAndMouseRestoreStoredFrame(){for(p in listOf(BoardHoverLoop.Pointer.STYLUS,BoardHoverLoop.Pointer.MOUSE)){val state=BoardHoverLoop().enter(p,BoardKind.ANIMATION,false,3);assertTrue(state.playing);assertEquals(3,state.exit().second);assertFalse(state.exit().first.playing);assertEquals(3,state.enter(p,BoardKind.ANIMATION,false,5).savedFrame)}
        assertFalse(BoardHoverLoop().enter(BoardHoverLoop.Pointer.FINGER,BoardKind.ANIMATION,false,3).playing);assertFalse(BoardHoverLoop().enter(BoardHoverLoop.Pointer.STYLUS,BoardKind.ANIMATION,true,3).playing)}
    @Test fun shelfAndSpriteStates(){val i=input(BoardKind.SPRITE).copy(selected=true,armed=true,liftedCell=1,targetCell=2);assertEquals(30f,element(i,"shelf").rect.height);assertEquals(234f,element(i,"shelf").rect.top);assertEquals(22f,element(i,"cols-minus").rect.height);assertEquals(1.08f,element(i,"sprite-lift").scale);assertEquals(-3f,element(i,"sprite-lift").rotationDeg);assertEquals(-2.2f,element(i,"sprite-cell-0").rotationDeg);assertEquals(0f,element(i.copy(reducedMotion=true),"sprite-cell-0").rotationDeg)}
    @Test fun spriteOrderAndPreviewExact(){val i=input(BoardKind.SPRITE).copy(selected=true,spriteOrder=listOf(0,1),playingCell=1);assertEquals(15f,element(i,"order-0").rect.width);assertEquals(2.5f,element(i,"sprite-playing").strokePx);assertEquals(84f,element(i,"preview").rect.width);assertEquals(56f,element(i,"preview-art").rect.height);assertTrue(element(i,"preview").rect.bottom<b.top);assertTrue(element(i,"preview-art").artSlot)}
    @Test fun layerMarkersExact(){val l=BoardChromeLayout.layerMarker(b,1f,true,"Walk");assertEquals(13f,l.elements.single().rect.width);assertEquals(b.left+8,l.elements.single().rect.cx);assertEquals("runner",l.elements.single().glyph);assertEquals("mountain",BoardChromeLayout.layerMarker(b,1f,false,"Walk").elements.single().glyph)}
    @Test fun allControlsAtLeastFortyAndNearestHit(){val l=BoardChromeLayout.layout(input().copy(selected=true,showFps=true,holds=listOf(1,1)));l.controls.forEach {assertTrue(it.hit.width>=40f);assertTrue(it.hit.height>=40f)}
        val kind=l.controls.first {it.id=="kind"};assertEquals("kind",BoardChromeLayout.hit(l,BoardChromeLayout.Point(kind.visual.cx,kind.visual.cy))!!.id)}
    @Test fun invalidInputsRefused(){assertFailsWith<IllegalArgumentException>{BoardChromeLayout.layout(input().copy(density=0f))};assertFailsWith<IllegalArgumentException>{BoardChromeLayout.layout(input().copy(holds=listOf(0)))}}
    @Test fun lockedNumericTokens(){assertEquals(.90f,BoardChromeLayout.CHROME_ALPHA);assertEquals(.55f,BoardChromeLayout.GLASS_ALPHA);assertEquals(.13f,BoardChromeLayout.CHROME_RING_ALPHA);assertEquals(.28f,BoardChromeLayout.STATE_HALO_ALPHA)
        val i=input(BoardKind.CANVAS).copy(selected=true,locked=false);for(id in listOf("feature","lock","export"))assertEquals(16f,element(i,id).rect.width)
        assertEquals(26f,element(i,"lock-ring").rect.width);assertEquals(8f,element(i,"lock-ring").radiusPx);assertEquals(2f,element(i,"title").haloPx);assertEquals(2f,element(i,"handle-0").radiusPx)
        val a=input().copy(selected=true,onion=true);assertEquals(1.5f,element(a,"peg-onion").strokePx);assertEquals(6f,element(a,"peg-onion").radiusPx);assertEquals(3f,element(a,"peg-onion").haloPx)
        val ruler=input().copy(selected=true,penDown=true,holds=listOf(1,2));assertEquals(22f,element(ruler,"cell-0").clip!!.height);assertEquals(14f,element(ruler,"cell-1").rect.left-element(ruler,"cell-0").rect.left)
        val s=input(BoardKind.SPRITE).copy(selected=true);assertEquals(.7f,element(s,"grid-count-on").alpha);assertEquals("▦ 2",element(s,"subgrid").text);assertEquals(300L,BoardChromeLayout.WIGGLE_MS)
    }
    @Test fun completeDocumentSavesTileFlagAndV4Default(){val board=Board("b","B",BoardKind.CANVAS,RectPx(0,0,20,20),tiled=true);val doc=JbDocument(id="d",name="D",boards=listOf(board),layers=emptyList());val saved=DocJson.encode(doc);assertTrue(DocJson.decode(saved).boards.single().tiled)
        val old=saved.replace("\"version\": $DOC_VERSION","\"version\": 4").replace(",\n      \"tiled\": true","");val decoded=DocJson.decode(old);assertEquals(4,decoded.version);assertFalse(decoded.boards.single().tiled)}
    @Test fun tiledSerializationAndOldVersionDefault(){val board=Board("b","B",BoardKind.CANVAS,RectPx(0,0,20,20),tiled=true)
        val json=kotlinx.serialization.json.Json {encodeDefaults=true};val encoded=json.encodeToString(Board.serializer(),board);assertTrue(encoded.contains("\"tiled\":true"));assertTrue(json.decodeFromString(Board.serializer(),encoded).tiled)
        assertFalse(json.decodeFromString(Board.serializer(),"""{"id":"b","name":"B","kind":"CANVAS","rect":{"x":0,"y":0,"w":20,"h":20}}""").tiled);assertTrue(DOC_VERSION >= 5)}
}
