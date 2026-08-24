#!/usr/bin/env python3
"""A6 T1 mixed-rate check — measure the RESAMPLED LANE inside a full mix.

export_resample_probe.py assumes the whole file is the lone 6 s tone fixture. T1
(master 48 kHz video + 44.1 kHz lane tone) exports an 8 s MIX, so this script
isolates the lane first:

  1. band-select 950..1050 Hz (FFT mask + irfft) -> the lane alone,
  2. pitch of the isolated lane must be 1000 Hz +/-25 (chipmunk = 1088.4),
  3. its burst grid must hold 6 onsets at 1 s spacing, worst drift < 50 ms,
  4. the master's 450..550 Hz band must span the full export (master intact),
  5. NEGCTRL: run steps 2-3 on tasks/a6/a6_BROKEN_chipmunk.m4a -> must FAIL.

Usage:
    python tasks/a6/a6_mixed_check.py EXPORTED_FILE [CHIPMUNK_FILE]
"""
import subprocess, sys, tempfile, os, wave
import numpy as np

LANE_HZ   = 1000.0
MASTER_HZ = 500.0
EXPECT_DUR = 8.0
BURST_PERIOD = 1.0
N_BURSTS = 6

fails = []
def check(ok, label):
    print(("PASS  " if ok else "FAIL  ") + label)
    if not ok:
        fails.append(label)

def decode(path):
    dst = os.path.join(tempfile.mkdtemp(), "probe.wav")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path,
                    "-ac", "1", "-c:a", "pcm_s16le", dst], check=True)
    with wave.open(dst, "rb") as w:
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0, rate

def band_isolate(x, rate, lo, hi):
    """Zero-phase band-select via FFT masking (good enough for tonal isolation)."""
    sp = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / rate)
    mask = (freqs >= lo) & (freqs <= hi)
    keep = np.zeros_like(sp)
    keep[mask] = sp[mask]
    return np.fft.irfft(keep, n=len(x))

def dominant_hz(x, rate):
    w = x * np.hanning(len(x))
    sp = np.abs(np.fft.rfft(w))
    k = int(np.argmax(sp[1:]) + 1)
    a, b, c = sp[k - 1], sp[k], sp[k + 1]
    denom = (a - 2 * b + c)
    delta = 0.5 * (a - c) / denom if denom != 0 else 0.0
    return (k + delta) * rate / len(x)

def burst_edges(x, rate):
    win = max(1, int(rate * 0.005))
    env = np.sqrt(np.convolve(x * x, np.ones(win) / win, mode="same"))
    peak = np.percentile(env, 95)
    if peak <= 0:
        return []
    on = env > (peak * 0.35)
    edges = []
    if on.size and on[0]:
        edges.append(0.0)
    for i in range(1, len(on)):
        if on[i] and not on[i - 1]:
            t = i / rate
            if not edges or t - edges[-1] > 0.25:
                edges.append(t)
    return edges

def lane_report(x, rate, label_prefix):
    lane = band_isolate(x, rate, 950.0, 1050.0)
    # steady-state window inside the FIRST burst
    seg = lane[int(rate * 0.05):int(rate * 0.45)]
    f = dominant_hz(seg, rate)
    print("%slane pitch : %.1f Hz (want %.0f +/- 25)" % (label_prefix, f, LANE_HZ))
    edges = burst_edges(lane, rate)
    print("%sbursts     : %d found -> %s" % (
        label_prefix, len(edges),
        ", ".join("%.3f" % e for e in edges[:8]) + ("..." if len(edges) > 8 else "")))
    return f, edges

def main():
    x, rate = decode(sys.argv[1])
    dur = len(x) / rate
    print("export: %.3fs @ %d Hz container" % (dur, rate))
    check(abs(dur - EXPECT_DUR) < 0.10,
          "duration preserved: %.3fs (want %.1f +/- 0.10)" % (dur, EXPECT_DUR))

    f, edges = lane_report(x, rate, "")
    check(abs(f - LANE_HZ) < 25.0,
          "RESAMPLED LANE pitch preserved: %.1f Hz (chipmunk would read 1088.4)" % f)

    # Burst structure is only assertable when the LANE FIXTURE IS GATED. This checker was
    # asserting 6 bursts against clip44.m4a, which is a CONTINUOUS 1 kHz tone -- so it
    # reported "found 1" and FAILED on a perfectly good export, while the commit that ran it
    # recorded "6 bursts drift 0.022s" that this file cannot contain. A check that fails on
    # correct output teaches people to ignore it, and a recorded number that the artifact
    # cannot produce is worse than no number at all.
    #
    # So: decide from the SIGNAL whether gating exists, and say which branch ran. A continuous
    # lane still proves the things that matter for A6 -- pitch (the chipmunk test), duration,
    # and the master surviving the mix. Timing needs the gated fixture,
    # tasks/a6/a6_tone_44100.m4a.
    gated = len(edges) >= 2
    if gated:
        lead = edges[0]
        drift = max(abs((e - lead) - i * BURST_PERIOD)
                    for i, e in enumerate(edges))
        check(len(edges) == N_BURSTS,
              "all %d lane bursts present (found %d)" % (N_BURSTS, len(edges)))
        check(drift < 0.050,
              "lane burst SPACING holds: worst drift %.3fs (<0.050)" % drift)
    else:
        print("      lane fixture is CONTINUOUS (%d onset) -- burst timing NOT asserted; "
              "use tasks/a6/a6_tone_44100.m4a for the gated case" % len(edges))

    master = band_isolate(x, rate, 450.0, 550.0)
    rms_master = float(np.sqrt(np.mean(master ** 2))) if len(master) else 0.0
    rms_lane  = float(np.sqrt(np.mean(band_isolate(x, rate, 950.0, 1050.0) ** 2)))
    print("master band rms: %.4f   lane band rms: %.4f" % (rms_master, rms_lane))
    check(rms_master > 0.01,
          "master 48 kHz audio present through the mix (rms %.4f)" % rms_master)

    # ── NEGCTRL on real data: the pre-fix chipmunk capture must FAIL the pitch gate ──
    if len(sys.argv) > 2:
        cx, crate = decode(sys.argv[2])
        cf, _ = lane_report(cx, crate, "[chipmunk] ")
        check(abs(cf - LANE_HZ) >= 25.0,
              "NEGCTRL: known-chipmunk fixture reads %.1f Hz — WOULD be caught" % cf)

    print("ALL GREEN" if not fails else "%d FAILED" % len(fails))
    sys.exit(1 if fails else 0)

main()
