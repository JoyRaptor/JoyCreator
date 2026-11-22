/* AvatarLab: WALK WITH YOUR FACE (the owner, 11-22: "like Character Animator"). A small drawer under Walking in Perform, with tick boxes:
 *
 *   Left and right with your face   your face to one side of where it rests walks the character that way, faster the further over,
 *                                   like the joystick; back to the middle and it stops where it is. (Turning or tilting your head still
 *                                   turns ITS head: this is where your face SITS, not where it points.) In a scene it can walk off.
 *   Closer and further with your face   come closer to the camera and it walks toward you, growing as it comes; sit back and it takes a
 *                                   few steps away. Where your face rests, it stands at its place. Real steps: al-depth-walk.js.
 *   Up close                        no face needed: it walks up to the camera and stays; untick it and it walks back.
 *
 * Small drift is not a walk: the middle of each is a dead zone (the face is noisy at the edges). Your face's resting place is the face
 * camera's own (taken by itself, and again with Re-centre). The signals are the face camera's leanX and near (al-face.js), read, never
 * changed. Whatever the face walks is the body's, so a take records it like a finger's walk; while a take plays, the take walks it.
 *
 * Phone: the drawer is a small Column under Walking; the signals are FaceSignals.LEAN_X and NEAR through FaceCapture; the driver is the
 * same few lines feeding the walker's target, beside the joystick's.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, fw = AL.faceWalk = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  fw.DEAD = { x: 0.18, z: 0.15 };     // the middle of each that is not a walk
  fw.SPEED = 0.8;                       // body heights a second, the face right over to one side
  fw.CURVE = 1.6;                       // gentle near the middle, quick toward the edge
  fw.TILT = 0.36;                       // the head tilted further than this (the face camera's |roll|) holds the sideways walk: a safety net, the face
                                        // engine takes the tilt's swing out of leanX itself. His Note 9 test (11-22): a lean rolled 0.23, a shoulder tilt 0.44
  var ROOM = fw.room = (function () { try { var v = JSON.parse(localStorage.getItem('al.facewalk') || 'null'); return v && typeof v === 'object' ? v : {}; } catch (e) { return {}; } })();
  function keep() { try { localStorage.setItem('al.facewalk', JSON.stringify({ lr: !!ROOM.lr, depth: !!ROOM.depth })); } catch (e) { } }
  fw.on = function (k) { return !!ROOM[k]; };
  fw.set = function (app, k, v) { ROOM[k] = !!v; keep(); refit(app); app.refreshPanel(true); };
  /** "Up close" belongs to the character in hand right now, not to the room. */
  fw.close = function (inp) { return !!(inp && inp._close); };
  fw.setClose = function (app, v) { app.inp._close = !!v; refit(app); app.refreshPanel(true); };
  function refit(app) { var inp = app.inp; inp._depth = !!(inp.walker.cfg() && (ROOM.depth || inp._close)); if (!app.scene || app.scene.dive) { app.userView = false; app.fit(); } }      // (alone, the view keeps room below for it to come closer)
  /** The face as the face camera sees it now, or null (no camera, or no face). The test puts its own face here. */
  fw.source = function (inp) {
    var c = inp && inp.cam; if (!c || !c.on) return null;
    var faceOn = c.mode === 'always' || inp.talking; if (!faceOn && inp.watch) inp.watch();      // (the camera is on but not driving the head: still look, for the walk)
    var f = c.face; return f && (f.face == null || f.face > 0.5) ? f : null;
  };
  /** The part of a signal past the dead zone, -1..1. */
  function past(v, dz) { var a = Math.abs(v || 0); return a <= dz ? 0 : Math.sign(v) * Math.min(1, (a - dz) / (1 - dz)); }
  fw.past = past;

  /** Once a frame while the room is alive: the face (or Up close) leads the walker of the character in hand. */
  fw.step = function (app, dt) {
    var inp = app.inp, w = inp && inp.walker; if (!w || !(app.mode === 'perform' || app.live)) return;
    var K = inp._fw || (inp._fw = { lx: 0, nz: 0, own: false }), face = fw.source(inp), lr = fw.on('lr'), dep = fw.on('depth'), up = fw.close(inp), drive = false;
    inp._depth = !!(w.cfg() && (dep || up));
    var replay = inp.take && !inp.recording && (inp.playing || inp.scrub);
    if (face) { var e = 1 - Math.exp(-8 * dt); K.lx += ((face.leanX || 0) - K.lx) * e; K.nz += ((face.near || 0) - K.nz) * e; } else { K.lx = 0; K.nz = 0; }
    if (!replay && !w.held) {
      /* left and right: a rate, like the joystick */
      if (lr && face && w.travels()) {
        K.roll = (K.roll || 0) + (Math.abs(face.roll || 0) - (K.roll || 0)) * (1 - Math.exp(-8 * dt));
        var u = K.roll > fw.TILT ? 0 : past(K.lx, fw.DEAD.x);      // (a head tilted toward a shoulder swings sideways about the neck and reads as a lean: the owner's Note 9 test, 11-22. A tilt is not a walk)
        if (u) { var b = AL.boundsOf(app.ch.root) || { y0: 0, y1: app.ch.h, x0: 0, x1: app.ch.w }, lim = w.lim || [-(b.x1 - b.x0) * 0.7, (b.x1 - b.x0) * 0.7];
          w.tx = clamp(w.tx + Math.sign(u) * Math.pow(Math.abs(u), fw.CURVE) * fw.SPEED * (b.y1 - b.y0) * dt, lim[0], lim[1]); drive = true; }
      }
      /* toward the camera and away: a place, set by how near the face is */
      if (w.z && w.cfg()) {
        var zl = w.z.lim, was = w.tz;
        if (up) w.tz = zl[1];
        else if (dep && face) { var n = past(K.nz, fw.DEAD.z); w.tz = n > 0 ? n * zl[1] : -n * zl[0]; }
        else if (!dep && (K.drove || Math.abs(w.base) > 0.05)) { w.tz = 0; if (Math.abs(w.base) < 0.05) K.drove = false; }      // (nothing asks for depth now: back to its place)
        if (w.tz != null && (Math.abs(w.tz - w.base) > 0.5 || w.tz !== was)) { drive = true; K.drove = true; }
      }
    }
    /* whatever the face walks is the body's (a take records it); handed back once it has stopped */
    if (drive) { inp.held.body = true; K.own = true; K.still = 0; }
    else if (K.own) { K.still = (K.still || 0) + dt; if (K.still > 0.4) { if (!w.held) inp.held.body = false; K.own = false; } }
  };
  app.hooks.push(function (dt) { fw.step(app, dt || 1 / 30); });

  /* ── the drawer: a button in the Walking row opens it ── */
  fw.chip = function (app) { return app.ui.chip('Walk with your face', { on: !!app.faceWalkOpen || fw.on('lr') || fw.on('depth') || fw.close(app.inp), icon: 'cam', title: 'Walk with your face: lean to a side to walk that way, come closer to walk toward the camera. And Up close.', fn: function () { app.faceWalkOpen = !app.faceWalkOpen; app.refreshPanel(true); } }); };
  fw.drawer = function (app, pb) {
    if (!app.faceWalkOpen) return;
    var ui = app.ui, inp = app.inp, deep = !!inp.walker.cfg(), box = AL.h('div', { class: 'facewalk' });
    if (!inp.cam.on) box.appendChild(ui.note('These two follow your face: turn on <b>Face</b> above. Up close works without it.'));
    box.appendChild(ui.toggle('Left and right with your face', fw.on('lr'), function (v) { fw.set(app, 'lr', v); }, 'Your face to one side of where it rests walks it that way', 'On: put your face to the left or right of where it rests and the character walks that way, faster the further over. Back to the middle and it stops. Turning your head still turns its head.'));
    if (deep) {
      box.appendChild(ui.toggle('Closer and further with your face', fw.on('depth'), function (v) { fw.set(app, 'depth', v); }, 'Come closer and it walks toward you; sit back and it steps away', 'On: lean in toward the camera and the character walks toward you, growing as it comes; sit back and it takes a few steps away. Where your face rests, it stands at its place.'));
      box.appendChild(ui.toggle('Up close', fw.close(inp), function (v) { fw.setClose(app, v); }, 'It walks up to the camera; untick and it walks back', 'On: the character walks up to the camera and stays there. Off: it walks back to its place. No face needed.'));
    }
    pb.appendChild(box);
  };
})(typeof self !== 'undefined' ? self : this);
