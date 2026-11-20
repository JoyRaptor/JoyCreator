/* AvatarLab: drawing. One tiny GLES2 program: textured triangles, premultiplied alpha, a tint, and a mask.
 * The solver hands over posed meshes; this only puts them on glass. Same shader runs on Android.
 *
 * Masks: a drawing may be shown only INSIDE another part, and/or be CUT where other parts are. The masking
 * parts are drawn first into an off-screen "how much shows here" picture (soft edges, because it is the
 * art's own alpha), and the drawing is multiplied by it. Phone: an FBO, or saveLayer + DST_IN / DST_OUT.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var VS = 'attribute vec2 p;attribute vec2 t;uniform vec4 uv;uniform vec2 ur;varying vec2 vt;' +
    'void main(){vec2 q=p*uv.xy+uv.zw;gl_Position=vec4(q.x/ur.x*2.0-1.0,1.0-q.y/ur.y*2.0,0.0,1.0);vt=t;}';
  var FS = 'precision mediump float;varying vec2 vt;uniform sampler2D us;uniform sampler2D um;uniform float ua;uniform float uk;uniform vec4 uc;uniform vec2 uq;' +
    'void main(){vec4 c=texture2D(us,vt);c.rgb=mix(c.rgb,uc.rgb*c.a,uc.a);float m=1.0;if(uk>0.5){m=clamp(texture2D(um,gl_FragCoord.xy/uq).a*2.2,0.0,1.0);}gl_FragColor=c*ua*m;}';

  AL.GL = function (canvas) {
    var gl = this.gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: true, preserveDrawingBuffer: true });
    this.canvas = canvas; this.tex = {}; this.ok = !!gl;
    if (!gl) return;
    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
    var pr = this.pr = gl.createProgram();
    gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, FS)); gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr)); gl.useProgram(pr);
    var L = this.loc = {}; ['uv', 'ur', 'uq', 'ua', 'uc', 'uk', 'us', 'um'].forEach(function (k) { L[k] = gl.getUniformLocation(pr, k); });
    L.p = gl.getAttribLocation(pr, 'p'); L.t = gl.getAttribLocation(pr, 't');
    gl.uniform1i(L.us, 0); gl.uniform1i(L.um, 1);
    this.vb = gl.createBuffer(); this.tb = gl.createBuffer(); this.ib = gl.createBuffer();
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
  };
  AL.GL.prototype.texture = function (id) {
    var gl = this.gl, im = AL.img.get(id); if (!im) return null;
    var t = this.tex[id];
    if (t && t.ver === im.ver) return t.tex;
    if (t) gl.deleteTexture(t.tex);
    var tex = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, im.canvas);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.tex[id] = { tex: tex, ver: im.ver };
    return tex;
  };
  AL.GL.prototype.maskTarget = function (slot) {
    var gl = this.gl, c = this.canvas, key = slot ? 'mk2' : 'mk', m = this[key];
    if (m && m.w === c.width && m.h === c.height) return m;
    if (m) { gl.deleteTexture(m.tex); gl.deleteFramebuffer(m.fb); }
    var tex = gl.createTexture(); gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, c.width, c.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    var fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.bindTexture(gl.TEXTURE_2D, null); gl.activeTexture(gl.TEXTURE0);
    return (this[key] = { tex: tex, fb: fb, w: c.width, h: c.height });
  };
  AL.GL.prototype.one = function (it, alpha, tc, masked) {
    var gl = this.gl, L = this.loc, tex = this.texture(it.img); if (!tex || alpha <= 0.003) return;
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vb); gl.bufferData(gl.ARRAY_BUFFER, it.v, gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.p, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.tb); gl.bufferData(gl.ARRAY_BUFFER, it.uv, gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.t, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, it.idx, gl.DYNAMIC_DRAW);
    gl.uniform4f(L.uc, tc ? tc[0] : 0, tc ? tc[1] : 0, tc ? tc[2] : 0, tc ? tc[3] : 0);
    gl.uniform1f(L.ua, alpha); gl.uniform1f(L.uk, masked ? 1 : 0);
    gl.drawElements(gl.TRIANGLES, it.idx.length, gl.UNSIGNED_SHORT, 0);
  };
  /** Paint "how much shows here" for one mask: inside-shape first (or everything), then cut the cutters out. */
  AL.GL.prototype.paintMask = function (mask) {
    var gl = this.gl, m = this.maskTarget(), self = this;
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, null);
    gl.bindFramebuffer(gl.FRAMEBUFFER, m.fb);
    if (mask.inside) { gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA); mask.inside.forEach(function (it) { self.one(it, 1, null, false); }); }
    else { gl.clearColor(1, 1, 1, 1); gl.clear(gl.COLOR_BUFFER_BIT); }
    /* more shapes it must ALSO be inside (an eyelid inside its eye, and the eye inside the outline of the head): keep only where every one is */
    (mask.more || []).forEach(function (list) {
      var m2 = self.maskTarget(1);
      gl.bindFramebuffer(gl.FRAMEBUFFER, m2.fb); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA); list.forEach(function (it) { self.one(it, 1, null, false); });
      gl.bindFramebuffer(gl.FRAMEBUFFER, m.fb); gl.blendFunc(gl.ZERO, gl.SRC_ALPHA); self.quad(m2.tex);
    });
    gl.blendFunc(gl.ZERO, gl.ONE_MINUS_SRC_ALPHA);
    mask.cuts.forEach(function (list) { list.forEach(function (it) { self.one(it, 1, null, false); }); });
    gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, m.tex); gl.activeTexture(gl.TEXTURE0);
  };
  /** One picture over the whole canvas (a mask multiplied into another). */
  AL.GL.prototype.quad = function (tex) {
    var gl = this.gl, L = this.loc, c = this.canvas, v = this._view || { s: 1, ox: 0, oy: 0 };
    gl.uniform4f(L.uv, 1, 1, 0, 0); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, c.width, 0, 0, c.height, c.width, c.height]), gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.p, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.tb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 1, 1, 1, 0, 0, 1, 0]), gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.t, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 1, 3, 2]), gl.DYNAMIC_DRAW);
    gl.uniform4f(L.uc, 0, 0, 0, 0); gl.uniform1f(L.ua, 1); gl.uniform1f(L.uk, 0);
    gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
    gl.uniform4f(L.uv, v.s, v.s, v.ox, v.oy);
  };
  /** view = { s, ox, oy }: character pixels to canvas pixels. tint(item) may return [r,g,b,amount] or null. */
  AL.GL.prototype.draw = function (items, view, tint) {
    var gl = this.gl, L = this.loc, c = this.canvas; if (!gl) return;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, c.width, c.height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(this.pr); this._view = view;
    gl.uniform4f(L.uv, view.s, view.s, view.ox, view.oy); gl.uniform2f(L.ur, c.width, c.height); gl.uniform2f(L.uq, c.width, c.height);
    gl.enableVertexAttribArray(L.p); gl.enableVertexAttribArray(L.t);
    var last = null;
    for (var i = 0; i < items.length; i++) {
      var it = items[i], mk = it.mask || null;
      if (mk && mk.key !== last) { this.paintMask(mk); last = mk.key; }
      this.one(it, it.a, tint ? tint(it) : null, !!mk);
    }
  };
  AL.GL.prototype.forget = function () { var gl = this.gl; if (!gl) return; for (var k in this.tex) gl.deleteTexture(this.tex[k].tex); this.tex = {}; };
})(typeof self !== 'undefined' ? self : this);
