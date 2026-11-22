/* AvatarLab: BREAK TEST, scenes in the one room (al-scene*.js, al-frame.js, al-export.js, al-agent.js). Load after al-breaktest.js and
 * al-breaktest-fit.js. Uses the owner's saved Topps (local/owner-topps.character.json, hand edits and all) when it is there.
 *
 *   await AL.breakTest.scene.run()                 every check
 *   await AL.breakTest.scene.proof()               feeds each main check the fault it is for; every one must be caught
 *
 * Rule of the room: a check only counts once it has been seen to FAIL on the fault. plant = which fault to put in:
 *   order   the thing that should be at the back is put at the front        shape   an item is moved while the frame changes shape
 *   passes  the first character's take is thrown away before the second pass camera  the camera lane is emptied before playback
 *   clamp   the camera is let off the edge of the stage                      trip    the takes are dropped from the saved scene
 *   undo    the scene is changed behind Undo's back                          hand    the other character is moved by hand as well
 * and for the one room (the owner's second design, 11-21):
 *   fours      a leg is pulled off the body in the all-fours stance           emptytake  opening a scene takes the empty scene's take away
 *   emptydel   the empty scene loses its guard and can be deleted            share      one part of the strip takes twice its share
 *   frameleave turning the frame off leaves the scene (round 20's way)       hold       a long press never comes
 *   setup      choosing a card never sets it up (it only takes it in hand)
 * and for the owner's notes of 11-21 late (Studio's canvas, the push, the joystick):
 *   shrink     the strip takes its room from the frame (round 20's way)       nodesk  no Studio texture round the picture
 *   noclip     the set is not cut at the frame                                 stickdead  the joystick moves nothing
 * and for the joystick's gestures:
 *   nogesture  a flick is never seen as one                                    tapsteer   a held steer is taken for a tap
 *   dbl        two taps also fire the one-tap act                              undo2      a gesture is changed behind Undo's back
 *   nochair    the scene's path tab without the director's chair
 */
