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
    ap.add_argument('--overlap-with', metavar='SOURCE_B', default=None,
                    help='E4 overlap-mix mode: also locate SOURCE_B in the export and '
                         'assert the two clips genuinely MIXED in their overlap region '
                         '(louder than either alone, non-clipping)')
    ap.add_argument('--expect-offset-b-ms', type=float, default=None,
                    help='authored timeline offset of SOURCE_B (required with --overlap-with)')
    args = ap.parse_args()

    if args.overlap_with and args.expect_offset_b_ms is None:
        ap.error('--overlap-with requires --expect-offset-b-ms')

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
    if args.overlap_with:
        solo_span = (args.expect_offset_b_ms - args.expect_offset_ms) / 1000.0
        if solo_span > 0.5:
            probe_dur = min(probe_dur, solo_span - 0.25)
            print(f"  (overlap mode: A probe truncated to {probe_dur:.2f}s — its solo span)")
    if probe_dur < args.source_dur - 1e-6:
        print(f'  (probe shortened {args.source_dur:.2f}s -> {probe_dur:.2f}s to leave '
              f'{head_needed:.2f}s of lag headroom in a {len(ex)/fs:.2f}s export)')
    probe = sc[:int(probe_dur * fs)]
    probe = probe - probe.mean()
    hay = ex - ex.mean()
    # Overlap mode: restrict the search to +/-1.5 s around the AUTHORED offset. Pure-tone
    # test media correlates equally well at any period-aligned shift, so an unrestricted
    # argmax is ambiguous; a genuinely misplaced clip (the old sequential mixer appended
    # B ~2 s late) falls OUTSIDE this window and fails on gain/corr instead.
    hay_a = hay
    lag_pad_a = 0
    if args.overlap_with:
        w0 = max(0, int((args.expect_offset_ms / 1000.0 - 1.5) * fs))
        w1 = min(len(hay), int((args.expect_offset_ms / 1000.0 + 1.5) * fs) + len(probe))
        hay_a = hay[w0:w1]
        lag_pad_a = w0
    corr = np.correlate(hay_a, probe, mode='valid')
    lag = int(np.argmax(np.abs(corr))) + lag_pad_a
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

    # ── E4 overlap-mix mode ──────────────────────────────────────────
    if args.overlap_with:
        sb_w = os.path.join(tmp, 'sb.wav')
        to_wav(args.overlap_with, sb_w)
        sb, fs3 = read(sb_w)
        if not len(sb):
            print('FAIL: the --overlap-with source decoded to zero samples.')
            sys.exit(1)
        off_b = args.expect_offset_b_ms / 1000.0
        a_end = off + len(sc) / fs
        b_end = off_b + len(sb) / fs3
        if fs3 != fs:
            print(f'FAIL: source B decoded at {fs3}Hz, expected {fs}Hz')
            sys.exit(1)

        # 1. B must be present at ITS authored offset (the A8 assertion: the old
        #    sequential mixer appended B after A, landing it late). The B probe starts
        #    where A's solo span ENDS so its window is B-only, and its search is
        #    restricted to +/-1.5 s around B's authored offset (same reason as A).
        b_solo_src_off = max(0.0, a_end - off_b) + 0.25
        b_probe = sb[int(b_solo_src_off * fs):].copy()
        if len(b_probe) < int(0.5 * fs):
            b_probe = sb.copy()  # fallback: whole source
            b_solo_src_off = 0.0
        b_probe = b_probe - b_probe.mean()
        expected_b_lag_ms = args.expect_offset_b_ms + b_solo_src_off * 1000.0
        w0b = max(0, int((expected_b_lag_ms / 1000.0 - 1.5) * fs))
        w1b = min(len(hay), int((expected_b_lag_ms / 1000.0 + 1.5) * fs) + len(b_probe))
        corr_b = np.correlate(hay[w0b:w1b], b_probe, mode='valid')
        lag_b = int(np.argmax(np.abs(corr_b))) + w0b
        seg_b = ex[lag_b:lag_b + len(b_probe)]
        m = min(len(seg_b), len(b_probe))
        seg_bm, pr_bm = seg_b[:m], b_probe[:m]
        denom_b = float(np.dot(pr_bm, pr_bm))
        scale_b = float(np.dot(seg_bm, pr_bm) / denom_b) if denom_b else 0.0
        ncc_b = (float(np.dot(seg_bm, pr_bm) / np.sqrt(np.dot(seg_bm, seg_bm) * denom_b))
                 if denom_b and np.dot(seg_bm, seg_bm) else 0.0)
        measured_b_ms = (lag_b - int(b_solo_src_off * fs)) / fs * 1000.0
        delta_b = abs(measured_b_ms - args.expect_offset_b_ms)
        # A pure-tone probe correlates equally well anti-phase (|corr| peaks at both
        # +/- half a period), so argmax(|corr|) can land on the NEGATIVE peak.
        # Normalise the sign before judging gain/shape — only |values| are meaningful.
        sign_b = 1.0 if scale_b >= 0 else -1.0
        scale_b *= sign_b
        ncc_b *= sign_b
        print(f'\n[B] best-match lag : {measured_b_ms:.1f} ms  '
              f'(expected {args.expect_offset_b_ms:.0f} ms, delta {delta_b:.1f} ms)')
        print(f'[B] fitted gain    : {scale_b:.3f}')
        print(f'[B] correlation    : {ncc_b:.3f}')
        checks.append(('B offset at its authored time', delta_b <= AAC_DELAY_TOLERANCE_MS))
        checks.append(('B present exactly once at the expected gain',
                       abs(scale_b - args.expect_gain) < 0.4 * max(args.expect_gain, 0.5)))
        checks.append(('B waveform actually matches (corr > 0.9)', ncc_b > 0.9))

        # 2. The overlap region must MIX: materially louder than either clip alone,
        #    and close to the uncorrelated-sum prediction sqrt(rmsA^2 + rmsB^2).
        guard = 0.25  # s — stay clear of both edges (AAC priming + correlation smear)
        ov_start = max(off, off_b) + guard
        ov_end = min(a_end, b_end) - guard
        if ov_end <= ov_start or a_end <= off_b:
            print(f'\nFAIL: authored clips do not actually overlap '
                  f'(A {off:.2f}-{a_end:.2f}s, B {off_b:.2f}-{b_end:.2f}s)')
            sys.exit(1)
        solo_a = ex[int((off + guard) * fs):int((off_b - guard) * fs)]
        solo_b = ex[int((a_end + guard) * fs):int((b_end - guard) * fs)]
        overlap = ex[int(ov_start * fs):int(ov_end * fs)]
        ra, rb, rmix = rms(solo_a), rms(solo_b), rms(overlap)
        expected_mix = float(np.sqrt(ra ** 2 + rb ** 2))
        print(f'\noverlap region {ov_start:.2f}-{ov_end:.2f}s:')
        print(f'  rms A-solo   : {ra:.6f}')
        print(f'  rms B-solo   : {rb:.6f}')
        print(f'  rms overlap  : {rmix:.6f}   (uncorrelated-sum predicted {expected_mix:.6f})')
        louder = rmix > 1.15 * max(ra, rb)
        checks.append(('overlap materially LOUDER than either clip alone (>1.15x)', louder))
        if ra > 0 and rb > 0:
            close = 0.75 * expected_mix <= rmix <= 1.25 * expected_mix
            checks.append(('overlap level matches the sum of both clips '
                           '(0.75x-1.25x of predicted)', close))
        peak_all = float(np.max(np.abs(ex))) if len(ex) else 0.0
        print(f'  peak sample  : {peak_all:.4f} across the export')
        checks.append(('no clipping anywhere (peak < 0.99)', peak_all < 0.99))

    print()
    ok = True
    for name, res in checks:
        print(f'  {"PASS" if res else "FAIL"}  {name}')
        ok &= res
    print(f'\n{"PASS" if ok else "FAIL"}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
