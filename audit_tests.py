from pathlib import Path
import re
root=Path('joybrush'); main=root/'core/src/commonMain/kotlin/cc/joycreator/joybrush/core'
test=root/'core/src/commonTest/kotlin/cc/joycreator/joybrush/core'
jvm=root/'core/src/jvmTest/kotlin/cc/joycreator/joybrush/core'
# Preserve existing UTF-8 text on Windows even when the process locale is legacy ANSI.
for p in list(main.rglob('*.kt'))+list((root/'shaders').glob('*'))+[root/'tools/paper/pack.py']:
    s=p.read_text(encoding='utf-8')
    if 'â' in s or 'Â' in s:
        try: p.write_text(s.encode('cp1252').decode('utf-8'),encoding='utf-8')
        except (UnicodeError): pass
def edit(p,f): p.write_text(f(p.read_text(encoding='utf-8')),encoding='utf-8')
p=test/'paper/SurfaceMapsTest.kt'; s=p.read_text(encoding='utf-8')
s=s.replace('At128','At127').replace('assertEquals(128, SurfaceMaps.encodeSlope','assertEquals(127, SurfaceMaps.encodeSlope').replace('assertEquals(255, SurfaceMaps.encodeSlope','assertEquals(254, SurfaceMaps.encodeSlope').replace('val halfStep = r / 255f','val halfStep = r / 254f')
start=s.index('    /**\n     * `decodeSlope'); end=s.index('    @Test',start)
s=s[:start]+'    /** Exact-zero encoding has 254 intervals; the worst round-trip error is r/254. */\n'+s[end:]
s=s.replace('    // ---- 3. every edge wraps', '''    @Test
    fun aSineRampInYReadsAsTheCentralDifferenceAndHasNoHorizontalSlope() {
        val w = 4; val h = 64
        val height = FloatArray(w * h) { i -> (0.5 + 0.5 * sin(2.0 * PI * (i / w) / h)).toFloat() }
        val (dx, dy) = SurfaceMaps.slopes(height, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val central = (height[((y + 1) % h) * w + x] - height[((y - 1 + h) % h) * w + x]) / 2f
            assertEquals(central, dy[y * w + x], 1e-6f, "dy at ($x,$y)")
            assertEquals(0f, dx[y * w + x], "dx at ($x,$y)")
        }
        assertEquals(0f, SurfaceMaps.decodeSlope(SurfaceMaps.encodeSlope(0f, 0.099f), 0.099f))
    }

    // ---- 3. every edge wraps''')
