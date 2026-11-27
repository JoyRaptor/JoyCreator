package cc.joycreator.avatar.draw;

/**
 * THE shader source for drawing a character: the room's stage, Studio's preview and Studio's export compile THESE strings, so a
 * preview cannot drift from an export (the rule {@code MeshGlSource} keeps for Studio's mesh stamp). Character for character the
 * web pilot's {@code tools/avatarlab/al-gl.js}, so the pilot's picture and the phone's are made by the same arithmetic.
 *
 * <p>Two programs. {@link #FS} is the everyday one: textured triangles, premultiplied alpha, a tint, and a mask read from a
 * second texture ("how much shows here", made in an off-screen pass from the masking parts' own soft alpha). {@link #FS_TONE}
 * is used only while a colour set is in play (the Note 9 rule: program size is the cost): each pixel's hue is looked up against
 * up to 8 "this colour becomes that colour" stops, arriving as a wave with a blotchy front.</p>
 *
 * <p>Vertex positions are character pixels; {@code uv} = (scale, scale, offset x, offset y) to canvas pixels; {@code ur} is the
 * canvas size; canvas y runs down, as in the pilot.</p>
 */
public final class CharacterGlSource {
    private CharacterGlSource() {}

    public static final String VS =
        "attribute vec2 p;attribute vec2 t;uniform vec4 uv;uniform vec2 ur;varying vec2 vt;"
        + "void main(){vec2 q=p*uv.xy+uv.zw;gl_Position=vec4(q.x/ur.x*2.0-1.0,1.0-q.y/ur.y*2.0,0.0,1.0);vt=t;}";

    public static final String FS =
        "precision mediump float;varying vec2 vt;uniform sampler2D us;uniform sampler2D um;uniform float ua;uniform float uk;uniform vec4 uc;uniform vec2 uq;"
        + "void main(){vec4 c=texture2D(us,vt);c.rgb=mix(c.rgb,uc.rgb*c.a,uc.a);float m=1.0;if(uk>0.5){m=clamp(texture2D(um,gl_FragCoord.xy/uq).a*2.2,0.0,1.0);}gl_FragColor=c*ua*m;}";

    public static final String FS_TONE =
        "precision mediump float;varying vec2 vt;uniform sampler2D us;uniform sampler2D um;uniform float ua;uniform float uk;uniform vec4 uc;uniform vec2 uq;"
        + "uniform vec4 uh[8];uniform vec4 ug[8];uniform vec4 ux;uniform float un;"
        + "vec3 hsv(vec3 c){vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));float d=q.x-min(q.w,q.y);return vec3(abs(q.z+(q.w-q.y)/(6.0*d+1.0e-5)),d/(q.x+1.0e-5),q.x);}"
        + "vec3 rgb(vec3 c){vec3 p=abs(fract(c.xxx+vec3(1.0,2.0/3.0,1.0/3.0))*6.0-3.0);return c.z*mix(vec3(1.0),clamp(p-1.0,0.0,1.0),c.y);}"
        + "void main(){vec4 c=texture2D(us,vt);"
        + "if(un>0.5&&c.a>0.01){vec3 h=hsv(clamp(c.rgb/c.a,0.0,1.0));float pos=dot(gl_FragCoord.xy,ux.xy)+ux.z;"
        + "float nz=sin(gl_FragCoord.x*ux.w)*sin(gl_FragCoord.y*ux.w*1.31+1.7)+0.5*sin((gl_FragCoord.x+gl_FragCoord.y)*ux.w*2.3);"
        + "float dh=0.0;float ls=0.0;float lv=0.0;float ws=0.0;"
        + "for(int i=0;i<8;i++){float d=abs(fract(h.x-uh[i].x+0.5)-0.5);float w=1.0-smoothstep(ug[i].x*0.5,ug[i].x,d);"
        + "float t=clamp((ug[i].y-pos-nz*ug[i].w)/ug[i].z,0.0,1.0);w*=t*t*(3.0-2.0*t);dh+=w*uh[i].y;ls+=w*uh[i].z;lv+=w*uh[i].w;ws+=w;}"
        + "float k=1.0/max(1.0,ws);float g=smoothstep(0.06,0.2,h.y)*smoothstep(0.04,0.14,h.z);"
        + "h.x=fract(h.x+dh*k*g+1.0);h.y=clamp(h.y*exp(ls*k*g),0.0,1.0);h.z=clamp(h.z*exp(lv*k*g),0.0,1.0);c.rgb=rgb(h)*c.a;}"
        + "c.rgb=mix(c.rgb,uc.rgb*c.a,uc.a);float m=1.0;if(uk>0.5){m=clamp(texture2D(um,gl_FragCoord.xy/uq).a*2.2,0.0,1.0);}gl_FragColor=c*ua*m;}";
}
