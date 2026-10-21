// One immutable periodic secondary mask per stroke. All operations only remove primary coverage.
uniform int u_dualMode; // 0 legacy, 1 multiply, 2 arithmetic subtract, 3 minimum
uniform highp sampler2D u_dualMask;
uniform float u_dualInvPeriod;

float jb_dualTipCoverage(float primary, vec2 offsetPx, JbTip t) {
    if (u_dualMode == 0) return primary;
    float c = cos(t.angle), s = sin(t.angle);
    vec2 q = vec2(c*offsetPx.x+s*offsetPx.y, -s*offsetPx.x+c*offsetPx.y);
    float a = clamp(t.aspect, -1.0, 1.0);
    vec2 raw = t.radiusPx * vec2(a > 0.0 ? 1.0-a : 1.0, a < 0.0 ? 1.0+a : 1.0);
    vec2 axes = max(raw, vec2(max(t.minPx, 0.5)*0.5));
    float along = clamp(q.y/axes.y*0.5+0.5, 0.0, 1.0);
    float widthScale = max(1.0-clamp(t.taper, 0.0, 1.0)*along, 1e-3);
    vec2 normalized = q/(axes*vec2(widthScale, 1.0));
    float secondary = textureLod(u_dualMask, normalized*u_dualInvPeriod+vec2(0.5), 0.0).r;
    if (u_dualMode == 1) return primary*secondary;
    if (u_dualMode == 2) return max(primary-secondary, 0.0);
    return min(primary, secondary);
}
