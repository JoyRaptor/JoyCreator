// SLIME, deposit: every agent leaves a drop of scent where it stands (one point each).
uniform sampler2D uState;
out vec3 vCol;

#define COUNT    uP[0].x
#define DEPOSIT  uP[1].y
#define SPECIES  uP[3].w

void main() {
    int id = gl_VertexID;
    int W = textureSize(uState, 0).x;
    vec4 s = texelFetch(uState, ivec2(id % W, id / W), 0);
    if (float(id) >= COUNT) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 1.0; return; }
    float sp = s.w;
    vec3 mask = SPECIES < 1.5 ? vec3(1.0, 0.0, 0.0)
              : sp < 0.5 ? vec3(1.0, 0.0, 0.0) : sp < 1.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vCol = mask * DEPOSIT * (0.6 + LEVEL * 0.8);
    gl_Position = vec4(s.xy / EXT, 0.0, 1.0);
    gl_PointSize = 1.0;
}
