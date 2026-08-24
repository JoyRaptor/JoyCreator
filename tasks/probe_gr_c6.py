"""C6 compressor gain-reduction bar — file proof that the reported number is real.

The bar reads CompressorProcessor.getGainReductionDb() via reportGainReductionDb.
Whether that number describes what the compressor DID to the export is measurable
from two files and the source:

  EXPORT_ON   export with the compressor engaged
  EXPORT_OFF  export with the chain bypassed (or the plain source re-encoded)
  SOURCE      the media on the lane (a steady tone fixture makes this exact)

  measured GR = 20log10( amp_off / amp_on )     (Goertzel amplitude of the tone)
  predicted GR = simulate_compressor(SOURCE)    (port of CompressorProcessor maths)

The probe fails unless the chain measurably reduced the tone, the measured amount
matches the predicted amount, AND (when supplied) the value shown on the bar
(--reported-gr-db, read off a screenshot/logcat capture) agrees with the measured
amount. Any of the three alone can be faked; together they cannot.

Usage:
    python tasks/probe_gr_c6.py ON.m4a OFF.m4a SOURCE.m4a \
        [--threshold-db -18] [--ratio 3] [--tone-hz 1000] \
        [--reported-gr-db -5.4]

    python tasks/probe_gr_c6.py --selftest proves the teeth:
      real compression + correct params + honest bar readout -> PASS
      ON == OFF (chain was a no-op)                          -> FAIL
      bar readout claims 0 dB while audio dropped ~6 dB       -> FAIL
      probed with wrong compressor params (ratio 1)          -> FAIL
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def measure(on, off, src, fs, tone_hz, comp_params,
            reported_gr_db=None, gr_tol_db=2.5, bar_tol_db=2.0):
    checks = []
    edge = int(0.1 * fs)
    seg = lambda x: x[edge:len(x) - edge] if len(x) > 2 * edge + 4800 else x

    amp_on = lib.goertzel_amp(seg(on), fs, tone_hz)
    amp_off = lib.goertzel_amp(seg(off), fs, tone_hz)
    if amp_off < 1e-5:
        checks.append(('tone measurable in the bypassed side', False, 'amp_off ~ 0'))
        return checks
    gr_meas = lib.db(amp_off / amp_on)
    print(f'  amp on/off : {lib.db(amp_on):.1f} / {lib.db(amp_off):.1f} dB'
          f'   -> measured GR {gr_meas:.2f} dB')

    # predicted: port of the processor's own maths run over the real source
    _, gr_reported_by_proc = lib.simulate_compressor(seg(src), fs, **comp_params)
    sim_out, _ = lib.simulate_compressor(seg(src), fs, **comp_params)
    gr_pred = lib.db(lib.rms(seg(src)) / max(lib.rms(sim_out), 1e-12))
    print(f'  predicted GR from compressor maths ({comp_params}): '
          f'{gr_pred:.2f} dB  (processor would report {gr_reported_by_proc:.2f})')

    reduced = gr_meas > 0.5
    checks.append(('engaged chain measurably REDUCED the tone (>0.5 dB)',
                   reduced, f'{gr_meas:.2f} dB'))
    matches = abs(gr_meas - gr_pred) <= gr_tol_db
    checks.append((f'measured GR matches compressor maths (+/-{gr_tol_db} dB)',
                   matches, f'{gr_meas:.2f} vs {gr_pred:.2f} dB'))
    if reported_gr_db is not None:
        honest_bar = abs(abs(reported_gr_db) - gr_meas) <= bar_tol_db
        print(f'  bar showed {reported_gr_db:+.2f} dB; tolerance {bar_tol_db} dB')
        checks.append(('bar readout agrees with what the audio did',
                       honest_bar,
                       f'bar {abs(reported_gr_db):.2f} vs measured {gr_meas:.2f} dB'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export_on', nargs='?', default=None)
    ap.add_argument('export_off', nargs='?', default=None)
    ap.add_argument('source', nargs='?', default=None)
    ap.add_argument('--threshold-db', type=float, default=-18.0)
    ap.add_argument('--ratio', type=float, default=3.0)
    ap.add_argument('--attack-ms', type=float, default=20.0)
    ap.add_argument('--release-ms', type=float, default=250.0)
    ap.add_argument('--makeup-db', type=float, default=0.0)
    ap.add_argument('--tone-hz', type=float, default=1000.0)
    ap.add_argument('--reported-gr-db', type=float, default=None,
                    help='value the C6 bar showed (screenshot/logcat); checked against measurement')
    ap.add_argument('--gr-tol-db', type=float, default=2.5)
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()
    comp = dict(threshold_db=args.threshold_db, ratio=args.ratio,
                attack_ms=args.attack_ms, release_ms=args.release_ms,
                makeup_gain_db=args.makeup_db)

    if args.selftest:
        import tempfile
        tmp = tempfile.mkdtemp(prefix='c6self_')
        src = lib.tone(3.0, args.tone_hz, 0.5)
        sim, rep = lib.simulate_compressor(src, lib.FS, **comp)
        wav_s, wav_o = os.path.join(tmp, 's.wav'), os.path.join(tmp, 'o.wav')
        lib.write_wav(wav_s, src)
        lib.write_wav(wav_o, sim)
        m4a_s, m4a_o = os.path.join(tmp, 's.m4a'), os.path.join(tmp, 'o.m4a')
        lib.encode_aac(wav_s, m4a_s)
        lib.encode_aac(wav_o, m4a_o)
        s_d, fs = lib.decode(m4a_s)
        o_d, _ = lib.decode(m4a_o)

        cases = []
        cases.append(('real compression, right params, honest bar', True,
                      measure(o_d, s_d, s_d, fs, args.tone_hz, comp,
                              reported_gr_db=rep)))
        cases.append(('ON == OFF (chain was a no-op)', False,
                      measure(s_d, s_d, s_d, fs, args.tone_hz, comp,
                              reported_gr_db=rep)))
        cases.append(('bar lies: claims 0 while audio dropped', False,
                      measure(o_d, s_d, s_d, fs, args.tone_hz, comp,
                              reported_gr_db=0.0)))
        wrong = dict(comp, ratio=1.0)
        cases.append(('probed with wrong compressor params', False,
                      measure(o_d, s_d, s_d, fs, args.tone_hz, wrong)))
        sys.exit(0 if lib.report_selftest('C6 gain-reduction', cases) else 1)

    if not all([args.export_on, args.export_off, args.source]):
        ap.error('needs EXPORT_ON EXPORT_OFF SOURCE (or --selftest)')
    if abs(args.ratio - 1.0) < 1e-6:
        ap.error('ratio 1.0 cannot compress — the prediction would be vacuous')

    print(f'C6 GR probe: on={os.path.basename(args.export_on)} '
          f'off={os.path.basename(args.export_off)}')
    on, fs = lib.decode(args.export_on)
    off, _ = lib.decode(args.export_off)
    src, _ = lib.decode(args.source)
    checks = measure(on, off, src, fs, args.tone_hz, comp,
                     reported_gr_db=args.reported_gr_db,
                     gr_tol_db=args.gr_tol_db)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}  {detail}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
