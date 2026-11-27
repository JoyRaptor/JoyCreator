from pathlib import Path
p=Path('joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt')
s=p.read_text(encoding='utf-8')
s=s.replace('import android.opengl.GLES30','import android.opengl.GLES30\nimport cc.joycreator.joybrush.androidkit.io.PaperResources\nimport cc.joycreator.joybrush.core.paper.ResolvedPaper\nimport cc.joycreator.joybrush.core.paper.SurfaceEntry\nimport cc.joycreator.joybrush.core.doc.RectPx')
s=s.replace('private val grains = GrainTextures()', '''private val grains = GrainTextures()
    private val paperBackground = PaperBackground(shaders)
    private var resolvedPaper: ResolvedPaper? = null
    private var loadedPaper: PaperResources.Loaded? = null
    private var lookTexture: Int? = null
    private var surfaceTexture: Int? = null
    private var strokeSurface: SurfaceEntry? = null
    private var strokePaperScale = 1f
    val paperWarnings: List<String> get() = loadedPaper?.warnings ?: emptyList()

    /** GL thread: switching paper invalidates only the background, never painted tiles. */
    fun setPaper(p: ResolvedPaper) {
        if (resolvedPaper == p && loadedPaper != null) return
        resolvedPaper = p
        loadedPaper = PaperResources.load(p)
        val effective = loadedPaper!!.paper
        lookTexture = effective.look?.file?.let { grains.textureFor(it, "paper") }
        surfaceTexture = effective.surface?.file?.let { grains.textureFor(it, "paper") }
        paperBackground.invalidate()
    }

    fun renderPaper(rect: RectPx): ByteArray = (loadedPaper ?: PaperResources.load(
        ResolvedPaper(null,null,0xFFFFFFFF.toInt(),1f,1f,1f,false))).render(rect)

    private fun drawPaper(w: Int, h: Int, m: FloatArray, fallback: Int, target: Int) {
        val p = loadedPaper?.paper ?: ResolvedPaper(null,null,fallback,1f,1f,1f,false)
        val lookSize = p.look?.file?.let { grains.sizeFor(it,"paper") } ?: 1
        paperBackground.draw(w,h,m,p,lookTexture,surfaceTexture,lookSize,grains.placeholder,tileVao,target)
    }''')
s=s.replace('compositor.forget()','compositor.forget()\n        paperBackground.forget()\n        loadedPaper = null; lookTexture = null; surfaceTexture = null')
s=s.replace('compositor.release()','compositor.release()\n        paperBackground.release()')
start=s.index('        val paperTex = if (grain.paper.enabled)')
end=s.index('\n    }',start)
s=s[:start]+'''        val p = loadedPaper?.paper
        strokeSurface = p?.surface
        strokePaperScale = p?.scale ?: 1f
        val documentGrain = if (p == null) grain.paper else if (p.surface != null && grain.paper.enabled)
            grain.paper.copy(asset=p.surface.file,pitchPx=p.surface.texelPx*p.scale,depth=grain.paper.depth*p.bite)
            else GrainMath.GrainUniforms.OFF
        val paperTex = if (documentGrain.enabled) grains.textureFor(documentGrain.asset) else null
        this.grain = GrainMath.StrokeGrain(
            tip = if (tipTex != null) grain.tip else GrainMath.GrainUniforms.OFF,
            paper = if (paperTex != null) documentGrain else GrainMath.GrainUniforms.OFF,
        )
        tipGrainTex = tipTex ?: grains.placeholder
        paperGrainTex = paperTex ?: grains.placeholder
        val tooth = if (tuft == null) null else if (p == null) grains.textureFor(tuft.paperAsset) else surfaceTexture
        tuftPaperTex = tooth ?: grains.placeholder
        this.tuft = tuft?.let {
            if(tooth == null) it.copy(paperPitchPx=0f) else if(p?.surface != null)
                it.copy(paperAsset=p.surface.file,paperPitchPx=p.surface.texelPx*p.scale,tooth=it.tooth*p.bite)
            else it
        }''' +s[end:]
s=s.replace('GrainMath.SURFACE_TEXEL_PX)','(strokeSurface?.texelPx ?: GrainMath.SURFACE_TEXEL_PX)*strokePaperScale)')
s=s.replace('GrainMath.SURFACE_SIZE)','strokeSurface?.size?.toFloat() ?: GrainMath.SURFACE_SIZE)')
s=s.replace('GrainMath.SURFACE_HEX_TEXELS)','strokeSurface?.hexTexels ?: GrainMath.SURFACE_HEX_TEXELS)')
s=s.replace('GrainMath.SURFACE_SLOPE_RANGE)','strokeSurface?.slopeRange ?: GrainMath.SURFACE_SLOPE_RANGE)')
s=s.replace('if (GrainMath.SURFACE_ROTATABLE) 1 else 0','if (strokeSurface?.rotatable ?: GrainMath.SURFACE_ROTATABLE) 1 else 0')
clear='''        GLES30.glClearColor(
            ((paperArgb shr 16) and 0xFF) / 255f, ((paperArgb shr 8) and 0xFF) / 255f,
            (paperArgb and 0xFF) / 255f, 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)'''
assert s.count(clear)==2
s=s.replace(clear,'        drawPaper(viewportW,viewportH,docToClip,paperArgb,0)',1)
s=s.replace(clear,'        drawPaper(w,h,docToClip,paperArgb,compositor.targetFbo)',1)
p.write_text(s,encoding='utf-8')
