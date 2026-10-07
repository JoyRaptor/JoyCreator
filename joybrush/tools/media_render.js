// media_render.js — render a media-lab test sheet headless and save it as a PNG, so a look can be checked
// as an image before anyone touches the phone.
//   node tools/media_render.js <out.png> "<query>"      e.g.  node tools/media_render.js out.png "test=pencil&paper=pulp_artisan"
// Needs playwright-core (npm i --no-save playwright-core, or NODE_PATH to an existing install) and Edge/Chrome.
const fs = require('fs');
const http = require('http');
const path = require('path');
let chromium;
try { ({ chromium } = require('playwright-core')); }
catch { ({ chromium } = require(path.join(process.env.TEMP || '', 'jbl/joybrush/tools/node_modules/playwright-core'))); }

const root = path.join(__dirname, '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png',
  '.glsl': 'text/plain', '.frag': 'text/plain', '.vert': 'text/plain', '.jpg': 'image/jpeg' };

function serve() {
  return new Promise(resolve => {
    const server = http.createServer((req, res) => {
      const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
      if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream' });
      fs.createReadStream(p).pipe(res);
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

(async () => {
  const [out, query = 'test=pencil'] = process.argv.slice(2);
  if (!out) { console.error('usage: node media_render.js <out.png> "<query>"'); process.exit(2); }
  const server = await serve();
  const port = server.address().port;
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const exe = process.env.CHROME || edge;
  const args = process.env.SWIFTSHADER ? ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] : ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'];
  const browser = await chromium.launch({ executablePath: exe, args });
  const page = await browser.newPage({ viewport: { width: 800, height: 600 }, deviceScaleFactor: 1 });
  const logs = [];
  page.on('console', m => logs.push(m.text()));
  page.on('pageerror', e => logs.push('pageerror: ' + e.message));
  const t0 = Date.now();
  await page.goto(`http://127.0.0.1:${port}${process.env.PAGE || '/lab/media/index.html'}?${query}`);
  await page.waitForFunction('window.__done === true', null, { timeout: 180000 });
  const err = await page.evaluate('window.__error || null');
  const info = await page.evaluate(() => {
    const gl = document.getElementById('c').getContext('webgl2');
    const dbg = gl.getExtension('WEBGL_debug_renderer_info');
    return dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : 'unknown';
  });
  if (err) { console.error(err); }
  if (process.env.PROBE) console.log(JSON.stringify(await page.evaluate(pts => pts.map(p => [p, window.__probe(p[0], p[1])]), JSON.parse(process.env.PROBE))));
  const dataUrl = await page.evaluate(() => document.getElementById('c').toDataURL('image/png'));
  fs.writeFileSync(out, Buffer.from(dataUrl.split(',')[1], 'base64'));
  console.log(JSON.stringify({ out, ms: Date.now() - t0, renderer: info, logs: logs.slice(-10) }));
  await browser.close();
  server.close();
  process.exit(err ? 1 : 0);
})();
