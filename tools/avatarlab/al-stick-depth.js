/* AvatarLab: THE JOYSTICK IN PERSPECTIVE, AND THE CAMERA'S ANGLE (the owner, 11-22).
 *
 * A tick box in the joystick's settings: "Up and down walk into the picture". On, the pad's up walks the character away from you and
 * down toward you, left and right as before: a floor seen from above, as in a game, with the same real steps as the face's Closer and
 * further (al-depth-walk.js). Flicks are still gestures: a flick up still jumps. Off, the pad is as it was (up toward a jump, down
 * toward a crouch).
 *
 * THE CAMERA ANGLE (his idea): one slider says how the camera looks at the floor, and so how much size changes with depth.
 *   Overhead (0)   the floor lies flat to the screen: walking away and toward you moves it up and down the picture with no change
 *                  of size (the plain "walking anywhere").
 *   Lower          the floor tilts away; the further it walks, the smaller; the nearer, the bigger. 45 is where it starts.
 * Alone on the plain stage the angle is the character's own (ch.settings.floorAngle). In a scene it is the SHOT's: the scene's own
 * floor (how much smaller things are at the back, floor.shrink), so the set, the objects and every character agree; changing it
 * there is one Undo step of the scene.
 *
 * So you can see it, a thin cyan floor grid lies in the pad under the knob while the pad walks in perspective: the floor drawn as a
 * plane tilted to the camera's angle (a flat square grid overhead, lines running away to a point when low), fading toward the pad's
 * edge, drifting as the character walks in or out. The slider has the same grid beside it, live.
 *
 * Phone: one Boolean in the stick settings and one angle; the grid is a few projected lines on the pad's Canvas with a radial alpha
 * brush; in a scene the angle is the scene document's floor.shrink.
 */
