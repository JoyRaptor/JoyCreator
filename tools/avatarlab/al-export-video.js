/* AvatarLab: VIDEO, IN THE EXPORT SHEET. The third choice beside Joy Creator file and One frame (al-export.js).
 *
 *   What goes      the sheet's own tick boxes: each character, the set (a scene)
 *   Behind it      the stage, or a KEY COLOUR he picks (green, blue, magenta, or his own), to cut out later in Video Studio or any
 *                  editor. Offered only where something shows behind; a set that fills the picture says so. Never see-through.
 *   Size           only the sizes this browser says it can make (Video Studio's 1080p, 720p, 480p, at the frame's shape)
 *   Its sound      the take's voice (every voice in a scene, each at its own time); the sheet says when there is none
 * The first picture is shown as it will be. Making it shows how far it has got; Stop (or closing the sheet) stops it and nothing is
 * saved. If it cannot be made, it says so in a sentence and what to try instead: never a technical error.
 *
 * Phone: Video Studio's export sheet and ExportService progress (its broadcasts), with the key colour as a solid image clip.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, h = AL.h, exp = AL.exporter, Vd = AL.video, frame = AL.frame, scene = AL.scene;
  function $(id) { return document.getElementById(id); }
  function load(k, d) { try { var v = localStorage.getItem(k); return v == null ? d : v; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem(k, String(v)); } catch (e) { } }
  var KEYS = [['#00b140', 'Green'], ['#0047bb', 'Blue'], ['#ff00ff', 'Magenta']];
  var O = exp.v = { behind: load('al.videoBehind', 'stage'), key: load('al.videoKey', '#00b140'), s: +load('al.videoSize', 1080), sizes: null, shape: null };
  document.head.appendChild(h('style', { text: [
    '.xbar{height:8px;border-radius:4px;background:var(--raised);overflow:hidden;margin:10px 0 6px}', '.xbar i{display:block;height:100%;width:0;background:var(--sel);transition:width .15s}',
    '.kdot{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:6px;vertical-align:-1px;box-shadow:0 0 0 1px rgba(255,255,255,.35)}', '#keypick{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none}'
  ].join('\n') }));
  function R0(app) { return scene.on(app) ? app.scene : null; }
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'; }); }
  /** Does anything show behind the picture (the plain stage, a scene with its set ticked off, or a scene with no backdrop)? */
  exp.behindShows = function (app) { var R = R0(app); if (!R) return true; return !!exp.skip(app).set || !R.data.items.some(function (it) { return it.kind === 'back'; }); };
  /** The colour behind, or null for the stage. */
  exp.keyNow = function (app) { return exp.behindShows(app) && O.behind === 'key' ? O.key : null; };
  function secs(s) { return (Math.round(s * 10) / 10) + ' s'; }
  /** The sizes for this shape, found once (asking the encoder takes a moment), then the sheet is shown again. */
  function sizes(app) {
    var shape = frame.shape(app); if (O.shape === shape && O.sizes) return O.sizes;
    if (O.asking !== shape) { O.asking = shape; Vd.sizes(app).then(function (l) { O.sizes = l; O.shape = shape; O.asking = null; if (exp.o.what === 'video' && $('sheet').style.display !== 'none' && $('sheet').querySelector('h3').textContent === 'Export') exp.sheet(app); }, function () { O.sizes = []; O.shape = shape; O.asking = null; }); }
    return null;
  }
  exp.videoBody = function (app, body, acts) {
    var ui = app.ui, R = R0(app), skip = exp.skip(app), plan = Vd.plan(app, skip), l = sizes(app);
    if (!plan) { body.appendChild(ui.note(R ? 'Nothing is recorded in this scene yet. The video is the scene\'s takes: record one first, with the red dot.' : 'Record a take first: the video is the take. The red dot is at the top of Perform.')); return; }
    if (exp.behindShows(app)) {
      body.appendChild(ui.lab('Behind ' + (R ? 'them' : 'it')));
      body.appendChild(ui.row([ui.seg([['stage', 'The stage', 'The plain dark stage, as you see it'], ['key', 'Key colour', 'One flat colour, to cut out later in Video Studio or any video editor. A video cannot be see-through.']], O.behind, function (v) { O.behind = v; save('al.videoBehind', v); exp.sheet(app); }, 'Behind')]));
      if (O.behind === 'key') {
        var mine = !KEYS.some(function (k) { return k[0] === O.key; }), pick = h('input', { type: 'color', id: 'keypick', value: O.key, 'aria-label': 'Your own key colour', onchange: function () { O.key = this.value; save('al.videoKey', O.key); exp.sheet(app); } });
        var chips = KEYS.map(function (k) { var c = ui.chip(k[1], { on: O.key === k[0], title: k[1] + ' (' + k[0] + '). Pick one the character has least of.', fn: function () { O.key = k[0]; save('al.videoKey', O.key); exp.sheet(app); } }); c.insertBefore(h('span', { class: 'kdot', style: 'background:' + k[0] }), c.firstChild); return c; });
        var own = ui.chip(mine ? 'Yours' : 'Your own', { on: mine, title: 'Pick any colour', fn: function () { pick.click(); } }); own.insertBefore(h('span', { class: 'kdot', style: 'background:' + (mine ? O.key : 'conic-gradient(red,yellow,lime,cyan,blue,magenta,red)') }), own.firstChild);
        body.appendChild(ui.row(chips.concat([own, pick])));
      }
    } else body.appendChild(ui.note('The set fills the picture. To have a key colour behind the characters instead, tick the set off above.'));
    if (!l) { body.appendChild(ui.note('Finding the sizes this browser can make…')); return; }
    if (!l.length) { body.appendChild(ui.note('This browser cannot make a video of this shape (' + esc(frame.shape(app)) + '). Chrome or Edge on a computer can; or save the Joy Creator file and make the video in Video Studio on the phone.')); return; }
    var pickS = l.filter(function (q) { return q.s === O.s; })[0] || l[0];
    body.appendChild(ui.lab('Size'));
    body.appendChild(ui.row([ui.seg(l.map(function (q) { return [String(q.s), q.label, q.w + ' × ' + q.h]; }), String(pickS.s), function (v) { O.s = +v; save('al.videoSize', v); exp.sheet(app); }, 'Video size')]));
    /* the first picture, as it will be */
    try {
      var pw = Math.min(480, pickS.w), ph = Math.round(pw * pickS.h / pickS.w), pic = plan.draw(0, pw, ph), pv = h('canvas', { class: 'xprev', 'aria-label': 'The first picture of the video' }); pv.width = pw; pv.height = ph;
      var g = pv.getContext('2d'); Vd.ground(g, pw, ph, exp.keyNow(app)); if (pic) g.drawImage(pic, 0, 0, pw, ph); body.appendChild(pv);
    } catch (e) { }
    var n = plan.voices.length;
    body.appendChild(ui.note('<b>' + pickS.w + ' × ' + pickS.h + '</b>, the frame\'s shape (' + esc(frame.shape(app)) + '), 30 pictures a second, <b>' + secs(plan.n / Vd.FPS) + '</b>: ' + (R ? 'the whole scene with its camera' : 'the whole take') + ', from its start.' + (frame.on() ? '' : R ? ' The frame is hidden, so the camera stays wide.' : ' The frame is hidden: this is what it would cut. Its button is at the bottom right of the stage in Perform.')));
    body.appendChild(ui.note(n ? (R && n > 1 ? 'Its sound: the ' + n + ' voices, each where it was recorded.' : 'Its sound: the voice kept with the take.') : 'There is no voice in ' + (R ? 'the characters going in' : 'this take') + ', so the video is silent. A take recorded with the Mic or a Voice file keeps its voice.'));
    acts.push({ label: 'Make the video', cls: 'go', icon: 'cam', title: 'Make the video and save it', fn: function () { exp.makeVideo(app, pickS); } });
  };

  /** Make it, with progress and Stop; then save it. */
  exp.makeVideo = function (app, size) {
    var ui = app.ui, stop = { now: false }, bar = h('i'), words = h('p', { class: 'note', text: 'Getting ready…' }), body = h('div', null, [h('div', { class: 'xbar', role: 'progressbar', 'aria-label': 'How far the video has got' }, bar), words, ui.note('Stop, or closing this, stops it. Nothing is saved until it is finished.')]);
    var R = R0(app), m = app.inp, key = exp.keyNow(app), skip = exp.skip(app), name = exp.base(app) + '.mp4';
    if (R) scene.stop(app); else if (m && (m.playing || m.recording)) m.stop();
    app.sheet('Making the video', body, [{ label: 'Stop', cls: 'quiet', icon: 'stop', title: 'Stop making the video', fn: function () { stop.now = true; app.closeSheet(); app.toast('Stopped. No video was saved.'); } }]);
    function shown() { return body.isConnected && $('sheet').style.display !== 'none'; }
    var jobs = []; if (R) Object.keys(R.actors).forEach(function (id) { if (R.actors[id].inp._vjob) jobs.push(R.actors[id].inp._vjob); }); else if (m && m._vjob) jobs.push(m._vjob);      // (a voice still being kept is waited for)
    exp.making = Promise.all(jobs).then(function () {
      return Vd.make(app, { size: size, key: key, skip: skip, stop: stop, progress: function (f, w) { if (!shown()) { if (!stop.now) { stop.now = true; app.toast('Stopped. No video was saved.'); } return; } bar.style.width = Math.round(f * 100) + '%'; words.textContent = w; } });
    }).then(function (r) {
      if (!r) return null;
      if (shown()) app.closeSheet();
      return Promise.resolve(AL.intake.saveFile(name, r.blob)).then(function () { return r; });
    }).catch(function (e) {
      if (root.console) console.warn('video', e);
      if (stop.now) return null;
      app.sheet('Export', ui.note('The video could not be made here. Try a smaller size, or Chrome or Edge on a computer. Nothing was saved.'), [{ label: 'OK', cls: 'quiet', fn: app.closeSheet }]);
      return null;
    });
    return exp.making;
  };
})(typeof self !== 'undefined' ? self : this);
