#version 100

precision highp float;
precision highp sampler2D;
varying highp vec2 vUv;
uniform sampler2D uPrevious;
uniform sampler2D uCurrent;
uniform sampler2D uNext;
uniform sampler2D uBeforeForward;
uniform sampler2D uBeforeBackward;
uniform sampler2D uAfterForward;
uniform sampler2D uAfterBackward;
uniform vec4 uRegion;
uniform vec2 uStageSize;
uniform vec2 uFlowSize;
uniform vec2 uSourcePerFlowPixel;
uniform vec2 uExtents;
uniform vec4 uFractionsA, uFractionsB, uFractionsC;
uniform vec4 uWeightsA, uWeightsB, uWeightsC;

vec2 unpackVelocity(vec4 encoded);

float trust(float confidence) {
    return clamp((confidence - 0.25) / 0.5, 0.0, 1.0);
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

bool consistent(sampler2D field, sampler2D inverse, vec2 point) {
    vec2 velocity = unpackVelocity(texture2D(field, point));
    vec2 landing = point + velocity / uFlowSize;
    if (any(lessThan(landing, vec2(0.0))) || any(greaterThan(landing, vec2(1.0)))) return false;
    vec2 reverse = unpackVelocity(texture2D(inverse, landing));
    vec2 error = velocity + reverse;
    float limit = 0.01 + 0.05 * (dot(velocity, velocity) + dot(reverse, reverse));
    return dot(error, error) <= limit;
}

vec2 inverseWarp(sampler2D field, vec2 outputPoint, float fraction) {
    vec2 donor = outputPoint;
    donor = outputPoint - fraction * unpackVelocity(texture2D(field, donor)) / uFlowSize;
    donor = outputPoint - fraction * unpackVelocity(texture2D(field, donor)) / uFlowSize;
    donor = outputPoint - fraction * unpackVelocity(texture2D(field, donor)) / uFlowSize;
    return donor;
}

void donor(sampler2D color, sampler2D field, sampler2D inverse,
        vec2 outputPoint, float fraction, float weight, vec4 centre,
        inout vec4 delta, inout float accepted) {
    if (weight <= 0.0) return;
    vec2 point = inverseWarp(field, outputPoint, fraction);
    vec2 inset = 0.5 / (uStageSize * uRegion.zw);
    if (any(lessThan(point, inset)) || any(greaterThan(point, 1.0 - inset))) return;
    vec4 packed = texture2D(field, point);
    if (!consistent(field, inverse, point)) return;
    vec2 velocity = unpackVelocity(packed);
    vec2 neighbour = unpackVelocity(texture2D(field, point + vec2(1.0) / uFlowSize));
    vec2 difference = (velocity - neighbour) * uSourcePerFlowPixel;
    velocity *= uSourcePerFlowPixel;
    if (dot(difference, difference) > 0.5625 + dot(velocity, velocity) / 16.0) return;
    float usable = weight * trust(packed.a);
    if (usable <= 0.0) return;
    vec2 uv = uRegion.xy + point * uRegion.zw;
    vec4 sampleColor = toLinear(texture2D(color, uv));
    delta += usable * (sampleColor - centre);
    accepted += usable;
}

void tap(float encoded, float weight, vec2 point, vec4 centre,
        inout vec4 delta, inout float accepted) {
    if (weight <= 0.0 || encoded == 0.0) return;
    if (encoded < 0.0) {
        float alpha = encoded + 1.0;
        donor(uPrevious, uBeforeForward, uBeforeBackward, point, alpha,
                (1.0 - alpha) * weight, centre, delta, accepted);
        donor(uCurrent, uBeforeBackward, uBeforeForward, point, 1.0 - alpha,
                alpha * weight, centre, delta, accepted);
    } else {
        float alpha = encoded - 1.0;
        donor(uCurrent, uAfterForward, uAfterBackward, point, alpha,
                (1.0 - alpha) * weight, centre, delta, accepted);
        donor(uNext, uAfterBackward, uAfterForward, point, 1.0 - alpha,
                alpha * weight, centre, delta, accepted);
    }
}

void main() {
    vec4 coded = texture2D(uCurrent, vUv);
    gl_FragColor = coded;
    if (!inPicture(vUv)) return;
    vec2 point = (vUv - uRegion.xy) / uRegion.zw;
    vec2 before = unpackVelocity(texture2D(uBeforeBackward, point)) * uExtents.x * uSourcePerFlowPixel;
    vec2 after = unpackVelocity(texture2D(uAfterForward, point)) * uExtents.y * uSourcePerFlowPixel;
    if (max(max(length(before), length(after)), length(after - before)) < 0.5) return;
    vec4 centre = toLinear(coded);
    vec4 delta = vec4(0.0);
    float accepted = 0.0;
    tap(uFractionsA.x, uWeightsA.x, point, centre, delta, accepted);
    tap(uFractionsA.y, uWeightsA.y, point, centre, delta, accepted);
    tap(uFractionsA.z, uWeightsA.z, point, centre, delta, accepted);
    tap(uFractionsA.w, uWeightsA.w, point, centre, delta, accepted);
    tap(uFractionsB.x, uWeightsB.x, point, centre, delta, accepted);
    tap(uFractionsB.y, uWeightsB.y, point, centre, delta, accepted);
    tap(uFractionsB.z, uWeightsB.z, point, centre, delta, accepted);
    tap(uFractionsB.w, uWeightsB.w, point, centre, delta, accepted);
    tap(uFractionsC.x, uWeightsC.x, point, centre, delta, accepted);
    tap(uFractionsC.y, uWeightsC.y, point, centre, delta, accepted);
    tap(uFractionsC.z, uWeightsC.z, point, centre, delta, accepted);
    tap(uFractionsC.w, uWeightsC.w, point, centre, delta, accepted);
    if (accepted > 0.0 && any(notEqual(delta, vec4(0.0)))) {
        gl_FragColor = toCoded(max(centre + delta, 0.0));
    }
}