(function (root) {
  'use strict';
  var AL = root.AL, sd = AL.stickDepth = {}, h = AL.h, DEG = Math.PI / 180;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  sd.SPEED = 0.6;            // body heights a second, the knob at the rim
  sd.ANGLE = 45;             // where the camera angle starts
  sd.MAX = 75;               // and how low it may go (at 90 the floor would be edge on: no floor at all)
  /** How much size changes with depth for a camera angle, 1 at the starting angle, 0 overhead. */
  sd.k = function (deg) { return Math.sin(clamp(deg, 0, 89) * DEG) / Math.sin(sd.ANGLE * DEG); };
  function inScene(app) { return !!(app.scene && !app.scene.dive && AL.scene.on(app)); }
  /** The camera's angle for what is on the stage now: the scene's own floor, or the character's own when it is alone. */
  sd.angle = function (app) {
    if (inScene(app)) { var sh = app.scene.data.floor.shrink, k = 2 * (1 - (sh == null ? 0.5 : sh)); return Math.asin(clamp(k * Math.sin(sd.ANGLE * DEG), 0, 1)) / DEG; }
    var a = app.ch && app.ch.settings && app.ch.settings.floorAngle; return a == null ? sd.ANGLE : a;
  };
  /** Set it: alone, the character's (the slider's own Undo step); in a scene, the scene's floor (one Undo step of the scene per drag). */
  sd.setAngle = function (app, deg, first) {
    deg = clamp(deg, 0, sd.MAX);
    if (inScene(app)) { if (first) AL.scene.snap(app, 'Camera angle'); app.scene.data.floor.shrink = Math.round((1 - 0.5 * sd.k(deg)) * 1000) / 1000; AL.scene.touch(app); }
    else app.ch.settings.floorAngle = Math.round(deg);
  };
  /** Is the pad in perspective for the character in hand? (Only for something that walks on legs.) */
  sd.on = function (app) { var st = app.ch && app.ch.settings, w = app.inp && app.inp.walker; return !!(st && st.stick && st.stick.depth && w && w.cfg()); };
  /** The tick box and the camera angle, at the top of the joystick's settings sheet. */
  sd.toggle = function (app, after) {
    var st = app.ch.settings, on = !!(st.stick && st.stick.depth), box = h('div', { class: 'stickdepth' });
    if (!(app.inp && app.inp.walker && app.inp.walker.cfg())) return null;
    box.appendChild(app.ui.toggle('Up and down walk into the picture', on, function (v) {
      app.edit(function () { st.stick = st.stick || {}; st.stick.depth = v; }, true, 'Joystick: up and down ' + (v ? 'into the picture' : 'jump and crouch'));
      if (after) after();
    }, 'Push up and it walks away from you; pull down and it comes toward you. Flicks still jump.', 'On: the pad walks the character into the background (up) and toward the camera (down), left and right as before. A thin floor grid in the pad shows it is on. Off: up and down jump and crouch.'));
    if (on) box.appendChild(sd.angleRow(app));
    return box;
  };
  /** The camera angle: a slider with the floor grid beside it, live. */
  sd.angleRow = function (app) {
    var c = h('canvas', { class: 'anglepic', width: 128, height: 128, 'aria-hidden': 'true' }), first = true, scene = inScene(app);
    function pic(deg) { var g = c.getContext('2d'); g.setTransform(2, 0, 0, 2, 0, 0); g.clearRect(0, 0, 64, 64); sd.grid(g, 64, 32, 26, 0, deg); }
    function fmt(v) { return v < 3 ? 'overhead' : Math.round(v) + '°'; }
    var sl = app.ui.slider(scene ? 'Camera angle (this scene)' : 'Camera angle', { min: 0, max: sd.MAX, step: 1, value: Math.round(sd.angle(app)), undo: !scene, fmt: fmt,
      title: 'How the camera looks at the floor. Overhead: walking away and toward you moves it up and down with no change of size. Lower: the further away, the smaller.' + (scene ? ' In a scene it is the shot\'s own: everything on the floor follows it.' : ''),
      set: function (v) { sd.setAngle(app, v, first && scene); first = false; pic(v); }, done: function () { first = true; } });
    pic(sd.angle(app));
    return h('div', { class: 'anglerow' }, [c, sl]);
  };
  document.head.appendChild(h('style', { text: '.stickdepth{margin-bottom:6px} .anglerow{display:flex;align-items:center;gap:10px;margin:2px 0 4px} .anglerow .sl{flex:1;min-width:0} .anglepic{width:64px;height:64px;flex:none;border-radius:10px;background:rgba(255,255,255,.03);box-shadow:inset 0 0 0 1px rgba(255,255,255,.06)}' }));

  /** Once a frame while a thumb steers the pad in perspective: the knob's up and down move where it is walking to, in depth. */
  sd.step = function (app, K, dt) {
    var w = app.inp.walker, z = w.z; app.inp._depth = true; if (!z) return;
    var gy = Math.sign(K.y) * Math.pow(Math.abs(K.y), AL.stick.CURVE), H = AL.depthWalk.tall(app.ch), from = w.tz == null ? w.base : w.tz;
    w.tz = clamp(from + gy * sd.SPEED * H * dt, z.lim[0], z.lim[1]);
  };

  /* ── the floor grid ── */
  /** g: a 2D context (already scaled to its size s); m: its middle; r: the radius it fills. phase: how far the floor has drifted (0..1).
      deg: the camera's angle. The floor is a square plane, tilted away by that angle about the line across its middle, seen in perspective. */
  sd.grid = function (g, s, m, r, phase, deg) {
    var th = clamp(deg == null ? sd.ANGLE : deg, 0, sd.MAX) * DEG, R = r * 1.25, D = R * 2.6, cs = Math.cos(th), sn = Math.sin(th), L = 8, n = 6, i;
    function at(u, v) { var X = u * R, Y = v * R * cs, Z = v * R * sn, f = D / (D + Z); return [m + X * f, m + r * 0.08 - Y * f]; }      // (v = 1 is the far edge)
    var fade = g.createRadialGradient(m, m, r * 0.15, m, m, r * 1.12); fade.addColorStop(0, 'rgba(34,211,238,.55)'); fade.addColorStop(0.6, 'rgba(34,211,238,.28)'); fade.addColorStop(1, 'rgba(34,211,238,0)');
    g.save(); g.strokeStyle = fade; g.lineWidth = 0.9; g.lineCap = 'round'; g.beginPath();
    for (i = 0; i <= L; i++) { var u = -1 + 2 * i / L, a = at(u, -1), b = at(u, 1); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); }
    for (i = 0; i <= n; i++) { var v = 1 - 2 * (((i + (phase || 0)) % (n + 1)) / n); if (v < -1 || v > 1) continue; var p = at(-1, v), q = at(1, v); g.moveTo(p[0], p[1]); g.lineTo(q[0], q[1]); }      // across, drifting toward you as it comes nearer
    g.stroke(); g.restore();
  };
  /** How far the floor has drifted for the character in hand (so the grid moves as it walks in or out). */
  sd.phase = function (app) { var w = app.inp && app.inp.walker; if (!w) return 0; var H = AL.depthWalk.tall(app.ch); return (((w.base / (H * 0.25)) % 1) + 1) % 1; };
})(typeof self !== 'undefined' ? self : this);
