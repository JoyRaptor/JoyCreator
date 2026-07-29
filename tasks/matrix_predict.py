"""Mirror of CaptionAnimator's MATRIX channel, in Python, for pre-registering predictions.

MATRIX is a pure function of (unit index, character position, tick) BY DESIGN — that
determinism is what keeps the preview and the export drawing the same characters. Which
means the exact string on screen at a given media time is PREDICTABLE, and a screenshot
can be checked character-for-character instead of merely eyeballed. That is what this
file is for; it is the instrument that closed the MATRIX device proof (LEDGER 3g).

Java semantics: 32-bit signed int arithmetic, >>> is an unsigned shift.

TWO THINGS THAT WILL GIVE YOU A WRONG PREDICTION IF YOU DO NOT KNOW THEM:

1. THE TRANSCRIPT IS WINDOWED TO THE CLIP'S TRIM BEFORE IT IS GROUPED INTO PHRASES.
   Grouping restarts at the first word at/after inPointMs, so the phrase boundaries are
   NOT the ones you get by grouping the whole pooled transcript. On the sandbox clip
   50d1cf46 (inPointMs 1406) the first phrase is "this cat is very cute she", not the
   "this is a cat this cat" you get from the untrimmed word list.

2. AT BLOCK GRANULARITY EVERY WORD IS SUBSTITUTED WITH unitIndex = 0, independently, using
   its OWN length. So character position i of every unsettled word shows the SAME glyph.
   That column fingerprint (e.g. tick 6 -> 'G' at index 2, '4' at index 3) is the signature
   to look for in a screenshot; it cannot arise by chance.
"""
import sys

GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&@?"
TICKS = 12
M32 = 0xFFFFFFFF


def i32(x):
    x &= M32
    return x - (1 << 32) if x >= (1 << 31) else x


def ushr(x, n):
    return (x & M32) >> n


def mix(a, b, c):
    h = i32(i32(a * 0x27D4EB2D) ^ i32(b * 0x165667B1) ^ i32(c * 0x9E3779B1))
    h = i32(h ^ ushr(h, 15))
    h = i32(h * 0x85EBCA6B)
    h = i32(h ^ ushr(h, 13))
    return h


def substitute(text, progress, unit_index):
    p = max(0.0, min(1.0, progress))
    if p >= 1.0:
        return text
    L = len(text)
    tick = int(p * TICKS)
    out = []
    for i, ch in enumerate(text):
        if ch.isspace() or p >= (i + 1) / float(L + 1):
            out.append(ch)
        else:
            out.append(GLYPHS[mix(unit_index, i, tick) % len(GLYPHS)])
    return "".join(out)


def zone_for_span(pct, span):
    if span <= 0:
        return 0
    pct = 0.0 if pct != pct or pct <= 0 else min(0.5, pct)
    if pct <= 0:
        return 0
    return min(round(span * pct), span // 2)


def unit_progress(media, start, end, in_zone, out_zone, idx, count):
    span = max(1, end - start)
    local = max(0, min(media - start, span))
    count = max(1, count)
    idx = max(0, min(idx, count - 1))
    if in_zone > 0 and local < in_zone:
        sl = in_zone / float(count + 1)
        return max(0.0, min(1.0, (local - sl * idx) / max(1.0, sl * 2.0)))
    if out_zone > 0 and local > span - out_zone:
        into = local - (span - out_zone)
        sl = out_zone / float(count + 1)
        return 1.0 - max(0.0, min(1.0, (into - sl * idx) / max(1.0, sl * 2.0)))
    return 1.0


# ── The sandbox subject: clip 50d1cf46 of bb2a9deb ─────────────────────────────
# Its two whole phrases, AFTER trim windowing (see note 1 in the module docstring):
#   phrase 0: this cat is very cute she   source 1740..3810
#   phrase 1: is white and fluffy her name source 3810..5640
# Override WORDS / SPAN_START / SPAN_END to switch phrases.
WORDS = ["this", "cat", "is", "very", "cute", "she"]
SPAN_START, SPAN_END = 1740, 3810          # source ms
IN_PCT = OUT_PCT = 0.5
CLIP_TL_START, CLIP_IN, SPEED = 250, 1406, 1.0   # timeline mapping


def render_at_source(src):
    span = SPAN_END - SPAN_START
    iz = zone_for_span(IN_PCT, span)
    oz = zone_for_span(OUT_PCT, span)
    p = unit_progress(src, SPAN_START, SPAN_END, iz, oz, 0, 1)  # BLOCK: one unit
    return p, " ".join(substitute(w, p, 0) for w in WORDS)


def tl_to_source(tl_ms):
    return CLIP_IN + (tl_ms - CLIP_TL_START) * SPEED


if __name__ == "__main__":
    if len(sys.argv) > 1:
        for a in sys.argv[1:]:
            tl = float(a)
            src = tl_to_source(tl)
            p, s = render_at_source(src)
            print("timeline %8.1f  source %8.1f  p=%.4f tick=%2d  %s"
                  % (tl, src, p, int(min(1, max(0, p)) * TICKS), s))
    else:
        span = SPAN_END - SPAN_START
        print("span=%d in=%d out=%d" % (span, zone_for_span(IN_PCT, span),
                                        zone_for_span(OUT_PCT, span)))
        for src in range(SPAN_START, SPAN_END + 1, 50):
            p, s = render_at_source(src)
            tl = CLIP_TL_START + (src - CLIP_IN) / SPEED
            print("tl %7.0f src %5d p=%.3f tick=%2d  %s" % (tl, src, p, int(p * TICKS), s))
