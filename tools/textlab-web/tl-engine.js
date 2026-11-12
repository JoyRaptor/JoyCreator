// TextLab look book: the engine. One frame is a fixed row of passes; a look only chooses
// which shader variant, which knobs, which sphere, which particles and how letters arrive.
//
//   compose   every letter's distance picture into the FIELD (nearest letter wins)
//   soften    optional: blur the distances (melt, round) and wobble them (liquid)
//   fire      optional: a few fixed ticks of the heat picture
//   shade     the letters, lit                           -> scene (linear, premultiplied, HDR)
//   fire draw flame and smoke over them                  -> scene
//   particles embers, glints, drops                      -> scene
//   bloom     light above the threshold, spread
//   final     over the backdrop, wet floor, film curve   -> the canvas
//
// The app's twin (Kotlin, GLES 3) runs the same passes with the same shader files.
(function () {
  const TL = window.TL;
  const EASE = {
    linear: (k) => k,
    out: (k) => 1 - Math.pow(1 - k, 3),
    inout: (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2),
    back: (k) => 1 + 2.70158 * Math.pow(k - 1, 3) + 1.70158 * Math.pow(k - 1, 2),
    bounce: (k) => {
      const n = 7.5625, d = 2.75;
      if (k < 1 / d) return n * k * k;
      if (k < 2 / d) return n * (k -= 1.5 / d) * k + 0.75;
      if (k < 2.5 / d) return n * (k -= 2.25 / d) * k + 0.9375;
      return n * (k -= 2.625 / d) * k + 0.984375;
    },
    spring: (k) => 1 - Math.exp(-6 * k) * Math.cos(11 * k),      // overshoots and settles, like jelly
  };
  const clamp01 = (x) => Math.min(1, Math.max(0, x));

  TL.Engine = class {
    constructor(canvas, assets) {
      this.canvas = canvas;
      const gl = (this.gl = canvas.getContext('webgl2', { antialias: false, alpha: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' }));
      if (!gl) throw new Error('This browser has no WebGL2.');
      this.glw = new TL.Gl(gl, assets.shaders);
      if (!this.glw.floatOk) throw new Error('This GPU cannot draw into half-float targets.');
      this.parts = assets.parts;
      this.glyphs = new TL.Glyphs(this.glw);
      this.glyphs.tubefont = assets.tubefont;
      this.text = 'TEXT'; this.stage = 1; this.tilt = [0, 0]; this.over = {};
      this.maxW = 1280; this.maxH = 720;
      this.sphere = this.glw.target(512, 512, 'half');
      this.black = this.glw.target(1, 1, 'byte');
      this.targets = {}; this.dirty = true; this.simTime = 0; this.simDebt = 0; this.last = null; this.clock0 = null;
    }

    setLook(look) { this.look = look; this.over = {}; this.dirty = true; this.replay(); }
    setText(text) { if (text !== this.text) { this.text = text; this.dirty = true; } }
    setSize(w, h) { if (w !== this.maxW || h !== this.maxH) { this.maxW = w; this.maxH = h; this.dirty = true; } }
    setKnob(name, value) { this.over[name] = value; this.sphereDirty = true; }
    replay() { this.clock0 = null; this.resetFire = true; }
    // SHATTER. How far the word has broken, 0 whole .. 1 gone: the slider, or by itself (whole, it breaks, gone,
    // then it comes back together the way it went).
    brokenNow(t) {
      const auto = this.knob('shatterAuto'), base = this.knob('shatter');
      if (auto <= 0) return base;
      const p = (t * auto * 0.11) % 1;
      return clamp01(base + (p < 0.18 ? 0 : p < 0.55 ? (p - 0.18) / 0.37 : p < 0.65 ? 1 : 1 - (p - 0.65) / 0.35));
    }
    // A letter's shards: up to eight break points in its cell (the same every time), and each shard's flight.
    // Letters break one after another along the word; in a letter, each shard lets go at its own moment, is
    // thrown outward, falls, turns, and fades as it leaves.
    shards(l) {
      const n = Math.round(Math.min(8, Math.max(3, this.knob('shatterPieces'))));
      if (l.shards && l.shards.n === n) return l.shards;
      let seed = (l.index + 1) * 7919 + n * 31 + (l.order || 0) * 101;
      const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
      for (let i = 0; i < 5; i++) rnd();
      const S = TL.SPREAD, w = l.piece.cellW, h = l.piece.cellH, seeds = [], r = [];
      for (let k = 0; k < 8; k++) { seeds.push(k < n ? [S + rnd() * Math.max(w - 2 * S, 0.01), S + rnd() * Math.max(h - 2 * S, 0.01)] : [99, 99]); r.push([rnd(), rnd(), rnd()]); }
      const flight = (k, part) => {
        const tau = clamp01((part - r[k][0] * 0.35) / 0.65), ox = seeds[k][0] - w / 2, oy = seeds[k][1] - h / 2, ol = Math.hypot(ox, oy) || 1;
        const burst = this.knob('shatterBurst') * (0.5 + r[k][2]) * (1 - (1 - tau) * (1 - tau));
        return {
          move: [(ox / ol) * burst, (oy / ol) * burst - this.knob('shatterFall') * tau * tau],
          turn: (r[k][1] - 0.5) * this.knob('shatterSpin') * 6.2832 * tau,
          fade: 1 - clamp01((tau - 0.72) / 0.28),
        };
      };
      l.shards = { n, seeds, flight, start: (l.index / Math.max(l.count - 1, 1)) * 0.55 };
      return l.shards;
    }
    // The user's own picture behind the words (stage 11). It is fitted to fill the field without stretching.
    setPhoto(source) {
      const gl = this.gl;
      if (!this.photo) this.photo = gl.createTexture();
      gl.activeTexture(gl.TEXTURE0 + 5); gl.bindTexture(gl.TEXTURE_2D, this.photo);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      this.photoAspect = source.width / source.height;
    }
    get photoFit() {
      const field = this.W / this.H, pic = this.photoAspect || field;
      return field > pic ? [1, pic / field] : [field / pic, 1];
    }
    knob(name) {
      if (name in this.over) return this.over[name];
      const p = this.look.p || {};
      if (name in p) return p[name];
      const k = this.parts.knobs[name];
      return 'c' in k ? k.c : k.v;
    }

    // knob values gathered into uniforms: { uBevel: [0.04], uExtrude: [0.3, -0.3], uFillA: [r, g, b] }
    uniformsFrom(schema, get) {
      const map = {};
      for (const name in schema) {
        const k = schema[name];
        if (!k.u) continue;
        const v = get(name, k);
        if ('c' in k) map[k.u] = TL.colour(v);
        else { (map[k.u] = map[k.u] || [])[k.i || 0] = v; }
      }
      for (const u in map) for (let i = 0; i < map[u].length; i++) if (map[u][i] === undefined) map[u][i] = 0;
      return map;
    }

    build() {
      const look = this.look, glw = this.glw;
      // a font the person brought replaces the look's own (not the neon tube letters: those are drawn, not typed)
      const lf = look.font || {};
      const pick = this.userFont ? 'user' : this.fontPick;
      const font = pick && lf.family !== 'tube' ? Object.assign({}, lf, { family: pick }, pick === 'user' ? { weight: 400, width: 100 } : {}) : lf;
      const lay = (this.lay = this.glyphs.layout(this.text, font, look.margin || [0.7, 0.7, 0.7, 0.7]));
      const em = (this.em = Math.min(this.maxW / lay.fieldW, this.maxH / lay.fieldH));
      const W = (this.W = Math.max(8, Math.round(lay.fieldW * em))), H = (this.H = Math.max(8, Math.round(lay.fieldH * em)));
      if (this.canvas.width !== W || this.canvas.height !== H) { this.canvas.width = W; this.canvas.height = H; }
      for (const k in this.targets) { const t = this.targets[k]; (Array.isArray(t) ? t : [t]).forEach((x) => glw.free(x)); }
      const T = (this.targets = {});
      T.field = glw.target(W, H, 'half', true);
      T.fa = glw.target(W, H, 'half'); T.fb = glw.target(W, H, 'half');
      T.scene = glw.target(W, H, 'half'); T.back = glw.target(W, H, 'half'); T.screen = glw.target(W, H, 'byte');
      // the fire's heat picture has a fixed fineness (640 across at most): the same flame, and the
      // same cost, whether the picture is a phone preview or a 4K still
      const hw = Math.min(W / 2, 640), hh = hw * H / W;
      T.heat = [glw.target(hw, hh, 'half'), glw.target(hw, hh, 'half')];
      this.heatPx = hw / lay.fieldW;
      T.bloom = [];
      for (let i = 1; i <= 5; i++) T.bloom.push(glw.target(W >> i, H >> i, 'half'));
      const pts = TL.emitterPoints(lay.letters);
      this.emit = glw.pointTexture(pts.w, pts.h, pts.data, this.emit);
      this.fieldU = [lay.fieldW, lay.fieldH, em, TL.SPREAD];
      this.dirty = false; this.sphereDirty = true; this.resetFire = true;
    }

    bakeSphere() {
      const s = this.look.sphere || {}, gl = this.gl;
      const ramp = (s.ramp || [[0, '#101010'], [1, '#f0f0f0']]).slice(0, 8);
      while (ramp.length < 8) ramp.push([1.0001 + ramp.length * 1e-4, ramp[ramp.length - 1][1]]);
      const lamps = (s.lamps || []).slice(0, 4);
      while (lamps.length < 4) lamps.push([0, 0, 1, 0, '#000000']);
      // softbox: [x, y, half width, half height, turn, softness, strength, colour, brighter middle]
      const boxes = (s.boxes || []).slice(0, 4);
      while (boxes.length < 4) boxes.push([0, 0, 0.1, 0.1, 0, 0.05, 0, '#000000', 0]);
      const p = this.glw.program('quad.vert', 'matcap.frag').use();
      p.set('uRamp', ramp.map((r) => r[1])).set('uRampPos', ramp.map((r) => r[0]));
      p.set('uJag', s.jag || [0.5, 0, 6, 0.08]);
      p.set('uLamp', lamps.map((l) => l.slice(0, 4))).set('uLampCol', lamps.map((l) => l[4]));
      p.set('uBox', boxes.map((b) => b.slice(0, 4))).set('uBoxB', boxes.map((b) => [b[4], b[5], b[6], b[8] || 0])).set('uBoxCol', boxes.map((b) => b[7]));
      p.set('uUneven', s.uneven || 0);
      p.set('uStrips', s.strips || [0, 6, 0.55, 0.2]).set('uRimCol', s.rim || '#000000');
      p.set('uIrid', s.irid || [0, 1.5]).set('uGain', s.gain === undefined ? 1 : s.gain);
      gl.disable(gl.BLEND);
      this.glw.pass(this.sphere);
      this.glw.mips(this.sphere);
      this.sphereDirty = false;
    }

    // Where letter i is at look-time t: how it arrives, then how it idles.
    pose(i, n, t) {
      const m = this.look.motion || {}, a = m.in || {}, idle = m.idle || {};
      const each = a.each === undefined ? 0.06 : a.each, time = a.time === undefined ? 0.4 : a.time;
      const start = (a.from === 'end' ? n - 1 - i : a.from === 'middle' ? Math.abs(i - (n - 1) / 2) : i) * each;
      const lin = time > 0 ? clamp01((t - start) / time) : 1;
      const k = (EASE[a.ease] || EASE.out)(lin), back = 1 - k;
      const rv = a.reveal || [0, time];
      const reveal = rv[1] > 0 ? clamp01((t - start - rv[0]) / rv[1]) : 1;
      const ph = t * (idle.speed === undefined ? 1 : idle.speed) * 6.2832 - i * (idle.phase === undefined ? 0.6 : idle.phase);
      const settled = clamp01((t - start - time) / 0.4);
      const sc = 1 + ((a.scale === undefined ? 1 : a.scale) - 1) * back + (idle.pulse || 0) * Math.sin(ph) * settled;
      return {
        move: [(a.x || 0) * back, (a.y || 0) * back + (idle.bob || 0) * Math.sin(ph) * settled],
        scale: [sc, sc],
        turn: (a.turn || 0) * back + (idle.sway || 0) * Math.sin(ph * 0.7 + 1.0) * settled,
        reveal,
        weight: (idle.breathe || 0) * Math.sin(ph * 0.5) * settled,
      };
    }

    frame(now) {
      const gl = this.gl, glw = this.glw, look = this.look;
      if (!look) return;
      if (this.dirty) this.build();
      if (this.sphereDirty) this.bakeSphere();
      if (this.clock0 === null) { this.clock0 = now; this.last = now; }
      const t = now - this.clock0, dt = Math.min(Math.max(now - this.last, 0), 0.1);
      this.last = now;
      const T = this.targets, W = this.W, H = this.H, lay = this.lay;
      const U = this.uniformsFrom(this.parts.knobs, (name) => this.knob(name));
      U.uTime = [t]; U.uField = this.fieldU; U.uTilt = this.tilt; U.uHasBack = [1]; U.uOut = [0];
      const neon = (look.features || []).includes('TUBE');
      U.uTubePaint = [lay.tubes ? 1 : neon ? 2 : 0];
      U.uWobble.length = 4; U.uWobble[3] = 0; U.uBloomCfg.length = 4; U.uBloomCfg.fill(0, 2);
      const defines = look.features || [];

      // backdrop
      gl.disable(gl.BLEND); gl.disable(gl.DEPTH_TEST);
      glw.program('quad.vert', 'backdrop.frag').use().setAll(U).set('uScene', this.stage)
        .set('uPhotoFit', this.photoFit || [1, 1]).tex('uPhoto', this.photo || this.black.tex, 5);
      glw.pass(T.back);
      if (defines.includes('FOG')) glw.mips(T.back);      // a steamed pane needs the world behind it soft as well as sharp

      // compose
      const gp = glw.program('glyph.vert', 'glyph.frag').use().setAll(U);
      glw.bind(T.field);
      gl.clearColor(-TL.SPREAD, 0, 0, 0); gl.clearDepth(0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.GREATER);
      const weight = this.knob('weight');
      const broken = defines.includes('SHATTER') ? this.brokenNow(t) : 0;
      for (const l of lay.letters) {
        const ps = this.pose(l.index, l.count, t);
        gp.set('uCell', [l.x, l.y, l.piece.cellW, l.piece.cellH]).set('uPivot', [l.pivotX, l.pivotY])
          .set('uMove', ps.move).set('uScale', ps.scale).set('uTurn', ps.turn).set('uLine', [l.baseline, l.cap])
          .set('uSdfScale', (ps.scale[0] + ps.scale[1]) / 2).set('uWeight', weight + ps.weight)
          .set('uLetter', [ps.reveal, (l.index + 0.5) / l.count]).set('uTubePiece', [l.piece.paint ? 1 : neon ? 2 : 0, l.order || 0])
          .set('uArc', l.arc || [0, 1]).set('uShard', -1).tex('uSdf', l.piece.tex, 0);
        // SHATTER: a breaking letter is drawn as its shards, each a rigid piece with its own flight
        const sh = broken > 0 ? this.shards(l) : null;
        const part = sh ? clamp01((broken - sh.start) / 0.45) : 0;
        if (part <= 0) { gl.drawArrays(gl.TRIANGLES, 0, 6); continue; }
        gp.set('uSeeds', sh.seeds);
        for (let k = 0; k < sh.n; k++) {
          const m = sh.flight(k, part);
          gp.set('uShard', k).set('uPivot', [l.x + sh.seeds[k][0], l.y + sh.seeds[k][1]])
            .set('uMove', [ps.move[0] + m.move[0], ps.move[1] + m.move[1]]).set('uTurn', ps.turn + m.turn)
            .set('uLetter', [ps.reveal * m.fade, (l.index + 0.5) / l.count]);
          gl.drawArrays(gl.TRIANGLES, 0, 6);
        }
      }
      gl.disable(gl.DEPTH_TEST);

      // soften
      let field = T.field;
      const soft = this.knob('soften') * this.em, wob = this.knob('wobble');
      if (soft > 0.25 || wob > 0 || this.knob('chip') > 0 || this.knob('grow') > 0 || (this.knob('drip') > 0 || this.knob('splash') > 0)) {
        const sp = glw.program('quad.vert', 'soften.frag').use().setAll(U).set('uRadius', soft);
        const shape = [wob, this.knob('wobbleScale'), this.knob('wobbleSpeed'), 1];
        if ((this.knob('drip') > 0 || this.knob('splash') > 0) && soft > 0.25) {
          // drips are new shape, so they go in first and the rounding then rounds them with the letter
          sp.set('uRadius', 0).set('uWobble', shape).set('uStep', [1 / W, 0]).tex('uSrc', T.field.tex, 0);
          glw.pass(T.fb);
          sp.set('uRadius', soft).set('uWobble', [0, 0, 0, 0]).tex('uSrc', T.fb.tex, 0);
        } else {
          sp.set('uWobble', shape).set('uStep', [1 / W, 0]).tex('uSrc', T.field.tex, 0);
        }
        glw.pass(T.fa);
        sp.set('uWobble', [0, 0, 0, 0]).set('uStep', [0, 1 / H]).tex('uSrc', T.fa.tex, 0);
        glw.pass(T.fb);
        field = T.fb;
      }

      // fire
      let heat = this.black;
      if (look.fire) {
        if (this.resetFire) {
          for (const h of T.heat) { glw.bind(h); gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT); }
          this.simTime = 0; this.simDebt = 0; this.resetFire = false;
        }
        this.simDebt = Math.min(this.simDebt + dt, 4 / 60);
        const fp = glw.program('quad.vert', 'fire_step.frag').use().setAll(U).set('uDt', 1 / 60);
        while (this.simDebt >= 1 / 60) { this.fireTick(fp, field); this.simDebt -= 1 / 60; }
        heat = T.heat[0];
      }

      // shade
      const sh = glw.program('quad.vert', 'shade.frag', defines).use().setAll(U);
      sh.tex('uFieldTex', field.tex, 0).tex('uMatcap', this.sphere.tex, 1).tex('uBack', T.back.tex, 2).tex('uHeat', heat.tex, 3);
      glw.pass(T.scene);

      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      if (look.fire) {
        glw.program('quad.vert', 'fire_draw.frag').use().setAll(U).tex('uHeat', heat.tex, 0).tex('uFieldTex', field.tex, 1);
        glw.pass(T.scene);
      }
      for (const ps of look.particles || []) {
        const PU = this.uniformsFrom(this.parts.particleKnobs, (name, k) => (ps.p && name in ps.p ? ps.p[name] : 'c' in k ? k.c : k.v));
        const pp = glw.program('particle.vert', 'particle.frag').use().setAll(U).setAll(PU);
        pp.tex('uEmit', this.emit, 0).tex('uFieldTex', field.tex, 1).tex('uMatcap', this.sphere.tex, 2);
        glw.bind(T.scene);
        gl.drawArrays(gl.TRIANGLES, 0, 6 * Math.round(ps.count || 200));
      }
      gl.disable(gl.BLEND);

      // bloom: down the chain, then back up adding
      const down = glw.program('quad.vert', 'bloom_down.frag').use();
      let src = T.scene;
      T.bloom.forEach((lvl, i) => {
        down.set('uHalf', [0.5 / src.w, 0.5 / src.h]).set('uBloomCfg', [this.knob('bloomThreshold'), this.knob('bloomKnee'), i === 0 ? 1 : 0, 0]).tex('uSrc', src.tex, 0);
        glw.pass(lvl); src = lvl;
      });
      const up = glw.program('quad.vert', 'bloom_up.frag').use();
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE);
      for (let i = T.bloom.length - 1; i > 0; i--) {
        up.set('uHalf', [0.5 / T.bloom[i].w, 0.5 / T.bloom[i].h]).tex('uSrc', T.bloom[i].tex, 0);
        glw.pass(T.bloom[i - 1]);
      }
      gl.disable(gl.BLEND);

      // final
      const fin = glw.program('quad.vert', 'final.frag').use().setAll(U);
      fin.tex('uScene', T.scene.tex, 0).tex('uBloom', T.bloom[0].tex, 1).tex('uBack', T.back.tex, 2).tex('uHeat', heat.tex, 3).tex('uBloomWide', T.bloom[3].tex, 4);
      glw.pass(T.screen);
      // smooth the steps left where two lit faces meet
      glw.program('quad.vert', 'fxaa.frag').use().set('uPx', [1 / W, 1 / H]).tex('uSrc', T.screen.tex, 0);
      glw.pass(null, W, H);
      if (this.debug) {   // show one stage of the pipeline as it is
        const pick = { heat: [heat.tex, 1, 0, 1], field: [field.tex, 4, 0.5, 1], sphere: [this.sphere.tex, 0.5, 0, 0], scene: [T.scene.tex, 0.5, 0, 0], bloom: [T.bloom[0].tex, 0.5, 0, 0] }[this.debug];
        if (pick) { glw.program('quad.vert', 'copy.frag').use().set('uShow', pick.slice(1)).tex('uSrc', pick[0], 0); glw.pass(null, W, H); }
      }
      this.lookTime = t;
    }

    fireTick(fp, field) {
      const T = this.targets;
      this.simTime += 1 / 60;
      fp.set('uTime', this.simTime).tex('uPrev', T.heat[0].tex, 0).tex('uFieldTex', field.tex, 1);
      this.glw.pass(T.heat[1]);
      T.heat.reverse();
    }

    // Draw the look as it stands `seconds` after it starts (for stills and checks): the fire is
    // run forward to that moment so the picture does not depend on how long the page was open.
    still(seconds) {
      this.replay();
      const steps = Math.min(Math.round(seconds * 60), 600);
      this.frame(1000);
      if (this.look.fire) {
        const fp = this.glw.program('quad.vert', 'fire_step.frag');
        const field = this.knob('soften') * this.em > 0.25 || this.knob('wobble') > 0 || this.knob('chip') > 0 || this.knob('grow') > 0 || (this.knob('drip') > 0 || this.knob('splash') > 0) ? this.targets.fb : this.targets.field;
        // the field must be the settled one: draw the end pose first
        this.clock0 = 1000 - seconds; this.frame(1000);
        this.gl.disable(this.gl.BLEND);
        fp.use();
        for (let i = 0; i < steps; i++) this.fireTick(fp, field);
      }
      this.clock0 = 1000 - seconds; this.last = 1000; this.simDebt = 0;
      this.frame(1000);
    }

    gpu() {
      const e = this.gl.getExtension('WEBGL_debug_renderer_info');
      return e ? this.gl.getParameter(e.UNMASKED_RENDERER_WEBGL) : 'unknown';
    }
  };
})();