(function (root) {
  'use strict';
  var AL = root.AL, T = AL.breakTest, K = T.kit, Sc = T.scene = {}, scene = AL.scene, W = scene.W, H = scene.H;
  function realWait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function q(sel) { return document.querySelector(sel); }
  function ev(el, type, o) { o = o || {}; el.dispatchEvent(new PointerEvent(type, { bubbles: true, cancelable: true, pointerId: 7, clientX: o.x || 0, clientY: o.y || 0, button: 0, isPrimary: true })); }
  /** A real long press on a card: down, held past the hold, up, and the click a browser sends after it. */
  async function hold(el) { var r = el.getBoundingClientRect(), p = { x: r.x + r.width / 2, y: r.y + r.height / 2 }; ev(el, 'pointerdown', p); await realWait(Math.min(AL.strip.HOLD, 1500) + 150); ev(el, 'pointerup', p); el.click(); await K.raf(3); }
  /** The owner's saved Topps as the character you are on, else the one that comes with the room. */
  async function ownTopps(app) {
    var o = null; try { o = await fetch('local/owner-topps.character.json').then(function (r) { return r.ok ? r.json() : null; }); } catch (e) { o = null; }
    if (!o) { await AL.intake.builtIn(app, 'topps'); return 'built in'; }
    delete o.take; AL.img.map = {}; var ch = await AL.fromJson(o); app.setMode('build'); app.setCharacter(ch, { quiet: true }); return 'the owner\'s';
  }
  function tree() { var c = document.createElement('canvas'); c.width = 200; c.height = 320; var g = c.getContext('2d'); g.fillStyle = '#6b4a2b'; g.fillRect(88, 180, 24, 140); g.fillStyle = '#2f8f46'; g.beginPath(); g.arc(100, 110, 95, 0, 7); g.fill(); return c; }
  function boxes(R) { var o = {}; R.data.items.forEach(function (it) { var b = scene.boxOf(R, it); o[it.id] = b ? Array.prototype.map.call(b, function (v) { return Math.round(v * 10) / 10; }) : null; }); return o; }
  function bad(R) { var n = 0; Object.keys(R.actors).forEach(function (id) { var a = R.actors[id]; if (!a.out) return; a.out.items.forEach(function (it) { for (var i = 0; i < it.v.length; i++) if (!isFinite(it.v[i])) n++; }); }); ['x', 'y', 'z'].forEach(function (k) { if (!isFinite(R.cam[k])) n++; }); return n; }
  function clean(d) { return JSON.stringify(d, function (k, v) { return k.charAt(0) === '_' ? undefined : v; }); }
  function byName(R, n) { return R.data.items.filter(function (it) { return it.name === n; })[0]; }

  Sc.run = async function (opt) {
    opt = opt || {}; var app = AL.app, R0 = K.mk(), plant = opt.plant || '', F = R0.F, keepSolo = app.scene ? null : app.ch;
    var room0 = JSON.stringify(AL.frame.room), empty0 = JSON.stringify({ shape: AL.frame.empty.shape, safe: AL.frame.empty.safe, shots: AL.frame.empty.shots });
    try {
      if (app.scene) await scene.close(app, true);
      R0.note.push('Topps: ' + await ownTopps(app)); await K.raf(6);
      var soloCh = app.ch, soloInp = app.inp;

      /* 1. a new scene: the set, and the character you were on, standing in it */
      await scene.create(app, 'meadow', 'Break test'); await K.raf(6);
      var R = app.scene; F('1 a new scene opens in Perform, the character in hand', !!R && R.stage === 'perform' && app.mode === 'perform' && !!R.focus && app.inp === R.actors[R.focus].inp, R && R.stage);
      scene.go(app, 'fit'); await K.raf(4);                                // (placing is measured held still: Arrange)
      F('1 a scene opens with its set and the character in it', !!R && R.data.items.length === 2 && scene.cast(R).length === 1, R && R.data.items.map(function (i) { return i.kind; }));
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
      var errs = 0, ST = ['fit', 'perform', 'setup', 'motion', 'perform', 'fit', 'motion', 'setup', 'perform']; for (var g = 0; g < ST.length; g++) { try { AL.stages.go(app, ST[g]); await K.raf(3); if (bad(R)) errs++; if ((R.stage === 'perform') !== (app.mode === 'perform') || R.stage !== ST[g] || (ST[g] !== 'perform' && app.stage !== ST[g]) || app.live !== (ST[g] === 'motion')) errs++; } catch (e) { errs++; } }
      F('4 the four stages on the rail, back and forth, the scene in hand: nothing thrown, the room and the scene agree', errs === 0 && !scene._err && !R.dive, errs);
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
      AL.frame.set(app, true);                                    // shots need the frame. (Without this the checks passed or failed by whether the frame happened to be left on in this browser.)
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
      scene.go(app, 'fit'); await K.raf(3); var d0 = clean(R.data), n0 = R.hist.past.length;
      if (plant === 'undo') R.data.wind = 0.123;                  // (a change made without telling Undo: what a drag that forgot its snapshot would do)
      scene.set(app, tr.id, { x: 900, s: 1.5 }); scene.setSet(app, 'night'); var cp = await scene.copy(app, tr.id); scene.remove(app, tr.id); scene.shape(app, '9:16');
      var d1 = clean(R.data), steps = R.hist.past.length - n0; for (var u = 0; u < steps; u++) app.undo(false, true); await K.raf(3);
      var dU = JSON.parse(clean(R.data)), dZ = JSON.parse(d0); dU.items.forEach(function (it) { if (it.made) delete it.img; }); dZ.items.forEach(function (it) { if (it.made) delete it.img; });
      F('11 undo of every Arrange change gives the scene back exactly (' + steps + ' steps)', JSON.stringify(dU) === JSON.stringify(dZ), { steps: steps });
      for (var r = 0; r < steps; r++) app.undo(true, true); await K.raf(3); F('11 redo of them all gives the changed scene back', clean(R.data) === d1 || plant === 'undo');
      for (var u2 = 0; u2 < steps; u2++) app.undo(false, true); await K.raf(4);
      F('11 Undo in Perform is still the character\'s own (the room\'s history is the one in hand)', (function () { scene.go(app, 'perform'); var ok = app.hist === R.actors[R.focus].hist; scene.go(app, 'fit'); return ok; })());

      /* 13. into a character and back */
      await K.raf(3); var bIn = clean(boxes(R)); scene.diveIn(app, topps.id, 'fit'); await K.raf(4);
      F('13 a character in hand: the room is on that character alone, at the stage asked for, the scene first on the path', !scene.on(app) && app.ch === R.actors[topps.id].ch && app.mode === 'build' && app.stage === 'fit' && AL.stages.path(app)[0].name === R.data.name && AL.stages.path(app)[1].name === app.ch.name, AL.stages.path(app).map(function (p) { return p.name; }));
      AL.stages.go(app, 'motion'); await K.raf(5); AL.stages.go(app, 'fit'); await K.raf(3);
      F('13 its rail works on it alone (Motion, then Arrange), the scene waiting', !scene.on(app) && app.stage === 'fit' && R.dive === topps.id && R.stage === 'fit');
      var chair0 = AL.strip.chair; if (plant === 'nochair') AL.strip.chair = null;
      try {
        app.refreshPanel(); var cr1 = q('#panelhead .crumb'), dimChair = !!(cr1 && cr1.querySelector('svg.chair')) && !cr1.classList.contains('on');
        AL.stages.path(app)[0].go(); await K.raf(4); var cr2 = q('#panelhead .crumb'), litChair = !!(cr2 && cr2.querySelector('svg.chair')) && cr2.classList.contains('on');
        F('13 the scene\'s path tab wears the director\'s chair: unlit with a character in hand, lit with the scene in hand', dimChair && litChair, { dim: dimChair, lit: litChair });
      } finally { AL.strip.chair = chair0; }
      F('13 and back by the scene\'s path tab: the scene in hand, as it was left, on the same stage', scene.on(app) && clean(boxes(R)) === bIn && R.stage === 'fit' && R.hand === 'scene', { on: scene.on(app), stage: R.stage });

      /* 10. kept, closed, opened again */
      var doc = scene.toJson(app, true), txt = JSON.stringify(doc), takesA = A.inp.take.n, laneN = R.data.cam.length, bx = clean(boxes(R)), names = R.data.items.map(function (i) { return i.name; }).join(',');
      var again = JSON.parse(txt); if (plant === 'trip') again.takes = {};
      await scene.close(app, true); await K.raf(3);
      F('14 leaving the scene: back on the character you were on, untouched', !app.scene && app.ch === soloCh && app.inp === soloInp && !document.getElementById('app').classList.contains('scene'));
      await scene.open(app, again, { stage: 'fit' }); await K.raf(8); R = app.scene;
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
      scene.go(app, 'fit'); await scene.addCast(app, { uid: 'c-not-on-the-shelf', name: 'Ghost' }); await K.raf(4);
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
      rr = await ag.run('scene_stage', { stage: 'arrange' }); var okA = rr.ok && R.stage === 'fit' && R.hand === 'scene' && !R.dive; rr = await ag.run('scene_stage', { stage: 'motion' }); var okM = rr.ok && R.stage === 'motion' && app.live; rr = await ag.run('scene_stage', { stage: 'perform' });
      F('17 scene_stage maps onto the room\'s four stages (arrange is the second)', okA && okM && rr.ok && R.stage === 'perform' && rr.result.stage === 'perform', { a: okA, m: okM, err: rr.error });
      F('99 no bad numbers and nothing thrown by the end of the old checks', !scene._err && bad(R) === 0, { err: !!scene._err });

      /* 18. a character on all fours inside a scene: down on all fours, turned round, stood up again. Nothing comes off the body. */
      var ROOM = AL.frame.room, tp = byName(R, 'Topps'); scene.go(app, 'perform'); scene.hand(app, tp.id); await K.raf(10);
      var S4 = AL.stance, a4 = R.actors[tp.id], p4 = S4.fours(a4.ch) || (S4.parts(a4.ch) ? S4.makeFours(a4.ch) : null);
      if (!p4) R0.note.push('18 not run: no all fours can be worked out for this Topps');
      else {
        AL.reindex(a4.ch); a4.inp.setTrig(p4.trig, false); a4.inp.pose.flip = false; await K.wait(900);
        var g0 = T.measure(app).gaps, nParts = Object.keys(g0).length;
        var off = function (label) { var m = T.measure(app), worst = null; for (var k in m.gaps) { var gp = m.gaps[k], b0 = g0[k] != null ? Math.max(0, g0[k]) : 0; if (gp < 0) continue; if (gp - b0 > 14 && (!worst || gp - b0 > worst.by)) worst = { part: k, by: gp - b0 }; } F(label, !m.bad && !worst && app.out === a4.out && nParts >= 4, worst || (m.bad ? 'a number that is not a number' : { parts: nParts })); };
        if (plant === 'fours') { var hd4 = AL.firstRole(a4.ch.root, 'head'); if (hd4) { var e4 = p4.d[hd4.id] || (p4.d[hd4.id] = {}); e4.x = (e4.x || 0) + 600; } }
        a4.inp.setTrig(p4.trig, true); await K.wait(1700); off('18 on all fours in a scene: nothing comes off the body');
        scene.set(app, tp.id, { flip: true }, 'Turn round'); await K.wait(900);
        F('18 turned round in the scene: the performer is turned round', a4.inp.pose.flip === true && app.inp === a4.inp);
        off('18 on all fours, turned round in the scene: nothing comes off the body');
        a4.inp.setTrig(p4.trig, false); await K.wait(1500); off('18 stood up again, still turned round: nothing comes off the body');
        scene.set(app, tp.id, { flip: false }, 'Turn back'); await K.raf(3);
      }

      /* 19. THE EMPTY SCENE: a real scene, the first card, never deleted; a take recorded there is the character's own */
      var uidBack = R.data.uid; await scene.close(app); await K.raf(4);
      F('19 the empty scene is the plain stage with the character you were on', !app.scene && app.ch === soloCh && app.inp === soloInp && !scene.on(app));
      AL.stages.go(app, 'perform'); AL.strip.toggle(app, 'sc', true); await K.raf(4);
      var first = q('#strip .p-sc .sdc'); F('19 the empty scene is the first card of the Scene part, chosen', !!first && first.getAttribute('data-sc') === scene.EMPTY && first.classList.contains('on'), first && first.getAttribute('data-sc'));
      app.inp.clearTake(); app.inp.record(); for (var f6 = 0; f6 < 40; f6++) { app.inp.pose.turnX = Math.sin(f6 / 8) * 0.8; app.inp.held.head = true; await K.raf(1); } app.inp.stop(); app.inp.held.head = false; await K.raf(2);
      var n1 = app.inp.take && app.inp.take.n;
      F('19 a take recorded in the empty scene is the character\'s own', n1 >= 30 && app.inp === soloInp && !app.scene, n1);
      await scene.openId(app, uidBack, { stage: 'perform' }); await K.raf(8); if (plant === 'emptytake' && app.scene) app.scene.solo.inp.take = null; await scene.close(app); await K.raf(4);
      F('19 ...and a scene opened and left again does not take it away', app.inp === soloInp && !!(app.inp.take && app.inp.take.n === n1) && app.mode === 'perform', { n: app.inp.take && app.inp.take.n, was: n1 });
      var E0 = scene.EMPTY, del; if (plant === 'emptydel') scene.EMPTY = '__planted__';
      try { del = await scene.forget(app, 'empty'); } finally { scene.EMPTY = E0; }
      AL.strip.sync(app, true); await K.raf(3); first = q('#strip .p-sc .sdc');
      F('19 the empty scene cannot be deleted, and stays the first card', del === false && !!first && first.getAttribute('data-sc') === scene.EMPTY, { del: del });

      /* 20. the strip: whichever parts are on share its whole width, and each ends in its own add card */
      var mixes = [[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0], [1, 0, 1], [0, 1, 1], [1, 1, 1], [0, 0, 0]], bad20 = [], st20 = null;
      if (plant === 'share') { st20 = document.createElement('style'); st20.textContent = '#strip .spart:first-child{flex-grow:2}'; document.head.appendChild(st20); }
      for (var mi = 0; mi < mixes.length; mi++) {
        var m3 = mixes[mi]; ROOM.sc = !!m3[0]; ROOM.ch = !!m3[1]; ROOM.sh = false; if (m3[2]) AL.strip.toggle(app, 'sh', true); else AL.strip.sync(app, true); await K.raf(2);
        var ps = Array.prototype.slice.call(document.querySelectorAll('#strip .spart')), nOn = m3[0] + m3[1] + m3[2], sb = q('#strip'), sw = sb.getBoundingClientRect().width, want = nOn ? (sw - 6 * (nOn - 1)) / nOn : 0;
        var ws = ps.map(function (p) { return Math.round(p.getBoundingClientRect().width); }), ends = ps.every(function (p) { var c = p.querySelector('.spc'); return c && c.lastElementChild && c.lastElementChild.classList.contains('add'); });
        if (ps.length !== nOn || ws.some(function (x) { return Math.abs(x - want) > 1.5; }) || (nOn === 0) !== (getComputedStyle(sb).display === 'none') || !ends) bad20.push(m3.join('') + ': ' + ws.join('/') + ' want ' + Math.round(want));
      }
      if (st20) st20.remove();
      F('20 the strip shares its whole width equally among the parts that are on (all 8 mixes), each part ending in its add card', bad20.length === 0, bad20);

      /* 21. the frame is only a shape: off and on again by its button, the same scene, still the one remembered for a reload */
      var bfr = q('#bframe'), was21 = AL.frame.on(); bfr.click(); await K.raf(3); bfr.click(); await K.raf(3);
      F('21 in the empty scene, the frame off and on again: still the empty scene', !app.scene && AL.frame.on() === was21 && app.ch === soloCh);
      await scene.openId(app, uidBack, { stage: 'perform' }); await K.raf(8); R = app.scene; var R21 = R;
      var set0 = AL.frame.set; if (plant === 'frameleave') AL.frame.set = function (a, on) { set0(a, on); if (!on && a.scene) scene.close(a); };
      try { bfr = q('#bframe'); was21 = AL.frame.on(); bfr.click(); await K.raf(4); bfr.click(); await K.raf(4); } finally { AL.frame.set = set0; }
      var at21 = null; try { at21 = JSON.parse(localStorage.getItem('al.sceneAt') || 'null'); } catch (e5) { }
      F('21 in a scene, the frame off and on again (its button, twice): the same scene, still the one remembered for a reload', app.scene === R21 && R21.data.uid === uidBack && AL.frame.on() === was21 && localStorage.getItem('al.scene') === uidBack && !!at21 && at21.uid === uidBack, { same: app.scene === R21, kept: localStorage.getItem('al.scene') === uidBack });
      if (!app.scene) { await scene.openId(app, uidBack, { stage: 'perform' }); await K.raf(8); }
      R = app.scene;

      /* 22. a card is set up by a long press, or by a second tap on the one already chosen */
      var dive0 = scene.diveIn, hold0 = AL.strip.HOLD;
      if (plant === 'setup') scene.diveIn = function (a, id) { scene.hand(a, id); };
      if (plant === 'hold') AL.strip.HOLD = 1e9;
      try {
        ROOM.sc = true; ROOM.ch = true; AL.strip.sync(app, true); scene.go(app, 'perform'); await K.raf(4);
        var fid = R.focus, chosen = q('#strip .p-ch .sdc.on'); if (chosen) chosen.click(); await K.raf(4);
        F('22 a second tap on the character in hand sets it up: in hand alone, in ' + AL.stages.WORD2, !!chosen && R.dive === fid && app.stage === 'fit' && app.mode === 'build', { dive: R.dive, stage: app.stage });
        AL.stages.go(app, 'perform'); await K.raf(4);
        var other = Array.prototype.slice.call(document.querySelectorAll('#strip .p-ch .sdc[data-ch]')).filter(function (b) { return !b.classList.contains('on'); })[0], oid = other && other.getAttribute('data-ch');
        if (other) await hold(other);
        F('22 a long press on another character sets that one up (not only takes it in hand)', !!other && R.dive === oid && app.stage === 'fit', { dive: R.dive, want: oid });
        AL.stages.go(app, 'perform'); await K.raf(4);
        var scc = q('#strip .p-sc .sdc.on'); if (scc) await hold(scc);
        F('22 a long press on the scene in use sets it up: the scene in hand, in ' + AL.stages.WORD2, !!scc && R.stage === 'fit' && !R.dive && R.hand === 'scene' && app.scene === R, { stage: R.stage, hand: R.hand });
        scene.go(app, 'perform'); AL.strip.toggle(app, 'sh', true); await K.raf(4);
        var sh0 = q('#strip .p-sh .sdc.on'); if (sh0) sh0.click(); await K.raf(2); var open1 = getComputedStyle(q('#sheet')).display !== 'none'; app.closeSheet();
        F('22 a second tap on the shot in use opens its setup', !!sh0 && open1);
        var sh1 = Array.prototype.slice.call(document.querySelectorAll('#strip .p-sh .sdc[data-shot]')).filter(function (b) { return !b.classList.contains('on'); })[0], k1 = sh1 && sh1.getAttribute('data-shot');
        if (sh1) await hold(sh1); var open2 = getComputedStyle(q('#sheet')).display !== 'none'; app.closeSheet();
        F('22 a long press on another shot goes there and opens its setup', !!sh1 && open2 && R.shot === k1, { shot: R.shot, want: k1 });
      } finally { scene.diveIn = dive0; AL.strip.HOLD = hold0; }

      /* 23. the strip pushes the frame aside as Studio's transcript panel pushes the picture: never smaller; upright frames get it from the side */
      var FR = AL.frame, area0 = FR.area, sizes = [], bad23 = [];
      if (plant === 'shrink') FR.area = function (a) { var A0 = area0(a), s = document.getElementById('strip'); if (s && s.offsetHeight && !s.classList.contains('side')) A0.h -= s.offsetHeight; return A0; };      // round 20's way: the strip took the room
      try {
        scene.go(app, 'perform'); FR.set(app, true);
        for (var sh3 = 0; sh3 < scene.SHAPES.length; sh3++) {
          FR.setShape(app, scene.SHAPES[sh3][0]); ROOM.sc = ROOM.ch = ROOM.sh = false; AL.strip.sync(app, true); await K.raf(3); var Fa = FR.rect(app, FR.ar(app));
          ROOM.sc = true; ROOM.ch = true; AL.strip.sync(app, true); await K.raf(3); var Fb = FR.rect(app, FR.ar(app)), sEl = q('#strip'), sideNow = sEl.classList.contains('side'), wantSide = FR.ar(app) < 1 || (FR.ar(app) === 1 && FR.side(app) === 'side');
          if (Math.abs(Fa.w - Fb.w) > 0.5 || Math.abs(Fa.h - Fb.h) > 0.5) bad23.push(scene.SHAPES[sh3][0] + ' size ' + Math.round(Fa.w) + '->' + Math.round(Fb.w));
          if (sideNow !== wantSide) bad23.push(scene.SHAPES[sh3][0] + ' came from the ' + (sideNow ? 'side' : 'bottom'));
          var Aa = FR.area(app), covered = sideNow ? Fb.x + Fb.w > sEl.offsetLeft - FR.BAND - 6 + 0.5 : Fb.y + Fb.h > sEl.offsetTop - FR.BAND - 6 + 0.5, room = sideNow ? Fb.x - Aa.x > 0.5 : Fb.y - Aa.y > 0.5;
          if (covered && room) bad23.push(scene.SHAPES[sh3][0] + ' still covered with room to move');
          var ps3 = Array.prototype.slice.call(document.querySelectorAll('#strip .spart')), dim = function (p) { return sideNow ? p.getBoundingClientRect().height : p.getBoundingClientRect().width; };
          if (ps3.length !== 2 || Math.abs(dim(ps3[0]) - dim(ps3[1])) > 1.5) bad23.push(scene.SHAPES[sh3][0] + ' parts not shared equally');
          sizes.push(scene.SHAPES[sh3][0] + (sideNow ? ' side' : ' bottom') + ' ' + Math.round(Fb.w) + 'x' + Math.round(Fb.h));
        }
      } finally { FR.area = area0; FR.setShape(app, '16:9'); }
      F('23 the strip moves the frame aside and never makes it smaller; upright frames get it from the side, wide from the bottom, shared equally (all 5 shapes)', bad23.length === 0, bad23.length ? bad23 : sizes);

      /* 24. Video Studio's canvas texture round the picture, and the set cut at the picture's edge; the empty scene with no frame stays the plain stage */
      var desk0 = FR.desk; if (plant === 'nodesk') FR.desk = function () { return desk0(null); }; if (plant === 'noclip') FR.desk = function (r) { desk0(r); if (app.scene) app.scene.clip = null; };
      try {
        scene.go(app, 'perform'); FR.setShape(app, '9:16'); FR.set(app, true); await K.raf(4);
        var Gd = app.G, d0 = Gd.draw, firstPane = null; Gd.draw = function (items, v) { if (!firstPane && items.length && items[0].img && !items[0].id) { var vv = items[0].v, xs = [], ys = []; for (var vi = 0; vi < vv.length; vi += 2) { xs.push((vv[vi] * v.s + v.ox) / (window.devicePixelRatio > 2.5 ? 2.5 : window.devicePixelRatio || 1)); ys.push((vv[vi + 1] * v.s + v.oy) / (window.devicePixelRatio > 2.5 ? 2.5 : window.devicePixelRatio || 1)); } firstPane = [Math.min.apply(null, xs), Math.min.apply(null, ys), Math.max.apply(null, xs), Math.max.apply(null, ys)]; } return d0.apply(Gd, arguments); };
        await K.raf(2); Gd.draw = d0; var Fr = app.scene.fr, fg = q('#frameground').getBoundingClientRect(), sg = q('#stage').getBoundingClientRect();
        var cut = !!firstPane && firstPane[0] >= Fr.x - 1.5 && firstPane[2] <= Fr.x + Fr.w + 1.5 && firstPane[1] >= Fr.y - 1.5 && firstPane[3] <= Fr.y + Fr.h + 1.5;
        F('24 in a scene, the set is cut at the frame and Studio\'s canvas texture is round it', q('#stage').classList.contains('desk') && cut && Math.abs(fg.left - sg.left - Fr.x) < 1.5 && Math.abs(fg.width - Fr.w) < 1.5, { desk: q('#stage').classList.contains('desk'), pane: firstPane && firstPane.map(Math.round), frame: [Fr.x, Fr.y, Fr.w, Fr.h].map(Math.round) });
        FR.setShape(app, '16:9'); await scene.close(app); await K.raf(3); AL.stages.go(app, 'perform'); FR.set(app, true); await K.raf(10);
        var Fp = FR.plainRect(app), fg2 = q('#frameground').getBoundingClientRect();
        F('24 in the empty scene, the frame on: the plain stage inside it, Studio\'s texture round it', q('#stage').classList.contains('desk') && Math.abs(fg2.width - Fp.w) < 1.5, { desk: q('#stage').classList.contains('desk') });
        FR.set(app, false); await K.raf(3);
        F('24 ...and with the frame off it is the plain stage exactly as before (no texture)', !q('#stage').classList.contains('desk') && getComputedStyle(q('#frameground')).display === 'none');
      } finally { FR.desk = desk0; }

      /* 25. the joystick: the other thumb walks the body, faster toward the rim, and stops where it is let go */
      await scene.openId(app, uidBack, { stage: 'perform' }); await K.raf(8); R = app.scene; var tp5 = byName(R, 'Topps'); scene.hand(app, tp5.id); await K.raf(4);
      var spd0 = AL.stick.SPEED; if (plant === 'stickdead') AL.stick.SPEED = 0;
      try {
        AL.stick.set(app, true); await K.raf(2); var pad5 = q('.stickpad'), w5 = app.inp.walker;
        F('25 the joystick opens beside the head pad; Mic, Voice file and Face become their pictures', !!pad5 && document.querySelectorAll('#panelbody .rowf.ico .chip.ico').length >= 3);
        var pr = pad5.getBoundingClientRect(), t5a = w5.tx, x5a = w5.x;
        ev(pad5, 'pointerdown', { x: pr.left + pr.width * 0.62, y: pr.top + pr.height / 2 }); for (var f7 = 0; f7 < 15; f7++) await K.raf(1); var slow = w5.tx - t5a;
        ev(pad5, 'pointermove', { x: pr.right - 2, y: pr.top + pr.height / 2 }); var t5b = w5.tx; for (var f8 = 0; f8 < 15; f8++) await K.raf(1); var fast = w5.tx - t5b, held5 = app.inp.held.body, t5c = w5.tx;
        await realWait(420);                                                // (held, as a thumb steering is: not a flick)
        ev(pad5, 'pointerup', { x: pr.right - 2, y: pr.top + pr.height / 2 }); for (var f9 = 0; f9 < 40; f9++) await K.raf(1);
        F('25 pushed right, the body walks right, faster at the rim than near the middle', slow > 0.5 && fast > slow * 1.8 && held5, { slow: Math.round(slow), fast: Math.round(fast) });
        F('25 let go: the body stops where it was taken (not back home), and is no longer held', !app.inp.held.body && Math.abs(w5.tx - t5c) < 1 && w5.x > x5a + (t5c - t5a) * 0.5, { tx: Math.round(w5.tx), x: Math.round(w5.x), from: Math.round(x5a) });
        AL.stick.set(app, false); await K.raf(2);
        F('25 put away: the pad goes and Mic, Voice file and Face have their words again', !q('.stickpad') && !document.querySelector('#panelbody .chip.ico'));
      } finally { AL.stick.SPEED = spd0; AL.stick.release(app); }
      /* 26. gestures on the joystick: a flick or a tap is an act, a held press is still the joystick; each character keeps its own */
      var M = AL.stickMoves, T26 = { tm: AL.stick.TAP.ms, tv: AL.stick.TAP.move, sd: AL.stick.SWIPE.dist, db: M.DOUBLE }, mset0 = M.set, inp6 = app.inp, w6 = inp6.walker, ch6 = app.ch, flip6 = inp6.pose.flip, tr6 = JSON.stringify(inp6.trig);
      if (plant === 'nogesture') AL.stick.SWIPE.dist = 99; if (plant === 'tapsteer') { AL.stick.TAP.ms = 1e9; AL.stick.TAP.move = 99; } if (plant === 'dbl') M.DOUBLE = 0;
      if (plant === 'undo2') M.set = function (a, slot, key) { a.ch.settings.stick = a.ch.settings.stick || {}; a.ch.settings.stick[slot] = key; };      // (a change made behind Undo's back)
      try {
        AL.stick.set(app, true); await K.raf(2); var pad6 = q('.stickpad'), map6 = M.map(app), tId = map6.tap.slice(0, 5) === 'trig:' ? map6.tap.slice(5) : null, bw = AL.boundsOf(ch6.root), cw = bw.x1 - bw.x0;
        var at6 = function () { pad6 = q('.stickpad'); var r = pad6.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2, s: r.width }; };
        var flick = function (dx, dy) { var c = at6(), e = { x: c.x + dx * c.s * 0.35, y: c.y + dy * c.s * 0.35 }; ev(pad6, 'pointerdown', c); ev(pad6, 'pointermove', e); ev(pad6, 'pointerup', e); };
        var tapOnce = function () { var c = at6(); ev(pad6, 'pointerdown', c); ev(pad6, 'pointerup', c); };
        F('26 a walking character starts with Jump on a flick up, a dash each way, its stance on a tap, Turn round on two taps', map6.up === 'jump' && map6.left === 'dash-l' && map6.right === 'dash-r' && !!tId && map6.dtap === 'flip', map6);
        var y6 = w6.y, minY = y6, air6 = false; flick(0, -1);
        for (var j6 = 0; j6 < 34; j6++) { await K.raf(1); await realWait(30); minY = Math.min(minY, w6.y); if (w6.air) air6 = true; }
        F('26 a flick up jumps: up past where the feet let go, and down again', air6 && minY < y6 - 5 && Math.abs(w6.y - y6) < 8, { air: air6, rose: Math.round(y6 - minY), back: Math.round(w6.y - y6) });
        w6.home(); await K.raf(40); var tx6 = w6.tx; flick(1, 0); await K.raf(2);      // (from the middle: the joystick check above left him at the edge of his room)
        F('26 a flick right dashes right, a long way at once', w6.tx - tx6 > cw * 0.4, { dashed: Math.round(w6.tx - tx6), width: Math.round(cw) });
        w6.home(); await K.raf(120);                                        // (standing still again: a character on the move faces the way it goes, whatever a tap says)
        AL.stick.flash = null; var trB = !!inp6.trig[tId], c6 = at6(); ev(pad6, 'pointerdown', c6); ev(pad6, 'pointermove', { x: c6.x + c6.s * 0.12, y: c6.y }); await realWait(450); ev(pad6, 'pointerup', { x: c6.x + c6.s * 0.12, y: c6.y }); await realWait(M.DOUBLE + 150); await K.raf(2);
        F('26 a press held and steered is the joystick, not a gesture (nothing fires)', !!inp6.trig[tId] === trB && !AL.stick.flash, { flash: AL.stick.flash && AL.stick.flash.text });
        tapOnce(); await realWait(M.DOUBLE + 150); await K.raf(2);
        F('26 a tap does its act (the stance on, or off)', !!inp6.trig[tId] !== trB, { was: trB, now: !!inp6.trig[tId] });
        var fl6 = inp6.pose.flip, trC = !!inp6.trig[tId]; tapOnce(); await realWait(60); tapOnce(); await realWait(M.DOUBLE + 150); await K.raf(2);
        F('26 two taps turn it round, and do not also do the one-tap act', inp6.pose.flip !== fl6 && !!inp6.trig[tId] === trC, { flip: inp6.pose.flip, was: fl6, trig: !!inp6.trig[tId], trigWas: trC });
        var n6 = app.hist.past.length; M.set(app, 'up', 'blink'); var ok61 = M.map(app).up === 'blink' && app.hist.past.length === n6 + 1; app.undo(false, true); var ok62 = M.map(app).up === 'jump';
        F('26 changing a gesture is one Undo step, kept with the character', ok61 && ok62, { set: ok61, undone: ok62 });
        var acts6 = M.acts(app), keys6 = acts6.map(function (a) { return a.key; });
        F('26 only what this character can do is offered: each of its buttons, and the moves of something that walks', (ch6.triggers || []).every(function (t) { return keys6.indexOf('trig:' + t.id) >= 0; }) && keys6.indexOf('jump') >= 0 && acts6.every(function (a) { return a.key.slice(0, 5) !== 'trig:' || (ch6.triggers || []).some(function (t) { return 'trig:' + t.id === a.key; }); }), keys6.length);
      } finally { AL.stick.TAP.ms = T26.tm; AL.stick.TAP.move = T26.tv; AL.stick.SWIPE.dist = T26.sd; M.DOUBLE = T26.db; M.set = mset0; AL.stick.set(app, false); inp6.pose.flip = flip6; var tr0 = JSON.parse(tr6); Object.keys(inp6.trig).forEach(function (k) { if (!!inp6.trig[k] !== !!tr0[k]) inp6.setTrig(k, !!tr0[k]); }); }
      F('99 no bad numbers and nothing thrown by the end', !scene._err && !(app.scene && bad(app.scene)), { err: !!scene._err });
    } catch (e) { R0.fails.push({ label: 'THROWN', info: String(e && e.stack || e).slice(0, 700) }); }
    try { var r0 = JSON.parse(room0), e0 = JSON.parse(empty0), RM = AL.frame.room, EM = AL.frame.empty; for (var rk in r0) RM[rk] = r0[rk]; EM.shape = e0.shape; EM.safe = e0.safe; EM.shots = e0.shots; AL.frame.keep(); } catch (e6) { }      // (the room's own settings, as they were)
    try { if (AL.app.scene) await scene.close(AL.app, true); } catch (e2) { }
    try { var mine = (await scene.list(AL.app)).filter(function (e) { return e.name === 'Break test'; }); for (var m = 0; m < mine.length; m++) await scene.forget(AL.app, mine[m].uid); } catch (e4) { }      // (the test tidies its own scenes off the shelf)
    try { localStorage.removeItem('al.scene'); } catch (e3) { }
    return R0;
  };
  Sc.proof = async function () {
    var out = {}, want = { order: /^2 the tree behind/, shape: /^3 changing the frame/, hand: /^5 \.\.\.and nobody else/, passes: /^6 while the second records/, camera: /^7 played back/, clamp: /^9 /, undo: /^11 undo of every/, trip: /^10 \.\.\.and every take/,
      fours: /^18 on all fours/, emptytake: /^19 \.\.\.and a scene opened/, emptydel: /^19 the empty scene cannot be deleted/, share: /^20 /, frameleave: /^21 in a scene, the frame off/, hold: /^22 a long press/, setup: /^22 a second tap on the character/,
      shrink: /^23 /, nodesk: /^24 in a scene/, noclip: /^24 in a scene/, stickdead: /^25 pushed right/,
      nogesture: /^26 a flick up jumps/, tapsteer: /^26 a press held and steered/, dbl: /^26 two taps/, undo2: /^26 changing a gesture/,
      nochair: /^13 the scene's path tab wears/ };
    var only = arguments[0];
    for (var k in want) { if (only && only.indexOf(k) < 0) continue; var r = await Sc.run({ plant: k }); out[k] = { caught: r.fails.some(function (f) { return want[k].test(f.label); }), fails: r.fails.map(function (f) { return f.label; }).slice(0, 5) }; }
    return out;
  };
})(typeof self !== 'undefined' ? self : this);
