package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import cc.joycreator.joybrush.core.doc.BoardKind
import kotlin.math.*

/** K6's reusable presentation model. No exporter, range validation, clock or document store. */
object BoardExportLayout {
    enum class Scope(val label: String) { ANIMATION("Animation"), RANGE("A range of frames"), FRAME("This frame"), BOARD("This board"), ALL_IMAGES("All image boards") }
    enum class Format(val label: String) { GIF("GIF"), PNG_FRAMES("PNG frames"), SPRITE_SHEET("Sprite sheet"), STUDIO("Send to Studio"), PNG("PNG"), SPRITE_LAB("Open in SpriteLab") }
    val chipFormats = listOf(Format.GIF, Format.PNG_FRAMES, Format.SPRITE_SHEET, Format.STUDIO)
    data class Choice(val boardId: String, val scope: Scope, val format: Format,
        val currentFrameId: String, val rangeStartId: String, val rangeEndId: String)
    data class Input(val sheet: Chrome.Rect, val density: Float, val boardId: String, val name: String,
        val frameIds: List<String>, val currentFrameId: String, val rangeStartId: String, val rangeEndId: String,
        val pixelWidth: Int, val pixelHeight: Int, val fps: String, val layerCount: Int,
        val memoryFraction: Float, val scope: Scope = Scope.ANIMATION, val format: Format = Format.GIF,
        val availableScopes: Set<Scope> = emptySet(), val availableFormats: Set<Format> = emptySet(),
        val exportEnabled: Boolean = false, val rangePreviewStart: Float = .2f, val rangePreviewEnd: Float = .7f,
        val formatTextWidthsPx: Map<Format, Float> = emptyMap(), val headerTextWidthPx: Float? = null,
        val budgetTextWidthPx: Float? = null, val percentTextWidthPx: Float? = null,
        val displayedScopes: List<Scope> = listOf(Scope.ANIMATION, Scope.RANGE, Scope.FRAME),
        val displayedFormats: List<Format> = chipFormats, val cellCount: Int? = null) {
        val identity = BoardChromeIdentity(boardId, frameIds)
        init {
            require(density.isFinite() && density > 0 && sheet.width > 0)
            require(identity.frameIds.isNotEmpty() && currentFrameId in identity.frameIds)
            require(memoryFraction.isFinite() && memoryFraction in 0f..1f)
            require(rangePreviewStart.isFinite() && rangePreviewEnd.isFinite() &&
                rangePreviewStart in 0f..1f && rangePreviewEnd in rangePreviewStart..1f)
        }
        fun choice() = Choice(boardId, scope, format, currentFrameId, rangeStartId, rangeEndId)
        fun canExport() = exportEnabled && scope in availableScopes && format in availableFormats
    }
    const val HEIGHT_DP = 236f
    const val TOP_RADIUS_DP = 20f
    const val BUDGET_TOOLTIP = "How much of this board's memory budget its frames use"

    fun contains(i: Input, point: Chrome.Point) = point.x >= i.sheet.left && point.x < i.sheet.right &&
        point.y >= i.sheet.top && point.y < i.sheet.top + HEIGHT_DP * i.density

