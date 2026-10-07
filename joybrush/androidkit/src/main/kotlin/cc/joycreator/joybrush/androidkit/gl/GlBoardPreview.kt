package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.doc.RectPx

/** One bounded crop repeated by one screen quad, regardless of tile size or zoom. Display only. */
internal class GlBoardPreview {
    private var program: GlProgram? = null
    private var texture = 0
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    val target: Int get() = framebuffer

    fun forget() { program=null; texture=0; framebuffer=0; width=0; height=0 }
    fun release() {
        if(texture != 0) GLES30.glDeleteTextures(1,intArrayOf(texture),0)
        if(framebuffer != 0) GLES30.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        program?.release(); forget()
    }
    fun prepare(w: Int,h: Int) {
        val max=IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,max,0)
        require(w in 1..max[0] && h in 1..max[0]) { "This board is too large to preview on this phone" }
        if(texture != 0 && width == w && height == h) return
        release()
        try {
            val ids=IntArray(1)
            GLES30.glGenTextures(1,ids,0); texture=ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA8,w,h,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null)
            for(param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,param,GLES30.GL_NEAREST)
            for(param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_TEXTURE_WRAP_T)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,param,GLES30.GL_REPEAT)
            GLES30.glGenFramebuffers(1,ids,0); framebuffer=ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffer)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Board preview framebuffer incomplete" }
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Board preview allocation failed" }
            width=w; height=h
        } catch(e: Throwable) {
            // A retry at the same size must allocate again, never reuse a partial target.
            release()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
            throw e
        }
    }
    fun show(w: Int,h: Int,m: FloatArray,rect: RectPx,vao: Int,repeat: Boolean,tint: FloatArray) {
        val p=program ?: GlProgram(VERTEX,FRAGMENT,"board preview").also { program=it }
        val a=m[0].toDouble(); val b=m[1].toDouble(); val c=m[3].toDouble(); val d=m[4].toDouble()
        val det=a*d-b*c; require(det.isFinite() && det != 0.0)
        val inverse=floatArrayOf((d/det).toFloat(),(-b/det).toFloat(),0f,(-c/det).toFloat(),(a/det).toFloat(),0f,
            ((c*m[7]-d*m[6])/det).toFloat(),((b*m[6]-a*m[7])/det).toFloat(),1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glViewport(0,0,w,h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if(repeat) GLES30.glDisable(GLES30.GL_BLEND) else {
            GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE,GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        p.use(); GLES30.glUniformMatrix3fv(p.loc("u_inverse"),1,false,inverse,0)
        GLES30.glUniform4f(p.loc("u_rect"),rect.x.toFloat(),rect.y.toFloat(),rect.w.toFloat(),rect.h.toFloat())
        GLES30.glUniform4fv(p.loc("u_tint"),1,tint,0)
        GLES30.glUniform1i(p.loc("u_repeat"),if(repeat)1 else 0)
        GLES30.glUniform1i(p.loc("u_crop"),0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture); GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4); GLES30.glBindVertexArray(0)
    }
    companion object {
        private val VERTEX="""#version 300 es
            precision highp float;
            out vec2 v_clip;
            void main() {
                vec2 p=vec2(float(gl_VertexID & 1),float((gl_VertexID >> 1) & 1))*2.0-1.0;
                v_clip=p; gl_Position=vec4(p,0.0,1.0);
            }
        """.trimIndent()
        private val FRAGMENT="""#version 300 es
            precision highp float;
            in vec2 v_clip;
            uniform mat3 u_inverse;
            uniform vec4 u_rect;
            uniform vec4 u_tint;
            uniform bool u_repeat;
            uniform sampler2D u_crop;
            out vec4 colour;
            void main() {
                vec2 world=(u_inverse*vec3(v_clip,1.0)).xy;
                vec2 uv=(world-u_rect.xy)/u_rect.zw;
                if(!u_repeat && (any(lessThan(uv,vec2(0.0))) || any(greaterThanEqual(uv,vec2(1.0))))) discard;
                vec4 src=texture(u_crop,fract(uv));
                colour=u_repeat ? src : vec4(u_tint.rgb*src.a*u_tint.a,src.a*u_tint.a);
            }
        """.trimIndent()
    }
}
