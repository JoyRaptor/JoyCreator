in float vAcross;
in float vT;
in float vRow;
in float vVal;
in float vKind;
out vec4 o;

#define THICK    uP[1].z
#define GLOW     uP[1].w
#define CMODE    uP[2].z
#define PALSHIFT uP[2].w
#define BRIGHTN  uP[3].x
#define FILL     uP[3].y
#define HUESPIN  uP[5].x

void main() {
    if (vKind > 0.5) {
        // Paint that hides the rows behind (the background shows through it).
        o = vec4(0.0, 0.0, 0.0, 1.0) * FILL * uOpacity;
        return;
    }
    float d = abs(vAcross) - THICK * 0.5;
    float ct = CMODE < 0.5 ? vT : (CMODE < 1.5 ? vRow : clamp(vVal, 0.0, 1.0));
    vec3 c = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
    float core = coreOf(d);
    float glow = glowOf(d, GLOW * 0.02 + 1e-4) * GLOW;
    o = lightOut(c * (core + glow) * BRIGHTN * (0.55 + 0.45 * vRow), core);
}
