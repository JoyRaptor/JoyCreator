# STATE — what Joy Creator actually has, and how proven it is

**Purpose:** stop the rediscovery tax. This is the one file that says what exists.
Every agent reads it first. Every promotional claim comes from it. The help doc and
Joybot's knowledge are generated from it.

**Last full pass:** 2026-09-09 (Claude/Opus, launch-planning session)
**Scale:** 710 Java files, ~349,000 lines, schema v13

---

## The proof levels — this is the point of the document

| | Means | Consequence |
|---|---|---|
| 🟢 **LIVE** | JoyRaptor has used it on a phone and it worked | Safe to ship. Safe to promote. Goes in the help doc. |
| 🟡 **BUILT** | Code complete, compiles, never driven on a device | **This is where 1-star reviews come from.** Do not promote. Consider hiding at launch. |
| 🔵 **SPECCED** | Written down, not built | Roadmap fuel |
| ⚪ **IDEA** | Mentioned somewhere, never specced | INBOX.md fuel |
| 🔴 **BROKEN** | Known defect, diagnosed | Must be triaged before launch |
| 🟠 **ORPHAN** | Started, stopped for lack of hours, still wanted | **Recover it. Do not archive it.** |

**The rule that keeps this true: an entry moves to 🟢 when it is proved on a phone, not
when the code lands.** That single discipline is what this file is for.

**"Is it finished" and "is it proven" are different questions.** Do not conflate them.
JoyRaptor, 2026-09-09: *"almost anything partially built is likely part of the finished vision
that I haven't had time to get around to yet."* He works in fragments around childcare, so
work stops for lack of hours, not lack of intent. A 🟠 entry is an orphan waiting to be
finished — never dead weight to be filed away. A whole feature (masking) was already lost
this way once. Push back on an idea only with a substantive reason it is wrong, never
because it is incomplete.

**Provenance:** entries marked ✔ were verified against the code in this session. Entries
marked ~ are inherited from LEDGER.md / road_map.md claims and are only as good as those.
Anything unmarked is a reading that should be challenged.

---

## 1. RECORDING (the FadCam inheritance)

| Feature | Level | Notes |
|---|---|---|
| Background video recording (screen off) | 🟢 ~ | The original FadCam core |
| Screen recording (FadRec) | 🟢 ~ | fadrec/ — own encoding pipeline |
| **Webcam overlay while screen recording** | 🟢 ~ | ✔ FloatingWebcamService — **Joy Creator exclusive, FadCam has no such thing.** Headline feature. |
| Auto-hiding recorder controls | 🟢 ~ | JoyRaptor's work. Less visually invasive than FadCam. |
| Dual camera (front + back) | 🟡 ✔ | dualcam/ full package. Device state unknown. |
| Photo capture / screenshot | 🟢 ~ | PhotoCaptureActivity, ScreenShotCaptureActivity |
| Remote camera control over LAN | 🟡 ✔ | streaming/RemoteStreamManager, LiveM3U8Server. **Real, unpromoted, untested by JoyRaptor.** |
| Live streaming / cloud upload | 🟡 ✔ | CloudStreamUploader, CloudAuthManager — **being removed from the Play build** |
| Watermark | 🟢 ~ | watermark/WatermarkManager |
| Live audio visualizer during record | 🟡 ✔ | visualizer/LiveVisualizer |
| Forensics gallery | 🟡 ✔ | forensics/ — full package, purpose and state unclear, needs a decision |
| Motion detection | 🟡 ✔ | motion/ package |
| Clock widget | 🟢 ~ | widgets/ |
| MiniApps (QR scanner etc.) | 🟡 ~ | Recorder gadgets. Different product surface — do not overload. |

### 🚨 Recording features that must leave the Play build

| Feature | Why |
|---|---|
| 30 disguise icons (Calculator, Notes, Weather, `$ ~/r00t`…) | ✔ Stalkerware signature on a screen recorder |
| Accessibility service used for screenshots | ✔ Non-accessibility use of the Accessibility API = policy violation |
| MANAGE_EXTERNAL_STORAGE | ✔ Requires justification; usually refused for editors |
| Privacy black screen | Per LAUNCH_STRATEGY.md section 3 |
| Cloud / streaming | Per LAUNCH_STRATEGY.md section 5 |

