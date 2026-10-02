package cc.joycreator.joybrush.core.paper

import cc.joycreator.joybrush.core.brush.joybrushRoot
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.grain.GrainMath
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.test.*

class LaunchMaterialsTest {
    private val dir = File(joybrushRoot(), "assets/paper")
    private val c get() = PaperCatalogues.parse(File(dir, "catalogue.json").readText())
    private fun texture(file: String): PaperTexture {
        val image = ImageIO.read(File(dir, file))
        val rgba = ByteArray(image.width * image.height * 4)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y); val i = (y * image.width + x)*4
            rgba[i]=(p ushr 16).toByte(); rgba[i+1]=(p ushr 8).toByte()
            rgba[i+2]=p.toByte(); rgba[i+3]=(p ushr 24).toByte()
        }
        return PaperTexture(image.width, image.height, rgba)
    }
    private fun channel(t: PaperTexture, q: Int) = DoubleArray(t.w*t.h) { (t.rgba[it*4+q].toInt() and 255).toDouble() }
    private fun std(v: DoubleArray): Double { val mean=v.average(); return sqrt(v.sumOf { (it-mean).pow(2) } / v.size) }
    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        val am=a.average(); val bm=b.average()
        return a.indices.sumOf { (a[it]-am)*(b[it]-bm) } / sqrt(
            a.sumOf { (it-am).pow(2) } * b.sumOf { (it-bm).pow(2) })
    }
    @Test fun everyRequestedMaterialIsSelectableAndTheLibraryFitsItsBudget() {
        val looks=setOf("off_white", "amoled_black", "canvas_linen", "canvas_cotton_duck", "canvas_jute",
            "pulp_factory", "pulp_handmade", "rice_cool", "rice_cream", "sugarcane", "construction_tan",
            "chalkboard_black", "chalkboard_green", "blueprint", "parchment", "papyrus", "crumpled",
            "crumpled_flattened", "cement", "fabric", "silk")
        assertTrue(c.looks.map { it.id }.toSet().containsAll(looks))
        assertEquals(emptyList(), PaperCatalogues.problems(c))
        for (look in c.looks.filter { it.id != "amoled_black" }) assertNotNull(look.defaultSurface, look.id)
        assertTrue(dir.listFiles()!!.filter { it.extension == "png" }.sumOf { it.length() } <= 25L*1024*1024)
    }
    @Test fun everyPhysicalTextureMatchesTheKotlinPackingTwinExactly() {
        for (s in c.surfaces) {
            val t=texture(s.file)
            val height=ByteArray(t.w*t.h) { t.rgba[it*4+2] }
            assertContentEquals(t.rgba, SurfaceMaps.pack(height,t.w,t.h,s.slopeRange),s.id)
            assertTrue(std(channel(t,2)) > 2., "${s.id} is physically flat")
        }
    }
    @Test fun picturedLooksAndTheirDefaultSurfacesUseIdenticalSamplingGeometryAndMeasuredMeans() {
        for (look in c.looks.filter { it.file != null }) {
            val s=assertNotNull(PaperCatalogues.surface(c, assertNotNull(look.defaultSurface)))
            val t=texture(assertNotNull(look.file))
            assertEquals(s.size,t.w,look.id); assertEquals(s.size,t.h,look.id)
            assertEquals(s.texelPx,look.texelPx,look.id); assertEquals(s.hexTexels,look.hexTexels,look.id)
            assertEquals(s.rotatable,look.rotatable,look.id)
            val mean=assertNotNull(look.mean)
            for (q in 0..2) assertTrue(abs(channel(t,q).average()-mean.substring(1+2*q,3+2*q).toInt(16)) <= .51,look.id)
        }
    }
    @Test fun sourceWrapHasNoStrongerJoinThanOrdinaryNeighbourVariation() {
        fun check(file: String, q: Int) {
            val t=texture(file); val values=channel(t,q)
            val edges=mutableListOf<Double>(); val interior=mutableListOf<Double>()
            for (y in 0 until t.h) for (x in 0 until t.w) {
                val v=values[y*t.w+x]
                if (x==t.w-1) edges.add(abs(v-values[y*t.w])) else interior.add(abs(v-values[y*t.w+x+1]))
                if (y==t.h-1) edges.add(abs(v-values[x])) else interior.add(abs(v-values[(y+1)*t.w+x]))
            }
            edges.sort(); interior.sort()
            val edge95=edges[(edges.size*.95).toInt()]
            val ordinary95=interior[(interior.size*.95).toInt()]
            assertTrue(edge95 <= max(1.,ordinary95)*1.65, "$file channel$q has a wrap join: $edge95 vs $ordinary95")
        }
        for (s in c.surfaces) check(s.file,2)
        for (l in c.looks) l.file?.let { check(it,0) }
    }
    @Test fun flattenedCreasesAreTheSameGeometryWithLessPhysicalHeightAndSlope() {
        val full=texture(assertNotNull(PaperCatalogues.surface(c,"crumpled")).file)
        val subtle=texture(assertNotNull(PaperCatalogues.surface(c,"crumpled_flattened")).file)
        assertTrue(corr(channel(full,2),channel(subtle,2)) > .995)
        val ratio=std(channel(subtle,2))/std(channel(full,2))
        assertTrue(ratio in .15.. .30,"height ratio $ratio")
        val slopes=std(channel(subtle,0))/std(channel(full,0))
        assertTrue(slopes in .05.. .50,"slope ratio $slopes")
    }
    @Test fun visibleWeaveRemainsAlignedWithBrushHeightAfterHexSampling() {
        for (id in listOf("canvas_linen","canvas_cotton_duck","canvas_jute","silk","fabric")) {
            val l=assertNotNull(PaperCatalogues.look(c,id)); val s=assertNotNull(PaperCatalogues.surface(c,id))
            val look=texture(assertNotNull(l.file)); val surface=texture(s.file)
            val colour=FloatArray(4); val height=FloatArray(4)
            val a=DoubleArray(512); val b=DoubleArray(512)
            for (i in a.indices) {
                val x=(i%32)*7.13; val y=(i/32)*13.27
                HexTile.sampleLook(look,x,y,l.hexTexels.toDouble(),l.rotatable,colour)
                HexTile.sampleSurface(surface,x,y,s.hexTexels.toDouble(),s.rotatable,s.slopeRange,height)
                a[i]=colour[0].toDouble(); b[i]=height[2].toDouble()
            }
            assertTrue(corr(a,b)>.94,"$id visible weave drifted away from brush height: ${corr(a,b)}")
        }
    }
    @Test fun physicalMaterialsCatchDifferentFacesAndWetValleys() {
        for (s in c.surfaces) {
            val t=texture(s.file); val out=FloatArray(4)
            var facing=0f; var pooling=0f
            for (i in 0 until 64) {
                val x=(i%8)*17.31; val y=(i/8)*23.19
                HexTile.sampleSurface(t,x,y,s.hexTexels.toDouble(),s.rotatable,s.slopeRange,out)
                val coarse=GrainMath.paperCoarseHeight(t,x,y,s.hexTexels.toDouble(),s.rotatable,s.slopeRange)
                val right=GrainMath.paperEffectiveHeight(out,coarse,1f,0f,s.slopeRange,1f,0f)
                val left=GrainMath.paperEffectiveHeight(out,coarse,-1f,0f,s.slopeRange,1f,0f)
                val wet=GrainMath.paperEffectiveHeight(out,coarse,1f,0f,s.slopeRange,1f,1f)
                facing+=abs(right-left); pooling+=abs(wet-out[2])
                assertTrue((wet-out[2])*(coarse-out[2]) >= -1e-7f,"${s.id} wet paint moves away from local valleys")
            }
            assertTrue(facing> .001f,"${s.id} directional response is inert")
            assertTrue(pooling> .001f,"${s.id} wet response is inert")
        }
    }
    @Test fun launchContactSheetUsesActualAlbedoAndReliefAtThreeScales() {
        val catalogue=c
        val looks=catalogue.looks
        val sheet=java.awt.image.BufferedImage(648,looks.size*156,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g=sheet.createGraphics(); g.color=java.awt.Color(235,235,235); g.fillRect(0,0,sheet.width,sheet.height)
        for ((row,l) in looks.withIndex()) {
            val s=l.defaultSurface?.let { PaperCatalogues.surface(catalogue,it) }
            val look=l.file?.let(::texture); val surface=s?.file?.let(::texture)
            for ((col,scale) in listOf(.25f,1f,4f).withIndex()) {
                val p=PaperState.resolve(Paper(lookId=l.id,textureId=l.defaultSurface,textureScale=scale),catalogue)
                val rgba=PaperRaster.render(p,look,surface,RectPx(0,0,144,128))
                for (y in 0 until 128) for (x in 0 until 144) {
                    val i=(y*144+x)*4
                    sheet.setRGB(col*216+x,row*156+y,((rgba[i].toInt() and 255) shl 16) or
                        ((rgba[i+1].toInt() and 255) shl 8) or (rgba[i+2].toInt() and 255))
                }
                g.color=java.awt.Color.BLACK; g.drawString("${l.name} ${scale}x",col*216+2,row*156+145)
            }
        }
        g.dispose(); val file=File(dir.parentFile.parentFile,"tools/paper/out/launch-contact.png")
        file.parentFile.mkdirs(); assertTrue(ImageIO.write(sheet,"png",file))
    }
}
