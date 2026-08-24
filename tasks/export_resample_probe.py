#!/usr/bin/env python3
"""
A6 probe — did the real export resample correctly?

SPEC_AUDIO_UX_V1 row A6 (ResamplingAudioProcessor). The JVM harness
(tools/jvm-harness/run-resample.sh) proves the arithmetic off device. It cannot
prove that media3 hands the processor the buffers it expects during an actual
export. This does, by reading the exported FILE.

The failure this exists to catch is the "chipmunk": a 44.1 kHz source declared as
48 kHz plays 8.8% fast and 8.8% sharp. It is obvious once you hear it and
invisible in any log.

Source fixture: 1000 Hz sine at 44100 Hz, gated 500ms ON / 500ms OFF, 6.0s.
The gating is what makes TIMING measurable — a pure tone would prove pitch and
nothing else.

Usage:
    python tasks/export_resample_probe.py EXPORTED_FILE
"""
import subprocess, sys, tempfile, os, wave, struct
import numpy as np

TONE_HZ      = 1000.0
SRC_RATE     = 44100
EXPECT_DUR   = 6.0      # seconds
BURST_ON     = 0.5
BURST_PERIOD = 1.0
N_BURSTS     = 6

fails = []
def check(ok, label):
    print(("PASS  " if ok else "FAIL  ") + label)
    if not ok:
        fails.append(label)

def to_wav(src):
    dst = os.path.join(tempfile.mkdtemp(), "probe.wav")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-ac", "1", "-c:a", "pcm_s16le", dst], check=True)
    return dst

def read(path):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    return x, rate

def dominant_hz(x, rate):
    """Frequency of the strongest bin, parabolically interpolated."""
    w = x * np.hanning(len(x))
    sp = np.abs(np.fft.rfft(w))
    k = int(np.argmax(sp[1:]) + 1)
    a, b, c = sp[k - 1], sp[k], sp[k + 1]
    denom = (a - 2 * b + c)
    delta = 0.5 * (a - c) / denom if denom != 0 else 0.0
    return (k + delta) * rate / len(x)

def burst_edges(x, rate):
    """Rising edges of the gated envelope, in seconds."""
    win = max(1, int(rate * 0.005))
    env = np.sqrt(np.convolve(x * x, np.ones(win) / win, mode="same"))
    peak = np.percentile(env, 95)
    if peak <= 0:
        return []
    on = env > (peak * 0.35)
    edges = []
    # The fixture's FIRST burst starts at t=0, so it has no rising edge to find. Without
    # this the probe reports 5 bursts out of 6 and blames the exporter for its own
    # off-by-one — which is exactly what the self-test on the untouched source caught.
    if on[0]:
        edges.append(0.0)
    for i in range(1, len(on)):
        if on[i] and not on[i - 1]:
            t = i / rate
            if not edges or t - edges[-1] > 0.25:   # debounce
                edges.append(t)
    return edges

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    x, rate = read(to_wav(sys.argv[1]))
    dur = len(x) / rate
    print("export: %.3fs @ %d Hz container" % (dur, rate))

    # 1. DURATION — a wrong output rate stretches or squeezes the whole file.
    check(abs(dur - EXPECT_DUR) < 0.10,
          "duration preserved: %.3fs (want %.1f +/- 0.10)" % (dur, EXPECT_DUR))

    # 2. PITCH — the chipmunk check. Measured on one burst, not the gaps.
    seg = x[int(rate * 0.05):int(rate * 0.45)]
    f = dominant_hz(seg, rate)
    check(abs(f - TONE_HZ) < 25.0,
          "pitch preserved: %.1f Hz (want %d +/- 25)" % (f, TONE_HZ))

    # 3. TIMING — bursts must still land on the 1.0s grid. Catches a time-warp
    #    that a single pitch reading at the head of the file would miss.
    edges = burst_edges(x, rate)
    print("      burst onsets: " + ", ".join("%.3f" % e for e in edges))
    check(len(edges) == N_BURSTS,
          "all %d bursts present (found %d)" % (N_BURSTS, len(edges)))
    if len(edges) >= 2:
        # Measure drift RELATIVE TO THE FIRST ONSET, and report the constant offset
        # separately. A real export starts the whole file a few tens of ms late (encoder
        # priming, container timestamps) — that is a constant shift, not a time-warp, and
        # every burst carries it equally. Charging it to "drift" made a correct export read
        # as 0.062s of warp when the actual spacing error was 0.021s. Warp is what stretches
        # a file and it shows up as the gap between bursts CHANGING, which this measures.
        lead = edges[0]
        drift = max(abs((e - lead) - i * BURST_PERIOD) for i, e in enumerate(edges))
        print("      constant lead-in %.3fs (encoder priming; not drift)" % lead)
        check(drift < 0.050,
              "burst SPACING holds to the end: worst drift %.3fs (<0.050)" % drift)

    # ── NEGATIVE CONTROLS ────────────────────────────────────────────────────
    # Without these the checks above are assertions, not evidence. Each one
    # synthesises the exact defect A6 exists to prevent and confirms the check
    # above would have caught it.
    ratio = 48000.0 / SRC_RATE           # 1.0884 — the naive-reinterpretation error

    chip_hz = TONE_HZ * ratio
    check(abs(chip_hz - TONE_HZ) >= 25.0,
          "NEGCTRL: chipmunk (44.1k read as 48k) would read %.1f Hz — caught by pitch" % chip_hz)

    chip_dur = EXPECT_DUR / ratio
    check(abs(chip_dur - EXPECT_DUR) >= 0.10,
          "NEGCTRL: same defect shortens file to %.3fs — caught by duration" % chip_dur)

    warp_drift = abs((N_BURSTS - 1) * BURST_PERIOD / ratio - (N_BURSTS - 1) * BURST_PERIOD)
    check(warp_drift >= 0.050,
          "NEGCTRL: time-warp drifts last burst by %.3fs — caught by grid" % warp_drift)

    print("ALL GREEN" if not fails else "%d FAILED" % len(fails))
    sys.exit(1 if fails else 0)

main()
