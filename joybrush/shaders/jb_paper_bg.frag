#version 300 es
precision highp float;
precision highp int;
uniform sampler2D u_look;
uniform sampler2D u_surface;
uniform bool u_hasLook, u_hasSurface, u_tinted, u_light;
uniform vec3 u_base, u_mean, u_lamp;
uniform float u_show, u_relief, u_slopeRange, u_detail;
uniform mat2 u_docStep;
uniform float u_pitch[3], u_hex[3], u_size[3];
uniform bool u_rotate[3];
uniform ivec2 u_hexBase[3];
uniform vec2 u_localOrigin[3], u_baseCentreMod[3];
out vec4 color;

float hashVertex(ivec2 v, int k) {
    uint x = uint(v.x)*73856093u ^ uint(v.y)*19349663u ^ uint(k)*83492791u;
    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u;
    x *= 0x846ca68bu; x ^= x >> 16u;
    return float(x >> 8u) / 16777216.0;
}
// All floating coordinates are relative to an integer lattice vertex. Hashes stay global.
vec4 readHex(sampler2D tex, int octave, int seed, bool slopes) {
    const float SQRT3 = 1.7320508075688772;
    vec2 p = u_localOrigin[octave] + u_docStep * gl_FragCoord.xy / u_pitch[octave];
    vec2 dx = dFdx(p), dy = dFdy(p);
    vec2 q = p / u_hex[octave];
    vec2 ab = vec2(q.x-q.y/SQRT3, 2.0*q.y/SQRT3);
    ivec2 b = ivec2(floor(ab)); vec2 f = fract(ab);
    ivec2 v[3]; vec3 w;
    if (f.x+f.y > 1.0) {
        v[0]=b+ivec2(1,1); v[1]=b+ivec2(1,0); v[2]=b+ivec2(0,1);
        w=vec3(f.x+f.y-1.0,1.0-f.y,1.0-f.x);
    } else {
        v[0]=b; v[1]=b+ivec2(1,0); v[2]=b+ivec2(0,1);
        w=vec3(1.0-(f.x+f.y),f.x,f.y);
    }
    w=w*w*w; w/=w.x+w.y+w.z;
    vec4 result=vec4(0);
    for (int n=0;n<3;++n) {
        ivec2 global=u_hexBase[octave]+v[n];
        vec2 centre=u_hex[octave]*vec2(float(v[n].x)+float(v[n].y)*0.5,float(v[n].y)*SQRT3*0.5);
        vec2 offset=vec2(hashVertex(global,1+seed),hashVertex(global,2+seed))*u_size[octave];
        float theta=u_rotate[octave]?hashVertex(global,3+seed)*6.283185307179586:0.0;
        float c=cos(theta), s=sin(theta); mat2 r=mat2(c,s,-s,c);
        vec2 t=r*(p-centre)+centre+u_baseCentreMod[octave]+offset;
        vec4 value=textureGrad(tex,t/u_size[octave],r*dx/u_size[octave],r*dy/u_size[octave]);
        if (slopes) {
            vec2 delta=value.rg*255.0-127.0;
            delta=mix(delta,vec2(0),lessThanEqual(abs(delta),vec2(1.0/65536.0)));
            value.rg=mat2(c,-s,s,c)*delta/127.0*u_slopeRange/u_pitch[octave];
        }
        result+=w[n]*value;
    }
    return result;
}
void main() {
    vec3 look=u_base;
    if (u_hasLook) {
        look=readHex(u_look,0,0,false).rgb;
        if (u_tinted) look=u_base*look/max(u_mean,vec3(1.0/255.0));
    }
    vec2 slope=vec2(0);
    if (u_hasSurface && u_light) {
        slope=readHex(u_surface,1,0,true).rg;
        if (u_detail>0.0) slope+=u_detail*readHex(u_surface,2,10,true).rg;
    }
    vec3 normal=normalize(vec3(-slope*6.0*u_relief,1));
    float shade=u_light?mix(1.0,clamp(dot(normal,u_lamp)/u_lamp.z,0.6,1.4),u_show):1.0;
    color=vec4(mix(u_base,look,u_show)*shade,1);
}
