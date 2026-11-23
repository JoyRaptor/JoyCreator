#version 100

precision highp float;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform sampler2D uCoarseFlow;
uniform vec2 uLevelSize;
// (0, 0) seeds the coarsest level with zero. Otherwise use the true size ratio on odd levels.
uniform vec2 uCoarseSize;

vec4 packVelocity(vec2 velocity, float alpha);
vec2 sampleVelocity(sampler2D field, vec2 size, vec2 pixel);

float fromPixel(vec2 pixel) {
    return texture2D(uFrom, (pixel + 0.5) / uLevelSize).r;
}

void main() {
    // 8 px patches, stride 4. The final patch is flush with the far edge, even on odd sizes.
    vec2 origin = min(floor(gl_FragCoord.xy) * 4.0, uLevelSize - 8.0);
    vec2 flow = vec2(0.0);
    if (uCoarseSize.x > 0.0) {
        vec2 scale = uLevelSize / uCoarseSize;
        flow = sampleVelocity(uCoarseFlow, uCoarseSize, (origin + 4.0) / scale - 0.5) * scale;
    }

    vec3 tpl[64];
    vec3 hessian = vec3(0.0);
    vec2 gradientSum = vec2(0.0);
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            vec2 p = origin + vec2(float(x), float(y));
            vec2 g = 0.5 * vec2(fromPixel(p + vec2(1.0, 0.0)) - fromPixel(p - vec2(1.0, 0.0)),
                                fromPixel(p + vec2(0.0, 1.0)) - fromPixel(p - vec2(0.0, 1.0)));
            tpl[y * 8 + x] = vec3(fromPixel(p), g);
            hessian += vec3(g.x * g.x, g.x * g.y, g.y * g.y);
            gradientSum += g;
        }
    }
    float determinant = hessian.x * hessian.z - hessian.y * hessian.y;
    if (determinant >= 1.0e-9) {
        vec2 candidate = flow;
        float acceptedError = 1.0e20;
        // Evaluate the initial guess and up to 12 steps. Keep the previous vector if the
        // residual stops decreasing. Arrays and loop bounds are fixed for GLES2.
        for (int trial = 0; trial < 13; ++trial) {
            float sum = 0.0;
            float squares = 0.0;
            vec2 gradientDot = vec2(0.0);
            for (int y = 0; y < 8; ++y) {
                for (int x = 0; x < 8; ++x) {
                    vec3 t = tpl[y * 8 + x];
                    vec2 p = origin + vec2(float(x), float(y)) + candidate;
                    float residual = texture2D(uTo, (p + 0.5) / uLevelSize).r - t.x;
                    sum += residual;
                    squares += residual * residual;
                    gradientDot += t.yz * residual;
                }
            }
#ifndef FLOW_DISABLE_MEAN_NORMALISATION
            // Sum (residual - mean)^2 and gradient times the centred residual in one sweep.
            float mean = sum / 64.0;
            squares = max(0.0, squares - mean * sum);
            gradientDot -= gradientSum * mean;
#endif
            if (squares >= acceptedError) break;
            float stepSquared = dot(candidate - flow, candidate - flow);
            flow = candidate;
            acceptedError = squares;
            if (trial > 0 && stepSquared < 1.0e-6) break;
            vec2 step = vec2(hessian.z * gradientDot.x - hessian.y * gradientDot.y,
                             hessian.x * gradientDot.y - hessian.y * gradientDot.x) / determinant;
            candidate = flow - step;
        }
    }

    gl_FragColor = packVelocity(flow, 1.0);
}
