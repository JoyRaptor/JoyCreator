// TextLab look book: the page. Loads the app's own shader, knob and look files, shows every
// look on the words you type, and builds its panels from parts.json (as the room will).
//
// Address-bar switches (for checks and stills):
//   ?look=<id>  &text=<words>  &stage=<0..6>  &t=<seconds: hold that moment>  &tx= &ty= (tilt, -1..1)
//   &w= &h= (largest picture, px)  &bare=1 (picture only)  &bench=1 (time every look, POST to /report; &only=a,b for some)
//   &crop=x,y (true pixels from that corner)  &k=knob:value,knob:value (turn knobs)
(function () {
  const TL = window.TL;
  const ASSETS = '../../app/src/main/assets/textlab/';
  const SHADERS = ['common.glsl', 'quad.vert', 'glyph.vert', 'glyph.frag', 'soften.frag', 'matcap.frag', 'shade.frag',
    'fire_step.frag', 'fire_draw.frag', 'particle.vert', 'particle.frag', 'bloom_down.frag', 'bloom_up.frag', 'final.frag', 'backdrop.frag', 'copy.frag', 'fxaa.frag'];
  const q = new URLSearchParams(location.search);
  const $ = (id) => document.getElementById(id);
  const fail = (e) => { const el = $('err'); el.style.display = 'block'; el.textContent = String(e && e.stack ? e.message : e); console.error(e); };
  window.addEventListener('error', (e) => fail(e.error || e.message));
  window.addEventListener('unhandledrejection', (e) => fail(e.reason));

  async function loadAssets() {
    if (window.TL_ASSETS) {   // the one-file build carries everything inside, fonts too
      return Object.assign({ fontUrl: (file) => window.TL_ASSETS.fonts[file] }, window.TL_ASSETS);
    }
    const text = (p) => fetch(ASSETS + p).then((r) => { if (!r.ok) throw new Error('missing ' + p); return r.text(); });
    const shaders = {};
    await Promise.all(SHADERS.map(async (n) => { shaders[n] = await text('shaders/' + n); }));
    const parts = JSON.parse(await text('parts.json'));
    const tubefont = JSON.parse(await text('tubefont.json'));
    const index = JSON.parse(await text('looks/index.json'));
    // "looks" are the finished ones; "drafts" are kept, unfinished, and shown only with &drafts=1
    const files = index.looks.concat(q.get('drafts') ? index.drafts || [] : []);
    const looks = await Promise.all(files.map(async (f) => JSON.parse(await text('looks/' + f))));
    return { shaders, parts, looks, tubefont, fontUrl: (file) => '../../' + file };
  }

  const SLOT_NEEDS = { Tube: 'TUBE', Glass: 'REFRACT', Surface: 'DETAIL', Pattern: 'PATTERN', Ember: 'EMBER', Hologram: 'HOLO', Stone: 'STONE', Fur: 'FUR', Lamps: 'CELLS', Lightning: 'ARCS', Paper: 'PAPER', Burn: 'BURN' };
  // which groups of sliders are open: remembered on this device, so a person leaves open only what they use
  let openSlots = new Set(['Shape', 'Finish']);
  try { const saved = JSON.parse(localStorage.getItem('tl-open') || 'null'); if (saved) openSlots = new Set(saved); } catch (e) { /* no storage here */ }
  // which family's sliders are showing: 'All', one family, or not chosen yet (then the page picks by its shape)
  let tab = null;
  try { tab = localStorage.getItem('tl-tab') || null; } catch (e) { /* no storage here */ }
  const rememberTab = () => { try { localStorage.setItem('tl-tab', tab); } catch (e) { /* no storage here */ } };
  const upright = () => window.matchMedia('(max-width: 900px)').matches;
  const rememberOpen = () => { try { localStorage.setItem('tl-open', JSON.stringify([...openSlots])); } catch (e) { /* no storage here */ } };
  const HIDDEN_FOR_TUBE = new Set(['Depth', 'Coats', 'Material', 'Light', 'Sides']);

  async function main() {
    if (q.get('bare')) document.body.classList.add('bare');
    if (q.has('crop')) {   // &crop=x,y: show the picture at its true pixels from that corner (for close checks)
      const [cx, cy] = q.get('crop').split(',').map(Number);
      Object.assign($('cv').style, { position: 'fixed', left: -cx + 'px', top: -cy + 'px', maxWidth: 'none', maxHeight: 'none' });
    }
    const assets = await loadAssets();
    await TL.loadFonts(assets.fontUrl);
    const engine = new TL.Engine($('cv'), assets);
    window.TLAPP = { engine, assets };
    engine.debug = q.get('debug');   // &debug=heat|field|sphere|scene|bloom
    let look = assets.looks.find((l) => l.id === q.get('look')) || assets.looks[0];
    let paused = false, holdAt = q.has('t') ? Number(q.get('t')) : null;

    function fit() {
      const v = $('view').getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio || 1, 2);
      engine.setSize(Number(q.get('w')) || Math.min(Math.round((v.width - 24) * dpr), 1600),
                     Number(q.get('h')) || Math.min(Math.round((v.height - 24) * dpr), 1000));
    }

    function choose(l, keepText) {
      look = l;
      engine.setLook(l);
      if (!keepText) $('text').value = q.get('text') || l.sample || 'TEXT';
      engine.setText($('text').value.replace(/\\n/g, '\n'));
      engine.stage = q.has('stage') ? Number(q.get('stage')) : (l.stage === undefined ? 1 : l.stage);
      $('stage').value = String(engine.stage);
      for (const b of $('looks').querySelectorAll('button')) b.classList.toggle('on', b.dataset.id === l.id);
      buildKnobs();
    }

    function buildLooks() {
      const box = $('looks'); box.textContent = '';
      let fam = null;
      for (const l of assets.looks) {
        if (l.family !== fam) { fam = l.family; const h = document.createElement('div'); h.className = 'fam'; h.textContent = fam; box.appendChild(h); }
        const b = document.createElement('button');
        b.textContent = l.name; b.dataset.id = l.id; b.title = l.description || l.name;
        b.onclick = () => choose(l);
        box.appendChild(b);
      }
    }

    function buildKnobs() {
      const box = $('knobs'); box.textContent = '';
      const feats = new Set(look.features || []);
      const slots = assets.parts.slots.filter((slot) => !(SLOT_NEEDS[slot] && !feats.has(SLOT_NEEDS[slot]))
        && !(slot === 'Fire' && !look.fire) && !(feats.has('TUBE') && HIDDEN_FOR_TUBE.has(slot)));
      // The families, as a row of tabs. A family shows only its own sliders (little to scroll, the picture
      // stays in sight); All shows every group, each folding away. An upright phone starts on a family:
      // the look's own special one if it has one. A dot marks the families this look actually uses.
      const own = slots.find((slot) => SLOT_NEEDS[slot]);
      let showing = tab === 'All' || slots.includes(tab) ? tab : upright() ? own || 'Shape' : 'All';
      const used = new Set(Object.keys(look.p || {}).map((name) => (assets.parts.knobs[name] || {}).slot));
      const tabs = document.createElement('div'); tabs.id = 'tabs'; tabs.setAttribute('role', 'tablist');
      const apply = () => {
        for (const b of tabs.children) b.classList.toggle('on', b.dataset.slot === showing);
        for (const det of box.querySelectorAll('details')) {
          const solo = showing !== 'All';
          det.classList.toggle('solo', solo);
          det.hidden = solo && det.dataset.slot !== showing;
          det.open = solo || openSlots.has(det.dataset.slot) || !!SLOT_NEEDS[det.dataset.slot];   // a look's own special group starts open
        }
        const on = tabs.querySelector('.on');                    // the chosen tab is never off the end of the row
        if (on) tabs.scrollLeft = on.offsetLeft - (tabs.clientWidth - on.offsetWidth) / 2;
      };
      for (const name of ['All', ...slots]) {
        const b = document.createElement('button'); b.textContent = name; b.dataset.slot = name; b.setAttribute('role', 'tab');
        b.title = name === 'All' ? 'Every group of sliders, each one folding away' : 'Only the ' + name + ' sliders';
        if (used.has(name)) b.classList.add('used');
        b.onclick = () => { showing = tab = name; rememberTab(); apply(); box.scrollTop = 0; };
        tabs.appendChild(b);
      }
      box.appendChild(tabs);
      for (const slot of slots) {
        const det = document.createElement('details'), sum = document.createElement('summary');
        sum.textContent = slot; det.appendChild(sum); det.dataset.slot = slot;
        det.ontoggle = () => { if (det.classList.contains('solo')) return; if (det.open) openSlots.add(slot); else openSlots.delete(slot); rememberOpen(); };
        box.appendChild(det);
        for (const [name, k] of Object.entries(assets.parts.knobs)) {
          if (k.slot !== slot) continue;
          const row = document.createElement('div'); row.className = 'row';
          const lab = document.createElement('label'); lab.textContent = k.label; row.appendChild(lab);
          const cur = engine.knob(name);
          if ('c' in k) {
            const [hex, gain] = String(cur).split('*');
            const inp = document.createElement('input'); inp.type = 'color'; inp.value = hex; inp.title = k.label; inp.id = 'k-' + name; lab.htmlFor = inp.id;
            inp.oninput = () => engine.setKnob(name, gain ? inp.value + '*' + gain : inp.value);
            row.appendChild(inp);
          } else {
            const inp = document.createElement('input'); inp.type = 'range'; inp.title = k.label; inp.id = 'k-' + name; lab.htmlFor = inp.id;
            inp.min = k.min; inp.max = k.max; inp.step = k.step || (k.max - k.min) / 400; inp.value = cur;
            const out = document.createElement('output'); out.textContent = Number(cur).toFixed(3).replace(/\.?0+$/, '');
            inp.oninput = () => { engine.setKnob(name, Number(inp.value)); out.textContent = Number(inp.value).toFixed(3).replace(/\.?0+$/, ''); };
            row.appendChild(inp); row.appendChild(out);
          }
          det.appendChild(row);
        }
      }
      apply();
    }

    $('text').oninput = () => engine.setText($('text').value.replace(/\\n/g, '\n'));
    $('stage').onchange = () => { engine.stage = Number($('stage').value); };
    $('replay').onclick = () => { holdAt = null; paused = false; $('pause').classList.remove('on'); engine.replay(); };
    $('pause').onclick = () => { paused = !paused; $('pause').classList.toggle('on', paused); };
    $('copy').onclick = () => {
      const out = JSON.parse(JSON.stringify(look)); out.p = Object.assign({}, look.p, engine.over);
      const say = (word) => { $('copy').textContent = word; setTimeout(() => { $('copy').textContent = 'Copy look'; }, 1400); };
      navigator.clipboard.writeText(JSON.stringify(out, null, 2)).then(() => say('Copied'), () => say('Copy refused here'));
    };
    $('fontpick').onchange = () => { engine.fontPick = $('fontpick').value; engine.userFont = null; $('fontname').textContent = 'Your font'; engine.dirty = true; };
    // Your own font: any .ttf / .otf / .woff file, used for every look that is drawn from type.
    $('font').onchange = async () => {
      const file = $('font').files[0];
      if (!file) return;
      try {
        const face = new FontFace('TLUser', await file.arrayBuffer());
        if (engine.userFace) document.fonts.delete(engine.userFace);
        document.fonts.add(await face.load());
        TL.FONTS.user = { css: 'TLUser' };
        engine.userFace = face; engine.userFont = file.name; $('fontpick').value = '';
        engine.glyphs.cache.clear();
        engine.dirty = true;
        $('fontname').textContent = file.name.replace(/\.[^.]+$/, '');
      } catch (e) { fail('That file could not be read as a font: ' + (e.message || e)); }
    };
    const cv = $('cv');
    cv.addEventListener('pointermove', (e) => {
      const r = cv.getBoundingClientRect();
      engine.tilt = [((e.clientX - r.left) / r.width) * 2 - 1, -(((e.clientY - r.top) / r.height) * 2 - 1)];
    });
    window.addEventListener('deviceorientation', (e) => {
      if (e.gamma === null) return;
      engine.tilt = [Math.max(-1, Math.min(1, e.gamma / 30)), Math.max(-1, Math.min(1, (e.beta - 45) / 30))];
    });
    window.addEventListener('resize', fit);

    buildLooks(); fit(); choose(look);
    if (q.has('tx')) engine.tilt = [Number(q.get('tx')), Number(q.get('ty') || 0)];
    // &k=bevel:0.06,fillA:%23ff0000 turns knobs from the address bar (numbers, or colours starting with #)
    for (const pair of (q.get('k') || '').split(',').filter(Boolean)) {
      const i = pair.indexOf(':'), name = pair.slice(0, i), v = pair.slice(i + 1);
      engine.setKnob(name, v[0] === '#' ? v : Number(v));
    }

    if (q.get('bench')) { await bench(engine, assets); return; }
    if (q.get('sheet')) { sheet(engine, assets); return; }

    let frames = 0, t0 = performance.now(), clock = 0, lastNow = performance.now();
    function tick(now) {
      try {
        if (holdAt !== null) { engine.still(holdAt); document.title = 'TextLab still ' + look.id; }
        else {
          if (!paused) clock += (now - lastNow) / 1000;
          engine.frame(clock);
        }
        lastNow = now;
        frames++;
        if (now - t0 > 1000) {
          $('stat').textContent = engine.W + ' x ' + engine.H + ' px   ' + Math.round((frames * 1000) / (now - t0)) + ' fps';
          frames = 0; t0 = now;
        }
      } catch (e) { fail(e); return; }
      if (holdAt === null) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  }

  // Every look as a still, side by side: &sheet=<columns>  &t=<seconds>  &from=<first>  &n=<how many>
  function sheet(engine, assets) {
    document.body.classList.add('bare');
    const view = document.getElementById('view'), gl = document.getElementById('cv');
    gl.style.display = 'none';
    view.style.cssText = 'display:grid;grid-template-columns:repeat(' + (Number(q.get('sheet')) || 2) + ',1fr);gap:6px;padding:6px;overflow:auto;align-items:start;align-content:start';
    const t = Number(q.get('t') || 2.5), from = Number(q.get('from') || 0), n = Number(q.get('n') || 99);
    for (const l of assets.looks.slice(from, from + n)) {
      engine.setSize(Number(q.get('w')) || 800, Number(q.get('h')) || 450);
      engine.setLook(l); engine.setText(q.get('text') || l.sample || 'TEXT'); engine.stage = l.stage === undefined ? 1 : l.stage;
      engine.still(t);
      const c = document.createElement('canvas'); c.width = engine.W; c.height = engine.H; c.title = l.name;
      c.getContext('2d').drawImage(gl, 0, 0);
      c.style.cssText = 'width:100%;border-radius:4px;display:block';
      const box = document.createElement('div'), cap = document.createElement('div');
      cap.textContent = l.name; cap.style.cssText = 'color:#8e8e98;font-size:11px;padding:2px 2px 6px';
      box.appendChild(c); box.appendChild(cap); view.appendChild(box);
    }
  }

  // Time every look on THIS device's GPU: frames drawn back to back, the GPU made to finish.
  async function bench(engine, assets) {
    const gl = engine.gl, px = new Uint8Array(4), rows = [];
    engine.setSize(Number(q.get('w')) || 1280, Number(q.get('h')) || 720);
    const only = (q.get('only') || '').split(',').filter(Boolean);      // &only=a,b times just those looks
    for (const l of assets.looks) {
      if (only.length && !only.includes(l.id)) continue;
      let ms = -1, err = null;
      try {
        engine.setLook(l); engine.setText(l.sample || 'TEXT'); engine.stage = l.stage === undefined ? 1 : l.stage;
        let t = 0;
        for (let i = 0; i < 20; i++) engine.frame((t += 1 / 30));
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        const a = performance.now(), N = 40;
        for (let i = 0; i < N; i++) engine.frame((t += 1 / 30));
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        ms = (performance.now() - a) / N;
      } catch (e) { err = String(e.message || e); }
      rows.push({ look: l.id, w: engine.W, h: engine.H, ms: Math.round(ms * 100) / 100, err });
      document.getElementById('stat').textContent = l.id + ' ' + ms.toFixed(1) + ' ms';
      await new Promise((r) => setTimeout(r, 50));
    }
    const report = { kind: 'textlab-bench', when: new Date().toISOString(), gpu: engine.gpu(), agent: navigator.userAgent, rows };
    document.getElementById('err').style.display = 'block';
    document.getElementById('err').style.background = '#10212a';
    document.getElementById('err').textContent = JSON.stringify(report, null, 1);
    try { await fetch('/report', { method: 'POST', body: JSON.stringify(report) }); } catch (e) { /* a page opened from a file has nowhere to post */ }
  }

  main().catch(fail);
})();
