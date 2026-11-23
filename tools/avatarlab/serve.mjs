// A tiny local server for the pilot:  node tools/avatarlab/serve.mjs [port]
// Static files with caching OFF (so an edit shows on reload), and PUT into local/ only, which is how the
// pilot saves a rigged character next to itself (local/ is ignored by git: owner art never gets committed).
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { extname, join, normalize, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url));
const port = Number(process.argv[2]) || 8795;
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.css': 'text/css', '.md': 'text/plain; charset=utf-8' };

createServer(async (req, res) => {
  try {
    const u = new URL(req.url, 'http://x');
    let rel = normalize(decodeURIComponent(u.pathname)).replace(/^[\\/]+/, '');
    if (!rel) rel = 'index.html';
    const file = join(root, rel);
    if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    if (req.method === 'PUT') {
      if (!rel.split(sep).join('/').startsWith('local/')) { res.writeHead(403).end('only local/ can be written'); return; }
      const chunks = []; for await (const c of req) chunks.push(c);
      await mkdir(dirname(file), { recursive: true }); await writeFile(file, Buffer.concat(chunks));
      res.writeHead(204).end(); return;
    }
    const data = await readFile(file);
    res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' }); res.end(data);
  } catch (e) { res.writeHead(404).end('not found'); }
}).listen(port, '127.0.0.1', () => console.log('avatarlab on http://127.0.0.1:' + port));
