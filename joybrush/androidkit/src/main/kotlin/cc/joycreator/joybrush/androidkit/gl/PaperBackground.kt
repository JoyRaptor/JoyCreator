package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.paper.PaperRaster
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import kotlin.math.hypot

/** One viewport texture, redrawn only after its paper, dimensions or affine view changes. */
internal class PaperBackground(private val shaders: ShaderLibrary) {
    private var program: GlProgram? = null
    private var texture = 0
    private var fbo = 0
    private var width = 0
    private var height = 0
    private var view: FloatArray? = null
    private var paper: ResolvedPaper? = null

    fun invalidate() { view = null }
    fun forget() { program = null; texture = 0; fbo = 0; width = 0; height = 0; view = null; paper = null }
    fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        program?.release()
        forget()
    }

    fun draw(w: Int, h: Int, m: FloatArray, p: ResolvedPaper, look: Int?, surface: Int?, lookSize: Int, placeholder: Int, vao: Int, target: Int) {
        if (w <= 0 || h <= 0) return
        val resized = texture == 0 || width != w || height != h
        if (resized) {
            release()
            width = w; height = h
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0); texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "paper cache framebuffer incomplete" }
        }
        if (resized || paper != p || view?.contentEquals(m) != true) {
            val prog = program ?: GlProgram(shaders.source("jb_paper_bg.vert"), shaders.source("jb_paper_bg.frag"), "paper background").also { program = it }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, w, h); GLES30.glDisable(GLES30.GL_BLEND)
            prog.use()
            // Invert in Double BEFORE selecting the global integer lattice vertex.
            val a=m[0].toDouble(); val b=m[1].toDouble(); val c=m[3].toDouble(); val d=m[4].toDouble()
            val det=a*d-b*c
            require(det != 0.0 && det.isFinite()) { "paper needs an invertible view" }
            val xx=d/det*2/w; val xy=-b/det*2/w
            val yx=-c/det*2/h; val yy=a/det*2/h
            val cornerX=(d*(-1.0-m[6])-c*(-1.0-m[7]))/det
            val cornerY=(-b*(-1.0-m[6])+a*(-1.0-m[7]))/det
            GLES30.glUniformMatrix2fv(prog.loc("u_docStep"),1,false,floatArrayOf(xx.toFloat(),xy.toFloat(),yx.toFloat(),yy.toFloat()),0)
            val zoom=hypot(a*w/2,b*h/2)
            val fade=((zoom-4)/12).coerceIn(0.0,1.0)
            GLES30.glUniform1f(prog.loc("u_detail"),(0.35*fade*fade*(3-2*fade)).toFloat())
            val lx=-0.45*xx/hypot(xx,xy)+0.55*yx/hypot(yx,yy)
            val ly=-0.45*xy/hypot(xx,xy)+0.55*yy/hypot(yx,yy)
            val len=kotlin.math.sqrt(lx*lx+ly*ly+0.70*0.70)
            GLES30.glUniform3f(prog.loc("u_lamp"),(lx/len).toFloat(),(ly/len).toFloat(),(0.70/len).toFloat())
            fun flag(name: String, v: Boolean) = GLES30.glUniform1i(prog.loc(name),if(v)1 else 0)
            flag("u_hasLook",look!=null); flag("u_hasSurface",surface!=null)
            flag("u_tinted",p.tintSet); flag("u_light",p.light); flag("u_transparent",p.screenTransparent)
            fun rgb(name: String, argb: Int) = GLES30.glUniform3f(prog.loc(name),((argb ushr 16) and 255)/255f,((argb ushr 8) and 255)/255f,(argb and 255)/255f)
            rgb("u_base",p.baseArgb)
            rgb("u_mean",p.look?.mean?.removePrefix("#")?.toIntOrNull(16) ?: 0xFFFFFF)
            GLES30.glUniform1f(prog.loc("u_show"),p.show)
            GLES30.glUniform1f(prog.loc("u_relief"),p.surface?.relief ?: 0f)
            GLES30.glUniform1f(prog.loc("u_slopeRange"),p.surface?.slopeRange ?: 0.1f)
            for (k in 0..2) {
                val texel=if(k==0) p.look?.texelPx ?: 2f else p.surface?.texelPx ?: 2f
                val hex=if(k==0) p.look?.hexTexels ?: 180f else p.surface?.hexTexels ?: 180f
                val size=if(k==0) lookSize else p.surface?.size ?: 512
                val pitch=texel*p.scale/(if(k==2)8 else 1)
                val frame=PaperRaster.localFrame(cornerX,cornerY,pitch.toDouble(),1.0,hex.toDouble(),size)
                GLES30.glUniform1f(prog.loc("u_pitch[$k]"),pitch)
                GLES30.glUniform1f(prog.loc("u_hex[$k]"),hex)
                GLES30.glUniform1f(prog.loc("u_size[$k]"),size.toFloat())
                flag("u_rotate[$k]",if(k==0)p.look?.rotatable ?: false else p.surface?.rotatable ?: false)
                GLES30.glUniform2i(prog.loc("u_hexBase[$k]"),frame.hexBaseX,frame.hexBaseY)
                GLES30.glUniform2f(prog.loc("u_localOrigin[$k]"),frame.localOriginX.toFloat(),frame.localOriginY.toFloat())
                GLES30.glUniform2f(prog.loc("u_baseCentreMod[$k]"),frame.baseCentreModX.toFloat(),frame.baseCentreModY.toFloat())
            }
            GLES30.glUniform1i(prog.loc("u_look"),0); GLES30.glUniform1i(prog.loc("u_surface"),1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,look ?: placeholder)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,surface ?: placeholder)
            GLES30.glBindVertexArray(vao); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
            view=m.copyOf(); paper=p
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,target)
        GLES30.glBlitFramebuffer(0,0,w,h,0,0,w,h,GLES30.GL_COLOR_BUFFER_BIT,GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,target)
        GLES30.glViewport(0,0,w,h)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }
}
