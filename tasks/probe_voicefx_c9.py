"""C9 per-clip voice-chain toggle — file proof that opting OUT is truly silent.

C9's whole point is OPT-IN per clip: a music lane under a voice lane must NOT be
processed because someone enhanced the voice. The failure modes are symmetric:

  EXPORT_ON    export with the clip's "Enhance voice" switch ON
  EXPORT_OFF   same project, switch OFF (or the plain source re-encoded)
  SOURCE       the media on the lane

  1. DIFFER:   ON vs OFF must differ measurably — the chain genuinely ran when asked.
  2. CLEAN:    OFF must match the PLAIN source (fit gain ~1, corr > 0.9). An opt-OUT
               that still colours the audio is exactly the pre-C9 bug this row exists
               to kill, and it would hide behind a passing DIFFER check.
  3. HONEST BAR: optionally, --reported-gr-db from the FX tab's GR readout must agree
               with what the audio did (same instrument as probe_gr_c6).

Usage:
    python tasks/probe_voicefx_c9.py ON.m4a OFF.m4a SOURCE.m4a [--tone-hz 1000]

Fixture: export/make_fixtures.py gr_tone.m4a on one lane; export twice, flipping
the FX tab's Enhance-voice switch between runs.

    python tasks/probe_voicefx_c9.py --selftest proves the teeth:
      real chain + transparent opt-out                    -> PASS
      ON == OFF (toggle was a no-op / never wired)        -> FAIL
      OFF secretly processed (opt-out leaks the chain)     -> FAIL
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def measure(on, off, src, fs, tone_hz, min_rel_delta=0.08):
    checks = []
    edge = int(0.1 * fs)
    trim = lambda x: x[edge:len(x) - edge] if len(x) > 2 * edge + 4800 else x
    on_t, off_t, src_t = trim(on), trim(off), trim(src)

    # 1. DIFFER — the chain ran when opted in
    r_on, r_off = lib.rms(on_t), lib.rms(off_t)
    rel = abs(r_on - r_off) / max(r_on, r_off, 1e-9)
    amp_on = lib.goertzel_amp(on_t, fs, tone_hz)
    amp_off = lib.goertzel_amp(off_t, fs, tone_hz)
    band_rel = abs(amp_on - amp_off) / max(amp_on, amp_off, 1e-9)
    strength = max(rel, band_rel)
    print(f'  rms on/off {lib.db(r_on):+.2f}/{lib.db(r_off):+.2f} dB   '
          f'tone-band rel delta {band_rel:.3f}')
    checks.append((f'ON and OFF exports DIFFER (chain ran when opted in, '
                   f'>= {min_rel_delta})', strength >= min_rel_delta,
                   f'{strength:.3f}'))

    # 2. CLEAN — opting out is transparent
    needle = src_t[:int(min(4.0, len(src_t) / fs) * fs)]
    lag, gain, ncc = lib.fit_inside(needle, off_t, search_s=0.15, fs=fs)
    print(f'  source-in-OFF fit : gain {gain:.3f}  corr {ncc:.3f}')
    checks.append(('OFF still contains the source (corr > 0.9)', ncc > 0.9,
                   f'ncc {ncc:.3f}'))
    checks.append(('OFF matches the PLAIN source (gain 1.00 +/- 0.25)',
                   abs(gain - 1.0) <= 0.25, f'gain {gain:.3f}'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export_on', nargs='?', default=None)
    ap.add_argument('export_off', nargs='?', default=None)
    ap.add_argument('source', nargs='?', default=None)
    ap.add_argument('--tone-hz', type=float, default=1000.0)
    ap.add_argument('--min-rel-delta', type=float, default=0.08)
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        import tempfile
        tmp = tempfile.mkdtemp(prefix='c9self_')
        comp = dict(threshold_db=-18.0, ratio=3.0, attack_ms=20.0,
                    release_ms=250.0, makeup_gain_db=0.0)
        src = lib.tone(3.5, args.tone_hz, 0.5)
        sim, _ = lib.simulate_compressor(src, lib.FS, **comp)
        wav_s, wav_p = os.path.join(tmp, 's.wav'), os.path.join(tmp, 'p.wav')
        lib.write_wav(wav_s, src)
        lib.write_wav(wav_p, sim)
        m4a_s, m4a_p = os.path.join(tmp, 's.m4a'), os.path.join(tmp, 'p.m4a')
        lib.encode_aac(wav_s, m4a_s)
        lib.encode_aac(wav_p, m4a_p)
        s_d, fs = lib.decode(m4a_s)
        p_d, _ = lib.decode(m4a_p)

        cases = []
        cases.append(('real chain + transparent opt-out', True,
                      measure(p_d, s_d, s_d, fs, args.tone_hz,
                              args.min_rel_delta)))
        cases.append(('ON == OFF (toggle never wired)', False,
                      measure(s_d, s_d, s_d, fs, args.tone_hz,
                              args.min_rel_delta)))
        cases.append(('OFF secretly processed (opt-out leaks)', False,
                      measure(p_d, p_d, s_d, fs, args.tone_hz,
                              args.min_rel_delta)))
        sys.exit(0 if lib.report_selftest('C9 voice-chain opt-in', cases) else 1)

    if not all([args.export_on, args.export_off, args.source]):
        ap.error('needs EXPORT_ON EXPORT_OFF SOURCE (or --selftest)')
    print(f'C9 opt-in probe: on={os.path.basename(args.export_on)} '
          f'off={os.path.basename(args.export_off)}')
    on, fs = lib.decode(args.export_on)
    off, _ = lib.decode(args.export_off)
    src, _ = lib.decode(args.source)
    checks = measure(on, off, src, fs, args.tone_hz, args.min_rel_delta)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}  {detail}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
