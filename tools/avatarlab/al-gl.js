/* AvatarLab: drawing. One tiny GLES2 program: textured triangles, premultiplied alpha, a tint.
 * The solver hands over posed meshes; this only puts them on glass. Same shader runs on Android.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var VS = 'attribute vec2 p;attribute vec2 t;uniform vec4 uv;uniform vec2 ur;varying vec2 vt;' +
    'void main(){vec2 q=p*uv.xy+uv.zw;gl_Position=vec4(q.x/ur.x*2.0-1.0,1.0-q.y/ur.y*2.0,0.0,1.0);vt=t;}';
  var FS = 'precision mediump float;varying vec2 vt;uniform sampler2D us;uniform float ua;uniform vec4 uc;' +
    'void main(){vec4 c=texture2D(us,vt);c.rgb=mix(c.rgb,uc.rgb*c.a,uc.a);gl_FragColor=c*ua;}';

  AL.GL = function (canvas) {
    var gl = this.gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: true, preserveDrawingBuffer: true });
    this.canvas = canvas; this.tex = {}; this.ok = !!gl;
    if (!gl) return;
    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
    var pr = this.pr = gl.createProgram();
    gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS)); gl.linkProgram(pr); gl.useProgram(pr);
    this.loc = { p: gl.getAttribLocation(pr, 'p'), t: gl.getAttribLocation(pr, 't'), uv: gl.getUniformLocation(pr, 'uv'), ur: gl.getUniformLocation(pr, 'ur'), ua: gl.getUniformLocation(pr, 'ua'), uc: gl.getUniformLocation(pr, 'uc') };
    this.vb = gl.createBuffer(); this.tb = gl.createBuffer(); this.ib = gl.createBuffer();
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
  };
  AL.GL.prototype.texture = function (id) {
    var gl = this.gl, im = AL.img.get(id); if (!im) return null;
    var t = this.tex[id];
    if (t && t.ver === im.ver) return t.tex;
    if (t) gl.deleteTexture(t.tex);
    var tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, im.canvas);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.tex[id] = { tex: tex, ver: im.ver };
    return tex;
  };
  /** view = { s, ox, oy }: character pixels to canvas pixels. tint(item) may return [r,g,b,amount] or null. */
  AL.GL.prototype.draw = function (items, view, tint) {
    var gl = this.gl, L = this.loc, c = this.canvas; if (!gl) return;
    gl.viewport(0, 0, c.width, c.height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(this.pr);
    gl.uniform4f(L.uv, view.s, view.s, view.ox, view.oy); gl.uniform2f(L.ur, c.width, c.height);
    gl.enableVertexAttribArray(L.p); gl.enableVertexAttribArray(L.t);
    for (var i = 0; i < items.length; i++) {
      var it = items[i], tex = this.texture(it.img); if (!tex || it.a <= 0.003) continue;
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.vb); gl.bufferData(gl.ARRAY_BUFFER, it.v, gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.p, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.tb); gl.bufferData(gl.ARRAY_BUFFER, it.uv, gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.t, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, it.idx, gl.DYNAMIC_DRAW);
      var tc = tint ? tint(it) : null;
      gl.uniform4f(L.uc, tc ? tc[0] : 0, tc ? tc[1] : 0, tc ? tc[2] : 0, tc ? tc[3] : 0);
      gl.uniform1f(L.ua, it.a);
      gl.drawElements(gl.TRIANGLES, it.idx.length, gl.UNSIGNED_SHORT, 0);
    }
  };
  AL.GL.prototype.forget = function () { var gl = this.gl; if (!gl) return; for (var k in this.tex) gl.deleteTexture(this.tex[k].tex); this.tex = {}; };
})(typeof self !== 'undefined' ? self : this);
