// Field compose: writes one piece's distance and its facts.
// The depth test (GREATER) keeps, at every pixel, the piece that is NEAREST, with all of its
// facts: that is how a pixel between two letters knows which one it belongs to.
// Neon tubes add one rule: INSIDE a tube, the later tube wins whole. Two tubes that cross do
// not melt into each other; one passes in front of the other.
in vec2 vUv;
in float vLineY;
out vec4 o;
uniform sampler2D uSdf;   // this piece's own distance picture, em at scale 1, positive inside (a tube's g channel: 1 = bare glass, 0 = painted black)
uniform float uSdfScale;  // distances grow with the letter
uniform float uWeight;    // em added to every distance: fatter (+) or thinner (-) letters
uniform vec2  uLetter;    // reveal 0..1, (index + 0.5) / count
uniform vec2  uTubePiece; // 1 = this piece is a tube drawn from a path; its order among its letter's tubes
void main() {
    vec4 t = texture(uSdf, vUv);
    float d = t.r * uSdfScale + uWeight;
    bool tube = uTubePiece.x > 0.5;
    o = vec4(d, uLetter.x, uLetter.y, tube ? t.g : vLineY);
    float depth = clamp((d + uField.w) / (3.0 * uField.w), 0.0, 1.0) * 0.7;
    if (tube && d > 0.0) depth = 0.75 + 0.02 * uTubePiece.y + depth * 0.01;
    gl_FragDepth = depth;
}
