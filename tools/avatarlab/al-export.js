/* AvatarLab: EXPORT. Video Studio's export button, in Avatar Studio's colours, where the paper plane was.
 *
 * The mark is Studio's own, path for path: the owner's film-strip export mark in app/src/main/res/drawable/ic_export_studio.xml,
 * shown bare (no pill), 40 px with 8 px round a 24 px mark, as Studio's top bar has it (activity_faditor_editor.xml, btn_export).
 * Only its colours change: the Joybot orb's gradient, #CC27FF to #5C43FD, running the way Studio's runs (bottom right to top left).
 *
 * Its sheet offers what works today:
 *   Joy Creator file   the character with its take, or the scene with its characters, takes, set and camera: picked up live in
 *                      Joy Creator, nothing flattened (in this web pilot it is saved as the file the phone reads)
 *   One frame          a picture at the frame's shape, 1920 on its long side, as the camera sees it now; a tick box for see-through
 *   Video              the take as an MP4 with its voice, at the frame's shape, over the stage or a key colour (al-export-video.js);
 *                      offered only where this browser can make one
 * and, in a scene, a tick box for each character and for the set.
 *
 * Phone: the same vector drawable with the room's gradient in place of Studio's; the sheet is a ModalBottomSheet; one frame is read
 * back from the stage's own GL surface drawn offscreen at the frame's size.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, h = AL.h, scene = AL.scene, frame = AL.frame, NS = 'http://www.w3.org/2000/svg', exp = AL.exporter = {};
  function $(id) { return document.getElementById(id); }
  var PATH = 'm509.99,43.5192c1.556,-0.0342 4.848,-0.0086 5.994,1.0231c5.263,4.7385 10.268,10.3865 15.32,15.3621c38.396,38.1371 76.552,76.5146 114.466,115.1316c11.9,11.927 23.721,23.969 35.663,35.818c3.715,3.686 1.756,9.532 -1.191,12.821c-4.827,4.941 -11.849,12.313 -16.792,16.755c-6.294,5.657 -29.684,33.347 -35.538,33.911c-2.177,0.229 -4.365,-0.321 -6.176,-1.552c-4.321,-2.995 -10.683,-10.968 -14.896,-14.932c-17.489,-16.455 -34.719,-36.533 -52.616,-52.25c0.797,58.519 -0.124,97.477 0.229,156.02c0.153,25.446 -0.656,30.38 0.087,55.693l80.604,-1.883c6.032,0.023 40.116,0.165 46.145,-0.01c17.445,-0.504 15.666,-14.308 15.665,-27.029l-0.023,-3.146l-0.433,-66.248l48.436,2.071c-0.154,8.781 -2.864,25.814 3.892,32.12c1.082,1.01 4.065,3.324 5.579,3.512c6.744,0.835 13.871,0.571 20.693,0.588l12.006,0.042c10.632,-0.063 16.229,-4.081 16.411,-15.589c0.108,-6.799 -0.257,-13.711 -0.445,-20.517c6.774,-0.006 13.835,-0.067 20.661,-0.213c12.505,-0.267 29.239,-1.732 29.516,15.753c0.172,10.83 -0.191,21.88 -0.135,32.793l0.238,93.864l-0.299,272.421l-0.375,161.601l-0.048,46.546c0.006,7.985 0.199,16.299 -0.194,24.236c-0.247,4.984 -7.518,12.765 -12.925,12.936c-9.855,0.311 -19.71,-0.027 -29.578,-0.027l-67.468,0.037l-224.542,0.081l-223.354,0.117l-73.179,-0.013c-11.417,0 -23.002,0.131 -34.423,0.062c-3.431,-0.021 -7.032,-1.16 -9.812,-3.247c-3.532,-2.643 -5.818,-6.625 -6.319,-11.008c-0.624,-5.498 -0.248,-17.91 -0.223,-23.943l0.021,-43.248l0.026,-150.252l-0.017,-272.449l0.15,-88.746c0.013,-16.254 -0.052,-32.562 0.072,-48.819c0.101,-4.761 0.192,-9.458 3.46,-13.317c5.029,-5.971 11.962,-4.827 18.602,-5.161c8.494,-0.427 19.385,1.022 27.558,-0.103l0.069,26.632c3.35,5.209 7.732,9.413 14.272,9.404c20.333,-0.03 12.007,0.573 32.303,0.024c14.388,-0.389 10.967,-26.85 10.899,-36.133l42.848,1.956c0.114,7.64 -0.214,15.219 -0.235,22.846c-1.084,33.33 -0.065,20.663 -0.063,51.994c0,2.907 0.117,5.647 0.346,8.522c0.3,4.639 5.438,11.739 10.334,12.318c8.305,0.981 18.082,0.607 26.509,0.598l111.622,-2.082c-0.542,-18.437 -0.303,-15.702 -0.328,-34.232l-0.025,-76.819l0.106,-58.834c-0.053,-12.04 -0.543,-30.43 0.222,-41.971l-24.717,25.21c-7.192,7.424 -37.002,39.74 -43.879,43.142c-1.817,0.899 -3.558,0.518 -5.375,-0.195c-2.273,-0.892 -4.282,-2.387 -6.101,-3.99c-6.316,-5.565 -12.029,-12.187 -18.006,-18.159c-8.721,-8.715 -18.117,-17.282 -26.287,-26.494c-1.358,-1.532 -2.62,-3.25 -3.396,-5.156c-0.931,-2.287 -0.9,-4.274 0.063,-6.54c0.719,-1.691 1.724,-3.091 2.963,-4.432c24.457,-26.487 51.024,-51.248 76.268,-77l57.921,-58.2898c5.232,-5.2497 26.657,-28.1839 31.174,-29.932zm171.385,749.8858c13.77,0.112 16.168,-9.387 16.175,-21.044l-1.214,-225.374l0.158,-43.713c0.035,-8.031 0.186,-16.249 -0.248,-24.27c-0.286,-5.268 -5.012,-12.561 -10.763,-12.827c-10.681,-0.494 -49.577,-0.259 -60.318,-0.24l-87.013,2.037l-153.952,1.99c-16.205,-0.022 -32.497,-0.115 -48.709,0.017c-0.754,0.006 -1.704,0.119 -2.457,0.198c-10.977,1.55 -12.569,7.55 -12.786,17.796c-0.305,14.448 0.09,29.003 0.135,43.47l1.114,173.985l-0.012,52.287c-0.006,8.576 -0.357,17.296 0.455,25.829c0.682,7.16 6.456,13.117 13.711,13.456c7.807,0.366 15.797,0.288 23.642,0.283l106.955,-2.124l102.153,0.161c18.805,0.022 94.254,-2.235 112.974,-1.917zm4.5,144.201c2.965,0.042 7.486,0.495 9.917,-0.872c1.09,-1.701 0.894,-4.756 0.887,-6.854c-0.076,-23.021 -0.032,-46.029 0.125,-69.049l1.161,12.133c0.009,-5.679 0.269,-12.137 -0.534,-17.709c-0.887,-6.16 -8.339,-11.587 -14.374,-11.71c-9.663,-0.197 -47.315,-0.101 -56.971,-0.078l-89.507,2.263l-159.589,1.745c-14.35,-0.007 -29.738,-0.271 -43.992,0.095c-13.766,3.683 -11.581,19.078 -11.548,29.803l-0.924,-15.209c0.022,25.609 -0.565,53.68 0.12,79.087c9.995,0.597 21.97,0.272 32.159,0.272l118.545,-1.987l214.525,-1.93zm-451.166,-402.818c-17.582,0.615 -13.316,16.027 -13.988,27.31c-1.787,29.999 8.698,27.176 32.475,27.157c12.611,-0.085 -3.777,-0.065 8.834,0.059c4.863,0.022 8.683,-0.176 12.384,-3.964c5.706,-5.966 3.961,-15.226 4.051,-22.775c0.058,-4.882 -0.095,-9.651 0.057,-14.574c0.189,-6.111 -4.197,-12.405 -10.569,-13.108c-7.913,-0.874 -16.23,-0.275 -24.213,-0.442c-12.433,0.2 3.834,-0.356 -8.54,0.306l-0.491,0.031zm517.44,1.133c-8.805,6.578 -7.961,12.031 -7.564,22.522c0.477,12.63 -4.424,29.634 14.27,30.788c19.881,0.158 12.238,0.601 32.115,0.116c14.883,-4.153 11.973,-14.716 12.237,-27.142c0.227,-10.703 1.666,-27.067 -13.354,-26.995c-19.608,0.093 -12.041,-0.976 -31.502,-0.008c-1.933,0.185 -4.334,0.342 -6.202,0.719zm31.976,283.951c4.014,0.027 6.259,-0.048 10.239,-0.479c4.098,-2.921 8.68,-7.229 8.901,-12.699c0.397,-9.187 0.152,-19.234 -0.425,-28.366c-0.523,-8.263 -7.491,-13.239 -15.29,-13.202c-16.637,0.08 -6.532,-0.716 -23.075,0.143c-4.376,0.09 -12.927,-0.043 -16.064,3.869c-6.583,8.932 -2.878,23.592 -3.739,34.215c-0.345,4.258 0.88,8.723 3.977,11.743c6.526,6.365 18.112,4.389 27.259,4.362c12.074,-0.062 -3.852,0.076 8.217,0.414zm8.836,-346.341c4.208,-1.879 7.32,-3.948 8.964,-8.499c2.065,-5.721 2.452,-32.849 -0.216,-38.447c-1.422,-2.984 -4.346,-5.523 -7.472,-6.556c-5.592,-1.848 -28.541,-0.708 -39.12,-0.43c-3.853,1.314 -7.2,2.96 -9.018,6.816c-0.784,1.662 -1.207,3.54 -1.395,5.362c-0.54,5.223 -0.474,29.223 1.045,33.001c1.223,3.04 4.371,6.863 7.572,7.939c5.961,2.004 29.569,0.915 39.64,0.814zm-524.241,-0.058c3.661,-1.559 6.587,-3.166 8.23,-7.054c1.999,-4.73 1.92,-34.985 -0.201,-39.568c-1.45,-3.13 -4.037,-5.529 -7.314,-6.631c-5.742,-1.931 -30.244,-1.089 -39.353,-0.643c-2.858,1.499 -8.404,5.193 -8.611,8.985c-2.293,42.124 -4.832,47.34 40.343,45.016c8.365,-0.43 -3.327,0.271 6.906,-0.105zm-30.348,292.258c-14.991,0.514 -17.798,5.316 -17.89,20.283c-0.057,9.163 -2.386,32.133 10.449,32.875c19.085,1.104 11.335,0.02 30.492,0.566c18.176,0.132 17.264,-10.733 17.055,-25.292c-0.153,-10.661 2.266,-28.387 -13.166,-28.426c-18.317,-0.046 -8.639,-0.622 -26.94,-0.006zm518.506,115.456c-8.685,1.114 -12.999,8.269 -12.327,16.541c0.793,9.749 -2.551,24.479 3.401,32.401c4.836,6.436 15.35,5.003 22.644,4.992l2.889,-0.053c4.017,-0.003 13.555,-0.157 17.237,0.331c15.478,-3.466 11.905,-17.58 12.216,-28.986c0.257,-9.447 0.679,-25.258 -12.58,-25.186c-19.619,0.108 -14.024,-1.142 -33.48,-0.04zm46.088,-186.877c0.036,-7.095 0.031,-14.141 0.095,-21.447c0.09,-10.2 1.344,-20.391 -11.681,-22.546c-3.029,-0.501 -6.011,-0.311 -9.071,-0.356c-16.103,0.188 -4.208,0.095 -20.312,0.086c-1.288,-0.001 -3.447,-0.042 -4.69,0.101c-16.631,2.186 -11.887,15.053 -12.708,25.903c-2.43,32.11 8.842,28.056 34.615,28.093c13.356,-0.001 -1.288,0.102 12.067,0.31c6.437,-2.248 8.334,-4.121 11.685,-10.144zm-538.341,9.842c19.69,-0.323 12.708,-26.645 13.615,-39.639c1.24,-17.754 -21.7,-14.442 -33.717,-14.425l-8.861,0.082c-10.381,0.986 -14.472,4.093 -14.602,14.978c-0.46,38.685 -3.097,39.184 34.192,39.097c12.791,0.037 -3.418,0.006 9.373,-0.093zm-32.245,177.017c-17.117,2.239 -9.942,27.417 -11.442,39.26c-0.913,7.22 4.071,14.13 11.698,14.344c21.141,0.301 14.681,0.805 35.755,0.219c5.329,-2.259 6.696,-3.627 9.715,-8.56c0.036,-5.597 0.029,-11.817 0.109,-16.86c0.13,-8.15 2.281,-27.175 -10.093,-27.753c-20.353,-0.951 -15.484,-1.293 -35.742,-0.65z';
  var n = 0;
  /** Studio's export mark in Avatar Studio's gradient. px = its size. */
  AL.exportGlyph = function (px) {
    var id = 'xg' + (++n), s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 1024 1024'); s.setAttribute('class', 'ic xg'); s.setAttribute('aria-hidden', 'true');
    if (px) { s.style.width = px + 'px'; s.style.height = px + 'px'; }
    var defs = document.createElementNS(NS, 'defs'), lg = document.createElementNS(NS, 'linearGradient');
    lg.setAttribute('id', id); lg.setAttribute('gradientUnits', 'userSpaceOnUse'); lg.setAttribute('x1', '962'); lg.setAttribute('y1', '1024'); lg.setAttribute('x2', '61'); lg.setAttribute('y2', '276');
    [['0', '#5C43FD'], ['1', '#CC27FF']].forEach(function (q) { var st = document.createElementNS(NS, 'stop'); st.setAttribute('offset', q[0]); st.setAttribute('stop-color', q[1]); lg.appendChild(st); });
    defs.appendChild(lg); s.appendChild(defs);
    var p = document.createElementNS(NS, 'path'); p.setAttribute('d', PATH); p.setAttribute('style', 'fill:url(#' + id + ');stroke:none'); s.appendChild(p);
    return s;
  };
  var icon0 = AL.icon; AL.icon = function (name) { return name === 'export' ? AL.exportGlyph() : icon0(name); };

  document.head.appendChild(h('style', { text: [
    '#bexport{flex:none;width:40px;height:40px;padding:8px;border-radius:50%;display:grid;place-items:center}', '#bexport:hover{background:var(--raised)} #bexport:active{transform:scale(.94)}',
    '#bexport svg{width:24px;height:24px;display:block}', '.ib svg.xg{width:22px;height:22px}', '.chip svg.xg{width:16px;height:16px}',
    '.xprev{display:block;width:100%;max-height:30vh;object-fit:contain;border-radius:10px;margin:2px 0 8px;background-color:#0b0b0e;background-image:linear-gradient(45deg,#1c1c22 25%,transparent 25%,transparent 75%,#1c1c22 75%),linear-gradient(45deg,#1c1c22 25%,transparent 25%,transparent 75%,#1c1c22 75%);background-size:14px 14px;background-position:0 0,7px 7px}'
  ].join('\n') }));
  var bx = h('button', { id: 'bexport', title: 'Export', 'aria-label': 'Export', onclick: function () { exp.sheet(app); } }, AL.exportGlyph(24));
  $('bjoy').parentNode.insertBefore(bx, $('bjoy'));

  /* ── what goes ── */
  var O = exp.o = { what: 'file', see: false };
  function R0(app) { return scene.on(app) ? app.scene : null; }
  function send(R) { var d = R.data; return d.send || (d.send = { bg: 'scene', skip: {} }); }
  /** What is left out, from the tick boxes: { set: true, <character id>: true } (a scene); nothing on the plain stage. */
  exp.skip = function (app) { var R = R0(app); if (!R) return {}; var s = send(R), o = {}; for (var k in s.skip) o[k] = true; if (s.noSet) o.set = true; return o; };
  /** One frame, as a picture: 1920 on its long side, at the frame's shape, as the camera sees it now. */
  exp.frame = function (app) {
    var z = frame.outSize(app), R = R0(app), pic = R ? scene.picture(app, z.w, z.h, exp.skip(app)) : frame.plainPicture(app, z.w, z.h);
    if (!pic) return null;
    var c = document.createElement('canvas'); c.width = z.w; c.height = z.h; var g = c.getContext('2d');
    if (!O.see) frame.ground(g, z.w, z.h);
    g.drawImage(pic, 0, 0); return c;
  };
  function base(app) { var R = R0(app); return ((R ? R.data.name : app.ch && app.ch.name) || 'export').replace(/[^\w\- ]+/g, '').trim() || 'export'; }
  exp.base = base;
  exp.saveFrame = function (app) { var c = exp.frame(app); if (!c) { app.toast('There is nothing to make a picture of yet.'); return Promise.resolve(false); } return new Promise(function (res) { c.toBlob(function (b) { res(AL.intake.saveFile(base(app) + '.png', b)); }, 'image/png'); }); };
  exp.saveFile = function (app) { var R = R0(app); return R ? scene.saveFile(app) : AL.intake.saveCharacter(app); };

  /* ── the sheet ── */
  exp.sheet = function (app) {
    if (!app.ch) return;
    var R = R0(app), ui = app.ui, body = h('div'), acts = [], vid = !!(exp.videoBody && AL.video && AL.video.can());
    if (O.what === 'video' && !vid) O.what = 'file';
    body.appendChild(ui.row([ui.seg([['file', 'Joy Creator file', 'The character or the scene, still alive, for the rest of Joy Creator'], ['frame', 'One frame', 'A picture of the frame as it is now']].concat(vid ? [['video', 'Video', 'The take as a video, with its voice: a file any player opens']] : []), O.what, function (v) { O.what = v; exp.sheet(app); }, 'What to export')]));
    if (!vid && exp.videoBody) body.appendChild(ui.note(root.isSecureContext === false ? 'A video can only be made on a page opened safely: its https address, or on the computer it runs on.' : 'A video cannot be made in this browser. Chrome or Edge can make one, on a computer or an Android phone.'));
    if (R) {
      var s = send(R); body.appendChild(ui.lab('What goes'));
      scene.cast(R).forEach(function (it) { body.appendChild(ui.toggle(it.name, !s.skip[it.id], function (v) { if (v) delete s.skip[it.id]; else s.skip[it.id] = 1; scene.touch(app); exp.sheet(app); }, null, (s.skip[it.id] ? 'Put ' : 'Leave ') + it.name + (s.skip[it.id] ? ' back in' : ' out'))); });
      body.appendChild(ui.toggle('The set', !s.noSet, function (v) { s.noSet = !v; scene.touch(app); exp.sheet(app); }, 'The backdrop, the objects and the foreground'));
    }
    if (O.what === 'file') {
      var has = app.inp && app.inp.take && app.inp.take.n;
      body.appendChild(ui.note(R ? 'The scene <b>' + esc(R.data.name) + '</b>, still alive: every character with its rig and its take, the set and the camera. On the phone Joy Creator picks it up live and nothing is flattened until a video is made; what is ticked off above is left out there.' : '<b>' + esc(app.ch.name) + '</b>' + (has ? ' with its take' : '') + '. On the phone Joy Creator picks it up live, and you can still grab the head and hands there.'));
      body.appendChild(ui.note('In this web pilot it is saved as the file the phone will read.'));
      if (!R) acts.push({ label: 'Sprite sheet', icon: 'grid', title: 'Bake the take to a sprite sheet for Sprite Lab, VisLab and Studio', fn: function () { app.closeSheet(); AL.intake.bakeSheet(app); } });
      acts.push({ label: R ? 'Save the scene file' : 'Save the character file', cls: 'go', icon: 'save', fn: function () { app.closeSheet(); exp.saveFile(app); } });
    } else if (O.what === 'video') exp.videoBody(app, body, acts);
    else {
      var z = frame.outSize(app), c = exp.frame(app);
      body.appendChild(ui.toggle('See-through', O.see, function (v) { O.see = v; exp.sheet(app); }, 'Where nothing is drawn, the picture is clear instead of the dark stage'));
      if (c) { var pv = h('canvas', { class: 'xprev', 'aria-label': 'What the picture will be' }); pv.width = c.width; pv.height = c.height; pv.getContext('2d').drawImage(c, 0, 0); body.appendChild(pv); }
      body.appendChild(ui.note('<b>' + z.w + ' × ' + z.h + '</b>, the frame\'s shape (' + frame.shape(app) + '), as it is this moment.' + (frame.on() ? '' : ' The frame is hidden: this is what it would cut. Its button is at the bottom right of the stage in Perform.')));
      acts.push({ label: 'Save the picture', cls: 'go', icon: 'image', fn: function () { app.closeSheet(); exp.saveFrame(app); } });
    }
    app.sheet('Export', body, acts);
  };
  function esc(s) { return String(s).replace(/[&<>]/g, function (c) { return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'; }); }
  /* every other way in (the menu, the Perform card) opens the same sheet */
  AL.intake.send = function (app) { return exp.sheet(app); };
})(typeof self !== 'undefined' ? self : this);
