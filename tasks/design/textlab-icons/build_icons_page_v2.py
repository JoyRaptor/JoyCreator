"""Build the TextLab icon review page from textlab-icons.json.  python -I build_icons_page.py <icons.json> <out.html>"""
import html
import json
import sys

icons = json.load(open(sys.argv[1], encoding='utf-8'))
v1 = {i['word'] + '|' + i['group']: i for i in json.load(open(sys.argv[3], encoding='utf-8'))}
UNMARKED = {'Plain', 'Ember'}
words = [i['word'] for i in icons]
for ic in icons:
    o = v1.get(ic['word'] + '|' + ic['group'])
    ic['status'] = 'new' if o is None else 'changed' if o['svg'] != ic['svg'] else 'unmarked' if ic['word'] in UNMARKED else 'kept'
    ic['label'] = ic['word'] + (' (' + ('Look row' if ic['group'] == 'Look row' else 'tab') + ')' if words.count(ic['word']) > 1 else '')
order = [('Redrawn for you', ('changed', 'new')), ('Not marked yet', ('unmarked',)), ('Already kept', ('kept',))]
groups = [(g, [i for i in icons if i['status'] in s]) for g, s in order]
intro = {
    'Redrawn for you': 'The ten you asked to change, drawn the way you described, plus the new Export icon. Sides and Depth are also shown swapped so you can choose either way round.',
    'Not marked yet': 'These two had no mark last time. They are unchanged.',
    'Already kept': 'The ones you approved, unchanged and already marked Keep. Tap Change on any of them if a second look changes your mind.',
}
BADGE = {'changed': 'Redrawn', 'new': 'New', 'unmarked': 'Unmarked', 'kept': 'Kept'}

cards = []
n = 0
for g, items in groups:
    cards.append('<section class="group"><h2>%s</h2><p class="lede">%s</p><div class="grid">' % (
        html.escape(g), html.escape(intro.get(g, ''))))
    for ic in items:
        n += 1
        key = '%d' % n
        svg = ic['svg']
        word = html.escape(ic['word'])
        label = html.escape(ic['label'])
        cards.append(
            '<article class="card" data-key="%s" data-word="%s" data-status="%s">'
            '<span class="badge %s">%s</span>'
            '<div class="row">'
            '<span class="pill">%s<span>%s</span></span>'
            '<span class="pill on">%s<span>%s</span></span>'
            '</div>'
            '<div class="big" aria-hidden="true">%s</div>'
            '<p class="means">%s</p>'
            '<p class="idea">%s</p>'
            '<div class="vote" role="group" aria-label="%s">'
            '<button type="button" class="keep" id="keep-%s" aria-pressed="false" title="Keep this icon">✓ Keep</button>'
            '<button type="button" class="change" id="change-%s" aria-pressed="false" title="Ask for a different icon">✎ Change</button>'
            '</div>'
            '<label class="say" hidden for="note-%s">What to change<input id="note-%s" type="text" placeholder="e.g. simpler, a flame not a drop"></label>'
            '</article>' % (key, label, ic['status'], ic['status'], BADGE[ic['status']], svg, word, svg, word, svg, html.escape(ic.get('meaning', '')),
                            html.escape(ic.get('note', '')), label, key, key, key, key))
    cards.append('</div></section>')

