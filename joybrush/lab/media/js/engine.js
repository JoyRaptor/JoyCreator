// engine.js — the media engine's GPU side for the lab: one layer's material state, the per-frame passes,
// and display. Mirrors what GlPaintEngine will do on the phone: dabs are batched (additive, no read-back),
// then ONE apply pass per frame over the dirty rectangle, so tile-based mobile GPUs are not thrashed.

import { loadShaderSource, makeProgram, use, makeTexture, makeFbo, makeQuad } from './gl.js';
import { WET, wetUniforms } from './wet.js';
import { PASTE_LANES, PASTE_DEPTH, cellCap } from './paste.js';

const DAB_FLOATS = 20;

export class MediaEngine {
  static async create(gl, shaderRoot, width, height, origin = [0, 0], scale = 1, opts = {}) {
    const src = n => loadShaderSource(shaderRoot, n);
    const [dabV, dabF, quadV, applyF, copyF, screenV, renderF, bakeF, wdabV, wdabF, fluxF, updF] = await Promise.all([
      src('media/jb_dry_dab.vert'), src('media/jb_dry_dab.frag'), src('media/jb_media_quad.vert'),
      src('media/jb_media_apply.frag'), src('media/jb_media_copy.frag'), src('media/jb_media_screen.vert'),
      src('media/jb_media_render.frag'), src('media/jb_media_bake.frag'), src('media/jb_wet_dab.vert'),
      src('media/jb_wet_dab.frag'), src('media/jb_wet_flux.frag'), src('media/jb_wet_update.frag'),
    ]);
    const [pasteDabF, pasteBrushF] = await Promise.all([src('media/jb_paste_dab.frag'), src('media/jb_paste_brush.frag')]);
    const e = new MediaEngine();
    e.gl = gl;
    e.progs = {
      dab: makeProgram(gl, dabV, dabF, 'dry dab'),
      apply: makeProgram(gl, quadV, applyF, 'apply'),
      copy: makeProgram(gl, quadV, copyF, 'copy'),
      render: makeProgram(gl, screenV, renderF, 'render'),
      bake: makeProgram(gl, quadV, bakeF, 'bake'),
      wetDab: makeProgram(gl, wdabV, wdabF, 'wet dab'),
      flux: makeProgram(gl, quadV, fluxF, 'wet flux'),
      update: makeProgram(gl, quadV, updF, 'wet update'),
      pasteDab: makeProgram(gl, quadV, pasteDabF, 'paste dab'),
      pasteBrush: makeProgram(gl, quadV, pasteBrushF, 'paste brush'),
    };
    e.init(width, height, origin, scale, opts);
    return e;
  }

  // Every pass knows where its layer sits on the page: layerPx = (docPx - origin) * scale.
  use(prog, values) { use(prog, { u_layerOrigin: this.origin, u_layerScale: this.scale, ...values }); }
  // A rectangle in document px → this layer's px, padded (doc px) and clamped to the layer.
  layerRect(r, padDoc = 0) {
    if (!r) return null;
    const o = this.origin, k = this.scale;
    return clampRect([(r[0] - o[0]) * k, (r[1] - o[1]) * k, (r[2] - o[0]) * k, (r[3] - o[1]) * k], this.w, this.h, padDoc * k);
  }

