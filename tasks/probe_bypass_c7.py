"""C7 A/B bypass — file proof that bypassing the FX chain yields a clean render.

Two exports, one source:

  EXPORT_FX      exported with the chain engaged
  EXPORT_BYPASS  exported with C7's toggle ON (chain passes PCM through unchanged)
  SOURCE         the media on the lane

The claim has two halves, and each half catches a different fraud:

  1. DIFFER:   the FX and bypass exports must differ MEASURABLY. If they decode
               to the same loudness, either the chain was a no-op or the toggle
               never reached the engine — the exact false-green C7 could produce.
  2. CLEAN:    the bypass export must match a PLAIN re-encode of the source:
               fit source-inside-bypass, corr > 0.9 and gain ~1. If the bypassed
               path still colours the audio (stale chain state, half-bypass),
               the fit drifts and this fails.

Usage:
    python tasks/probe_bypass_c7.py FX.m4a BYPASS.m4a SOURCE.m4a [--min-rel-delta 0.08]

    python tasks/probe_bypass_c7.py --selftest proves the teeth:
      real FX + clean bypass                    -> PASS
      FX == bypass (toggle was a no-op)         -> FAIL on DIFFER
      "bypass" export actually still processed  -> FAIL on CLEAN
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def measure(fx, by, src, fs, comp_params=None, min_rel_delta=0.08):
    checks = []
    edge = int(0.1 * fs)
    trim = lambda x: x[edge:len(x) - edge] if len(x) > 2 * edge + 4800 else x
    fx_t, by_t, src_t = trim(fx), trim(by), trim(src)

    # 1. DIFFER
    r_fx, r_by = lib.rms(fx_t), lib.rms(by_t)
    rel_delta = abs(r_fx - r_by) / max(r_fx, r_by, 1e-9)
    print(f'  rms fx {lib.db(r_fx):+.2f} dB   rms bypass {lib.db(r_by):+.2f} dB   '
          f'relative delta {rel_delta:.3f}')
    differ = rel_delta >= min_rel_delta
    checks.append((f'FX and bypass exports DIFFER measurably '
                   f'(rel delta >= {min_rel_delta})', differ,
                   f'{rel_delta:.3f}'))
    # waveform-level corroboration: same-tone renders correlate ~1 when identical
    _, _, ncc_ab = lib.fit_inside(by_t[:int(2 * fs)], fx_t, search_s=0.15, fs=fs)
    print(f'  fx-vs-bypass waveform corr {ncc_ab:.4f}')

    # 2. CLEAN
    needle = src_t[:int(min(4.0, len(src_t) / fs) * fs)]
    lag, gain, ncc = lib.fit_inside(needle, by_t, search_s=0.15, fs=fs)
    print(f'  source-in-bypass fit : gain {gain:.3f}  corr {ncc:.3f}  '
          f'lag {lag / fs * 1000:+.0f} ms')
    present = ncc > 0.9
    checks.append(('bypass export still contains the source (corr > 0.9)',
                   present, f'ncc {ncc:.3f}'))
    unity = abs(gain - 1.0) <= 0.25
    checks.append(('bypass export matches a PLAIN re-encode (gain 1.00 +/- 0.25)',
                   unity, f'gain {gain:.3f}'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export_fx', nargs='?', default=None)
    ap.add_argument('export_bypass', nargs='?', default=None)
    ap.add_argument('source', nargs='?', default=None)
    ap.add_argument('--min-rel-delta', type=float, default=0.08,
                    help='smallest rms difference that still counts as "the chain did '
                         'something"; lower it only for chains that barely move level')
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        import tempfile
        tmp = tempfile.mkdtemp(prefix='c7self_')
        src = lib.tone(3.5, 1000.0, 0.5)
        sim, _ = lib.simulate_compressor(src, lib.FS)
        wav_s, wav_p = os.path.join(tmp, 's.wav'), os.path.join(tmp, 'p.wav')
        lib.write_wav(wav_s, src)
        lib.write_wav(wav_p, sim)
        m4a_s, m4a_p = os.path.join(tmp, 's.m4a'), os.path.join(tmp, 'p.m4a')
        lib.encode_aac(wav_s, m4a_s)
        lib.encode_aac(wav_p, m4a_p)
        s_d, fs = lib.decode(m4a_s)
        p_d, _ = lib.decode(m4a_p)

        cases = []
        cases.append(('real FX + clean bypass', True,
                      measure(p_d, s_d, s_d, fs, min_rel_delta=args.min_rel_delta)))
        cases.append(('FX == bypass (toggle was a no-op)', False,
                      measure(s_d, s_d, s_d, fs, min_rel_delta=args.min_rel_delta)))
        cases.append(('"bypass" export is secretly processed', False,
                      measure(p_d, p_d, s_d, fs, min_rel_delta=args.min_rel_delta)))
        sys.exit(0 if lib.report_selftest('C7 bypass', cases) else 1)

    if not all([args.export_fx, args.export_bypass, args.source]):
        ap.error('needs EXPORT_FX EXPORT_BYPASS SOURCE (or --selftest)')
    print(f'C7 bypass probe: fx={os.path.basename(args.export_fx)} '
          f'bypass={os.path.basename(args.export_bypass)}')
    fx, fs = lib.decode(args.export_fx)
    by, _ = lib.decode(args.export_bypass)
    src, _ = lib.decode(args.source)
    checks = measure(fx, by, src, fs, min_rel_delta=args.min_rel_delta)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}  {detail}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
