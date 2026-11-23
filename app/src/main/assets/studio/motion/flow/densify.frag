#version 100

precision highp float;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform sampler2D uPatchFlow;
uniform vec2 uLevelSize;
uniform vec2 uPatchGridSize;

vec4 packVelocity(vec2 velocity, float alpha);
vec2 unpackVelocity(vec4 encoded);

float coveringPatch(float pixel, float last, int slot) {
    if (slot == 2) return last;
    float regular = floor(pixel / 4.0) + float(slot) - 1.0;
    return regular >= 0.0 && regular < last ? regular : -1.0;
}

void main() {
    vec2 pixel = floor(gl_FragCoord.xy);
    vec2 last = uPatchGridSize - 1.0;
    float from = texture2D(uFrom, gl_FragCoord.xy / uLevelSize).r;
    vec2 flowSum = vec2(0.0);
    float weightSum = 0.0;
    // There can be three covering patches per axis at a flush, non-stride-aligned edge.
    for (int y = 0; y < 3; ++y) {
        for (int x = 0; x < 3; ++x) {
            vec2 patch = vec2(coveringPatch(pixel.x, last.x, x), coveringPatch(pixel.y, last.y, y));
            vec2 origin = min(patch * 4.0, uLevelSize - 8.0);
            vec2 inside = pixel - origin;
            if (any(lessThan(patch, vec2(0.0))) || any(lessThan(inside, vec2(0.0)))
                    || any(greaterThanEqual(inside, vec2(8.0)))) continue;
            vec2 flow = unpackVelocity(texture2D(uPatchFlow, (patch + 0.5) / uPatchGridSize));
            float to = texture2D(uTo, (gl_FragCoord.xy + flow) / uLevelSize).r;
            float weight = 1.0 / max(1.0 / 255.0, abs(to - from));
            flowSum += weight * flow;
            weightSum += weight;
        }
    }
    // Every in-bounds pixel is covered for levels at least 8 px wide and high.
    gl_FragColor = packVelocity(flowSum / weightSum, 1.0);
}
