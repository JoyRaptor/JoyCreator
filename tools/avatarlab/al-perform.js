/* AvatarLab: the Perform panel. The phone is the puppeteer's hand: drag on the character itself,
 * talk, tap a button, record. A second recording only replaces what you touch, so a performance is
 * built in passes: head first, then the hands, then the mouth.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h;
  function $(id) { return document.getElementById(id); }
  var perform = AL.perform = {};
  var LANES = ['head', 'face', 'handL', 'handR', 'body', 'trig'];

  function talkButton(app) {
    var b = h('button', { class: 'talk', title: 'Hold to make the character chatter (or use the microphone)' }, [AL.icon('smile'), 'Hold to talk']);
    function on(v) { app.inp.talking = v; app.inp.held.face = v; b.classList.toggle('on', v); }
    b.addEventListener('pointerdown', function (e) { b.setPointerCapture(e.pointerId); on(true); });
    b.addEventListener('pointerup', function () { on(false); }); b.addEventListener('pointercancel', function () { on(false); });
    return b;
  }
  function triggerChips(app) {
    var inp = app.inp, ui = app.ui, out = [];
    (app.ch.triggers || []).forEach(function (t, i) {
      out.push(ui.chip(t.name, { on: !!inp.trig[t.id], icon: 'bolt', title: 'Tap to switch “' + t.name + '” on and off' + (i < 9 ? ' (key ' + (i + 1) + ')' : ''), fn: function () { inp.trig[t.id] = !inp.trig[t.id]; app.refreshPanel(true); } }));
    });
    out.push(ui.chip('Blink', { icon: 'eye', title: 'Blink now (key B)', fn: function () { inp.blinkNow(); } }));
    return out;
  }

  function hasSwing(app) { return !!AL.find(app.ch.root, function (n) { return n.swing && n.swing.on !== false; }); }
  function moodChips(app) {
    return Object.keys(AL.MOODS).map(function (m) { return app.ui.chip(m, { on: app.inp.pose.mood === m, title: 'Eyelids and brows: ' + m.toLowerCase(), fn: function () { app.inp.setMood(m); app.refreshPanel(true); } }); });
  }
  function breeze(app) {
    return app.ui.slider('Breeze', { min: -1, max: 1, step: 0.05, value: app.inp.pose.wind, undo: false, set: function (v) { app.inp.pose.wind = Math.abs(v) < 0.06 ? 0 : v; app.inp.held.body = true; clearTimeout(app._wt); app._wt = setTimeout(function () { app.inp.held.body = false; }, 500); },
      fmt: function (v) { return Math.abs(v) < 0.06 ? 'still' : (v < 0 ? '\u2190 ' : '') + Math.round(Math.abs(v) * 100) + (v > 0 ? ' \u2192' : ''); }, title: 'Wind. Hair, feathers, tails and chains with Breeze turned on lean and wave in it.' });
  }

  /** The few controls shown under "Try it" while building. */
  perform.quick = function (app) {
    var box = h('div');
    var hasHead = app.zoneNode('head') && app.zoneNode('head').type === 'set';
    box.appendChild(h('div', { class: 'padrow' }, [hasHead ? AL.cards.turnPad(app, {}) : null, h('div', { class: 'side' }, [talkButton(app), h('div', { class: 'rowf' }, triggerChips(app))])]));
    if (AL.hasLids(app.ch)) { box.appendChild(app.ui.lab('Mood')); box.appendChild(app.ui.row(moodChips(app))); }
    if (hasSwing(app)) box.appendChild(breeze(app));
    box.appendChild(app.ui.note('This is the same as <b>Perform</b>, kept beside the part you were working on.'));
    return box;
  };

  perform.render = function (app) {
    var ph = $('panelhead'), pb = $('panelbody'), inp = app.inp, ui = app.ui, st = app.ch.settings;
    $('stagebar').innerHTML = ''; ph.innerHTML = ''; pb.innerHTML = '';
    app.coach(inp.recording ? '<b>Recording.</b> ' + (inp.fresh ? 'Everything you do is kept.' : 'Only what you touch is replaced.') + ' Tap the square to stop.' : '<b>Perform.</b> Drag the <b>head</b> to turn, a <b>hand</b> to reach, the <b>body</b> to lean. Double-tap lets go.');

    /* transport */
    var rec = h('button', { class: 'recb' + (inp.recording ? ' on' : ''), title: inp.recording ? 'Stop recording' : (inp.take && inp.take.n ? 'Record another pass: only what you touch is replaced' : 'Record a take'), 'aria-pressed': String(inp.recording), onclick: function () { if (inp.recording) inp.stop(); else inp.record(); app.refreshPanel(true); } }, h('i'));
    var tlc = h('canvas'), tl = h('div', { class: 'tl', title: 'The take. Drag to look through it.' }, tlc);
    var play = h('button', { class: 'ib' + (inp.playing && !inp.recording ? ' on' : ''), title: inp.playing ? 'Stop' : 'Play the take', disabled: !(inp.take && inp.take.n), onclick: function () { if (inp.playing) inp.stop(); else inp.play(); inp.scrub = false; app.refreshPanel(true); } }, AL.icon(inp.playing && !inp.recording ? 'stop' : 'play'));
    var clr = h('button', { class: 'ib', title: 'Throw this take away', disabled: !(inp.take && inp.take.n), onclick: function () { app.ask('Throw this take away?', 'The recorded performance is removed. The character is not touched.', 'Throw away', function () { inp.clearTake(); inp.scrub = false; app.refreshPanel(); }, true); } }, AL.icon('trash'));
    ph.appendChild(h('div', { class: 'transport', style: 'flex:1;min-width:0' }, [rec, tl, play, clr]));
    function scrubAt(e) { if (!inp.take || !inp.take.n || inp.recording) return; var r = tl.getBoundingClientRect(); inp.playing = false; inp.scrub = true; inp.ph = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * inp.duration(); }
    tl.addEventListener('pointerdown', function (e) { tl.setPointerCapture(e.pointerId); tl._d = true; scrubAt(e); app.refreshPanel(true); });
    tl.addEventListener('pointermove', function (e) { if (tl._d) scrubAt(e); });
    tl.addEventListener('pointerup', function () { tl._d = false; });
    perform.tl = tlc;

    /* hands-on controls */
    var hasHead = app.zoneNode('head') && app.zoneNode('head').type === 'set';
    var meter = h('div', { class: 'meter', title: 'How loud the voice is' }, h('i')); perform.meter = meter.firstChild;
    var hosted = AL.intake.hosted;
    var voice = h('div', { class: 'rowf' }, [
      hosted ? null : ui.chip('Mic', { on: inp.mic.on && inp.mic.kind === 'mic', icon: 'mic', title: 'Lip-sync to your voice', fn: function () { if (inp.mic.on) { inp.micStop(); app.refreshPanel(true); } else inp.micStart().then(function () { app.refreshPanel(true); }).catch(function () { app.toast('No microphone here. Hold Talk, or play a voice file.'); }); } }),
      ui.chip('Voice file', { on: inp.mic.on && inp.mic.kind === 'file', icon: 'note', title: 'Play a recording and lip-sync to it', fn: function () { if (inp.mic.kind === 'file') { inp.micStop(); app.refreshPanel(true); } else $('fileAudio').click(); } }),
      hosted ? null : ui.chip('Face', { on: inp.cam.on, icon: 'cam', title: 'Drive the head, eyes and mouth with your own face. The picture is never shown or kept.', fn: function () { if (inp.cam.on) { inp.camStop(); app.refreshPanel(true); } else { app.toast('Starting the face camera…'); inp.camStart().then(function () { app.toast('Face camera on. Look at the screen.'); app.refreshPanel(true); }).catch(function () { app.toast('The face camera could not start here. It works on the PC page with a webcam.'); }); } } })
    ]);
    if (inp.cam.on) voice.appendChild(ui.chip('Swap sides', { on: inp.cam.swap, title: 'If the character turns the wrong way, swap left and right', fn: function () { inp.cam.swap = !inp.cam.swap; app.refreshPanel(true); } }));
    pb.appendChild(h('div', { class: 'padrow' }, [hasHead ? AL.cards.turnPad(app, {}) : null, h('div', { class: 'side' }, [talkButton(app), meter, voice])]));

    if (hosted) pb.appendChild(ui.note('The microphone and the face camera work on the PC page (tools/avatarlab). Here, hold Talk or play a voice file.'));
    pb.appendChild(ui.lab('Buttons'));
    pb.appendChild(ui.row(triggerChips(app).concat([
      ui.chip('Let go of everything', { cls: 'quiet', title: 'Head to the middle, hands to rest', fn: function () { inp.resetPose(); inp.S.pin = {}; app.refreshPanel(true); } })
    ])));

    if (AL.hasLids(app.ch)) { pb.appendChild(ui.lab('Mood (eyelids and brows)')); pb.appendChild(ui.row(moodChips(app))); }
    if (hasSwing(app)) { pb.appendChild(ui.lab('Weather')); pb.appendChild(breeze(app)); }
    pb.appendChild(ui.lab('Alive by itself'));
    pb.appendChild(ui.row([
      ui.chip('Show-off loop', { on: inp.auto, icon: 'loop', title: 'The character performs by itself: looks around, waves, talks. Anything you grab is yours.', fn: function () { inp.auto = !inp.auto; if (!inp.auto) inp.resetPose(); app.refreshPanel(true); } }),
      ui.chip('Blinks', { on: st.blinkAuto !== false, fn: function () { app.edit(function () { st.blinkAuto = st.blinkAuto === false; }); } }),
      ui.chip('Breathes', { on: st.breathe !== false, fn: function () { app.edit(function () { st.breathe = st.breathe === false; }); } }),
      ui.chip('Never still', { on: st.idle !== false, fn: function () { app.edit(function () { st.idle = st.idle === false; }); } }),
      st.facing !== 'front' ? ui.chip('Turns around', { on: !!st.autoFlip, icon: 'flip', title: 'Flip the whole character when the head turns the other way', fn: function () { app.edit(function () { st.autoFlip = !st.autoFlip; }); } }) : null
    ]));

    pb.appendChild(ui.lab('Use it'));
    pb.appendChild(ui.row([
      ui.chip('Send to Studio', { cls: 'go', icon: 'send', title: 'Put this character, and its take, into a video', fn: function () { AL.intake.send(app); } }),
      ui.chip('Sprite sheet', { icon: 'grid', title: 'Bake the take to a sprite sheet for Sprite Lab, VisLab and Studio', fn: function () { AL.intake.bakeSheet(app); } })
    ]));
  };

  /* per frame: the take strip and the voice meter */
  AL.app.hooks.push(function () {
    var app = AL.app, inp = app.inp; if (app.padDraw) app.padDraw();
    if (app.mode !== 'perform') return;
    if (perform.meter) perform.meter.style.width = Math.round((inp.S.mouth || 0) * 100) + '%';
    var c = perform.tl; if (!c || !c.parentNode) return;
    var w = c.parentNode.clientWidth, hh = c.parentNode.clientHeight, dpr = root.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(hh * dpr); }
    var g = c.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, hh);
    var tk = inp.take, dur = inp.duration(), lh = (hh - 10) / LANES.length;
    g.font = '500 10px "IBM Plex Mono",monospace'; g.textBaseline = 'middle';
    if (!tk || !tk.n) { g.fillStyle = '#71717a'; g.fillText(inp.recording ? 'recording…' : 'No take yet. Tap the red dot, then perform.', 10, hh / 2); if (!inp.recording) return; }
    var total = Math.max(dur, inp.recording ? inp.ph : 0, 0.001);
    LANES.forEach(function (ln, i) {
      var names = ln === 'trig' ? (app.ch.triggers || []).map(function (t) { return 'g:' + t.id; }) : AL.TAKE_GROUPS[ln], len = 0;
      names.forEach(function (n) { if (tk && tk.ch[n]) len = Math.max(len, tk.ch[n].length); });
      if (!len) return;
      g.fillStyle = inp.recording && inp.wrote[ln] ? '#f43f8e' : '#3a3a46'; g.beginPath(); g.roundRect(4, 5 + i * lh + 1, Math.max(3, (w - 8) * Math.min(1, (len / tk.fps) / total)), Math.max(2, lh - 2), 2); g.fill();
    });
    var px = 4 + (w - 8) * Math.min(1, inp.ph / total);
    g.fillStyle = inp.recording ? '#f43f8e' : '#22d3ee'; g.fillRect(px - 1, 2, 2, hh - 4);
    g.fillStyle = '#e4e4e7'; g.textAlign = 'right'; g.fillText(inp.ph.toFixed(1) + ' / ' + total.toFixed(1) + ' s', w - 8, hh - 9); g.textAlign = 'left';
    if (perform._rec !== inp.recording || perform._play !== inp.playing) { var changed = perform._rec !== undefined; perform._rec = inp.recording; perform._play = inp.playing; if (changed) app.refreshPanel(true); }
  });

  perform.key = function (app, e, down) {
    var inp = app.inp, k = e.key;
    if (k === ' ') { e.preventDefault(); inp.talking = down; inp.held.face = down; return; }
    if (!down) return;
    if (k === 'b' || k === 'B') inp.blinkNow();
    else if (k === 'r' || k === 'R') { if (inp.recording) inp.stop(); else inp.record(); app.refreshPanel(true); }
    else if (k === 'p' || k === 'P') { if (inp.playing) inp.stop(); else inp.play(); app.refreshPanel(true); }
    else if (k >= '1' && k <= '9') { var t = (app.ch.triggers || [])[parseInt(k, 10) - 1]; if (t) { inp.trig[t.id] = !inp.trig[t.id]; app.refreshPanel(true); } }
  };
})(typeof self !== 'undefined' ? self : this);
