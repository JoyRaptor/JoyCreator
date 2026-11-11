// Bundle the TextLab look book into ONE self-contained HTML page (for sharing / the artifact).
//   node tools/textlab-web/build.mjs [--drafts]  ->  tools/textlab-web/dist/textlab-lookbook.html
//
// The shaders, the knobs, the looks and the fonts the looks use are read from the app's own
// folders, so the page shows exactly what the app ships. Deliberately dumb, as VisLab's is:
// whole files are inlined, nothing is parsed or rewritten.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const assets = path.join(root, 'app/src/main/assets/textlab');
const read = (p) => fs.readFileSync(p, 'utf8');
const withDrafts = process.argv.includes('--drafts');

const shaders = {};
for (const n of fs.readdirSync(path.join(assets, 'shaders'))) shaders[n] = read(path.join(assets, 'shaders', n));
const parts = JSON.parse(read(path.join(assets, 'parts.json')));
const index = JSON.parse(read(path.join(assets, 'looks', 'index.json')));
const files = index.looks.concat(withDrafts ? index.drafts || [] : []);
const looks = files.map((f) => JSON.parse(read(path.join(assets, 'looks', f))));

// only the fonts these looks ask for (the file paths are the ones tl-glyphs.js names)
const fontFiles = {
  archivo: 'app/src/main/res/font/archivo_variable.ttf', plex: 'app/src/main/res/font/plex_sans_variable.ttf',
  mono: 'app/src/main/res/font/plex_mono_semibold.ttf', ubuntu: 'app/src/main/res/font/ubuntu_regular.ttf', doto: 'app/src/main/assets/doto.ttf',
};
const fonts = {};
for (const fam of new Set(looks.map((l) => (l.font && l.font.family) || 'archivo').concat(['archivo']))) {
  const f = fontFiles[fam];
  if (f) fonts[f] = 'data:font/ttf;base64,' + fs.readFileSync(path.join(root, f)).toString('base64');
}

// "</" inside an inline <script> would end it early.
const safe = (s) => s.replace(/<\//g, '<\\/');
const tubefont = JSON.parse(read(path.join(assets, 'tubefont.json')));
const blob = safe(JSON.stringify({ shaders, parts, looks, fonts, tubefont }));
const scripts = ['tl-gl.js', 'tl-glyphs.js', 'tl-tubes.js', 'tl-engine.js', 'tl-app.js']
  .map((f) => '<script>\n' + safe(read(path.join(here, f))) + '\n</script>').join('\n');

const html = read(path.join(here, 'index.html'));
const start = html.indexOf('<!--@SCRIPTS@-->');
const end = html.indexOf('<!--@END@-->');
if (start < 0 || end < 0) throw new Error('index.html lost its @SCRIPTS@ / @END@ markers');
const out = html.slice(0, start) + '<script>window.TL_ASSETS = ' + blob + ';</script>\n' + scripts + html.slice(end + '<!--@END@-->'.length);

fs.mkdirSync(path.join(here, 'dist'), { recursive: true });
const dest = path.join(here, 'dist', 'textlab-lookbook.html');
fs.writeFileSync(dest, out);
console.log(`${dest}  ${(out.length / 1024).toFixed(0)} KB  ${Object.keys(shaders).length} shaders  ${looks.length} looks  ${Object.keys(fonts).length} fonts`);

// The claude.ai artifact host wraps the page in its own document, so that copy carries only
// the head's <title>/<meta>/<style> (title first) and the body's content.
const h0 = out.indexOf('<head>'), h1 = out.indexOf('</head>');
const b0 = out.indexOf('<body>'), b1 = out.lastIndexOf('</body>');
const head = out.slice(h0 + 6, h1).split('\n').filter((l) => !/<meta charset|name="viewport"/.test(l)).join('\n').trim();
const destA = path.join(here, 'dist', 'textlab-lookbook.artifact.html');
fs.writeFileSync(destA, head + '\n' + out.slice(b0 + 6, b1).trim() + '\n');
console.log(`${destA}  (artifact body)`);
