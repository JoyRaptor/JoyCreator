#version 100

precision highp float;
precision highp sampler2D;
varying highp vec2 vUv;
uniform sampler2D uSource;
uniform sampler2D uFlow;
uniform sampler2D uGuide;
// The visible source rectangle in the staged texture, in bottom-up UV coordinates.
uniform vec4 uRegion;
uniform vec2 uStageSize;
uniform vec2 uSourcePerFlowPixel;
uniform vec2 uFlowSize;
uniform float uTravelScale;
uniform vec4 uOffsetsA;
uniform vec4 uOffsetsB;
uniform vec4 uWeightsA;
uniform vec4 uWeightsB;

vec2 unpackVelocity(vec4 encoded);

float trust(float confidence) {
    return clamp((confidence - 0.25) / 0.5, 0.0, 1.0);
}

float codedLuma(vec4 color) {
    return dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
}

void guideCorner(vec2 pixel, float spatial, float reference,
        inout vec2 velocitySum, inout float confidenceSum, inout float support) {
    vec2 uv = (pixel + 0.5) / uFlowSize;
    float edge = max(0.0, 1.0 - abs(texture2D(uGuide, uv).r - reference) * (255.0 / 32.0));
    vec4 field = texture2D(uFlow, uv);
    float weight = spatial * edge * edge;
    support += weight;
    confidenceSum += weight * field.a;
    velocitySum += weight * field.a * unpackVelocity(field);
}

vec3 guidedFlow(vec2 local, float reference) {
    vec2 pixel = local * uFlowSize - 0.5;
    vec2 low = floor(pixel);
    vec2 t = fract(pixel);
    vec2 sum = vec2(0.0);
    float confidence = 0.0;
    float support = 0.0;
    guideCorner(low, (1.0 - t.x) * (1.0 - t.y), reference, sum, confidence, support);
    guideCorner(low + vec2(1.0, 0.0), t.x * (1.0 - t.y), reference, sum, confidence, support);
    guideCorner(low + vec2(0.0, 1.0), (1.0 - t.x) * t.y, reference, sum, confidence, support);
    guideCorner(low + 1.0, t.x * t.y, reference, sum, confidence, support);
    if (confidence <= 0.000001) return vec3(0.0);
    return vec3(sum / confidence, trust(confidence / support));
}

vec4 toLinear(vec4 color) {
    vec3 straight = clamp(color.rgb / max(color.a, 1.0 / 255.0), 0.0, 1.0);
    return vec4(color.a * mix(straight / 12.92,
            pow((straight + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), straight)), color.a);
}

vec4 toCoded(vec4 color) {
    vec3 straight = clamp(color.rgb / max(color.a, 0.000001), 0.0, 1.0);
    return vec4(color.a * mix(12.92 * straight,
            1.055 * pow(straight, vec3(1.0 / 2.4)) - 0.055,
            step(vec3(0.0031308), straight)), color.a);
}

bool inPicture(vec2 uv) {
    return all(greaterThanEqual(uv, uRegion.xy)) && all(lessThan(uv, uRegion.xy + uRegion.zw));
}

vec4 linearDonor(vec2 pixel, vec4 centre) {
    vec2 uv = (pixel + 0.5) / uStageSize;
    if (!inPicture(uv)) return centre;
    return toLinear(texture2D(uSource, uv));
}

vec4 linearSample(vec2 uv, vec4 centre) {
    vec2 pixel = uv * uStageSize - 0.5;
    vec2 low = floor(pixel);
    vec2 t = fract(pixel);
    return mix(mix(linearDonor(low, centre), linearDonor(low + vec2(1.0, 0.0), centre), t.x),
            mix(linearDonor(low + vec2(0.0, 1.0), centre), linearDonor(low + 1.0, centre), t.x), t.y);
}

void applyTap(float offset, float weight, vec2 local, vec3 field, vec4 centre,
        inout vec4 delta, inout float accepted) {
    if (weight <= 0.0 || offset == 0.0) return;
    vec2 donor = local - offset * field.xy / uFlowSize;
    vec2 texelInset = 0.5 / (uStageSize * uRegion.zw);
    if (any(lessThan(donor, texelInset)) || any(greaterThan(donor, 1.0 - texelInset))) return;

    // One nearest flow lookup per tap conservatively rejects a different surface.
    vec4 neighbour = texture2D(uFlow, donor);
    vec2 difference = (unpackVelocity(neighbour) - field.xy) * uSourcePerFlowPixel;
    vec2 velocity = field.xy * uSourcePerFlowPixel;
    if (dot(difference, difference) > 0.5625 + dot(velocity, velocity) / 16.0) return;
    float usable = field.z * trust(neighbour.a) * weight;
    if (usable <= 0.0) return;
    vec2 uv = uRegion.xy + donor * uRegion.zw;
    delta += usable * (linearSample(uv, centre) - centre);
    accepted += usable;
}

void main() {
    vec4 color = texture2D(uSource, vUv);
    gl_FragColor = color;
    if (!inPicture(vUv)) return;
    vec2 local = (vUv - uRegion.xy) / uRegion.zw;
    vec3 field = guidedFlow(local, codedLuma(color));
    if (field.z == 0.0 || uTravelScale * length(field.xy * uSourcePerFlowPixel) < 0.5) return;

    vec4 centre = toLinear(color);
    vec4 delta = vec4(0.0);
    float accepted = 0.0;
    applyTap(uOffsetsA.x, uWeightsA.x, local, field, centre, delta, accepted);
    applyTap(uOffsetsA.y, uWeightsA.y, local, field, centre, delta, accepted);
    applyTap(uOffsetsA.z, uWeightsA.z, local, field, centre, delta, accepted);
    applyTap(uOffsetsA.w, uWeightsA.w, local, field, centre, delta, accepted);
    applyTap(uOffsetsB.x, uWeightsB.x, local, field, centre, delta, accepted);
    applyTap(uOffsetsB.y, uWeightsB.y, local, field, centre, delta, accepted);
    applyTap(uOffsetsB.z, uWeightsB.z, local, field, centre, delta, accepted);
    applyTap(uOffsetsB.w, uWeightsB.w, local, field, centre, delta, accepted);
    if (accepted > 0.0 && any(notEqual(delta, vec4(0.0)))) {
        gl_FragColor = toCoded(max(centre + delta, 0.0));
    }
}
