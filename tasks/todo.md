# Export Lane Plan — C4 / C8 / E1

> **STATUS 2026-08-24: ALL THREE ROWS BUILT** (details in LANES.md LANE C + SPEC §7).
> 4eb07a01 unblock · c7eb026a C4/C8 engine+dialog · E1 probe via 5b89b76a.
> Evidence: TYPECHECK OK — 652 sources, 1806 classes; exact-command harness -21.8→-14.0 LUFS
> (+ unchanged no-loudnorm negative control); probe parity PASS / negative-FAIL.
> DEVICE VERIFY OWED (logcat "C4 LOUDNESS: before → after"). Checklists below kept for that run.

**Lane:** export only — `export/ExportManager.java`, `FaditorEditorActivity.java` (export dialog only), `tasks/export_audio_probe.py`, `faditor/audio/LoudnessAnalyzer.java` (NEW)
**Do NOT touch:** `layers/`, `tools/`, `model/` (except ExportSettings which is in model but is part of export — allowed? spec says model is forbidden, but ExportSettings is model — need clarification; treat as allowed for C4 target field, else use export-local storage)

## Context
- §0 Rule 9: paste literal harness last line, never summary
- C4: Loudness targets (YouTube -14, Podcast -16, TikTok -14, Broadcast -23, Off) with measured LUFS before/after via `ffmpeg -af ebur128` (ffmpeg-kit-full 6.0 LTS already bundled, verify filter args against actual API)
- C8: Wire Clean Audio checkbox (currently no-op, `ExportSettings.isCleanAudio()` persisted but never read in `ExportManager`/`ExportService`) to C4's chain — do NOT hide/remove per JoyRaptor ruling
- E1: Extend `export_audio_probe.py` to assert preview LUFS == export LUFS with negative control (A6 cautionary tale: wired but not running)

## Investigation Needed
- [ ] Verify `ebur128` filter syntax in ffmpeg-kit-full 6.0 LTS — check `ffmpeg -h filter=ebur128` or docs, confirm args `framelog`, `target`, etc. vs `loudnorm`'s `I`, `TP`, `LRA`
- [ ] Inspect current `ExportManager` export flow — where audio processors are chained, where `ExportSettings` is read, where `projectSampleRate` is resolved (A6 lesson: three call sites disagreed)
- [ ] Inspect `FaditorEditorActivity` export dialog at `11230` — cleanAudio checkbox, resolution/quality spinners, audioOnly
- [ ] Read `tasks/export_audio_probe.py` — current probe modes (`--overlap-with`, etc.), how it measures LUFS/corr, how to add preview vs export path
- [ ] Check `faditor/audio/BakedAudioCache.java` for ffmpeg invocation pattern (FFmpegKit, ReturnCode, parseMeasured)
- [ ] Check if `LoudnessAnalyzer.java` must use `FFmpegKit.execute` or `FFprobeKit` — verify against `BakedAudioCache` and ffmpeg-kit docs

