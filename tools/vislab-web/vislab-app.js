/* VisLab look book: the page around the engine. Look strip, sound sources, framing tests,
 * and an editor generated from types.json (the same registry the app builds its editor from).
 */
(function () {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const errBox = $('#err');
  function fail(e) {
    const msg = (e && (e.stack || e.message)) || String(e);
    errBox.style.display = 'block';
    errBox.textContent += msg + '\n';
    window.__error = msg;
  }
  window.addEventListener('error', (e) => fail(e.error || e.message));
  window.addEventListener('unhandledrejection', (e) => fail(e.reason));
  function toast(t) { const el = $('#toast'); el.textContent = t; el.style.opacity = 1; clearTimeout(toast.h); toast.h = setTimeout(() => (el.style.opacity = 0), 1600); }
  const clone = (o) => JSON.parse(JSON.stringify(o));

  const PALETTES = [
    { name: 'Neon Night', stops: ['#0B0221', '#3A0CA3', '#7209B7', '#F72585', '#4CC9F0'] },
    { name: 'Aurora', stops: ['#021B1A', '#0B6E4F', '#23F0C7', '#7B61FF', '#E0AAFF'] },
    { name: 'Sunset Drive', stops: ['#1A0B2E', '#7A1CAC', '#FF2E63', '#FF9A3C', '#FFE66D'] },
    { name: 'Ember', stops: ['#120400', '#6A040F', '#D00000', '#F48C06', '#FFBA08'] },
    { name: 'Ice', stops: ['#020617', '#0C4A6E', '#0EA5E9', '#7DD3FC', '#F0F9FF'] },
    { name: 'Toxic', stops: ['#050505', '#0F3D0F', '#39FF14', '#CCFF00', '#F5FFDD'] },
    { name: 'Vapor', stops: ['#1B0B3B', '#FF71CE', '#01CDFE', '#05FFA1', '#B967FF'] },
    { name: 'Gold', stops: ['#0A0705', '#3D2B1F', '#A67C52', '#E6C88A', '#FFF6E0'] },
    { name: 'Mono', stops: ['#000000', '#2A2A2A', '#7A7A7A', '#CFCFCF', '#FFFFFF'] },
    { name: 'Joy', stops: ['#050507', '#FA3D5D', '#FF008C', '#FAA03D', '#35F6BF'] },
    { name: 'Ocean', stops: ['#001219', '#005F73', '#0A9396', '#94D2BD', '#E9D8A6'] },
    { name: 'Rose', stops: ['#1A0610', '#5C1A33', '#C2185B', '#F48FB1', '#FFE4EC'] },
    { name: 'Spectrum', stops: ['#FF2D55', '#FF9500', '#FFE600', '#34C759', '#5AC8FA'] }
  ];

  async function loadAssets() {
    if (window.VIS_ASSETS) return window.VIS_ASSETS;
    const base = '../../app/src/main/assets/vislab/';
    const bust = '?v=' + Date.now();
    const get = async (p) => { const r = await fetch(base + p + bust, { cache: 'no-store' }); if (!r.ok) throw new Error('fetch ' + p + ' ' + r.status); return r; };
    const types = await (await get('types.json')).json();
    const names = new Set(['common.glsl', 'fullscreen.vert', 'copy.frag', 'bloom_down.frag', 'bloom_up.frag', 'echo.frag', 'composite.frag', 'soften.frag']);
    for (const t of Object.values(types.types)) for (const k of ['frag', 'vert', 'sim', 'step', 'depositVert', 'depositFrag', 'diffuse']) if (t[k]) names.add(t[k]);
    const shaders = {};
    await Promise.all([...names].map(async (n) => { shaders[n] = await (await get('shaders/' + n)).text(); }));
    const index = await (await get('scenes/index.json')).json();
    const scenes = await Promise.all(index.scenes.map(async (f) => (await get('scenes/' + f)).json()));
    const lyrics = await (await get('words/demo-lyrics.json')).json();
    return { types, shaders, scenes, lyrics };
  }

  function defaultLogo() {
    const c = document.createElement('canvas');
    c.width = c.height = 512;
    const g = c.getContext('2d');
    const grd = g.createLinearGradient(0, 0, 512, 512);
    grd.addColorStop(0, '#faa03d'); grd.addColorStop(1, '#fc6818');
    g.fillStyle = grd;
    g.beginPath(); g.arc(256, 256, 240, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#0b0b0e';
    g.beginPath(); g.arc(256, 256, 200, 0, Math.PI * 2); g.fill();
    g.strokeStyle = 'rgba(250,160,61,0.35)'; g.lineWidth = 3;
    for (let r = 120; r < 200; r += 14) { g.beginPath(); g.arc(256, 256, r, 0, Math.PI * 2); g.stroke(); }
    g.fillStyle = '#faa03d';
    g.font = '800 150px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.fillText('JOY', 256, 262);
    return c;
  }

  // ---------------------------------------------------------------- the "video" underneath
  function drawUnder(t) {
    const c = $('#underCanvas');
    const w = c.width = Math.round(innerWidth * 0.5), h = c.height = Math.round(innerHeight * 0.5);
    const g = c.getContext('2d');
    const sky = g.createLinearGradient(0, 0, 0, h);
    sky.addColorStop(0, '#5b7fa6'); sky.addColorStop(0.55, '#d9a37c'); sky.addColorStop(1, '#3b2f2a');
    g.fillStyle = sky; g.fillRect(0, 0, w, h);
    g.fillStyle = 'rgba(255,240,210,0.85)';
    g.beginPath(); g.arc(w * 0.7, h * 0.42, h * 0.08, 0, Math.PI * 2); g.fill();
    for (let i = 0; i < 3; i++) {
      g.fillStyle = ['#2a2f3a', '#20242c', '#171a20'][i];
      g.beginPath(); g.moveTo(0, h);
      for (let x = 0; x <= w; x += 8) g.lineTo(x, h * (0.62 + i * 0.08) + Math.sin(x * 0.01 * (i + 1) + i + t * 0.1 * (i + 1)) * h * 0.04);
      g.lineTo(w, h); g.fill();
    }
    g.fillStyle = 'rgba(255,255,255,0.75)'; g.font = `600 ${Math.round(h * 0.035)}px system-ui`;
    g.fillText('your video / your screen', w * 0.05, h * 0.92);
  }

  // ---------------------------------------------------------------- main
  async function main() {
    const assets = await loadAssets();
    const canvas = $('#stage');
    const gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: false, powerPreference: 'high-performance' });
    if (!gl) { fail('This browser has no WebGL2.'); return; }
    const engine = new VisEngine(gl, assets);
    engine.defaultImage = defaultLogo();
    // Words for word layers: the demo song's sample lyrics until the person types their own.
    const words = new WordFeed();
    if (assets.lyrics) words.setWords(assets.lyrics.words, assets.lyrics.period, 'demo');
    engine.words = words;
    engine.onError = (layer, e) => fail('Layer "' + (layer.name || layer.type) + '" switched off: ' + (e.message || e));
    window.__engine = engine;
    const rig = new VisAudio.AudioRig();
    const idleFx = new VisAudio.FeatureExtractor(48000, 2048);
    const T = assets.types;
    const working = assets.scenes.map(clone);
    let current = 0;
    let paused = false;
    let aspect = 'full';
    let over = false;
    let brand = null;
    let selLayer = 0;
    let tab = 'layers';
    let lastF = null;

    function setScene(i) {
      current = (i + working.length) % working.length;
      engine.setScene(working[current]);
      $('#lookName').textContent = working[current].name;
      $$('.card', $('#strip')).forEach((c, k) => {
        c.classList.toggle('on', k === current);
        if (k === current && c.scrollIntoView) c.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
      });
      selLayer = 0;
      renderDrawer();
      try { history.replaceState(null, '', '#' + working[current].id); } catch (e) { /* file:// */ }
    }

    // strip
    const strip = $('#strip');
    working.forEach((s, i) => {
      const b = document.createElement('button');
      b.className = 'card';
      b.title = s.description || s.name;
      const pal = (s.palette || []).map((c) => `<i style="background:${c}"></i>`).join('');
      b.innerHTML = `<span class="nm">${s.name}</span><span class="fam">${s.family || ''}</span><span class="sw">${pal}</span>`;
      b.onclick = () => setScene(i);
      strip.appendChild(b);
    });
    const hashIdx = working.findIndex((s) => '#' + s.id === location.hash);
    setScene(hashIdx >= 0 ? hashIdx : 0);

    // sources
    async function startSource(src) {
      try {
        if (src === 'demo') await rig.useDemo(Number(new URLSearchParams(location.search).get('bar')) || 0);
        else if (src === 'mic') await rig.useMic();
        else if (src === 'file') { $('#fileIn').click(); return; }
        $('#start').classList.add('hide');
        $$('#srcSeg .pill').forEach((p) => p.classList.toggle('on', p.dataset.src === src));
        paused = false; $('#pauseBtn').textContent = 'Pause';
      } catch (e) {
        toast(src === 'mic' ? 'The microphone is not available on this page. Play the demo song or pick a song file.' : 'Could not start: ' + e.message);
      }
    }
    $('#fileIn').onchange = async (e) => {
      const f = e.target.files[0];
      if (!f) return;
      await rig.useFile(f);
      $('#start').classList.add('hide');
      $$('#srcSeg .pill').forEach((p) => p.classList.toggle('on', p.dataset.src === 'file'));
      toast(f.name);
    };
    $$('[data-start]').forEach((b) => (b.onclick = () => startSource(b.dataset.start)));
    $$('#srcSeg .pill').forEach((b) => (b.onclick = () => startSource(b.dataset.src)));
    $('#pauseBtn').onclick = () => { paused = !paused; rig.pause(paused); $('#pauseBtn').textContent = paused ? 'Play' : 'Pause'; };

    // framing
    function layoutBox() {
      const box = $('#box');
      const W = innerWidth, H = innerHeight;
      box.classList.toggle('boxed', aspect !== 'full');
      let w = W, h = H;
      const fit = (a) => { if (W / H > a) { h = H * 0.78; w = h * a; } else { w = W * 0.94; h = w / a; } };
      if (aspect === '16:9') fit(16 / 9);
      else if (aspect === '9:16') fit(9 / 16);
      else if (aspect === '1:1') fit(1);
      else if (aspect === 'overlay') { w = Math.min(W * 0.8, 560); h = w * 0.42; }
      box.style.width = w + 'px'; box.style.height = h + 'px';
      box.style.marginTop = aspect === 'overlay' ? (H * 0.25) + 'px' : '0';
    }
    $$('#aspectSeg .pill').forEach((b) => (b.onclick = () => {
      aspect = b.dataset.aspect;
      $$('#aspectSeg .pill').forEach((p) => p.classList.toggle('on', p === b));
      if (aspect === 'overlay' && !over) toggleOver();
      layoutBox();
    }));
    $$('#edgeSeg .pill').forEach((b) => (b.onclick = () => {
      engine.forceFrame = b.dataset.edge === 'scene' ? null : Number(b.dataset.edge);
      $$('#edgeSeg .pill').forEach((p) => p.classList.toggle('on', p === b));
    }));
    function toggleOver() {
      over = !over;
      $('#overBtn').classList.toggle('on', over);
      $('#under').style.display = over ? 'block' : 'none';
      engine.transparent = over;
    }
    $('#overBtn').onclick = toggleOver;
    addEventListener('resize', layoutBox);
    layoutBox();

    // menus
    const menus = { soundBtn: '#soundMenu', viewBtn: '#viewMenu' };
    for (const id in menus) {
      $('#' + id).onclick = (e) => {
        e.stopPropagation();
        for (const other in menus) if (other !== id) $(menus[other]).classList.add('hide');
        $(menus[id]).classList.toggle('hide');
      };
      $(menus[id]).onclick = (e) => e.stopPropagation();
    }
    document.addEventListener('click', () => { for (const id in menus) $(menus[id]).classList.add('hide'); });

    // swipe the picture left / right to change look
    let swipe = null;
    canvas.addEventListener('pointerdown', (e) => { swipe = { x: e.clientX, y: e.clientY, t: performance.now() }; });
    canvas.addEventListener('pointerup', (e) => {
      if (!swipe) return;
      const dx = e.clientX - swipe.x, dy = e.clientY - swipe.y;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5 && performance.now() - swipe.t < 800) setScene(current + (dx < 0 ? 1 : -1));
      swipe = null;
    });

    // drawer
    // Portrait: the drawer covers the lower part of the screen, so shrink the picture into the
    // space above it (scaled, not re-laid-out: the composition being edited stays the same).
    function fitStage() {
      const box = $('#box');
      const open = $('#drawer').classList.contains('open') && matchMedia('(max-width: 700px)').matches;
      if (!open) { box.style.transform = ''; return; }
      const topH = $('#top').getBoundingClientRect().bottom;
      const avail = innerHeight * (1 - 0.62) - topH - 6;
      const sc = Math.max(0.2, Math.min(1, avail / box.offsetHeight));
      box.style.transformOrigin = 'top center';
      box.style.transform = 'translateY(' + (topH - box.offsetTop) + 'px) scale(' + sc + ')';
    }
    function setDrawer(open) { $('#drawer').classList.toggle('open', open); renderDrawer(); fitStage(); }
    $('#editBtn').onclick = () => setDrawer(!$('#drawer').classList.contains('open'));
    $('#closeDrawer').onclick = () => setDrawer(false);
    addEventListener('resize', fitStage);
    $$('#tabs .pill').forEach((b) => (b.onclick = () => { tab = b.dataset.tab; $$('#tabs .pill').forEach((p) => p.classList.toggle('on', p === b)); renderDrawer(); }));
    $('#copyBtn').onclick = async () => {
      const txt = JSON.stringify(working[current], (k, v) => (k === 'image' && typeof v === 'object' ? undefined : v), 2);
      try { await navigator.clipboard.writeText(txt); toast('Look copied'); } catch (e) { console.log(txt); toast('Copy blocked: printed to console'); }
    };

    function baseOf(raw, d) { return raw === undefined || raw === null ? d.def : (typeof raw === 'object' ? (raw.v !== undefined ? raw.v : d.def) : raw); }
    function modsOf(raw) { return raw && typeof raw === 'object' && raw.mod ? raw.mod : null; }

    function knobRows(container, defs, holder, key) {
      // holder[key] is the object of raw knob values (created on first edit).
      let lastGroup = null;
      for (const d of defs) {
        // A scene's own controls appear only with that scene.
        if (d.showFor) {
          const dk = Object.keys(d.showFor)[0];
          const dd = defs.find((x) => x.id === dk);
          const cur = dd ? Math.round(baseOf(holder[key] ? holder[key][dk] : undefined, dd)) : 0;
          if (dd && !d.showFor[dk].includes(cur)) continue;
        }
        if (d.group && d.group !== lastGroup) { const s = document.createElement('div'); s.className = 'sec'; s.textContent = d.group; container.appendChild(s); lastGroup = d.group; }
        const row = document.createElement('div');
        row.className = 'row';
        const p = () => (holder[key] = holder[key] || {});
        const raw = holder[key] ? holder[key][d.id] : undefined;
        const set = (v) => {
          const obj = p();
          const r = obj[d.id];
          if (r && typeof r === 'object') r.v = v; else obj[d.id] = v;
        };
        const lab = document.createElement('label');
        lab.textContent = d.label; lab.title = d.label;
        row.appendChild(lab);
        let input, val = document.createElement('span');
        val.className = 'val';
        if (d.enum) {
          // Choices are fully rounded pills, not a dropdown: every option visible, one tap.
          row.classList.add('choice');
          input = document.createElement('div');
          input.className = 'pills';
          const cur = Math.round(baseOf(raw, d));
          d.enum.forEach((name, i) => {
            const b = document.createElement('button');
            b.className = 'pill' + (i === cur ? ' on' : '');
            b.textContent = name;
            b.title = d.label + ': ' + name;
            b.onclick = () => { set(i); if (defs.some((x) => x.showFor && x.showFor[d.id])) { renderDrawer(); return; } 53436('.pill', input).forEach((x, k) => x.classList.toggle('on', k === i)); };
            input.appendChild(b);
          });
        } else if (d.toggle) {
          input = document.createElement('button');
          const on = () => baseOf(holder[key] ? holder[key][d.id] : undefined, d) > 0.5;
          input.className = 'pill' + (on() ? ' on' : '');
          input.textContent = on() ? 'On' : 'Off';
          input.title = d.label;
          input.onclick = () => { set(on() ? 0 : 1); input.classList.toggle('on', on()); input.textContent = on() ? 'On' : 'Off'; };
        } else {
          input = document.createElement('input');
          input.type = 'range';
          input.title = d.label;
          const lg = d.log && d.min > 0;
          const toS = (v) => lg ? Math.log(v / d.min) / Math.log(d.max / d.min) : (v - d.min) / (d.max - d.min);
          const fromS = (s) => { let v = lg ? d.min * Math.pow(d.max / d.min, s) : d.min + s * (d.max - d.min); if (d.step) v = Math.round(v / d.step) * d.step; return v; };
          input.min = 0; input.max = 1000; input.step = 1;
          input.value = Math.round(toS(baseOf(raw, d)) * 1000);
          const show = (v) => (val.textContent = Math.abs(v) >= 100 ? Math.round(v) : Number(v).toFixed(Math.abs(v) < 0.1 ? 3 : 2));
          show(baseOf(raw, d));
          input.oninput = () => { const v = fromS(input.value / 1000); set(v); show(v); };
        }
        row.appendChild(input);
        if (d.enum) { container.appendChild(row); continue; }
        row.appendChild(val);
        const mb = document.createElement('button');
        mb.className = 'modbtn' + (modsOf(raw) ? ' has' : '');
        mb.innerHTML = '&#x266A;';
        mb.title = 'Make "' + d.label + '" react to the music';
        row.appendChild(mb);
        container.appendChild(row);
        if (d.enum || d.toggle) { mb.style.visibility = 'hidden'; continue; }
        const mods = document.createElement('div');
        mods.className = 'mods hide';
        container.appendChild(mods);
        mb.onclick = () => { mods.classList.toggle('hide'); drawMods(); };
        function drawMods() {
          mods.innerHTML = '';
          const obj = p();
          let r = obj[d.id];
          const active = modsOf(r) || {};
          for (const s of T.signals) {
            const c = document.createElement('button');
            c.className = 'chip' + (active[s.id] !== undefined ? ' on' : '');
            c.textContent = s.label;
            c.title = 'React to ' + s.label;
            c.onclick = () => {
              r = obj[d.id];
              if (!r || typeof r !== 'object') { r = obj[d.id] = { v: baseOf(r, d), mod: {} }; }
              r.mod = r.mod || {};
              if (r.mod[s.id] !== undefined) delete r.mod[s.id]; else r.mod[s.id] = 0.25;
              if (!Object.keys(r.mod).length) obj[d.id] = r.v;
              mb.classList.toggle('has', !!modsOf(obj[d.id]));
              drawMods();
            };
            mods.appendChild(c);
          }
          for (const sid in active) {
            const lab2 = document.createElement('div');
            lab2.style.cssText = 'width:100%;display:grid;grid-template-columns:80px 1fr 40px;gap:6px;align-items:center;font-size:11px;color:var(--ink-dim)';
            const sl = document.createElement('input');
            sl.type = 'range'; sl.min = -100; sl.max = 100; sl.value = Math.round(active[sid] * 100);
            sl.title = 'How much ' + sid + ' moves ' + d.label;
            const vv = document.createElement('span'); vv.textContent = active[sid].toFixed(2);
            sl.oninput = () => { obj[d.id].mod[sid] = sl.value / 100; vv.textContent = (sl.value / 100).toFixed(2); };
            const name = T.signals.find((x) => x.id === sid);
            lab2.innerHTML = `<span>${name ? name.label : sid}</span>`;
            lab2.appendChild(sl); lab2.appendChild(vv);
            mods.appendChild(lab2);
          }
        }
      }
    }

    function renderDrawer() {
      if (!$('#drawer').classList.contains('open')) return;
      const body = $('#dbody');
      body.innerHTML = '';
      const scene = working[current];
      if (tab === 'layers') {
        const list = document.createElement('div');
        (scene.layers || []).forEach((L, i) => {
          const t = T.types[L.type] || { label: L.type };
          const el = document.createElement('div');
          el.className = 'layer' + (i === selLayer ? ' sel' : '');
          el.innerHTML = `<button class="ib ${L.on === false ? 'off' : ''}" data-a="eye" title="Show or hide this layer">&#x25C9;</button>
            <div class="ln"><b>${L.name || t.label}</b><span>${t.label}${L.echo === false ? ' &middot; above echo' : ''}</span></div>
            <button class="ib" data-a="up" title="Move up (drawn later, on top)">&#x25B2;</button>
            <button class="ib" data-a="down" title="Move down (drawn earlier, behind)">&#x25BC;</button>
            <button class="ib" data-a="dup" title="Duplicate this layer">&#x29C9;</button>
            <button class="ib" data-a="del" title="Remove this layer">&#x2715;</button>`;
          el.onclick = (e) => {
            const a = e.target.dataset && e.target.dataset.a;
            const ls = scene.layers;
            if (a === 'eye') L.on = L.on === false;
            else if (a === 'up' && i < ls.length - 1) { ls.splice(i, 1); ls.splice(i + 1, 0, L); selLayer = i + 1; }
            else if (a === 'down' && i > 0) { ls.splice(i, 1); ls.splice(i - 1, 0, L); selLayer = i - 1; }
            else if (a === 'dup') { const c = clone(L); c.seed = Math.floor(Math.random() * 1000); ls.splice(i + 1, 0, c); selLayer = i + 1; engine.setScene(scene); }
            else if (a === 'del') { ls.splice(i, 1); selLayer = Math.max(0, i - 1); engine.setScene(scene); }
            else selLayer = i;
            renderDrawer();
          };
          list.appendChild(el);
        });
        body.appendChild(list);
        const add = document.createElement('button');
        add.className = 'pill'; add.textContent = '+ Add a layer'; add.title = 'Add a new piece to this look';
        add.style.margin = '6px 0';
        body.appendChild(add);
        const grid = document.createElement('div');
        grid.className = 'hide';
        add.onclick = () => grid.classList.toggle('hide');
        for (const fam of T.families) {
          const s = document.createElement('div'); s.className = 'sec'; s.textContent = fam.label; grid.appendChild(s);
          const g = document.createElement('div'); g.className = 'addgrid';
          for (const id in T.types) {
            const t = T.types[id];
            if (t.family !== fam.id) continue;
            const b = document.createElement('button');
            b.innerHTML = `${t.label}<small>${t.about}</small>`;
            b.title = t.about;
            b.onclick = () => { scene.layers.push({ type: id, name: t.label, add: 1, p: {} }); selLayer = scene.layers.length - 1; renderDrawer(); };
            g.appendChild(b);
          }
          grid.appendChild(g);
        }
        body.appendChild(grid);
        const L = scene.layers[selLayer];
        if (L) {
          const t = T.types[L.type];
          const s = document.createElement('div'); s.className = 'sec'; s.textContent = (L.name || t.label) + ' - layer'; body.appendChild(s);
          const lp = document.createElement('div');
          // The controls every layer has (types.json "layer"): Opacity, Reactivity, Softness here,
          // the Place group after the layer's own knobs.
          const common = T.layer ? T.layer.params : [{ id: 'opacity', label: 'Opacity', min: 0, max: 1, def: 1 }];
          knobRows(lp, common.filter((d) => !d.group)
            .concat([{ id: 'add', label: 'Light / paint', min: 0, max: 1, def: 1 }]), { x: L }, 'x');
          const echoRow = document.createElement('div'); echoRow.className = 'row';
          echoRow.innerHTML = '<label>Under echo</label>';
          const eb = document.createElement('button'); eb.className = 'pill' + (L.echo !== false ? ' on' : ''); eb.textContent = L.echo !== false ? 'Yes' : 'No';
          eb.title = 'Draw into the echo trails (yes) or crisp above them (no)';
          eb.onclick = () => { L.echo = L.echo === false; renderDrawer(); };
          echoRow.appendChild(eb);
          lp.appendChild(echoRow);
          if (L.type === 'image') {
            const r = document.createElement('div'); r.className = 'row'; r.innerHTML = '<label>Picture</label>';
            const pick = document.createElement('button'); pick.className = 'pill'; pick.textContent = 'Use my logo'; pick.title = 'Pick an image from this device';
            pick.onclick = () => {
              const fi = document.createElement('input'); fi.type = 'file'; fi.accept = 'image/*';
              fi.onchange = () => { const img = new Image(); img.onload = () => { L.image = img; }; img.src = URL.createObjectURL(fi.files[0]); };
              fi.click();
            };
            r.appendChild(pick); lp.appendChild(r);
          }
          body.appendChild(lp);
          knobRows(body, t.params, L, 'p');
          knobRows(body, common.filter((d) => d.group), { x: L }, 'x');
        }
      } else if (tab === 'finish') {
        scene.post = scene.post || {};
        knobRows(body, T.post.params, scene.post, 'p');
      } else if (tab === 'echo') {
        const r = document.createElement('div'); r.className = 'row'; r.innerHTML = '<label>Echo</label>';
        const b = document.createElement('button');
        const on = !!(scene.echo && scene.echo.on !== false);
        b.className = 'pill' + (on ? ' on' : ''); b.textContent = on ? 'On' : 'Off'; b.title = 'Feedback trails on or off';
        b.onclick = () => { if (on) scene.echo.on = false; else { scene.echo = scene.echo || { p: {} }; scene.echo.on = true; } engine.echoFresh = true; renderDrawer(); };
        r.appendChild(b); body.appendChild(r);
        const n = document.createElement('p'); n.className = 'note'; n.textContent = T.echo.about; body.appendChild(n);
        if (on) knobRows(body, T.echo.params, scene.echo, 'p');
      } else if (tab === 'colours') {
        const s = document.createElement('div'); s.className = 'sec'; s.textContent = 'This look'; body.appendChild(s);
        const stops = document.createElement('div'); stops.className = 'stops';
        scene.palette = scene.palette || PALETTES[0].stops.slice();
        scene.palette.forEach((c, i) => {
          const inp = document.createElement('input'); inp.type = 'color'; inp.value = c; inp.title = 'Palette colour ' + (i + 1);
          inp.oninput = () => { scene.palette[i] = inp.value; };
          stops.appendChild(inp);
        });
        body.appendChild(stops);
        const bgRow = document.createElement('div'); bgRow.className = 'row'; bgRow.innerHTML = '<label>Background</label>';
        const bgIn = document.createElement('input'); bgIn.type = 'color'; bgIn.value = scene.background || '#000000'; bgIn.title = 'Background colour';
        bgIn.oninput = () => { scene.background = bgIn.value; };
        const see = document.createElement('button'); see.className = 'pill' + (!scene.background ? ' on' : ''); see.textContent = 'See-through';
        see.title = 'No background: the look sits over whatever is underneath';
        see.onclick = () => { scene.background = scene.background ? null : '#000000'; renderDrawer(); };
        bgRow.appendChild(bgIn); bgRow.appendChild(see); body.appendChild(bgRow);
        const s2 = document.createElement('div'); s2.className = 'sec'; s2.textContent = 'Palettes (tap = this look, hold = brand for every look)'; body.appendChild(s2);
        const pals = document.createElement('div'); pals.className = 'pals';
        PALETTES.forEach((P) => {
          const b = document.createElement('button');
          b.className = brand === P ? 'on' : '';
          b.title = 'Tap: use on this look. Long-press: brand lock for every look';
          b.innerHTML = `<div class="sw" style="height:10px;margin-bottom:4px">${P.stops.map((c) => `<i style="background:${c}"></i>`).join('')}</div>${P.name}`;
          let held = null;
          b.onpointerdown = () => { held = setTimeout(() => { held = 'done'; brand = brand === P ? null : P; engine.brandPalette = brand ? brand.stops : null; toast(brand ? 'Brand: ' + P.name + ' on every look' : 'Brand lock off'); renderDrawer(); }, 550); };
          b.onpointerup = () => { if (held !== 'done') { clearTimeout(held); scene.palette = P.stops.slice(); renderDrawer(); } held = null; };
          pals.appendChild(b);
        });
        body.appendChild(pals);
        const n = document.createElement('p'); n.className = 'note';
        n.textContent = 'Brand lock re-colours every look with your palette. Finish > Brand recolour maps the whole picture through it for a strict brand look.';
        body.appendChild(n);
      } else if (tab === 'sound') {
        const m = document.createElement('div'); m.className = 'meters'; m.id = 'meters'; body.appendChild(m);
        const n = document.createElement('p'); n.className = 'note'; n.id = 'bpm'; body.appendChild(n);
      }
    }

    function updateMeters(f) {
      const m = $('#meters');
      if (!m || !f) return;
      const rows = [['Bass', f.bass], ['Low mids', f.low], ['Mids', f.mid], ['Highs', f.high], ['Air', f.air], ['Loudness', f.level], ['Voice', f.voice],
        ['Kick', f.kick], ['Snare', f.snare], ['Hi-hat', f.hat], ['Beat', f.beat]];
      m.innerHTML = rows.map(([n, v]) => `<span>${n}</span><i style="width:${Math.round(Math.min(1, v) * 100)}%"></i>`).join('');
      $('#bpm').textContent = 'Tempo guess: ' + Math.round(f.bpm) + ' bpm';
    }

    // keyboard: arrows switch looks
    addEventListener('keydown', (e) => { if (e.key === 'ArrowRight') setScene(current + 1); if (e.key === 'ArrowLeft') setScene(current - 1); if (e.key === ' ') $('#pauseBtn').click(); });

    // loop
    let last = performance.now(), fpsT = 0, frames = 0, slowFrames = 0, fastFrames = 0;
    engine.renderScale = matchMedia('(max-width: 700px)').matches ? 0.7 : 1;
    function frame(now) {
      requestAnimationFrame(frame);
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      const box = $('#box');
      const dpr = Math.min(devicePixelRatio || 1, 2);
      const cw = Math.max(16, Math.round(box.clientWidth * dpr)), ch = Math.max(16, Math.round(box.clientHeight * dpr));
      if (canvas.width !== cw || canvas.height !== ch) { canvas.width = cw; canvas.height = ch; }
      let f = rig.read(dt);
      if (!f) f = VisAudio.idleFeatures(idleFx, now / 1000, dt);
      lastF = f;
      const st = rig.songTime ? rig.songTime() : null;
      engine.wordsTime = words.label === 'demo' && rig.source === 'demo' ? st : (words.label === 'typed' ? engine.time : st);
      try { engine.render(f, paused ? 0 : dt, cw, ch); } catch (e) { fail(e); return; }
      if (over) drawUnder(now / 1000);
      frames++; fpsT += dt;
      // Keep it smooth: drop the internal resolution when frames run long, raise it when there is room.
      // Ignore single huge stalls (tab switches, screenshots): they are not the GPU.
      if (dt > 0.024 && dt < 0.08) slowFrames++; else if (dt < 0.0175) fastFrames++;
      if (fpsT >= 1) {
        if (slowFrames > frames * 0.3 && engine.renderScale > 0.45) engine.renderScale = Math.max(0.45, engine.renderScale - 0.1);
        else if (fastFrames > frames * 0.9 && engine.renderScale < 1) engine.renderScale = Math.min(1, engine.renderScale + 0.05);
        $('#fps').textContent = `${frames} fps · ${engine.stats.ms.toFixed(1)} ms · ${Math.round(engine.renderScale * 100)}%`;
        frames = 0; fpsT = 0; slowFrames = 0; fastFrames = 0;
        if (tab === 'sound') updateMeters(lastF);
      }
    }
    requestAnimationFrame(frame);
    window.__vislab = { engine, rig, words, setScene: (i) => setScene(i), scenes: working, startSource };
    // Dev: pick up edited shaders / looks without restarting the music.
    window.__vislab.reload = async () => {
      const a = await loadAssets();
      engine.assets = a;
      engine.types = a.types.types;
      engine.programs = new Map();
      working.splice(0, working.length, ...a.scenes.map(clone));
      setScene(current);
      return 'reloaded ' + working[current].id;
    };
  }

  main().catch(fail);
})();
