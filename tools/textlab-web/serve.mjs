// TextLab look book: the dev server.
//   node tools/textlab-web/serve.mjs [port]
//
// Serves the whole repository, never cached, so the page reads the same shader and look files the
// app ships (app/src/main/assets/textlab). Open  http://127.0.0.1:8791/tools/textlab-web/
//
// POST /report keeps what a page measured (frame times on a phone's own GPU, compile errors) in
// the system temp folder as textlab-reports.jsonl, one JSON object per line. GET /report reads it.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const port = Number(process.argv[2] || process.env.PORT || 8791);
const reports = path.join(os.tmpdir(), 'textlab-reports.jsonl');
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.css': 'text/css; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.webp': 'image/webp', '.ttf': 'font/ttf', '.otf': 'font/otf', '.svg': 'image/svg+xml',
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/report') {
    if (req.method === 'POST') {
      let body = '';
      req.on('data', (c) => { body += c; if (body.length > 1e6) req.destroy(); });
      req.on('end', () => {
        fs.appendFileSync(reports, body.replace(/\n/g, ' ') + '\n');
        console.log('report ' + body.slice(0, 300));
        res.writeHead(204).end();
      });
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(fs.existsSync(reports) ? fs.readFileSync(reports) : '');
    return;
  }
  let rel = decodeURIComponent(url.pathname);
  if (rel.endsWith('/')) rel += 'index.html';
  const file = path.join(root, rel);
  if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found: ' + rel); return; }
    res.writeHead(200, {
      'Content-Type': types[path.extname(file).toLowerCase()] || (/\.(glsl|frag|vert)$/.test(file) ? 'text/plain; charset=utf-8' : 'application/octet-stream'),
      'Cache-Control': 'no-store',
    });
    res.end(data);
  });
}).listen(port, '0.0.0.0', () => console.log(`TextLab look book: http://127.0.0.1:${port}/tools/textlab-web/`));
