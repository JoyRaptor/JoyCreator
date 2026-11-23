/* AvatarLab: BREAK TEST, the voice kept with a take (al-voice.js, al-voice-play.js) and the video export (al-video.js, al-mp4.js,
 * al-export-video.js). Load after al-breaktest.js and al-breaktest-fit.js. Click the page once first (a browser only plays sound
 * after a touch), then:
 *
 *   await AL.breakTest.export.run()                 every check
 *   await AL.breakTest.export.proof()               feeds each main check the fault it is for; every one must be caught
 *
 * It drives time by hand (al-breaktest-fit.js K.sim): reload the page afterwards. The voice is a made-up sound fed exactly as the
 * microphone's would be (a tone at known moments), so every check knows where the sound must be. The videos are real MP4 files,
 * read back by the browser's own video player and sound decoder, as any player would.
 *
 * plant = which fault to put in:
 *   nokeep    the voice is never kept                         late     the kept voice starts 0.2 s late
 *   short     the kept voice is shorter than the take          wipe     a pass without the voice throws the voice away
 *   whole     a pass with the voice replaces all of it         noreload the stored copy loses the voice
 *   nofile    the character file loses the voice               keepclear a cleared take keeps its voice
 *   long      the video is longer than the take                shape    the video is not the frame's shape
 *   nokey     the key colour is not behind                      nosound  the video has no sound
 *   lagsound  the sound is 0.2 s behind the picture             nostop   Stop does not stop it
 *   skipoff   a ticked-off character goes in anyway             sceneone a kept scene loses a character's voice
 *   playone   Play plays only the voice of the one in hand      mixone   the scene video has only one voice
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, X = T.export = {}, V = AL.voice, Vd = AL.video, scene = AL.scene, SR = 48000;
  function realWait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  async function ownTopps(app) {
    var o = null; try { o = await fetch('local/owner-topps.character.json').then(function (r) { return r.ok ? r.json() : null; }); } catch (e) { o = null; }
    if (!o) { await AL.intake.builtIn(app, 'topps'); return 'built in'; }
    delete o.take; AL.img.map = {}; var ch = await AL.fromJson(o); app.setMode('build'); app.setCharacter(ch, { quiet: true }); return 'the owner\'s';
  }

  /* ── a made-up voice, fed to a performer exactly where the microphone's would go ── */
  function fakeSound(inp) {
    var S = { t: 1, fed: 1, base: 0, sig: function () { return 0; } };
    inp.actx = { state: 'running', sampleRate: SR, resume: function () { }, get currentTime() { return S.t; } };
    inp.an = { connect: function () { }, disconnect: function () { },
      getFloatTimeDomainData: function (a) { for (var i = 0; i < a.length; i++) a[i] = S.sig(S.t - (a.length - i) / SR - S.base); },
      getByteFrequencyData: function (a) { for (var i = 0; i < a.length; i++) a[i] = i > 4 && i < 14 ? 180 : 0; } };
    inp.td = new Float32Array(1024); inp.fd = new Uint8Array(512);
    inp._vcap = { ctx: inp.actx, sr: SR, chunks: [], node: {}, on: false, arm: function () { } };
    S.inp = inp; return S;
  }
  function unfake(inp) { inp.actx = null; inp.an = null; inp._vcap = null; inp.mic.on = false; inp.mic.kind = null; }
  function tone(f, a, t0, t1) { return function (t) { return t >= t0 && t < t1 ? a * Math.sin(2 * Math.PI * f * t) : 0; }; }
  /* one frame of the room, with the sound that came in during it */
  async function frame1(S) {
    S.t += 1 / 30; var c = S.inp._vcap;
    if (c && c.on) { var f0 = Math.round(S.fed * SR), f1 = Math.round(S.t * SR), d = new Float32Array(Math.max(0, f1 - f0)); for (var i = 0; i < d.length; i++) d[i] = S.sig((f0 + i) / SR - S.base); c.chunks.push({ f0: f0, d: d }); }
    S.fed = S.t; await K.step(1);
  }
  /** Record a pass of n frames. voiceAt(i) = is the voice on at frame i. move = drive the head too. */
  async function pass(app, S, n, voiceAt, move, sceneRec) {
    var inp = S.inp; S.base = S.t + 1 / 30;                                 // (the take's time 0 is the first frame after Record)
    if (sceneRec) scene.rec(app); else inp.record();
    for (var i = 0; i < n; i++) { inp.mic.on = !!voiceAt(i); inp.mic.kind = inp.mic.on ? 'mic' : null; if (move) { inp.pose.turnX = Math.sin(i / 7) * 0.8; inp.held.head = true; } await frame1(S); }
    inp.held.head = false; inp.mic.on = false; inp.mic.kind = null;
    if (sceneRec) scene.rec(app); else inp.stop();
    await (inp._vjob || Promise.resolve());
  }
  /* reading a kept voice */
  function rms(p, t0, t1) { var a = Math.max(0, Math.round(t0 * p.rate)), b = Math.min(p.d.length, Math.round(t1 * p.rate)), s = 0; for (var i = a; i < b; i++) s += p.d[i] * p.d[i]; return b > a ? Math.sqrt(s / (b - a)) : 0; }
  function onset(d, rate, lim) { for (var i = 0; i < d.length; i++) if (Math.abs(d[i]) > (lim || 0.1)) return i / rate; return -1; }
  /* how much of one pitch is in a stretch (Goertzel) */
  function pitch(d, rate, f, t0, t1) { var a = Math.round(t0 * rate), b = Math.min(d.length, Math.round(t1 * rate)), w = 2 * Math.PI * f / rate, c = 2 * Math.cos(w), s1 = 0, s2 = 0; for (var i = a; i < b; i++) { var s = d[i] + c * s1 - s2; s2 = s1; s1 = s; } return Math.sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / Math.max(1, b - a); }
  function voiceWith(p, fn) { var d = fn(p.d); return { rate: p.rate, n: d.length, wav: V.wav(d, p.rate) }; }

  /* ── reading a made video back, as any player would ── */
  function openVideo(blob) {
    return new Promise(function (res, rej) {
      var v = document.createElement('video'); v.muted = true; v.playsInline = true; v.preload = 'auto'; v.src = URL.createObjectURL(blob);
      var to = setTimeout(function () { rej(new Error('the video would not open')); }, 15000);
      v.onloadeddata = function () { clearTimeout(to); res(v); }; v.onerror = function () { clearTimeout(to); rej(new Error('the video would not open')); };
    });
  }
  function seek(v, t) { return new Promise(function (r) { var to = setTimeout(r, 5000); v.onseeked = function () { clearTimeout(to); r(); }; v.currentTime = t; }); }
  function grab(v) { var c = document.createElement('canvas'); c.width = v.videoWidth; c.height = v.videoHeight; var g = c.getContext('2d'); g.drawImage(v, 0, 0); return g.getImageData(0, 0, c.width, c.height); }
  function rgb(img, x, y) { var i = (Math.round(y) * img.width + Math.round(x)) * 4; return [img.data[i], img.data[i + 1], img.data[i + 2]]; }
  function hex(k) { return [parseInt(k.slice(1, 3), 16), parseInt(k.slice(3, 5), 16), parseInt(k.slice(5, 7), 16)]; }
  function near(a, b, tol) { return Math.abs(a[0] - b[0]) <= tol && Math.abs(a[1] - b[1]) <= tol && Math.abs(a[2] - b[2]) <= tol; }
  /* the share of a box (in picture pixels) that is the key colour */
  function keyShare(img, b, key) { var n = 0, k = 0, kc = hex(key); for (var y = Math.max(0, b[1]); y < Math.min(img.height, b[3]); y += 2) for (var x = Math.max(0, b[0]); x < Math.min(img.width, b[2]); x += 2) { n++; if (near(rgb(img, x, y), kc, 30)) k++; } return n ? k / n : 1; }
  function sound(blob) { return blob.arrayBuffer().then(function (buf) { var C = root.OfflineAudioContext; return new C(1, SR, SR).decodeAudioData(buf); }).then(function (b) { return { d: b.getChannelData(0), rate: b.sampleRate, secs: b.duration }; }, function () { return null; }); }
  async function make(app, o) { return Vd.make(app, o); }

  X.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R0 = K.mk(), plant = opt.plant || '', F = R0.F, keep = {};
    var room0 = JSON.stringify(AL.frame.room), shape0 = AL.frame.empty.shape;
    function wrap(obj, k, fn) { keep[k] = keep[k] || [obj, k.split('.').pop(), obj[k.split('.').pop()]]; obj[k.split('.').pop()] = fn(keep[k][2]); }
    try {
      K.sim(true);
      if (app.scene) await scene.close(app, true);
      R0.note.push('Topps: ' + await ownTopps(app)); app.setMode('perform'); await K.raf(4);
      var inp = app.inp; inp.clearTake(); AL.frame.set(app, false);
      if (plant === 'nokeep') wrap(V, 'V.endPass', function () { return function (m) { m._vr = null; return Promise.resolve(false); }; });
      var passNo = 0;
      if (/^(late|short|whole|wipe)$/.test(plant)) wrap(V, 'V.endPass', function (e0) { return function (m) { var no = ++passNo; return e0(m).then(function (ok) {
        var tk = m.take, p = tk && V.pcm(tk.voice);
        if (plant === 'wipe' && no === 2 && tk) tk.voice = null;
        if (p && plant === 'late' && no === 1) tk.voice = voiceWith(p, function (d) { var o = new Float32Array(d.length); o.set(d.subarray(0, d.length - Math.round(0.2 * p.rate)), Math.round(0.2 * p.rate)); return o; });
        if (p && plant === 'short' && no === 1) tk.voice = voiceWith(p, function (d) { return d.slice(0, Math.round(d.length * 0.9)); });
        if (p && plant === 'whole' && no === 3) tk.voice = voiceWith(p, function (d) { var o = new Float32Array(d.length); o.set(d.subarray(Math.round(0.8 * p.rate), Math.round(1.2 * p.rate)), Math.round(0.8 * p.rate)); return o; });
        return ok; }); }; });

      /* 1. a take recorded with the voice on keeps it: as long as the take, starting with it */
      var S = fakeSound(inp); S.sig = tone(440, 0.5, 0.5, 1.5);
      await pass(app, S, 60, function () { return true; }, true);
      var tk = inp.take, p1 = V.pcm(tk && tk.voice);
      F('1 the voice is kept with the take', !!(tk && tk.n && p1 && p1.d.length), tk && tk.voice && { n: tk.voice.n });
      F('1 ...as long as the take', !!p1 && Math.abs(p1.d.length / p1.rate - tk.n / tk.fps) < 0.002, p1 && { voice: p1.d.length / p1.rate, take: tk.n / tk.fps });
      var on1 = p1 ? onset(p1.d, p1.rate) : -1, mo = -1; (tk.ch.mouth || []).some(function (v, i) { if (v > 0.15) { mo = i / tk.fps; return true; } return false; });
      F('1 ...and starts with it: the sound is where it was in the take, and the mouth opens with it', Math.abs(on1 - 0.5) < 0.01 && mo >= 0 && Math.abs(mo - on1) < 0.1, { sound: on1, mouth: mo });
      F('1 a take is still stored as signals, its voice beside them (a WAV of 24 000 a second)', /^data:audio\/wav;base64,/.test(tk.voice && tk.voice.wav || '') && tk.voice.rate === V.RATE && !tk.ch.voice, tk.voice && tk.voice.rate);

      /* 2. Play plays it: the voice is heard from the take's place */
      var Pc = V.play.ctx(), heard = 0; if (Pc && Pc.state !== 'running') await Promise.race([Pc.resume().catch(function () { }), realWait(1500)]); inp.ph = 0; inp.play(); for (var f = 0; f < 6; f++) { await K.step(1); if (inp._vp && inp._vp.src) heard++; } inp.stop(); await K.step(1);
      F('2 Play plays the voice', Pc && Pc.state === 'running' ? heard >= 4 : false, Pc && Pc.state === 'running' ? heard : 'the page needs one click first: a browser only plays sound after a touch');
      F('2 ...and it stops with the take', !inp._vp || !inp._vp.src, !!inp._vp);

      /* 3. a pass without the voice leaves it alone (the head is recorded over) */
      var before = (V.pcm(inp.take.voice) || { d: new Float32Array(0) }).d.slice(); await pass(app, S, 60, function () { return false; }, true);
      var p3 = V.pcm(inp.take && inp.take.voice), diff = 0; if (p3) for (var i3 = 0; i3 < before.length; i3++) diff = Math.max(diff, Math.abs(before[i3] - (p3.d[i3] || 0)));
      F('3 a pass without the voice leaves it alone', !!p3 && p3.d.length >= before.length && diff < 1e-3, { diff: diff, n: p3 && p3.d.length });

      /* 4. a pass with the voice on for a stretch replaces that stretch and only that */
      S.sig = tone(220, 0.2, 0, 99); var undoTo = (V.pcm(inp.take.voice) || { d: new Float32Array(0) }).d.slice();
      await pass(app, S, 60, function (i) { return i >= 24 && i < 36; }, false);
      var p4 = V.pcm(inp.take.voice), inS = p4 ? rms(p4, 0.84, 1.14) : 0, outA = p4 ? rms(p4, 0.55, 0.77) : 0, outB = p4 ? rms(p4, 1.23, 1.47) : 0, outC = p4 ? rms(p4, 0.05, 0.45) : 1;
      F('4 a pass with the voice replaces its stretch', Math.abs(inS - 0.2 / Math.SQRT2) < 0.02 && p4 && pitch(p4.d, p4.rate, 220, 0.85, 1.15) > pitch(p4.d, p4.rate, 440, 0.85, 1.15) * 4, { inside: inS });
      F('4 ...and only that stretch: the voice before and after it is the old one', Math.abs(outA - 0.5 / Math.SQRT2) < 0.02 && Math.abs(outB - 0.5 / Math.SQRT2) < 0.02 && outC < 0.01, { before: outA, after: outB, silence: outC });
      inp.undoPass(); var p4u = V.pcm(inp.take && inp.take.voice), du = 0; if (p4u) for (var iu = 0; iu < undoTo.length; iu++) du = Math.max(du, Math.abs(undoTo[iu] - (p4u.d[iu] || 0)));
      F('4 Undo takes the pass back, voice and all', !!p4u && du < 1e-3, du);

      /* 5. it survives: kept in this browser (what a reload reads) and in the character file */
      var want = (V.pcm(inp.take.voice) || { d: new Float32Array(0) }).d;
      app.dirty = true; await app.autosave();
      if (plant === 'noreload') await app.idb(function (s) { return s.get('current'); }).then(function (o) { delete o.take.voice; return app.idb(function (s) { return s.put(o, 'current'); }); });
      var back = await app.idb(function (s) { return s.get('current'); }), bp = back && back.take && V.pcm(back.take.voice);
      F('5 it comes back after a reload (from what this browser keeps)', !!bp && bp.d.length === want.length && Math.abs(bp.d[Math.round(0.7 * bp.rate)] - want[Math.round(0.7 * bp.rate)]) < 1e-3, bp && bp.d.length);
      var got = null; AL.intake.saveHook = function (name, blob) { got = blob; }; try { AL.intake.saveCharacter(app); } finally { AL.intake.saveHook = null; }
      var text = got ? await got.text() : ''; if (plant === 'nofile') { var oj = JSON.parse(text); delete oj.take.voice; text = JSON.stringify(oj); }
      var keepTake = inp.take; await AL.intake.openJson(app, text); await K.raf(3); app.setMode('perform'); await K.raf(2); inp = app.inp;
      var fp = V.pcm(inp.take && inp.take.voice);
      F('5 it comes back from the character file, saved and opened again', !!fp && fp.d.length === want.length && Math.abs(onset(fp.d, fp.rate) - 0.5) < 0.01, fp && fp.d.length);
      if (!inp.take || !inp.take.voice) inp.take = keepTake;               // (so the video checks below still have a voice)

      /* 7. the video of the take alone: 9:16 at 720, over a key colour */
      AL.frame.set(app, true); AL.frame.setShape(app, '9:16'); await K.raf(12);
      var key = '#ff00ff', l = await Vd.sizes(app), sz = l.filter(function (q) { return q.s === 720; })[0];
      if (plant === 'shape') { AL.frame.setShape(app, '16:9'); sz = (await Vd.sizes(app)).filter(function (q) { return q.s === 720; })[0]; AL.frame.setShape(app, '9:16'); }
      if (plant === 'long') wrap(Vd, 'Vd.plan', function (f0) { return function (a, s) { var pl = f0(a, s); if (pl) pl.n += 15; return pl; }; });
      if (plant === 'nosound') wrap(Vd, 'Vd.plan', function (f0) { return function (a, s) { var pl = f0(a, s); if (pl) pl.voices = []; return pl; }; });
      if (plant === 'lagsound') wrap(Vd, 'Vd.mix', function (f0) { return function (vs, rate, secs) { return f0(vs, rate, secs).then(function (d) { if (!d) return d; var o = new Float32Array(d.length); o.set(d.subarray(0, d.length - Math.round(0.2 * rate)), Math.round(0.2 * rate)); return o; }); }; });
      var takeLen = inp.take.n / inp.take.fps, steps = [];
      var r7 = sz ? await make(app, { size: sz, key: plant === 'nokey' ? null : key, skip: {}, progress: function (f) { steps.push(f); } }) : null;
      F('7 a video can be made here (at 720 for an upright frame)', !!(r7 && r7.blob && r7.blob.size > 1000), { sizes: l.map(function (q) { return q.label; }), made: !!r7 });
      F('7 it shows how far it has got', steps.length > 20 && steps[steps.length - 1] === 1 && steps.every(function (v, i) { return !i || v >= steps[i - 1]; }), steps.length);
      if (r7) {
        var vid = await openVideo(r7.blob);
        F('7 the video is as long as the take', Math.abs(vid.duration - takeLen) < 0.04, { video: vid.duration, take: takeLen });
        F('7 the video is the frame\'s shape and size (720 x 1280)', vid.videoWidth === 720 && vid.videoHeight === 1280, [vid.videoWidth, vid.videoHeight]);
        await seek(vid, 0.8); var img = grab(vid);
        F('7 the key colour is in a corner, where nothing is drawn', near(rgb(img, 4, 4), hex(key), 28) && near(rgb(img, img.width - 5, img.height - 5), hex(key), 28), [rgb(img, 4, 4), rgb(img, img.width - 5, img.height - 5)]);
        F('7 ...and the character is drawn over it (the middle is not all key colour)', keyShare(img, [img.width * 0.4, img.height * 0.4, img.width * 0.6, img.height * 0.6].map(Math.round), key) < 0.6, keyShare(img, [img.width * 0.4, img.height * 0.4, img.width * 0.6, img.height * 0.6].map(Math.round), key));
        URL.revokeObjectURL(vid.src);
        var snd = await sound(r7.blob), so = snd ? onset(snd.d, snd.rate) : -1;
        F('7 the sound is there, as long as the picture', !!snd && Math.abs(snd.secs - takeLen) < 0.03 && so >= 0, snd && { secs: snd.secs });
        F('7 ...and starts with the picture: the voice is where it was in the take', Math.abs(so - 0.5) < 0.025, { sound: so, take: 0.5 });
      } else ['7 the video is as long as the take', '7 the video is the frame\'s shape and size (720 x 1280)', '7 the key colour is in a corner, where nothing is drawn', '7 the sound is there, as long as the picture', '7 ...and starts with the picture: the voice is where it was in the take'].forEach(function (s) { F(s, false, 'no video'); });
      ['Vd.plan', 'Vd.mix'].forEach(function (k) { if (keep[k]) { keep[k][0][keep[k][1]] = keep[k][2]; delete keep[k]; } });
      /* Stop */
      var st = { now: false }, n7 = 0, r7s = await make(app, { size: l[l.length - 1], key: key, skip: {}, stop: plant === 'nostop' ? {} : st, progress: function () { if (++n7 === 5) st.now = true; } });
      F('7 Stop stops it, and nothing is made', r7s === null && n7 < 10, { made: !!r7s, pictures: n7 });
      AL.frame.setShape(app, shape0); AL.frame.set(app, false); await K.raf(2);

      /* 6. a cleared take has no voice, and Play has nothing to play */
      var kv = inp.take.voice; inp.clearTake(); if (plant === 'keepclear') inp.take = { fps: 30, n: 0, ch: {}, voice: kv };
      await K.step(3);
      F('6 a cleared take has no voice', !(inp.take && inp.take.voice) && !(inp._vp && inp._vp.src), inp.take && Object.keys(inp.take));
      unfake(inp);

      /* 8, 9. a scene: each character keeps its own voice; Play plays them together; the video has every voice, and what is ticked off is left out */
      await scene.create(app, 'plain', 'Voice test'); await K.raf(6); var R = app.scene, topps = scene.cast(R)[0];
      await scene.addCast(app, { stock: 'pip', name: 'Pip' }); await K.raf(6); var pip = R.data.items.filter(function (it) { return it.name === 'Pip'; })[0];
      scene.set(app, topps.id, { x: 600, y: Math.round(scene.H * 0.9) }); scene.set(app, pip.id, { x: 1400, y: Math.round(scene.H * 0.9) }); scene.go(app, 'perform'); await K.raf(4);
      scene.hand(app, topps.id); var SA = fakeSound(app.inp); SA.sig = tone(440, 0.5, 0.3, 1.0); await pass(app, SA, 60, function () { return true; }, false, true);
      scene.hand(app, pip.id); await K.raf(2); var SB = fakeSound(app.inp); SB.sig = tone(660, 0.5, 1.2, 1.8); await pass(app, SB, 60, function () { return true; }, false, true);
      var A = R.actors[topps.id], B = R.actors[pip.id], pa = V.pcm(A.inp.take && A.inp.take.voice), pb = V.pcm(B.inp.take && B.inp.take.voice);
      F('8 in a scene each character keeps its own voice', !!pa && !!pb && Math.abs(onset(pa.d, pa.rate) - 0.3) < 0.01 && Math.abs(onset(pb.d, pb.rate) - 1.2) < 0.01 && pitch(pa.d, pa.rate, 440, 0.4, 0.9) > 0.1 && pitch(pb.d, pb.rate, 660, 1.3, 1.7) > 0.1, { a: pa && onset(pa.d, pa.rate), b: pb && onset(pb.d, pb.rate) });
      unfake(A.inp); unfake(B.inp);
      if (plant === 'playone') wrap(V.play, 'P.sync', function (s0) { return function (m, v) { return s0(m, m === app.inp ? v : null); }; });
      scene.hand(app, topps.id); await K.raf(2); scene.play(app); var both = 0; for (var f8 = 0; f8 < 12; f8++) { await K.step(1); if (A.inp._vp && A.inp._vp.src && B.inp._vp && B.inp._vp.src) both++; } scene.stop(app); await K.step(2);
      F('8 Play plays every voice together', Pc && Pc.state === 'running' ? both >= 8 : false, Pc && Pc.state === 'running' ? both : 'the page needs one click first');
      if (keep['P.sync']) { keep['P.sync'][0].sync = keep['P.sync'][2]; delete keep['P.sync']; }
      /* the scene's video: Pip ticked off, over a key colour */
      if (plant === 'skipoff') wrap(Vd, 'Vd.plan', function (f0) { return function (a) { return f0(a, {}); }; });
      if (plant === 'mixone') wrap(Vd, 'Vd.mix', function (f0) { return function (vs, rate, secs) { return f0(vs.slice(0, 1), rate, secs); }; });
      var s480 = (await Vd.sizes(app)).filter(function (q) { return q.s === 480; })[0], skip = {}; skip[pip.id] = 1;
      var r8 = await make(app, { size: s480, key: '#0047bb', skip: skip });
      if (r8) {
        var v8 = await openVideo(r8.blob); await seek(v8, 1.0); var i8 = grab(v8), k8 = v8.videoWidth / 1920;
        var bb = scene.boxOf(R, pip), bt = scene.boxOf(R, topps); function inner(b) { var cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2, hw = (b[2] - b[0]) * 0.3, hh = (b[3] - b[1]) * 0.3; return [cx - hw, cy - hh, cx + hw, cy + hh].map(function (v) { return Math.round(v * k8); }); }
        F('8 a character ticked off is not in the video (its place is all key colour)', !!bb && keyShare(i8, inner(bb), '#0047bb') > 0.97, bb && keyShare(i8, inner(bb), '#0047bb'));
        F('8 ...and the one ticked on is', !!bt && keyShare(i8, inner(bt), '#0047bb') < 0.6, bt && keyShare(i8, inner(bt), '#0047bb'));
        URL.revokeObjectURL(v8.src);
      } else F('8 a character ticked off is not in the video (its place is all key colour)', false, 'no video');
      if (keep['Vd.plan']) { Vd.plan = keep['Vd.plan'][2]; delete keep['Vd.plan']; }
      var r9 = await make(app, { size: s480, key: null, skip: {} }), s9 = r9 ? await sound(r9.blob) : null;
      F('8 the scene\'s video has every voice, each where it was recorded', !!s9 && pitch(s9.d, s9.rate, 440, 0.4, 0.9) > 0.08 && pitch(s9.d, s9.rate, 660, 1.3, 1.7) > 0.08 && pitch(s9.d, s9.rate, 660, 0.4, 0.9) < 0.02 && Math.abs(onset(s9.d, s9.rate) - 0.3) < 0.025, s9 && { a: pitch(s9.d, s9.rate, 440, 0.4, 0.9), b: pitch(s9.d, s9.rate, 660, 1.3, 1.7), onset: onset(s9.d, s9.rate) });
      if (keep['Vd.mix']) { Vd.mix = keep['Vd.mix'][2]; delete keep['Vd.mix']; }
      /* the scene kept and opened again */
      var uid = R.data.uid; await scene.close(app, true); await K.raf(3);      // (leaving a scene keeps it)
      if (plant === 'sceneone') await app.idb(function (s) { return s.get('s:' + uid); }).then(function (o) { delete o.takes[pip.id].voice; return app.idb(function (s) { return s.put(o, 's:' + uid); }); });
      await scene.openId(app, uid); await K.raf(6); R = app.scene;
      var A2 = R && R.actors[topps.id], B2 = R && R.actors[pip.id], pa2 = A2 && V.pcm(A2.inp.take && A2.inp.take.voice), pb2 = B2 && V.pcm(B2.inp.take && B2.inp.take.voice);
      F('9 each character keeps its own voice after the scene is kept and opened again', !!pa2 && !!pb2 && Math.abs(onset(pa2.d, pa2.rate) - 0.3) < 0.01 && Math.abs(onset(pb2.d, pb2.rate) - 1.2) < 0.01, { a: !!pa2, b: !!pb2 });
      /* the sheet offers it */
      AL.exporter.o.what = 'video'; AL.exporter.sheet(app); await realWait(400); AL.exporter.sheet(app); var txt = document.getElementById('sheet').innerText;
      F('9 the Export sheet offers Video, its sizes, and making it', /Video/.test(txt) && /1080p|720p|480p/.test(txt) && /Make the video/.test(txt), txt.slice(0, 160));
      app.closeSheet(); AL.exporter.o.what = 'file';
      await scene.forget(app, uid);
    } catch (e) { R0.fails.push({ label: 'the test itself stopped', info: String(e && e.stack || e).slice(0, 400) }); }
    finally {
      Object.keys(keep).forEach(function (k) { keep[k][0][keep[k][1]] = keep[k][2]; });
      try { if (app.scene) await scene.close(app, true); } catch (e) { }
      var rm = JSON.parse(room0); for (var k in rm) AL.frame.room[k] = rm[k]; AL.frame.empty.shape = shape0; AL.frame.keep();
    }
    return { checks: R0.n, fails: R0.fails, note: R0.note };
  };

  X.proof = async function () {
    var out = {}, want = { nokeep: /^1 the voice is kept/, late: /^1 \.\.\.and starts with it/, short: /^1 \.\.\.as long as the take/, wipe: /^3 a pass without/, whole: /^4 \.\.\.and only that stretch/,
      noreload: /^5 it comes back after a reload/, nofile: /^5 it comes back from the character file/, keepclear: /^6 a cleared take/, long: /^7 the video is as long/, shape: /^7 the video is the frame/,
      nokey: /^7 the key colour/, nosound: /^7 the sound is there/, lagsound: /^7 \.\.\.and starts with the picture/, nostop: /^7 Stop stops it/, skipoff: /^8 a character ticked off/,
      sceneone: /^9 each character keeps/, playone: /^8 Play plays every voice/, mixone: /^8 the scene's video has every voice/ };
    var only = arguments[0];
    for (var k in want) { if (only && only.indexOf(k) < 0) continue; var r = await X.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
