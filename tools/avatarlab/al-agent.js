/* AvatarLab: THE ROOM AS TOOLS. One list of named acts, for Joybot and for any model the owner connects.
 *
 * Everything a person does in this room by tapping is one plain function underneath. This file gives those functions
 * names, a sentence each, and their arguments, in the shape every tool-calling model reads (Claude, OpenAI, a model
 * behind OpenCode or OpenRouter): { name, description, input_schema }. Nothing here is a second way of doing things:
 * each tool calls the same function its button calls, so what an agent builds is what a person can then pick up,
 * undo and tinker with.
 *
 *   AL.agent.manifest()        the tools, ready to hand to a model
 *   AL.agent.run(name, args)   do one; resolves { ok, result } or { ok: false, error } in plain words. Never throws.
 *   AL.agent.describe()        what is open right now, small enough to send every turn
 *
 * THE PUPPET-FROM-A-RENDER PATH (the owner's example: "here is my character render, cut it up and make it a puppet"):
 *   1. the model looks at the render and cuts it into parts itself (each part a PNG on the full canvas, transparent elsewhere)
 *   2. character_new, then character_add_part once per part with its zone: the room finds joints and pins as it does for a person
 *   3. describe, to see what the room made; then the same tools a person has (fit, stances, scene) to finish it
 *   4. the person plays with it and asks for changes, in words
 * What the room does NOT do is the cutting: that is the model's eyes. On the phone the BOT-6 bridge carries these calls.
 *
 * Pictures come in as data URLs (or any URL the page may read). People and items are named by their NAME, not an id.
 */
