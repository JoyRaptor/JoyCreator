package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.anim.FilmStrip
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import kotlin.math.*

/** Locked JB-3.00a §K. All output geometry is screen pixels; views only render it. */
object BoardChromeLayout {
    data class Point(val x: Float, val y: Float)
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right-left
        val height get() = bottom-top
        val cx get() = (left+right)/2
        val cy get() = (top+bottom)/2
        fun intersects(other: Rect) = right>other.left && left<other.right && bottom>other.top && top<other.bottom
        fun distance(p: Point): Float = hypot(max(max(left-p.x, 0f), p.x-right), max(max(top-p.y, 0f), p.y-bottom))
    }
    enum class Colour { PAPER, CHROME, GLASS, INK, DIM, CYAN, PINK, AMBER, GREY, ON_GRADIENT, SPRITE_ON, PAPER_SURFACE, ANIMATION_ON }
    enum class Shape { ROUND_RECT, LINE, TEXT, GLYPH }
    enum class Font { ARCHIVO, MONO, SANS }
    enum class Align { LEFT, CENTRE, RIGHT }
    data class Element(val id: String, val shape: Shape, val rect: Rect,
        val colour: Colour = Colour.PAPER, val alpha: Float = 1f, val strokePx: Float = 0f,
        val radiusPx: Float = 0f, val text: String = "", val font: Font = Font.MONO,
        val sizeSp: Float = 9f, val weight: Int = 600, val glyph: String = "",
        val gradient: BoardKind? = null, val haloPx: Float = 0f, val dashed: Boolean = false,
        val rotationDeg: Float = 0f, val scale: Float = 1f, val clip: Rect? = null,
        val cornerRadiiPx: List<Float>? = null, val align: Align = Align.CENTRE,
        val baselinePx: Float = rect.bottom, val fadeStartPx: Float? = null,
        val fadeEndPx: Float? = null, val shadowPx: Float = 0f, val artSlot: Boolean = false,
        val transformOrigin: Point? = null)
    data class Control(val id: String, val visual: Rect, val hit: Rect, val tooltip: String, val enabled: Boolean = true)
    data class Layout(val elements: List<Element>, val controls: List<Control>, val tileCanvas: Boolean,
        val suppressOtherBoards: Boolean, val folded: Boolean, val stripFadeStartPx: Float? = null)
    data class Input(val board: Rect, val density: Float, val kind: BoardKind, val name: String = "Board",
        val selected: Boolean = false, val locked: Boolean = true, val armed: Boolean = false,
        val tiled: Boolean = false, val currentFrame: Int = 1, val holds: List<Int> = listOf(1),
        val pixelWidth: Int = 1920, val pixelHeight: Int = 1080,
        val pen: Point? = null, val penDown: Boolean = false, val penDownElapsedMs: Long = 120,
        val penLiftElapsedMs: Long = 300, val approachExitElapsedMs: Long = 300,
        val approachWasNear: Boolean = false, val screen: Rect = Rect(0f,0f,548f,1126f),
        val otherTileArmed: Boolean = false, val stripScrollPx: Float = 0f, val liftedFrame: Int? = null,
        val insertionIndex: Int? = null, val onion: Boolean = false, val playing: Boolean = false,
        val loopGlyph: String = "loop", val showFps: Boolean = false, val fps: Int = 12,
        val typingSize: Boolean = false, val columns: Int = 4, val rows: Int = 2,
        val cellWidth: Int = 128, val cellHeight: Int = 128, val titleWidthPx: Float? = null,
        val subGrid: Int = 2, val gridByPixels: Boolean = false, val spriteOrder: List<Int> = emptyList(),
        val playingCell: Int? = null, val liftedCell: Int? = null, val targetCell: Int? = null,
        val liftedCellOffset: Point = Point(0f,0f),
        val reducedMotion: Boolean = false, val wiggleElapsedMs: Long = 0,
        /** Actual alpha at the last pen transition; supplied by BoardChromePenFade for rapid strokes. */
        val penFadeStartAlpha: Float? = null)

    const val CHROME_ALPHA = .90f
    const val GLASS_ALPHA = .55f
    const val CHROME_RING_ALPHA = .13f
    const val STATE_HALO_ALPHA = .28f
    const val TOUCH_DP = 40f
    const val APPROACH_DP = 48f
    const val FADE_OUT_MS = 120L
    const val FADE_BACK_MS = 300L
    const val DRAWING_ALPHA = .12f
    const val RULER_ALPHA = .35f
    const val WHEEL_DP = 9f
    const val WIGGLE_MS = 300L
    const val STRIP_FADE_FRACTION = .18f
    fun drawingAlpha(down: Boolean, elapsedMs: Long, startAlpha: Float = if (down) 1f else DRAWING_ALPHA): Float {
        require(startAlpha.isFinite() && startAlpha in DRAWING_ALPHA..1f)
        val t=(elapsedMs.toFloat()/(if(down) FADE_OUT_MS else FADE_BACK_MS)).coerceIn(0f,1f)
        val target = if (down) DRAWING_ALPHA else 1f
        return startAlpha + (target-startAlpha)*t
    }
    fun wheelFrame(start: Int, dragPx: Float, density: Float, count: Int): Int {
        require(density.isFinite() && density>0)
        return (start + (dragPx/(WHEEL_DP*density)).toInt()).coerceIn(1,max(1,count))
    }
    fun typedSize(topLeft: RectPx, width: Int, height: Int): RectPx {
        require(width>0 && height>0)
        return topLeft.copy(w=width,h=height)
    }
    /** Called only after a stroke actually wraps, never when the switch is tapped. */
    fun afterWrappedStroke(board: Board, armed: Boolean): Board =
        if(armed && board.kind==BoardKind.CANVAS && !board.tiled) board.copy(tiled=true) else board

    /** Only visual pen state belongs in the redraw key. Painting keeps every raw sample itself. */
    fun visualInput(i: Input): Input {
        val near = i.pen?.let { i.board.distance(it) <= APPROACH_DP * i.density } == true
        return i.copy(pen = if (!i.selected && near) Point(i.board.cx, i.board.cy) else null,
            penDownElapsedMs = i.penDownElapsedMs.coerceIn(0, FADE_OUT_MS),
            penLiftElapsedMs = i.penLiftElapsedMs.coerceIn(0, FADE_BACK_MS),
            approachExitElapsedMs = i.approachExitElapsedMs.coerceIn(0, FADE_BACK_MS),
            wiggleElapsedMs = if (i.selected && i.kind == BoardKind.SPRITE && i.armed && !i.reducedMotion)
                i.wiggleElapsedMs % (2 * WIGGLE_MS) else 0)
    }

    fun layout(i: Input): Layout {
        require(i.density.isFinite() && i.density>0 && i.board.width>0 && i.board.height>0)
        require(i.columns>0 && i.rows>0 && i.columns.toLong()*i.rows<=SpriteGridMath.MAX_CELLS && i.holds.all { it>0 })
        val e=mutableListOf<Element>(); val c=mutableListOf<Control>(); val d=i.density; val b=i.board
        fun rect(x:Float,y:Float,w:Float,h:Float)=Rect(x,y,x+w*d,y+h*d)
        fun centre(x:Float,y:Float,w:Float,h:Float)=rect(x-w*d/2,y-h*d/2,w,h)
        fun add(id:String,r:Rect,shape:Shape=Shape.ROUND_RECT,col:Colour=Colour.INK,a:Float=1f,
            stroke:Float=0f,radius:Float=0f,text:String="",font:Font=Font.MONO,size:Float=9f,
            weight:Int=600,glyph:String="",gradient:BoardKind?=null,halo:Float=0f,dashed:Boolean=false,
            rotation:Float=0f,scale:Float=1f,clip:Rect?=null) {
            val stripPart=id.startsWith("sprocket") || id.startsWith("cell-") || id.startsWith("hold-") || id=="add-cell" || id=="add" || id=="insertion"
            val corners=if(id=="peg-tab")listOf(11f,11f,3f,3f).map { it*d } else null
            val alignment=if(id=="title")Align.LEFT else if(id=="size"||id=="cell-size"||id.startsWith("hold-"))Align.RIGHT else Align.CENTRE
            val baseline=when(id){"title"->b.top-7*d;"size"->b.top-8*d;else->r.cy+size*d*.35f}
            val stripClip=if(stripPart)if(i.penDown || i.penLiftElapsedMs<FADE_BACK_MS)Rect(b.left,b.bottom+10*d,b.right,b.bottom+32*d) else Rect(b.left,b.bottom+29*d,b.right,b.bottom+69*d) else null
            e+=Element(id,shape,r,col,a,stroke*d,radius*d,text,font,size,weight,glyph,gradient,
                (if(col==Colour.PAPER)max(halo,1f) else halo)*d,dashed,rotation,scale,clip?:stripClip,corners,alignment,baseline,
                if(stripPart)b.left+b.width*.82f else null,if(stripPart)b.right else null,
                if(id=="sprite-lift" || id.startsWith("cell-") && !id.startsWith("cell-number-") && i.liftedFrame!=null && id=="cell-${i.liftedFrame-1}")10*d
                else if(col==Colour.PAPER)1*d else 0f,id=="preview-art")
        }
        fun control(id:String,r:Rect,tip:String) {
            val stripPart=id.startsWith("cell-")||id=="add"
            if(stripPart && (r.right<=b.left || r.left>=b.right))return
            val visual=if(stripPart)Rect(max(r.left,b.left),r.top,min(r.right,b.right),r.bottom) else r
            c+=Control(id,visual,centre(visual.cx,visual.cy,max(TOUCH_DP,visual.width/d),max(TOUCH_DP,visual.height/d)),tip)
        }
        fun glyph(id:String,r:Rect,g:String,tip:String,col:Colour=Colour.INK,a:Float=1f,gradient:BoardKind?=null) {
            add(id,r,Shape.GLYPH,col,a,glyph=g,gradient=gradient); control(id,r,tip)
        }
        fun text(id:String,x:Float,y:Float,w:Float,h:Float,t:String,size:Float=9f,font:Font=Font.MONO,
            weight:Int=600,col:Colour=Colour.PAPER,a:Float=1f,tip:String?=null,gradient:BoardKind?=null) {
            val r=rect(x,y,w,h); add(id,r,Shape.TEXT,col,a,text=t,font=font,size=size,weight=weight,gradient=gradient,halo=if(col==Colour.PAPER)2f else 0f)
            if(tip!=null)control(id,r,tip)
        }
        val tile=i.kind==BoardKind.CANVAS && i.armed
        if(i.otherTileArmed && !tile)return Layout(emptyList(),emptyList(),false,false,false)
        val fixed=i.kind==BoardKind.ANIMATION && i.holds.size>=2
        val locked=i.locked || fixed
        val icon=when(i.kind){BoardKind.ANIMATION->"film";BoardKind.SPRITE->"sprite";else->if(i.tiled)"seam" else "image"}
        val kindLabel=when(i.kind){BoardKind.ANIMATION->"animation";BoardKind.SPRITE->"sprite";else->if(i.tiled)"tile" else "image"}
        val tabTip="${i.name}, $kindLabel board. Tap to show its controls; hold for the board menu."+
            if(i.kind==BoardKind.ANIMATION && !i.selected)" Hover with a pen or mouse to play it in place." else ""
        fun ring(){
            // Stroke centres are offset enough for even the halo to remain outside all art pixels.
            add("selection-halo",Rect(b.left-4.5f*d,b.top-4.5f*d,b.right+4.5f*d,b.bottom+4.5f*d),col=Colour.CYAN,a=.28f,stroke=3f)
            add("selection",Rect(b.left-2.25f*d,b.top-2.25f*d,b.right+2.25f*d,b.bottom+2.25f*d),col=Colour.CYAN,stroke=1.5f)
            add("keyline",Rect(b.left-.5f*d,b.top-.5f*d,b.right+.5f*d,b.bottom+.5f*d),col=Colour.PAPER,a=.55f,stroke=1f)
        }
        if(tile){
            ring()
            var r=rect(b.left-44*d,b.top,34f,38f)
            if(!b.intersects(i.screen)) {
                val s=i.screen; val x=r.cx.coerceIn(s.left+25*d,s.right-25*d); val y=r.cy.coerceIn(s.top+27*d,s.bottom-27*d)
                val projections=listOf(Point(s.left,r.cy.coerceIn(s.top,s.bottom)),Point(s.right,r.cy.coerceIn(s.top,s.bottom)),Point(r.cx.coerceIn(s.left,s.right),s.top),Point(r.cx.coerceIn(s.left,s.right),s.bottom))
                val edges=projections.map { hypot(r.cx-it.x,r.cy-it.y) }; val n=edges.indices.minByOrNull { edges[it] }!!
                r=centre(if(n==0)s.left+25*d else if(n==1)s.right-25*d else x,
                    if(n==2)s.top+27*d else if(n==3)s.bottom-27*d else y,34f,38f)
            }
            add("tile-pill",r,col=Colour.CHROME,a=CHROME_ALPHA,radius=17f)
            val cell=centre(r.cx,r.cy,26f,26f);add("tile-armed",cell,col=Colour.CYAN,stroke=1.5f,radius=8f,halo=3f)
            glyph("feature",centre(r.cx,r.cy,16f,16f),"seam",BoardChromeLabels.TILE_ON)
            return Layout(e,c,true,true,false)
        }
        if(!i.selected){
            val near=i.pen?.let { b.distance(it)<=APPROACH_DP*d }==true
            val a=if(near).45f else if(i.approachWasNear).45f*(1-i.approachExitElapsedMs/300f).coerceIn(0f,1f) else 0f
            if(a>0) for((n,p) in listOf(Point(b.left-2*d,b.top-2*d),Point(b.right+2*d,b.top-2*d),Point(b.right+2*d,b.bottom+2*d),Point(b.left-2*d,b.bottom+2*d)).withIndex()){
                val sx=if(n==0||n==3)1 else -1;val sy=if(n<2)1 else -1
                add("bracket-$n-h",Rect(p.x,p.y,p.x+sx*14*d,p.y),Shape.LINE,Colour.PAPER,a,stroke=1f)
                add("bracket-$n-v",Rect(p.x,p.y,p.x,p.y+sy*14*d),Shape.LINE,Colour.PAPER,a,stroke=1f)
            }
            glyph("kind",centre(b.left-27*d,b.top+19*d,18f,18f),icon,tabTip,Colour.PAPER)
            if(i.kind==BoardKind.ANIMATION && i.currentFrame!=1)text("rest-frame",b.left-33*d,b.top+31*d,12f,12f,"${i.currentFrame}",11f,Font.ARCHIVO,800)
            return Layout(e,c,false,false,false)
        }
        ring()
        val startAlpha=i.penFadeStartAlpha ?: if(i.penDown)1f else drawingAlpha(true,i.penDownElapsedMs)
        val a=drawingAlpha(i.penDown,if(i.penDown)i.penDownElapsedMs else i.penLiftElapsedMs,startAlpha)
        val folded=i.kind==BoardKind.ANIMATION && (i.penDown || i.penLiftElapsedMs<FADE_BACK_MS)
        val pillH=if(i.kind==BoardKind.ANIMATION)148f else 128f
        add("pill",rect(b.left-44*d,b.top,34f,pillH),col=Colour.CHROME,a=CHROME_ALPHA*a,radius=17f)
        val cx=b.left-27*d
        glyph("kind",centre(cx,b.top+19*d,18f,18f),icon,tabTip,a=a,gradient=i.kind)
        if(i.kind==BoardKind.ANIMATION){
            val r=centre(cx,b.top+59*d,30f,50f); control("wheel",r,BoardChromeLabels.WHEEL)
            text("frame",cx-15*d,b.top+49*d,30f,20f,"${i.currentFrame}",18f,Font.ARCHIVO,900,Colour.INK,a,gradient=i.kind)
            if(i.currentFrame>1)text("frame-prev",cx-15*d,b.top+36*d,30f,10f,"${i.currentFrame-1}",8.5f,weight=500,a=.45f*a,col=Colour.DIM)
            if(i.currentFrame<i.holds.size)text("frame-next",cx-15*d,b.top+72*d,30f,10f,"${i.currentFrame+1}",8.5f,weight=500,a=.45f*a,col=Colour.DIM)
        }else{
            if(i.armed)add("feature-ring",centre(cx,b.top+49*d,26f,26f),col=Colour.CYAN,a=a,stroke=1.5f,radius=8f,halo=3f)
            glyph("feature",centre(cx,b.top+49*d,16f,16f),if(i.kind==BoardKind.SPRITE)"swap" else "seam",
                if(i.kind==BoardKind.SPRITE)if(i.armed)BoardChromeLabels.SPRITE_ON else BoardChromeLabels.SPRITE_OFF else BoardChromeLabels.TILE_OFF,a=a)
        }
        val offset=if(i.kind==BoardKind.ANIMATION)20f else 0f
        if(!locked)add("lock-ring",centre(cx,b.top+(79+offset)*d,26f,26f),col=Colour.AMBER,a=a,stroke=1.5f,radius=8f)
        glyph("lock",centre(cx,b.top+(79+offset)*d,16f,16f),if(locked)"lockC" else "lockO",if(fixed)BoardChromeLabels.FIXED else if(locked)BoardChromeLabels.LOCKED else BoardChromeLabels.UNLOCKED,a=a)
        if(fixed)add("nail",centre(cx+9*d,b.top+(70+offset)*d,5f,5f),a=a,radius=2.5f)
        glyph("export",centre(cx,b.top+(109+offset)*d,16f,16f),"export",when(i.kind){BoardKind.ANIMATION->BoardChromeLabels.EXPORT_ANIM;BoardKind.SPRITE->BoardChromeLabels.EXPORT_SPRITE;else->if(i.tiled)BoardChromeLabels.EXPORT_TILE else BoardChromeLabels.EXPORT_IMAGE},a=a)
        text("title",b.left,b.top-19*d,(i.titleWidthPx?:min(b.width,i.name.length*6.6f*d+4*d))/d,12f,i.name,12f,Font.ARCHIVO,700,a=a,tip=BoardChromeLabels.TITLE)
        val sizeW="${i.pixelWidth} × ${i.pixelHeight}".length*5.4f
        text("size",b.right-sizeW*d,b.top-17*d,sizeW,9f,"${i.pixelWidth} × ${i.pixelHeight}",9f,weight=500,a=a*(if(locked).6f else 1f),tip=BoardChromeLabels.WIDTH)
        if(!locked){
            if(i.typingSize){
                add("size-typing",rect(b.right-sizeW*d-2*d,b.top-19*d,sizeW+4,13f),col=Colour.CYAN,a=a,stroke=1.5f,radius=2f)
                add("size-caret",Rect(b.right+1*d,b.top-16*d,b.right+1*d,b.top-8*d),Shape.LINE,Colour.CYAN,a,stroke=1.5f)
            }
            else add("size-underline",Rect(b.right-sizeW*d,b.top-6*d,b.right,b.top-6*d),Shape.LINE,Colour.PAPER,a,stroke=1f,dashed=true)
            val pts=listOf(Point(b.left-9*d,b.top-9*d),Point(b.cx-4*d,b.top-9*d),Point(b.right+d,b.top-9*d),Point(b.right+d,b.cy-4*d),Point(b.right+d,b.bottom+d),Point(b.cx-4*d,b.bottom+d),Point(b.left-9*d,b.bottom+d),Point(b.left-9*d,b.cy-4*d))
            pts.forEachIndexed { n,p->val r=rect(p.x,p.y,8f,8f);add("handle-$n",r,a=a,radius=2f);add("handle-ring-$n",r,col=Colour.CYAN,a=a,stroke=1.5f,radius=2f);control("handle-$n",r,"Drag to resize") }
        }
        if(i.kind==BoardKind.ANIMATION){
            val py=b.bottom+8*d; val sy=py+21*d; val strip=rect(b.left,sy,b.width/d,40f)
            if(!folded){
                add("peg-tab",rect(b.cx-68*d,py,136f,24f),col=Colour.CHROME,a=CHROME_ALPHA,radius=11f)
                for((n,g) in listOf("onion","play","loop").withIndex()){
                    val pc=b.cx+(n-1)*44*d;val r=centre(pc,py+12*d,if(n==1)17f else 32f,if(n==1)17f else 12f)
                    val col=if(n==0&&i.onion)Colour.CYAN else if(n==1&&i.playing)Colour.PINK else Colour.INK
                    add("peg-$g",r,col=col,stroke=1.5f,radius=if(n==1)8.5f else 6f,halo=if(col==Colour.CYAN||col==Colour.PINK)3f else 0f)
                    glyph(g,centre(pc,py+12*d,9f,9f),if(n==1&&i.playing)"pause" else if(n==2)i.loopGlyph else g,
                        if(n==0)BoardChromeLabels.ONION else if(n==1)if(i.playing)"Pause." else "Play this board." else BoardChromeLabels.LOOP,col)
                }
                if(i.showFps){
                    val r=rect(b.cx-60*d,py-34*d,120f,30f);add("fps-panel",r,col=Colour.CHROME,a=CHROME_ALPHA,radius=9f)
                    text("fps",b.cx-35*d,py-26*d,70f,14f,"${i.fps} fps",11f,col=Colour.INK)
                    text("fps-minus",b.cx-56*d,py-27*d,20f,16f,"−",13f,col=Colour.INK,tip="One frame per second slower")
                    text("fps-plus",b.cx+36*d,py-27*d,20f,16f,"+",13f,col=Colour.INK,tip="One frame per second faster")
                }
                for(n in 0 until ceil(b.width/(9*d)).toInt())for(row in 0..1)add("sprocket-$row-$n",rect(b.left+(n*9+2.5f)*d,sy+(if(row==0)1.5f else 34.5f)*d,4f,4f),col=Colour.PAPER,a=.55f,stroke=1f,radius=1f,clip=strip)
            }else add("ruler-line",Rect(b.left, b.bottom+28*d,b.right-22*d,b.bottom+28*d),Shape.LINE,Colour.PAPER,RULER_ALPHA*.55f,stroke=1f)
            val model=Board("chrome",i.name,i.kind,RectPx(0,0,1,1),frames=i.holds.mapIndexed { n,h->Frame("$n",h) })
            val film=FilmStrip(model,d)
            var heldTicks=0L
            for(n in i.holds.indices){
                val firstElement=e.size
                val cellLeft=heldTicks*film.tickPx
                val x=b.left+(if(folded)4*d+cellLeft*14/44 else 8*d+cellLeft)-i.stripScrollPx
                val y=if(folded)b.bottom+16*d else sy+7*d
                val r=if(folded)rect(x,y,if(n+1==i.currentFrame)2f else 1f,if(n+1==i.currentFrame)12f else 6f) else rect(x,y,(film.cellWidth(n)/d)-4f,26f)
                val col=if(i.liftedFrame==n+1)Colour.AMBER else if(n+1==i.currentFrame)Colour.PINK else Colour.PAPER
                add("cell-$n",r,col=col,a=if(folded)RULER_ALPHA else if(col==Colour.PAPER).55f else 1f,stroke=if(folded)0f else if(col==Colour.PAPER)1f else 1.5f,radius=if(folded)0f else 5f,
                    rotation=if(i.liftedFrame==n+1)-2f else 0f,scale=if(i.liftedFrame==n+1)1.1f else 1f,clip=if(folded)rect(b.left,b.bottom+10*d,b.width/d,22f) else strip)
                if(!folded || n+1==i.currentFrame)text("cell-number-$n",if(folded)x+4*d else r.left,if(folded)y-2*d else y+8*d,if(folded)18f else r.width/d,12f,"${n+1}",col=col,a=if(folded)RULER_ALPHA else 1f)
                control("cell-$n",if(folded)rect(x,y,i.holds[n]*14f,12f) else r,BoardChromeLabels.frame(n+1))
                if(!folded && i.holds[n]>1)text("hold-$n",r.right-20*d,r.top+2*d,17f,8f,"×${i.holds[n]}",7f,col=Colour.AMBER)
                if(!folded && i.liftedFrame==n+1)for(index in firstElement until e.size){
                    e[index]=e[index].copy(rotationDeg=-2f,scale=1.1f,transformOrigin=Point(r.cx,r.cy))
                }
                heldTicks+=i.holds[n].toLong()
            }
            if(!folded){
                val ar=rect(b.left+8*d+heldTicks*film.tickPx-i.stripScrollPx,sy+7*d,26f,26f)
                add("add-cell",ar,col=Colour.PAPER,a=.55f,stroke=1f,radius=5f,dashed=true,clip=strip)
                glyph("add",centre(ar.cx,ar.cy,11f,11f),"plus",BoardChromeLabels.ADD,Colour.PAPER)
                i.insertionIndex?.let {n->val ticks=i.holds.take(n.coerceIn(0,i.holds.size)).sumOf {it.toLong()}
                    add("insertion",rect(b.left+8*d+ticks*film.tickPx-i.stripScrollPx-2*d,sy+3*d,2f,34f),col=Colour.CYAN,radius=1f,halo=3f,clip=strip)}
            }
        }
        if(i.kind==BoardKind.SPRITE){
            val y=b.bottom+8*d;add("shelf",rect(b.left,y,245f,30f),col=Colour.CHROME,a=CHROME_ALPHA,radius=9f)
            val xs=listOf(5f,31f,65f,85f,99f,128f,148f,162f,188f)
            val names=listOf("grid-count","grid-px","cols-minus","cols","cols-plus","rows-minus","rows","rows-plus","subgrid")
            val labels=listOf("#","px","−","${if(i.gridByPixels)i.cellWidth else i.columns}","+","−","${if(i.gridByPixels)i.cellHeight else i.rows}","+","▦ ${i.subGrid}")
            val tips=listOf("Set the grid by how many cells","Set the grid by cell size in pixels","One column fewer","Columns","One column more","One row fewer","Rows","One row more","Sub-grid: a drawing guide inside every cell, never exported (2 to 8)")
            for(n in names.indices){val w=if(n==8)44f else if(n<2)24f else if(n==3||n==6)14f else 20f
                val r=rect(b.left+xs[n]*d,y+4*d,w,22f)
                if(n==8 || n==if(i.gridByPixels)1 else 0)add("${names[n]}-on",r,col=Colour.SPRITE_ON,a=.7f,radius=11f)
                text(names[n],r.left,r.top,w,22f,labels[n],if(n==2||n==4||n==5||n==7)13f else 10f,col=Colour.INK,tip=tips[n])
            }
            text("grid-times",b.left+119*d,y+4*d,8f,22f,"×",10f,col=Colour.DIM)
            val gridModel=Board("chrome-grid",i.name,BoardKind.SPRITE,RectPx(0,0,i.columns,i.rows),grid=SpriteGrid(i.columns,i.rows,1,1))
            val cw=b.width/i.columns; val ch=b.height/i.rows
            fun cell(n:Int):Rect {val r=SpriteGridMath.cellRect(gridModel,n);return Rect(b.left+r.x*cw,b.top+r.y*ch,b.left+(r.x+r.w)*cw,b.top+(r.y+r.h)*ch)}
            for(n in 0 until i.columns*i.rows){val r=cell(n);control("sprite-cell-$n",r,if(i.armed)BoardChromeLabels.SPRITE_OFF else "Cell ${n+1}. Tap to add it to the preview order.")}
            for(n in 1 until i.columns)add("grid-column-$n",Rect(b.left+n*cw,b.top,b.left+n*cw,b.bottom),Shape.LINE,Colour.PAPER,.35f,stroke=1f)
            for(n in 1 until i.rows)add("grid-row-$n",Rect(b.left,b.top+n*ch,b.right,b.top+n*ch),Shape.LINE,Colour.PAPER,.35f,stroke=1f)
            if(i.subGrid in 2..8)SpriteGridMath.subGridLines(gridModel,i.subGrid).forEachIndexed {n,l->
                add("subgrid-$n",Rect(b.left+l[0]*cw,b.top+l[1]*ch,b.left+l[2]*cw,b.top+l[3]*ch),Shape.LINE,Colour.PAPER,.15f,stroke=.5f)
            }
            val cellLabel="Cell ${i.cellWidth} × ${i.cellHeight} px"
            val labelWidth=cellLabel.length*5.4f
            text("cell-size",b.right-labelWidth*d,b.bottom+43*d,labelWidth,12f,cellLabel,9f,weight=500,tip="Cell size in whole pixels. Tap to resize every cell and the board.")
            if(i.armed){
                val t=(i.wiggleElapsedMs%600)/300f;val angle=if(i.reducedMotion)0f else if(t<=1)-2.2f+4.4f*t else 6.6f-4.4f*t
                for(n in 0 until i.columns*i.rows){val r=cell(n);add("sprite-cell-$n",r,col=Colour.PAPER,a=.35f,stroke=1f,rotation=angle*(if(n%2==0)1 else -1))}
                i.targetCell?.let {add("sprite-target",cell(it),col=Colour.CYAN,stroke=1.5f,radius=3f,halo=3f)}
                i.liftedCell?.let { val r=cell(it); val p=i.liftedCellOffset
                    add("sprite-lift",Rect(r.left+p.x,r.top+p.y,r.right+p.x,r.bottom+p.y),col=Colour.AMBER,stroke=1.5f,radius=4f,rotation=-3f,scale=1.08f) }
            }else if(i.spriteOrder.isNotEmpty()){
                i.spriteOrder.forEachIndexed { order,n->val r=cell(n);val col=if(n==i.playingCell)Colour.PINK else Colour.CYAN
                    if(n==i.playingCell)add("sprite-playing",Rect(r.left+d,r.top+d,r.right-d,r.bottom-d),col=col,stroke=2.5f,radius=3f)
                    val badge=rect(r.cx-7.5f*d,r.top+3*d,15f,15f);add("order-$order",badge,col=col,radius=7.5f)
                    text("order-number-$order",badge.left,badge.top,15f,15f,"${order+1}",9f,weight=700,col=Colour.ON_GRADIENT)
                }
                val x=b.right-84*d;val py=b.top-114*d
                add("preview",rect(x,py,84f,87f),col=Colour.CHROME,a=CHROME_ALPHA,radius=12f)
                control("preview",rect(x,py,84f,87f),"Preview: loops the cells in the order you tapped them")
                add("preview-art",rect(x+4*d,py+4*d,76f,56f),col=Colour.PAPER_SURFACE,radius=8f)
                glyph("preview-play",rect(x+8*d,py+66*d,11f,11f),if(i.playing)"pause" else "play",if(i.playing)"Pause the preview" else "Play the preview")
                text("preview-fps",x+27*d,py+65*d,31f,12f,"${i.fps} fps",8.5f,weight=500,col=Colour.INK)
                glyph("preview-clear",rect(x+64*d,py+67*d,10f,10f),"x","Clear the order and start again")
            }
        }
        return Layout(e,c,false,false,folded,if(i.kind==BoardKind.ANIMATION&&!folded)b.left+b.width*(1-STRIP_FADE_FRACTION) else null)
    }

    fun layerMarker(row: Rect, density: Float, animated: Boolean, boardName: String): Layout {
        require(density>0 && density.isFinite())
        val r=Rect(row.left+1.5f*density,row.cy-6.5f*density,row.left+14.5f*density,row.cy+6.5f*density)
        val hit=Rect(row.left-12*density,row.cy-20*density,row.left+28*density,row.cy+20*density)
        val tip=if(animated)"Moves in $boardName: its pixels change on every frame. Tap to keep it the same on every frame." else "The same on every frame of $boardName (a held background). Tap to make it move."
        return Layout(listOf(Element("layer-marker",Shape.GLYPH,r,if(animated)Colour.INK else Colour.GREY,glyph=if(animated)"runner" else "mountain",gradient=if(animated)BoardKind.ANIMATION else null)),listOf(Control("layer-marker",r,hit,tip)),false,false,false)
    }
    /** Nearest visual centre resolves enlarged, overlapping touch targets deterministically. */
    fun containsHit(layout: Layout, id: String, point: Point): Boolean = layout.controls.firstOrNull { it.id == id }?.let {
        point.x >= it.hit.left && point.x <= it.hit.right && point.y >= it.hit.top && point.y <= it.hit.bottom
    } == true
    fun hit(layout: Layout, point: Point, enabledOnly: Boolean = true): Control? = layout.controls.filter {
        (!enabledOnly || it.enabled) &&
        point.x>=it.hit.left && point.x<=it.hit.right && point.y>=it.hit.top && point.y<=it.hit.bottom
    }.minByOrNull { hypot(point.x-it.visual.cx,point.y-it.visual.cy) }
}

