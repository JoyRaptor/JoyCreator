/* AvatarLab: HEARING THE VOICE. A take's voice (al-voice.js) is heard whenever its take moves forward: Play, a recording pass over
 * it (where the voice is not being replaced), and in a scene every character's voice together on the scene's one clock.
 *
 * The sound follows the take's own place, frame by frame: if they are ever more than a few hundredths of a second apart (a jump
 * back to the start, a scene coming round again, a slow frame), the sound is started again from the take's place.
 * Scrubbing by hand is silent. While the voice is being replaced (Mic on, or a Voice file playing, while recording), the old one is
 * not played over the new.
 *
 * Phone: Video Studio's AudioLayerSync (armForPlay / tick / parkOnPause) over its AudioClipPreviewPlayer, one per voice.
 */
(function (root) {
  'use strict';
  var AL = root.AL, V = AL.voice, app = AL.app, P = V.play = {}, SLACK = 0.09, OUT = null, ON = [];
  /** The one sound graph every take's voice is heard through. */
  P.ctx = function () { if (!OUT) { var C = root.AudioContext || root.webkitAudioContext; if (!C) return null; OUT = new C(); } return OUT; };
  var BUF = typeof WeakMap === 'function' ? new WeakMap() : null;
  function buffer(c, v) {
    var b = BUF && BUF.get(v); if (b) return b; var p = V.pcm(v); if (!p || !p.d.length) return null;
    b = c.createBuffer(1, p.d.length, p.rate); b.getChannelData(0).set(p.d); if (BUF) BUF.set(v, b); return b;
  }
  function stop(inp) { var q = inp._vp; inp._vp = null; if (q && q.src) { try { q.src.onended = null; q.src.stop(); q.src.disconnect(); } catch (e) { } } }
  /** Keep one performer's voice where its take is (v = the voice to hear, or null for silence). */
  P.sync = function (inp, v) {
    var q = inp._vp;
    if (!v) { if (q) stop(inp); return; }
    var c = P.ctx(); if (!c) return;
    if (c.state !== 'running') { if (!P._asked) { P._asked = true; c.resume().then(function () { P._asked = false; }, function () { P._asked = false; }); } return; }
    var want = inp.ph;
    if (q && q.v === v) { if (q.done && want >= q.len - 0.02) return; if (Math.abs(q.ph0 + (c.currentTime - q.t0) - want) < SLACK) return; }
    stop(inp); var b = buffer(c, v); if (!b) return;
    if (want >= b.duration - 0.02) { inp._vp = { v: v, done: true, len: b.duration, ph0: want, t0: c.currentTime }; return; }
    var s = c.createBufferSource(); s.buffer = b; s.connect(c.destination); s.start(0, Math.max(0, want));
    inp._vp = { v: v, src: s, t0: c.currentTime, ph0: want, len: b.duration };
  };
  /* who is on the stage now, and whether each one's take is moving forward */
  function cast() {
    var R = app.scene, out = [];
    if (R && !R.dive) { var run = AL.scene.running(app); Object.keys(R.actors).forEach(function (id) { var a = R.actors[id]; out.push({ inp: a.inp, go: moving(a.inp) || !!(a._follow && run && R.clock.on) }); }); }
    else if (app.inp) out.push({ inp: app.inp, go: moving(app.inp) });
    return out;
  }
  function moving(inp) { return !!((inp.playing && !inp.recording) || (inp.recording && !(inp._vr && inp._vr.open) && !inp.mic.on)); }
  P.tick = function () {
    var l = cast(), seen = [];
    l.forEach(function (e) { var tk = e.inp.take; seen.push(e.inp); P.sync(e.inp, e.go && V.has(tk) ? tk.voice : null); });
    ON.forEach(function (inp) { if (seen.indexOf(inp) < 0) stop(inp); });       // (off the stage: a scene left, a character changed)
    ON = seen.filter(function (inp) { return !!inp._vp; });
    var m = app.inp, cq = m && m._vcap;
    if (m && m.mic.on && m.actx && m.an && !(cq && cq.ctx === m.actx && (cq.node || cq.wait))) V.tap(m);      // (the voice is on: the listening part is made ready before a pass needs it)
  };
  P.silence = function () { ON.forEach(stop); ON = []; };
  app.hooks.push(function () { try { P.tick(); } catch (e) { if (!P._err) { P._err = true; console.error(e); } } });
  /* a sound graph may only start after a touch: any touch on the page lets it */
  ['pointerdown', 'keydown'].forEach(function (k) { root.addEventListener(k, function () { if (OUT && OUT.state === 'suspended') OUT.resume(); }, true); });
})(typeof self !== 'undefined' ? self : this);
