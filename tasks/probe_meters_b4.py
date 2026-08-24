"""B4 level meters — file proof that what the meter paints matches the audio.

B4's meter draws PROGRAM level: the sum of contributing clips' final audible gain
(envelope x volumeLevel) under the playhead — LayerRowRenderer.trackLevelAt. The
row's honest standing limit is "not post-DSP measured", which is exactly why it
needs an independent instrument: this probe measures the EXPORT's actual audible
level at the same playhead moments and compares it against the reported values.

The contract: for each --at T_MS:DB point,
    measured_db = 20log10( rms(export around T) / rms(source at T - clip_offset) )
must equal DB within tolerance. Both numbers come from DECODED PCM of different
files — neither side can copy the other, so agreement means the meter maths and
the mix agree, not that a value was echoed.

Usage:
    python tasks/probe_meters_b4.py EXPORT.m4a SOURCE.m4a \
        --at 1500:-6.0 --at 3500:-12.0 [--clip-offset-ms 0] [--tol-db 1.5]

SOURCE = the media placed on the lane, EXPORT = the exported project. Take the
reported dB values off the meter (screenshot/logcat capture at those playheads).
Fixture with three distinct level plateaus: export/make_fixtures.py meter-steps.

    python tasks/probe_meters_b4.py --selftest proves the teeth:
      correct reports        -> PASS
      one report +6 dB wrong -> FAIL   (a probe deaf to a lying readout is worthless)
      report over a silent source window -> FAIL (unmeasurable must not equal zero)
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def parse_at(spec):
    t_s, _, v = spec.partition(':')
    return float(t_s), float(v)


def measure(ex, src, fs, points, clip_offset_ms, win_ms, tol_db):
    checks = []
    half = int(win_ms * fs / 1000)
    off = int(clip_offset_ms * fs / 1000)

    def win(x, center):
        i = max(int(center * fs) - half, 0)
        seg = x[i:i + 2 * half]
        if len(seg) < half:
            return None
        return seg

    print(f'  window ±{win_ms:.0f} ms around each playhead, tolerance {tol_db:.1f} dB')
    for t_ms, reported_db in points:
        t = t_ms / 1000.0
        w_ex = win(ex, t)
        w_src = win(src, t - off)
        if w_ex is None or w_src is None:
            checks.append((f'@{t_ms:.0f}ms windows decodable', False, 'window past end of file'))
            continue
        r_ex, r_src = lib.rms(w_ex), lib.rms(w_src)
        if r_src < 1e-5:
            checks.append((f'@{t_ms:.0f}ms source window measurable', False,
                           'source window is digital silence — pick a playhead over content'))
            continue
        measured_db = lib.db(r_ex / r_src)
        delta = abs(measured_db - reported_db)
        ok = delta <= tol_db
        print(f'  @{t_ms:7.0f}ms  reported {reported_db:+7.2f} dB   '
              f'measured {measured_db:+7.2f} dB   delta {delta:.2f}')
        checks.append((f'meter value @ {t_ms:.0f}ms matches audio ({tol_db:.1f} dB)',
                       ok, f'delta {delta:.2f} dB'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export', nargs='?', default=None)
    ap.add_argument('source', nargs='?', default=None)
    ap.add_argument('--at', metavar='T_MS:DB', action='append', default=[],
                    help='playhead ms and the dB the meter showed there; repeatable')
    ap.add_argument('--clip-offset-ms', type=float, default=0.0,
                    help='where the clip starts on the TIMELINE (source time = T - this)')
    ap.add_argument('--win-ms', type=float, default=250.0)
    ap.add_argument('--tol-db', type=float, default=1.5)
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        import tempfile
        import wave as _w
        tmp = tempfile.mkdtemp(prefix='b4self_')
        # three plateaus of DIFFERENT content amplitude; export applies known
        # gains on top, so reported-vs-measured has real work to do
        segs = [lib.tone(2.0, 800.0, a) for a in (0.5, 0.25, 0.125)]
        src = np_concat(segs)
        gains = [1.0, 0.501, 0.251]           # ~0, ~-6, ~-12 dB program
        ex = np_concat([s * g for s, g in zip(segs, gains)])

        swav, ewav = os.path.join(tmp, 's.wav'), os.path.join(tmp, 'e.wav')
        lib.write_wav(swav, src)
        lib.write_wav(ewav, ex)
        sm4a, em4a = os.path.join(tmp, 's.m4a'), os.path.join(tmp, 'e.m4a')
        lib.encode_aac(swav, sm4a)
        lib.encode_aac(ewav, em4a)
        ex_d, fs = lib.decode(em4a)
        src_d, _ = lib.decode(sm4a)

        cases = []
        good = [(1000, 0.0), (3000, -6.02), (5000, -12.04)]
        cases.append(('reports match the mix', True,
                      measure(ex_d, src_d, fs, good, 0, 250, args.tol_db)))
        bad = [(1000, 0.0), (3000, 0.0), (5000, -12.04)]   # middle readout lies +6 dB
        cases.append(('one readout 6 dB wrong', False,
                      measure(ex_d, src_d, fs, bad, 0, 250, args.tol_db)))
        silent_pt = [(7000, 0.0)]                          # past all content
        cases.append(('report over silence/unmeasurable', False,
                      measure(ex_d, src_d, fs, silent_pt, 0, 250, args.tol_db)))
        sys.exit(0 if lib.report_selftest('B4 meters', cases) else 1)

    if not args.export or not args.source or not args.at:
        ap.error('needs EXPORT SOURCE and at least one --at T_MS:DB (or --selftest)')
    points = [parse_at(a) for a in args.at]

    print(f'B4 meters probe: {os.path.basename(args.export)} vs {os.path.basename(args.source)}')
    ex, fs = lib.decode(args.export)
    src, fs2 = lib.decode(args.source)
    checks = measure(ex, src, fs, points, args.clip_offset_ms, args.win_ms, args.tol_db)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}  {detail}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


def np_concat(parts):
    import numpy as np
    return np.concatenate(parts)


if __name__ == '__main__':
    main()
