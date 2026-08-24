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

E1/C1.E loudness-parity mode (no source/offset needed):
    python tasks/export_audio_probe.py EXPORT.mp4 --preview PREVIEW.m4a [--lufs-tol 1.0]
    Measures both files with ebur128 and asserts preview LUFS == export LUFS.
    Also least-squares fits the two decoded waveforms against each other and requires
    them to MATCH (ncc > 0.9): loudness alone cannot see an EQ-shaped difference, a
    correlated waveform can. A negative control (two files at deliberately different
    levels or shapes) exits FAIL. This is the instrument that closes C1.E's row: run it
    with PREVIEW = a captured preview render of a project with the FX chain engaged and
    EXPORT = the exported file of the same project.

C1.E FX-engaged mode (prove an export actually ran the chain, from two files):
    python tasks/export_audio_probe.py EXPORT.mp4 --fx-source SOURCE.m4a --expect-fx-gain 0.4
    Fits SOURCE inside EXPORT (same correlation machinery as offset mode, +/-120 ms
    search) and asserts:
      a) the source IS present (corr > 0.9) — the export did not lose it;
      b) the fitted gain DIFFERS from unity by more than --fx-gain-tol — i.e. some
         processor genuinely reshaped it (NEGATIVE CONTROL: a no-op chain fits at ~1.00
         and FAILS here; so does an export that dropped the clip);
      c) loudness moved by roughly the same amount (|20*log10(gain)| vs LUFS delta).
    Choose --expect-fx-gain from the known compressor/EQ maths for the project's input
    level (e.g. a -3 dBFS tone through the voice chain compresses to ~0.35-0.55).

Requires ffmpeg on PATH. Both inputs are decoded to mono 48 kHz so containers,
channel counts and sample rates do not have to match.

