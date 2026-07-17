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

## Research: how CapCut & mobile editors keep long files fast (2026-07-16)
Industry standard = **PROXY MEDIA**: the editor transcodes each imported source to a low-res proxy
(CapCut "Proxy Mode"; ~540/720p H.264) and ALL preview playback/scrubbing/thumbnails run against the
proxy; the original swaps back in only at export. Plus: lower preview resolution decoupled from
export, hardware-accelerated decode, and compound/grouped clips to keep the timeline light.
**Recommendation for FadCam:** extend the existing (now kill-safe) remux pipeline into a true proxy
pass — background-transcode fMP4 sources to a seekable ~540p proxy (ffmpeg-kit is already in-app);
MasterPlaybackEngine + filmstrip + everything preview-side resolves the proxy, ExportManager keeps
the original. This directly addresses: black/late previews on 1440×3088 sources, choppy scrubbing,
the 1.7GB memory blowup (3 concurrent decoders at 540p instead of QHD), and slow filmstrip sweeps
(decode the proxy, not the 2.4GB original). Sources:
- https://www.capcut.com/resource/pc-professional-video-editor
- https://moviemaker.minitool.com/news/capcut-lagging.html
- https://filmora.wondershare.com/advanced-video-editing/capcut-timeline.html

## Round-2 fixes landed (this session)
- sheen: fixed-width band (≤120dp) sweeping the VISIBLE window, 2.4s period (was segment-scaled flash)
- label pin: viewport-left is scrollOffsetPx in content space (canvas is scroll-translated) — now pins
- waveform disk cache: coverage-validated on READ (<95% span → delete + re-extract) — self-heals the
  pre-fix poisoned entries behind the "2/3 then flatline with no indicator"
- rank-1 capped clips re-toast every 5th failed attempt (was one toast ever → "play does nothing")

## Round 3 (2026-07-16, playback at 4-5fps — THE regression found)
Live inspection while slow: app CPU ~idle, app PSS ~717MB (reasonable), but phone at 10.3/10.6GB
with 2.3GB swap (background apps; not our leak this time). The real cause of 4-5fps:
- **🔥 FOUND: transcript words draw storm.** The 07-14 "words always visible" change removed the
  drawer-only gate on drawSegmentTranscript — a transcribed 45-min lecture = THOUSANDS of drawText
  calls PER FRAME during playback. This is why "it used to scrub a 55-min file fine": words used to
  draw only with a drawer open. FIX: viewport culling (time-ordered early-break past the right edge).
- Partial-tape fixes: incomplete analyses now EVICT from the memory cache after 60s (self-retries;
  was frozen at 70% all session) and the extractor stall-bail went 2s→15s so long decodes finish.
- Prior round-3 fixes: rank-1 cap → 45s cooldown + honest toast; background remux deferred 2min.
NEXT SESSION (fresh context, this doc is the brief): ① verify playback fps on the lecture project
after word-culling (expect near-back-to-normal); ② proxy-media architecture (research above) — JoyRaptor
notes it shouldn't be REQUIRED for one long file + 3 cuts, and she's right once the word storm is
gone; treat proxy as the robustness layer, not the fps fix; ③ ANR/memory (1.7GB trace pull);
④ minimap loading-progress meters (spec above); ⑤ visualizer crash repro on sandbox (current build
installed there); ⑥ preview double-tap → object menu for avatar/captions (still owed from 07-16 am).

## Autonomous block (2026-07-16 pm, sandbox)
- Visualizer crash (JoyRaptor 07-14): **NOT reproducible on current build** (long-press + toolbar tap both
  exercised on cebc19e0 with live logcat — no FATAL). Fixed incidentally by this week's work; the 07-14
  crash buffers are gone. CLOSING unless it recurs. ⚠️ Found instead: **long-press on a preview
  visualizer = INSTANT DELETE** (toast only, no confirm) and the Visualizer toolbar button = instant
  add. Per gesture-contract §4.5 hold should open the object menu (delete inside). Recommend routing
  long-press → object menu; JoyRaptor to confirm. A3a (SAF style round-trip) is UNBLOCKED — the drawer
  didn't crash; still needs the actual ⇩/⇧ file-picker test.

## Evening autonomous block — LANDED (all installed on REAL_SERIAL by 19:00)
- ✅ 2-min zoom-out (MIN_ZOOM 0.07) + word→thin-white-mark crossfade (gap scanning) + 2m/5m ruler tiers (7c21c37)
- ✅ trim-independent thumbnails — cuts/trims keep frames, no re-decode (579323d)
- ✅ transcript version-chip menu: copy plain / [mm:ss] / SRT + import SRT/VTT/[mm:ss] (2295d95, TranscriptIO)
- ✅ preview double-tap → object menu (sprite/avatar) and style-bar+keyframes drawer (captions) (2295d95)
- ✅ minimap loading meters (dark-unloaded + pulse + thin blue audio-progress bar) + "Opening project…"
  overlay until first STATE_READY (4075369)
- ✅ slides spec addendum: API-less copy-a-prompt path (in feature-ai-generated-slides-spec.md)

## Still open (updated 2026-07-17)
- ✅ Transcript SEARCH hits highlight on the words tape (amber bold words; taller amber
  marks at wide zoom; live with typing, clears with the search bar).
- ✅ Gap-detection LIVE PREVIEW: both silence dialogs (detect + auto-cut) now preview on
  slider release — yellow candidates paint on the tape, status line reports "N gaps — Xs
  would be trimmed" (generation-guarded against slow scans).
- ✅ Slides build — ALL phases + addendum shipped (see feature-ai-generated-slides-spec.md).
- ✅ Visualizer long-press → object menu (Customize style… / Delete visualizer) with
  haptic; instant-delete removed per gesture contract §4.5. (Second host at ~11072 keeps
  the legacy default-interface fallback.)
- ⏳ ANR trace pull (likely moot after the word-culling + thumbnail fixes — verify on next
  long open of the lecture project on the main phone).
- DEVICE-VERIFY owed on the three ✅ builds above (this session was build+compile only).
