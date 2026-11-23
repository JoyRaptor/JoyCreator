#version 100

precision highp float;
precision highp sampler2D;
// The previous luma level uses linear filtering and clamp-to-edge. Set the target size to
// max(1, floor(sourceSize / 2)) per axis, with the viewport origin at (0, 0).
uniform sampler2D uSource;
uniform vec2 uSourceTexel;

void main() {
    // Two bilinear samples per axis at weights (1, 3) and (3, 1) give the oracle's
    // separable [1 3 3 1] / 8 kernel. Source pixels 2x-1 to 2x+2 are centred at 2x+0.5.
    vec2 centre = 2.0 * gl_FragCoord.xy;
    float total = texture2D(uSource, (centre + vec2(-0.75, -0.75)) * uSourceTexel).r
                + texture2D(uSource, (centre + vec2( 0.75, -0.75)) * uSourceTexel).r
                + texture2D(uSource, (centre + vec2(-0.75,  0.75)) * uSourceTexel).r
                + texture2D(uSource, (centre + vec2( 0.75,  0.75)) * uSourceTexel).r;
    gl_FragColor = vec4(total * 0.25, 0.0, 0.0, 1.0);
}
