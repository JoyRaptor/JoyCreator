// FINISH: the last pass. Lens moves, glitches, bloom, reflection floor, film, brand colour
// map, framing, background and the hand-off to whatever sits underneath (video, screen).
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uScene;   // HDR, premultiplied
uniform sampler2D uBloom;   // half resolution, light only

#define EXPOSURE  uP[0].x
#define BLOOM     uP[0].y
#define CHROMA    uP[0].z
#define VIGNETTE  uP[0].w
#define GRAIN     uP[1].x
#define SCANLINES uP[1].y
#define SATURATE  uP[1].z
#define CONTRAST  uP[1].w
#define KALEIDO   uP[2].x   // segments, 0 = off
#define KANGLE    uP[2].y   // turns
#define MIRROR    uP[2].z   // 0 off, 1 left-right, 2 top-bottom, 3 both
#define ZOOMBLUR  uP[2].w
#define SHAKE     uP[3].x
#define GLITCH    uP[3].y
#define GRADMAP   uP[3].z   // 0..1: recolour by brightness through the palette (brand look)
#define FLASH     uP[3].w
#define VHS       uP[4].x
#define PIXELATE  uP[4].y   // cell size in scene units, 0 = off
#define POSTER    uP[4].z   // colour levels, 0 = off
#define INVERT    uP[4].w
#define REFLECT   uP[5].x   // wet floor strength
#define HORIZON   uP[5].y   // floor line, scene y
#define RIPPLE    uP[5].z
#define LENS      uP[5].w   // barrel bend (CRT), 0 = flat
#define FRAMEMODE uP[6].x   // 0 bleed, 1 rounded box, 2 circle, 3 ellipse
#define INSET     uP[6].y
#define FEATHER   uP[6].z
#define ROUNDING  uP[6].w
#define BG        uP[7]     // linear rgb + alpha (0 = see-through)
#define ALPHAMODE uP[8].x   // 0 light adds over what is below, 1 screen-like (overlay windows)
#define TINT      uP[8].yzw

vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec2 toUv(vec2 p) { return p / EXT * 0.5 + 0.5; }

vec4 sceneAt(vec2 p) {
    vec2 uv = toUv(p);
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return texture(uScene, uv) * inside.x * inside.y;
}

