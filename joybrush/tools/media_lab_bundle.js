// media_lab_bundle.js — pack the media lab into ONE self-contained HTML page (shaders, scripts and lab
// papers inlined) so it can be opened on the phone from a single link.
//   node tools/media_lab_bundle.js <out.html>
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const lab = path.join(root, 'lab', 'media');
const out = process.argv[2] || path.join(lab, 'build', 'media_lab.html');

// Scripts: each ES module becomes a scope that returns its exports; imports read from those scopes.
const order = ['gl.js', 'paper.js', 'stick.js', 'tests.js', 'wet.js', 'spline.js', 'paste.js', 'engine.js', 'vector.js', 'main.js'];
let js = '';
for (const f of order) {
  let src = fs.readFileSync(path.join(lab, 'js', f), 'utf8');
  const exports = [];
  // One name per export line: "export const A = 1, B = 2" would silently drop B from the bundle.
  const multi = src.match(/^export\s+(const|let)\s+\w+\s*=[^;\n]*,\s*\w+\s*=/m);
  if (multi) throw new Error(f + ': split this export into one declaration per line: ' + multi[0]);
  src = src.replace(/^import\s*\{([^}]+)\}\s*from\s*'\.\/([\w.]+)';?$/gm, (_, names, mod) =>
    `const {${names}} = __m['${mod}'];`);
  src = src.replace(/^export\s+(async\s+function|function|const|let|class)\s+(\w+)/gm, (_, kind, name) => {
    exports.push(name);
    return `${kind} ${name}`;
  });
  js += `__m['${f}'] = (() => {\n${src}\nreturn { ${exports.join(', ')} };\n})();\n`;
}

// Shaders: the media shaders and exactly what they #include (nothing else is shipped).
const shaders = {};
const add = name => {
  if (shaders[name] !== undefined) return;
  const src = fs.readFileSync(path.join(root, 'shaders', name), 'utf8');
  shaders[name] = src;
  for (const m of src.matchAll(/^\s*#include\s+"([^"]+)"/gm)) add(m[1]);
};
for (const f of fs.readdirSync(path.join(root, 'shaders', 'media'))) if (/\.(vert|frag)$/.test(f)) add('media/' + f);

// Papers: a few of the paper session's papers (height + fluid maps; the look photos stay out).
const files = {};
const app = JSON.parse(fs.readFileSync(path.join(root, 'assets', 'paper', 'catalogue.json'), 'utf8'));
const pick = ['drawing_tooth', 'bristol_tooth', 'cold_press', 'hot_press', 'rough_press', 'canvas_linen', 'cardboard'];
const appCat = { surfaces: app.surfaces.filter(s => pick.includes(s.id)), looks: app.looks.filter(l => pick.includes(l.defaultSurface)).map(l => ({ ...l, file: null })) };
for (const s of appCat.surfaces) for (const f of [s.file, s.fluid].filter(Boolean))
  files['../../assets/paper/' + f] = fs.readFileSync(path.join(root, 'assets', 'paper', f)).toString('base64');
const bundle = { shaders, json: { '../../assets/paper/catalogue.json': appCat }, files };

const html = fs.readFileSync(path.join(lab, 'index.html'), 'utf8');
const title = html.match(/<title>[\s\S]*?<\/title>/)[0];
const style = html.match(/<style>[\s\S]*?<\/style>/)[0];
const body = html.match(/<body>([\s\S]*?)<script/)[1];
const page = `${title}\n${style}\n${body}\n<script>globalThis.__LAB_BUNDLE = ${JSON.stringify(bundle)};</script>\n` +
  `<script type="module">\nconst __m = {};\n${js}</script>\n`;
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, page);
console.log(out, (page.length / 1e6).toFixed(2) + ' MB');