All of these **stay in the website / F-Droid build.** Decision already made and documented.

---

## 2. THE EDITOR — core timeline

| Feature | Level | Notes |
|---|---|---|
| Multi-clip timeline, split/trim/reorder | 🟢 ~ | The spine |
| Multi-track layers | 🟢 ~ | road_map "Phase 5 KEYSTONE" — reported complete |
| Keyframes + easing | 🟢 ~ | keyframe/, EasePickerPopover |
| Masks | 🟢 ~ | Schema v13. **The engine shipped before the UI did — that gap is why LEDGER.md exists.** |
| Chroma key / green screen | 🟢 ~ | LEDGER 3a-KEY: preview and export both proved on the Note 9, 2026-08-05 |
| Luma key | 🔵 ~ | LEDGER: "spec complete, the work is not" |
| GL transitions (35+) | 🟢 ~ | gltransitions/ |
| **External .glsl transitions from a dropped-in folder** | 🟢 ✔ | GlExternalTransitions.scanAndRegister — **this is already a working pack loader.** |
| Speed / zoom / crop / flip | 🟢 ~ | |
| Text overlays + animation | 🟢 ~ | SPEC_TEXT_ANIMATION; odometer preset proved character-by-character 2026-07-31 |
| Adjustment layers / FX | 🔵 ~ | SPEC_ADJUSTMENT_LAYERS_FX.md — 742 lines of spec |
| Mesh warp | 🟡 ✔ | transform/mesh/MeshWarpSpec + SPEC_20260902_MESH_WARP (780 lines). **In progress.** |
| Undo (one press = one step) | 🟢 ~ | JoyRaptor's ruling. Batch operations collapse to one undo. |
| Export (out-of-process) | 🟢 ~ | Own process so an editor crash cannot kill an export. Proved 2026-07-30. |
| Single-frame export (JPG/PNG) | 🟡 ~ | todo.md is this spec, marked partly done |

---

## 3. AUDIO

| Feature | Level | Notes |
|---|---|---|
| Waveforms (tile cache + mip pyramid) | 🟢 ~ | Device-proven on SM-N986U |
| Beat and onset detection | 🟢 ✔ | waveform/BeatDetector, OnsetDetector — pure DSP, offline, **already wired to snapping** |
| Silence detection | 🟢 ✔ | SilenceDetector — pure DSP |
| Loudness normalisation (EBU R128) | 🟢 ~ | |
| fix_audio chain (highpass → denoise → compress → loudnorm) | 🟢 ✔ | Works. **Not exposed as a button — only reachable by asking the chatbot.** |
| Voiceover recording | 🟢 ~ | audio/VoiceoverRecorder |
| Audio ducking | 🔵 | Removed pending a mixer that can honour it (set_clip_duck removal comment in code ✔) |
| Visualizer studio | 🔵 ~ | feature-visualizer-studio-spec.md, Tier 3 planned |

---

## 4. TRANSCRIPTION — fully offline, no key, no upload

| Feature | Level | Notes |
|---|---|---|
| Vosk small (~40MB) — best timing | 🟢 ~ | ✔ engine present |
| Vosk large (~128MB) | 🟢 ~ | |
| whisper.cpp base.en (~57MB) — best wording | 🟢 ~ | ✔ native build, arm64 only |
| LCS merge of Vosk timing + Whisper words | 🟢 ✔ | TranscriptSynthesizer — genuinely novel |
| Filler-word detection and strike | 🟢 ✔ | |
| Word-level retiming and scrubbing | 🟢 ~ | WordScrubView |
| Captions with 5 animated styles | 🟢 ~ | pop / zoom / bounce / boxed / hot |

**This is the strongest competitive asset in the app and it needs no internet, no key, and
no money.** Nothing else on Android does the two-engine merge.

---

