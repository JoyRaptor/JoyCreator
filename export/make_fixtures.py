"""Device test media for the file-provable audio probes (B3/B4/C6/C7/C5.E).

Generates the SOURCE clips to place on lanes before exporting; each fixture is
shaped so its row's probe can read the answer off the exported file alone —
distinguishable tones for band measurements, level plateaus for meter checks,
a steady tone for compressor maths, separated music/voice tones for the duck
curve. Run this once, adb-push or copy the files onto the device, build the
projects per the mapping below, export, then run the matching probe.

Usage:
    python export/make_fixtures.py [OUT_DIR]      (default: export/fixtures)

Fixture -> spec row -> proof command:

  solo_lane_a_tone.m4a / solo_lane_b_tone.m4a   B3
      Two lanes, A=440 Hz and B=1500 Hz, both audible for the whole project.
      Export twice: once plain (operator negctl - the B3 probe MUST FAIL on it),
      once with solo engaged on lane A.
        python tasks/probe_solo_b3.py SOLO_EXPORT.m4a

  meter_steps.m4a                               B4
      Three 2 s plateaus at 0 / -6 / -12 dBFS-relative content. Note the playhead
      times and what the track/master meter shows at each; put the clip at
      timeline 0 so source time == timeline time.
        python tasks/probe_meters_b4.py EXPORT.m4a meter_steps.m4a \
            --at 1000:<meter dB> --at 3000:<meter dB> --at 5000:<meter dB>

  gr_tone.m4a                                   C6 (+ C7 EXPORT_FX side)
      Steady 1 kHz tone at ~-6 dBFS, well above the default -18 dB threshold.
      Export with the voice chain live (EXPORT_ON) and bypassed via the C7
      toggle (EXPORT_OFF); read the GR bar mid-playback for --reported-gr-db.
        python tasks/probe_gr_c6.py ON.m4a OFF.m4a gr_tone.m4a \
            --reported-gr-db <bar value>
        python tasks/probe_bypass_c7.py ON.m4a OFF.m4a gr_tone.m4a

  duck_music.m4a / duck_voice.m4a               C5.E
      Music = continuous 330 Hz tone (the target lane); voice = 1200 Hz tone
      bursts between 3 s and 9 s on the timeline (the key lane). Export with the
      generated duck keyframes applied; note Ducker.Params used.
        python tasks/probe_duck_c5e.py DUCKED_EXPORT.m4a \
            --voice-start-ms 3000 --voice-end-ms 9000 \
            [--duck-mult .. --attack-ms .. --release-ms .. --hold-ms ..]
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                '..', 'tasks'))
import audio_probe_lib as lib


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), 'fixtures')
    os.makedirs(out, exist_ok=True)

    def emit(name, sig):
        wav = os.path.join(out, name + '.wav')
        m4a = os.path.join(out, name + '.m4a')
        lib.write_wav(wav, sig)
        lib.encode_aac(wav, m4a)
        print(f'{name + ".m4a":<24} {len(sig) / lib.FS:5.1f}s')

    # B3: two distinguishable carrier tones
    emit('solo_lane_a_tone', lib.tone(8.0, 440.0, 0.4))
    emit('solo_lane_b_tone', lib.tone(8.0, 1500.0, 0.4))

    # B4: three content plateaus (content amp differs; program gain is what the
    # meter claims and the probe measures as export/source rms ratio)
    emit('meter_steps', np.concatenate([
        lib.tone(2.0, 800.0, 0.5),
        lib.tone(2.0, 800.0, 0.25),
        lib.tone(2.0, 800.0, 0.125)]))

    # C6 / C7: steady tone above the default compressor threshold
    emit('gr_tone', lib.tone(8.0, 1000.0, 0.5))

    # C5.E: music (target) + voice (key) on separate lanes, same timeline length
    music = lib.tone(12.0, 330.0, 0.4)
    voice = np.concatenate([lib.silence(3.0),
                            lib.tone(6.0, 1200.0, 0.3),
                            lib.silence(3.0)])
    emit('duck_music', music)
    emit('duck_voice', voice)

    print(f'\nfixtures in: {out}')


if __name__ == '__main__':
    main()
