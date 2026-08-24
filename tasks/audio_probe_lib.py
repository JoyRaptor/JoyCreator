"""Shared DSP for the file-provable audio spec probes (B3/B4/C6/C7/C5.E).

Companion to tasks/export_audio_probe.py, which proves presence/offset/gain by
correlation. These probes need frequency-selective and time-local measurements —
"lane B's tone is ABSENT", "the meter value matches THIS window", "the music
band dipped by exactly the keyframed amount" — so they share:

  goertzel_amp     amplitude of ONE exact frequency in real PCM (pure-tone probes)
  band_envelope    amplitude-vs-time of one band (ducking curves)
  decode / write_wav / encode_aac   fixture plumbing (synthesised WAV -> AAC ->
                   decoded again, because AAC priming is part of the pipeline
                   under test and a probe proven only on raw WAV proves less)

Every probe built on this library MUST expose --selftest, which synthesises a
good input (must PASS) AND a deliberately broken input (must FAIL) and runs the
same measurement code on both. A probe that cannot fail on the broken input has
no teeth; this project has produced four false greens in tooling already, so
run_negctl_suite.py refuses to certify any probe whose negative control was not
demonstrated in this session.
"""
import math
import os
import subprocess
import tempfile
import wave

import numpy as np

FS = 48000


# ── IO ────────────────────────────────────────────────────────────────────────

def write_wav(path, x, fs=FS):
    """Float PCM [-1, 1] -> 16-bit mono WAV. Clips defensively: a fixture that
    silently wraps must not become a probe that 'passes' on garbage."""
    x = np.clip(np.asarray(x, dtype=np.float64), -1.0, 1.0)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(fs)
        w.writeframes((x * 32767).astype('<i2').tobytes())


def encode_aac(wav_path, out_path):
    """WAV -> mono AAC .m4a at 128k, the same codec family exports produce."""
    subprocess.run(
        ['ffmpeg', '-v', 'error', '-y', '-i', wav_path,
         '-c:a', 'aac', '-b:a', '128k', '-ac', '1', '-ar', str(fs_of(wav_path)),
         out_path],
        check=True)


def fs_of(path):
    import wave as _w
    with _w.open(path) as w:
        return w.getframerate()


def decode(path, dur=None):
    """Any media file -> (float64 mono [-1,1], sample_rate), via ffmpeg, the same
    normalisation export_audio_probe.py uses so containers never matter."""
    tmp = tempfile.mkdtemp(prefix='probe_dec_')
    wav = os.path.join(tmp, 'dec.wav')
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-vn', '-ac', '1',
           '-ar', str(FS)]
    if dur:
        cmd += ['-t', str(dur)]
    cmd += ['-f', 'wav', '-y', wav]
    subprocess.run(cmd, check=True)
    with wave.open(wav) as w:
        a = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2')
        return a.astype(np.float64) / 32768.0, w.getframerate()


# ── synthesis (fixtures + selftests) ─────────────────────────────────────────

def tone(dur_s, hz, amp=0.5, phase=0.0, fs=FS):
    t = np.arange(int(dur_s * fs)) / fs
    return amp * np.sin(2 * np.pi * hz * t + phase)


def silence(dur_s, fs=FS):
    return np.zeros(int(dur_s * fs))


def mix(*parts):
    """Overlay signals of different lengths into one buffer."""
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    peak = np.max(np.abs(out))
    if peak > 0.98:
        out *= 0.98 / peak
    return out


# ── measurement ──────────────────────────────────────────────────────────────

def goertzel_amp(x, fs, f0):
    """Amplitude of an EXACT frequency in x.

    For pure-tone probes this beats an FFT bin: the tone never lands exactly on
    a bin, and leakage across neighbours is what a 'is it there' judgement would
    have to argue about. Goertzel projects onto sin/cos at f0 directly, so a
    tone at amplitude A measures ~A regardless of where it sits between bins.
    """
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    if n < 64:
        return 0.0
    k = 2.0 * math.pi * f0 / fs
    cos_k = 2.0 * math.cos(k)
    s_prev = s_prev2 = 0.0
    for v in x:
        s = v + cos_k * s_prev - s_prev2
        s_prev2 = s_prev
        s_prev = s
    power = s_prev2 ** 2 + s_prev ** 2 - cos_k * s_prev * s_prev2
    return 2.0 * math.sqrt(max(power, 0.0)) / n  # sine of amp A -> ~A


def goertzel_track(x, fs, f0, hop_ms=10.0, win_ms=40.0):
    """Goertzel amplitude in consecutive windows -> (times_s, amps)."""
    hop = int(hop_ms * fs / 1000)
    win = int(win_ms * fs / 1000)
    times, amps = [], []
    for i in range(0, max(len(x) - win, 1), hop):
        amps.append(goertzel_amp(x[i:i + win], fs, f0))
        times.append((i + win / 2) / fs)
    return np.array(times), np.array(amps)


