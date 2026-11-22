/* AvatarLab: THE JOYSTICK IN PERSPECTIVE (the owner, 11-22). A tick box in the joystick's settings: "Up and down walk into the picture".
 *
 * On, the pad's up and down walk the character into the background and toward the camera, and left and right stay left and right:
 * a floor seen from above, as in a game. Push up and it walks away from you, smaller; pull down and it comes toward you, bigger,
 * with the same steps as the face's Closer and further (al-depth-walk.js). Flicks are still gestures: a flick up still jumps.
 * Off, the pad is as it was (up toward a jump, down toward a crouch).
 *
 * So you can see which way the pad works, a thin cyan floor grid lies in the pad under the knob while it is on: lines running away
 * to the top, fading out toward the pad's edge, and drifting as the character walks in or out.
 *
 * Each character keeps its own (ch.settings.stick.depth), beside its gestures; a change is one Undo step.
 *
 * Phone: one more Boolean in the stick settings; the grid is a few lines drawn on the pad's Canvas with a radial alpha brush.
 */
(function (root) {
  'use strict';
  var AL = root.AL, sd = AL.stickDepth = {};
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  sd.SPEED = 0.6;            // body heights a second, the knob at the rim
  /** Is the pad in perspective for the character in hand? (Only for something that walks on legs.) */
  sd.on = function (app) { var st = app.ch && app.ch.settings, w = app.inp && app.inp.walker; return !!(st && st.stick && st.stick.depth && w && w.cfg()); };
  /** The tick box, at the top of the joystick's settings sheet. */
  sd.toggle = function (app, after) {
    var st = app.ch.settings, on = !!(st.stick && st.stick.depth);
    if (!(app.inp && app.inp.walker && app.inp.walker.cfg())) return null;
    return app.ui.toggle('Up and down walk into the picture', on, function (v) {
      app.edit(function () { st.stick = st.stick || {}; st.stick.depth = v; }, true, 'Joystick: up and down ' + (v ? 'into the picture' : 'jump and crouch'));
      if (after) after();
    }, 'Push up and it walks away from you; pull down and it comes toward you. Flicks still jump.', 'On: the pad walks the character into the background (up) and toward the camera (down), left and right as before. A thin floor grid in the pad shows it is on. Off: up and down jump and crouch.');
  };
  /** Once a frame while a thumb steers the pad in perspective: the knob's up and down move where it is walking to, in depth. */
  sd.step = function (app, K, dt) {
    var w = app.inp.walker, z = w.z; app.inp._depth = true; if (!z) return;
    var gy = Math.sign(K.y) * Math.pow(Math.abs(K.y), AL.stick.CURVE), H = AL.depthWalk.tall(app.ch), from = w.tz == null ? w.base : w.tz;
    w.tz = clamp(from + gy * sd.SPEED * H * dt, z.lim[0], z.lim[1]);
  };

  /* ── the floor grid in the pad ── */
  /** g: the pad's 2D context (already scaled to its size s); m: its middle; r: the radius of its ring. phase: how far the floor has drifted (0..1). */
  sd.grid = function (g, s, m, r, phase) {
    var top = m - r * 0.78, bot = m + r * 1.05, vx = m, vy = m - r * 1.9, half = r * 1.5, L = 9, n = 6, i;
    var fade = g.createRadialGradient(m, m, r * 0.15, m, m, r * 1.12); fade.addColorStop(0, 'rgba(34,211,238,.55)'); fade.addColorStop(0.6, 'rgba(34,211,238,.28)'); fade.addColorStop(1, 'rgba(34,211,238,0)');
    function xAt(x0, y) { return vx + (x0 - vx) * (y - vy) / (bot - vy); }      // a line from the near edge toward the vanishing point
    g.save(); g.strokeStyle = fade; g.lineWidth = 0.9; g.lineCap = 'round'; g.beginPath();
    for (i = 0; i <= L; i++) { var x0 = m - half + 2 * half * i / L; g.moveTo(x0, bot); g.lineTo(xAt(x0, top), top); }
    for (i = 0; i < n; i++) {                                                     // across: closer together toward the back, drifting as it walks
      var t = ((i + (phase || 0)) % n) / n, k = 3, f = (1 - 1 / (1 + k * t)) / (1 - 1 / (1 + k)), y = top + (bot - top) * f;
      g.moveTo(xAt(m - half, y), y); g.lineTo(xAt(m + half, y), y);
    }
    g.stroke(); g.restore();
  };
  /** How far the floor has drifted for the character in hand (so the grid moves as it walks in or out). */
  sd.phase = function (app) { var w = app.inp && app.inp.walker; if (!w) return 0; var H = AL.depthWalk.tall(app.ch); return (((w.base / (H * 0.25)) % 1) + 1) % 1; };
})(typeof self !== 'undefined' ? self : this);