## C4 — LoudnessAnalyzer + Export Sheet
- [ ] Create `faditor/audio/LoudnessAnalyzer.java`:
  - [ ] Method `measureLUFS(File inputFile, File projectDir)` that runs `ffmpeg -i input -filter:a ebur128=framelog=verbose -f null -` and parses `I:` integrated LUFS from stderr (ffmpeg-kit 6.0 LTS — verify `ebur128` outputs `I: -XX.X LUFS`, `LRA:`, `TP:`)
  - [ ] Verify filter args: `ebur128` takes `framelog`, `peak`, `dualmono`, NOT `I`/`TP`/`LRA` (those are `loudnorm`'s). Confirm via `ffmpeg -h filter=ebur128` in bundled build or desktop 7.0.2 reference, then note ffmpeg-kit's 6.0 LTS docs
  - [ ] Parse integrated loudness via regex `I:\s+([-\d.]+)\s+LUFS` from session logs (like `BakedAudioCache.parseMeasured` does for loudnorm JSON)
  - [ ] Return `Double` or custom result with `integratedLUFS`, `truePeak`, `lra` — but spec says measured LUFS before and after, so at least integrated
  - [ ] Handle silence/inf case (like `BakedAudioCache` does for `-?inf`)
  - [ ] Run off main thread, caller supplies `projectDir` (no `getCacheDir`)
- [ ] Add to `ExportSettings.java` (if allowed despite `model/` ban — else store in `ExportManager` local): `LoudnessTarget` enum with `YOUTUBE(-14)`, `PODCAST(-16)`, `TIKTOK(-14)`, `BROADCAST(-23)`, `OFF(null)` and getter/setter, persisted via `ProjectStorage` like `cleanAudio`
  - [ ] If `model/` is truly forbidden, store target in `ExportManager` or `FaditorEditorActivity` dialog state and pass to `ExportManager` — but spec says files are `FaditorEditorActivity (export dialog), ExportManager, new LoudnessAnalyzer` — so target likely lives in `ExportSettings` which is `model/` — need to confirm with lane owner or treat as exception
- [ ] In `FaditorEditorActivity` export dialog (`11230` area):
  - [ ] Add loudness target spinner/dropdown (YouTube -14, Podcast -16, TikTok -14, Broadcast -23, Off) — persists to `ExportSettings`
  - [ ] Add LUFS readouts: "Measured: -XX.X LUFS → Target: -14 LUFS" before export, and after export show "Before: -XX.X → After: -XX.X LUFS" (requires running `LoudnessAnalyzer` on the source mix before export and on the exported file after)
  - [ ] Clean Audio checkbox stays visible (C8) — when ticked, it should select the `loudnorm` chain with target LUFS from C4, not a fixed -16
- [ ] In `ExportManager`:
  - [ ] Before building composition, run `LoudnessAnalyzer` on the composed audio mix (or on each source?) to get "before" LUFS — but spec says "measured LUFS before and after" — before is the preview mix's LUFS, after is the exported file's LUFS
  - [ ] After export completes, run `LoudnessAnalyzer` on the output file and log/return it
  - [ ] If loudness target != OFF, inject `loudnorm` filter via ffmpeg or via `VolumeAudioProcessor`? But spec says use `ebur128` for *measurement* (analysis), not for correction. Correction likely still via `loudnorm` two-pass (as in `BakedAudioCache`). So C4's chain is: measure with `ebur128`, then apply `loudnorm` with `I=target`, `TP=-1`, `LRA=...` as in `BakedAudioCache`
  - [ ] Ensure project sample rate is unified (A6 lesson: three call sites disagreed) — use single `resolveProjectSampleRate` and pass it to all resamplers

## C8 — Wire Clean Audio
- [ ] In `ExportManager.buildComposition` / `buildAudioOnlyComposition` / `buildOverlayAudioSequence`: if `ExportSettings.isCleanAudio()` is true, route audio through C4's `loudnorm` chain (or `BakedAudioCache`'s `CHAIN_FIX`?) — spec says "It belongs to C4's chain" — so Clean Audio should trigger the same chain as loudness target, not a separate fixed chain
- [ ] Verify by grepping that `isCleanAudio()` is now read in `ExportManager` (previously 0 consumers) — negative control: when checkbox is OFF, chain is NOT added; when ON, chain IS added (log "Clean Audio: ..." + ffmpeg command)
- [ ] Do NOT hide the checkbox, do NOT change its label — just make it do something

## E1 — Probe Preview == Export
- [ ] In `tasks/export_audio_probe.py`:
  - [ ] Add mode `--assert-preview-export-parity` that takes `--preview-lufs` and `--export-lufs` (or takes two files and runs `LoudnessAnalyzer`/`ebur128` on both)
  - [ ] Assert `abs(previewLUFS - exportLUFS) < 1.0` (or tighter, like 0.5) — preview and export must match within tolerance
  - [ ] Negative control: run probe on two files with deliberately different LUFS (e.g., -14 vs -23) and assert it FAILS — proves the check would catch a no-op where preview and export diverge
  - [ ] Follow existing probe pattern: `argparse`, `9/9 PASS` style, `TYPECHECK` not needed but `ALL PASS` vs `FAIL`

