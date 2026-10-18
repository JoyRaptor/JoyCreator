// WATERFALL: the last two seconds of sound as a scrolling picture (a spectrogram), flat
// like a studio meter or tipped back into 3D. Precise and grown-up next to the neon looks.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define DIRN     uP[0].x   // 0 rises, 1 falls, 2 flows out from the middle
#define CONTRAST uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define TILT     uP[1].x
#define MIRROR   uP[1].y
#define GRIDL    uP[1].z
#define HUESPIN  uP[1].w

void main() {
    vec2 uv = vUv;
    if (TILT > 0.0) {
        float y = uv.y;
        float z = 1.0 / (1.0 + TILT * 3.0 * (1.0 - y));
        uv.x = (uv.x - 0.5) / mix(1.0, z, 0.8) + 0.5;
        uv.y = pow(y, 1.0 + TILT);
        if (uv.x < 0.0 || uv.x > 1.0) { o = vec4(0.0); return; }
    }
    float f = uv.x;
    if (MIRROR > 0.5) f = abs(uv.x * 2.0 - 1.0);
    float age;
    if (DIRN < 0.5) age = uv.y;            // newest at the bottom, rising
    else if (DIRN < 1.5) age = 1.0 - uv.y;  // newest at the top, falling
    else age = abs(uv.y * 2.0 - 1.0);
    float v = hist(f, age);
    v = pow(clamp(v, 0.0, 1.0), CONTRAST);
    vec3 col = palw(v * 0.5 + PALSHIFT + TIME * HUESPIN) * v * (1.5 - age * 0.8);
    if (GRIDL > 0.0) {
        float g = step(0.97, fract(uv.x * 10.0)) + step(0.98, fract(uv.y * 8.0));
        col += vec3(0.25) * g * GRIDL;
    }
    o = lightOut(col * BRIGHTN, 0.0);
}