p.write_text(s,encoding='utf-8')
p=jvm/'paper/SurfaceAssetTest.kt'; s=p.read_text(encoding='utf-8'); start=s.index('    /**\n     * Rebuilding'); end=s.index('    @Test',start)
s=s[:start]+'    /** Rebuild the regenerated surface with Double arithmetic: zero differing bytes. */\n'+s[end:]
s=s.replace('worst <= 1','differing == 0').replace('by ±1 or more','by one byte or more')
p.write_text(s,encoding='utf-8')
edit(test/'paper/ImportedTextureTest.kt',lambda s:s.replace('assertEquals(128, single.rgba','assertEquals(SurfaceMaps.encodeSlope(0f, surface.slopeRange), single.rgba') if False else s)
# Replace slope literals only: height B=128 remains a height, not a slope.
p=test/'paper/ImportedTextureTest.kt'; s=p.read_text(encoding='utf-8')
s=s.replace('assertEquals(128, single.rgba','assertEquals(SurfaceMaps.encodeSlope(0f, single.slopeRange), single.rgba').replace('assertEquals(128, surface.rgba','assertEquals(SurfaceMaps.encodeSlope(0f, surface.slopeRange), surface.rgba').replace('r != 128 || g != 128','r != SurfaceMaps.encodeSlope(0f, surface.slopeRange) || g != SurfaceMaps.encodeSlope(0f, surface.slopeRange)').replace('val halfStep = range / 255f','val halfStep = range / 254f').replace('R = G = 128','R = G = encodeSlope(0f, range)')
p.write_text(s,encoding='utf-8')
edit(test/'paper/HexTileTest.kt',lambda s:s.replace('128.toByte() // flat slope','127.toByte() // flat slope').replace('px[o + 1] = 128.toByte()','px[o + 1] = 127.toByte()').replace('G is 128','G is 127'))
p=jvm/'grain/PencilOnAFingerTest.kt'; s=p.read_text(encoding='utf-8')
s=s.replace('import cc.joycreator.joybrush.core.brush.BrushJson','import cc.joycreator.joybrush.core.brush.BrushJson\nimport cc.joycreator.joybrush.core.paper.HexTile\nimport cc.joycreator.joybrush.core.paper.PaperTexture')
start=s.index('    private val heights:'); end=s.index('\n    private fun coverageAt',start)
s=s[:start]+'''    private val surface: PaperTexture = run {
        val img = ImageIO.read(File(root(), "assets/paper/${GrainMath.DEFAULT_SURFACE}"))
        val bytes = ByteArray(img.width * img.height * 4)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val argb = img.getRGB(x, y); val i = (y * img.width + x) * 4
            bytes[i] = (argb ushr 16).toByte(); bytes[i + 1] = (argb ushr 8).toByte()
            bytes[i + 2] = argb.toByte(); bytes[i + 3] = (argb ushr 24).toByte()
        }
        PaperTexture(img.width, img.height, bytes)
    }
    private val heights = Array(64) { y -> FloatArray(64) { x ->
        val out = FloatArray(4)
        HexTile.sampleSurface(surface, x * 8.0, y * 8.0, GrainMath.SURFACE_HEX_TEXELS.toDouble(),
            GrainMath.SURFACE_ROTATABLE, GrainMath.SURFACE_SLOPE_RANGE, out)
        out[2]
    } }
''' + s[end:]
s=s.replace('        // TODO(JB-9.02): call HexTile.sampleSurface at document points once its CPU twin lands.\n        // JB-9.03: B is height; this test exercises real surface texels and the NaN/threshold guards.','        // JB-9.03b: heights are the real CPU hex reads used by the GPU, not repeating B texels.')
p.write_text(s,encoding='utf-8')
p=test/'chrome/BrushTuningTest.kt'; s=p.read_text(encoding='utf-8')
start=s.index('    @Test\n    fun thePaperSlidersSitAfter'); end=s.index('    /**',start)
s=s[:start]+'''    @Test
    fun paperSlidersStayHiddenUntilTheirEngineIsLive() {
        assertFalse(BrushKnobs.PAPER_ENGINE_LIVE)
        for (brush in listOf(ink(), pencil(), sable())) {
            assertTrue(BrushKnobs.forBrush(brush).none { it.key.startsWith("paper.") })
        }
    }

'''+s[end:]
s=s.replace('assertEquals(paperKeys, keys.filter { it in paperKeys }.toSet(),','assertEquals(emptySet(), keys.filter { it in paperKeys }.toSet(),')
s=s.replace('BrushKnobs.forBrush(pencil()).first { it.key == key }','BrushKnobs.PAPER.first { it.key == key }').replace('BrushKnobs.forBrush(old).first { it.key == "paper.influence" }','BrushKnobs.PAPER.first { it.key == "paper.influence" }').replace('BrushKnobs.forBrush(moved).first { it.key == "paper.influence" }','BrushKnobs.PAPER.first { it.key == "paper.influence" }').replace('BrushKnobs.forBrush(untouched).first { it.key == "paper.influence" }','BrushKnobs.PAPER.first { it.key == "paper.influence" }')
s=s.replace('BrushKnobs.forBrush(pencil()).filter { it.key.startsWith("paper.") }','BrushKnobs.PAPER').replace('knob.hint.trimEnd().endsWith("(takes effect when the paper engine lands)")','!knob.hint.contains("takes effect when")').replace('BrushKnobs.forBrush(pencil()).map { it.key }.filter { it.startsWith("paper.") }.toSet()','BrushKnobs.PAPER.map { it.key }.toSet()')
p.write_text(s,encoding='utf-8')
# Update repeat-control replacement after the height-only sampler rename.
p=root/'tools/shader_check.js'; s=p.read_text(encoding='utf-8').replace("replace('return jb_paperSurface(docPx).z;'", "replace('return jb_paperHeight(docPx);'")
s=s.replace('    out.paperCorrelation=dabCorrelation(false);', '''    // JB-9.03b: exact-zero slopes must survive the GPU's filtered decode.
    const flatBytes = new Uint8Array(4 * 4 * 4);
    for (let i=0;i<flatBytes.length;i+=4) flatBytes.set([127,127,128,64],i);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D,surface);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,4,4,0,gl.RGBA,gl.UNSIGNED_BYTE,flatBytes);
    gl.generateMipmap(gl.TEXTURE_2D);
    const zeroP = prog(stripV, `#version 300 es\\n${S['jb_paper.glsl']}\\nout vec4 color;
      void main(){vec2 slope=jb_paperSurface(gl_FragCoord.xy).xy; color=vec4(slope,0,1);}`);
    gl.useProgram(zeroP); paperUniforms(zeroP,4); gl.uniform1i(u(zeroP,'u_paperSurface'),1);
    gl.activeTexture(gl.TEXTURE3);
    const zeroTex=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,zeroTex);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,1,1,0,gl.RGBA,gl.FLOAT,null);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,zeroTex,0);
    gl.viewport(0,0,1,1); gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const slopes=new Float32Array(4);gl.readPixels(0,0,1,1,gl.RGBA,gl.FLOAT,slopes);
    out.flatSlopeExactZero=slopes[0]===0 && slopes[1]===0;
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,surface);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,gl.RGBA,gl.UNSIGNED_BYTE,image);gl.generateMipmap(gl.TEXTURE_2D);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,strip,0);gl.viewport(0,0,W,H);
    out.paperCorrelation=dabCorrelation(false);''')
s=s.replace('!res.paperNoRepeat ||','!res.paperNoRepeat || !res.flatSlopeExactZero ||')
p.write_text(s,encoding='utf-8')
# Regenerate with the unchanged B channel; never use an untracked height source.
import importlib.util, numpy as np
from PIL import Image
spec=importlib.util.spec_from_file_location('paperpack',root/'tools/paper/pack.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
p=root/'assets/paper/surface_pulp_artisan.png'; old=np.asarray(Image.open(p)).copy()
packed,sr=mod.pack(old[:,:,2],0.099)
assert np.array_equal(old[:,:,2],packed[:,:,2])
Image.fromarray(packed,'RGBA').save(p,optimize=True)
print('Regenerated surface: B unchanged; slopeRange',sr)
