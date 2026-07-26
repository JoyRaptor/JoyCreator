"""Objective check that a source's audio lands in an export ONCE, at the right offset.

Written for SPEC_PIP_AUDIO acceptance 2, whose recorded failure mode is "doubled audio
in an export the user may not re-check". Doubling is not something you can reliably
hear on a phone speaker, and "I listened and it sounded fine" is not a proof — but it
IS trivially measurable: fit the export against the source and read off the scale.

    scale ~= 1.0  -> the source is mixed in exactly once at unity gain
    scale ~= 2.0  -> it is in there twice (the bug)
    scale ~= 0.0  -> it never made it in

Usage:
    python tasks/export_audio_probe.py EXPORT.mp4 SOURCE.mp4 --expect-offset-ms 1200
                                       [--source-dur 3.0] [--expect-gain 1.0]
                                       [--silent-before]

Requires ffmpeg on PATH. Both inputs are decoded to mono 48 kHz so containers,
channel counts and sample rates do not have to match.

Note on offset tolerance: an AAC encoder adds priming/delay of roughly 1024-2112
samples (21-44 ms at 48 kHz), and the muxer aligns to frame boundaries. A measured
offset within ~60 ms of the intended one is therefore not distinguishable from exact,
and this script says so rather than pretending to millisecond precision.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

AAC_DELAY_TOLERANCE_MS = 60


def has_audio(path):
    """True if the file carries an audio stream at all. A file with none cannot be
    decoded to WAV, and ffmpeg fails with a traceback-shaped error rather than an
    answer — so ask first. `--expect-absent` turns "no audio stream" into the PASS."""
    out = subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'a',
         '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', path],
        capture_output=True, text=True)
    return 'audio' in out.stdout


def to_wav(src, dst, dur=None):
    cmd = ['ffmpeg', '-v', 'error', '-i', src, '-vn', '-ac', '1', '-ar', '48000']
    if dur:
        cmd += ['-t', str(dur)]
    cmd += ['-f', 'wav', '-y', dst]
    subprocess.run(cmd, check=True)


def read(path):
    with wave.open(path) as w:
        a = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2')
        return a.astype(np.float64) / 32768.0, w.getframerate()


def rms(x):
    return float(np.sqrt(np.mean(x ** 2))) if len(x) else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export')
    ap.add_argument('source')
    ap.add_argument('--expect-offset-ms', type=float, required=True)
    ap.add_argument('--source-dur', type=float, default=3.0,
                    help='seconds of SOURCE to use as the probe (default 3)')
    ap.add_argument('--expect-gain', type=float, default=1.0)
    ap.add_argument('--silent-before', action='store_true',
                    help='also require the export to be silent before the offset')
    ap.add_argument('--expect-absent', action='store_true',
                    help='invert: PASS when the source is NOT in the export (the '
                         'not-opted-in control). An export with no audio stream at '
                         'all is the strongest form of that.')
    args = ap.parse_args()

    if not has_audio(args.export):
        print(f'export : NO AUDIO STREAM at all ({os.path.basename(args.export)})')
        if args.expect_absent:
            print('\nPASS  (source absent, as expected - the export carries no audio track)')
            sys.exit(0)
        print('\nFAIL  expected the source to be present, but there is no audio track')
        sys.exit(1)

    tmp = tempfile.mkdtemp(prefix='audioprobe_')
    ex_w = os.path.join(tmp, 'ex.wav')
    sr_w = os.path.join(tmp, 'sr.wav')
    to_wav(args.export, ex_w)
    to_wav(args.source, sr_w, args.source_dur)
    ex, fs = read(ex_w)
    sc, fs2 = read(sr_w)
    if not len(ex):
        print('FAIL: the export has NO audio samples at all.')
        sys.exit(1)
    if not len(sc):
        print('FAIL: the source probe decoded to zero samples.')
        sys.exit(1)
    print(f'export : {len(ex)} samples = {len(ex)/fs:.3f}s @ {fs}Hz  rms={rms(ex):.6f}')
    print(f'source : {len(sc)} samples = {len(sc)/fs2:.3f}s @ {fs2}Hz  rms={rms(sc):.6f}')

    off = args.expect_offset_ms / 1000.0
    checks = []

    if args.silent_before and off > 0:
        pre = ex[:int(off * fs)]
        post = ex[int(off * fs):]
        quiet = rms(pre) < max(rms(post) * 0.2, 1e-9)
        print(f'\nrms before {args.expect_offset_ms:.0f}ms : {rms(pre):.6f}   '
              f'after: {rms(post):.6f}   -> {"PASS" if quiet else "FAIL"}')
        checks.append(('silent before the offset', quiet))

    # Locate the source inside the export, then least-squares fit its amplitude there.
    #
    # The probe MUST be short enough to leave real lag headroom. A probe nearly as long
    # as the export leaves the correlation only a few ms of valid lag, the peak lands on
    # the edge of the search range, the fitted segment is truncated, and the gain comes
    # out as noise — which is exactly how this script first reported gain -0.204 on an
    # export whose gain is 0.993. Keep at least 2x the expected offset of headroom.
    head_needed = max(2.0 * off, 1.0)
    max_probe = max(0.4, len(ex) / fs - head_needed)
    probe_dur = min(args.source_dur, max_probe)
    if probe_dur < args.source_dur - 1e-6:
        print(f'  (probe shortened {args.source_dur:.2f}s -> {probe_dur:.2f}s to leave '
              f'{head_needed:.2f}s of lag headroom in a {len(ex)/fs:.2f}s export)')
    probe = sc[:int(probe_dur * fs)]
    probe = probe - probe.mean()
    hay = ex - ex.mean()
    corr = np.correlate(hay, probe, mode='valid')
    lag = int(np.argmax(np.abs(corr)))
    seg = ex[lag:lag + len(probe)]
    m = min(len(seg), len(probe))
    seg, pr = seg[:m], probe[:m]
    denom = float(np.dot(pr, pr))
    scale = float(np.dot(seg, pr) / denom) if denom else 0.0
    ncc = (float(np.dot(seg, pr) / np.sqrt(np.dot(seg, seg) * denom))
           if denom and np.dot(seg, seg) else 0.0)
    measured_ms = lag / fs * 1000.0
    delta = abs(measured_ms - args.expect_offset_ms)
    print(f'\nbest-match lag  : {measured_ms:.1f} ms  (expected {args.expect_offset_ms:.0f} ms, '
          f'delta {delta:.1f} ms)')
    print(f'fitted gain     : {scale:.3f}  (expected ~{args.expect_gain:.2f}; '
          f'~{args.expect_gain*2:.2f} would mean DOUBLED)')
    print(f'correlation     : {ncc:.3f}')

    off_ok = delta <= AAC_DELAY_TOLERANCE_MS
    if off_ok and delta > 5:
        print(f'  (delta {delta:.1f} ms is within AAC priming/frame-alignment, '
              f'so not distinguishable from exact)')
    checks.append(('offset', off_ok))
    checks.append(('present exactly once at the expected gain',
                   abs(scale - args.expect_gain) < 0.4 * max(args.expect_gain, 0.5)))
    checks.append(('waveform actually matches (corr > 0.9)', ncc > 0.9))

    print()
    ok = True
    for name, res in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
