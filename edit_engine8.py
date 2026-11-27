from pathlib import Path
p=Path('joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt')
s=p.read_text(encoding='utf-8')
s=s.replace('import cc.joycreator.joybrush.core.paint.Dab','import cc.joycreator.joybrush.core.paint.Dab\nimport cc.joycreator.joybrush.core.paint.DabTravel\nimport cc.joycreator.joybrush.core.brush.PaperResponse',1)
s=s.replace('private var instanceData: FloatBuffer = newFloats(6 * 256)','''private val strokeTravel = DabTravel()
    private var strokePaperResponse = PaperResponse()
    private var strokePaperInfluence = 0f
    private var instanceData: FloatBuffer = newFloats(8 * 256)''')
# Widen only the stamp VAO. Smudge retains its existing 10-float record/location3 colour.
start=s.index('        GLES30.glBindVertexArray(dabVao)')
end=s.index('        // The smudge dab:',start)
region=s[start:end].replace('false, 24,','false, 32,')
region+='''        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, 32, 24)
        GLES30.glVertexAttribDivisor(4, 1)

'''
s=s[:start]+region+s[end:]
s=s.replace('''        cancelStroke()
        strokeLayer = storeOf(layerId)''','''        cancelStroke()
        strokeTravel.reset()
        strokeLayer = storeOf(layerId)''')
s=s.replace('''        val documentGrain = documentPaperGrain(grain.paper,p)''','''        strokePaperResponse = if (tuft != null) tuft.paperResponse else grain.paperResponse
        // Old direct engine callers have grain settings but no document/response section.
        if (p == null && strokePaperResponse.isDefault && grain.paper.enabled)
            strokePaperResponse = PaperResponse(influence=1f)
        strokePaperInfluence = if (smudge != null) 0f else strokePaperResponse.influence*(p?.bite ?: 1f)
        val documentGrain = documentPaperGrain(grain.paper,p,strokePaperResponse)''')
s=s.replace('''            paper = if (paperTex != null) documentGrain else GrainMath.GrainUniforms.OFF,
        )''','''            paper = if (paperTex != null) documentGrain else GrainMath.GrainUniforms.OFF,
            paperResponse = strokePaperResponse,
        )''')
s=s.replace('tooth=it.tooth*p.bite','tooth=it.tooth')
# A tuft with positive response binds the page even if its old tooth was disabled.
s=s.replace('paperPitchPx=surface.texelPx*p.scale,tooth=it.tooth','paperPitchPx=if(strokePaperInfluence>0f)surface.texelPx*p.scale else 0f,tooth=it.tooth')
# Travel must be captured before tile bucketing, once per dab, not once per tile copy.
s=s.replace('''        val buckets = Tiles.bucket(dabs, size)''','''        val directed = dabs.map { dab ->
            strokeTravel.update(dab.x,dab.y)
            if (dab.travelKnown) dab else dab.copy(travelX=strokeTravel.x,travelY=strokeTravel.y,travelKnown=true)
        }
        val buckets = Tiles.bucket(directed, size)''',1)
s=s.replace('list.size * 24, instanceData','list.size * 32, instanceData')
s=s.replace('.put(t.kind.toFloat()).put(t.graze).put(0f).put(0f)','.put(t.kind.toFloat()).put(t.graze).put(t.travelX).put(t.travelY)')
s=s.replace('''        setPaperSurfaceUniforms(tuftProg)''','''        setPaperSurfaceUniforms(tuftProg)
        setPaperResponseUniforms(tuftProg)''')
s=s.replace('''    private fun setGrainUniforms(newest: Dab) {''','''    private fun setPaperResponseUniforms(program: GlProgram) {
        GLES30.glUniform1f(program.loc("u_paperInfluence"),strokePaperInfluence)
        GLES30.glUniform1f(program.loc("u_paperDirectional"),strokePaperResponse.directional)
        GLES30.glUniform1f(program.loc("u_paperWet"),strokePaperResponse.wet)
    }

    private fun setGrainUniforms(newest: Dab) {
        setPaperResponseUniforms(dabProg)''')
s=s.replace('val need = list.size * 6','val need = list.size * 8')
s=s.replace('instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap)','instanceData.put(d.x).put(d.y).put(d.radius).put(d.angle).put(d.flow).put(d.cap).put(d.travelX).put(d.travelY)')
p.write_text(s,encoding='utf-8')
