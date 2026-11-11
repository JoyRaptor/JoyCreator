// TextLab look book: the small WebGL2 layer. The app's twin does the same four jobs:
// assemble a shader by the one contract, set uniforms by name, make render targets, draw.
(function () {
  const TL = (window.TL = window.TL || {});

  // THE ASSEMBLY CONTRACT (see shaders/common.glsl). Change it here and in the app together.
  TL.PRECISION = 'precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n';
  TL.assemble = (sources, name, defines) => {
    if (!(name in sources)) throw new Error('missing shader ' + name);
    const d = (defines || []).map((x) => '#define ' + x + '\n').join('');
    return '#version 300 es\n' + TL.PRECISION + d + sources['common.glsl'] + '\n#line 1\n' + sources[name];
  };

  // "#rrggbb" or "#rrggbb*2.5" (a gain, for light brighter than white) to linear rgb.
  TL.colour = (s) => {
    const [hex, gain] = String(s).split('*');
    const n = parseInt(hex.slice(1), 16);
    const lin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const g = gain === undefined ? 1 : Number(gain);
    return [lin((n >> 16) & 255) * g, lin((n >> 8) & 255) * g, lin(n & 255) * g];
  };
  const flat = (v) => {
    if (typeof v === 'string') return TL.colour(v);
    if (typeof v === 'number') return [v];
    const out = [];
    for (const x of v) for (const y of flat(x)) out.push(y);
    return out;
  };

  class Program {
    constructor(gl, vs, fs, label) {
      this.gl = gl; this.label = label;
      const make = (type, src) => {
        const s = gl.createShader(type);
        gl.shaderSource(s, src); gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(label + ': ' + gl.getShaderInfoLog(s));
        return s;
      };
      const p = (this.p = gl.createProgram());
      gl.attachShader(p, make(gl.VERTEX_SHADER, vs)); gl.attachShader(p, make(gl.FRAGMENT_SHADER, fs));
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(label + ': ' + gl.getProgramInfoLog(p));
      this.u = {};
      const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
      for (let i = 0; i < n; i++) {
        const a = gl.getActiveUniform(p, i);
        const name = a.name.replace(/\[0\]$/, '');
        this.u[name] = { loc: gl.getUniformLocation(p, a.name), type: a.type, size: a.size };
      }
    }
    use() {
      const gl = this.gl;
      gl.useProgram(this.p);
      if (this.u.uNoise && Program.noise) { gl.activeTexture(gl.TEXTURE7); gl.bindTexture(gl.TEXTURE_2D, Program.noise); gl.uniform1i(this.u.uNoise.loc, 7); }
      return this;
    }
    has(name) { return name in this.u; }
    set(name, value) {
      const u = this.u[name]; if (!u) return this;
      const gl = this.gl, v = flat(value);
      const need = { [gl.FLOAT]: 1, [gl.FLOAT_VEC2]: 2, [gl.FLOAT_VEC3]: 3, [gl.FLOAT_VEC4]: 4 }[u.type];
      if (need && v.length !== need * u.size) { console.warn(this.label + ': ' + name + ' wants ' + need * u.size + ' numbers, got ' + v.length); return this; }
      switch (u.type) {
        case gl.FLOAT: gl.uniform1fv(u.loc, v); break;
        case gl.FLOAT_VEC2: gl.uniform2fv(u.loc, v); break;
        case gl.FLOAT_VEC3: gl.uniform3fv(u.loc, v); break;
        case gl.FLOAT_VEC4: gl.uniform4fv(u.loc, v); break;
        default: gl.uniform1i(u.loc, v[0]);
      }
      return this;
    }
    setAll(map) { for (const k in this.u) if (k in map) this.set(k, map[k]); return this; }
    tex(name, texture, unit) {
      const u = this.u[name]; if (!u) return this;
      const gl = this.gl;
      gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, texture); gl.uniform1i(u.loc, unit);
      return this;
    }
  }

  TL.Gl = class {
    constructor(gl, sources) {
      this.gl = gl; this.src = sources; this.programs = new Map();
      this.floatOk = !!gl.getExtension('EXT_color_buffer_float') || !!gl.getExtension('EXT_color_buffer_half_float');
      gl.getExtension('OES_texture_float_linear');
      Program.noise = this.noiseTexture();
      this.vao = gl.createVertexArray();
      gl.bindVertexArray(this.vao);
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    }
    program(vert, frag, defines) {
      const key = vert + '|' + frag + '|' + (defines || []).join(',');
      let p = this.programs.get(key);
      if (!p) {
        p = new Program(this.gl, TL.assemble(this.src, vert, defines), TL.assemble(this.src, frag, defines), frag + ' [' + (defines || []).join(' ') + ']');
        this.programs.set(key, p);
      }
      return p;
    }
    // A render target. kind: 'half' (RGBA16F, the usual) or 'byte'. depth: a depth buffer too.
    target(w, h, kind, depth) {
      const gl = this.gl;
      w = Math.max(1, Math.round(w)); h = Math.max(1, Math.round(h));
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      if (kind === 'byte') gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, w, h, 0, gl.RGBA, gl.HALF_FLOAT, null);
      this.params(gl.LINEAR);
      const fbo = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
      let rb = null;
      if (depth) {
        rb = gl.createRenderbuffer();
        gl.bindRenderbuffer(gl.RENDERBUFFER, rb);
        gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH_COMPONENT24, w, h);
        gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.RENDERBUFFER, rb);
      }
      const ok = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      if (!ok) throw new Error('this GPU cannot draw into a ' + kind + ' target');
      return { fbo, tex, rb, w, h };
    }
    // Make the smaller, blurrier copies of a target's picture (a satin surface reads those).
    mips(t) {
      const gl = this.gl;
      gl.bindTexture(gl.TEXTURE_2D, t.tex);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    }
    free(t) {
      if (!t) return;
      const gl = this.gl;
      gl.deleteFramebuffer(t.fbo); gl.deleteTexture(t.tex); if (t.rb) gl.deleteRenderbuffer(t.rb);
    }
    params(filter) {
      const gl = this.gl;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    }
    // One channel of floats (a letter's distance picture). Rows run bottom to top.
    floatTexture(w, h, data) {
      const gl = this.gl, tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.R16F, w, h, 0, gl.RED, gl.FLOAT, data);
      this.params(gl.LINEAR);
      return tex;
    }
    // The engine's dice: 256 x 256 random bytes, repeating, from a fixed recipe (xorshift32 from
    // 0x9E3779B9, the top byte of each step) so every device rolls the same picture.
    noiseTexture() {
      const gl = this.gl, n = 256, data = new Uint8Array(n * n);
      let x = 0x9e3779b9 | 0;
      for (let i = 0; i < n * n; i++) { x ^= x << 13; x ^= x >>> 17; x ^= x << 5; data[i] = x >>> 24; }
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, n, n, 0, gl.RED, gl.UNSIGNED_BYTE, data);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
      return tex;
    }
    // Two channels of floats (a tube's distance and its paint). Rows run bottom to top.
    pairTexture(w, h, data) {
      const gl = this.gl, tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG16F, w, h, 0, gl.RG, gl.FLOAT, data);
      this.params(gl.LINEAR);
      return tex;
    }
    // Four exact floats per texel, never filtered (the emitter's point list).
    pointTexture(w, h, data, old) {
      const gl = this.gl, tex = old || gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, w, h, 0, gl.RGBA, gl.FLOAT, data);
      this.params(gl.NEAREST);
      return tex;
    }
    bind(t, w, h) {
      const gl = this.gl;
      gl.bindFramebuffer(gl.FRAMEBUFFER, t ? t.fbo : null);
      gl.viewport(0, 0, t ? t.w : w, t ? t.h : h);
    }
    // Run a fullscreen pass into `t` (null = the canvas).
    pass(t, w, h) { this.bind(t, w, h); this.gl.drawArrays(this.gl.TRIANGLES, 0, 3); }
  };
})();