## Verification (Rule 9 — literal last lines)
- [ ] `bash tools/jvm-harness/typecheck.sh` → paste literal `TYPECHECK OK — N sources, M classes` or `TYPECHECK FAILED`
- [ ] `bash tools/jvm-harness/run-resample.sh` → literal `ALL GREEN` (A6 still PENDING but harness exists)
- [ ] `bash tools/jvm-harness/run-audio-fx.sh` → literal `ALL PASS` (C1.E still PENDING but harness exists)
- [ ] For C4: `bash -c "ffmpeg -h filter=ebur128 2>&1 | head -n 20"` or desktop `ffmpeg -f lavfi -i anullsrc -filter:a ebur128 -f null - 2>&1 | grep -E 'I:|LRA:|TP:'` to verify filter args before using
- [ ] For C4/C8: run `LoudnessAnalyzer` on a known sine file, show measured LUFS, then export with target -14 and show after LUFS ≈ target — negative control: with Clean Audio OFF, after LUFS stays at before value, not target
- [ ] For E1: `python tasks/export_audio_probe.py --preview-lufs -14 --export-lufs -14` → `ALL PASS`, and `--preview-lufs -14 --export-lufs -23` → `FAIL` (negative control)
- [ ] Commit with `git add` explicit paths (no `-A`) while lane ACTIVE

## Risks
- ffmpeg-kit-full 6.0 LTS `ebur128` syntax may differ from desktop 7.0.2 — must verify against bundled docs or `ffmpeg -h filter=ebur128` from the kit, not just plausibility
- `model/` is forbidden but `ExportSettings` is in `model/` — need to confirm if C4 target field is allowed or must live elsewhere
- A6 lesson: ensure `projectSampleRate` is resolved once and passed consistently to all `ResamplingAudioProcessor` instances in `ExportManager` (currently three call sites: master, audio lanes, PiP)

## Agent 2 — file-provable probes for BUILT audio rows (2026-08-24)

Convert B3/B4/C6/C7/C5.E from 'needs ears' to 'needs an exported file':
- [ ] tasks/audio_probe_lib.py — shared DSP (Goertzel, band envelope, wav/aac IO, selftest harness)
- [ ] tasks/probe_solo_b3.py   — solo lane A => lane B tone ABSENT from export (band measurement)
- [ ] tasks/probe_meters_b4.py — reported meter dB == measured RMS ratio at same playhead
- [ ] tasks/probe_gr_c6.py     — measured reduction (on/off exports) matches compressor maths + reported bar value
- [ ] tasks/probe_bypass_c7.py — fx vs bypassed differ measurably; bypassed == plain re-encode
- [ ] tasks/probe_duck_c5e.py  — music-band dip matches keyframe-predicted curve (depth/ramp/recovery)
- [ ] export/make_fixtures.py  — device test media (tones, stepped levels, duck pair)
- [ ] export/run_negctl_suite.py — runs every probe --selftest; every probe must FAIL on broken input
Rule: no probe is trusted until its negative control has been RUN and FAILED.

### REVIEW (agent 2)
All five probes built and PROVEN against deliberately broken inputs this session:
- B3 solo  : mix(A+B)->FAIL / A-only->PASS / wrong-fA->FAIL
- B4 meters: honest readouts->PASS / one readout +6dB lie->FAIL / silence window->FAIL
- C6 GR    : real compression+bar agrees->PASS / ON==OFF no-op->FAIL / bar lies 0dB->FAIL / wrong ratio->FAIL
             (python port of CompressorProcessor maths matches measurement to 0.17 dB on fixture)
- C7 bypass: fx+bypass differ 0.569 rel rms; bypass fits source at gain 1.000 corr 1.000;
             fx==bypass->FAIL / secretly-processed 'bypass'->FAIL (fit gain 0.431 caught)
- C5.E duck: keyed curve matched x0.250 measured vs x0.250 keyed, ramp midpoint x0.600 vs x0.625;
             never-ducked->FAIL / shallow x0.8->FAIL / wrong voice window->FAIL
python export/run_negctl_suite.py -> SUITE PASS, exit 0.
Device fixtures generated to export/fixtures/ via export/make_fixtures.py.
NOT touched: layers/, tools/, faditor/audio/fx/, faditor/compositor (read-only reference).
