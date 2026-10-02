package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.chrome.BoardExportLayout.Input
import cc.joycreator.joybrush.core.chrome.BoardExportLayout.Scope
import cc.joycreator.joybrush.core.chrome.BoardExportLayout.Format
import cc.joycreator.joybrush.core.chrome.BoardExportLayout.Choice
import cc.joycreator.joybrush.core.chrome.BoardChromeLayout as Chrome
import kotlin.test.*

class BoardExportLayoutTest {
    private fun input() = Input(Chrome.Rect(0f,94f,352f,330f),1f,"walk","Walk cycle",
        (1..6).map {"f$it"},"f3","f2","f5",1920,1080,"12",3,.22f,
        availableScopes=Scope.entries.toSet(),availableFormats=BoardExportLayout.chipFormats.toSet(),exportEnabled=true,
        formatTextWidthsPx=mapOf(Format.GIF to 17f,Format.PNG_FRAMES to 50f,Format.SPRITE_SHEET to 57f,Format.STUDIO to 77f),
        headerTextWidthPx=35f,budgetTextWidthPx=90f,percentTextWidthPx=16f)
    private fun element(i:Input,id:String)=BoardExportLayout.layout(i).elements.first {it.id==id}

    @Test fun sheetAndGlassUseLockedHeightCornersAndTokens() {
        val panel=element(input(),"export-panel")
        assertEquals(236f,panel.rect.height)
        assertEquals(listOf(20f,20f,0f,0f),panel.cornerRadiiPx)
        assertEquals(.55f,panel.alpha);assertEquals(Chrome.Colour.GLASS,panel.colour)
        assertEquals(330f,panel.rect.bottom)
        assertEquals(12f,panel.rect.bottom-element(input(),"export-go").rect.bottom)
    }

    @Test fun pictureWordsFontsChipsRangeAndBudgetArePresent() {
        val i=input();val l=BoardExportLayout.layout(i)
        assertEquals("Export",element(i,"export-title").text)
        assertEquals(14.5f,element(i,"export-title").sizeSp)
        assertEquals(Chrome.Font.ARCHIVO,element(i,"export-title").font)
        assertEquals("all 6 frames",element(i,"export-scope-animation-detail").text)
        assertEquals("2–5",element(i,"export-scope-range-detail").text)
        assertEquals("frame 3 · PNG",element(i,"export-scope-frame-detail").text)
        assertEquals("22%",element(i,"export-budget-percent").text)
        assertEquals(3f,element(i,"export-budget-track").rect.height)
        assertEquals(24f,element(i,"export-format-gif").rect.height)
        assertEquals(37f,element(i,"export-format-gif").rect.width) // 17 measured + 20 padding.
        assertEquals(.7f,element(i,"export-format-gif").alpha)
        assertEquals(Chrome.Colour.ANIMATION_ON,element(i,"export-format-gif").colour)
        assertEquals(32f,element(i,"export-go").rect.height)
        assertEquals(Chrome.Colour.ON_GRADIENT,element(i,"export-go-label").colour)
        assertEquals(BoardExportLayout.BUDGET_TOOLTIP,l.controls.first {it.id=="export-budget"}.tooltip)
        assertEquals("Export Walk cycle as a GIF",l.controls.first {it.id=="export-go"}.tooltip)
        assertTrue(l.controls.all {it.hit.width>=40f && it.hit.height>=40f && it.tooltip.isNotEmpty()})
    }

    @Test fun unsupportedActionsRemainDisabledAndNeverHit() {
        val i=input().copy(availableScopes=emptySet(),availableFormats=emptySet(),exportEnabled=false)
        val l=BoardExportLayout.layout(i);assertTrue(l.controls.none {it.enabled})
        val go=l.controls.first {it.id=="export-go"}
        val point=Chrome.Point(go.visual.cx,go.visual.cy)
        assertNull(Chrome.hit(l,point))
        assertEquals("export-go",Chrome.hit(l,point,enabledOnly=false)?.id)
        assertFalse(i.canExport())
    }

    @Test fun exportChoiceCarriesStableIdsAndSingleFrameFormat() {
        val i=input().copy(scope=Scope.FRAME,format=Format.PNG,availableFormats=setOf(Format.PNG))
        assertTrue(i.canExport())
        assertEquals(Choice("walk",Scope.FRAME,Format.PNG,"f3","f2","f5"),i.choice())
        assertEquals("frame 3 · PNG",element(i,"export-scope-frame-detail").text)
        assertFalse(i.copy(exportEnabled=false).canExport())
        assertFalse(i.copy(availableScopes=emptySet()).canExport())
    }

    @Test fun densityAndContainmentAreLiteralAndMeasurementIsRequired() {
        val base=input();val i=base.copy(sheet=Chrome.Rect(0f,188f,704f,660f),density=2f,
            formatTextWidthsPx=base.formatTextWidthsPx.mapValues {it.value*2},headerTextWidthPx=70f,
            budgetTextWidthPx=180f,percentTextWidthPx=32f)
        assertEquals(472f,element(i,"export-panel").rect.height)
        assertEquals(40f,element(i,"export-panel").cornerRadiiPx!!.first())
        assertTrue(BoardExportLayout.contains(i,Chrome.Point(100f,188f)))
        assertFalse(BoardExportLayout.contains(i,Chrome.Point(100f,187.9f)))
        assertFalse(BoardExportLayout.contains(i,Chrome.Point(100f,660f)))
        assertFailsWith<IllegalArgumentException> {BoardExportLayout.layout(base.copy(formatTextWidthsPx=emptyMap()))}
        assertFailsWith<IllegalArgumentException> {base.copy(memoryFraction=Float.NaN)}
    }
}
