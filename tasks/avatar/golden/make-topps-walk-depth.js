/* Makes topps-walk-depth.json: the web pilot's own walker and solver walking Topps toward the camera and away (al-depth-walk.js),
 * frame by frame, for the phone's Walker to match (tools/jvm-harness/AvatarDepthWalkTest.java).
 * Run in the pilot page (tools/avatarlab, served by serve.mjs) with the built-in Topps open, in the console or over CDP:
 *   copy(JSON.stringify(await (<this file's text>)))      (or the CDP helper returns it)
 * The script: idle, blinks, breathing and eyes-lead off; Perform; depth in use (the face's Closer and further), so the plain
 * stage's depth model is made; frames 0-9 stand; 10-79 the ground line is asked to the near limit (toward the camera);
 * 80-149 to the far limit (away); at 150 Home (it walks back); 220 frames at 30 a second. S is read after the input step
 * (which steps the walker), then the solver is run live (it hands the walker the hips and hands for the next frame).
 */
(async function () {
  window.requestAnimationFrame = function () { return 0; };                  // the page's own loop stops (it would step the inputs too)
  await new Promise(function (r) { setTimeout(r, 150); });
  var app = AL.app, inp = app.inp, set = app.ch.settings = app.ch.settings || {}, dt = 1 / 30, frames = [];
  set.idle = false; set.blinkAuto = false; set.breathe = false; set.eyesLead = false;
  AL.stages.go(app, 'perform'); inp.take = null; inp.pose.flip = false; inp.walker.reset(); inp.walker.z = null;
  inp._depth = true;                                                         // depth in use: dw.prep makes the plain stage's model
  for (var i = 0; i < 220; i++) {
    var w = inp.walker;
    if (i >= 10 && i < 80) w.tz = 1e6;                                       // (clamped to the near limit)
    else if (i >= 80 && i < 150) w.tz = -1e6;                                // (clamped to the far limit)
    if (i === 150) { inp._depth = false; w.home(); }                         // let go: Home walks it back
    inp.step(dt);
    var S = inp.S;
    app.out = app.solver.eval(S, { live: true, dt: dt });
    function hand(k) { var h = S.hand && S.hand[k]; return h ? [+h.x.toFixed(4), +h.y.toFixed(4)] : null; }
    frames.push({ rx: +S.root.x.toFixed(4), ry: +S.root.y.toFixed(4), rs: +S.rootScale.toFixed(5), FL: hand('FL'), FR: hand('FR'),
      fl: +((S.foot && S.foot.FL) || 0).toFixed(5), fr: +((S.foot && S.foot.FR) || 0).toFixed(5), walk: +(S.walk || 0).toFixed(5), sway: +(S.sway || 0).toFixed(5), bounce: +(S.bounce || 0).toFixed(5),
      base: +(w.base || 0).toFixed(4), tz: w.tz == null ? null : +w.tz.toFixed(4), turnX: +S.turnX.toFixed(5), z: w.z ? { lim: w.z.lim.map(function (v) { return +v.toFixed(4); }), front: w.z.front } : null });
  }
  return { format: 'joycreator.walk-depth-trace/1', character: 'Topps', dt: dt,
    script: 'idle, blinks, breathing, eyes-lead off; Perform; depth in use; frames 0-9 stand, 10-79 tz = near limit, 80-149 tz = far limit, 150 Home (depth let go); 220 frames; S read after inp.step then solver eval live',
    frames: frames };
})()
