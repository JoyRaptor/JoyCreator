// media_measure.js — numbers instead of eyeballs: renders a test sheet headless and evaluates expressions
// against the page's measurement hooks (__dark, __row, __probe).
//   node tools/media_measure.js "<query>" "<js expression returning JSON-able>"
const fs = require('fs');
const http = require('http');
const path = require('path');
let chromium;
try { ({ chromium } = require('playwright-core')); }
catch { ({ chromium } = require(path.join(process.env.TEMP || '', 'jbl/joybrush/tools/node_modules/playwright-core'))); }
const root = path.join(__dirname, '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png' };
(async () => {
  const [query, expr] = process.argv.slice(2);
  const server = http.createServer((req, res) => {
    const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
    if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'text/plain' });
    fs.createReadStream(p).pipe(res);
  }).listen(0, '127.0.0.1');
  await new Promise(r => server.on('listening', r));
  const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', args: ['--use-angle=d3d11'] });
  const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
  await page.goto(`http://127.0.0.1:${server.address().port}/lab/media/index.html?${query}`, { timeout: 120000 });
  await page.waitForFunction('window.__done === true', null, { timeout: 300000 });
  const err = await page.evaluate('window.__error || null');
  if (err) { console.error(err); process.exit(1); }
  console.log(JSON.stringify(await page.evaluate(expr)));
  await browser.close(); server.close();
})();
