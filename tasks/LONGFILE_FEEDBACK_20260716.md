# Long-file (45-min real project) feedback — JoyRaptor 2026-07-16, post remux-fix walkthrough

Context: after the remux-cache poisoning fix (bb723c7), "first lecture on phone" loads and plays.
Performance/transitions "doing good". These are the remaining gaps found on a REAL long file, ranked.
JoyRaptor: "work autonomously on getting the performance dialed in… work out all the broken things."

## P0 — correctness / perf
1. **ANR + long blank screen on project open.** Initial load of the 45-min project blanks for minutes;
   Android shows "FadCam beta isn't responding — close app or wait". It DID eventually load. Two parts:
   (a) find+fix whatever blocks the main thread >5s on open (the ANR is a real main-thread stall, not
   cosmetic); (b) an honest loading indicator ("pulsing… informative… keeps the user's hopes up") for
   the legitimately-long parts. "Most people would think the app crashed."
2. **Filmstrip caps at EXACTLY 30 frames.** JoyRaptor counted: 30 preview frames on the master tape, then
   blank; after a minimap tap the blank refills as the LAST frame stretched over the whole remaining
   duration. Long clips need duration-scaled / windowed extraction, not a fixed 30.
3. **Waveform flatlines on long clips.** Clip-audio drawer on the long clip: audio only half-loaded on
   the first third, then all 4 band lines flatline at their last data point ("stripes") for the rest.
   Render must not extrapolate; and extraction must actually cover the full trimmed range.

## P1 — peace-of-mind UX (analysis pipeline)
4. **Background-analyze audio as soon as the project loads.** JoyRaptor has DECIDED (was on the fence at
   AV4): "we should definitely have it load in the background now" — she had the project open idle for
   a while, then double-tapped and got an all-blank drawer + a 2-3 minute wait. Analysis should start
   at project load so it's ready by the time anyone opens the drawer. (Relates to the AV4 analyzeEager
   pref — the drawer's tape analysis should not wait for first-open regardless of that setting.)
5. **"analyzing audio…" label must pin to the left edge** (same dynamic as the trash-can badge) so it's
   visible wherever the viewport is over the tape — JoyRaptor opened the drawer mid-project and never saw
   the label sitting at the tape's far-left start.
6. **Loading sheen/pulse on the analyzing tape** — "really slick, yet a little bit subtle" animation on
   the un-analyzed tape region so users see analysis in progress (text + animation = two signals).

## P2 — small UX
7. **Double-tap on the AUDIO band → open the waveform customization settings** (the AV4
   WaveformVisualizerSettingsSheet). Currently double-tap there does nothing. JoyRaptor: good spot because
   you can see the band update live while editing settings (crossovers, smoothing, spark dots).
8. **Spark dots ½–⅓ current size** — the white spark dots are "quite large relative to the tape…
   a little bit obnoxious."

## Status (updated 2026-07-16, commit c579b58 — BUILT + INSTALLED on REAL_SERIAL)
- ✅ #2 filmstrip: proportional tile→thumb mapping + cap 30→60. No more blank/stretched tail.
- ✅ #3 stripes: incomplete extractions displayed but never cached; renderer silences past real data.
- ✅ #4 background analysis: kicks at setTimeline for every master clip, FULL-source spans + new
  superset-reuse in the cache (one job per file serves every trim window forever).
- ✅ #5 pinned "analyzing audio…" label (viewport-left, trash-can style) + pulse.
- ✅ #6 sheen sweep over the analyzing tape (self-stopping throttled repaint).
- ✅ #7 audio-band double-tap → waveform customization sheet (onAudioBandDoubleTapped).
- ✅ #8 spark dots 1.6→0.7dp.
- ➕ bonus: saved projects on fragmented sources now kick a background remux (kill-safe) — raw file
  this session, seekable copy from the next open (fixes the never-remuxed saved-project seeking).
- ⏳ #1 ANR: EVIDENCE CAPTURED, root-cause owed. ApplicationExitInfo for the 09:29 ANR shows the
  process at **1.7GB PSS / 1.8GB RSS** — main-thread stall almost certainly memory-pressure GC
  thrash. OS trace saved at /data/system/procexitstore/anr_2026-07-16-09-29-03-350.gz (app can read
  it via ActivityManager.getHistoricalProcessExitReasons().getTraceInputStream() — root not needed).
  FilmstripSweepExtractor audited: NOT the leak (one full-res frame at a time, recycled). NEXT
  SESSION: pull the trace via a small debug hook or Debug.dumpHprofData on open of the lecture
  project; suspects = codec surface/graphics heaps ×3 concurrent decoders (playback + sweep + band
  extraction) on 1440×3088 sources, or transcript/caption view allocation for ~7k words. Note the
  loading-overlay half of #1 (honest progress UI on open) is also still owed.
- DEVICE-VERIFY owed on all of the above (JoyRaptor re-test on the lecture project + a fresh drawer open).

## Round 2 (JoyRaptor re-test, 2026-07-16 ~10:4x)
- ✅ pulsing label: good.
- 🔧 sheen reads as a homogeneous FLASH: the band width scales with SEGMENT width (18%), so on a
  45-min clip it's enormous and crosses the viewport in a blink. Fix: clamp band to viewport-scale
  (~120dp), sweep the VISIBLE region, slow the period.
- 🔧 label does NOT pin (coordinate space bug — seg.left is content-space under scroll translate?).
- 🔥 tape shows ~2/3 analyzed then flatlines WITH NO indicator and the label vanishes: OLD poisoned
  disk-cache entries (written before c579b58 as "complete") still load as complete → displayed,
  no re-extract, no analyzing state. Fix: coverage-validate disk cache on READ (span <95% covered →
  discard + re-extract), mirroring hasLeadingMoov self-heal.
- 🔥 clip 3 (tail of the lecture): filmstrip eventually appeared but PREVIEW WINDOW BLACK + play does
  nothing. Suspect chain: session-sticky rank-1 cap (3 unpoisoned failures → permanent silent stop,
  only one toast ever) + IO contention from the new 2.4GB background remux at open. Consider: retry
  allowance after a cooldown, re-toast on replay attempt into a capped clip, defer background remux
  until playback idles.
- 🆕 MINIMAP LOADING-PROGRESS spec (JoyRaptor design): minimap bars double as per-clip load meters —
  unloaded region = darker shade of the bar's color (dark gray unselected / dark green selected),
  loaded region = full color, with a subtle diagonal-lines animation on the loading boundary; plus a
  temporary THIN BLUE BAR along the bar bottom showing AUDIO analysis progress for that clip. Point:
  "running slow because it's doing stuff, not because it's broken."
- 📚 Research request: what do CapCut/mobile editors do for long-file previews (proxy media?
  windowed thumbs?) — hyper-optimizations common in film software + mobile.
