// Blit a (possibly smaller) premultiplied target onto the current one, bilinear.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uSrc;
void main() {
    o = texture(uSrc, vUv) * uOpacity;
}
