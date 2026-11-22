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
      out.push(ui.chip(t.name, { on: !!inp.trig[t.id], icon: 'bolt', title: 'Tap to switch “' + t.name + '” on and off' + (i < 9 ? ' (key ' + (i + 1) + ')' : ''), fn: function () { inp.toggleTrig(t.id); app.refreshPanel(true); } }));
    });
    var bl = ui.chip('Blink', { icon: 'eye', title: 'Tap to blink. Hold to keep the eyes shut. (key B)' });
    bl.addEventListener('pointerdown', function (e) { try { bl.setPointerCapture(e.pointerId); } catch (x) { } inp.blinkHold = true; bl.classList.add('on'); });
    ['pointerup', 'pointercancel'].forEach(function (ev) { bl.addEventListener(ev, function () { inp.blinkHold = false; bl.classList.remove('on'); }); });
    out.push(bl);
    return out;
  }

  function morePads(app, box, sets) {
    if (sets.length < 2) return;
    box.appendChild(app.ui.lab('More to turn'));
    box.appendChild(h('div', { class: 'rowf', style: 'align-items:flex-start;gap:10px' }, sets.slice(1).map(function (st) { return h('div', null, [AL.cards.turnPad(app, { set: st.id }), h('div', { class: 'badge', style: 'margin-top:4px;display:inline-block', text: st.name })]); })));
  }
  function hasSwing(app) { return !!AL.find(app.ch.root, function (n) { return n.swing && n.swing.on !== false; }); }
  function moodChips(app) {
    return Object.keys(AL.MOODS).map(function (m) { return app.ui.chip(m, { on: app.inp.pose.mood === m, title: 'Eyelids and brows: ' + m.toLowerCase(), fn: function () { app.inp.setMood(m); app.refreshPanel(true); } }); });
  }
  function breeze(app) {
    return app.ui.slider('Breeze', { min: -1, max: 1, step: 0.05, value: app.inp.pose.wind, undo: false, set: function (v) { app.inp.pose.wind = Math.abs(v) < 0.06 ? 0 : v; app.inp.held.wind = true; clearTimeout(app._wt); app._wt = setTimeout(function () { app.inp.held.wind = false; }, 600); },
      fmt: function (v) { return Math.abs(v) < 0.06 ? 'still' : (v < 0 ? '\u2190 ' : '') + Math.round(Math.abs(v) * 100) + (v > 0 ? ' \u2192' : ''); }, title: 'Wind. Hair, feathers, tails and chains with Breeze turned on lean and wave in it.' });
  }

  /** The few controls shown under "Try it" while building. */
  perform.quick = function (app) {
    var box = h('div');
    var sets = AL.cards.turnSets(app);
    box.appendChild(h('div', { class: 'padrow' }, [sets[0] ? AL.cards.turnPad(app, { set: sets[0].id }) : null, h('div', { class: 'side' }, [talkButton(app), h('div', { class: 'rowf' }, triggerChips(app))])]));
    morePads(app, box, sets);
    if (AL.hasLids(app.ch)) { box.appendChild(app.ui.lab('Mood')); box.appendChild(app.ui.row(moodChips(app))); }
    if (hasSwing(app)) box.appendChild(breeze(app));
    box.appendChild(app.ui.note('This is the same as <b>Perform</b>, kept beside the part you were working on.'));
    return box;
  };

  perform.render = function (app) {
    var ph = $('panelhead'), pb = $('panelbody'), inp = app.inp, ui = app.ui, st = app.ch.settings;
    $('stagebar').innerHTML = ''; ph.innerHTML = ''; pb.innerHTML = '';
    app.coach(inp.recording ? '<b>Recording.</b> ' + (inp.fresh ? 'Everything you do is kept.' : 'Only what you touch is replaced.') + ' Tap the square to stop.' : (inp.walker.cfg() ? '<b>Perform.</b> Drag the <b>body</b> and he walks. Drag the <b>head</b>, a <b>hand</b> or a <b>foot</b> with other fingers at the same time. Two fingers on empty space zoom.' : '<b>Perform.</b> Drag the <b>head</b> to turn, a <b>hand</b> to reach, the <b>body</b> to lean. Double-tap lets go.'));

    /* transport: the one take bar. In a scene with several characters it is the scene's clock: red records the one in hand while the
       others play their takes back in step, and Play plays everything (al-scene-take.js). */
    var hasTake = !!(inp.take && inp.take.n), sc = perform.sceneClock(app), run = sc ? AL.scene.running(app) : null;
    var rec = h('button', { class: 'recb' + (inp.recording ? ' on' : ''), title: inp.recording ? 'Stop recording' : sc ? 'Record ' + app.ch.name + '. Everyone else plays back in step.' : (hasTake ? 'Record over the take: only what you touch is replaced, and it is handed back when you let go' : 'Record a take'), 'aria-pressed': String(inp.recording), onclick: function () { if (sc) { AL.scene.rec(app); return; } if (inp.recording) inp.stop(); else inp.record(); app.refreshPanel(true); } }, h('i'));
    var tlc = h('canvas'), tl = h('div', { class: 'tl', tabindex: '0', role: 'slider', 'aria-label': 'Place in the take', 'aria-valuemin': '0', 'aria-valuemax': String(Math.round(inp.duration() * 10) / 10), 'aria-valuenow': String(Math.round(inp.ph * 10) / 10), title: 'The take. Drag, or use the left and right arrow keys, to look through it.' }, tlc);
    var playing = sc ? run === 'play' : inp.playing && !inp.recording;
    var play = h('button', { class: 'ib' + (playing ? ' on' : ''), title: playing ? 'Stop' : sc ? 'Play the whole scene' : 'Play the take', disabled: sc ? !(run || AL.scene.length(app) > 0) : !hasTake, onclick: function () { if (sc) { AL.scene.play(app); return; } if (inp.playing) inp.stop(); else inp.play(); inp.scrub = false; app.refreshPanel(true); } }, AL.icon(playing ? 'stop' : 'play'));
    var back = h('button', { class: 'ib', title: 'Take back the last recording pass', disabled: !inp.backup || inp.recording, onclick: function () { if (inp.undoPass()) { app.toast('The last pass was taken back'); app.refreshPanel(true); } } }, AL.icon('undo'));
    var clr = h('button', { class: 'ib', title: 'Throw this take away', disabled: !hasTake, onclick: function () { app.ask('Throw this take away?', 'The recorded performance is removed. The character is not touched.', 'Throw away', function () { inp.clearTake(); inp.scrub = false; app.refreshPanel(); }, true); } }, AL.icon('trash'));
    ph.appendChild(h('div', { class: 'transport', style: 'flex:1;min-width:0' }, [rec, tl, play, back, clr]));
    /* the strip stays put while it is dragged: rebuilding it under the finger is what lost the drag */
    function scrubAt(x) { if (!inp.take || !inp.take.n || inp.recording) return; var r = tl.getBoundingClientRect(); inp.playing = false; inp.scrub = true; inp.ph = Math.max(0, Math.min(0.999, (x - r.left - 4) / (r.width - 8))) * inp.duration(); tl.setAttribute('aria-valuenow', String(Math.round(inp.ph * 10) / 10)); }
    tl.addEventListener('pointerdown', function (e) { try { tl.setPointerCapture(e.pointerId); } catch (x) { } perform.scrubbing = true; scrubAt(e.clientX); });
    tl.addEventListener('pointermove', function (e) { if (perform.scrubbing) scrubAt(e.clientX); });
    ['pointerup', 'pointercancel'].forEach(function (ev) { tl.addEventListener(ev, function () { if (!perform.scrubbing) return; perform.scrubbing = false; app.refreshPanel(true); }); });
    tl.addEventListener('keydown', function (e) { if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return; e.preventDefault(); if (!inp.take || !inp.take.n) return; inp.playing = false; inp.scrub = true; inp.ph = Math.max(0, Math.min(inp.duration() - 0.001, inp.ph + (e.key === 'ArrowLeft' ? -0.1 : 0.1))); });
    perform.tl = tlc;

    /* hands-on controls */
    var sets = AL.cards.turnSets(app);
    var meter = h('div', { class: 'meter', title: 'How loud the voice is' }, h('i')); perform.meter = meter.firstChild;
    var hosted = AL.intake.hosted, stk = !!(AL.stick && AL.stick.open()), ico = stk ? 'ico' : '';      // (the joystick open: these become their pictures, al-stick.js)
    var voice = h('div', { class: 'rowf' + (stk ? ' ico' : '') }, [
      hosted ? null : ui.chip('Mic', { cls: ico, on: inp.mic.on && inp.mic.kind === 'mic', icon: 'mic', title: 'Lip-sync to your voice', fn: function () { if (inp.mic.on) { inp.micStop(); app.refreshPanel(true); } else inp.micStart().then(function () { app.refreshPanel(true); }).catch(function () { app.toast('No microphone here. Hold Talk, or play a voice file.'); }); } }),
      ui.chip('Voice file', { cls: ico, on: inp.mic.on && inp.mic.kind === 'file', icon: 'note', title: 'Play a recording and lip-sync to it', fn: function () { if (inp.mic.kind === 'file') { inp.micStop(); app.refreshPanel(true); } else $('fileAudio').click(); } }),
      hosted ? null : ui.chip(!inp.cam.on ? 'Face' : inp.cam.mode === 'always' ? 'Face: always' : 'Face: while talking', { cls: ico, on: inp.cam.on, icon: 'cam', title: 'Drive the head, eyes and mouth with your own face. Tap again to change between "while Talk is held" and "always", then off. The picture is never shown or kept.', fn: function () {
        if (!inp.cam.on) { app.toast('Starting the face camera\u2026'); inp.cam.mode = 'talk'; inp.camStart().then(function () { app.toast('Face is ready. Hold Talk and it takes over from where your hand left the head.'); app.refreshPanel(true); }).catch(function () { app.toast('The face camera could not start here. It needs a webcam and the PC page.'); }); }
        else if (inp.cam.mode === 'talk') { inp.cam.mode = 'always'; inp.cam.zero = null; app.refreshPanel(true); }
        else { inp.camStop(); app.refreshPanel(true); }
      } })
    ]);
    if (inp.cam.on) voice.appendChild(ui.chip('Re-centre', { title: 'Take the way you are facing now as straight ahead', fn: function () { inp.cam.zero = null; } }));
    if (inp.cam.on) voice.appendChild(ui.chip('Swap sides', { on: inp.cam.swap, title: 'If the character turns the wrong way, swap left and right', fn: function () { inp.cam.swap = !inp.cam.swap; app.refreshPanel(true); } }));
    if (AL.stick) { var jc = AL.stick.chip(app); if (stk) jc.classList.add('ico'); voice.appendChild(jc); }
    pb.appendChild(h('div', { class: 'padrow' + (stk ? ' stick' : '') }, [sets[0] ? AL.cards.turnPad(app, { set: sets[0].id }) : null, h('div', { class: 'side' }, [talkButton(app), meter, voice]), stk ? AL.stick.pad(app) : null]));
    morePads(app, pb, sets);

    if (hosted) pb.appendChild(ui.note('The microphone and the face camera work on the PC page (tools/avatarlab). Here, hold Talk or play a voice file.'));
    pb.appendChild(ui.lab('Buttons'));
    pb.appendChild(ui.row(triggerChips(app).concat([
      ui.chip('Let go of everything', { cls: 'quiet', title: 'Head to the middle, hands to rest', fn: function () { inp.resetPose(); inp.S.pin = {}; app.refreshPanel(true); } })
    ])));

    if (inp.walker.travels()) { pb.appendChild(ui.lab(inp.walker.cfg() ? 'Walking' : 'Floating')); AL.cards.walkControls(app, pb, true); }
    if (AL.find(app.ch.root, function (n) { return n.ball && n.ball.on !== false && n.ball.spin; })) pb.appendChild(ui.slider('Turn right round', { min: -100, max: 100, step: 1, value: Math.round((inp.pose.spin || 0) * 100), undo: false, set: function (v) { inp.pose.spin = Math.abs(v) < 4 ? 0 : v / 100; inp.held.body = true; clearTimeout(inp._spt); inp._spt = setTimeout(function () { inp.held.body = false; }, 500); }, fmt: function (v) { return Math.abs(v) < 4 ? 'front' : Math.round(Math.abs(v) * 1.8) + '\u00b0 ' + (v < 0 ? 'left' : 'right'); }, title: 'Spin the whole character round: to its side, to its back' }));
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
      ui.chip('Export', { icon: 'export', title: 'Export: the Joy Creator file (this character and its take, for Studio and the rest of Joy Creator), or one frame as a picture', fn: function () { AL.intake.send(app); } }),
      ui.chip('Sprite sheet', { icon: 'grid', title: 'Bake the take to a sprite sheet for Sprite Lab, VisLab and Studio', fn: function () { AL.intake.bakeSheet(app); } })
    ]));
  };

  /** In a scene with more than one character the take bar runs the scene's clock. */
  perform.sceneClock = function (app) { var S = AL.scene; return !!(S && S.on(app) && app.scene.stage === 'perform' && S.cast(app.scene).length > 1); };

  /* per frame: the take strip and the voice meter */
  AL.app.hooks.push(function () {
    var app = AL.app, inp = app.inp; (app.padDraws || []).forEach(function (d) { d(); });
    if (app.mode !== 'perform') return;
    if (perform.meter) perform.meter.style.width = Math.round((inp.S.mouth || 0) * 100) + '%';
    var c = perform.tl; if (!c || !c.parentNode) return;
    var w = c.parentNode.clientWidth, hh = c.parentNode.clientHeight, dpr = root.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(hh * dpr); }
    var g = c.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, hh);
    var tk = inp.take, dur = inp.duration(), lanes = AL.TAKE_LANES, lh = (hh - 8) / lanes.length;
    g.font = '500 10px "IBM Plex Mono",monospace'; g.textBaseline = 'middle';
    if (perform.sceneClock(app) && app.scene.clock.on && !inp.recording && !inp.playing && !inp.scrub) {      // the scene playing, led by its clock (the one in hand has no take of its own)
      var SL = Math.max(AL.scene.length(app), 0.001), sp = 4 + (w - 8) * Math.min(1, app.scene.clock.ph / SL);
      g.fillStyle = '#22d3ee'; g.fillRect(sp - 1, 2, 2, hh - 4); g.fillStyle = '#e4e4e7'; g.textAlign = 'right'; g.fillText('scene ' + app.scene.clock.ph.toFixed(1) + ' / ' + SL.toFixed(1) + ' s', w - 8, hh - 9); g.textAlign = 'left';
      if (!tk || !tk.n) return;
    }
    if (!tk || !tk.n) { g.fillStyle = '#71717a'; g.fillText(inp.recording ? 'recording\u2026' : 'No take yet. Tap the red dot, then perform.', 10, hh / 2); if (!inp.recording) return; }
    var total = Math.max(dur, inp.recording ? inp.ph : 0, 0.001), len = {}, hot = {}, known = {}, k;
    lanes.forEach(function (l) { known[l[0]] = 1; });
    if (tk) for (k in tk.ch) { var o = inp.ownerOf(k), ln = known[o] ? o : (o === 'footL' || o === 'footR' ? 'body' : 'other'); len[ln] = Math.max(len[ln] || 0, tk.ch[k].length); if (inp.recording && inp.fade && inp.fade[o] > 0) hot[ln] = 1; }
    lanes.forEach(function (l, i) {
      if (!len[l[0]]) return;
      g.fillStyle = hot[l[0]] ? '#f43f8e' : '#3a3a46'; g.beginPath(); g.roundRect(4, 4 + i * lh + 0.5, Math.max(3, (w - 8) * Math.min(1, (len[l[0]] / tk.fps) / total)), Math.max(1.5, lh - 1), 1.5); g.fill();
    });
    var px = 4 + (w - 8) * Math.min(1, inp.ph / total);
    g.fillStyle = inp.recording ? '#f43f8e' : '#22d3ee'; g.fillRect(px - 1, 2, 2, hh - 4);
    var names = lanes.filter(function (l) { return hot[l[0]]; }).map(function (l) { return l[1]; });
    g.fillStyle = '#e4e4e7'; g.textAlign = 'right'; g.fillText(inp.ph.toFixed(1) + ' / ' + total.toFixed(1) + ' s', w - 8, hh - 9);
    if (names.length) { g.fillStyle = '#f43f8e'; g.fillText('writing: ' + names.join(', '), w - 8, 9); }
    g.textAlign = 'left';
    if (perform._rec !== inp.recording || perform._play !== inp.playing) { var changed = perform._rec !== undefined; perform._rec = inp.recording; perform._play = inp.playing; if (changed && !perform.scrubbing) app.refreshPanel(true); }
  });

  perform.key = function (app, e, down) {
    var inp = app.inp, k = e.key;
    if (k === ' ') { e.preventDefault(); inp.talking = down; inp.held.face = down; return; }
    if (!down) return;
    var sc = perform.sceneClock(app);
    if (k === 'b' || k === 'B') inp.blinkNow();
    else if (k === 'r' || k === 'R') { if (sc) return AL.scene.rec(app); if (inp.recording) inp.stop(); else inp.record(); app.refreshPanel(true); }
    else if (k === 'p' || k === 'P') { if (sc) return AL.scene.play(app); if (inp.playing) inp.stop(); else inp.play(); app.refreshPanel(true); }
    else if (k >= '1' && k <= '9') { var t = (app.ch.triggers || [])[parseInt(k, 10) - 1]; if (t) { inp.toggleTrig(t.id); app.refreshPanel(true); } }
  };
})(typeof self !== 'undefined' ? self : this);
