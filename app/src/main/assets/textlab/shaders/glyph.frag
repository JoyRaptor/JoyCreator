// Field compose: writes one piece's distance and its facts.
// The depth test (GREATER) keeps, at every pixel, the piece that is NEAREST, with all of its
// facts: that is how a pixel between two letters knows which one it belongs to.
// Neon tubes add one rule: INSIDE a tube, the later tube wins whole. Two tubes that cross do
// not melt into each other; one passes in front of the other.
in vec2 vUv;
in float vLineY;
out vec4 o;
uniform sampler2D uSdf;   // this piece's own picture: r = distance, em at scale 1, positive inside; g = an extra fact (see uTubePiece)
uniform float uSdfScale;  // distances grow with the letter
uniform float uWeight;    // em added to every distance: fatter (+) or thinner (-) letters
uniform vec2  uLetter;    // reveal 0..1, (index + 0.5) / count
uniform vec2  uArc;       // a path tube's share of its letter's writing: where it starts (0..1) and how much of the letter it is
uniform float uShard;     // which shard of a broken letter this draw is (-1 = the whole letter)
uniform vec2  uSeeds[8];  // the letter's break points, em in its own cell
uniform vec4  uCell;      // the letter's cell at rest (as in the vertex stage)
uniform vec2  uTubePiece; // x: 0 = a letter (the field's a = height in its line); 1 = a tube drawn from a path (a = 1 bare glass, 0 painted); 2 = a letter for a neon OUTLINE (a = distance to its loop's break, em).  y: a path tube's order among its letter's tubes
void main() {
    vec4 t = texture(uSdf, vUv);
    float d = t.r * uSdfScale + uWeight;
    if (uShard > -0.5) {
        // one SHARD: the part of the letter nearest its own break point. The crack is an edge like any other
        // (so a shard is a solid chunk with the look's whole finish), and outside it the distance runs on.
        vec2 p = vUv * uCell.zw;
        int k = int(uShard + 0.5);
        float mine = distance(p, uSeeds[k]), other = 1e9;
        for (int i = 0; i < 8; i++) if (i != k) other = min(other, distance(p, uSeeds[i]));
        d = max(min(d, ((other - mine) * 0.5 - 0.003) * uSdfScale), -uField.w);
    }
    bool pathTube = uTubePiece.x > 0.5 && uTubePiece.x < 1.5;
    // (a path tube's own picture says 1 + how far along the tube; the field then says 1 + how far along the LETTER)
    o = vec4(d, uLetter.x, uLetter.y, pathTube ? 1.0 + uArc.x + (t.g - 1.0) * uArc.y : uTubePiece.x > 0.5 ? t.g : vLineY);
    float depth = clamp((d + uField.w) / (3.0 * uField.w), 0.0, 1.0) * 0.7;
    if (pathTube && d > 0.0) depth = 0.75 + 0.02 * uTubePiece.y + depth * 0.01;
    gl_FragDepth = depth;
}
