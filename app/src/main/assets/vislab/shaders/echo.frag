// ECHO: the feedback buffer. Last frame, warped and faded, becomes the canvas this frame's
// layers draw onto. This one pass is where tunnels, trails, smoke and melting come from.
//
// Every rate is per SECOND (or per 1/60 s for the multiplicative ones, raised to dt*60),
// so 30 fps export and 60 fps preview build the same trails.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uPrev;

#define DECAY   uP[0].x   // brightness kept per 1/60 s
#define ZOOM    uP[0].y   // scale per 1/60 s (>1 flies outward)
#define ROT     uP[0].z   // turns per second
#define SWIRL   uP[0].w   // turns per second at the centre, fading outward
#define DRIFT   uP[1].xy  // scene units per second
#define HUE     uP[1].z   // hue turns per second
#define BLUR    uP[1].w   // soften, in pixels
#define RIPPLE  uP[2].x
#define RFREQ   uP[2].y
#define WARP    uP[2].z
#define WSCALE  uP[2].w
#define MODE    uP[3].x   // 0 classic, 1 datamosh, 2 melt, 3 ink, 4 slit-scan
#define CEN     uP[3].yz
#define AMOUNT  uP[3].w   // strength of the mode-specific move

vec4 prevAt(vec2 p) {
    vec2 uv = p / EXT * 0.5 + 0.5;
    // Outside the frame is empty, never a smear of the edge pixels.
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return texture(uPrev, uv) * inside.x * inside.y;
}

void main() {
    float k = DT * 60.0;
    vec2 p = vPos - CEN;
    float r = length(p);
    float z = pow(max(ZOOM, 0.05), k);
    float a = ROT * TAU * DT + SWIRL * TAU * DT * exp(-r * 1.6);
    p = rot2(-a) * p / z;
    if (WARP > 0.0) {
        vec3 q = vec3(p * WSCALE, TIME * 0.25);
        p += WARP * DT * vec2(snoise(q), snoise(q + vec3(31.7, 17.3, 5.1)));
    }
    if (RIPPLE > 0.0) {
        p -= (p / max(r, 1e-4)) * sin(r * RFREQ - TIME * 7.0) * RIPPLE * 0.006 * k;
    }
    p += CEN - DRIFT * DT;

    float keep = pow(clamp(DECAY, 0.0, 1.0), k);
    if (MODE > 0.5 && MODE < 1.5) {
        // Datamosh: blocks of the old frame slide and refuse to fade, like a broken codec.
        vec2 cell = floor(vPos * vec2(9.0, 14.0));
        float frame = floor(TIME * 12.0);
        float pick = hash12(cell + frame * 1.37);
        if (pick < AMOUNT * 0.35) {
            vec2 mv = (hash22(cell + frame) - 0.5) * vec2(0.08, 0.03);
            p = vPos + mv * (1.0 + KICK * 2.0);
            keep = mix(keep, 1.0, 0.85);
        }
    } else if (MODE > 1.5 && MODE < 2.5) {
        // Melt: bright paint drips down, faster on the bass.
        float l = luma(prevAt(p).rgb);
        p.y += min(l, 2.0) * AMOUNT * 0.012 * k * (0.5 + BASS);
    } else if (MODE > 2.5 && MODE < 3.5) {
        // Ink: the old frame is carried along a slow curl-noise current.
        p -= curl2(vec3(p * 1.8, TIME * 0.15)) * AMOUNT * 0.0035 * k;
    } else if (MODE > 3.5) {
        // Slit-scan: each row is a moment older than the one below it.
        p.y -= AMOUNT * 0.01 * k;
        p.x += sin(p.y * 6.0 + TIME) * AMOUNT * 0.002 * k;
    }

    vec4 c = prevAt(p);
    if (BLUR > 0.0) {
        vec2 d = vec2(BLUR * uPx);
        c = 0.4 * c + 0.15 * (prevAt(p + vec2(d.x, 0.0)) + prevAt(p - vec2(d.x, 0.0))
                              + prevAt(p + vec2(0.0, d.y)) + prevAt(p - vec2(0.0, d.y)));
    }
    if (HUE != 0.0) c.rgb = max(hueRotate(c.rgb, HUE * TAU * DT), 0.0);
    o = c * keep;
}
