#version 300 es
precision highp float;
precision highp int;
#include "jb_paper.glsl"
#include "jb_tile_paper.glsl"
uniform sampler2D u_band00;
uniform sampler2D u_band10;
uniform sampler2D u_band20;
uniform sampler2D u_band01;
uniform sampler2D u_band11;
uniform sampler2D u_band21;
uniform sampler2D u_band02;
uniform sampler2D u_band12;
uniform sampler2D u_band22;
uniform sampler2D u_backdrop;
uniform sampler2D u_fineGrain;
uniform vec2 u_tileOrigin;
uniform float u_scale;
uniform int u_bandTileSize;
uniform vec3 u_tintLinear;
uniform vec3 u_tintSrgb;
uniform float u_paperCut;
uniform int u_hasPaper;
uniform float u_edgeWidth;
uniform float u_edgePool;
uniform float u_edgeAlpha;
uniform float u_gravityWidth;
uniform float u_gravityPool;
uniform vec2 u_gravityDirection; // document coordinates, down = (0,+1)
uniform float u_pooling;        // 0 live/unpooled, 1 finished, smoothstep fade between
uniform float u_backdropWorld;
uniform vec2 u_backdropOffset;  // document pixels, frozen from the record seed
uniform float u_textureStrength;
uniform float u_textureContrast;
uniform int u_textureInvert;
uniform float u_grain;
in vec2 v_docPx;
out vec4 o_color;

vec3 wc_bands(vec2 doc) {
    ivec2 p=ivec2(floor((doc-u_tileOrigin)*u_scale));
    ivec2 tile=ivec2(floor(vec2(p)/float(u_bandTileSize)));
    ivec2 q=p-tile*u_bandTileSize;
    if(tile.x < -1 || tile.x > 1 || tile.y < -1 || tile.y > 1) return vec3(0.0);
    if(tile.y == -1) {
        if(tile.x == -1) return texelFetch(u_band00,q,0).rgb;
        if(tile.x == 0) return texelFetch(u_band10,q,0).rgb;
        return texelFetch(u_band20,q,0).rgb;
    }
    if(tile.y == 0) {
        if(tile.x == -1) return texelFetch(u_band01,q,0).rgb;
        if(tile.x == 0) return texelFetch(u_band11,q,0).rgb;
        return texelFetch(u_band21,q,0).rgb;
    }
    if(tile.x == -1) return texelFetch(u_band02,q,0).rgb;
    if(tile.x == 0) return texelFetch(u_band12,q,0).rgb;
    return texelFetch(u_band22,q,0).rgb;
}
float wc_height(vec2 doc) {
    return u_hasPaper == 1 ? jb_tilePaperSurface(doc).z : 0.5;
}
float wc_coverage(vec2 doc) {
    vec3 bands=wc_bands(doc);
    // Uniform branch: derivatives in the canonical hex sampler remain valid at stroke boundaries.
    if(u_hasPaper == 1 && u_paperCut > 0.0) {
        float h=wc_height(doc);
        vec3 cut=vec3(1.0,clamp((h-0.425)*8.0+0.5,0.0,1.0),clamp((h-0.85)*8.0+0.5,0.0,1.0));
        bands*=mix(vec3(1.0),cut,u_paperCut);
    }
    return 1.0-(1.0-bands.r)*(1.0-bands.g)*(1.0-bands.b);
}
vec3 wc_encode(vec3 linear) {
    return mix(1.055*pow(max(linear,vec3(0.0)),vec3(1.0/2.4))-0.055,linear*12.92,lessThanEqual(linear,vec3(0.0031308)));
}
void main() {
    float alpha=wc_coverage(v_docPx);
    // The unpooled result is retained conceptually; the tint/coverage blend below is premultiplied.
    float finishedAlpha=alpha;
    vec3 finishedTint=u_tintSrgb;
    if(u_pooling > 0.0 && (u_edgePool > 0.0 || u_edgeAlpha > 0.0 || u_gravityPool > 0.0)) {
        float inner=min(min(wc_coverage(v_docPx+vec2(u_edgeWidth,0.0)),wc_coverage(v_docPx-vec2(u_edgeWidth,0.0))),
                        min(wc_coverage(v_docPx+vec2(0.0,u_edgeWidth)),wc_coverage(v_docPx-vec2(0.0,u_edgeWidth))));
        float edge=max(0.0,alpha-inner)/max(alpha,1e-8);
        float lower=0.0;
        if(u_gravityPool > 0.0) for(int k=1;k<=4;k++)
            lower+=max(0.0,alpha-wc_coverage(v_docPx+u_gravityDirection*u_gravityWidth*float(k)/4.0))/max(alpha,1e-8)*0.25;
        float concentration=1.0+2.0*clamp(edge*u_edgePool+lower*u_gravityPool,0.0,1.0);
        finishedTint=wc_encode(pow(clamp(u_tintLinear,0.0,1.0),vec3(concentration)));
        finishedAlpha=1.0-pow(max(0.0,1.0-alpha),1.0+4.0*u_edgeAlpha*edge);
    }
    // Tile armed (uniform): the wash and the fine grain repeat with the board, blended exactly as the paper is, so the seam
    // carries no texture break. Unarmed, both reads are the original lines, untouched.
    bool tiled=u_tilePaperRect.z > 0.0 && u_tilePaperRect.w > 0.0;
    float wash;
    if(tiled) wash=jb_tileTextureR(u_backdrop,v_docPx,u_backdropOffset,u_backdropWorld,0.5);
    else wash=texture(u_backdrop,(v_docPx+u_backdropOffset)/u_backdropWorld).r;
    wash=clamp((wash-0.5)*u_textureContrast+0.5,0.0,1.0);
    if(u_textureInvert == 0) wash=1.0-wash;
    float mask=mix(1.0,wash,u_textureStrength);
    // Fixed canvas grain, never the seeded backdrop offset.
    if(tiled) mask*=1.0-u_grain*0.8*jb_tileTextureR(u_fineGrain,v_docPx,vec2(0.0),256.0,0.5);
    else mask*=1.0-u_grain*0.8*texture(u_fineGrain,v_docPx/256.0).r;
    vec4 plain=vec4(u_tintSrgb*alpha,alpha);
    vec4 pooled=vec4(finishedTint*finishedAlpha,finishedAlpha);
    o_color=mix(plain,pooled,clamp(u_pooling,0.0,1.0))*mask;
}