(function (root) {
  'use strict';
  var AL = root.AL, agent = AL.agent = {}, app = AL.app, scene = AL.scene, H = scene.H;
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function need(c, msg) { if (!c) throw new Error(msg); }
  function R() { need(app.scene, 'No scene is open. Use scene_new or scene_open first.'); if (app.scene.dive) scene.undive(app); return app.scene; }
  function itemBy(name) { var Rr = R(), q = String(name || '').toLowerCase(), l = Rr.data.items.filter(function (it) { return it.id === name || it.name.toLowerCase() === q; }); need(l.length, 'Nothing in this scene is called "' + name + '". It has: ' + Rr.data.items.map(function (it) { return it.name; }).join(', ') + '.'); return l[0]; }
  function pictureOf(url) { return new Promise(function (res, rej) { var im = new Image(); im.crossOrigin = 'anonymous'; im.onload = function () { var c = document.createElement('canvas'); c.width = im.width; c.height = im.height; c.getContext('2d').drawImage(im, 0, 0); res(c); }; im.onerror = function () { rej(new Error('That picture could not be read.')); }; im.src = url; }); }
  function shotKey(name) { var Rr = R(), q = String(name || 'wide').toLowerCase(), k = null; scene.shots(app).forEach(function (s) { var full = (s.kind === 'close' ? 'close on ' + s.name : s.name).toLowerCase(); if (s.key === name || full === q || (s.kind === 'two' && /^(two|two-shot|everyone)$/.test(q))) k = s.key; }); need(k, 'No shot is called "' + name + '". There are: ' + scene.shots(app).map(function (s) { return s.kind === 'close' ? 'Close on ' + s.name : s.name; }).join(', ') + '.'); return k; }
  function actor(name) { var Rr = R(), it = name ? itemBy(name) : scene.item(Rr, Rr.focus); need(it && it.kind === 'cast' && Rr.actors[it.id], (name || 'That') + ' is not a character in this scene.'); return { it: it, a: Rr.actors[it.id] }; }
  var S = { type: 'string' }, N = { type: 'number' }, B = { type: 'boolean' };

  var T = agent.list = [
    { name: 'describe', description: 'What is open right now: the scene (set, frame, everything in it, who is in hand, the shot, takes) or the character (its parts, stances, buttons).', props: {}, fn: function () { return agent.describe(); } },

    /* scenes */
    { name: 'scene_new', description: 'Create a scene on one of the sets that come with the room. The character that is open walks in with it.', props: { set: { type: 'string', enum: Object.keys(scene.SETS) }, name: S }, fn: function (a) { return scene.create(app, a.set || 'meadow', a.name).then(agent.describe); } },
    { name: 'scene_open', description: 'Open one of the scenes already made, by its name.', props: { name: S }, required: ['name'], fn: function (a) { return scene.list(app).then(function (l) { var e = l.filter(function (q) { return q.name.toLowerCase() === String(a.name).toLowerCase(); })[0]; need(e, 'No scene is called "' + a.name + '". There are: ' + (l.map(function (q) { return q.name; }).join(', ') || 'none') + '.'); return scene.openId(app, e.uid).then(agent.describe); }); } },
    { name: 'scene_add_character', description: 'Put a character on the stage: one that comes with the room (topps, pip) or one of the owner\'s by name.', props: { name: S }, required: ['name'], fn: function (a) { R(); var q = String(a.name).toLowerCase(); if (/^(topps|pip|joybot)$/.test(q)) return scene.addCast(app, { stock: q, name: a.name }).then(agent.describe); return AL.shelf.list(app).then(function (l) { var e = l.filter(function (c) { return (c.name || '').toLowerCase() === q; })[0]; need(e, 'No character is called "' + a.name + '". The shelf has: ' + (l.map(function (c) { return c.name; }).join(', ') || 'none') + ', and topps and pip come with the room.'); return scene.addCast(app, { uid: e.uid, name: e.name }).then(agent.describe); }); } },
    { name: 'scene_add_picture', description: 'Put a picture in the scene. kind: backdrop (behind everything, fills the stage), object (stands on the floor), foreground (in front of everything). Several pictures for one object play as a loop.', props: { kind: { type: 'string', enum: ['backdrop', 'object', 'foreground'] }, pictures: { type: 'array', items: S, description: 'data URLs' }, name: S }, required: ['kind', 'pictures'], fn: function (a) { R(); var k = { backdrop: 'back', object: 'obj', foreground: 'front' }[a.kind]; need(k, 'kind must be backdrop, object or foreground.'); return Promise.all((a.pictures || []).map(pictureOf)).then(function (cs) { var it = scene.addPictures(app, k, k === 'obj' ? cs : cs.slice(0, 1), a.name); need(it, 'No picture came through.'); return agent.describe(); }); } },
    { name: 'scene_place', description: 'Move or change one thing in the scene, by name. x is 0 (left edge of the stage) to 1920. back is how far back it stands: 0 at the front of the floor, 100 at the back (it shrinks and goes behind things in front). size is a percentage. sway is 0 to 100 (objects, in the wind).', props: { item: S, x: N, back: N, size: N, flip: B, sway: N, name: S }, required: ['item'], fn: function (a) { var Rr = R(), it = itemBy(a.item), f = Rr.data.floor, w = {}; if (a.x != null) w.x = Math.round(a.x); if (a.back != null) w.y = Math.round(H * (f.near - clamp(a.back, -15, 110) / 100 * (f.near - f.far))); if (a.size != null) w.s = clamp(a.size, 5, 800) / 100; if (a.flip != null) w.flip = !!a.flip; if (a.sway != null) w.sway = clamp(a.sway, 0, 100) / 100; if (a.name) w.name = String(a.name); scene.set(app, it.id, w, 'Place ' + it.name); return agent.describe(); } },
    { name: 'scene_remove', description: 'Take one thing out of the scene, by name.', props: { item: S }, required: ['item'], fn: function (a) { scene.remove(app, itemBy(a.item).id); return agent.describe(); } },
    { name: 'scene_frame', description: 'The shape of the picture and its margins. Changing the shape moves nothing: the frame is a window on the same stage.', props: { shape: { type: 'string', enum: scene.SHAPES.map(function (q) { return q[0]; }) }, margins: { type: 'string', enum: ['off', 'title', 'social'] } }, fn: function (a) { R(); if (a.shape) scene.shape(app, a.shape); if (a.margins) scene.edit(app, 'Margins', function (d) { d.safe = a.margins; }); return agent.describe(); } },
    { name: 'scene_set', description: 'The set behind everything, and the air: wind (0 to 100, on things that sway) and haze (0 to 100, distance).', props: { set: { type: 'string', enum: Object.keys(scene.SETS) }, wind: N, haze: N }, fn: function (a) { R(); if (a.set) scene.setSet(app, a.set); if (a.wind != null || a.haze != null) scene.edit(app, 'The air', function (d) { if (a.wind != null) d.wind = clamp(a.wind, 0, 100) / 100; if (a.haze != null) d.haze = clamp(a.haze, 0, 100) / 100; }); return agent.describe(); } },
    { name: 'scene_stage', description: 'arrange (things hold still to be placed) or perform (everything is alive).', props: { stage: { type: 'string', enum: ['arrange', 'perform'] } }, required: ['stage'], fn: function (a) { R(); scene.go(app, a.stage === 'perform' ? 'perform' : 'arrange'); return agent.describe(); } },
    { name: 'scene_take_in_hand', description: 'Choose which character is being performed: fingers, voice and recording are theirs.', props: { character: S }, required: ['character'], fn: function (a) { var q = actor(a.character); scene.hand(app, q.it.id); return agent.describe(); } },
    { name: 'scene_shot', description: 'Send the camera to a shot: Wide, Two-shot, "Close on <character>", or a shot the owner kept. glide = seconds to ease there; leave it out to cut.', props: { shot: S, glide: N }, required: ['shot'], fn: function (a) { var Rr = R(); if (Rr.stage !== 'perform') scene.go(app, 'perform'); scene.shot(app, shotKey(a.shot), clamp(a.glide || 0, 0, 8)); return agent.describe(); } },
    { name: 'scene_record', description: 'Start or stop recording the character in hand. Everyone else plays back in step.', props: { on: B }, required: ['on'], fn: function (a) { var Rr = R(); if (Rr.stage !== 'perform') scene.go(app, 'perform'); if ((scene.running(app) === 'rec') !== !!a.on) scene.rec(app); return agent.describe(); } },
    { name: 'scene_play', description: 'Play or stop the whole scene.', props: { on: B }, required: ['on'], fn: function (a) { var Rr = R(); if (Rr.stage !== 'perform') scene.go(app, 'perform'); if (!!scene.running(app) !== !!a.on) scene.play(app); return agent.describe(); } },

    /* performing by word: an agent can puppeteer, and what it does is recorded like a hand would be */
    { name: 'perform', description: 'Move a character as a puppeteer would. mood: ' + Object.keys(AL.MOODS).join(', ') + '. look: where the head turns, x and y from -1 to 1. mouth: 0 shut to 1 open. button: the name of one of its buttons, switched by "on". blink: true to blink now.', props: { character: S, mood: S, look: { type: 'object', properties: { x: N, y: N } }, mouth: N, button: S, on: B, blink: B }, fn: function (a) {
      var Rr = app.scene, inp, ch; if (Rr && !Rr.dive) { if (Rr.stage !== 'perform') scene.go(app, 'perform'); var q = actor(a.character); inp = q.a.inp; ch = q.a.ch; } else { if (app.mode !== 'perform') app.setMode('perform'); inp = app.inp; ch = app.ch; }
      if (a.mood) { need(AL.MOODS[a.mood], 'There is no mood called "' + a.mood + '".'); inp.setMood(a.mood); }
      if (a.look) { inp.pose.turnX = clamp(a.look.x || 0, -1, 1); inp.pose.turnY = clamp(a.look.y || 0, -1, 1); inp.held.head = true; }
      if (a.mouth != null) inp.pose.mouth = clamp(a.mouth, 0, 1);
      if (a.blink) inp.blinkNow();
      if (a.button) { var t = (ch.triggers || []).filter(function (x) { return x.name.toLowerCase() === String(a.button).toLowerCase(); })[0]; need(t, ch.name + ' has no button called "' + a.button + '". It has: ' + ((ch.triggers || []).map(function (x) { return x.name; }).join(', ') || 'none') + '.'); inp.setTrig(t.id, a.on !== false); }
      return agent.describe();
    } },

    /* characters */
    { name: 'character_open', description: 'Open a character: topps or pip (they come with the room), or one of the owner\'s by name. Leaves any scene (it is kept).', props: { name: S }, required: ['name'], fn: function (a) { var q = String(a.name).toLowerCase(), left = app.scene ? scene.close(app, true) : Promise.resolve(); return left.then(function () { if (/^(topps|pip|joybot)$/.test(q)) return AL.intake.builtIn(app, q); return AL.shelf.list(app).then(function (l) { var e = l.filter(function (c) { return (c.name || '').toLowerCase() === q; })[0]; need(e, 'No character is called "' + a.name + '".'); return AL.shelf.open(app, e.uid); }); }).then(agent.describe); } },
    { name: 'character_new', description: 'Start an empty character to build from parts. width and height are the size of the whole drawing the parts were cut from.', props: { name: S, width: N, height: N }, required: ['name'], fn: function (a) { var left = app.scene ? scene.close(app, true) : Promise.resolve(); return left.then(function () { AL.img.map = {}; app.setMode('build'); app.setCharacter(AL.newCharacter(String(a.name), Math.round(a.width || 512), Math.round(a.height || 512))); return agent.describe(); }); } },
    { name: 'character_add_part', description: 'Add one cut-out part to the open character. zone: ' + AL.ZONES.map(function (z) { return z.id; }).join(', ') + '. The picture is the part on the FULL canvas (same size as the whole drawing, transparent everywhere else), so it lands where it belongs. Several pictures for one zone are its drawings (arm poses, head angles). The room finds joints and pins itself.', props: { zone: { type: 'string', enum: AL.ZONES.map(function (z) { return z.id; }) }, pictures: { type: 'array', items: S, description: 'data URLs' }, name: S }, required: ['zone', 'pictures'], fn: function (a) { need(!app.scene || app.scene.dive, 'A scene is open. Open the character first (character_open or character_new).'); need(AL.ZONES.some(function (z) { return z.id === a.zone; }), 'There is no zone called "' + a.zone + '".'); return Promise.all((a.pictures || []).map(pictureOf)).then(function (cs) { need(cs.length, 'No picture came through.'); var pics = cs.map(function (c, i) { return AL.intake.trim(c, (a.name || a.zone) + (cs.length > 1 ? ' ' + (i + 1) : '')); }).filter(Boolean); need(pics.length, 'Those pictures were empty.'); app.edit(function () { AL.intake.place(app, a.zone, pics); }, false, 'Add ' + (a.name || a.zone)); return agent.describe(); }); } }
  ];

  /** The tools in the shape a tool-calling model is given. */
  agent.manifest = function () { return T.map(function (t) { return { name: t.name, description: t.description, input_schema: { type: 'object', properties: t.props || {}, required: t.required || [] } }; }); };
  agent.tools = agent.manifest;
  /** Do one. Never throws: what went wrong comes back as a sentence the model (or Joybot) can read and act on. */
  agent.run = function (name, args) {
    var t = T.filter(function (q) { return q.name === name; })[0];
    if (!t) return Promise.resolve({ ok: false, error: 'There is no tool called "' + name + '". The tools are: ' + T.map(function (q) { return q.name; }).join(', ') + '.' });
    var miss = (t.required || []).filter(function (k) { return !args || args[k] == null; });
    if (miss.length) return Promise.resolve({ ok: false, error: name + ' needs: ' + miss.join(', ') + '.' });
    return Promise.resolve().then(function () { return t.fn(args || {}); }).then(function (r) { return { ok: true, result: r }; }, function (e) { return { ok: false, error: String(e && e.message || e) }; });
  };
  /** What is open, in a few hundred characters. */
  agent.describe = function () {
    var Rr = app.scene, ch = app.ch;
    if (Rr && !Rr.dive) {
      var d = Rr.data, f = d.floor, made = d.items.filter(function (q) { return q.made; })[0];
      return { open: 'scene', name: d.name, stage: Rr.stage, frame: d.shape, margins: d.safe || 'off', set: made ? made.made : null, wind: Math.round((d.wind == null ? 0.5 : d.wind) * 100), haze: Math.round((d.haze == null ? 0.5 : d.haze) * 100),
        things: d.items.map(function (it) { var o = { name: it.name, kind: { back: 'backdrop', obj: 'object', front: 'foreground', cast: 'character' }[it.kind] }; if (it.kind === 'obj' || it.kind === 'cast') { o.x = it.x; o.back = Math.round((f.near - it.y / H) / (f.near - f.far) * 100); } o.size = Math.round((it.s || 1) * 100); if (it.flip) o.flip = true; if (it.sway) o.sway = Math.round(it.sway * 100); var a = Rr.actors[it.id]; if (a) { o.take_seconds = Math.round(a.inp.duration() * 10) / 10; o.buttons = (a.ch.triggers || []).map(function (t) { return t.name; }); } if (it.lost) o.missing = true; return o; }),
        in_hand: Rr.focus && scene.item(Rr, Rr.focus) ? scene.item(Rr, Rr.focus).name : null, shot: scene.shotName(app, Rr.shot), shots: scene.shots(app).map(function (s) { return s.kind === 'close' ? 'Close on ' + s.name : s.name; }), camera_changes: d.cam.length, running: scene.running(app), seconds: Math.round(scene.length(app) * 10) / 10 };
    }
    if (!ch) return { open: 'nothing' };
    var parts = []; AL.ZONES.forEach(function (z) { var n = app.zoneNode(z.id); if (n) parts.push({ zone: z.id, name: n.name, drawings: AL.findAll(n, function (k) { return k.type === 'art'; }).length }); });
    return { open: 'character', name: ch.name, size: [ch.w, ch.h], stage: app.mode === 'perform' ? 'perform' : (app.stage || 'setup'), in_scene: Rr ? Rr.data.name : null, parts: parts, stances: (ch.poses || []).map(function (p) { return p.name; }), buttons: (ch.triggers || []).map(function (t) { return t.name; }), colour_sets: (ch.palettes || []).map(function (p) { return p.name; }), take_seconds: app.inp ? Math.round(app.inp.duration() * 10) / 10 : 0 };
  };
})(typeof self !== 'undefined' ? self : this);
