// Bundle the VisLab look book into ONE self-contained HTML page (for sharing / the artifact).
//   node tools/vislab-web/build.mjs  ->  tools/vislab-web/dist/vislab-lookbook.html
//
// The shaders, the knob registry and the looks are read from the app's assets folder, so the
// page always shows exactly what the app ships. Deliberately dumb: whole files are inlined,
// nothing is parsed or rewritten (a clever bundler once dropped exports silently here).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets/vislab');
const read = (p) => fs.readFileSync(p, 'utf8');

const types = JSON.parse(read(path.join(assets, 'types.json')));
const names = new Set(['common.glsl', 'fullscreen.vert', 'copy.frag', 'bloom_down.frag', 'bloom_up.frag', 'echo.frag', 'composite.frag']);
for (const t of Object.values(types.types)) for (const k of ['frag', 'vert', 'sim', 'step', 'depositVert', 'depositFrag', 'diffuse']) if (t[k]) names.add(t[k]);
const shaders = {};
for (const n of names) shaders[n] = read(path.join(assets, 'shaders', n));
const index = JSON.parse(read(path.join(assets, 'scenes', 'index.json')));
const scenes = index.scenes.map((f) => JSON.parse(read(path.join(assets, 'scenes', f))));

// "</" inside an inline <script> would end it early.
const safe = (s) => s.replace(/<\//g, '<\\/');
const blob = safe(JSON.stringify({ types, shaders, scenes }));
const scripts = ['vislab-engine.js', 'vislab-audio.js', 'vislab-app.js']
  .map((f) => '<script>\n' + safe(read(path.join(here, f))) + '\n</script>').join('\n');

const html = read(path.join(here, 'index.html'));
const start = html.indexOf('<!--@SCRIPTS@-->');
const end = html.indexOf('<!--@END@-->');
if (start < 0 || end < 0) throw new Error('index.html lost its @SCRIPTS@ / @END@ markers');
const out = html.slice(0, start) + '<script>window.VIS_ASSETS = ' + blob + ';</script>\n' + scripts + html.slice(end + '<!--@END@-->'.length);

fs.mkdirSync(path.join(here, 'dist'), { recursive: true });
const dest = path.join(here, 'dist', 'vislab-lookbook.html');
fs.writeFileSync(dest, out);
console.log(`${dest}  ${(out.length / 1024).toFixed(0)} KB  ${Object.keys(shaders).length} shaders  ${scenes.length} looks`);

// The claude.ai artifact host wraps the page in its own document, so that copy carries only
// the head's <title>/<meta>/<style> (title first) and the body's content.
const h0 = out.indexOf('<head>'), h1 = out.indexOf('</head>');
const b0 = out.indexOf('<body>'), b1 = out.lastIndexOf('</body>');
const head = out.slice(h0 + 6, h1).split('\n')
  .filter((l) => !/<meta charset|name="viewport"/.test(l)).join('\n').trim();
const fragment = head + '\n' + out.slice(b0 + 6, b1).trim() + '\n';
const destA = path.join(here, 'dist', 'vislab-lookbook.artifact.html');
fs.writeFileSync(destA, fragment);
console.log(`${destA}  ${(fragment.length / 1024).toFixed(0)} KB (artifact body)`);
