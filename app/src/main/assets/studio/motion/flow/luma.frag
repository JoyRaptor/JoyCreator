#version 100

precision highp float;
uniform sampler2D uSource;
varying highp vec2 vUv;

void main() {
    // LumaImage.fromArgb works in coded RGB. Decode transfer only for the final shutter blend.
    vec3 rgb = texture2D(uSource, vUv).rgb;
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    gl_FragColor = vec4(luma, 0.0, 0.0, 1.0);
}