page = '''<meta charset="utf-8">
<title>TextLab Icons</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@500;700;800&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
/* Layout: one column of groups; each group a wrapping grid of icon cards, as the app's dark drawer shows them. */
:root {
  --ground: #0b0b0c; --panel: #141417; --control: #1f1f26; --line: #2a2a31;
  --ink: #e4e4e7; --dim: #8a8a96; --cyan: #22d3ee; --on-ink: #050507; --amber: #fbbf24;
  --display: "Archivo", "Helvetica Neue", Arial, sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace;
  color-scheme: dark;
}
body { background: var(--ground); color: var(--ink); font: 15px/1.5 var(--display); }
.wrap { max-width: 1100px; margin: 0 auto; padding-block: 28px 120px;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
h1 { font-weight: 800; font-size: clamp(28px, 5vw, 44px); letter-spacing: -0.01em; margin: 0 0 6px; text-wrap: balance; }
.sub { color: var(--dim); max-width: 62ch; margin: 0 0 8px; }
.how { font-family: var(--mono); font-size: 12.5px; color: var(--dim); margin: 0 0 24px; }
.group { margin-top: 34px; }
h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.12em; color: var(--dim); margin: 0 0 4px; font-weight: 700; }
.lede { color: var(--dim); margin: 0 0 14px; max-width: 62ch; font-size: 14px; }
.grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 14px;
  display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.card.is-keep { border-color: rgba(34, 211, 238, 0.55); }
.card.is-change { border-color: rgba(251, 191, 36, 0.6); }
.badge { align-self: flex-start; font: 500 11px var(--mono); letter-spacing: 0.06em; text-transform: uppercase;
  padding: 2px 8px; border-radius: 10px; background: var(--control); color: var(--dim); }
.badge.changed, .badge.new { background: rgba(34, 211, 238, 0.14); color: var(--cyan); }
.badge.unmarked { background: rgba(251, 191, 36, 0.14); color: var(--amber); }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.pill { display: inline-flex; align-items: center; gap: 7px; height: 32px; padding: 0 12px 0 9px; border-radius: 16px;
  background: var(--control); color: var(--ink); font-size: 13px; font-weight: 500; white-space: nowrap; }
.pill svg { width: 20px; height: 20px; flex: none; }
.pill.on { background: var(--cyan); color: var(--on-ink); font-weight: 700; }
.big { color: var(--ink); display: flex; gap: 14px; align-items: center; }
.big svg { width: 56px; height: 56px; }
.means { margin: 0; font-size: 13.5px; }
.idea { margin: 0; font-size: 12.5px; color: var(--dim); }
.vote { display: flex; gap: 6px; margin-top: auto; }
.vote button { flex: 1; height: 34px; border-radius: 17px; border: 1px solid var(--line); background: transparent;
  color: var(--ink); font: 600 13px var(--display); cursor: pointer; }
.vote button:focus-visible, .say input:focus-visible, .bar button:focus-visible { outline: 2px solid var(--cyan); outline-offset: 2px; }
.vote .keep[aria-pressed="true"] { background: var(--cyan); color: var(--on-ink); border-color: var(--cyan); }
.vote .change[aria-pressed="true"] { background: var(--amber); color: var(--on-ink); border-color: var(--amber); }
.say { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--dim); }
.say[hidden] { display: none; }
.say input { background: var(--ground); color: var(--ink); border: 1px solid var(--line); border-radius: 8px;
  padding: 7px 9px; font: 13px var(--display); min-width: 0; }
.bar { position: fixed; left: 0; right: 0; bottom: 0; background: rgba(11, 11, 12, 0.92); border-top: 1px solid var(--line);
  backdrop-filter: blur(8px); padding: 10px max(16px, env(safe-area-inset-right, 0px)) calc(10px + env(safe-area-inset-bottom, 0px)) max(16px, env(safe-area-inset-left, 0px)); }
.bar .in { max-width: 1100px; margin: 0 auto; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.bar output { flex: 1 1 260px; min-width: 0; font-family: var(--mono); font-size: 12px; color: var(--dim);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.bar button { height: 36px; padding: 0 16px; border-radius: 18px; border: 0; background: var(--cyan); color: var(--on-ink);
  font: 700 13px var(--display); cursor: pointer; }
.count { font-family: var(--mono); font-size: 12px; color: var(--ink); white-space: nowrap; }
@media (prefers-reduced-motion: no-preference) { .card { transition: border-color 0.15s; } }
</style>
<div class="wrap">
  <h1>TextLab Icons</h1>
  <p class="sub">Round two. The icons you asked to change are redrawn and come first, with the new Export icon. Everything is
  drawn in one line weight so the set reads as a family at 20 pixels on the app’s dark drawers.</p>
  <p class="how">Mark each one ✓ Keep or ✎ Change (and say what to change), then copy your picks and paste them back to me.</p>
  ''' + ''.join(cards) + '''
</div>
<div class="bar"><div class="in">
  <span class="count" id="count"></span>
  <output id="picks" aria-live="polite">Nothing marked yet</output>
  <button type="button" id="copy">Copy my picks</button>
</div></div>
<script>
(function () {
  var cards = Array.prototype.slice.call(document.querySelectorAll('.card'));
  var state = {};
  var saved = null;
  try { saved = JSON.parse(localStorage.getItem('textlab-icon-picks-v2') || 'null'); } catch (e) { saved = null; }
  if (saved) { state = saved; } else { cards.forEach(function (c) { if (c.dataset.status === 'kept') state[c.dataset.key] = { v: 'keep' }; }); }
  function save() { try { localStorage.setItem('textlab-icon-picks-v2', JSON.stringify(state)); } catch (e) {} }
  function line() {
    var keep = [], change = [];
    cards.forEach(function (c) {
      var s = state[c.dataset.key]; if (!s) return;
      if (s.v === 'keep') keep.push(c.dataset.word);
      if (s.v === 'change') change.push(c.dataset.word + (s.note ? ' (' + s.note + ')' : ''));
    });
    var parts = [];
    if (keep.length) parts.push('KEEP: ' + keep.join(', '));
    if (change.length) parts.push('CHANGE: ' + change.join('; '));
    return parts.join(' | ');
  }
  function paint() {
    var k = 0, ch = 0;
    cards.forEach(function (c) {
      var s = state[c.dataset.key] || {};
      var keep = c.querySelector('.keep'), change = c.querySelector('.change'), say = c.querySelector('.say');
      keep.setAttribute('aria-pressed', s.v === 'keep' ? 'true' : 'false');
      change.setAttribute('aria-pressed', s.v === 'change' ? 'true' : 'false');
      c.classList.toggle('is-keep', s.v === 'keep');
      c.classList.toggle('is-change', s.v === 'change');
      say.hidden = s.v !== 'change';
      if (s.v === 'keep') k++; if (s.v === 'change') ch++;
    });
    document.getElementById('count').textContent = k + ' keep · ' + ch + ' change · ' + (cards.length - k - ch) + ' to go';
    document.getElementById('picks').textContent = line() || 'Nothing marked yet';
  }
  cards.forEach(function (c) {
    var key = c.dataset.key, input = c.querySelector('input');
    if (state[key] && state[key].note) input.value = state[key].note;
    c.querySelector('.keep').addEventListener('click', function () {
      state[key] = state[key] && state[key].v === 'keep' ? undefined : { v: 'keep' }; if (!state[key]) delete state[key]; save(); paint();
    });
    c.querySelector('.change').addEventListener('click', function () {
      state[key] = state[key] && state[key].v === 'change' ? undefined : { v: 'change', note: input.value }; if (!state[key]) delete state[key]; save(); paint();
    });
    input.addEventListener('input', function () { if (state[key]) { state[key].note = input.value; save(); paint(); } });
  });
  document.getElementById('copy').addEventListener('click', function () {
    var text = 'TextLab icons — ' + (line() || 'nothing marked');
    var out = document.getElementById('picks');
    function fallback() { var r = document.createRange(); r.selectNodeContents(out); var s = window.getSelection(); s.removeAllRanges(); s.addRange(r); out.textContent = text; }
    try {
      navigator.clipboard.writeText(text).then(function () { out.textContent = 'Copied. Paste it to me in the chat.'; setTimeout(paint, 2500); }, fallback);
    } catch (e) { fallback(); }
  });
  paint();
})();
</script>
'''
open(sys.argv[2], 'w', encoding='utf-8').write(page)
print('icons', n)
