#!/usr/bin/env python3
"""Builds the small GitHub Pages site in docs/ from the repository's own text files.

Run:  python -B tools/make_site.py        (no packages needed)
Pages: docs/index.html (written below), docs/privacy.html (from PRIVACY.md),
       docs/testers.html (from tasks/SHOW2_tester_invite.md), docs/trademark.html (from TRADEMARK.md).
While a source still holds a [PLACEHOLDER IN CAPITALS] the page shows a "draft, not reviewed by the owner" banner.
Block quotes (the internal draft notes at the top of the sources) are not published.
The site uses system fonts only and loads no outside scripts, fonts or images.
"""
import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

CSS = """
:root{color-scheme:dark;--bg:#050507;--panel:#111114;--line:#232329;--ink:#ececf1;--dim:#a1a1aa;--sel:#22d3ee;--warn:#faa03d}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:17px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:46rem;margin:0 auto;padding:1.5rem 16px 4rem}
header.site{max-width:46rem;margin:0 auto;padding:1rem 16px 0;display:flex;gap:1.25rem;flex-wrap:wrap;align-items:baseline}
header.site a{color:var(--dim);text-decoration:none}header.site a.brand{color:var(--ink);font-weight:800;font-size:1.1rem}
header.site a:hover{color:var(--sel)}
h1{font-size:2rem;line-height:1.2;margin:1.2rem 0 .6rem}h2{font-size:1.3rem;margin:2rem 0 .5rem;border-top:1px solid var(--line);padding-top:1rem}
a{color:var(--sel)}p,li{max-width:42rem}
table{border-collapse:collapse;width:100%;margin:1rem 0;font-size:.95rem}
th,td{border:1px solid var(--line);padding:.5rem .6rem;text-align:left;vertical-align:top}th{background:var(--panel)}
.tablewrap{overflow-x:auto}
code{background:var(--panel);padding:.1rem .3rem;border-radius:4px}
.draft{border:1px solid var(--warn);color:var(--warn);padding:.6rem .9rem;border-radius:8px;margin:1rem 0}
footer{max-width:46rem;margin:0 auto;padding:0 16px 3rem;color:var(--dim);font-size:.9rem}
.btn{display:inline-block;background:var(--sel);color:#001;padding:.6rem 1rem;border-radius:999px;font-weight:700;text-decoration:none;margin:.3rem .4rem .3rem 0}
.btn.ghost{background:transparent;color:var(--ink);border:1px solid var(--line)}
@media (prefers-color-scheme:light){:root{--bg:#fff;--panel:#f4f4f6;--line:#d8d8de;--ink:#17171c;--dim:#55555f;--sel:#0a7f94;--warn:#a85a00}.btn{color:#fff}}
"""

NAV = ('<header class="site"><a class="brand" href="index.html">Joy Creator</a>'
       '<a href="testers.html">Test it</a><a href="privacy.html">Privacy</a><a href="trademark.html">Names</a></header>')


def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", t)

    def link(m):
        text, url = m.group(1), m.group(2)
        if not url.startswith(("http://", "https://", "mailto:")):
            if "/" in url:
                return text            # a link to a repo file: show the words only
            url = url.replace(".md", ".html").lower()
        return '<a href="%s">%s</a>' % (url, text)

    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, t)


