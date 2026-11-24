"""Page text for the Joy Creator website: home, one page per wing, contact.

Used by tools/make_site.py. Claims here must match tasks/STATE.md ("built", never "proven on every phone").
No prices (owner ruling: no subscription; packs maybe later). Placeholders are written in [CAPITALS] so the
site builder shows its "draft" banner while any remain.
Room colours are taken from the shared palette in tasks/design/Zai-potential-website-landing-page-desighn.html.
"""

CONTACT_EMAIL = "studio@joycreator.cc"   # stand-in until the owner confirms (OWNER_CLICKS step 2)

# slug, name, colour a, colour b, one-line tag, intro, what you can do (bullets), "good to know"
WINGS = [
    ("joybrush", "JoyBrush", "#f43f8e", "#ff7ab8", "Paint and animate",
     "Brushes made to feel like the real thing, on a canvas built for a phone and a stylus.",
     ["Pencil, oil and watercolour brushes that respond to pressure and tilt from a stylus.",
      "Watercolour that runs on a wet sheet, so you can watch it dry.",
      "Layers, frames and animation clips.",
      "Paper surfaces with real tooth under the brush.",
      "Select a drawing, copy it, nudge it, and keep your lines editable.",
      "Boards: image, animation and sprite regions you can size, move and export."],
     "JoyBrush is the newest and most ambitious wing, and some of it is still being tried on real phones. "
     "Frame in-betweens are not finished yet, so they are not offered."),
    ("studio", "Studio", "#35f6bf", "#97fe8b", "Edit video and sound",
     "A multi-track timeline for the videos you actually want to make.",
     ["Several tracks. Split, trim, reorder, speed, crop and zoom.",
      "Keyframes with easing, masks and green screen.",
      "Titles, effects and transitions.",
      "Record a voice-over right on the timeline.",
      "Captions from speech, made on your phone with no upload.",
      "Drop in your VisLab visuals and TextLab titles, then export in the background to the folder you chose."],
     "Studio grew out of FadCam, a free open-source recorder, and keeps its camera and screen capture."),
    ("vislab", "VisLab", "#5c43fd", "#4397fd", "Music visuals you build",
     "Looks that move with the music, and a lab where you build your own.",
     ["Forty-odd looks that react to sound.",
      "Build from parts you can browse, tweak and mix, starting from nothing if you like.",
      "Use a look as a layer in a Studio video.",
      "Float a visual over other apps while you play music.",
      "Words can be part of the art."],
     "The lab is the point: finished looks are only starting places."),
    ("textlab", "TextLab", "#faa03d", "#fc6818", "Film-poster titles",
     "Title text with looks like chrome, gold, neon and fire, drawn live on your own words.",
     ["Dozens of looks, from metal and neon to sand, ink and sequins.",
      "Type your own words; the look is drawn live.",
      "Export a picture, a video or a moving sheet.",
      "A title in Studio can wear a look, and move with a tilt."],
     "TextLab is new; the owner's verdicts on the newest looks are still to come."),
    ("spritelab", "SpriteLab", "#f9f462", "#ffc341", "Characters and puppets",
     "Cut sprite sheets, build animations, and puppeteer a character on your timeline.",
     ["Cut a sheet into frames and line them up.",
      "Build animations from the frames.",
      "Puppeteer a character in Studio.",
      "Everything the sprite editor makes is stored with your project."],
     "SpriteLab began as a desktop helper; the phone editor is the real one."),
    ("avatar", "Avatar Studio", "#cc27ff", "#8c3dfa", "Perform as a character",
     "Make a character that moves when you do, and record the performance.",
     ["A drawn character that follows your face and voice.",
      "Built to let you record a performance and take it into Studio."],
     "Avatar Studio is being rebuilt for the phone right now, so this page says only what it is for. "
     "What it can do at launch is still to be decided."),
    ("library", "Library", "#4397fd", "#55e0f9", "Everything you made, in one place",
     "One folder you choose holds all of your work. Library shows it.",
     ["Pictures, videos, sprite sheets, looks and sounds side by side.",
      "Open anything straight into the room that made it.",
      "Your files stay readable in a normal file manager."],
     "Removing the app never deletes your work."),
    ("joybot", "Joybot", "#22d3ee", "#5c43fd", "A helper who knows the whole app",
     "Ask how to do something, or ask Joybot to do the job.",
     ["Answers about every room, in plain words.",
      "Changes your project only in steps you can undo.",
      "Optional: it uses your own key from the AI service you like, so the rest of the app never needs one."],
     "Without a key, Joybot still offers tips. Nothing is sent anywhere until you connect it and ask."),
]

WING_BY_SLUG = {w[0]: w for w in WINGS}


def wing_nav(current=None):
    chips = "".join('<a class="chip%s" style="--a:%s" href="%s.html">%s</a>'
                    % (" on" if w[0] == current else "", w[2], w[0], w[1]) for w in WINGS)
    return '<nav class="chips" aria-label="Rooms">%s</nav>' % chips