## 5. ANIMATION — sprites, avatars, puppets

| Feature | Level | Notes |
|---|---|---|
| Sprite sheets + grid detection | 🟢 ~ | sprite/; road_map says S1–S7 complete |
| Sprite sidecar .sprite.json | 🟢 ✔ | Code comment: *"the sharing format, the plugin contract"* |
| Dope sheet | 🟢 ~ | |
| Avatar rigs (biped, head yaw/pitch, limbs) | 🟡 ~ | road_map claims the full loop is done; **JoyRaptor has not driven it** |
| MediaPipe face tracking | 🟡 ✔ | face_landmarker.task in assets, MediaPipeTrackingSource |
| Visemes from audio (spectral, 6-class) | 🟡 ✔ | SpectralVisemeAnalyzer, AudioLevelViseme |
| IK solver, dangle physics, motion smoothing | 🟡 ✔ | FabrikSolver, DangleSim, OneEuroFilter |
| Bake rig to keyframes | 🟢 ~ | LEDGER: device-verified |
| Point-at-video | 🟢 ~ | |
| Puppet architecture | 🟡 ✔ | SPEC_20260904_PUPPET_ARCHITECTURE — recent, in flight |

> ⚠️ **Avatar Studio is the largest 🟡 block in the app.** Substantial, sophisticated, and
> never exercised by a human. Recommendation: **present in the launch build, not reachable
> from the UI.** Ship what has been driven.

---

## 6. AI — the agent

| Feature | Level | Notes |
|---|---|---|
| Chat assistant inside the editor | 🟢 ✔ | ChatAssistantActivity (1,844 lines) |
| **55 tools that mutate a real timeline** | 🟢 ✔ | Counted in executeTool(). No mobile competitor has this. |
| EditScript contract (23 op types) | 🟢 ✔ | Validate-all-then-apply, one undo step, unknown ops rejected |
| Proposal cards (confirm before applying) | 🟢 ~ | narrative / b-roll / avatar rig |
| AI-generated slides (GSAP, deterministic) | 🟢 ~ | Device-proven 2026-07-16, 87 frames to MP4 |
| Copy-prompt then paste-back for slides | 🟢 ~ | **The precedent for share-in.** Already works. |
| Vision (send a frame to the model) | 🟢 ~ | |
| B-roll tagging and suggestion | 🟢 / 🔴 ~ | Works on video-track projects; **fails on audio-track projects** — see section 9 |
| Background AI jobs that survive app close | 🟢 ~ | AIJobService — the pattern video generation will reuse |
| Headless apply over adb | 🟢 ~ | ApplyEditsActivity — dev tool |
| Image generation | 🔵 | Endpoint verified live: POST openrouter.ai/api/v1/images |
| Video generation | 🔵 | First post-launch feature |
| Share-in from other apps | 🔵 | **The headline.** Needs an intent filter plus the existing import path. |
| Multi-provider support | 🔵 | ai_provider pref ✔ **declared and never used** |
| Streaming replies | 🔵 | Currently a frozen "Thinking…" |
| Joybot sprite and states | ⚪ | INBOX.md — needs its own session |

---

## 7. PROJECTS, ASSETS, STORAGE

| Feature | Level | Notes |
|---|---|---|
| Project = folder + project.json | 🟢 ✔ | schema v13 |
| Atomic save (.tmp then rename, .bak kept) | 🟢 ✔ | |
| Refuses to overwrite a newer-schema project | 🟢 ✔ | Good discipline, already there |
| **Consolidate** — copy every asset into the project | 🟢 ~ | Dedup by content hash, copy-never-move, cancellable |
| project:// relative addressing | 🟢 ✔ | AssetResolver. One scheme, deliberately. |
| Fonts consolidated into the project | 🟢 ~ | They used to die on uninstall |
| Zip export / import of a project | 🟢 ~ | |
| Relink by content hash and filename | 🟢 ~ | One relink resolves a whole folder |
| Asset browser (scans a chosen folder) | 🟢 ~ | assetbrowser/AssetScanner |
| B-roll bucket + .broll_tags.json | 🟢 ✔ | Lives in Pictures/FadCam/assets — **outside the project** |
| **THE VAULT** | 🔵 | ✔ **Not built. Projects live in getFilesDir() — wiped on uninstall.** |

