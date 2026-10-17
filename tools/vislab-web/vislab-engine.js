/* VisLab engine, web host (WebGL2).
 *
 * The SAME shader files and the SAME scene JSON run in the Android host
 * (app/src/main/java/com/fadcam/vislab). Keep this file and that one structurally in step:
 * same pass order, same fixed 60 Hz simulation tick, same parameter packing.
 *
 * Frame order:
 *   1. sound -> Audio uniform block + spectrum / wave / history textures
 *   2. living layers (particles, slime, reaction-diffusion) step on a fixed 1/60 s tick
 *   3. ECHO: last frame warped + faded becomes this frame's canvas (if the scene has echo)
 *   4. layers draw in order, every one with ONE, ONE_MINUS_SRC_ALPHA (premultiplied)
 *   5. bloom (dual filter, half resolution down)
 *   6. FINISH: lens, glitch, film, colour, framing, background -> the screen
 */
(function (global) {
  'use strict';

  const PRECISION = 'precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n';
  const TICK = 1 / 60;
  const NB = 128, NW = 512, NH = 64;

  function srgbToLinear(c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
  function hexToLinear(hex) {
    let h = String(hex || '#000000').replace('#', '');
    if (h.length === 3) h = h.split('').map((x) => x + x).join('');
    const n = parseInt(h.slice(0, 6), 16) || 0;
    return [srgbToLinear(((n >> 16) & 255) / 255), srgbToLinear(((n >> 8) & 255) / 255), srgbToLinear((n & 255) / 255)];
  }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  const SLOT_AXIS = { x: 0, y: 1, z: 2, w: 3 };

  /** Resolve a knob to its number this frame: base + sum(signal * amount * range). */
  function evalKnob(def, raw, signals) {
    let base, mod = null;
    if (raw === undefined || raw === null) base = def.def;
    else if (typeof raw === 'object') { base = raw.v !== undefined ? raw.v : def.def; mod = raw.mod || null; }
    else base = raw;
    base = Number(base);
    if (mod && signals) {
      const range = (def.max !== undefined && def.min !== undefined) ? (def.max - def.min) : 1;
      for (const k in mod) {
        const s = signals[k];
        if (s !== undefined) base += s * mod[k] * range;
      }
      if (def.min !== undefined) base = clamp(base, def.min - (def.max - def.min), def.max + (def.max - def.min));
    }
    return base;
  }

  class VisEngine {
    constructor(gl, assets) {
      this.gl = gl;
      this.assets = assets;              // { shaders: {file: src}, types: {...} }
      this.types = assets.types.types;
      this.programs = new Map();
      this.layerState = new Map();       // runtime state per layer object
      this.time = 0;
      this.frame = 0;
      this.scene = null;
      this.renderScale = 1;
      this.w = 0; this.h = 0;
      this.stats = { ms: 0 };
      this.errors = [];
      this._initGl();
    }

    // ------------------------------------------------------------------ GL setup
    _initGl() {
      const gl = this.gl;
      this.extFloat = gl.getExtension('EXT_color_buffer_float');
      this.extHalf = this.extFloat ? null : gl.getExtension('EXT_color_buffer_half_float');
      gl.getExtension('OES_texture_float_linear');
      this.hdrFormat = (this.extFloat || this.extHalf) ? { internal: gl.RGBA16F, type: gl.HALF_FLOAT } : { internal: gl.RGBA8, type: gl.UNSIGNED_BYTE };
      this.stateFormat = this.extFloat ? { internal: gl.RGBA32F, type: gl.FLOAT } : this.hdrFormat;
      this.vao = gl.createVertexArray();
      this.ubo = gl.createBuffer();
      this.uboData = new Float32Array(32);
      gl.bindBuffer(gl.UNIFORM_BUFFER, this.ubo);
      gl.bufferData(gl.UNIFORM_BUFFER, this.uboData.byteLength, gl.DYNAMIC_DRAW);
      gl.bindBufferBase(gl.UNIFORM_BUFFER, 0, this.ubo);
      this.texSpec = this._dataTex(NB, 1, gl.RGBA16F, gl.RGBA);
      this.texWave = this._dataTex(NW, 1, gl.RGBA16F, gl.RGBA);
      this.texHist = this._dataTex(NB, NH, gl.R16F, gl.RED);
      this.specData = new Float32Array(NB * 4);
      this.waveData = new Float32Array(NW * 4);
      this.paramBuf = new Float32Array(48);
      this.palBuf = new Float32Array(15);
    }

    _dataTex(w, h, internal, format) {
      const gl = this.gl;
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, format, gl.FLOAT, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      t._w = w; t._h = h; t._internal = internal; t._format = format;
      return t;
    }

    _shaderSrc(name) {
      const s = this.assets.shaders[name];
      if (s === undefined) throw new Error('missing shader ' + name);
      return s;
    }

    _compile(type, name) {
      const gl = this.gl;
      const src = '#version 300 es\n' + PRECISION + this._shaderSrc('common.glsl') + '\n#line 1\n' + this._shaderSrc(name);
      const sh = gl.createShader(type);
      gl.shaderSource(sh, src);
      gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        const log = gl.getShaderInfoLog(sh);
        gl.deleteShader(sh);
        throw new Error(name + ': ' + log);
      }
      return sh;
    }

    program(vert, frag) {
      const key = vert + '|' + frag;
      let p = this.programs.get(key);
      if (p) { if (p.error) throw p.error; return p; }
      try { return this._link(key, vert, frag); } catch (e) { this.programs.set(key, { error: e }); throw e; }
    }

    _link(key, vert, frag) {
      let p;
      const gl = this.gl;
      const prog = gl.createProgram();
      gl.attachShader(prog, this._compile(gl.VERTEX_SHADER, vert));
      gl.attachShader(prog, this._compile(gl.FRAGMENT_SHADER, frag));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(key + ' link: ' + gl.getProgramInfoLog(prog));
      const block = gl.getUniformBlockIndex(prog, 'Audio');
      if (block !== gl.INVALID_INDEX && block !== 0xffffffff) gl.uniformBlockBinding(prog, block, 0);
      p = { prog, loc: new Map(), name: key };
      this.programs.set(key, p);
      return p;
    }

    _u(p, name) {
      let l = p.loc.get(name);
      if (l === undefined) { l = this.gl.getUniformLocation(p.prog, name); p.loc.set(name, l); }
      return l;
    }

    /** Bind a program and the standard per-draw uniforms every shader may read. */
    _use(p, opts) {
      const gl = this.gl;
      gl.useProgram(p.prog);
      let unit = 0;
      const bindTex = (name, tex) => {
        const l = this._u(p, name);
        if (l === null) return;
        gl.activeTexture(gl.TEXTURE0 + unit);
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.uniform1i(l, unit);
        unit++;
      };
      bindTex('uSpec', this.texSpec);
      bindTex('uWave', this.texWave);
      bindTex('uHist', this.texHist);
      if (opts.tex) for (const k in opts.tex) bindTex(k, opts.tex[k]);
      let l;
      if ((l = this._u(p, 'uP')) !== null) gl.uniform4fv(l, opts.params || this.paramBuf);
      if ((l = this._u(p, 'uPal[0]')) !== null) gl.uniform3fv(l, opts.pal || this.palBuf);
      if ((l = this._u(p, 'uPx')) !== null) gl.uniform1f(l, opts.px);
      if ((l = this._u(p, 'uSeed')) !== null) gl.uniform1f(l, opts.seed || 0);
      if ((l = this._u(p, 'uAdd')) !== null) gl.uniform1f(l, opts.add === undefined ? 1 : opts.add);
      if ((l = this._u(p, 'uOpacity')) !== null) gl.uniform1f(l, opts.opacity === undefined ? 1 : opts.opacity);
      if (opts.uniforms) {
        for (const k in opts.uniforms) {
          const v = opts.uniforms[k];
          const ul = this._u(p, k);
          if (ul === null) continue;
          if (typeof v === 'number') gl.uniform1f(ul, v);
          else if (v.length === 2) gl.uniform2fv(ul, v);
          else if (v.length === 3) gl.uniform3fv(ul, v);
          else gl.uniform4fv(ul, v);
        }
      }
    }

    // ------------------------------------------------------------------ targets
    _target(w, h, fmt, attachments, filter) {
      const gl = this.gl;
      const fb = gl.createFramebuffer();
      gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
      const texs = [];
      for (let i = 0; i < (attachments || 1); i++) {
        const t = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, fmt.internal, w, h, 0, gl.RGBA, fmt.type, null);
        const f = filter === false ? gl.NEAREST : gl.LINEAR;
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, f);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, f);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0);
        texs.push(t);
      }
      if (texs.length > 1) gl.drawBuffers(texs.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
      const st = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
      if (st !== gl.FRAMEBUFFER_COMPLETE) throw new Error('framebuffer incomplete ' + st + ' ' + w + 'x' + h);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      return { fb, tex: texs[0], texs, w, h };
    }

    _free(t) {
      if (!t) return;
      const gl = this.gl;
      gl.deleteFramebuffer(t.fb);
      for (const x of t.texs) gl.deleteTexture(x);
    }

    _bindTarget(t) {
      const gl = this.gl;
      if (t) { gl.bindFramebuffer(gl.FRAMEBUFFER, t.fb); gl.viewport(0, 0, t.w, t.h); }
      else { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, this.outW, this.outH); }
    }

    _clear(t) {
      const gl = this.gl;
      this._bindTarget(t);
      gl.disable(gl.BLEND);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
    }

    _ensureTargets(w, h) {
      if (w === this.w && h === this.h && this.main) return;
      this.w = w; this.h = h;
      for (const k of ['main', 'echoA', 'echoB', 'half']) { this._free(this[k]); this[k] = null; }
      (this.bloom || []).forEach((t) => this._free(t));
      this.main = this._target(w, h, this.hdrFormat);
      this.half = this._target(Math.max(1, w >> 1), Math.max(1, h >> 1), this.hdrFormat);
      this.bloom = [];
      let bw = Math.max(1, w >> 1), bh = Math.max(1, h >> 1);
      for (let i = 0; i < 6; i++) {
        this.bloom.push(this._target(bw, bh, this.hdrFormat));
        bw = Math.max(1, bw >> 1); bh = Math.max(1, bh >> 1);
      }
      this.echoA = null; this.echoB = null;
      // Living layers sized to the frame rebuild on the next step.
      for (const st of this.layerState.values()) { if (st.resize) st.resize(); }
    }

    // ------------------------------------------------------------------ scene
    setScene(scene) {
      this.scene = scene;
      for (const st of this.layerState.values()) if (st.free) st.free();
      this.layerState = new Map();
      this.echoFresh = true;
    }

    /** Throw away all memory (particles, trails, echo) so the next frame starts clean. */
    reset() {
      for (const st of this.layerState.values()) if (st.free) st.free();
      this.layerState = new Map();
      this.echoFresh = true;
    }

    _palette(layer) {
      const stops = (layer && layer.palette) || (this.brandPalette) || (this.scene && this.scene.palette) || ['#000000', '#333333', '#777777', '#bbbbbb', '#ffffff'];
      const out = new Float32Array(15);
      for (let i = 0; i < 5; i++) {
        const c = hexToLinear(stops[Math.min(i, stops.length - 1)]);
        out[i * 3] = c[0]; out[i * 3 + 1] = c[1]; out[i * 3 + 2] = c[2];
      }
      return out;
    }

    /** Pack a knob set into uP[12]; returns {params, values} where values holds host knobs too. */
    _pack(defList, raw, signals) {
      const params = new Float32Array(48);
      const values = {};
      for (const d of defList) {
        const v = evalKnob(d, raw ? raw[d.id] : undefined, signals);
        values[d.id] = v;
        if (d.slot) {
          const idx = parseInt(d.slot, 10) * 4 + SLOT_AXIS[d.slot[d.slot.length - 1]];
          params[idx] = v;
        }
      }
      return { params, values };
    }

    // ------------------------------------------------------------------ sound
    _uploadAudio(f, dt, punch) {
      const gl = this.gl;
      const u = this.uboData;
      u.set([f.bass, f.low, f.mid, f.high], 0);
      u.set([f.air, f.level, f.voice, f.bright], 4);
      u.set([f.kick, f.snare, f.hat, f.beat], 8);
      u.set([this.time, dt, f.beatPhase, f.barPhase], 12);
      u.set([f.bassAcc, f.levelAcc, f.energy, punch], 16);
      const m = Math.min(this.w, this.h);
      u.set([this.w, this.h, this.w / m, this.h / m], 20);
      const sh = f.shocks || [];
      for (let i = 0; i < 8; i++) u[24 + i] = sh[i] !== undefined ? sh[i] : 99;
      gl.bindBuffer(gl.UNIFORM_BUFFER, this.ubo);
      gl.bufferSubData(gl.UNIFORM_BUFFER, 0, u);
    }

    _uploadTextures(f) {
      const gl = this.gl;
      const s = this.specData, w = this.waveData;
      for (let i = 0; i < NB; i++) { s[i * 4] = f.spec[i]; s[i * 4 + 1] = f.peak[i]; s[i * 4 + 2] = f.raw[i]; s[i * 4 + 3] = 0; }
      for (let i = 0; i < NW; i++) { w[i * 4] = f.waveL[i]; w[i * 4 + 1] = f.waveR[i]; w[i * 4 + 2] = 0; w[i * 4 + 3] = 0; }
      gl.bindTexture(gl.TEXTURE_2D, this.texSpec);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, NB, 1, gl.RGBA, gl.FLOAT, s);
      gl.bindTexture(gl.TEXTURE_2D, this.texWave);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, NW, 1, gl.RGBA, gl.FLOAT, w);
      gl.bindTexture(gl.TEXTURE_2D, this.texHist);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, NB, NH, gl.RED, gl.FLOAT, f.hist);
    }

    static signalsOf(f, time) {
      return {
        bass: f.bass, low: f.low, mid: f.mid, high: f.high, air: f.air, level: f.level, voice: f.voice,
        bright: f.bright, kick: f.kick, snare: f.snare, hat: f.hat, beat: f.beat,
        beatSine: 0.5 + 0.5 * Math.sin(f.beatPhase * Math.PI * 2), bar: f.barPhase, energy: f.energy,
        slow: 0.5 + 0.5 * Math.sin(time * 0.31), wave: 0.5 + 0.5 * Math.sin(time * 1.57)
      };
    }

    // ------------------------------------------------------------------ frame
    /**
     * Render one frame.
     * @param f    features from the sound analyser for this moment
     * @param dt   seconds since the last frame (0 is fine when paused)
     * @param outW/outH  size of the screen buffer to finish into
     */
    render(f, dt, outW, outH) {
      const gl = this.gl;
      const t0 = performance.now();
      if (!this.scene) return;
      this.outW = outW; this.outH = outH;
      const w = Math.max(16, Math.round(outW * this.renderScale));
      const h = Math.max(16, Math.round(outH * this.renderScale));
      this._ensureTargets(w, h);
      dt = clamp(dt, 0, 0.25);
      this.time += dt;
      this.frame++;
      const signals = VisEngine.signalsOf(f, this.time);
      this._uploadTextures(f);
      gl.bindVertexArray(this.vao);
      gl.disable(gl.DEPTH_TEST);
      gl.blendEquation(gl.FUNC_ADD);

      const scene = this.scene;
      const layers = (scene.layers || []).filter((l) => l.on !== false && this.types[l.type]);

      // 2. living layers, fixed tick
      this.simAcc = (this.simAcc || 0) + dt;
      let ticks = Math.floor(this.simAcc / TICK);
      this.simAcc -= ticks * TICK;
      ticks = Math.min(ticks, 6);
      for (let k = 0; k < ticks; k++) {
        this._uploadAudio(f, TICK, k === 0 ? f.punch : 0);
        for (const layer of layers) this._step(layer, signals, k === 0 ? f.punch : 0);
      }

      this._uploadAudio(f, dt, f.punch);
      const pxScene = 2 / Math.min(w, h);

      // 3 + 4. echo and layers
      const echoOn = scene.echo && scene.echo.on !== false;
      let canvasT;
      if (echoOn) {
        if (!this.echoA || this.echoA.w !== w || this.echoA.h !== h) {
          this._free(this.echoA); this._free(this.echoB);
          this.echoA = this._target(w, h, this.hdrFormat);
          this.echoB = this._target(w, h, this.hdrFormat);
          this.echoFresh = true;
        }
        if (this.echoFresh) { this._clear(this.echoA); this._clear(this.echoB); this.echoFresh = false; }
        const prev = this.echoA, cur = this.echoB;
        this._clear(cur);
        const e = this._pack(this.assets.types.echo.params, scene.echo.p, signals);
        this._bindTarget(cur);
        gl.disable(gl.BLEND);
        this._use(this.program('fullscreen.vert', 'echo.frag'), { params: e.params, px: pxScene, tex: { uPrev: prev.tex }, pal: this._palette(null) });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        for (const layer of layers) if (layer.echo !== false) this._draw(layer, cur, signals, pxScene);
        this.echoA = cur; this.echoB = prev;
        const above = layers.filter((l) => l.echo === false);
        if (above.length) {
          this._clear(this.main);
          this._blit(cur.tex, this.main, 1);
          for (const layer of above) this._draw(layer, this.main, signals, pxScene);
          canvasT = this.main;
        } else {
          canvasT = cur;
        }
      } else {
        this._clear(this.main);
        for (const layer of layers) this._draw(layer, this.main, signals, pxScene);
        canvasT = this.main;
      }

      // 5. bloom
      const post = this._pack(this.assets.types.post.params, scene.post && scene.post.p, signals);
      const bloomOn = post.values.bloom > 0.001;
      if (bloomOn) this._bloom(canvasT, post.values);

      // 6. finish
      this._bindTarget(null);
      gl.disable(gl.BLEND);
      const P = post.params;
      // The upsample chain SUMS every level, so a wide bloom carries ~levels x the light.
      // Average it here: the Bloom knob means the same strength at any size.
      if (bloomOn) P[1] = post.values.bloom * 1.6 / this._bloomLevels;
      const bg = scene.background ? hexToLinear(scene.background) : null;
      const transparent = this.transparent || !bg;
      P[28] = bg ? bg[0] : 0; P[29] = bg ? bg[1] : 0; P[30] = bg ? bg[2] : 0; P[31] = transparent ? 0 : 1;
      // See-through output handed to a compositor we do not control (a browser page, an
      // Android window) must be "screen" alpha: light with zero coverage gets clamped to black
      // there. Additive (0) is only for our own GL compositing over video (Studio export).
      P[32] = this.alphaMode !== undefined ? this.alphaMode : (transparent ? 1 : 0);
      if (this.forceFrame !== undefined && this.forceFrame !== null) P[24] = this.forceFrame;
      this._use(this.program('fullscreen.vert', 'composite.frag'), {
        params: P, px: pxScene, pal: this._palette(null),
        tex: { uScene: canvasT.tex, uBloom: bloomOn ? this.bloom[0].tex : this.blackTex() }
      });
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      this.stats.ms = performance.now() - t0;
    }

    blackTex() {
      if (this._black) return this._black;
      const gl = this.gl;
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 0]));
      this._black = t;
      return t;
    }

    _blit(tex, target, opacity) {
      const gl = this.gl;
      this._bindTarget(target);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      this._use(this.program('fullscreen.vert', 'copy.frag'), { tex: { uSrc: tex }, opacity, px: 0 });
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }

    _bloom(src, v) {
      const gl = this.gl;
      const levels = 3 + Math.round(clamp(v.bloomRadius, 0, 1) * 3);
      this._bloomLevels = levels;
      gl.disable(gl.BLEND);
      let from = src;
      for (let i = 0; i < levels; i++) {
        const dst = this.bloom[i];
        this._bindTarget(dst);
        this._use(this.program('fullscreen.vert', 'bloom_down.frag'), {
          tex: { uSrc: from.tex }, px: 0,
          uniforms: { uHalf: [0.5 / from.w, 0.5 / from.h], uBloomCfg: [v.bloomThreshold, 0.4, i === 0 ? 1 : 0, 0] }
        });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        from = dst;
      }
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE);
      for (let i = levels - 1; i > 0; i--) {
        const s = this.bloom[i], d = this.bloom[i - 1];
        this._bindTarget(d);
        this._use(this.program('fullscreen.vert', 'bloom_up.frag'), {
          tex: { uSrc: s.tex }, px: 0, uniforms: { uHalf: [0.5 / s.w, 0.5 / s.h], uWeight: 1.0 }
        });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      }
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    }

    // ------------------------------------------------------------------ layers
    _state(layer) {
      let st = this.layerState.get(layer);
      if (!st) { st = { seed: layer.seed !== undefined ? layer.seed : Math.floor(this.layerState.size * 7.31 + 1) }; this.layerState.set(layer, st); }
      return st;
    }

    _instances(type, values) {
      let n = 1;
      for (const x of type.instances || [1]) n *= (typeof x === 'number') ? x : Math.max(1, Math.floor(values[x] || 1));
      return n;
    }

    // One broken layer (a bad pack shader, a driver quirk) must never take the picture down:
    // it is reported once, then skipped, and every other layer keeps playing.
    _draw(layer, target, signals, pxScene) { this._guard(layer, () => this._drawLayer(layer, target, signals, pxScene)); }
    _step(layer, signals, punch) { this._guard(layer, () => this._stepLayer(layer, signals, punch)); }
    _guard(layer, fn) {
      const st = this._state(layer);
      if (st.broken) return;
      try { fn(); } catch (e) {
        st.broken = e;
        this.errors.push((layer.name || layer.type) + ': ' + (e && e.message ? e.message : e));
        if (this.onError) this.onError(layer, e);
        const gl = this.gl;
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      }
    }

    _drawLayer(layer, target, signals, pxScene) {
      const gl = this.gl;
      const type = this.types[layer.type];
      const st = this._state(layer);
      const { params, values } = this._pack(type.params, layer.p, signals);
      const opacity = evalKnob({ def: 1, min: 0, max: 1 }, layer.opacity, signals);
      if (opacity <= 0.001) return;
      const add = layer.add === undefined ? 1 : layer.add;
      const pal = this._palette(layer);
      const res = layer.res !== undefined ? layer.res : (type.res || 1);
      const draw = type.draw;
      const fullscreenLike = draw === 'fullscreen' || draw === 'image' || draw === 'slime' || draw === 'rd';
      let dst = target;
      let px = pxScene;
      if (fullscreenLike && res < 0.99) {
        dst = this.half;
        px = pxScene * 2;
        this._clear(dst);
      }
      this._bindTarget(dst);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      const common = { params, pal, px, seed: st.seed, add, opacity: dst === target ? opacity : 1 };
      if (draw === 'fullscreen') {
        this._use(this.program('fullscreen.vert', type.frag), common);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      } else if (draw === 'image') {
        const img = this._imageTex(layer, st);
        common.tex = { uImage: img.tex };
        common.uniforms = { uImageAspect: img.aspect };
        this._use(this.program('fullscreen.vert', type.frag), common);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      } else if (draw === 'instanced') {
        const n = this._instances(type, values);
        this._use(this.program(type.vert, type.frag), common);
        gl.drawArraysInstanced(gl.TRIANGLES, 0, type.verts || 6, n);
      } else if (draw === 'particles') {
        if (!st.ps) return;
        common.tex = { uState0: st.ps.cur.texs[0], uState1: st.ps.cur.texs[1] };
        this._use(this.program(type.vert, type.frag), common);
        gl.drawArraysInstanced(gl.TRIANGLES, 0, 6, st.ps.n);
      } else if (draw === 'slime') {
        if (!st.sl) return;
        common.tex = { uTrail: st.sl.trailA.tex };
        this._use(this.program('fullscreen.vert', type.frag), common);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      } else if (draw === 'rd') {
        if (!st.rd) return;
        common.tex = { uState: st.rd.a.tex };
        this._use(this.program('fullscreen.vert', type.frag), common);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      }
      if (dst !== target) this._blit(dst.tex, target, opacity);
    }

    _imageTex(layer, st) {
      const src = layer.image || this.defaultImage;
      if (st.img && st.imgSrc === src) return st.img;
      const gl = this.gl;
      if (st.img) gl.deleteTexture(st.img.tex);
      const t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, t);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
      let aspect = 1;
      if (src && (src.width || src.videoWidth)) {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
        aspect = (src.width || src.videoWidth) / (src.height || src.videoHeight);
      } else {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 0]));
      }
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      st.img = { tex: t, aspect };
      st.imgSrc = src;
      st.free = () => { gl.deleteTexture(t); };
      return st.img;
    }

    // ------------------------------------------------------------------ living layers
    _stepLayer(layer, signals, punch) {
      const type = this.types[layer.type];
      if (type.draw === 'particles') this._stepParticles(layer, type, signals, punch);
      else if (type.draw === 'slime') this._stepSlime(layer, type, signals);
      else if (type.draw === 'rd') this._stepRd(layer, type, signals, punch);
    }

    _stepParticles(layer, type, signals, punch) {
      const gl = this.gl;
      const st = this._state(layer);
      const { params, values } = this._pack(type.params, layer.p, signals);
      const want = clamp(Math.round(values.count), 256, 131072);
      if (!st.ps || st.ps.n !== want) {
        if (st.ps) { this._free(st.ps.cur); this._free(st.ps.next); }
        const W = 256, H = Math.ceil(want / W);
        const a = this._target(W, H, this.stateFormat, 2, false);
        const b = this._target(W, H, this.stateFormat, 2, false);
        // Everyone starts dead (life 2); emission brings them in.
        for (const t of [a, b]) {
          gl.bindFramebuffer(gl.FRAMEBUFFER, t.fb);
          gl.clearBufferfv(gl.COLOR, 0, [0, 0, 0, 2]);
          gl.clearBufferfv(gl.COLOR, 1, [0, 0, 0, 0]);
        }
        st.ps = { n: want, cur: a, next: b, cursor: 0, serial: 0, acc: 0 };
        st.free = () => { this._free(a); this._free(b); };
      }
      const ps = st.ps;
      const life = Math.max(0.05, values.life);
      ps.acc += values.rate * ps.n / life * TICK;
      let count = Math.floor(ps.acc);
      ps.acc -= count;
      if (punch > 0) count += Math.floor(values.burst * ps.n * punch);
      count = Math.min(count, ps.n);
      this._bindTarget(ps.next);
      gl.disable(gl.BLEND);
      this._use(this.program('fullscreen.vert', type.sim), {
        params, px: 0, seed: st.seed, pal: this.palBuf,
        tex: { uState0: ps.cur.texs[0], uState1: ps.cur.texs[1] },
        uniforms: { uEmit: [ps.cursor, count, ps.n, ps.serial % 1048576] }
      });
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      ps.cursor = (ps.cursor + count) % ps.n;
      ps.serial += count;
      const t = ps.cur; ps.cur = ps.next; ps.next = t;
    }

    _stepSlime(layer, type, signals) {
      const gl = this.gl;
      const st = this._state(layer);
      const { params, values } = this._pack(type.params, layer.p, signals);
      const count = clamp(Math.round(values.count), 1000, 300000);
      const detail = clamp(values.detail || 0.5, 0.2, 1);
      const tw = Math.max(32, Math.round(this.w * detail)), th = Math.max(32, Math.round(this.h * detail));
      let init = false;
      if (!st.sl || st.sl.count !== count) {
        if (st.sl) { this._free(st.sl.a); this._free(st.sl.b); }
        const W = 512, H = Math.ceil(count / W);
        st.sl = Object.assign(st.sl || {}, { count, a: this._target(W, H, this.stateFormat, 1, false), b: this._target(W, H, this.stateFormat, 1, false) });
        init = true;
      }
      const sl = st.sl;
      if (!sl.trailA || sl.trailA.w !== tw || sl.trailA.h !== th) {
        this._free(sl.trailA); this._free(sl.trailB);
        sl.trailA = this._target(tw, th, this.hdrFormat);
        sl.trailB = this._target(tw, th, this.hdrFormat);
        this._clear(sl.trailA); this._clear(sl.trailB);
      }
      st.free = () => { this._free(sl.a); this._free(sl.b); this._free(sl.trailA); this._free(sl.trailB); };
      sl.tick = (sl.tick || 0) + 1;
      // a) agents
      this._bindTarget(sl.b);
      gl.disable(gl.BLEND);
      this._use(this.program('fullscreen.vert', type.step), {
        params, px: 0, seed: st.seed, tex: { uState: sl.a.tex, uTrail: sl.trailA.tex },
        uniforms: { uSim: [count, init ? 1 : 0, sl.tick % 9973, 0] }
      });
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      let t = sl.a; sl.a = sl.b; sl.b = t;
      // b) deposit
      this._bindTarget(sl.trailA);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE);
      this._use(this.program(type.depositVert, type.depositFrag), { params, px: 0, seed: st.seed, tex: { uState: sl.a.tex } });
      gl.drawArrays(gl.POINTS, 0, count);
      gl.disable(gl.BLEND);
      // c) diffuse + decay
      this._bindTarget(sl.trailB);
      this._use(this.program('fullscreen.vert', type.diffuse), { params, px: 0, tex: { uTrail: sl.trailA.tex } });
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      t = sl.trailA; sl.trailA = sl.trailB; sl.trailB = t;
    }

    _stepRd(layer, type, signals, punch) {
      const gl = this.gl;
      const st = this._state(layer);
      const { params, values } = this._pack(type.params, layer.p, signals);
      const detail = clamp(values.detail || 0.4, 0.2, 1);
      const rw = Math.max(32, Math.round(this.w * detail)), rh = Math.max(32, Math.round(this.h * detail));
      let init = false;
      if (!st.rd || st.rd.a.w !== rw || st.rd.a.h !== rh) {
        if (st.rd) { this._free(st.rd.a); this._free(st.rd.b); }
        st.rd = { a: this._target(rw, rh, this.stateFormat), b: this._target(rw, rh, this.stateFormat), kicks: 0 };
        init = true;
        st.free = () => { this._free(st.rd.a); this._free(st.rd.b); };
      }
      const rd = st.rd;
      if (punch > 0) rd.kicks++;
      const iters = clamp(Math.round(values.iterations), 1, 24);
      gl.disable(gl.BLEND);
      const prog = this.program('fullscreen.vert', type.step);
      for (let i = 0; i < (init ? 1 : iters); i++) {
        this._bindTarget(rd.b);
        this._use(prog, { params, px: 0, seed: st.seed, tex: { uState: rd.a.tex }, uniforms: { uSim: [0, init ? 1 : 0, rd.kicks, 0] } });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        const t = rd.a; rd.a = rd.b; rd.b = t;
      }
    }
  }

  global.VisEngine = VisEngine;
  global.VisEngine.hexToLinear = hexToLinear;
  global.VisEngine.evalKnob = evalKnob;
})(typeof window !== 'undefined' ? window : globalThis);
