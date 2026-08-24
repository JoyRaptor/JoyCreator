"""C5.E ducking — file proof that the music dipped by the keyframed amount, on time.

Ducking writes ORDINARY VOLUME KEYFRAMES (at most four per speech region: start of
dip, floor, end of hold, back up), which means the export's music-band level is not
an approximation to be eyeballed — it is a PREDICTABLE CURVE:

    1.0 until (voiceStart - attack)
    linear down to duckMult across attack
    duckMult through the voice region plus hold
    linear back to 1.0 across release

This probe measures the music band's envelope in the export (band-pass + analytic
signal, so the co-existing voice tone cannot contaminate it) and compares it with
that curve point for point, after allowing one global AAC-priming shift.

Usage:
    python tasks/probe_duck_c5e.py DUCKED_EXPORT.m4a \
        --voice-start-ms 1500 --voice-end-ms 3500 \
        [--music-hz 330] [--voice-hz 1200] [--duck-mult 0.25] \
        [--attack-ms 150] [--release-ms 400] [--hold-ms 250]

Fixture pair (export/make_fixtures.py): continuous music tone lane + voice-tone
lane; export once with C5.E applied. The UNSOLOED/UNDUCKED export of the same
project is the operator negative control and MUST FAIL here.

    python tasks/probe_duck_c5e.py --selftest proves the teeth:
      correctly ducked mix                          -> PASS
      never ducked (generator wrote nothing)        -> FAIL on depth + ramp
      ducked far too shallowly (0.8 instead of 0.25)-> FAIL on depth
      probed with the WRONG voice window            -> FAIL (curve must be located,
                                                       not just "somewhere quieter")
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def duck_curve(t_s, vs, ve, mult, attack_s, release_s, hold_s):
    """The exact curve four-keyframe ducking implies (see module docstring)."""
    t = np.asarray(t_s, dtype=np.float64)
    c = np.ones_like(t)
    down_start, down_end = vs - attack_s, vs
    up_start, up_end = ve + hold_s, ve + hold_s + release_s
    down = (t >= down_start) & (t < down_end)
    if attack_s > 0:
        frac = (t[down] - down_start) / max(attack_s, 1e-6)
    else:
        frac = np.ones(np.count_nonzero(down))
    c[down] = 1.0 + (mult - 1.0) * np.clip(frac, 0, 1)
    c[(t >= down_end) & (t < up_start)] = mult
    up = (t >= up_start) & (t < up_end)
    if release_s > 0:
        frac_u = (t[up] - up_start) / max(release_s, 1e-6)
    else:
        frac_u = np.ones(np.count_nonzero(up))
    c[up] = mult + (1.0 - mult) * np.clip(frac_u, 0, 1)
    return c


def measure(x, fs, p):
    checks = []
    vs, ve = p['voice_start_ms'] / 1000.0, p['voice_end_ms'] / 1000.0
    mult = p['duck_mult']
    atk, rel, hold = (p['attack_ms'] / 1000.0, p['release_ms'] / 1000.0,
                      p['hold_ms'] / 1000.0)

    # fixture sanity: the voice band must actually carry the voice in-window
    vt, venv = lib.band_envelope(x, fs, p['voice_hz'])
    inside = float(np.median(venv[(vt >= vs) & (vt <= ve)]))
    outside = float(np.median(venv[(vt < vs - 0.5) | (vt > ve + 0.5)])) \
        if np.any((vt < vs - 0.5) | (vt > ve + 0.5)) else 0.0
    print(f'  voice band: in {lib.db(inside):+.1f} dB  out {lib.db(outside):+.1f} dB')
    checks.append(('voice IS present in the speech window (fixture sanity)',
                   inside > 6.0 * max(outside, 1e-5),
                   f'in/out ratio {inside / max(outside, 1e-9):.1f}x'))

    mt, menv = lib.band_envelope(x, fs, p['music_hz'], smooth_ms=20.0)
    base_lo, base_hi = max(vs - atk - 0.45, 0.0), max(vs - atk - 0.15, 0.05)
    base_mask = (mt >= base_lo) & (mt <= base_hi)
    if not np.any(base_mask):
        checks.append(('pre-voice baseline exists', False,
                       'no clean music-only span before the voice — lengthen fixture'))
        return checks
    baseline = float(np.median(menv[base_mask]))
    if baseline < 1e-5:
        checks.append(('music band measurable pre-voice', False, 'baseline ~ silence'))
        return checks
    norm = menv / baseline

    # one global alignment shift (AAC priming), found by fitting the curve itself
    best_shift, best_err = 0.0, None
    for shift in np.arange(-0.25, 0.25, 0.01):
        pred = duck_curve(mt - shift, vs, ve, mult, atk, rel, hold)
        err = float(np.mean((norm[:len(pred)] - pred) ** 2))
        if best_err is None or err < best_err:
            best_err, best_shift = err, shift
    pred = duck_curve(mt - best_shift, vs, ve, mult, atk, rel, hold)
    print(f'  alignment shift {best_shift * 1000:+.0f} ms '
          f'(curve fit rms {np.sqrt(best_err):.3f})')

    # 1. depth: the HOLD floor really is at the duck multiplier
    hold_lo, hold_hi = vs + 0.1, ve + hold - 0.05
    hm = (mt - best_shift >= hold_lo) & (mt - best_shift <= hold_hi)
    floor_val = float(np.median(norm[hm])) if np.any(hm) else 1.0
    depth_tol = max(0.08, mult * 0.4)
    ok_depth = abs(floor_val - mult) <= depth_tol
    print(f'  duck floor: measured x{floor_val:.3f}, keyed x{mult:.3f}')
    checks.append((f'music holds the KEYED duck depth (x{mult:.2f} '
                   f'+/-{depth_tol:.2f})', ok_depth,
                   f'measured x{floor_val:.3f}'))
    # deep-dip corroboration: the dip is not merely "a bit lower"
    dips = bool(np.any(hm)) and float(np.min(norm[hm])) <= mult + 0.15
    checks.append(('the dip genuinely reaches the keyed floor', dips))

    # 2. ramp shape: attack midpoint sits near half the dive
    mid_t = vs - atk / 2.0 + best_shift
    mid_val = lib.env_at(mt, norm, mid_t)
    expect_mid = (1.0 + mult) / 2.0
    ok_ramp = abs(mid_val - expect_mid) <= 0.18
    print(f'  attack midpoint: measured x{mid_val:.3f}, keyed x{expect_mid:.3f}')
    checks.append(('attack RAMP passes through its keyed midpoint',
                   ok_ramp, f'x{mid_val:.3f} vs x{expect_mid:.3f}'))

    # 3. recovery: back near unity after release
    rec_lo = ve + hold + rel + 0.15 + best_shift
    rec_hi = rec_lo + 0.6
    rm = (mt >= rec_lo) & (mt <= rec_hi)
    rec_val = float(np.median(norm[rm])) if np.any(rm) else 0.0
    ok_rec = rec_val >= 0.75
    print(f'  recovery: x{rec_val:.3f} of baseline')
    checks.append(('music RECOVERS after release (>=75% of baseline)', ok_rec))

    # 4. untouched before the duck begins (anti "ducks from beginning to end")
    pre = (mt - best_shift >= base_lo) & (mt - best_shift <= base_hi)
    pre_val = float(np.median(norm[pre]))
    checks.append(('NOT ducked before the attack starts (>=80% of baseline)',
                   pre_val >= 0.8, f'x{pre_val:.3f}'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export', nargs='?', default=None)
    ap.add_argument('--voice-start-ms', type=float, default=None)
    ap.add_argument('--voice-end-ms', type=float, default=None)
    ap.add_argument('--music-hz', type=float, default=330.0)
    ap.add_argument('--voice-hz', type=float, default=1200.0)
    ap.add_argument('--duck-mult', type=float, default=0.25)
    ap.add_argument('--attack-ms', type=float, default=150.0)
    ap.add_argument('--release-ms', type=float, default=400.0)
    ap.add_argument('--hold-ms', type=float, default=250.0)
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()
    p = dict(voice_start_ms=args.voice_start_ms, voice_end_ms=args.voice_end_ms,
             music_hz=args.music_hz, voice_hz=args.voice_hz,
             duck_mult=args.duck_mult, attack_ms=args.attack_ms,
             release_ms=args.release_ms, hold_ms=args.hold_ms)

    def synth(tmp, duck_to, name):
        """Music tone + voice tone; music multiplied by the keyed curve down to
        `duck_to` (None = generator produced nothing = plain constant music)."""
        dur = 5.0
        vs, ve = args.voice_start_ms / 1000.0, args.voice_end_ms / 1000.0
        atk, rel, hold = args.attack_ms / 1000.0, args.release_ms / 1000.0, args.hold_ms / 1000.0
        music = lib.tone(dur, args.music_hz, 0.4)
        if duck_to is not None:
            t = np.arange(len(music)) / lib.FS
            music *= duck_curve(t, vs, ve, duck_to, atk, rel, hold)
        voice = lib.tone(max(ve - vs, 0.1), args.voice_hz, 0.3)
        sig = lib.mix(music, np.concatenate([lib.silence(vs), voice]))
        wav = os.path.join(tmp, name + '.wav')
        lib.write_wav(wav, sig)
        m4a = os.path.join(tmp, name + '.m4a')
        lib.encode_aac(wav, m4a)
        return lib.decode(m4a)

    if args.selftest:
        if args.voice_start_ms is None:
            args.voice_start_ms, args.voice_end_ms = 1500.0, 3500.0
            p['voice_start_ms'], p['voice_end_ms'] = 1500.0, 3500.0
        import tempfile
        tmp = tempfile.mkdtemp(prefix='c5eself_')
        x_good, fs = synth(tmp, args.duck_mult, 'good')
        x_none, _ = synth(tmp, None, 'noduck')
        x_shallow, _ = synth(tmp, 0.8, 'shallow')
        cases = []
        cases.append(('correctly ducked mix', True, measure(x_good, fs, p)))
        cases.append(('never ducked (generator no-op)', False, measure(x_none, fs, p)))
        cases.append(('ducked too shallowly (x0.8)', False, measure(x_shallow, fs, p)))
        wrong = dict(p, voice_start_ms=p['voice_start_ms'] + 2000.0,
                     voice_end_ms=p['voice_end_ms'] + 2000.0)
        cases.append(('probed with the WRONG voice window', False,
                      measure(x_good, fs, wrong)))
        sys.exit(0 if lib.report_selftest('C5.E ducking', cases) else 1)

    if not args.export or args.voice_start_ms is None or args.voice_end_ms is None:
        ap.error('needs EXPORT --voice-start-ms --voice-end-ms (or --selftest)')
    print(f'C5.E duck probe: {os.path.basename(args.export)}')
    x, fs = lib.decode(args.export)
    checks = measure(x, fs, p)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}' + (f'  ({detail})' if detail else ''))
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
