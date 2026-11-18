// Folds the pilot into ONE file for publishing as a private Claude artifact:
//   node tools/avatarlab/build.mjs      ->  tools/avatarlab/dist/avatar-studio.html
// The artifact host supplies <html>/<head>/<body>, so this keeps only the title, fonts, styles,
// the page body, and every script inlined in order.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, 'index.html'), 'utf8');
const pick = (re) => { const m = html.match(re); if (!m) throw new Error('index.html is missing ' + re); return m[0]; };

const title = pick(/<title>[\s\S]*?<\/title>/);
const sides = pick(/<meta name="claude:viewport-fit-sides"[^>]*>/);
const fonts = pick(/<link rel="stylesheet" href="https:\/\/fonts\.googleapis\.com[^>]*>/);
const style = pick(/<style>[\s\S]*?<\/style>/);
let body = pick(/<body>[\s\S]*<\/body>/).replace(/^<body>/, '').replace(/<\/body>$/, '');
body = body.replace(/<script src="([^"]+)"><\/script>/g, (_, src) => {
  const js = readFileSync(join(here, src), 'utf8');
  if (js.includes('</script')) throw new Error(src + ' contains a closing script tag');
  return '<script>\n' + js + '\n</script>';
});
mkdirSync(join(here, 'dist'), { recursive: true });
const out = [title, sides, fonts, style, body].join('\n');
writeFileSync(join(here, 'dist', 'avatar-studio.html'), out);
console.log('dist/avatar-studio.html', (out.length / 1024).toFixed(0) + ' KB');
