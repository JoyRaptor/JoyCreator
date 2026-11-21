/* AvatarLab: BREAK TEST, the scene room (al-scene*.js, al-agent.js). Load after al-breaktest.js and al-breaktest-fit.js.
 *
 *   await AL.breakTest.scene.run()                 every check
 *   await AL.breakTest.scene.proof()               feeds each main check the fault it is for; every one must be caught
 *
 * Rule of the room: a check only counts once it has been seen to FAIL on the fault. plant = which fault to put in:
 *   order   the thing that should be at the back is put at the front        shape   an item is moved while the frame changes shape
 *   passes  the first character's take is thrown away before the second pass camera  the camera lane is emptied before playback
 *   clamp   the camera is let off the edge of the stage                      trip    the takes are dropped from the saved scene
 *   undo    the scene is changed behind Undo's back                          hand    the other character is moved by hand as well
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, Sc = T.scene = {}, scene = AL.scene, W = scene.W, H = scene.H;
  function tree() { var c = document.createElement('canvas'); c.width = 200; c.height = 320; var g = c.getContext('2d'); g.fillStyle = '#6b4a2b'; g.fillRect(88, 180, 24, 140); g.fillStyle = '#2f8f46'; g.beginPath(); g.arc(100, 110, 95, 0, 7); g.fill(); return c; }
  function boxes(R) { var o = {}; R.data.items.forEach(function (it) { var b = scene.boxOf(R, it); o[it.id] = b ? Array.prototype.map.call(b, function (v) { return Math.round(v * 10) / 10; }) : null; }); return o; }
  function bad(R) { var n = 0; Object.keys(R.actors).forEach(function (id) { var a = R.actors[id]; if (!a.out) return; a.out.items.forEach(function (it) { for (var i = 0; i < it.v.length; i++) if (!isFinite(it.v[i])) n++; }); }); ['x', 'y', 'z'].forEach(function (k) { if (!isFinite(R.cam[k])) n++; }); return n; }
  function clean(d) { return JSON.stringify(d, function (k, v) { return k.charAt(0) === '_' ? undefined : v; }); }
  function byName(R, n) { return R.data.items.filter(function (it) { return it.name === n; })[0]; }

  Sc.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R0 = K.mk(), plant = opt.plant || '', F = R0.F, keepSolo = app.scene ? null : app.ch;
    try {
      if (app.scene) await scene.close(app, true);
      await AL.intake.builtIn(app, 'topps'); await K.raf(6);
      var soloCh = app.ch, soloInp = app.inp;

      /* 1. a new scene: the set, and the character you were on, standing in it */
      await scene.create(app, 'meadow', 'Break test'); await K.raf(6);
      var R = app.scene; F('1 a scene opens with its set and the character in it', !!R && R.data.items.length === 2 && scene.cast(R).length === 1, R && R.data.items.map(function (i) { return i.kind; }));
      F('1 the frame is on the glass, inside the stage area', R.fr && R.fr.w > 50 && R.fr.h > 30 && R.fr.x >= -1 && R.fr.y >= 0, R.fr);
      F('1 nothing thrown in the frame loop, no bad numbers', !scene._err && bad(R) === 0, { err: !!scene._err, bad: bad(R) });
      var topps = scene.cast(R)[0];

      /* 2. depth: up the floor = further back = smaller, and drawn before what stands in front */
      var tr = scene.addPictures(app, 'obj', [tree()], 'Tree'); scene.set(app, tr.id, { x: 500, y: Math.round(H * 0.7) }); scene.set(app, topps.id, { x: 600, y: Math.round(H * 0.9) }); await K.raf(4);
      var hFront = scene.boxOf(R, topps); hFront = hFront[3] - hFront[1];
      scene.set(app, topps.id, { y: Math.round(H * R.data.floor.far) }); await K.raf(4); var hBack = scene.boxOf(R, topps); hBack = hBack[3] - hBack[1];
      F('2 at the back of the floor a character is drawn smaller (about the floor\'s shrink)', hBack < hFront * 0.75 && hBack > hFront * 0.3, { front: Math.round(hFront), back: Math.round(hBack) });
      scene.set(app, topps.id, { y: Math.round(H * 0.9) }); if (plant === 'order') scene.set(app, tr.id, { y: Math.round(H * 0.97) }); await K.raf(3);
      var log = [], G = app.G, draw0 = G.draw; G.draw = function (items) { if (items.length) log.push(items[0].img && !items[0].id ? 'q:' + items[0].img : 'c:' + items[0].id); return draw0.apply(G, arguments); };
      await K.raf(2); G.draw = draw0;
      var iTree = log.indexOf('q:' + tr.img), iCast = -1; log.forEach(function (s, i) { if (s.slice(0, 2) === 'c:' && iCast < 0) iCast = i; });
      F('2 the tree behind is drawn before the character in front', iTree >= 0 && iCast >= 0 && iTree < iCast, { tree: iTree, cast: iCast, n: log.length });
      F('2 the backdrop is drawn first', log[0] === 'q:' + R.data.items.filter(function (q) { return q.kind === 'back'; })[0].img, log[0]);

      /* 3. the frame's shape is a window: changing it moves nothing */
      var b0 = clean(boxes(R)), moved = [];
      for (var si = 0; si < scene.SHAPES.length; si++) { scene.shape(app, scene.SHAPES[si][0]); if (plant === 'shape' && si === 2) byName(R, 'Tree').x += 40; await K.raf(3); if (clean(boxes(R)) !== b0) moved.push(scene.SHAPES[si][0]); var fr = scene.frame(R.data); F('3 ' + scene.SHAPES[si][0] + ': the frame is that shape and inside the stage', Math.abs(fr.w / fr.h - scene.SHAPES[si][1]) < 0.01 && fr.x >= 0 && fr.x + fr.w <= W + 0.01, fr); }
      F('3 changing the frame\'s shape moves nothing on the stage', moved.length === 0, moved);
      scene.shape(app, '16:9'); await K.raf(2);

      /* 4. a second character; Arrange and Perform, back and forth */
      await scene.addCast(app, { stock: 'pip', name: 'Pip' }); await K.raf(6);
      var pip = byName(R, 'Pip'); F('4 a second character comes in with its own engine', !!R.actors[pip.id] && R.actors[pip.id].inp !== R.actors[topps.id].inp && R.actors[pip.id].solver !== R.actors[topps.id].solver);
      scene.set(app, pip.id, { x: 1300, y: Math.round(H * 0.9) }); scene.set(app, topps.id, { x: 620 });
      var errs = 0; for (var g = 0; g < 8; g++) { try { scene.go(app, g % 2 ? 'arrange' : 'perform'); await K.raf(3); if (bad(R)) errs++; if ((R.stage === 'perform') !== (app.mode === 'perform')) errs++; } catch (e) { errs++; } }
      F('4 Arrange and Perform eight times: nothing thrown, the room and the scene agree', errs === 0 && !scene._err, errs);
      scene.go(app, 'perform'); await K.raf(4);

      /* 5. in hand: fingers move that one and only that one */
      scene.hand(app, topps.id); await K.raf(20);
      F('5 the one in hand is the room\'s character (its engine, its fingers)', app.ch === R.actors[topps.id].ch && app.inp === R.actors[topps.id].inp && app.out === R.actors[topps.id].out);
      var pipS0 = R.actors[pip.id].inp.pose.turnX; app.inp.pose.turnX = 0.9; app.inp.held.head = true; if (plant === 'hand') R.actors[pip.id].inp.pose.turnX = 0.9; await K.raf(20);
      F('5 turning the head of the one in hand turns it', Math.abs(app.inp.S.turnX) > 0.5, app.inp.S.turnX);
      F('5 ...and nobody else was moved by it', R.actors[pip.id].inp.pose.turnX === pipS0, R.actors[pip.id].inp.pose.turnX);
      app.inp.pose.turnX = 0; app.inp.held.head = false; await K.raf(10);

      /* 6. passes: one records, the other plays back in step */
      var A = R.actors[topps.id], Bq = R.actors[pip.id];
      scene.rec(app); for (var f1 = 0; f1 < 60; f1++) { app.inp.pose.turnX = Math.sin(f1 / 9) * 0.9; app.inp.held.head = true; await K.raf(1); } scene.rec(app); app.inp.held.head = false; await K.raf(3);
      F('6 the first pass is kept as that character\'s take', !!(A.inp.take && A.inp.take.n >= 50 && A.inp.take.ch.turnX), A.inp.take && A.inp.take.n);
      scene.hand(app, pip.id); await K.raf(3); if (plant === 'passes') A.inp.take = null;
      scene.rec(app); var off = 0, seen = 0;
      for (var f2 = 0; f2 < 45; f2++) { app.inp.pose.turnY = Math.sin(f2 / 7) * 0.6; app.inp.held.head = true; await K.raf(1); var tk = A.inp.take, i2 = tk ? Math.min(Math.floor(A.inp.ph * tk.fps), tk.ch.turnX.length - 1) : -1; if (tk && A._follow) { seen++; if (Math.abs(A.inp.S.turnX - tk.ch.turnX[i2]) > 0.02) off++; } }
      scene.rec(app); app.inp.held.head = false; await K.raf(3);
      F('6 while the second records, the first plays its take back in step', seen >= 40 && off === 0, { seen: seen, off: off });
      F('6 the second pass is kept for the second character, and the first is untouched', !!(Bq.inp.take && Bq.inp.take.n >= 40) && !!(A.inp.take && A.inp.take.n >= 50), { a: A.inp.take && A.inp.take.n, b: Bq.inp.take && Bq.inp.take.n });
      F('6 the scene runs as long as its longest take', Math.abs(scene.length(app) - Math.max(A.inp.duration(), Bq.inp.duration())) < 0.05, { len: scene.length(app), a: A.inp.duration(), b: Bq.inp.duration() });

      /* 7. the camera lane: cuts made while recording come back at the same moments */
      scene.shot(app, 'wide', 0); scene.hand(app, topps.id); await K.raf(3);
      scene.rec(app); var at = {}; for (var f3 = 0; f3 < 55; f3++) { if (f3 === 12) { scene.shot(app, 'close:' + topps.id, 0); at.close = app.inp.ph; } if (f3 === 34) { scene.shot(app, 'two', 0); at.two = app.inp.ph; } await K.raf(1); } scene.rec(app); await K.raf(3);
      F('7 two camera changes while recording are written on the lane (with where it started)', R.data.cam.length === 3 && R.data.cam[0].t === 0 && R.data.cam[0].shot === 'wide', R.data.cam);
      if (plant === 'camera') R.data.cam = [];
      scene.shot(app, 'wide', 0); scene.play(app); var got = {}; for (var f4 = 0; f4 < 52; f4++) { await K.raf(1); var ph = app.inp.ph; if (ph > at.close + 0.12 && ph < at.two - 0.05) got.mid = R.shot; if (ph > at.two + 0.12) got.end = R.shot; if (ph < at.close - 0.05) got.start = R.shot; }
      scene.stop(app); await K.raf(2);
      F('7 played back, the camera is on each shot when it was', got.start === 'wide' && got.mid === 'close:' + topps.id && got.end === 'two', got);
      /* a second camera pass takes over from its first change */
      scene.rec(app); for (var f5 = 0; f5 < 30; f5++) { if (f5 === 20) scene.shot(app, 'wide', 0); await K.raf(1); } scene.rec(app); await K.raf(2);
      var lane = R.data.cam, last = lane[lane.length - 1]; F('7 a later pass keeps the cuts before its first change and replaces those after', last.shot === 'wide' && lane.filter(function (e) { return e.t > last.t; }).length === 0 && lane.some(function (e) { return e.shot === 'close:' + topps.id; }), lane);

      /* 8, 9. shots: a close-up finds the head and keeps up; the camera never looks off the stage */
      var clamp0 = scene.clampCam; if (plant === 'clamp') scene.clampCam = function (c) { return c; };
      var offStage = [], lost = [];
      for (var s2 = 0; s2 < scene.SHAPES.length; s2++) {
        scene.shape(app, scene.SHAPES[s2][0]); var keys = scene.shots(app).map(function (q) { return q.key; });
        for (var k2 = 0; k2 < keys.length; k2++) {
          scene.shot(app, keys[k2], 0); await K.raf(3); var fr2 = scene.frame(R.data), c2 = R.cam, hw = fr2.w / (2 * c2.z), hh2 = fr2.h / (2 * c2.z);
          if (c2.x - hw < -0.5 || c2.x + hw > W + 0.5 || c2.y - hh2 < -0.5 || c2.y + hh2 > H + 0.5) offStage.push(scene.SHAPES[s2][0] + ' ' + keys[k2]);
          if (keys[k2].slice(0, 6) === 'close:') { var a2 = R.actors[keys[k2].slice(6)], bb = scene.boxOf(R, scene.item(R, a2.id)), cxA = (bb[0] + bb[2]) / 2; if (Math.abs(c2.x - cxA) > hw + 40 || c2.z < 1.1) lost.push(scene.SHAPES[s2][0] + ' ' + scene.item(R, a2.id).name); }
        }
        scene.freeCam(app, function (c) { c.x = -900; c.y = 5000; c.z = 3; }); await K.raf(2); var c3 = R.cam, fr3 = scene.frame(R.data);
        if (c3.x - fr3.w / (2 * c3.z) < -0.5 || c3.y + fr3.h / (2 * c3.z) > H + 0.5) offStage.push(scene.SHAPES[s2][0] + ' by hand');
      }
      scene.clampCam = clamp0;
      F('9 in every shape, every shot and a wild drag by hand: the frame never looks off the stage', offStage.length === 0, offStage);
      F('8 a close-up has its character in it, closer than wide', lost.length === 0, lost);
      scene.shape(app, '16:9'); scene.shot(app, 'close:' + pip.id, 0); await K.raf(4); var cx0 = R.cam.x; scene.set(app, pip.id, { x: pip.x - 300 }); await K.raf(40);
      F('8 a close-up follows its character when they move', Math.abs((cx0 - R.cam.x) - 300) < 30, { moved: Math.round(cx0 - R.cam.x) });
      scene.shot(app, 'wide', 0); await K.raf(2);

      /* 11. undo and redo, in Arrange */
      scene.go(app, 'arrange'); await K.raf(3); var d0 = clean(R.data), n0 = R.hist.past.length;
      if (plant === 'undo') R.data.wind = 0.123;                  // (a change made without telling Undo: what a drag that forgot its snapshot would do)
      scene.set(app, tr.id, { x: 900, s: 1.5 }); scene.setSet(app, 'night'); var cp = await scene.copy(app, tr.id); scene.remove(app, tr.id); scene.shape(app, '9:16');
      var d1 = clean(R.data), steps = R.hist.past.length - n0; for (var u = 0; u < steps; u++) app.undo(false, true); await K.raf(3);
      var dU = JSON.parse(clean(R.data)), dZ = JSON.parse(d0); dU.items.forEach(function (it) { if (it.made) delete it.img; }); dZ.items.forEach(function (it) { if (it.made) delete it.img; });
      F('11 undo of every Arrange change gives the scene back exactly (' + steps + ' steps)', JSON.stringify(dU) === JSON.stringify(dZ), { steps: steps });
      for (var r = 0; r < steps; r++) app.undo(true, true); await K.raf(3); F('11 redo of them all gives the changed scene back', clean(R.data) === d1 || plant === 'undo');
      for (var u2 = 0; u2 < steps; u2++) app.undo(false, true); await K.raf(4);
      F('11 Undo in Perform is still the character\'s own (the room\'s history is the one in hand)', (function () { scene.go(app, 'perform'); var ok = app.hist === R.actors[R.focus].hist; scene.go(app, 'arrange'); return ok; })());

      /* 13. into a character and back */
      await K.raf(3); var bIn = clean(boxes(R)); scene.diveIn(app, topps.id); await K.raf(4);
      F('13 stepping into a character: the room is on that character alone', !scene.on(app) && app.ch === R.actors[topps.id].ch && app.mode === 'build');
      AL.stages.go(app, 'motion'); await K.raf(5); AL.stages.go(app, 'fit'); await K.raf(3); scene.undive(app); await K.raf(4);
      F('13 and back: the scene is as it was left', scene.on(app) && clean(boxes(R)) === bIn && R.stage === 'arrange', { on: scene.on(app) });

      /* 10. kept, closed, opened again */
      var doc = scene.toJson(app, true), txt = JSON.stringify(doc), takesA = A.inp.take.n, laneN = R.data.cam.length, bx = clean(boxes(R)), names = R.data.items.map(function (i) { return i.name; }).join(',');
      var again = JSON.parse(txt); if (plant === 'trip') again.takes = {};
      await scene.close(app, true); await K.raf(3);
      F('14 leaving the scene: back on the character you were on, untouched', !app.scene && app.ch === soloCh && app.inp === soloInp && !document.getElementById('app').classList.contains('scene'));
      await scene.open(app, again); await K.raf(8); R = app.scene;
      var t2 = byName(R, 'Topps'), A2 = R.actors[t2.id];
      F('10 a scene file opened again has everything in it, where it was', R.data.items.map(function (i) { return i.name; }).join(',') === names && clean(boxes(R)) === bx, { names: R.data.items.map(function (i) { return i.name; }).join(',') });
      F('10 ...and every take and the camera lane came back with it', !!(A2 && A2.inp.take && A2.inp.take.n === takesA) && R.data.cam.length === laneN, { take: A2 && A2.inp.take && A2.inp.take.n, was: takesA, lane: R.data.cam.length });
      F('10 the file carries its characters (it opens with none on the shelf)', Object.keys(doc.characters || {}).length === 2);

      /* 12, 15, 16. the awkward ones */
      await scene.addCast(app, { stock: 'topps', name: 'Topps' }); await K.raf(6);
      var twins = R.data.items.filter(function (i) { return i.name === 'Topps'; });
      F('15 the same character twice: two performers, each its own', twins.length === 2 && R.actors[twins[0].id] && R.actors[twins[1].id] && R.actors[twins[0].id].ch !== R.actors[twins[1].id].ch);
      scene.go(app, 'perform'); scene.hand(app, twins[1].id); await K.raf(4); scene.rec(app); await K.raf(6); var th = 0; try { scene.remove(app, twins[1].id); await K.raf(6); } catch (e) { th = 1; }
      F('12 removing the character in hand while it records: nothing thrown, someone else is in hand, nothing still recording', !th && !scene._err && R.focus && R.focus !== twins[1].id && !scene.running(app) && bad(R) === 0, { focus: R.focus, run: scene.running(app) });
      scene.go(app, 'arrange'); await scene.addCast(app, { uid: 'c-not-on-the-shelf', name: 'Ghost' }); await K.raf(4);
      var ghost = byName(R, 'Ghost'); F('16 a character that is no longer on the shelf: marked missing, the scene still plays', !!(ghost && ghost.lost && !R.actors[ghost.id]) && !scene._err && bad(R) === 0);
      scene.remove(app, ghost.id); await K.raf(2);

      /* 17. the same acts by name, as Joybot or a connected model would call them */
      var ag = AL.agent, rr;
      rr = await ag.run('scene_place', { item: 'Tree', back: 80, size: 60, sway: 50 }); var trN = byName(R, 'Tree');
      F('17 a tool call does what the button does (place the tree back, small, swaying)', rr.ok && Math.abs(trN.s - 0.6) < 0.001 && Math.abs(trN.sway - 0.5) < 0.001 && trN.y < H * 0.72, { ok: rr.ok, err: rr.error, y: trN.y });
      rr = await ag.run('scene_place', { item: 'Nobody', x: 5 }); F('17 a wrong name comes back as a sentence that says what there is, not a crash', !rr.ok && /Tree/.test(rr.error), rr.error);
      rr = await ag.run('no_such_tool', {}); F('17 an unknown tool says so and lists the tools', !rr.ok && /scene_new/.test(rr.error));
      rr = await ag.run('scene_shot', { shot: 'Close on Pip', glide: 0 }); await K.raf(3); F('17 "Close on Pip" by name', rr.ok && R.shot === 'close:' + byName(R, 'Pip').id && R.stage === 'perform', rr.error);
      rr = await ag.run('perform', { character: 'Pip', mood: 'Cheery', look: { x: 0.5, y: 0 } }); F('17 a model can puppeteer: mood and look land on that character', rr.ok && R.actors[byName(R, 'Pip').id].inp.pose.mood === 'Cheery' && R.actors[byName(R, 'Pip').id].inp.pose.turnX === 0.5, rr.error);
      var man = ag.manifest(); F('17 the tool list is complete and well formed', man.length >= 15 && man.every(function (t) { return t.name && t.description && t.input_schema && t.input_schema.type === 'object'; }), man.length);
      F('99 no bad numbers and nothing thrown by the end', !scene._err && bad(R) === 0, { err: !!scene._err });
    } catch (e) { R0.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 700) }); }
    try { if (AL.app.scene) await scene.close(AL.app, true); } catch (e2) { }
    try { var mine = (await scene.list(AL.app)).filter(function (e) { return e.name === 'Break test'; }); for (var m = 0; m < mine.length; m++) await scene.forget(AL.app, mine[m].uid); } catch (e4) { }      // (the test tidies its own scenes off the shelf)
    try { localStorage.removeItem('al.scene'); } catch (e3) { }
    return R0;
  };
  Sc.proof = async function () {
    var out = {}, want = { order: /^2 the tree behind/, shape: /^3 changing the frame/, hand: /^5 \.\.\.and nobody else/, passes: /^6 while the second records/, camera: /^7 played back/, clamp: /^9 /, undo: /^11 undo of every/, trip: /^10 \.\.\.and every take/ };
    for (var k in want) { var r = await Sc.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 4) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