def band_envelope(x, fs, f_center, width_hz=None, smooth_ms=25.0):
    """Amplitude-vs-time of the band around f_center.

    FFT-mask + analytic signal: good enough for pure tones hundreds of Hz apart
    (which is what every fixture here uses), and numpy-only. The mask is a flat
    pass +-width_hz/2; smoothing is a moving average so ramp shapes survive.
    Returns (times_s, envelope) sampled at ~1/smooth_ms resolution.
    """
    x = np.asarray(x, dtype=np.float64)
    if width_hz is None:
        width_hz = max(f_center * 0.25, 60.0)
    full = np.fft.fft(x)
    ffull = np.fft.fftfreq(len(x), 1.0 / fs)
    keep_pos = np.abs(ffull - f_center) <= width_hz / 2
    analytic_spec = np.where(ffull >= 0, full * keep_pos, 0.0)
    ana = np.fft.ifft(analytic_spec * 2.0)
    env = np.abs(ana)
    w = max(int(smooth_ms * fs / 1000), 1)
    kernel = np.ones(w) / w
    env = np.convolve(env, kernel, mode='same')
    t = np.arange(len(env)) / fs
    step = max(w // 2, 1)
    return t[::step], env[::step]


def env_at(times, env, t_s):
    i = int(np.searchsorted(times, t_s))
    i = min(max(i, 0), len(env) - 1)
    return float(env[i])


def db(x):
    return 20.0 * math.log10(max(x, 1e-9))


def rms(x):
    return float(np.sqrt(np.mean(np.asarray(x) ** 2))) if len(x) else 0.0


def fit_inside(needle, hay, search_s=0.15, fs=FS):
    """Locate `needle` inside `hay` (+-search_s) and least-squares-fit its gain.
    Same machinery as export_audio_probe.py's offset mode; returns (lag, gain, ncc).
    A needle as long as (or longer than) the haystack is truncated to fit rather
    than treated as absent — absence must be measured, not asserted by geometry."""
    if len(hay) < 64 or len(needle) < 64:
        return 0, 0.0, 0.0
    needle = needle[:len(hay)].astype(np.float64)
    needle = needle - needle.mean()
    hay = hay.astype(np.float64) - hay.mean()
    if not len(needle):
        return 0, 0.0, 0.0
    search = int(search_s * fs)
    room = len(hay) - len(needle)
    lo = max(min(search, room // 2), 0)
    region = hay[lo:len(hay) - lo]
    if len(region) >= len(needle):
        corr = np.correlate(region, needle, mode='valid')
    else:
        corr = np.correlate(hay, needle[:len(hay)], mode='valid')
        needle = needle[:len(hay)]
        lo = 0
    lag = int(np.argmax(np.abs(corr))) + lo
    seg = hay[lag:lag + len(needle)]
    m = min(len(seg), len(needle))
    seg, pr = seg[:m], needle[:m]
    denom = float(np.dot(pr, pr))
    scale = float(np.dot(seg, pr) / denom) if denom else 0.0
    ncc = (float(np.dot(seg, pr) / np.sqrt(float(np.dot(seg, seg)) * denom))
           if denom and float(np.dot(seg, seg)) else 0.0)
    sign = 1.0 if scale >= 0 else -1.0
    return lag, scale * sign, ncc * sign


# ── compressor simulation (C6) ───────────────────────────────────────────────

def simulate_compressor(x, fs, threshold_db=-18.0, ratio=3.0,
                        attack_ms=20.0, release_ms=250.0, makeup_gain_db=0.0):
    """Bit-faithful port of CompressorProcessor.queueInput (float64 instead of
    float32/short rounding — differences are far below any tolerance used).

    Kept HERE, out of app code, and re-derived from CompressorProcessor.java so
    a drift between the two shows up as a C6 probe failure rather than hiding.
    """
    attack_coeff = math.exp(-1000.0 / (attack_ms * fs))
    release_coeff = math.exp(-1000.0 / (release_ms * fs))
    threshold_lin = 10.0 ** (threshold_db / 20.0)
    makeup = 10.0 ** (makeup_gain_db / 20.0)
    envelope = 0.0
    worst_reduction = 0.0
    out = np.empty_like(x)
    for i, xi in enumerate(x):
        ax = abs(xi)
        coeff = attack_coeff if ax > envelope else release_coeff
        envelope = envelope * coeff + ax * (1.0 - coeff)
        gain = 1.0
        if envelope > threshold_lin:
            excess_db = 20.0 * math.log10(envelope / threshold_lin)
            reduction_db = excess_db * (1.0 - 1.0 / ratio)
            gain = 10.0 ** (-reduction_db / 20.0)
            worst_reduction = max(worst_reduction, reduction_db)
        out[i] = xi * gain * makeup
    reported_gr = -worst_reduction if worst_reduction > 0.01 else 0.0
    return out, reported_gr


# ── selftest harness ─────────────────────────────────────────────────────────

def report_selftest(probe_name, cases):
    """cases: list of (label, expect_pass, checks_list).

    Every probe calls this from --selftest with its good case(s) expecting PASS
    and its deliberately-broken case(s) expecting FAIL. The suite passes only if
    EVERY expectation holds — including that each broken input really failed.
    """
    print(f'== {probe_name} selftest (negative controls) ==')
    ok = True
    for label, expect_pass, checks in cases:
        checks = [(c + ('',))[:3] for c in checks]
        got_pass = all(r for _, r, _ in checks)
        verdict_ok = (got_pass == expect_pass)
        ok &= verdict_ok
        print(f'  [{"OK" if verdict_ok else "TEETH-MISSING"}] {label}: '
              f'expected {"PASS" if expect_pass else "FAIL"}, '
              f'got {"PASS" if got_pass else "FAIL"}')
        if not verdict_ok or not expect_pass:
            for name, res, detail in checks:
                mark = 'pass' if res else 'FAIL'
                print(f'      {mark:<4} {name}' + (f'  ({detail})' if detail else ''))
    print(f'{"ALL TEETH CONFIRMED" if ok else "SELFTEST FAILED"} — {probe_name}')
    return ok
