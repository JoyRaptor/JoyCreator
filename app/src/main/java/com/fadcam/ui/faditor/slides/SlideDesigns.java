package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Joybot's built-in slide designs: five kinds (title, chapter, quote, list, credits) in five moods
 * (calm, bold, playful, cinematic, clean), with no AI and no network.
 *
 * <p>Before this, a slide made without a model was one plain fade whatever was asked for — so the
 * offline slide wizard would have asked "what mood?" and then ignored the answer. Each design here
 * is the same contract the model writes to (one GSAP timeline, {@code Faditor.register} once, no
 * timers, system fonts only), so it renders through the same HTML → frames path and always passes
 * {@link SlideContract#validate}.</p>
 *
 * <p>Layout lives in one small script shared by every combination; Java only supplies the words,
 * the canvas size and the mood's tokens. Sizes derive from the canvas's short side so a 9:16 and a
 * 16:9 slide both fill sensibly.</p>
 */
public final class SlideDesigns {

    private SlideDesigns() {}

    public static final String[] KINDS = {"title", "chapter", "quote", "list", "credits"};
    public static final String[] MOODS = {"calm", "bold", "playful", "cinematic", "clean"};

    /** True when there is a built-in design for this pair. */
    public static boolean knows(@Nullable String kind, @Nullable String mood) {
        return indexOf(KINDS, kind) >= 0 && indexOf(MOODS, mood) >= 0;
    }

    /**
     * A complete slide. Unknown kind/mood fall back to a clean title, so this never fails.
     *
     * @param text the words; for quote/list/credits, one item per line (a quote's last line, when
     *             there are two or more, is who said it)
     */
    @NonNull
    public static String build(int width, int height, @Nullable String kind, @Nullable String mood,
                               @NonNull String text, long durationMs) {
        String k = indexOf(KINDS, kind) >= 0 ? kind : "title";
        String m = indexOf(MOODS, mood) >= 0 ? mood : "clean";
        long ms = Math.max(800, durationMs);
        return "<!doctype html><html><head><meta charset=\"utf-8\">\n"
                + "<!-- " + SlideContract.CONTRACT_MARKER + SlideContract.CONTRACT_VERSION + " (built-in: " + k + "/" + m + ") -->\n"
                + "<style>\n" + css(width, height, m) + "</style></head>\n"
                + "<body>\n<div id=\"stage\"><div id=\"wrap\"></div></div>\n"
                + "<script src=\"gsap.min.js\"></script>\n"
                + "<script src=\"faditor_runtime.js\"></script>\n"
                + "<script>\n"
                + "const KIND = \"" + k + "\";\n"
                + "const MOOD = " + moodJs(m) + ";\n"
                + "const TEXT = " + jsString(text) + ";\n"
                + "const W = " + width + ", H = " + height + ", T = " + (ms / 1000.0) + ";\n"
                + SCRIPT
                + "Faditor.register(tl, " + ms + ");\n"
                + "</script>\n</body></html>";
    }

    // ── moods ───────────────────────────────────────────────────────────────

    private static String css(int w, int h, String mood) {
        String bg, ink, font, weight, upper, spacing, style;
        switch (mood) {
            case "calm":
                bg = "linear-gradient(160deg,#121821 0%,#1d2633 100%)"; ink = "#EAF0F6";
                font = "-apple-system,system-ui,Arial,sans-serif"; weight = "300"; upper = "none";
                spacing = "0.01em"; style = "normal"; break;
            case "bold":
                bg = "#050507"; ink = "#FFFFFF";
                font = "-apple-system,system-ui,Arial,sans-serif"; weight = "900"; upper = "uppercase";
                spacing = "-0.01em"; style = "normal"; break;
            case "playful":
                bg = "linear-gradient(135deg,#FF8FAB 0%,#FFD166 100%)"; ink = "#2B2D42";
                font = "-apple-system,system-ui,Arial,sans-serif"; weight = "800"; upper = "none";
                spacing = "0"; style = "normal"; break;
            case "cinematic":
                bg = "radial-gradient(ellipse at center,#1a1612 0%,#000000 75%)"; ink = "#F3E7CF";
                font = "Georgia,'Times New Roman',serif"; weight = "400"; upper = "uppercase";
                spacing = "0.22em"; style = "normal"; break;
            default: // clean
                bg = "#0B0B0D"; ink = "#F4F4F5";
                font = "-apple-system,system-ui,Arial,sans-serif"; weight = "600"; upper = "none";
                spacing = "-0.01em"; style = "normal"; break;
        }
        return "html,body{margin:0;padding:0;background:#000;}\n"
                + "#stage{width:" + w + "px;height:" + h + "px;position:relative;overflow:hidden;"
                + "background:" + bg + ";color:" + ink + ";font-family:" + font + ";}\n"
                + "#wrap{position:absolute;left:0;top:0;width:" + w + "px;height:" + h + "px;"
                + "box-sizing:border-box;padding:0 " + Math.round(w * 0.08) + "px;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;text-align:center;}\n"
                + ".t{font-weight:" + weight + ";text-transform:" + upper + ";letter-spacing:" + spacing
                + ";font-style:" + style + ";line-height:1.08;}\n"
                + ".w{display:inline-block;white-space:pre;}\n"
                + ".bar{position:absolute;left:0;width:" + w + "px;background:#000;}\n";
    }

    /** The mood's colours and motion, for the shared script. */
    private static String moodJs(String mood) {
        switch (mood) {
            case "calm":
                return "{accent:'#9CC3E6',dur:1.4,ease:'sine.out',from:{opacity:0,y:0.03},words:false,italic:true}";
            case "bold":
                return "{accent:'#FFD23F',dur:0.45,ease:'back.out(1.6)',from:{opacity:0,scale:1.35,blur:10},words:false}";
            case "playful":
                return "{accent:'#FFFFFF',dur:0.6,ease:'back.out(2.6)',from:{opacity:0,y:0.08,rotation:-6},words:true}";
            case "cinematic":
                return "{accent:'#C9A227',dur:1.8,ease:'power2.out',from:{opacity:0,blur:6,spread:true},words:false,bars:true,italic:true}";
            default:
                return "{accent:'#22D3EE',dur:0.55,ease:'power3.out',from:{opacity:0,x:-0.04},words:false}";
        }
    }

    /**
     * Shared layout + timeline. Everything is built before the first seek and depends only on the
     * timeline's time, so any frame can be drawn in any order (the contract's main rule).
     */
    private static final String SCRIPT = String.join("\n",
            "const S = Math.min(W, H);",
            "const wrap = document.getElementById('wrap');",
            "const tl = gsap.timeline({ paused: true });",
            "function el(cls, txt, parent) { const d = document.createElement('div'); d.className = cls;",
            "  if (txt !== null) d.textContent = txt; (parent || wrap).appendChild(d); return d; }",
            "function words(node) { const t = node.textContent; node.textContent = '';",
            "  t.split(/(\\s+)/).forEach(p => { const s = document.createElement('span'); s.className = 'w';",
            "    s.textContent = p; node.appendChild(s); }); return node.querySelectorAll('.w'); }",
            // Size by AREA: the words should fill about half the frame at most, wrapping, so a
            // long quote is two or three readable lines rather than one tiny one.
            "function fitSize(txt, big, small) { const n = Math.max(1, txt.length);",
            "  const a = Math.sqrt((W * 0.84) * (H * 0.45) / (n * 0.55));",
            "  return Math.max(small, Math.min(big, a)); }",
            "function fromVars() { const f = MOOD.from, v = { opacity: 0 };",
            "  if (f.y) v.y = f.y * S; if (f.x) v.x = f.x * S; if (f.scale) v.scale = f.scale;",
            "  if (f.rotation) v.rotation = f.rotation; if (f.blur) v.filter = 'blur(' + f.blur + 'px)';",
            "  if (f.spread) v.letterSpacing = '0.6em'; return v; }",
            "function toVars() { const v = { opacity: 1, x: 0, y: 0, scale: 1, rotation: 0, duration: MOOD.dur, ease: MOOD.ease };",
            "  if (MOOD.from.blur) v.filter = 'blur(0px)'; if (MOOD.from.spread) v.letterSpacing = getComputedStyle(document.querySelector('.t') || wrap).letterSpacing;",
            "  return v; }",
            "function enter(node, at) { if (MOOD.words && node.classList.contains('t')) {",
            "    const ws = words(node); tl.fromTo(ws, fromVars(), Object.assign(toVars(), { stagger: Math.min(0.08, 0.9 / ws.length) }), at);",
            "  } else { tl.fromTo(node, fromVars(), toVars(), at); } }",
            "function rule(at) { const r = el('rule', null); r.style.cssText = 'width:' + Math.round(S * 0.18) + 'px;height:' +",
            "    Math.max(2, Math.round(S * 0.008)) + 'px;background:' + MOOD.accent + ';margin-top:' + Math.round(S * 0.05) + 'px;transform-origin:center';",
            "  tl.fromTo(r, { scaleX: 0 }, { scaleX: 1, duration: Math.max(0.4, MOOD.dur * 0.8), ease: 'power2.inOut' }, at); }",
            "const lines = TEXT.split(/\\n+/).map(s => s.trim()).filter(s => s.length > 0);",
            "if (MOOD.bars) { const a = el('bar', null, document.getElementById('stage')), b = el('bar', null, document.getElementById('stage'));",
            "  a.style.top = '0px'; b.style.bottom = '0px'; a.style.height = b.style.height = Math.round(H * 0.11) + 'px';",
            "  tl.fromTo([a, b], { scaleY: 0 }, { scaleY: 1, duration: 0.8, ease: 'power2.out' }, 0); }",
            "const out = Math.max(0.35, Math.min(0.7, T * 0.14));",
            "if (KIND === 'title') {",
            "  const t = el('t', lines.join(' ')); t.style.fontSize = fitSize(t.textContent, S * 0.16, S * 0.06) + 'px';",
            "  enter(t, 0.15); rule(0.15 + MOOD.dur * 0.5);",
            "} else if (KIND === 'chapter') {",
            "  const all = lines.join(' '); const m = all.match(/^(.{1,24}?)\\s*[:\\u2014\\u2013-]\\s+(.+)$/);",
            "  const lab = el('lab', m ? m[1] : 'Chapter'); const t = el('t', m ? m[2] : all);",
            "  lab.style.cssText = 'font-size:' + Math.round(S * 0.032) + 'px;letter-spacing:0.3em;text-transform:uppercase;color:' + MOOD.accent + ';margin-bottom:' + Math.round(S * 0.03) + 'px;font-weight:600';",
            "  t.style.fontSize = fitSize(t.textContent, S * 0.12, S * 0.05) + 'px';",
            "  tl.fromTo(lab, { opacity: 0, y: -S * 0.02 }, { opacity: 1, y: 0, duration: 0.5, ease: 'power2.out' }, 0.1);",
            "  enter(t, 0.3);",
            "} else if (KIND === 'quote') {",
            "  const by = lines.length > 1 ? lines[lines.length - 1].replace(/^[-\\u2014\\u2013\\s]+/, '') : null;",
            "  const q = (by ? lines.slice(0, -1) : lines).join(' ');",
            "  const t = el('t', '\\u201C' + q + '\\u201D'); t.style.fontSize = fitSize(q, S * 0.08, S * 0.04) + 'px';",
            "  t.style.textTransform = 'none'; t.style.letterSpacing = '0'; if (MOOD.italic) t.style.fontStyle = 'italic';",
            "  enter(t, 0.15);",
            "  if (by) { const b = el('by', '\\u2014 ' + by); b.style.cssText = 'font-size:' + Math.round(S * 0.036) + 'px;color:' + MOOD.accent + ';margin-top:' + Math.round(S * 0.045) + 'px';",
            "    tl.fromTo(b, { opacity: 0 }, { opacity: 1, duration: 0.6 }, 0.15 + MOOD.dur); }",
            "} else if (KIND === 'list') {",
            "  wrap.style.alignItems = 'flex-start'; wrap.style.textAlign = 'left';",
            "  const items = lines.slice(0, 7); const size = Math.max(S * 0.038, Math.min(S * 0.07, S * 0.36 / Math.max(3, items.length)));",
            "  const gap = Math.min(0.6, (T * 0.55) / Math.max(1, items.length));",
            "  items.forEach((s, i) => { const row = el('li', null); row.style.cssText = 'display:flex;align-items:center;margin:' + Math.round(size * 0.28) + 'px 0';",
            "    const dot = el('dot', null, row); dot.style.cssText = 'flex:none;width:' + Math.round(size * 0.32) + 'px;height:' + Math.round(size * 0.32) + 'px;border-radius:50%;background:' + MOOD.accent + ';margin-right:' + Math.round(size * 0.5) + 'px';",
            "    const t = el('t', s, row); t.style.fontSize = Math.round(size) + 'px'; t.style.textTransform = 'none'; t.style.letterSpacing = '0';",
            "    tl.fromTo(row, { opacity: 0, x: -S * 0.05 }, { opacity: 1, x: 0, duration: Math.min(0.6, MOOD.dur), ease: 'power2.out' }, 0.15 + i * gap); });",
            "} else {",
            "  const roll = el('roll', null); roll.style.cssText = 'position:absolute;left:0;top:0;width:' + W + 'px;text-align:center';",
            "  lines.forEach((s, i) => { const t = el('t', s, roll); t.style.fontSize = Math.round(S * (i === 0 ? 0.06 : 0.045)) + 'px';",
            "    t.style.margin = Math.round(S * 0.035) + 'px 0'; t.style.textTransform = 'none'; if (i === 0) t.style.color = MOOD.accent; });",
            "  const rh = roll.offsetHeight;",
            "  tl.fromTo(roll, { y: H }, { y: -rh, duration: T, ease: 'none' }, 0);",
            "}",
            "if (KIND !== 'credits') tl.to(wrap, { opacity: 0, duration: out, ease: 'power1.in' }, Math.max(T - out, tl.duration() + 0.2));",
            "if (tl.duration() < T) tl.to({}, { duration: T - tl.duration() });",
            "");

    // ── helpers ─────────────────────────────────────────────────────────────

    private static int indexOf(String[] a, @Nullable String v) {
        if (v == null) return -1;
        String l = v.toLowerCase(Locale.US);
        for (int i = 0; i < a.length; i++) if (a[i].equals(l)) return i;
        return -1;
    }

    /** A JS string literal: escaped so no text can close the script or the string. */
    @NonNull
    static String jsString(@NonNull String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '<': b.append("\\u003C"); break;
                case '>': b.append("\\u003E"); break;
                case ' ': b.append("\\u2028"); break;
                case ' ': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