void main() {
    vec2 p0 = vPos;
    vec2 p = p0;

    if (LENS > 0.0) p *= 1.0 + LENS * dot(p / EXT, p / EXT) * 0.25;

    if (SHAKE > 0.0) {
        float t = TIME * 23.0;
        p += SHAKE * 0.04 * vec2(snoise(vec2(t, 1.3)), snoise(vec2(4.7, t)));
    }

    // Glitch: rows tear sideways in bursts, mostly on the snare.
    float tear = 0.0;
    if (GLITCH > 0.0) {
        float row = floor(p.y * 18.0 + hash11(floor(TIME * 9.0)) * 7.0);
        float burst = step(1.0 - GLITCH * (0.25 + SNARE * 0.75), hash12(vec2(row, floor(TIME * 17.0))));
        tear = burst * (hash12(vec2(row * 3.1, floor(TIME * 31.0))) - 0.5) * 0.35 * GLITCH;
        p.x += tear;
    }

    if (VHS > 0.0) {
        float band = smoothstep(0.0, 0.02, abs(fract(p.y * 0.35 - TIME * 0.17) - 0.5) - 0.47);
        p.x += (1.0 - band) * 0.03 * VHS * snoise(vec2(p.y * 40.0, TIME * 30.0));
        p.x += 0.004 * VHS * snoise(vec2(p.y * 3.0, TIME * 2.0));
    }

    if (PIXELATE > 0.0) p = (floor(p / PIXELATE) + 0.5) * PIXELATE;

    if (MIRROR > 0.5) {
        if (MIRROR < 1.5 || MIRROR > 2.5) p.x = abs(p.x);
        if (MIRROR > 1.5) p.y = abs(p.y);
    }

    if (KALEIDO >= 2.0) {
        float seg = TAU / floor(KALEIDO);
        float ang = atan(p.y, p.x) + KANGLE * TAU;
        float r = length(p);
        ang = mod(ang, seg);
        ang = abs(ang - seg * 0.5);
        p = r * vec2(cos(ang), sin(ang));
    }

    // Wet floor: below the horizon we see the scene upside down, rippled and darker.
    float refl = 0.0;
    if (REFLECT > 0.0 && p.y < HORIZON) {
        float depth = HORIZON - p.y;
        p.y = HORIZON + depth;
        p.x += RIPPLE * 0.02 * snoise(vec2(p.x * 3.0, depth * 40.0 - TIME * 2.0)) * (0.3 + depth);
        p.y += RIPPLE * 0.03 * snoise(vec2(depth * 25.0 + TIME, p.x * 2.0));
        refl = REFLECT * exp(-depth * 2.2);
    }

    // Lens: zoom blur and chromatic split pull the three channels apart toward the edge.
    vec4 c;
    float ca = CHROMA * (1.0 + KICK * 0.5);
    if (ZOOMBLUR > 0.0) {
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 8; i++) {
            float s = 1.0 - ZOOMBLUR * 0.06 * float(i) / 7.0;
            acc += sceneAt(p * s);
        }
        c = acc / 8.0;
    } else {
        c = sceneAt(p);
    }
    if (ca > 0.0) {
        vec2 dir = p * ca * 0.06;
        c.r = sceneAt(p + dir).r;
        c.b = sceneAt(p - dir).b;
    }
    if (VHS > 0.0) {
        c.r = mix(c.r, sceneAt(p + vec2(0.012 * VHS, 0.0)).r, 0.7);
        c.b = mix(c.b, sceneAt(p - vec2(0.008 * VHS, 0.0)).b, 0.7);
    }

    vec3 bloom = texture(uBloom, toUv(p)).rgb;
    vec3 col = c.rgb + bloom * BLOOM;
    float a = c.a;

    if (refl > 0.0) {
        // Rough water: lean on the blurred bloom so the reflection is soft, not a copy.
        col = mix(col, bloom * 1.2, 0.45) * refl;
        a *= refl;
    }

    col += FLASH * vec3(1.0) * (0.6 + 0.4 * BRIGHT);
    col *= EXPOSURE;
    col = aces(col);

    if (TINT != vec3(0.0)) col *= 1.0 + TINT;
    float l = luma(col);
    col = mix(vec3(l), col, SATURATE);
    col = clamp((col - 0.5) * CONTRAST + 0.5, 0.0, 1.0);
    if (GRADMAP > 0.0) col = mix(col, pal(clamp(l * 1.2, 0.0, 1.0)) * smoothstep(0.0, 0.3, l), GRADMAP);
    if (POSTER >= 2.0) col = floor(col * POSTER + 0.5) / POSTER;
    if (INVERT > 0.0) col = mix(col, max(a, max3(col)) - col, INVERT);

    if (VHS > 0.0) {
        col += VHS * 0.08 * (hash12(floor(vPos * vec2(160.0, 90.0)) + TIME) - 0.5);
        col *= 1.0 - VHS * 0.15 * step(0.985, fract(vPos.y * 1.5 - TIME * 0.6));
    }
    if (SCANLINES > 0.0) {
        float line = 0.5 + 0.5 * sin(gl_FragCoord.y * PI * 0.5);
        col *= 1.0 - SCANLINES * 0.45 * line;
    }
    if (GRAIN > 0.0) {
        col += GRAIN * 0.08 * (hash12(gl_FragCoord.xy + fract(TIME * 13.7) * 431.0) - 0.5);
    }
    if (VIGNETTE > 0.0) {
        vec2 v = p0 / EXT;
        col *= 1.0 - VIGNETTE * smoothstep(0.35, 1.45, dot(v, v));
    }
    col = max(col, 0.0);

    vec4 outc = vec4(col, a);
    // Background sits under everything (it never enters the echo, so it never smears).
    outc.rgb += BG.rgb * BG.a * (1.0 - outc.a);
    outc.a += BG.a * (1.0 - outc.a);

    if (ALPHAMODE > 0.5) outc.a = max(outc.a, min(1.0, max3(outc.rgb)));

    // Framing: "contained" art fades out inside a shape so it never meets a hard edge.
    if (FRAMEMODE > 0.5) {
        float d;
        float m = min(EXT.x, EXT.y);
        if (FRAMEMODE < 1.5) d = sdRoundBox(p0, EXT - INSET, ROUNDING * m);
        else if (FRAMEMODE < 2.5) d = length(p0) - (m - INSET);
        else d = (length(p0 / EXT) - 1.0) * m + INSET;
        float mask = 1.0 - smoothstep(-max(FEATHER, 1e-3), 0.0, d);
        outc *= mask;
    }

    outc.rgb = pow(outc.rgb, vec3(1.0 / 2.2));
    o = outc;
}