    fun layout(i: Input): Chrome.Layout {
        require(i.displayedFormats.all { i.formatTextWidthsPx[it]?.let { width -> width.isFinite() && width >= 0 } == true } &&
            i.headerTextWidthPx != null && i.budgetTextWidthPx != null && i.percentTextWidthPx != null) {
            "Export presentation requires measured shared-font label widths"
        }
        val d = i.density; val s = i.sheet; val e = arrayListOf<Chrome.Element>(); val c = arrayListOf<Chrome.Control>()
        fun rect(x: Float, y: Float, w: Float, h: Float) = Chrome.Rect(s.left + x*d, s.top + y*d, s.left + (x+w)*d, s.top + (y+h)*d)
        val width = s.width / d
        fun shape(id: String, r: Chrome.Rect, colour: Chrome.Colour = Chrome.Colour.INK, alpha: Float = 1f,
            radius: Float = 0f, stroke: Float = 0f, halo: Float = 0f, gradient: BoardKind? = null) {
            e += Chrome.Element(id, Chrome.Shape.ROUND_RECT, r, colour, alpha, stroke*d, radius*d,
                gradient = gradient, haloPx = halo*d)
        }
        fun text(id: String, r: Chrome.Rect, value: String, size: Float, font: Chrome.Font = Chrome.Font.SANS,
            weight: Int = 600, colour: Chrome.Colour = Chrome.Colour.INK, alpha: Float = 1f,
            align: Chrome.Align = Chrome.Align.LEFT) {
            e += Chrome.Element(id, Chrome.Shape.TEXT, r, colour, alpha, text = value, font = font,
                sizeSp = size, weight = weight, align = align, baselinePx = r.cy + size*d*.35f)
        }
        fun control(id: String, r: Chrome.Rect, tooltip: String, enabled: Boolean) {
            val w = max(Chrome.TOUCH_DP*d, r.width); val h = max(Chrome.TOUCH_DP*d, r.height)
            c += Chrome.Control(id, r, Chrome.Rect(r.cx-w/2, r.cy-h/2, r.cx+w/2, r.cy+h/2), tooltip, enabled)
        }
        shape("export-panel", rect(0f, 0f, width, HEIGHT_DP), Chrome.Colour.GLASS, Chrome.GLASS_ALPHA)
        e[e.lastIndex] = e.last().copy(cornerRadiiPx = listOf(TOP_RADIUS_DP*d, TOP_RADIUS_DP*d, 0f, 0f))
        // The record's flex sheet: fixed text/chips/button plus 7 eight-dp gaps and 18 dp
        // padding leave 83 dp for the shrinking grab + three nominal 32-dp option rows.
        val shrink = (HEIGHT_DP-18f-7*8f-14.5f-24f-8.5f-32f)/(3.5f+3*32f)
        val grabHeight = 3.5f*shrink; val rowHeight = 32f*shrink
        shape("export-grab", rect((width-38f)/2, 6f, 38f, grabHeight), alpha=.3f, radius=2f)
        var y = 6f+grabHeight+8f
        val headerWidth=i.headerTextWidthPx!!/d
        text("export-title", rect(14f,y,headerWidth,14.5f), "Export",14.5f,Chrome.Font.ARCHIVO,800)
        text("export-metadata",rect(14f+headerWidth+8f,y,width-36f-headerWidth,14.5f),"${i.name} · ${i.pixelWidth}×${i.pixelHeight}" + if(i.fps.isNotEmpty()) " · ${i.fps} fps" else "",8.5f,Chrome.Font.MONO,500,Chrome.Colour.DIM)
        y += 14.5f+8f
        for (scope in i.displayedScopes) {
            val enabled = scope in i.availableScopes; val alpha = if (enabled) 1f else .35f
            val r = rect(14f,y,width-28f,rowHeight); val id = "export-scope-${scope.name.lowercase()}"
            shape(id,r,alpha=.06f,radius=10f)
            if (scope==i.scope) shape("$id-ring",r,Chrome.Colour.CYAN,alpha,radius=10f,stroke=1.5f,halo=3f)
            shape("$id-radio",rect(24f,y+(rowHeight-13f)/2,13f,13f),
                if(scope==i.scope)Chrome.Colour.CYAN else Chrome.Colour.INK,
                alpha*(if(scope==i.scope)1f else .5f),radius=6.5f,stroke=if(scope==i.scope)4f else 1.5f)
            text("$id-label",rect(46f,y,width-156f,rowHeight),scope.label,12f,alpha=alpha)
            val detail = when(scope) {
                Scope.ANIMATION -> "all ${i.identity.frameIds.size} frames"
                Scope.FRAME -> "frame ${i.identity.frameIds.indexOf(i.currentFrameId)+1} · ${if(i.scope==Scope.FRAME)i.format.label else "PNG"}"
                Scope.RANGE -> {
                    val a=i.identity.frameIds.indexOf(i.rangeStartId); val b=i.identity.frameIds.indexOf(i.rangeEndId)
                    if(a>=0 && b>=0) "${a+1}–${b+1}" else ""
                }
                Scope.BOARD -> i.format.label
                Scope.ALL_IMAGES -> "PNG files"
            }
            text("$id-detail",rect(width-104f,y,80f,rowHeight),detail,9f,Chrome.Font.MONO,500,Chrome.Colour.DIM,alpha,Chrome.Align.RIGHT)
            if(scope==Scope.RANGE) {
                val x=width-126f; val ry=y+rowHeight/2
                shape("export-range-rail",rect(x,ry-1f,64f,2f),alpha=.2f,radius=1f)
                shape("export-range-span",rect(x+64*i.rangePreviewStart,ry-1f,64*(i.rangePreviewEnd-i.rangePreviewStart),2f),alpha=alpha)
                for((n,fraction) in listOf(i.rangePreviewStart,i.rangePreviewEnd).withIndex())
                    shape("export-range-knob-$n",rect(x+64*fraction-5f,ry-5f,10f,10f),alpha=alpha,radius=5f)
            }
            control(id,r,scope.label,enabled)
            y += rowHeight+8f
        }
        var x=14f
        // Static boards keep the same 236dp sheet, with the unused scope rows as breathing room.
        y += (3-i.displayedScopes.size).coerceAtLeast(0)*(rowHeight+8f)
        for(format in i.displayedFormats) {
            val enabled=format in i.availableFormats; val alpha=if(enabled)1f else .35f
            val chipWidth=i.formatTextWidthsPx.getValue(format)/d+20f
            val r=rect(x,y,chipWidth,24f); val id="export-format-${format.name.lowercase()}"
            shape(id,r,if(format==i.format)Chrome.Colour.ANIMATION_ON else Chrome.Colour.INK,if(format==i.format).7f*alpha else .1f,12f)
            text("$id-label",r,format.label,10.5f,colour=if(format==i.format)Chrome.Colour.ON_GRADIENT else Chrome.Colour.DIM,
                alpha=alpha,align=Chrome.Align.CENTRE)
            control(id,r,format.label,enabled)
            x += chipWidth+5f
        }
        y += 24f+8f
        val budgetWidth=i.budgetTextWidthPx!!/d; val percentWidth=i.percentTextWidthPx!!/d
        text("export-budget-label",rect(14f,y,budgetWidth,8.5f),
            (i.cellCount?.let { "cells $it" } ?: "frames ${i.identity.frameIds.size}") + " × layers ${i.layerCount}",8.5f,Chrome.Font.MONO,500,Chrome.Colour.DIM)
        val barLeft=21f+budgetWidth; val barWidth=max(0f,width-21f-percentWidth-barLeft)
        shape("export-budget-track",rect(barLeft,y+2.75f,barWidth,3f),alpha=.15f,radius=2f)
        shape("export-budget-value",rect(barLeft,y+2.75f,barWidth*i.memoryFraction,3f),radius=2f)
        text("export-budget-percent",rect(width-14f-percentWidth,y,percentWidth,8.5f),"${(i.memoryFraction*100).roundToInt()}%",8.5f,Chrome.Font.MONO,500,Chrome.Colour.DIM,align=Chrome.Align.RIGHT)
        control("export-budget",rect(14f,y,width-28f,8.5f),BUDGET_TOOLTIP,false)
        y += 8.5f+8f
        val button=rect(14f,y,width-28f,32f);val ready=i.canExport()
        shape("export-go",button,alpha=if(ready)1f else .35f,radius=16f,gradient=BoardKind.ANIMATION)
        text("export-go-label",button,"Export",13f,weight=700,colour=Chrome.Colour.ON_GRADIENT,
            alpha=if(ready)1f else .35f,align=Chrome.Align.CENTRE)
        val formatLabel=if(i.format==Format.GIF)"a GIF" else i.format.label
        control("export-go",button,"Export ${i.name} as $formatLabel",ready)
        return Chrome.Layout(e,c,false,false,false)
    }
}