def convert(md):
    out, i = [], 0
    lines = [l.rstrip("\r") for l in md.split("\n")]
    title = "Joy Creator"
    while i < len(lines):
        l = lines[i]
        if not l.strip():
            i += 1
        elif l.startswith("# "):
            title = re.sub(r"[*`]", "", l[2:])
            out.append("<h1>%s</h1>" % inline(l[2:]))
            i += 1
        elif l.startswith("## "):
            out.append("<h2>%s</h2>" % inline(l[3:]))
            i += 1
        elif l.startswith("### "):
            out.append("<h3>%s</h3>" % inline(l[4:]))
            i += 1
        elif l.strip() == "---":
            out.append("<hr>")
            i += 1
        elif l.startswith(">"):
            while i < len(lines) and lines[i].startswith(">"):
                i += 1
        elif l.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            head = rows[0]
            body = [r for r in rows[1:] if not all(re.fullmatch(r":?-+:?", c) for c in r)]
            h = "".join("<th>%s</th>" % inline(c) for c in head)
            b = "".join("<tr>%s</tr>" % "".join("<td>%s</td>" % inline(c) for c in r) for r in body)
            out.append('<div class="tablewrap"><table><thead><tr>%s</tr></thead><tbody>%s</tbody></table></div>' % (h, b))
        elif re.match(r"^(-|\d+\.) ", l):
            tag = "ol" if l[0].isdigit() else "ul"
            items = []
            while i < len(lines) and re.match(r"^(-|\d+\.) ", lines[i]):
                item = re.sub(r"^(-|\d+\.) ", "", lines[i])
                i += 1
                while i < len(lines) and lines[i].startswith("  ") and lines[i].strip():
                    item += " " + lines[i].strip()
                    i += 1
                items.append("<li>%s</li>" % inline(item))
            out.append("<%s>%s</%s>" % (tag, "".join(items), tag))
        else:
            para = [l]
            i += 1
            while i < len(lines) and lines[i].strip() and not re.match(r"^(#|\||>|-|\d+\. |---)", lines[i]):
                para.append(lines[i])
                i += 1
            out.append("<p>%s</p>" % inline(" ".join(p.strip() for p in para)))
    return title, "\n".join(out)


def page(title, body, draft):
    note = ('<p class="draft"><strong>Draft.</strong> The owner has not reviewed this page yet; the parts in square '
            'brackets are still to be filled in.</p>') if draft else ""
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1"><title>%s</title>'
            '<style>%s</style></head><body>%s<main>%s%s</main>'
            '<footer>Joy Creator is free software (GNU GPL v3), built on FadCam by FadSec Lab. '
            '"Joy Creator" and "JoyBrush" are the owner\'s marks.</footer></body></html>'
            % (html.escape(title), CSS, NAV, note, body))


def has_placeholder(md):
    return re.search(r"\[[A-Z][A-Z0-9 ,/:'\-.()]{6,}", md) is not None


def build_md_page(src, dest):
    md = (ROOT / src).read_text(encoding="utf-8")
    title, body = convert(md)
    (DOCS / dest).write_text(page(title, body, has_placeholder(md)), encoding="utf-8", newline="\n")
    return dest


INDEX = """
<h1>Joy Creator</h1>
<p><strong>A creative studio on your phone.</strong> Paint and animate, edit video, make music visuals and film-poster
title looks, build characters, and keep everything in one folder you choose. Free for good: no ads, no subscription,
no watermark, no account.</p>
<p><a class="btn" href="testers.html">Help test it</a><a class="btn ghost" href="privacy.html">Privacy policy</a></p>
<h2>What is inside</h2>
<ul>
<li><strong>JoyBrush</strong>: painting and animation with brushes made to feel real.</li>
<li><strong>Studio</strong>: a video and audio editor with titles, effects, transitions and captions.</li>
<li><strong>VisLab</strong>: music visualisers built from parts you can mix.</li>
<li><strong>TextLab</strong>: title looks like chrome, gold, neon and fire.</li>
<li><strong>SpriteLab</strong>: sprite sheets, characters and puppets.</li>
<li><strong>Joybot</strong>: an AI helper that knows the whole app. You bring your own key; it is optional.</li>
</ul>
<p>Joy Creator is in beta and is coming to Google Play. This page will grow into the full site; until then it holds the
privacy policy and the tester sign-up.</p>
<p>Contact: [CONTACT EMAIL: OWNER TO CONFIRM]</p>
"""


def main():
    DOCS.mkdir(exist_ok=True)
    (DOCS / ".nojekyll").write_text("", encoding="utf-8")
    (DOCS / "index.html").write_text(page("Joy Creator: a creative studio for your phone", INDEX, True),
                                     encoding="utf-8", newline="\n")
    built = ["index.html"]
    built.append(build_md_page("PRIVACY.md", "privacy.html"))
    built.append(build_md_page("tasks/SHOW2_tester_invite.md", "testers.html"))
    built.append(build_md_page("TRADEMARK.md", "trademark.html"))
    print("built:", ", ".join(built))


if __name__ == "__main__":
    sys.exit(main())
