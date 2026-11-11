// Shows one texture as it is (checks and thumbnails). uShow: scale, offset, 1 = red channel as grey.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform vec3 uShow;
void main() {
    vec4 c = texture(uSrc, vUv);
    vec3 v = uShow.z > 0.5 ? vec3(c.r) : c.rgb;
    o = vec4(v * uShow.x + uShow.y, 1.0);
}