  init(w, h, origin = [0, 0], scale = 1, opts = {}) {
    const gl = this.gl;
    this.origin = origin; this.scale = scale;
    if (!gl.getExtension('EXT_color_buffer_float')) throw new Error('needs EXT_color_buffer_float');
    gl.getExtension('EXT_float_blend');
    this.w = w; this.h = h;
    // Layer state is FULL float: watercolour moves pigment in thousands of tiny steps, and half floats stall
    // (an increment below half an ulp is lost: deposits froze at exactly 1.0 and the colour drifted,
    // 2026-10-06). Full floats are not linearly filterable everywhere, so they are NEAREST and the display
    // filters by hand. Blend targets (delta, brush input) stay half float: phones may not blend full floats.
    // A view that never runs water (the sharp vector view) uses half floats throughout: half the memory.
    // Memory: textures are made when a medium is first used (dry: delta; wet: water; paste/wet: paper bake).
    const full = opts.half ? { internal: gl.RGBA16F, type: gl.HALF_FLOAT, filter: gl.NEAREST } : { internal: gl.RGBA32F, type: gl.FLOAT, filter: gl.NEAREST };
    this.T = () => makeTexture(gl, w, h, full);
    this.H = () => makeTexture(gl, w, h);
    this.fullFormat = full;
    this.state = [0, 1].map(() => {
      const t = { p0: this.T(), p1: this.T(), paper: this.H() };
      t.fbo = makeFbo(gl, [t.p0, t.p1, t.paper]);
      return t;
    });
    this.pFbo0 = makeFbo(gl, [this.state[0].p0, this.state[0].p1]);
    this.pFbo1 = makeFbo(gl, [this.state[1].p0, this.state[1].p1]);
    this.wc = 0;
    this.wetRect = null;
    this.wetIdle = 0;
    const B = () => makeTexture(gl, PASTE_LANES, PASTE_DEPTH, { internal: gl.RGBA32F, type: gl.FLOAT, filter: gl.NEAREST });
    this.brush = [0, 1].map(() => { const t = { b0: B(), b1: B() }; t.fbo = makeFbo(gl, [t.b0, t.b1]); return t; });
    this.bc = 0;
    this.readFbo = gl.createFramebuffer();
    this.drawFbo = gl.createFramebuffer();
    this.undoStack = [];
    this.undoBudget = opts.undoBytes ?? 160e6;
    this.clearAll();

    this.quad = makeQuad(gl);
    this.quadVao = gl.createVertexArray();
    gl.bindVertexArray(this.quadVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    this.dabBuf = gl.createBuffer();
    this.dabVao = gl.createVertexArray();
    gl.bindVertexArray(this.dabVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quad);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dabBuf);
    for (let i = 0; i < 5; i++) {
      gl.enableVertexAttribArray(1 + i);
      gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, DAB_FLOATS * 4, i * 16);
      gl.vertexAttribDivisor(1 + i, 1);
    }
    gl.bindVertexArray(null);
    this.emptyVao = gl.createVertexArray();
  }

  // ---- textures made on first use ----
  ensureDry() {
    if (this.delta) return;
    this.delta = this.H();
    this.deltaFbo = makeFbo(this.gl, [this.delta]);
    this.clearFbo(this.deltaFbo);
  }
  ensureBake() {
    if (this.bakeTex) return;
    const gl = this.gl;
    this.bakeTex = this.H();
    this.fluidBake = makeTexture(gl, this.w, this.h, { internal: gl.RGBA8, format: gl.RGBA, type: gl.UNSIGNED_BYTE, filter: gl.NEAREST });
    this.waterBake = this.H();
    this.bakeFbo = makeFbo(gl, [this.bakeTex, this.fluidBake, this.waterBake]);
  }
  ensureWet() {
    if (this.wet) return;
    const gl = this.gl;
    this.ensureBake();
    this.wet = [0, 1].map(() => ({ w0: this.T(), w1: this.T(), flux: this.H() }));
    this.fluxFbo = this.wet.map(x => makeFbo(gl, [x.flux]));
    this.updFbo = this.wet.map(x => makeFbo(gl, [x.w0, x.w1, this.state[1].p0, this.state[1].p1]));
    this.wetCopyFbo = this.wet.map(x => makeFbo(gl, [x.w0, x.w1, x.flux]));
    this.in0 = this.H(); this.in1 = this.H();
    this.inFbo = makeFbo(gl, [this.in0, this.in1]);
    for (const f of [...this.wetCopyFbo, this.inFbo]) this.clearFbo(f);
    if (this.before) { this.before.w0 = this.T(); this.before.w1 = this.T(); this.clearTex(this.before.w0); this.clearTex(this.before.w1); }
  }
  get hasWet() { return !!this.wet; }

  clearFbo(f) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    gl.viewport(0, 0, this.w, this.h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
  }
  clearTex(t) { const f = makeFbo(this.gl, [t]); this.clearFbo(f); this.gl.deleteFramebuffer(f); }

  clearAll() {
    for (const s of this.state) this.clearFbo(s.fbo);
    if (this.deltaFbo) this.clearFbo(this.deltaFbo);
    if (this.wet) for (const f of [...this.wetCopyFbo, this.inFbo]) this.clearFbo(f);
    this.wetRect = null;
    this.gl.bindFramebuffer(this.gl.FRAMEBUFFER, null);
  }

  // ---- undo: only the rectangle a stroke touched is kept (the app keeps 256-px tiles the same way) ----
  // `before` is a copy of the layer as it was before the current stroke. A stroke's step stores just its
  // rectangle of `before`, then `before` catches up. Water that moved on its own since the last stroke is
  // caught up at the next stroke's start.
  blit(src, sr, dst, dr) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, this.readFbo);
    gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, src, 0);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, this.drawFbo);
    gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, dst, 0);
    gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
    gl.blitFramebuffer(sr[0], sr[1], sr[2], sr[3], dr[0], dr[1], dr[2], dr[3], gl.COLOR_BUFFER_BIT, gl.NEAREST);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, null);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
  }
  layerTextures() {
    const t = [['p0', this.state[0].p0], ['p1', this.state[0].p1], ['paper', this.state[0].paper]];
    if (this.wet) t.push(['w0', this.wet[this.wc].w0], ['w1', this.wet[this.wc].w1]);
    return t;
  }
  makeLike(name, w, h) {
    const gl = this.gl;
    const f = name === 'paper' ? { internal: gl.RGBA16F, type: gl.HALF_FLOAT, filter: gl.NEAREST } : this.fullFormat;
    return makeTexture(gl, w, h, f);
  }
  syncBefore(r) {
    if (!r) return;
    for (const [k, t] of this.layerTextures()) this.blit(t, r, this.before[k], r);
  }
  touch(r) {
    if (!r) return;
    const u = this.strokeRect;
    this.strokeRect = u ? [Math.min(u[0], r[0]), Math.min(u[1], r[1]), Math.max(u[2], r[2]), Math.max(u[3], r[3])] : [...r];
  }
  beginStroke() {
    if (!this.before) {
      this.before = {};
      for (const [k] of this.layerTextures()) this.before[k] = this.makeLike(k, this.w, this.h);
      this.syncBefore([0, 0, this.w, this.h]);
    } else if (this.wetSince) {
      this.syncBefore(this.wetSince.map(Math.round));
    }
    this.wetSince = null;
    this.strokeRect = null;
    this.wetRectAtStart = this.wetRect && [...this.wetRect];
  }
  endStroke() {
    let r = this.strokeRect;
    this.strokeRect = null;
    if (!r) return;
    r = [Math.max(0, Math.floor(r[0])), Math.max(0, Math.floor(r[1])), Math.min(this.w, Math.ceil(r[2])), Math.min(this.h, Math.ceil(r[3]))];
    const w = r[2] - r[0], h = r[3] - r[1];
    if (w <= 0 || h <= 0) return;
    const step = { rect: r, tex: {}, wetRect: this.wetRectAtStart, bytes: 0 };
    for (const [k] of this.layerTextures()) {
      step.tex[k] = this.makeLike(k, w, h);
      this.blit(this.before[k], r, step.tex[k], [0, 0, w, h]);
      step.bytes += w * h * (k === 'paper' ? 8 : 16);
    }
    this.syncBefore(r);
    this.undoStack.push(step);
    let total = this.undoStack.reduce((a, s) => a + s.bytes, 0);
    while (this.undoStack.length > 1 && total > this.undoBudget) { const old = this.undoStack.shift(); total -= old.bytes; this.freeStep(old); }
  }
  undo() {
    const step = this.undoStack.pop();
    if (!step) return false;
    const r = step.rect, w = r[2] - r[0], h = r[3] - r[1];
    for (const [k, t] of this.layerTextures()) {
      if (!step.tex[k]) continue;
      this.blit(step.tex[k], [0, 0, w, h], t, r);
      this.blit(step.tex[k], [0, 0, w, h], this.before[k], r);
      if ((k === 'w0' || k === 'w1') && this.wet) this.blit(step.tex[k], [0, 0, w, h], this.wet[1 - this.wc][k], r);
    }
    if (this.wet) {   // no water is moving in the restored region yet; it starts again from rest
      for (const x of this.wet) this.clearTexRect(x.flux, r);
      this.wetRect = this.wetRect || step.wetRect ? r : null;
      if (this.wetRect && step.wetRect) this.wetRect = [Math.min(r[0], step.wetRect[0]), Math.min(r[1], step.wetRect[1]), Math.max(r[2], step.wetRect[2]), Math.max(r[3], step.wetRect[3])];
    }
    this.freeStep(step);
    return true;
  }
  clearTexRect(t, r) {
    const gl = this.gl, f = makeFbo(gl, [t]);
    gl.enable(gl.SCISSOR_TEST);
    gl.scissor(r[0], r[1], r[2] - r[0], r[3] - r[1]);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.SCISSOR_TEST);
    gl.deleteFramebuffer(f);
  }
  freeStep(s) { for (const t of Object.values(s.tex)) this.gl.deleteTexture(t); }


  // ---- wet media ----
  bakePaper(paper) {
    this.ensureBake();
    if (this.bakedPaper === paper) return;
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.bakeFbo);
    gl.viewport(0, 0, this.w, this.h);
    gl.disable(gl.BLEND);
    this.use(this.progs.bake, { ...paper.uniforms, u_cellPx: [1, 1], u_rect: [0, 0, this.w, this.h], u_targetSize: [this.w, this.h] });
    gl.bindVertexArray(this.quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    this.bakedPaper = paper;
  }

  get wetActive() { return !!this.wetRect; }

  // ---- paste media (oil, knife) ----
  // Fill every brush cell with fresh paint: what dipping into the palette does. (A tip-to-belly gradient
  // load comes from the loading tray, not from sampling the canvas: patent guard.)
  reloadBrush(paint, brush, seed = 1, belly = null) {
    // No dip loads a brush evenly: some runs of hair come out fuller than others (fixed per dip), and the
    // belly holds more than the tip.
    const gl = this.gl, cap = cellCap(brush);
    const a = new Float32Array(PASTE_LANES * PASTE_DEPTH * 4), b = new Float32Array(PASTE_LANES * PASTE_DEPTH * 4);
    let x = (seed * 2654435761) >>> 0;
    const rnd = () => ((x = (x * 1664525 + 1013904223) >>> 0) / 4294967296);
    const lane = [];
    let walk = 0;
    for (let i = 0; i < PASTE_LANES; i++) { walk = walk * 0.6 + (rnd() - 0.5); lane.push(walk); }
    const laneTone = [], laneHue = [];
    let tw = 0;
    for (let i = 0; i < PASTE_LANES; i++) { tw = tw * 0.7 + (rnd() - 0.5); laneTone.push(tw); laneHue.push([rnd() - 0.5, rnd() - 0.5, rnd() - 0.5]); }
    for (let j = 0; j < PASTE_DEPTH; j++) {
      for (let i = 0; i < PASTE_LANES; i++) {
        const amt = cap * Math.max(0.15, 1 + 0.55 * lane[i] + 0.15 * (rnd() - 0.5)) * (0.8 + 0.4 * j / (PASTE_DEPTH - 1));
        // Loading tray. Round: the tip dipped in one colour, the belly in the other (pressing spreads the
        // belly out to the stroke's edges). Flat: double-loaded side to side, the classic two-colour stripe.
        const ss = (e0, e1, x) => { const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); };
        const w = !belly ? 0 : brush.shape === 0 ? ss(1.5, 3.5, j) : ss(PASTE_LANES * 0.35, PASTE_LANES * 0.65, i);
        // A palette load is never mixed perfectly: each run of hair carries a slightly different shade.
        const shade = 1 + 0.18 * laneTone[i];
        const K = [0, 1, 2].map(c => (paint.K[c] * (1 - w) + (belly ? belly.K[c] * w : 0)) * shade * (1 + 0.06 * laneHue[i][c]));
        const S = paint.S * (1 - w) + (belly ? belly.S * w : 0);
        const o = (j * PASTE_LANES + i) * 4;
        a[o] = K[0] * amt; a[o + 1] = K[1] * amt; a[o + 2] = K[2] * amt; a[o + 3] = amt;
        b[o] = S * amt;
      }
    }
    for (const t of this.brush) {
      gl.bindTexture(gl.TEXTURE_2D, t.b0);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, PASTE_LANES, PASTE_DEPTH, 0, gl.RGBA, gl.FLOAT, a);
      gl.bindTexture(gl.TEXTURE_2D, t.b1);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, PASTE_LANES, PASTE_DEPTH, 0, gl.RGBA, gl.FLOAT, b);
    }
  }

  // One step of a paste brush: the brush trades with the canvas under it, both sides from the same state.
  pasteStep(st, brush, paper, pxPerMm, seed) {
    const gl = this.gl;
    this.bakePaper(paper);
    const cap = cellCap(brush);
    const U = {
      u_center: [st.x, st.y], u_wDir: st.wDir, u_lDir: st.lDir, u_halfW: st.halfW, u_len: st.len, u_lenMax: st.lenMax,
      u_shape: brush.shape, u_pressure: st.pressure, u_thick: brush.thickMm, u_scrape: brush.scrape,
      u_hairDepth: brush.hairDepth, u_ridge: brush.ridge, u_rate: brush.rate, u_mix: brush.mix, u_swap: brush.swap ?? 0.6,
      u_bow: brush.bow ?? 0, u_lump: brush.lump ?? 0, u_rigid: brush.rigid ?? 0,
      u_slideMm: st.slideMm, u_cellCap: cap, u_seed: seed, u_pxPerMm: pxPerMm, u_toothMm: paper.toothMm,
      u_paperBake: this.bakeTex, u_p0: this.state[0].p0, u_p1: this.state[0].p1,
    };
    const cur = this.brush[this.bc], nxt = this.brush[1 - this.bc];
    gl.disable(gl.BLEND);
    gl.bindVertexArray(this.quadVao);
    // Brush side first (reads the canvas BEFORE this step), into the other brush buffer.
    gl.bindFramebuffer(gl.FRAMEBUFFER, nxt.fbo);
    gl.viewport(0, 0, PASTE_LANES, PASTE_DEPTH);
    this.use(this.progs.pasteBrush, { ...U, u_brush0: cur.b0, u_brush1: cur.b1, u_wick: brush.wick, u_active: 1,
      u_layerSize: [this.w, this.h], u_rect: [0, 0, PASTE_LANES, PASTE_DEPTH], u_targetSize: [PASTE_LANES, PASTE_DEPTH] });
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    // Canvas side, from the same (old) brush cells, over the footprint's rectangle.
    const r = (Math.hypot(st.halfW, st.lenMax) + 0.3) * pxPerMm;
    const rect = this.layerRect([st.x - r, st.y - r, st.x + r, st.y + r], 2);
    if (rect) {
      this.touch(rect);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.pFbo1);
      gl.viewport(0, 0, this.w, this.h);
      this.use(this.progs.pasteDab, { ...U, u_brush0: cur.b0, u_brush1: cur.b1, u_targetSize: [this.w, this.h], u_rect: rect });
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      this.copyRect({ p0: this.state[1].p0, p1: this.state[1].p1, paper: this.state[1].paper }, this.pFbo0, rect);
    }
    this.bc = 1 - this.bc;
  }

  // Debug: exact float values of every state texture at doc px (x, y).
  probe(x, y) {
    const gl = this.gl, out = {};
    const read = (name, tex) => {
      const f = makeFbo(gl, [tex]);
      const px = new Float32Array(4);
      gl.readPixels(Math.floor(x), Math.floor(y), 1, 1, gl.RGBA, gl.FLOAT, px);
      gl.deleteFramebuffer(f);
      out[name] = Array.from(px).map(v => +v.toPrecision(4));
    };
    read('p0', this.state[0].p0); read('p1', this.state[0].p1);
    if (this.wet) { read('w0', this.wet[this.wc].w0); read('w1', this.wet[this.wc].w1); }
    if (x < 0) {   // brush cells: probe(-1, j) reads lane 16 at depth j
      const f = makeFbo(gl, [this.brush[this.bc].b0]);
      const px = new Float32Array(4);
      gl.readPixels(16, y, 1, 1, gl.RGBA, gl.FLOAT, px);
      gl.deleteFramebuffer(f);
      out.brush = Array.from(px).map(v => +v.toPrecision(4));
    }
    return out;
  }

  // One frame of wet media: new dabs (if any) go into the brush-input buffers, then the water runs.
  wetFrame(batch, paper, pxPerMm, opts = {}) {
    const gl = this.gl;
    if (!this.wet && !(batch && batch.count)) return;
    this.ensureWet();
    this.bakePaper(paper);
    const w = opts.wet || WET;
    let useInput = false;
    if (batch && batch.count) {
      const r = this.layerRect(batch.dirty, 48);
      if (r) this.wetRect = this.wetRect ? [Math.min(this.wetRect[0], r[0]), Math.min(this.wetRect[1], r[1]),
        Math.max(this.wetRect[2], r[2]), Math.max(this.wetRect[3], r[3])] : r;
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.inFbo);
      gl.viewport(0, 0, this.w, this.h);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE);
      gl.blendEquation(gl.FUNC_ADD);
      this.use(this.progs.wetDab, {
        u_w0: this.wet[this.wc].w0, u_paperBake: this.bakeTex,
        u_targetSize: [this.w, this.h], u_pxPerMm: pxPerMm, u_fullMm: w.fullMm,
      });
      gl.bindBuffer(gl.ARRAY_BUFFER, this.dabBuf);
      gl.bufferData(gl.ARRAY_BUFFER, batch.dabs, gl.STREAM_DRAW);
      gl.bindVertexArray(this.dabVao);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, batch.count);
      gl.disable(gl.BLEND);
      useInput = true;
      this.wetIdle = 0;
    }
    if (!this.wetRect) return;
    const rect = this.wetRect;
    this.touch(rect);
    this.wetSince = this.wetSince ? [Math.min(this.wetSince[0], rect[0]), Math.min(this.wetSince[1], rect[1]), Math.max(this.wetSince[2], rect[2]), Math.max(this.wetSince[3], rect[3])] : [...rect];
    const U = { ...wetUniforms(paper, w), u_tilt: this.tilt || [0, 0], ...(opts.override || {}) };
    gl.disable(gl.BLEND);
    const steps = opts.substeps ?? w.substeps;
    for (let k = 0; k < steps; k++) {
      const a = this.wet[this.wc], bi = 1 - this.wc, b = this.wet[bi];
      gl.bindVertexArray(this.quadVao);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fluxFbo[bi]);
      gl.viewport(0, 0, this.w, this.h);
      this.use(this.progs.flux, { ...U, u_w0: a.w0, u_flux: a.flux, u_paperBake: this.bakeTex, u_fluidBake: this.fluidBake, u_waterBake: this.waterBake, u_rect: rect, u_targetSize: [this.w, this.h] });
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.updFbo[bi]);
      this.use(this.progs.update, {
        ...U, u_w0: a.w0, u_w1: a.w1, u_flux: b.flux, u_p0: this.state[0].p0, u_p1: this.state[0].p1,
        u_paperBake: this.bakeTex, u_waterBake: this.waterBake, u_fluidBake: this.fluidBake, u_in0: this.in0, u_in1: this.in1, u_useInput: k === 0 && useInput ? 1 : 0,
        u_rect: rect, u_targetSize: [this.w, this.h],
      });
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      this.copyRect({ p0: this.state[1].p0, p1: this.state[1].p1, paper: this.state[1].paper }, this.pFbo0, rect);
      this.wc = bi;
    }
    if (useInput) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.inFbo);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
    }
    this.wetIdle += steps * w.dt;
    if (this.wetIdle > (opts.maxWetSeconds ?? 240)) this.dryNow(paper);
  }

  // Let everything dry where it lies (the Dry button, and the end of a long idle).
  dryNow(paper) {
    if (!this.wet || !this.wetRect) return;
    this.wetFrame(null, paper, 20, { substeps: 1, override: { u_evap: 1e4 }, maxWetSeconds: 1e9 });
    const gl = this.gl;
    for (const f of this.wetCopyFbo) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, this.w, this.h); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    }
    this.wetRect = null;
  }

  copyRect(src, dstFbo, rect) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, dstFbo);
    gl.viewport(0, 0, this.w, this.h);
    gl.disable(gl.BLEND);
    this.use(this.progs.copy, { u_a: src.p0, u_b: src.p1, u_c: src.paper, u_targetSize: [this.w, this.h], u_rect: rect });
    gl.bindVertexArray(this.quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  // The two-layer paper's squeeze → tooth level table, as a 256×1 float texture (rebuilt when it changes).
  contactTexture(lut) {
    const gl = this.gl;
    if (this.lutSrc === lut) return this.lutTex;
    if (!this.lutTex) this.lutTex = makeTexture(gl, lut.a.length, 1, { internal: gl.R16F, format: gl.RED, type: gl.FLOAT, filter: gl.LINEAR });
    gl.bindTexture(gl.TEXTURE_2D, this.lutTex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R16F, lut.a.length, 1, 0, gl.RED, gl.FLOAT, lut.a);   // R16F filters everywhere
    this.lutSrc = lut;
    return this.lutTex;
  }

  // ---- one frame of a dry stroke ----
  dryFrame(batch, paper, mat, pxPerMm) {
    if (!batch.count) return;
    this.ensureDry();
    const gl = this.gl;
    const cur = this.state[0], nxt = this.state[1];
    const rect = this.layerRect(batch.dirty, pxPerMm * mat.smearMm + 2);
    if (!rect) return;
    this.touch(rect);

    gl.bindFramebuffer(gl.FRAMEBUFFER, this.deltaFbo);
    gl.viewport(0, 0, this.w, this.h);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.blendEquation(gl.FUNC_ADD);
    this.use(this.progs.dab, {
      ...paper.uniforms,
      u_crush: cur.paper,
      u_contactLut: this.contactTexture(paper.lut), u_lutMaxMm: paper.lut.maxMm,
      u_targetSize: [this.w, this.h],
      u_pxPerMm: pxPerMm,
      u_toothMm: paper.toothMm,
      u_dirStrength: mat.dirStrength,
      u_dustRate: mat.dustRate,
      u_crushStart: mat.crushStart * paper.toothMm,
      u_conform: mat.conform,
    });
    gl.bindBuffer(gl.ARRAY_BUFFER, this.dabBuf);
    gl.bufferData(gl.ARRAY_BUFFER, batch.dabs, gl.STREAM_DRAW);
    gl.bindVertexArray(this.dabVao);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, batch.count);
    gl.disable(gl.BLEND);

    gl.bindFramebuffer(gl.FRAMEBUFFER, nxt.fbo);
    const t = batch.travel;
    this.use(this.progs.apply, {
      u_p0: cur.p0, u_p1: cur.p1, u_paperState: cur.paper, u_delta: this.delta,
      u_targetSize: [this.w, this.h], u_rect: rect,
      u_capMm: mat.capMm, u_abrasion: mat.abrasion, u_pigK: mat.pigK, u_pigS: mat.pigS,
      u_crushRate: mat.crushRate, u_crushMax: mat.crushMax, u_smear: mat.smear,
      u_smearPx: [t[0] * mat.smearMm * pxPerMm * this.scale, t[1] * mat.smearMm * pxPerMm * this.scale],
    });
    gl.bindVertexArray(this.quadVao);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

    this.copyRect(nxt, cur.fbo, rect);

    gl.bindFramebuffer(gl.FRAMEBUFFER, this.deltaFbo);
    gl.enable(gl.SCISSOR_TEST);
    gl.scissor(Math.floor(rect[0]), Math.floor(rect[1]), Math.ceil(rect[2] - rect[0]) + 1, Math.ceil(rect[3] - rect[1]) + 1);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.SCISSOR_TEST);
  }

  render(view, paper, look, canvasW, canvasH) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, canvasW, canvasH);
    gl.disable(gl.BLEND);
    const cur = this.state[0];
    this.use(this.progs.render, {
      ...paper.uniforms,
      u_p0: cur.p0, u_p1: cur.p1, u_paperState: cur.paper,
      u_w0: this.wet ? this.wet[this.wc].w0 : this.emptyTex(), u_w1: this.wet ? this.wet[this.wc].w1 : this.emptyTex(),
      u_targetSize: [this.w, this.h],
      u_pan: view.pan, u_zoom: view.zoom,
      u_pxPerMm: look.pxPerMm, u_toothMm: paper.toothMm,
      u_paperColor: look.paperColor, u_lamp: look.lamp, u_relief: look.relief,
      u_sheen: look.sheen, u_capMm: look.capMm, u_mode: look.mode, u_impasto: look.impasto ?? 1,
    });
    gl.bindVertexArray(this.emptyVao);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }
}

MediaEngine.prototype.emptyTex = function () {
  if (!this._empty) this._empty = makeTexture(this.gl, 1, 1, { internal: this.gl.RGBA16F, type: this.gl.HALF_FLOAT, filter: this.gl.NEAREST });
  return this._empty;
};

function clampRect(r, w, h, pad) {
  if (!r) return null;
  const x0 = Math.max(0, Math.floor(r[0] - pad)), y0 = Math.max(0, Math.floor(r[1] - pad));
  const x1 = Math.min(w, Math.ceil(r[2] + pad)), y1 = Math.min(h, Math.ceil(r[3] + pad));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
