in vec2 vQ;
in vec3 vCol;
in float vA;
in float vLook;
out vec4 o;

void main() {
    vec2 q = vQ;
    float r2 = dot(q, q);
    float r = sqrt(r2);
    float L = vLook;
    float I;
    float cover = 0.0;
    if (L < 0.5 || (L > 5.5 && L < 6.5)) {                 // glow, ember
        I = exp(-r2 * 5.0) + exp(-r2 * 30.0) * 1.5;
    } else if (L < 1.5 || L > 7.5) {                        // spark, streak
        float w = (q.x + 1.0) * 0.5;
        I = exp(-q.y * q.y * 6.0) * w * w * smoothstep(1.0, 0.6, q.x) * 1.6;
        if (L > 7.5) I = exp(-q.y * q.y * 9.0) * smoothstep(1.0, 0.7, abs(q.x));
    } else if (L < 2.5) {                                   // bokeh
        float disc = smoothstep(1.0, 0.9, r);
        float rim = smoothstep(0.6, 0.92, r) * disc;
        I = disc * 0.22 + rim * 0.55 + exp(-r2 * 6.0) * 0.08;
    } else if (L < 3.5) {                                   // star flare
        float cross_ = exp(-abs(q.x) * 14.0) * exp(-q.y * q.y * 1.2) + exp(-abs(q.y) * 14.0) * exp(-q.x * q.x * 1.2);
        vec2 d = vec2(q.x + q.y, q.x - q.y) * 0.7071;
        float diag = exp(-abs(d.x) * 20.0) * exp(-d.y * d.y * 4.0) + exp(-abs(d.y) * 20.0) * exp(-d.x * d.x * 4.0);
        I = exp(-r2 * 24.0) * 2.0 + cross_ * 0.9 + diag * 0.35 + exp(-r2 * 3.0) * 0.12;
    } else if (L < 4.5) {                                   // ring
        I = exp(-abs(r - 0.7) * 16.0) * smoothstep(1.0, 0.9, r);
    } else if (L < 5.5) {                                   // square (paint)
        float m = max(abs(q.x), abs(q.y));
        I = smoothstep(0.9, 0.75, m);
        cover = I;
    } else {                                                // soft dot (paint): dust, snow, smoke
        I = smoothstep(1.0, 0.2, r);
        cover = I * 0.8;
    }
    if (I < 0.002) discard;
    o = vec4(vCol * I * vA, min(cover * vA, 1.0) * (1.0 - uAdd)) * uOpacity;
}
