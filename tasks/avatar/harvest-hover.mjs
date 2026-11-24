// Collects every control's words and hover label from the web pilot, so the phone room carries them over word for word.
//   node tasks/avatar/harvest-hover.mjs      writes tasks/avatar/AVA_HOVER_LABELS.md
// Reads tools/avatarlab/al-*.js (not the break tests). A control is a ui call whose first argument is a quoted label and whose
// options carry `title: '...'` (the pilot's hover label). Labels built at run time (a part's own name, a stance's name) cannot
// be read this way; they are counted at the end and listed by file so a slice can find them.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url)), pilot = join(here, '..', '..', 'tools', 'avatarlab');
const files = readdirSync(pilot).filter((f) => /^al-.*\.js$/.test(f) && !/breaktest|format/.test(f)).sort();
const Q = "'((?:[^'\\\\]|\\\\.)*)'";
const KINDS = 'chip|slider|tick|seg|toggle|check|button|btn|iconBtn|pick|sw|switch2|ctl|opt|card|tile';
// the label, then anything (a function body too) up to its own title, never crossing into the next control's call
const call = new RegExp('\\b(' + KINDS + ')\\(' + Q + '((?:(?!\\b(?:' + KINDS + ')\\()[\\s\\S]){0,900}?)title:\\s*' + Q, 'g');
const loose = new RegExp('title:\\s*' + Q, 'g');
const un = (s) => s.replace(/\\u2019/g, '’').replace(/\\u2026/g, '…').replace(/\\u203a/g, '›').replace(/\\u00d7/g, '×').replace(/\\'/g, "'").replace(/\|/g, '\\|');
let out = ['# Avatar Studio: every control and its hover label (harvested from the pilot)', '',
  'Made by `node tasks/avatar/harvest-hover.mjs` from `tools/avatarlab/al-*.js`. The phone room uses these words as they are',
  '(rule: every button gets a hover label). Part A of [the phone spec](AVA_PHONE_SPEC.md) says where each control sits.',
  'A slice moves its rows into `strings_avatar.xml` (control words) and `strings_hover_avatar.xml` (hover labels).', ''];
let total = 0, dyn = [];
for (const f of files) {
  const src = readFileSync(join(pilot, f), 'utf8'), rows = [], seen = new Set();
  let m; call.lastIndex = 0;
  while ((m = call.exec(src))) { const k = m[2] + '\u0000' + m[4]; if (seen.has(k)) continue; seen.add(k); rows.push([un(m[2]), un(m[4]), m[1]]); }
  const all = (src.match(loose) || []).length;
  if (all > rows.length) dyn.push(f + ' (' + (all - rows.length) + ')');
  if (!rows.length) continue;
  total += rows.length;
  out.push('## `' + f + '`', '', '| Control | Kind | Hover label |', '|---|---|---|');
  rows.forEach((r) => out.push('| ' + (r[0] || '(icon only)') + ' | ' + r[2] + ' | ' + r[1] + ' |'));
  out.push('');
}
out.push('## Built at run time', '', 'Hover labels whose control words are made while the room runs (a part, a stance, a button, a shot by its own',
  'name). Read them in these files: ' + (dyn.join(', ') || 'none') + '.', '', total + ' controls listed.', '');
writeFileSync(join(here, 'AVA_HOVER_LABELS.md'), out.join('\n'));
console.log(total + ' controls; run-time labels in ' + dyn.length + ' files');
