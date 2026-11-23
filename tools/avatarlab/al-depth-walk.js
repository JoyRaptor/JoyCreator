/* AvatarLab: WALKING TOWARD THE CAMERA AND AWAY. The depth half of walking (the owner, 11-22: "like Character Animator").
 *
 * The walker (al-walk.js) walks its GROUND LINE toward a depth target (walker.tz), with real steps: the feet stay planted and step
 * to where the new ground line puts them, so nothing slides or floats. This file says, for where that ground line is, how big the
 * character is drawn (walker.z.s), and how far toward or away it may go (walker.z.lim):
 *
 *   ALONE, on the plain stage   it grows from its own eye line down, as a person walking up to a camera held at eye height does:
 *                               the top of the drawing stays where it is and the feet come down the screen. From about two thirds
 *                               of its size (away) to nearly half as big again (close). Only while depth is in use: otherwise the
 *                               plain stage is exactly as before.
 *   IN A SCENE                  the scene's own floor rule (scene.depth): lower on the floor = nearer = bigger, and drawn in front
 *                               of whoever stands further back (dw.footY gives the depth for the drawing order). From the back of
 *                               the floor to just inside the picture's bottom edge, and no more than a body's height either way.
 *
 * Coming toward the camera it turns to its FRONT drawing, if its head has one (an angle drawn at the middle of the turn pad: Topps's
 * Frontal), and back as it stops.
 *
 * Phone: two small functions beside the walker (the scale for a ground line, and back); the scene's floor rule is the same one.
 */
(function (root) {
  'use strict';
  var AL = root.AL, scene = AL.scene, P = AL.Inputs.prototype, dw = AL.depthWalk = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  dw.NEAR = 0.45;           // alone: how much bigger it may come (0.45 = nearly half as big again)
  dw.FAR = 0.35;            // alone: how much smaller it may go
  dw.REACH = 1;             // in a scene: no further than this many of its own heights either way
  dw.FRONT = 0.98;          // in a scene: its feet come no nearer than this (of the stage's height): just inside the picture's bottom edge
  /** The character's height from the top of its drawing to its ground line (its own units). */
  function tall(ch) { var b = AL.boundsOf(ch.root) || { y0: 0, y1: ch.h }, w = ch.settings && ch.settings.walk, gy = w && w.groundY != null ? w.groundY : b.y1; return Math.max(20, gy - b.y0); }
  dw.tall = tall;
  /** Its front drawing's place on the turn pad (the head's angle nearest the middle), or null when it has none. */
  dw.front = function (ch) {
    var ix = AL.ix(ch); if (ch._dwIx === ix) return ch._dwFront; ch._dwIx = ix; ch._dwFront = null;
    var hd = AL.firstRole(ch.root, 'head'), best = null, bd = 0.3;
    if (hd && hd.type === 'set' && hd.pick && hd.pick.by === 'turn') (hd.kids || []).forEach(function (k) { if (!k.at) return; var d = Math.max(Math.abs(k.at[0]), Math.abs(k.at[1])); if (d <= bd) { bd = d; best = k.at[0]; } });
    return (ch._dwFront = best);
  };
  /** Alone on the plain stage: scale about its eye line (the top stays, the feet come down). */
  dw.plain = function (ch) {
    var H = tall(ch), a = ch.settings && ch.settings.floorAngle, k = AL.stickDepth ? AL.stickDepth.k(a == null ? AL.stickDepth.ANGLE : a) : 1;      // (the camera's angle: overhead, no change of size; lower, more: al-stick-depth.js)
    return { s: function (b) { return clamp(1 + k * b / H, 0.3, 3); }, inv: k > 1e-3 ? function (s) { return (s - 1) * H / k; } : null, lim: [-dw.FAR * H, dw.NEAR * H], front: dw.front(ch), kind: 'plain', k: k };
  };
  /** In a scene: the scene's own floor rule, for a cast member standing at its place T (scene.place). */
  dw.scene = function (d, it, T, ch) {
    var H = scene.H, f = d.floor, k = Math.max(1e-6, T.k), d0 = scene.depth(d, it.y), reach = tall(ch) * dw.REACH;
    function yOf(b) { return it.y + b * k; }
    return {
      s: function (b) { return scene.depth(d, yOf(b)) / d0; },
      inv: function (s) { var t = (s * d0 - f.shrink) / Math.max(1e-6, 1 - f.shrink); return ((f.far + t * (f.near - f.far)) * H - it.y) / k; },
      lim: [Math.min(0, Math.max(-reach, (f.far * H - it.y) / k)), Math.max(0, Math.min(reach, (H * dw.FRONT - it.y) / k))],
      front: dw.front(ch), kind: 'scene'
    };
  };
  /** Where a cast member's feet are on the floor right now (stage y), worked out from how big it is drawn: what the drawing order and
      the haze go by. A recorded take carries the size, so this is right in playback too. */
  dw.footY = function (R, it) {
    var a = R && R.actors[it.id], live = R && !R.dive && (R.stage === 'perform' || R.stage === 'motion'), s = a && a.inp && a.inp.S ? a.inp.S.rootScale : 1;
    if (!live || !a || !a.T || s == null || Math.abs(s - 1) < 1e-4) return it.y;
    var z = dw.scene(R.data, it, a.T, a.ch); return it.y + z.inv(s) * a.T.k;
  };

  /** Alone on the plain stage the depth model is there only while depth is in use (asked for, or still on its way back). */
  dw.prep = function (inp) {
    var w = inp.walker; if (!w || w.lim) return;                                 // (on a scene's stage the scene sets it: al-scene-draw.js)
    var want = !!inp._depth || (w.z && w.z.kind === 'plain' && (Math.abs(w.base) > 0.05 || (w.tz != null && Math.abs(w.tz) > 0.05)));
    if (!want) { if (w.z) { w.z = null; w.tz = null; } return; }
    var fa = inp.app.ch.settings && inp.app.ch.settings.floorAngle;
    if (!w.z || w.z.kind !== 'plain' || w.z.ch !== inp.app.ch || w.z.ix !== AL.ix(inp.app.ch) || w.z.fa !== fa) { w.z = dw.plain(inp.app.ch); w.z.ch = inp.app.ch; w.z.ix = AL.ix(inp.app.ch); w.z.fa = fa; }
  };
  var step0 = P.step;
  P.step = function (dt) { dw.prep(this); return step0.call(this, dt); };
  /** The plain stage keeps room below the character for it to come closer (app.fit). */
  dw.room = function (app) { var inp = app.inp, w = inp && inp.walker; return inp && (inp._depth || (w && w.z && w.z.kind === 'plain')) ? dw.NEAR * 1.1 : 0.05; };
})(typeof self !== 'undefined' ? self : this);