def wing_page(slug):
    _, name, ca, cb, tag, intro, bullets, note = WING_BY_SLUG[slug]
    items = "".join("<li>%s</li>" % b for b in bullets)
    return """
<div class="hero" style="--a:%s;--b:%s">
<p class="eyebrow">%s</p>
<h1>%s</h1>
<p class="lead">%s</p>
</div>
<h2>What you can do</h2>
<ul class="ticks">%s</ul>
<div class="shot" style="--a:%s"><span>[SCREENSHOT OF %s]</span></div>
<p class="note"><strong>Good to know.</strong> %s</p>
%s
<p><a class="btn" href="%s">Get it on Google Play</a><a class="btn ghost" href="index.html">Back to all rooms</a></p>
""" % (ca, cb, tag, name, intro, items, ca, name.upper(), note, wing_nav(slug), "index.html#play")


INDEX = """
<div class="hero big">
<p class="eyebrow">A creative studio on your phone</p>
<h1>Paint it. Animate it. Cut it. Score it.</h1>
<p class="lead">Joy Creator puts painting, animation, video editing, music visuals, film-poster titles and
characters in one app, and keeps everything you make in one folder you choose. Free for good: no ads, no
subscription, no watermark, no account.</p>
<p><a class="btn" href="#play">Get it on Google Play</a><a class="btn ghost" href="testers.html">Help test it</a></p>
</div>

<div class="shot wide"><span>[SIZZLE VIDEO GOES HERE]</span></div>

<h2>The rooms</h2>
<p>Each room has its own colour, so you always know where you are.</p>
<div class="cards">%(cards)s</div>

<h2 id="joybot">Joybot, your helper</h2>
<p>Joybot knows the whole app. Ask it how to do something, or ask it to do the job. It changes your project only in
steps you can undo, and it uses your own key from the AI service you like, so the rest of Joy Creator never needs one.
<a href="joybot.html">More about Joybot</a>.</p>

<h2>Yours to keep</h2>
<ul class="ticks">
<li>One folder, chosen by you. Open it with any file manager, back it up, move it.</li>
<li>Removing the app never deletes your work.</li>
<li>No analytics, no tracking, and I never receive your work. <a href="privacy.html">The privacy policy lists every case.</a></li>
<li>Open source (GNU GPL v3).</li>
</ul>

<h2 id="play">Get it</h2>
<p>Joy Creator is in beta and coming to Google Play.</p>
<div class="shot"><span>[PLAY STORE LINK GOES HERE]</span></div>
<p><a class="btn ghost" href="testers.html">Help test the beta</a><a class="btn ghost" href="contact.html">Contact</a></p>
"""


def index_body():
    cards = "".join(
        '<a class="card" style="--a:%s;--b:%s" href="%s.html"><strong>%s</strong><span>%s</span></a>'
        % (w[2], w[3], w[0], w[1], w[4]) for w in WINGS)
    return INDEX % {"cards": cards}


CONTACT = """
<h1>Contact</h1>
<p>Questions, bugs, ideas, or something that confused you: I want to hear about it.</p>
<p><strong>Email:</strong> [CONTACT EMAIL TO BE CONFIRMED BY THE OWNER] (for now %s)</p>
<p>If you are a beta tester, the <a href="testers.html">tester page</a> has the feedback form.</p>
<p>How your data is handled is in the <a href="privacy.html">privacy policy</a>.</p>
""" % CONTACT_EMAIL

EXTRA_CSS = """
.hero{margin:1rem 0 1.5rem;padding:1.25rem 16px;border-radius:14px;background:var(--panel);border:1px solid var(--line);border-left:6px solid var(--a,var(--sel))}
.hero.big{border-left:0;background:linear-gradient(135deg,var(--panel),var(--bg2))}
.hero h1{margin:.2rem 0 .6rem;font-size:2.1rem}
.eyebrow{margin:0;color:var(--a,var(--sel));font-weight:700;text-transform:uppercase;letter-spacing:.06em;font-size:.8rem}
.lead{font-size:1.1rem;color:var(--ink)}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;margin:1rem 0}
.card{display:flex;flex-direction:column;gap:.2rem;padding:.8rem;border-radius:12px;background:var(--panel);border:1px solid var(--line);border-top:4px solid var(--a);text-decoration:none;color:var(--ink)}
.card span{color:var(--dim);font-size:.9rem}.card:hover{border-color:var(--a)}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin:1.5rem 0}
.chip{padding:.35rem .8rem;border-radius:999px;border:1px solid var(--a);color:var(--ink);text-decoration:none;font-size:.9rem}
.chip.on{background:var(--a);color:#050507;font-weight:700}
.ticks{list-style:none;padding:0}.ticks li{padding-left:1.4rem;position:relative;margin:.4rem 0}
.ticks li::before{content:"";position:absolute;left:0;top:.55em;width:.7em;height:.7em;border-radius:3px;background:var(--a,var(--sel))}
.shot{display:flex;align-items:center;justify-content:center;min-height:11rem;margin:1.2rem 0;border:2px dashed var(--line2,#3a3a46);border-radius:14px;color:var(--dim);text-align:center;padding:1rem}
.shot.wide{min-height:14rem}
.note{color:var(--dim)}
@media (prefers-color-scheme:light){.chip.on,.hero{color:#17171c}}
"""