### 🚨 The storage finding

✔ ProjectStorage line 84: `projectsRoot = new File(context.getFilesDir(), "faditor/projects")`

That is app-private internal storage. Projects are invisible to the user, invisible to a
PC, impossible to put on an SD card, and **deleted without warning on uninstall.**
On 2026-08-29 an acceptance test did exactly that and destroyed 23 of JoyRaptor's 24 projects.

Everything downstream (consolidate, project://, zip, relink) is already correct and does
not care where the root lives. **The vault is a small change that must happen before
strangers install.**

---

## 8. ONBOARDING AND IDENTITY

| Feature | Level | Notes |
|---|---|---|
| App name is "Joy Creator" | 🟢 ✔ | strings.xml line 2 |
| Only 16 of 2,820 strings say "FadCam" | ✔ | Branding surface is small |
| Intro with animated fading lines | 🟢 ✔ | RowFadeAnimator — **the cycling-words mechanic already exists** |
| Language picker | 🟢 ✔ | |
| Permission screen (all up front) | 🟢 ✔ | Recommend deferring to point of use |
| "Verify you are a human" + privacy link | 🟢 ✔ | **Copy is written for a covert recorder. Must be rewritten for Play.** |
| New intro copy | 🔵 | Drafted 2026-09-09, approved by JoyRaptor |
| Vault picker screen | 🔵 | |
| Demo project | ⚪ | INBOX.md |
| New landing screen / studio funnel | ⚪ | INBOX.md — its own session |

---

## 9. 🔴 KNOWN BROKEN — triage before launch

| # | Defect | Severity | Source |
|---|---|---|---|
| 1 | **Audio clip IDs regenerate on every load and save.** AudioClip has no setId, so the id on disk is write-only. Anything outliving a session that names an audio clip is unreliable. Fix shape fully diagnosed. | High — data integrity, undermines portability | LEDGER 2c |
| 2 | Three AI tools resolve only against the video track — they fail on projects where the transcript sits on an audio clip (music / lyric projects). The one-path resolver already exists; three functions need to use it. | Medium | SPEC_20260828_AI_AUDIO_TOOLS |
| 3 | Default AI model is openrouter/auto — the **paid** router. Every new user is billed on message one. | High — first impression | ✔ 4 code sites |
| 4 | AI API key stored in plaintext preferences while unused Keystore machinery exists | Medium — security | ✔ |
| 5 | AI tool-calling loop has no depth cap | Medium — can hang | Reports, plausible |
| 6 | AI reply limit of 1024 tokens truncates edit instructions | Low, trivial fix | Reports |
| 7 | ai_merge_transcript has "ai" in the name and uses no AI | Cosmetic honesty | ✔ |
| 8 | remove_silence is report-only for audio clips | Low | |
| 9 | Stranded drag-latch, second path — self-heals, fires occasionally | Low, watch only | LEDGER 2b |
| 10 | No automated tests at all — app/src/test does not exist | Structural risk | ✔ |

---

## 10. WHAT DOES NOT EXIST (so nobody re-discovers the absence)

Text-to-image. Text-to-video. Text-to-speech or voice cloning. Voice enhancement.
On-device LLM. Hosted AI credits. Template gallery. Pack manager UI. Community pack index.
Music generation. A website. A Play listing. A privacy policy that mentions AI.
**A backup of this repository anywhere but JoyRaptor's PC.**

---

## 11. HOW TO MAINTAIN THIS

1. When something is proved on a phone, move it to 🟢 and say what proved it.
2. When a spec is written, add a 🔵 line. Do not write the spec until it is scheduled.
3. When an idea appears it goes in INBOX.md — **not** into a new SPEC_*.md.
4. When a defect is found, add it to section 9 with its severity.
5. LEDGER.md keeps the story. This file keeps the score. Both stay.
