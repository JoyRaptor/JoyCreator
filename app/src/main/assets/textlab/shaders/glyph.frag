// Field compose: writes one letter's distance and its facts.
// The depth test (GREATER) keeps, at every pixel, the letter that is NEAREST, with all of its
// facts: that is how a pixel between two letters knows which one it belongs to.
in vec2 vUv;
in float vLineY;
out vec4 o;
uniform sampler2D uSdf;   // this letter's own distance picture, em at scale 1, positive inside
uniform float uSdfScale;  // distances grow with the letter
uniform float uWeight;    // em added to every distance: fatter (+) or thinner (-) letters
uniform vec2  uLetter;    // reveal 0..1, (index + 0.5) / count
void main() {
    float d = texture(uSdf, vUv).r * uSdfScale + uWeight;
    o = vec4(d, uLetter.x, uLetter.y, vLineY);
    gl_FragDepth = clamp((d + uField.w) / (3.0 * uField.w), 0.0, 1.0);
}
