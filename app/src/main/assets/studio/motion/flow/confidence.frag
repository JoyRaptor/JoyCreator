#version 100

precision highp float;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform sampler2D uForward;
uniform vec2 uLevelSize;
#ifdef FLOW_HAS_REVERSE_FIELD
uniform sampler2D uBackward;
#endif

vec2 unpackVelocity(vec4 encoded);
vec2 sampleVelocity(sampler2D field, vec2 size, vec2 pixel);

float fromPixel(vec2 pixel) {
    return texture2D(uFrom, (pixel + 0.5) / uLevelSize).r;
}

void main() {
    vec2 pixel = floor(gl_FragCoord.xy);
    vec4 encoded = texture2D(uForward, gl_FragCoord.xy / uLevelSize);
    vec2 flow = unpackVelocity(encoded);
    vec2 landing = pixel + flow;
    if (any(lessThan(landing, vec2(0.0))) || any(greaterThan(landing, uLevelSize - 1.0))) {
        gl_FragColor = vec4(encoded.rgb, 0.0);
        return;
    }
    float confidence = 1.0;
#ifdef FLOW_HAS_REVERSE_FIELD
    vec2 backward = sampleVelocity(uBackward, uLevelSize, landing);
    vec2 roundTrip = flow + backward;
    float errorSquared = dot(roundTrip, roundTrip);
    float limit = 0.01 * (dot(flow, flow) + dot(backward, backward)) + 0.5;
    confidence = errorSquared > limit ? 0.0 : exp(-errorSquared / limit);
#endif
    float residual = texture2D(uTo, (landing + 0.5) / uLevelSize).r - fromPixel(pixel);
    confidence *= exp(-(residual * residual) / 0.0128);

    vec3 tensor = vec3(0.0);
    for (int y = -2; y <= 2; ++y) {
        for (int x = -2; x <= 2; ++x) {
            vec2 p = pixel + vec2(float(x), float(y));
            vec2 g = 0.5 * vec2(fromPixel(p + vec2(1.0, 0.0)) - fromPixel(p - vec2(1.0, 0.0)),
                                fromPixel(p + vec2(0.0, 1.0)) - fromPixel(p - vec2(0.0, 1.0)));
            tensor += vec3(g.x * g.x, g.x * g.y, g.y * g.y);
        }
    }
    float diagonal = tensor.x - tensor.z;
    float disc = sqrt(diagonal * diagonal + 4.0 * tensor.y * tensor.y);
    float smallerEigenvalue = max(0.0, 0.5 * (tensor.x + tensor.z - disc));
    confidence *= smallerEigenvalue / (smallerEigenvalue + 0.0001);
    // Keep the packed field in RGB. The flow confidence is stored in the spare alpha byte.
    gl_FragColor = vec4(encoded.rgb, confidence);
}
