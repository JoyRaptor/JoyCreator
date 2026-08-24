"""B3 solo — file proof that soloing lane A removes lane B from the export.

SPEC_AUDIO_UX_V1 B3 is implemented as derived muting; the acceptance question
"did non-soloed carriers really go silent in the EXPORT" currently waits on
someone noticing by ear. Two tones make it a measurement:

  lane A tone (default 440 Hz)  -> must be PRESENT in the export
  lane B tone (default 1500 Hz) -> must be ABSENT

Presence is Goertzel amplitude at the exact frequency, judged against off-tune
guard frequencies (the local noise floor — AAC reproduction is never mathematically
silent) and against A's amplitude. Absence means: at the noise floor, not merely
quieter.

Usage:
    python tasks/probe_solo_b3.py SOLO_EXPORT.m4a [--freq-a 440] [--freq-b 1500]

The fixture is the two-tone pair from export/make_fixtures.py on lanes A and B;
export once with solo on lane A. Running this probe on the UNSOLOED export of the
same project is the operator-side negative control and MUST FAIL.

    python tasks/probe_solo_b3.py --selftest     proves the teeth:
      mix(A+B)   -> FAIL   (B still there = solo did nothing = exactly the bug)
      A only     -> PASS
      A only, but probe pointed at the wrong fA -> FAIL (presence check has teeth too)
"""
import argparse
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_probe_lib as lib


def measure(x, fs, freq_a, freq_b):
    """checks: list of (name, ok, detail)."""
    checks = []
    edge = int(0.1 * fs)                      # skip AAC priming / encoder tail
    seg = x[edge:len(x) - edge] if len(x) > 2 * edge + 4800 else x
    amp_a = lib.goertzel_amp(seg, fs, freq_a)
    amp_b = lib.goertzel_amp(seg, fs, freq_b)
    # guards sit between and beside the tones: what the noise floor alone produces
    guards = [amp for g in (freq_a * 0.5, (freq_a + freq_b) / 2, freq_b * 1.6)
              for amp in [lib.goertzel_amp(seg, fs, g)]]
    floor = max(guards)
    print(f'  amp@A({freq_a:.0f}Hz) : {lib.db(amp_a):7.1f} dB')
    print(f'  amp@B({freq_b:.0f}Hz) : {lib.db(amp_b):7.1f} dB   '
          f'(noise-floor guards max {lib.db(floor):.1f} dB)')

    present_a = amp_a > max(8.0 * floor, 2e-4)
    checks.append((f'lane A tone IS present ({freq_a:.0f} Hz > 8x noise floor)',
                   present_a,
                   f'{lib.db(amp_a):.1f} vs floor {lib.db(floor):.1f} dB'))
    absent_b = amp_b < max(4.0 * floor, 0.10 * amp_a)
    checks.append(('lane B tone is ABSENT (at noise floor, <10% of A)',
                   absent_b,
                   f'{lib.db(amp_b):.1f} dB vs limit '
                   f'{lib.db(max(4.0 * floor, 0.10 * amp_a)):.1f} dB'))
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export', nargs='?', default=None)
    ap.add_argument('--freq-a', type=float, default=440.0)
    ap.add_argument('--freq-b', type=float, default=1500.0)
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()

    if args.selftest:
        tmp = tempfile.mkdtemp(prefix='b3self_')
        wav = os.path.join(tmp, 'f.wav')
        cases = []

        def case(label, expect_pass, sig):
            lib.write_wav(wav, sig)
            m4a = os.path.join(tmp, label.replace(' ', '_') + '.m4a')
            lib.encode_aac(wav, m4a)
            x, fs = lib.decode(m4a)
            cases.append((label, expect_pass, measure(x, fs, args.freq_a, args.freq_b)))

        a = lib.tone(3.0, args.freq_a, 0.4)
        b = lib.tone(3.0, args.freq_b, 0.4)
        case('mix A+B (solo no-op)', False, lib.mix(a, b))
        case('A only (solo worked)', True, a)
        wrong = dict(freq=args.freq_b)  # probe aimed at B's freq sees nothing at A
        xw = lib.tone(3.0, args.freq_a, 0.4)
        lib.write_wav(wav, xw)
        m4a = os.path.join(tmp, 'wrongfreq.m4a')
        lib.encode_aac(wav, m4a)
        x, fs = lib.decode(m4a)
        cases.append(('A only but probed at wrong fA', False,
                      measure(x, fs, args.freq_b, args.freq_a)))
        ok = lib.report_selftest('B3 solo', cases)
        sys.exit(0 if ok else 1)

    if not args.export:
        ap.error('EXPORT file required (or --selftest)')
    if abs(args.freq_a - args.freq_b) < 200:
        ap.error('--freq-a and --freq-b must be far apart to be distinguishable bands')

    print(f'B3 solo probe: {os.path.basename(args.export)}')
    x, fs = lib.decode(args.export)
    if not len(x):
        print('FAIL: export decoded to zero samples.')
        sys.exit(1)
    checks = measure(x, fs, args.freq_a, args.freq_b)
    ok = True
    for name, res, detail in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}  {detail}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
