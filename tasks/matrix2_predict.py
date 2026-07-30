# MATRIX resolve-wave model. Mirrors CaptionAnimator.substituteUnit so the on-screen
# string at a given progress can be PREDICTED and checked character-for-character,
# the same way tasks/matrix_predict.py did for the original scramble.
#
# NOTE: `current()` below is the OLD behaviour, kept deliberately. It is what showed
# that the whole body was inked at p=0 - the thing the user objected to - and it is the
# control that makes the new version's leading edge legible as a change.
# Run with:  python -c "import sys;sys.stdout.reconfigure(encoding='utf-8');exec(open('tasks/matrix2_predict.py').read())"
# (the console is cp1252; katakana will raise UnicodeEncodeError without that.)

# Mirrors CaptionAnimator.substituteUnit. CURRENT vs PROPOSED, so the change can be
# judged before a line of Java is written.
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
    return i32(h ^ ushr(h, 13))

ASCII_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*&^%$#@!{}?<>"
# Weighted x2, mirroring CaptionAnimator.MATRIX_ASCII_WEIGHT: the user asked for a higher
# ratio of English letters and numbers so the churn does not read as one language.
GLYPHS = [chr(c) for c in range(0xFF66, 0xFF9E)] + list(ASCII_GLYPHS) * 2
TICKS = 12
CHURN_SPAN = 0.30
JITTER_SALT = 0x3A7C19

def current(text, p, unit=0):
    if p >= 1: return text
    L = len(text); tick = int(p * TICKS); out = []
    for i, c in enumerate(text):
        if c.isspace() or p >= (i + 1) / (L + 1): out.append(c)
        else: out.append(GLYPHS[mix(unit, i, tick) % len(GLYPHS)])
    return "".join(out)

def proposed(text, p, unit=0):
    if p >= 1: return text
    L = len(text); tick = int(p * TICKS); out = []
    for i, c in enumerate(text):
        if c.isspace(): out.append(c); continue
        reveal = i / L
        jit = (mix(unit, i, JITTER_SALT) % 1000) / 1000.0
        resolve = min(1.0, reveal + CHURN_SPAN * (0.6 + 0.8 * jit))
        if p < reveal: out.append(' ')            # not arrived yet -> the leading edge
        elif p >= resolve: out.append(c)          # settled
        else: out.append(GLYPHS[mix(unit, i, tick) % len(GLYPHS)])
    return "".join(out)

T = "PICKERTEST"
print("progress | CURRENT      | PROPOSED")
print("---------|--------------|-------------")
for k in range(0, 21):
    p = k / 20
    print(f"  {p:4.2f}   | {current(T,p):12s} | {proposed(T,p):12s}")
