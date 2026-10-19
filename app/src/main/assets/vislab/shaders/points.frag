in vec2 vQ;
in vec3 vCol;
in float vA;
in float vBlur;
out vec4 o;
void main() {
    float r2 = dot(vQ, vQ);
    // In focus: a hot dot with a halo. Out of focus: a soft disc with a faint rim (bokeh).
    float sharp = exp(-r2 * 6.0) + exp(-r2 * 30.0);
    float r = sqrt(r2);
    float bokeh = smoothstep(1.0, 0.85, r) * (0.35 + 0.65 * smoothstep(0.5, 0.95, r));
    float I = mix(sharp, bokeh * 0.6, vBlur);
    if (I < 0.002) discard;
    o = vec4(vCol * I * vA, 0.0) * uOpacity;
}
