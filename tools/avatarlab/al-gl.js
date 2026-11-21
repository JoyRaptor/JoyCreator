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

  /*
   * COLOUR SETS (a chameleon, a blush, night colours). A second program, used only while a colour set is in play, so the
   * everyday program stays as small as it is. Each pixel's hue is looked up against a few "this colour becomes that colour"
   * stops; greys, blacks and whites are left alone. The change arrives as a WAVE that crosses the character with a blotchy
   * front, so it reads as skin changing and not as a filter fading in.
   *   uh[i] = from hue (0..1), hue shift, log(more colour), log(lighter)      ug[i] = width, wave threshold, softness, blotchiness
   *   ux    = wave position = dot(fragCoord, ux.xy) + ux.z, blotch size ux.w   un = 1 for a part that changes colour
   */
  var FS2 = 'precision mediump float;varying vec2 vt;uniform sampler2D us;uniform sampler2D um;uniform float ua;uniform float uk;uniform vec4 uc;uniform vec2 uq;' +
    'uniform vec4 uh[8];uniform vec4 ug[8];uniform vec4 ux;uniform float un;' +
    'vec3 hsv(vec3 c){vec4 K=vec4(0.0,-1.0/3.0,2.0/3.0,-1.0);vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));float d=q.x-min(q.w,q.y);return vec3(abs(q.z+(q.w-q.y)/(6.0*d+1.0e-5)),d/(q.x+1.0e-5),q.x);}' +
    'vec3 rgb(vec3 c){vec3 p=abs(fract(c.xxx+vec3(1.0,2.0/3.0,1.0/3.0))*6.0-3.0);return c.z*mix(vec3(1.0),clamp(p-1.0,0.0,1.0),c.y);}' +
    'void main(){vec4 c=texture2D(us,vt);' +
    'if(un>0.5&&c.a>0.01){vec3 h=hsv(clamp(c.rgb/c.a,0.0,1.0));float pos=dot(gl_FragCoord.xy,ux.xy)+ux.z;' +
    'float nz=sin(gl_FragCoord.x*ux.w)*sin(gl_FragCoord.y*ux.w*1.31+1.7)+0.5*sin((gl_FragCoord.x+gl_FragCoord.y)*ux.w*2.3);' +
    'float dh=0.0;float ls=0.0;float lv=0.0;float ws=0.0;' +
    'for(int i=0;i<8;i++){float d=abs(fract(h.x-uh[i].x+0.5)-0.5);float w=1.0-smoothstep(ug[i].x*0.5,ug[i].x,d);' +
    'float t=clamp((ug[i].y-pos-nz*ug[i].w)/ug[i].z,0.0,1.0);w*=t*t*(3.0-2.0*t);dh+=w*uh[i].y;ls+=w*uh[i].z;lv+=w*uh[i].w;ws+=w;}' +
    'float k=1.0/max(1.0,ws);float g=smoothstep(0.06,0.2,h.y)*smoothstep(0.04,0.14,h.z);' +
    'h.x=fract(h.x+dh*k*g+1.0);h.y=clamp(h.y*exp(ls*k*g),0.0,1.0);h.z=clamp(h.z*exp(lv*k*g),0.0,1.0);c.rgb=rgb(h)*c.a;}' +
    'c.rgb=mix(c.rgb,uc.rgb*c.a,uc.a);float m=1.0;if(uk>0.5){m=clamp(texture2D(um,gl_FragCoord.xy/uq).a*2.2,0.0,1.0);}gl_FragColor=c*ua*m;}';

  AL.GL = function (canvas) {
    var gl = this.gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: true, preserveDrawingBuffer: true });
    this.canvas = canvas; this.tex = {}; this.ok = !!gl;
    if (!gl) return;
    function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; }
    this.build = function (fs, extra) {
      var pr = gl.createProgram();
      gl.attachShader(pr, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(pr, sh(gl.FRAGMENT_SHADER, fs)); gl.bindAttribLocation(pr, 0, 'p'); gl.bindAttribLocation(pr, 1, 't');
      gl.linkProgram(pr); if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(pr)); gl.useProgram(pr);
      var L = {}; ['uv', 'ur', 'uq', 'ua', 'uc', 'uk', 'us', 'um'].concat(extra || []).forEach(function (k) { L[k] = gl.getUniformLocation(pr, k); });
      L.p = 0; L.t = 1; gl.uniform1i(L.us, 0); gl.uniform1i(L.um, 1);
      return { pr: pr, loc: L };
    };
    this.base = this.build(FS); this.pr = this.base.pr; this.loc = this.base.loc; this.col = null;
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
    var src = im.canvas; if (im.hi) { var mx = this._maxTex || (this._maxTex = gl.getParameter(gl.MAX_TEXTURE_SIZE)); if (im.hi.width <= mx && im.hi.height <= mx) src = im.hi; }      // finer art is what is drawn (unless this device cannot hold it)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
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
    if (L.un) gl.uniform1f(L.un, (this._tone && !this._inMask && !it.nc && !it.ghost) ? 1 : 0);
    gl.drawElements(gl.TRIANGLES, it.idx.length, gl.UNSIGNED_SHORT, 0);
  };
  /** Paint "how much shows here" for one mask: inside-shape first (or everything), then cut the cutters out. */
  AL.GL.prototype.paintMask = function (mask) {
    var gl = this.gl, m = this.maskTarget(), self = this;
    this._inMask = true;
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
    this._inMask = false;
  };
  /** One picture over the whole canvas (a mask multiplied into another). */
  AL.GL.prototype.quad = function (tex) {
    var gl = this.gl, L = this.loc, c = this.canvas, v = this._view || { s: 1, ox: 0, oy: 0 };
    gl.uniform4f(L.uv, 1, 1, 0, 0); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, c.width, 0, 0, c.height, c.width, c.height]), gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.p, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.tb); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 1, 1, 1, 0, 0, 1, 0]), gl.DYNAMIC_DRAW); gl.vertexAttribPointer(L.t, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array([0, 1, 2, 1, 3, 2]), gl.DYNAMIC_DRAW);
    gl.uniform4f(L.uc, 0, 0, 0, 0); gl.uniform1f(L.ua, 1); gl.uniform1f(L.uk, 0); if (L.un) gl.uniform1f(L.un, 0);
    gl.drawElements(gl.TRIANGLES, 6, gl.UNSIGNED_SHORT, 0);
    gl.uniform4f(L.uv, v.s, v.s, v.ox, v.oy);
  };
  /** view = { s, ox, oy }: character pixels to canvas pixels. tint(item) may return [r,g,b,amount] or null. */
  /** colour = { stops: [{ from, to, sat, val, width, lv, soft, blotch }], box: [x0, y0, x1, y1], dir } from the solver, or nothing. */
  AL.GL.prototype.draw = function (items, view, tint, colour) {
    var gl = this.gl, c = this.canvas; if (!gl) return;
    var tone = colour && colour.stops && colour.stops.length ? colour : null, P = this.base;
    if (tone) P = this.col || (this.col = this.build(FS2, ['uh', 'ug', 'ux', 'un']));
    this.pr = P.pr; this.loc = P.loc; this._tone = !!tone; this._inMask = false;
    var L = this.loc;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, c.width, c.height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.useProgram(this.pr); this._view = view;
    gl.uniform4f(L.uv, view.s, view.s, view.ox, view.oy); gl.uniform2f(L.ur, c.width, c.height); gl.uniform2f(L.uq, c.width, c.height);
    if (tone) {
      var uh = new Float32Array(32), ug = new Float32Array(32), i0;
      for (i0 = 0; i0 < 8; i0++) {
        var q = tone.stops[i0]; if (!q) { ug[i0 * 4] = 0.01; ug[i0 * 4 + 1] = -9; ug[i0 * 4 + 2] = 1; continue; }
        var dh = ((q.to - q.from) % 360 + 540) % 360 - 180, sf = Math.max(0.02, q.soft == null ? 0.22 : q.soft), bl = q.blotch == null ? 0.12 : q.blotch;
        uh[i0 * 4] = (((q.from % 360) + 360) % 360) / 360; uh[i0 * 4 + 1] = dh / 360; uh[i0 * 4 + 2] = Math.log(Math.max(0.05, q.sat == null ? 1 : q.sat)); uh[i0 * 4 + 3] = Math.log(Math.max(0.05, q.val == null ? 1 : q.val));
        ug[i0 * 4] = Math.max(4, q.width == null ? 40 : q.width) / 360; ug[i0 * 4 + 1] = q.lv * (1 + sf + 3 * bl) - 1.5 * bl; ug[i0 * 4 + 2] = sf; ug[i0 * 4 + 3] = bl;
      }
      gl.uniform4fv(L.uh, uh); gl.uniform4fv(L.ug, ug);
      var b = tone.box, cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2, R = Math.max(8, Math.sqrt((b[2] - b[0]) * (b[2] - b[0]) + (b[3] - b[1]) * (b[3] - b[1])) / 2), an = (tone.dir || 0) * Math.PI / 180, ca = Math.cos(an) / (2 * R), sa = Math.sin(an) / (2 * R);
      gl.uniform4f(L.ux, ca / view.s, -sa / view.s, 0.5 + (-view.ox / view.s - cx) * ca + ((c.height - view.oy) / view.s - cy) * sa, 6.2832 / Math.max(6, R * 0.5 * view.s));
    }
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
