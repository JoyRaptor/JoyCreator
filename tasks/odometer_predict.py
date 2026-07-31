# -*- coding: utf-8 -*-
"""
ODOMETER, predicted off-device.

The house rule here is PREDICT, THEN LOOK: every animated channel in this feature is a closed-form
function of the playhead, so a screenshot should be checked against a number computed BEFORE the
screenshot is taken. This is the ODOMETER twin of matrix2_predict.py.

It mirrors, in order:
  CaptionAnimator.unitProgress   -- the staggered per-unit progress
  decelerate                     -- 1 - (1-t)^2
  rollUnit                       -- wheel = (1 - decelerate(p)) * ROLL_STEPS
  ringStep / ringText            -- the RING, which is the whole point of the preset

Run (the Windows console is cp1252 and will mangle anything interesting, so force UTF-8):
  python -c "import sys;sys.stdout.reconfigure(encoding='utf-8');exec(open('tasks/odometer_predict.py').read())"
"""

ROLL_STEPS = 8


def decelerate(t):
    return 1.0 - (1.0 - t) * (1.0 - t)


def unit_progress(media_ms, start_ms, end_ms, in_zone_ms, out_zone_ms, unit_index, unit_count):
    span = max(1, end_ms - start_ms)
    local = max(0, min(media_ms - start_ms, span))
    count = max(1, unit_count)
    idx = max(0, min(unit_index, count - 1))
    if in_zone_ms > 0 and local < in_zone_ms:
        slice_ = in_zone_ms / float(count + 1)
        p = (local - slice_ * idx) / max(1.0, slice_ * 2.0)
        return max(0.0, min(1.0, p))
    if out_zone_ms > 0 and local > span - out_zone_ms:
        into = local - (span - out_zone_ms)
        slice_ = out_zone_ms / float(count + 1)
        p = (into - slice_ * idx) / max(1.0, slice_ * 2.0)
        return 1.0 - max(0.0, min(1.0, p))
    return 1.0


def ring_step(ch, back):
    """One character, `back` places earlier on its own ring. Off-ring characters are unchanged --
    that is the rule that keeps a ':' still while the digits either side of it spin."""
    o = ord(ch)
    if ord('0') <= o <= ord('9'):
        return chr(ord('0') + (o - ord('0') - back) % 10)
    if ord('a') <= o <= ord('z'):
        return chr(ord('a') + (o - ord('a') - back) % 26)
    if ord('A') <= o <= ord('Z'):
        return chr(ord('A') + (o - ord('A') - back) % 26)
    return ch


def ring_text(text, back):
    return ''.join(ring_step(c, back) for c in text)


def has_any_ring(text):
    return any(ring_step(c, 1) != c for c in text)


def roll(text, progress):
    """(rolling, incoming, outgoing, phase) -- the mirror of CaptionAnimator.rollUnit."""
    p = max(0.0, min(1.0, progress))
    if p >= 1.0 or not text or not has_any_ring(text):
        return (False, text, text, 0.0)
    wheel = (1.0 - decelerate(p)) * ROLL_STEPS
    step = int(wheel)
    return (True, ring_text(text, step), ring_text(text, step + 1), wheel - step)


def predict(media_ms, text, start_ms, end_ms, in_pct, out_pct, granularity='BLOCK'):
    """What the slot(s) show at media_ms. Granularity BLOCK = one unit, the whole string."""
    span = max(1, end_ms - start_ms)
    in_zone = min(int(round(span * in_pct)), span // 2)
    out_zone = min(int(round(span * out_pct)), span // 2)
    units = [text] if granularity == 'BLOCK' else list(text)
    rows = []
    for i, u in enumerate(units):
        p = unit_progress(media_ms, start_ms, end_ms, in_zone, out_zone, i, len(units))
        rows.append((u, p) + roll(u, p))
    return in_zone, out_zone, rows


if __name__ == '__main__' or True:
    # The sandbox case: PICKERTEST on bb2a9deb, BLOCK granularity, 25% in-zone, no out-zone.
    TEXT = 'PICKERTEST'
    START, END = 0, 5820          # span measured from the item's tape on the Note 9
    IN_PCT, OUT_PCT = 0.25, 0.0

    in_zone, out_zone, _ = predict(0, TEXT, START, END, IN_PCT, OUT_PCT)
    print('span=%dms  in-zone=%dms  out-zone=%dms  (BLOCK: one unit)' % (END - START, in_zone, out_zone))
    print()
    print('%8s  %8s  %6s  %-12s  %-12s' % ('mediaMs', 'progress', 'phase', 'incoming', 'outgoing'))
    for ms in (0, 100, 200, 300, 400, 500, 700, 900, 1100, 1300, 1400, 1455, 1500, 2000):
        _, _, rows = predict(ms, TEXT, START, END, IN_PCT, OUT_PCT)
        u, p, rolling, inc, out, phase = rows[0]
        print('%8d  %8.4f  %6.3f  %-12s  %-12s%s'
              % (ms, p, phase, inc, out, '' if rolling else '   (settled: drawn normally)'))
