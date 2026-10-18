in vec2 vQ;
in vec3 vCol;
in float vA;
in float vKind;
out vec4 o;

#define LINEW uP[1].w
#define GLOW  uP[2].x

void main() {
    float I;
    if (vKind < 0.5) {
        float g = 1.0 + GLOW * 2.0;
        float r2 = dot(vQ, vQ) * g * g;
        I = exp(-r2 * 30.0) * 2.0 + exp(-r2 * 4.0) * GLOW;
    } else {
        float d = abs(vQ.y) - LINEW * 0.5;
        I = coreOf(d) + glowOf(d, GLOW * 0.012 + 1e-4) * GLOW * 0.5;
    }
    if (I < 0.002) discard;
    o = vec4(vCol * I * vA, 0.0) * uOpacity;
}