/** Verbatim §K page hover labels; no UI wording is invented by the renderer. */
object BoardChromeLabels {
    const val TITLE="The board's name; it is also the export's file name. Tap to rename."
    const val WIDTH="Width in pixels. While unlocked, tap and type; the board grows to the right."
    const val WHEEL="The frame this board shows. Drag up or down to step through the frames."
    const val TILE_OFF="Turn tiling on: your art repeats around the board so you can see the seams, and every stroke wraps into it."
    const val TILE_ON="Tiling is on. Tap to turn it off; it becomes a plain image board again."
    const val SPRITE_OFF="Rearrange: drag one cell onto another to swap them, every layer at once."
    const val SPRITE_ON="Rearranging. Tap again to set the cells where you have put them."
    const val LOCKED="Locked in place: painting near the board can never move or resize it. Tap to unlock."
    const val UNLOCKED="Unlocked: drag an edge or type a number to resize. Tap to lock it in place."
    const val FIXED="Fixed in place because this board has frames. Hold to move the board with all its frames."
    const val EXPORT_ANIM="Export: the whole animation, a range of frames, or just this frame."
    const val EXPORT_IMAGE="Export this board as a picture, or every image board at once."
    const val EXPORT_TILE="Export the tile as a picture."
    const val EXPORT_SPRITE="Export the sheet and its JSON, or open it in SpriteLab."
    const val ONION="Onion skin: see the frames before and after this one."
    const val LOOP="Playback: loop. Tap for ping-pong, tap again to play once."
    const val ADD="Add a frame (a copy of this one). Hold for blank, link, hold longer or delete."
    fun frame(n:Int)="Frame $n. Tap to show it; drag sideways to move it, drag up to remove it; drag its right edge to hold it longer."
}

/** Hover playback is ephemeral: it never changes selection or writes the stored current frame. */
data class BoardHoverLoop(val savedFrame: Int? = null) {
    enum class Pointer { STYLUS, MOUSE, FINGER }
    fun enter(pointer: Pointer, kind: BoardKind, selected: Boolean, currentFrame: Int): BoardHoverLoop =
        if(pointer!=Pointer.FINGER && kind==BoardKind.ANIMATION && !selected && savedFrame==null) copy(savedFrame=currentFrame) else this
    fun exit(): Pair<BoardHoverLoop,Int?> = BoardHoverLoop() to savedFrame
    val playing get() = savedFrame!=null
}
