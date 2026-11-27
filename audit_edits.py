from pathlib import Path
import re
root=Path('joybrush')
core=root/'core/src/commonMain/kotlin/cc/joycreator/joybrush/core'
def edit(p,f):
    p.write_text(f(p.read_text()),encoding='utf-8')
p=core/'paper/SurfaceMaps.kt'
s=p.read_text().replace('import kotlin.math.roundToInt','import kotlin.math.round')
start=s.index('    fun slopes('); end=s.index('\n    /**',start)
block=s[start:end]
block=block.replace('fun slopes(height: FloatArray','private fun slopesDouble(height: DoubleArray').replace('Pair<FloatArray, FloatArray>','Pair<DoubleArray, DoubleArray>').replace('FloatArray(w * h)','DoubleArray(w * h)').replace('3f *','3.0 *').replace('10f *','10.0 *').replace('/ SCHARR_NORM','/ 32.0')
wrapper='''    fun slopes(height: FloatArray, w: Int, h: Int): Pair<FloatArray, FloatArray> {
        val (dx, dy) = slopesDouble(DoubleArray(height.size) { height[it].toDouble() }, w, h)
        return FloatArray(dx.size) { dx[it].toFloat() } to FloatArray(dy.size) { dy[it].toFloat() }
    }

'''
s=s[:start]+wrapper+block+s[end:]
start=s.index('    /** One slope as a byte:'); end=s.index('    /**\n     * The GPU surface layout',start)
s=s[:start]+'''    /** Exact-zero encoding: 127 is flat; -range and +range are 0 and 254. */
    fun encodeSlope(s: Float, slopeRange: Float): Int {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        return encodeDouble(s.toDouble(), slopeRange.toString().toDouble())
    }

    private fun encodeDouble(s: Double, range: Double): Int =
        round(127.0 + 127.0 * (s / range).coerceIn(-1.0, 1.0)).toInt()

    /** Inverse, including the exact zero at byte 127. */
    fun decodeSlope(b: Int, slopeRange: Float): Float {
        require(slopeRange.isFinite() && slopeRange > 0f) { "slopeRange must be a positive finite number, was $slopeRange" }
        return (b - 127) / 127f * slopeRange
    }

'''+s[end:]
s=s.replace('val height = FloatArray(heightBytes.size) { heightBytes[it].toInt().and(0xFF) / 255f }','val height = DoubleArray(heightBytes.size) { heightBytes[it].toInt().and(0xFF) / 255.0 }').replace('val (dx, dy) = slopes(height, w, h)','val (dx, dy) = slopesDouble(height, w, h)').replace('encodeSlope(dx[i], slopeRange)','encodeDouble(dx[i], slopeRange.toString().toDouble())').replace('encodeSlope(dy[i], slopeRange)','encodeDouble(dy[i], slopeRange.toString().toDouble())').replace('(255f * b / 255f * (b / 255f)).roundToInt()','round(255.0 * height[i] * height[i]).toInt()')
p.write_text(s,encoding='utf-8')
p=core/'paper/HexTile.kt'; s=p.read_text()
s=s.replace('val fa = (a - i0).toFloat()','val fa = a - i0').replace('val fb = (b - j0).toFloat()','val fb = b - j0').replace('fa + fb > 1f','fa + fb > 1.0').replace('floatArrayOf(fa + fb - 1f, 1f - fb, 1f - fa)','floatArrayOf((fa + fb - 1.0).toFloat(), (1.0 - fb).toFloat(), (1.0 - fa).toFloat())').replace('floatArrayOf(1f - (fa + fb), fa, fb)','floatArrayOf((1.0 - (fa + fb)).toFloat(), fa.toFloat(), fb.toFloat())')
s=s.replace('out: FloatArray,\n    ) {','out: FloatArray,\n        seed: Int = 0,\n    ) {',1)
s=s.replace('rotatable: Boolean, out: FloatArray) {','rotatable: Boolean, out: FloatArray, seed: Int = 0) {')
s=s.replace('rotatable, s)','rotatable, s, seed)').replace('SurfaceMaps.decodeSlope(byteOf(s[0]), slopeRange)','(s[0] * 255f - 127f) / 127f * slopeRange').replace('SurfaceMaps.decodeSlope(byteOf(s[1]), slopeRange)','(s[1] * 255f - 127f) / 127f * slopeRange').replace('rotation(l.vi[n], l.vj[n])','rotation(l.vi[n], l.vj[n], seed)')
s=s.replace('out: FloatArray,\n    ) {','out: FloatArray,\n        seed: Int,\n    ) {').replace('hash(i, j, 1)','hash(i, j, 1 + seed)').replace('hash(i, j, 2)','hash(i, j, 2 + seed)').replace('rotation(i, j)','rotation(i, j, seed)').replace('private fun rotation(i: Int, j: Int): Double = hash(i, j, 3) * TAU','private fun rotation(i: Int, j: Int, seed: Int): Double = hash(i, j, 3 + seed) * TAU')
s=re.sub(r'\n    /\*\* The 0\.\.1 channel.*?private fun byteOf[^\n]+\n','\n',s,flags=re.S)
p.write_text(s,encoding='utf-8')
p=core/'paper/PaperTexture.kt'; s=p.read_text().replace('(x - cx).toFloat()','x - cx').replace('(y - cy).toFloat()','y - cy').replace('1f - fx','1.0 - fx').replace('1f - fy','1.0 - fy')
s=s.replace('out[c] = g00 * byte(rgba[i00 + c]) + g10 * byte(rgba[i10 + c]) +\n                g01 * byte(rgba[i01 + c]) + g11 * byte(rgba[i11 + c])','out[c] = ((g00 * (rgba[i00 + c].toInt() and 255) + g10 * (rgba[i10 + c].toInt() and 255) +\n                g01 * (rgba[i01 + c].toInt() and 255) + g11 * (rgba[i11 + c].toInt() and 255)) / 255.0).toFloat()')
p.write_text(s,encoding='utf-8')
edit(root/'tools/paper/pack.py',lambda s:s.replace('round(255 * clamp(0.5 + 0.5 * s / slopeRange, 0, 1))','round(127 + 127 * clamp(s / slopeRange, -1, 1))').replace('np.round(255 * np.clip(0.5 + 0.5 * s / slope_range, 0, 1))','np.round(127 + 127 * np.clip(s / slope_range, -1, 1))'))
p=root/'shaders/jb_paper.glsl'; s=p.read_text().replace('precision highp int;','precision highp float;\nprecision highp int;').replace('vec4 jb_paperSurface(vec2 docPx) {','vec4 jb_paperRead(vec2 docPx, int seed, bool slopes) {').replace('jb_hash(i, j, 1)','jb_hash(i, j, 1 + seed)').replace('jb_hash(i, j, 2)','jb_hash(i, j, 2 + seed)').replace('jb_hash(i, j, 3)','jb_hash(i, j, 3 + seed)').replace('vec2 slope = (sampleValue.rg * 2.0 - 1.0) * u_paperSlopeRange;\n        slope = mat2(c, -s, s, c) * slope;','vec2 slope = vec2(0.0);\n        if (slopes) {\n            slope = (sampleValue.rg * 255.0 - 127.0) / 127.0 * u_paperSlopeRange;\n            slope = mat2(c, -s, s, c) * slope;\n        }')
s+='\nvec4 jb_paperSurface(vec2 docPx, int seed) { return jb_paperRead(docPx, seed, true); }\nvec4 jb_paperSurface(vec2 docPx) { return jb_paperSurface(docPx, 0); }\nfloat jb_paperHeight(vec2 docPx) { return jb_paperRead(docPx, 0, false).z; }\n'
p.write_text(s,encoding='utf-8')
edit(root/'shaders/jb_grain_sample.glsl',lambda s:s.replace('return jb_paperSurface(docPx).z;','return jb_paperHeight(docPx);'))
p=core/'grain/GrainMath.kt'; s=p.read_text().replace('const val SURFACE_TEXEL_PX = 2f','const val DEFAULT_SURFACE_TEXEL_PX = 2f\n    const val SURFACE_TEXEL_PX = DEFAULT_SURFACE_TEXEL_PX')
s=s.replace('uniformsFor(spec.copy(image = DEFAULT_SURFACE, scale = GRAIN_UNIT_PX / SURFACE_TEXEL_PX), depthAtFirstDab)','if (!spec.enabled) GrainUniforms.OFF else GrainUniforms(\n            DEFAULT_SURFACE_TEXEL_PX, unit(depthAtFirstDab, unit(spec.depth.base, 1f)),\n            if (spec.edge.isFinite()) spec.edge.coerceIn(0f, 1f) else 0.3f,\n            if (spec.tiltGradient.isFinite()) spec.tiltGradient else 0f,\n            if (spec.radial.isFinite()) spec.radial else 0f, DEFAULT_SURFACE,\n        )')
p.write_text(s,encoding='utf-8')
edit(core/'brush/TuftStroke.kt',lambda s:s.replace('paperPitchPx = GrainMath.SURFACE_TEXEL_PX,','paperPitchPx = if (u(t.tooth) > 0f || u(t.strays) > 0f) GrainMath.DEFAULT_SURFACE_TEXEL_PX else 0f,').replace('        const val PAPER_TOOTH_SCALE = GrainMath.GRAIN_UNIT_PX / GrainMath.SURFACE_TEXEL_PX\n',''))
edit(core/'brush/BrushPreset.kt',lambda s:s.replace('@Serializable data class GrainSpec(','/** Paper grain image/scale are ignored since JB-9.03: the document paper decides (R10 P4).\n * Tip textures still use their own image and scale. */\n@Serializable data class GrainSpec('))
p=core/'chrome/BrushKnobs.kt'; s=p.read_text().replace('    fun forBrush(p: BrushPreset)', '    const val PAPER_ENGINE_LIVE = false // JB-9.08 enables the three response controls.\n    private val livePaper get() = if (PAPER_ENGINE_LIVE) PAPER else emptyList()\n\n    fun forBrush(p: BrushPreset)').replace('TUFT + PAPER +','TUFT + livePaper +').replace(' + PAPER + SMOOTHING',' + livePaper + SMOOTHING').replace('hint + NOT_YET','hint').replace('private val PAPER =','internal val PAPER =')
s=re.sub(r'    /\*\*\n     \* Said on all three sliders.*?private const val NOT_YET[^\n]+\n', '',s,flags=re.S)
p.write_text(s,encoding='utf-8')
edit(root/'brushes/pencil/brush.json',lambda s:s.replace('    "image": "cloud_fine_256.png",\n','').replace('    "scale": 1.5,\n',''))
