// Field compose: one letter (or one whole drawing) laid into the field as a quad.
// Six vertices, no buffers. The host draws one of these per letter with its own little pose.
uniform vec4  uCell;    // the letter's cell at rest, em: x, y of its bottom left, then width, height
uniform vec2  uPivot;   // the point it turns and grows about, em
uniform vec2  uMove;    // moved by, em
uniform vec2  uScale;   // grown by
uniform float uTurn;    // turned by, radians
uniform vec2  uLine;    // its line: baseline y (em), cap height (em)
out vec2 vUv;
out float vLineY;
void main() {
    int c = gl_VertexID % 6;
    vec2 a = vec2((c == 1 || c == 2 || c == 4) ? 1.0 : 0.0, (c == 2 || c == 4 || c == 5) ? 1.0 : 0.0);
    vec2 rest = uCell.xy + a * uCell.zw;
    vLineY = (rest.y - uLine.x) / uLine.y;
    vec2 q = rot(uTurn) * ((rest - uPivot) * uScale) + uPivot + uMove;
    vUv = a;
    gl_Position = vec4(q / uField.xy * 2.0 - 1.0, 0.0, 1.0);
}
