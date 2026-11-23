/* AvatarLab: WHAT A TAKE KNOWS ABOUT WALKING. Three rules that keep a recorded walk whole when it is played back.
 *
 * 1. A WALKING HAND IS A FOOT. On all fours the hands are planted on the stage, like the feet (al-walk.js). The take says so,
 *    frame by frame (the lanes hLw and hRw, written in al-inputs.js), so a replay plants them where they stood; and they are
 *    written with the body: whoever walks the body in a pass also writes its walking hands. Without this a replayed hand was
 *    read as "reaching, from the body" and hung in the air, away from the spot it had stood on.
 * 2. A BODY YOU WALKED IS YOURS TO THE END OF THE PASS (on a scene's stage). Letting go of a head hands it back to the older
 *    take over a quarter of a second, which is right for a head. A body that has walked somewhere must not be handed back:
 *    the older take has it standing somewhere else, and it would slide there across the stage.
 * 3. A TAKE THAT IS PLAYING LEADS THE WALKER (on a scene's stage). The body is drawn where the take says, and the walker
 *    underneath walks along with it. So when the take stops, or you take the body half way through, it goes on from where it
 *    is seen, not from wherever it was last left. And a take starts facing the way it was recorded (the engine eases a
 *    turn-round over a moment; a replay must not begin with a turn that is not in it).
 *
 * Rules 2 and 3 are for a scene's stage (the walker has a limit there: walker.lim, al-stage-walk.js), where a walk can cross
 * the whole stage. Alone on the plain stage a take hands the body back and leaves the walker alone, as it always did.
 *
 * Phone: AvatarParamTrack gains the two hand lanes; rule 2 is one flag in the recorder's list of owners; rule 3 sets the
 * walker's target from the track each frame.
 */
(function (root) {
  'use strict';
  var AL = root.AL, P = AL.Inputs.prototype, tw = AL.takeWalk = {};
  /** What the take holds on a lane at the frame being played (undefined when it holds nothing there). */
  function at(inp, name) { var tk = inp.take, arr = tk && tk.ch && tk.ch[name]; return arr && arr.length ? arr[Math.min(Math.floor(inp.ph * tk.fps), arr.length - 1)] : undefined; }
  tw.at = at;
  /** Is this performer on a scene's stage (and not alone on the plain one)? */
  tw.onStage = function (inp) { return !!(inp.walker && inp.walker.lim); };
  tw.keep = true;           // rule 2 (off: a walked body is handed back to the older take, as a head is)
  tw.leads = true;          // rule 3 (off: the walker stays wherever it was last left while a take plays)
  tw.snap = true;           // rule 3, its first frame (off: a take started while the character faces the other way turns round on its own first)

  var owned0 = P.owned;
  P.owned = function (talking, faceOn) {
    var o = owned0.call(this, talking, faceOn), self = this, w = this.walker;
    if (tw.keep && this.recording && tw.onStage(this)) {                        // rule 2
      if (o.body && w.travels()) this._walked = this.wrote;                     // (this.wrote is a new object for every pass)
      if (this._walked === this.wrote) o.body = o.footL = o.footR = true;
    }
    if (o.body) ['L', 'R'].forEach(function (sd) {                              // rule 1
      var hd = self.S.hand[sd]; if ((hd && hd.w) || at(self, 'h' + sd + 'w')) o['hand' + sd] = true;
    });
    return o;
  };

  /** The engine that draws this performer (the scene's cast member, or the room's own character). */
  function solverOf(inp) { var R = AL.app.scene, k; if (R) for (k in R.actors) if (R.actors[k].inp === inp) return R.actors[k].solver; return AL.app.inp === inp ? AL.app.solver : null; }
  /** Rule 3, once a frame before the performer is stepped. */
  tw.lead = function (inp) {
    var w = inp.walker; if (!w) return; w.led = false;
    var run = !!(inp.take && (inp.playing || inp.recording || inp.scrub)), start = run && !inp._twRun; inp._twRun = run;
    if (!tw.leads || !tw.onStage(inp) || !run || inp.held.body || w.held) return;
    if (inp.recording && (inp.fresh || inp._walked === inp.wrote)) return;       // (everything, or the body, is being written live in this pass)
    var x = at(inp, 'rootX'), f = at(inp, 'flip'); if (x == null || x !== x) return;
    w.tx = x; w.led = true; if (f != null) inp.pose.flip = f > 0.5;
    var s = at(inp, 'rootS'); if (s != null && s === s && w.z && w.z.inv) w.tz = w.z.inv(s);      // (toward the camera and away too: the take carries how big it is drawn, al-depth-walk.js)
    /* a take starts facing the way it was recorded: no turn-round of its own at the first frame because it was last left facing the other way */
    if (tw.snap && start && f != null && inp.ph < 0.1) { var sv = solverOf(inp); if (sv && sv.st) sv.st.flip = (sv.st.flipWant ? 1 : 0) ^ (f > 0.5 ? 1 : 0); w.x = x; if (w.tz != null && w.z) { var db = w.tz - w.base; w.base += db; w.y += db; w.ty += db; } }
  };
  var step0 = P.step;
  P.step = function (dt) { tw.lead(this); return step0.call(this, dt); };
})(typeof self !== 'undefined' ? self : this);
