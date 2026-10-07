package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.*
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import kotlin.math.*

/** Board adapter geometry only. Brush pressure, stamp shape, colour and paper response remain unchanged. */
internal object TilePainting {
    const val MAX_COPIES = 256
    const val MAX_TILES = 256

    fun board(document: JbDocument, id: String): Board {
        val board = document.boards.firstOrNull { it.id == id } ?: error("The tile board no longer exists")
        require(board.kind == BoardKind.CANVAS) { "Only an Image board can be tiled" }
        val r = board.rect
        require(r.w > 0 && r.h > 0 && r.x.toLong() + r.w <= Int.MAX_VALUE && r.y.toLong() + r.h <= Int.MAX_VALUE)
        require(r.w.toLong() * r.h <= MAX_REGION_PX) { "This tile is too large to preview on this phone" }
        val columns = Math.floorDiv(r.x.toLong() + r.w - 1, Tiles.SIZE.toLong()) - Math.floorDiv(r.x.toLong(), Tiles.SIZE.toLong()) + 1
        val rows = Math.floorDiv(r.y.toLong() + r.h - 1, Tiles.SIZE.toLong()) - Math.floorDiv(r.y.toLong(), Tiles.SIZE.toLong()) + 1
        require(columns * rows <= MAX_TILES) { "This tile touches too many painting tiles on this phone" }
        return board
    }

    fun clip(rect: RectPx, key: Long): RegionTileRect? {
        val x = Tiles.tx(key).toLong() * Tiles.SIZE; val y = Tiles.ty(key).toLong() * Tiles.SIZE
        val left = maxOf(0L, rect.x.toLong() - x); val top = maxOf(0L, rect.y.toLong() - y)
        val right = minOf(Tiles.SIZE.toLong(), rect.x.toLong() + rect.w - x)
        val bottom = minOf(Tiles.SIZE.toLong(), rect.y.toLong() + rect.h - y)
        return if (right <= left || bottom <= top) null else RegionTileRect(left.toInt(), top.toInt(), (right-left).toInt(), (bottom-top).toInt())
    }

    fun intersect(a: RegionTileRect, b: RegionTileRect): RegionTileRect? {
        val x = maxOf(a.x,b.x); val y = maxOf(a.y,b.y)
        val right = minOf(a.right,b.right); val bottom = minOf(a.bottom,b.bottom)
        return if(right <= x || bottom <= y) null else RegionTileRect(x,y,right-x,bottom-y)
    }

    private fun translations(rect: RectPx, left: Double, top: Double, right: Double, bottom: Double,
                             anchorX: Double, anchorY: Double): List<Pair<Float,Float>> {
        require(listOf(left,top,right,bottom,anchorX,anchorY).all { it.isFinite() }) { "The brush footprint is not finite" }
        // Bring the footprint's anchor close to the board first; retain its entire shape across seams.
        val baseX = -floor((anchorX-rect.x)/rect.w) * rect.w
        val baseY = -floor((anchorY-rect.y)/rect.h) * rect.h
        val x0d = floor((rect.x-left-baseX- (right-left))/rect.w)+1
        val x1d = ceil((rect.x.toDouble()+rect.w-left-baseX)/rect.w)-1
        val y0d = floor((rect.y-top-baseY- (bottom-top))/rect.h)+1
        val y1d = ceil((rect.y.toDouble()+rect.h-top-baseY)/rect.h)-1
        // Check in floating point before conversion: saturated Long endpoints could
        // overflow subtraction and turn an enormous range into a negative "small" count.
        val columns=x1d-x0d+1; val rows=y1d-y0d+1
        require(columns in 1.0..MAX_COPIES.toDouble() && rows in 1.0..MAX_COPIES.toDouble() &&
            columns*rows <= MAX_COPIES && listOf(x0d,x1d,y0d,y1d).all {
                it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble()
            }) { "Choose a smaller brush for this tile" }
        val x0=x0d.toLong(); val x1=x1d.toLong(); val y0=y0d.toLong(); val y1=y1d.toLong()
        return buildList { for(y in y0..y1) for(x in x0..x1) add((baseX+x*rect.w).toFloat() to (baseY+y*rect.h).toFloat()) }
    }

    fun dabs(rect: RectPx, dab: Dab): List<Dab> {
        if(dab.radius <= 0f || dab.flow <= 0f || dab.cap <= 0f) return emptyList()
        val e = TipMath.extent(dab.radius,dab.anchor).toDouble()
        return translations(rect,dab.x-e,dab.y-e,dab.x+e,dab.y+e,dab.x.toDouble(),dab.y.toDouble())
            .map { (x,y) -> dab.copy(x=dab.x+x,y=dab.y+y) }
    }

    fun stamps(rect: RectPx, stamp: TuftStamp): List<TuftStamp> {
        if(stamp.flow <= 0f || stamp.cap <= 0f) return emptyList()
        val b=TuftMath.bounds(stamp)
        return translations(rect,b[0].toDouble(),b[1].toDouble(),b[2].toDouble(),b[3].toDouble(),stamp.ax.toDouble(),stamp.ay.toDouble())
            .map { (x,y) -> stamp.copy(ax=stamp.ax+x,ay=stamp.ay+y,bx=stamp.bx+x,by=stamp.by+y) }
    }
}