Note on offset tolerance: an AAC encoder adds priming/delay of roughly 1024-2112
samples (21-44 ms at 48 kHz), and the muxer aligns to frame boundaries. A measured
offset within ~60 ms of the intended one is therefore not distinguishable from exact,
and this script says so rather than pretending to millisecond precision.
"""
import argparse
import math
import os
import re
import subprocess
import sys
import tempfile
import wave

import numpy as np

AAC_DELAY_TOLERANCE_MS = 60


def ebur128_lufs(path):
    """Integrated loudness of `path` in LUFS via `ffmpeg -af ebur128`, or None.

    Mirrors LoudnessAnalyzer.measure on-device: same filter, and like it we read
    the LAST `I:` line (the integrated summary, not the per-frame gates).
    Non-finite summaries (digital silence -> `-inf`) return None rather than a
    number that would silently compare as a huge gain difference.
    """
    r = subprocess.run(
        ['ffmpeg', '-hide_banner', '-nostats', '-i', path,
         '-filter:a', 'ebur128=framelog=verbose', '-f', 'null', '-'],
        capture_output=True, text=True)
    matches = re.findall(r'I:\s+(-?[0-9.]+|[-+]inf|nan)\s+LUFS', r.stderr)
    if not matches:
        return None
    try:
        v = float(matches[-1])
    except ValueError:
        return None
    return v if math.isfinite(v) else None


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
    ap.add_argument('export', nargs='?', default=None,
                    help='export file (required in both modes)')
    ap.add_argument('source', nargs='?', default=None,
                    help='source file (offset mode only)')
    ap.add_argument('--preview', metavar='PREVIEW_FILE', default=None,
                    help='E1 parity mode: measure both files with ebur128 and assert '
                         "the preview's integrated LUFS equals the export's within "
                         '--lufs-tol. Replaces the offset/gain checks.')
    ap.add_argument('--lufs-tol', type=float, default=1.0,
                    help='max |preview LUFS - export LUFS| for parity PASS (default 1.0)')
    ap.add_argument('--fx-source', metavar='SOURCE_FILE', default=None,
                    help='C1.E FX-engaged mode: locate SOURCE_FILE inside EXPORT and '
                         'assert the chain actually reshaped it (gain != unity).')
    ap.add_argument('--expect-fx-gain', type=float, default=0.5,
                    help='approximate gain the FX chain should apply (default 0.5)')
    ap.add_argument('--fx-gain-tol', type=float, default=0.2,
                    help='how far from --expect-fx-gain still counts (default 0.2); '
                         'a fitted gain within tol of 1.00 FAILS — that is a no-op chain')
    ap.add_argument('--expect-offset-ms', type=float, default=None)
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

    if not args.export:
        ap.error('EXPORT file is required')

    if args.fx_source:
        # ── C1.E: FX-engaged export proof, from files alone ──────────
        tmp = tempfile.mkdtemp(prefix='fxengaged_')
        ex_w = os.path.join(tmp, 'ex.wav')
        src_w = os.path.join(tmp, 'src.wav')
        to_wav(args.export, ex_w)
        to_wav(args.fx_source, src_w, 4.0)   # probe = first 4 s of the source
        ex, fs = read(ex_w)
        sc, fs2 = read(src_w)
        if not len(ex) or not len(sc):
            print('FAIL: a side decoded to zero samples.')
            sys.exit(1)
        print(f'export : {len(ex)} samples = {len(ex)/fs:.3f}s @ {fs}Hz  rms={rms(ex):.6f}')
        print(f'source : {min(len(sc), int(4*fs))} samples @ {fs2}Hz  rms={rms(sc):.6f}')

        needle = sc[:int(4 * fs)] - (sc[:int(4 * fs)].mean() if len(sc) else 0)
        hay = ex - ex.mean()
        search = int(0.12 * fs)
        if len(hay) <= 2 * search + len(needle):
            print('FAIL: export too short to search for the source.')
            sys.exit(1)
        corr_full = np.correlate(hay[search:len(hay) - search], needle, mode='valid')
        lag = int(np.argmax(np.abs(corr_full))) + search
        seg = ex[lag:lag + len(needle)]
        m = min(len(seg), len(needle))
        seg, pr = seg[:m], needle[:m]
        denom = float(np.dot(pr, pr))
        scale = float(np.dot(seg, pr) / denom) if denom else 0.0
        ncc = (float(np.dot(seg, pr) / np.sqrt(float(np.dot(seg, seg)) * denom))
               if denom and float(np.dot(seg, seg)) else 0.0)
        sign = 1.0 if scale >= 0 else -1.0
        ncc *= sign
        scale *= sign

        checks = []
        present = ncc > 0.9
        print(f'best-match lag : {lag / fs * 1000.0:+.1f} ms   fitted gain {scale:.3f}   corr {ncc:.3f}')
        checks.append(('source IS in the export (corr > 0.9)', present))

        # THE assertion: unity-gain fit means the chain was a NO-OP (or never ran).
        not_noop = abs(scale - 1.0) > args.fx_gain_tol
        checks.append(('chain actually reshaped the audio (gain != 1.00 '
                       f'+/- {args.fx_gain_tol})', not_noop))
        near_expected = abs(scale - args.expect_fx_gain) < args.fx_gain_tol
        checks.append((f'gain near the expected FX maths ({args.expect_fx_gain} '
                       f'+/- {args.fx_gain_tol})', near_expected))

        ex_lufs = ebur128_lufs(args.export)
        src_lufs = ebur128_lufs(args.fx_source)
        if ex_lufs is not None and src_lufs is not None:
            lufs_move = abs(ex_lufs - src_lufs)
            gain_db = abs(20 * math.log10(max(scale, 1e-6)))
            close = abs(lufs_move - gain_db) < 3.0
            print(f'loudness moved {lufs_move:.2f} LU; fitted gain implies {gain_db:.2f} dB')
            checks.append(('loudness movement agrees with fitted gain (<3 dB apart)', close))
        ok = all(r for _, r in checks)
        for name, res in checks:
            print(f'  {"PASS" if res else "FAIL"}  {name}')
        print(f'\n{"PASS" if ok else "FAIL"}')
        sys.exit(0 if ok else 1)

    if args.preview:
        # ── E1/C1.E: preview/export parity mode ──────────────────────
        ex_lufs = ebur128_lufs(args.export)
        pv_lufs = ebur128_lufs(args.preview)
        print(f'preview : {os.path.basename(args.preview)}  '
              f'integrated LUFS = {pv_lufs if pv_lufs is not None else "UNMEASURABLE (silence?)"}')
        print(f'export  : {os.path.basename(args.export)}  '
              f'integrated LUFS = {ex_lufs if ex_lufs is not None else "UNMEASURABLE (silence?)"}')
        checks = []
        if pv_lufs is None or ex_lufs is None:
            print('\nFAIL  a side was unmeasurable — cannot claim parity over silence')
            checks.append(('loudness measurable on both sides', False))
        else:
            delta = abs(pv_lufs - ex_lufs)
            ok = delta <= args.lufs_tol
            print(f'\nloudness delta    : {delta:.2f} LU   (tolerance {args.lufs_tol:.1f})')
            checks.append(('loudness parity (delta <= tol)', ok))

        # Waveform match: decode both sides and least-squares fit one against the other.
        # Preview and export run the SAME processors, so their renders must correlate
        # ~perfectly; an EQ/spectrum-only difference (invisible to LUFS) shows up here.
        tmp = tempfile.mkdtemp(prefix='fxparity_')
        ex_w = os.path.join(tmp, 'ex.wav')
        pv_w = os.path.join(tmp, 'pv.wav')
        to_wav(args.export, ex_w)
        to_wav(args.preview, pv_w)
        ex, fs = read(ex_w)
        pv, fs2 = read(pv_w)
        if not len(ex) or not len(pv):
            print('FAIL: a side decoded to zero samples.')
            checks.append(('waveform decodable on both sides', False))
        else:
            n = min(len(ex), len(pv))
            hay, needle = ex[:n] - ex[:n].mean(), pv[:n] - pv[:n].mean()
            # Search +/-120 ms of alignment for the best match (AAC priming differs per
            # encode); fit gain at that lag like the offset mode does.
            search = int(0.12 * fs)
            corr_full = np.correlate(hay[search:len(hay) - search],
                                     needle, mode='valid') if n > 2 * search + len(needle) else None
            if corr_full is None:
                lag, seg, pr = 0, hay[:len(needle)], needle
            else:
                lag = int(np.argmax(np.abs(corr_full))) + search
                seg = hay[lag:lag + len(needle)]
                pr = needle
            denom = float(np.dot(pr, pr))
            scale = float(np.dot(seg, pr) / denom) if denom else 0.0
            ncc = (float(np.dot(seg, pr) / np.sqrt(float(np.dot(seg, seg)) * denom))
                   if denom and float(np.dot(seg, seg)) else 0.0)
            sign = 1.0 if scale >= 0 else -1.0
            ncc *= sign
            scale *= sign
            print(f'waveform match    : lag {lag / fs * 1000.0:+.1f} ms  fitted gain '
                  f'{scale:.3f}  corr {ncc:.3f}')
            checks.append(('waveform actually matches preview vs export (corr > 0.9)',
                           ncc > 0.9))
        ok = all(r for _, r in checks)
        for name, res in checks:
            print(f'  {"PASS" if res else "FAIL"}  {name}')
        print(f'\n{"PASS" if ok else "FAIL"}')
        sys.exit(0 if ok else 1)

    if args.source is None or args.expect_offset_ms is None:
        ap.error('offset mode needs SOURCE and --expect-offset-ms '
                 '(or use --preview for E1 parity mode)')

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
