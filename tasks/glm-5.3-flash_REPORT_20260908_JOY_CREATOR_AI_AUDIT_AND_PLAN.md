# Joy Creator — AI Audit, Competitive Analysis, Market Assessment & Path to #1 (v2)

**Model:** `glm-5.3-flash` (`opencode-go/glm-5.3-flash`) · **Date:** 2026-09-08 · **Status:** v2 — supersedes my v1 (this file; v1 deleted on rename per owner instruction)
**Sibling reports on the same brief (not modified by me):** `opencode-muse-spark-1.3-contributor-free_JOY_CREATOR_AI_DOMINANCE_REPORT.md` (v2.1), `REPORT_20260908_AI_INTEGRATION_AND_BYOK.md` (`opencode-go/grok-4.6`). Where my numbers converge with theirs, I say so — cross-report agreement is evidence.
**Inputs:** full-repo code audit, all `tasks/*.md` specs, live web research (Sept 2026), plus a re-measurement pass against the rival's error report.

---

## 0. Adversarial self-audit of my v1 (read this first)

I re-measured my own v1 against source after the sibling reports flagged discrepancies. Method: `Get-Content | Measure-Object -Line` and counted dispatch cases in the `executeTool()` switch range only. Fixes applied below; remaining soft spots flagged at §10.

| # | v1 error | Truth (measured 2026-09-08) | Fixed in |
|---|---|---|---|
| 1 | Subagent line counts repeated as gospel (`AIToolExecutor 3,538`, `ChatAssistantActivity 1,844`, `EditScriptApplier 1,123`, `BRollBucket 247`, `TranscriptSynthesizer 443`, `SequenceAiOps 313`) | **3,268 / 1,653 / 1,017 / 219 / 396 / 293** — my v1 inflated 8–13%. All line refs are ±30-line search anchors; HEAD drifts. Grep the symbol. | §2.1 |
| 2 | "56 dispatched tools" | **55** dispatch cases in `AIToolExecutor.executeTool()` (L89–144; one line is the `set_clip_duck` removal comment — I counted it). Repo-wide `case "` = 60 (hits `SequenceAiOps` inner switches). | §2.2 |
| 3 | Forecast gave a single "base" band (4k–20k installs, $600–$6k) with no funnel, implicitly assuming the YouTube engine runs | Wrong shape and optimistic. Recalibrated as funnel + three explicit scenarios; my listing-only (bear) case now converges with both sibling reports: **1.5k–6k installs, $150–$600**. | §5 |
| 4 | **No plugin layer.** The owner's brief name-checks Blender/Winamp/XBMC/Obsidian; my v1 ignored it | Prior in-repo work exists (Tier-4 plugin folder P0 + templates, WASM/native parked, sprite model pre-shaped as plugin client, visualizer presets library, selection-badges extension point, SlideContract as working pack precedent, EditScript as the plugin mutation API). Now §7 — made load-bearing, integrated with the Tier-4 lane. | §7 |
| 5 | Provider design was OpenRouter-centric with alternatives listed in a footnote | Owner requirement: *not dependent on one service*. Rebuilt as a capability+budget router with ≥3 independent backends per capability class, direct vendor APIs, custom/local endpoints, a no-key try-path, and a mandatory pre-build probe. | §6.2–6.4 |
| 6 | §5 spec gaps found on re-read: raw `File` bucket paths break under scoped storage on the Play build; video gen inside the 120s tool timeout would die; generated MP4s not normalized for Media3 export | Fixed: MediaStore/SAF-aware dual-copy file story; async job pattern via `AIJobService`; import normalization (H.264 yuv420p + silent AAC). | §6.5–6.7 |
| 7 | Key storage: I said DataStore+Tink (correct — `EncryptedSharedPreferences` is deprecated in `androidx.security:security-crypto:1.1.0`); sibling muse-spark v2 recommends EncryptedSharedPreferences | Keeping DataStore+Tink+Keystore; noting the disagreement and the source so JoyRaptor can arbitrate. | §6.6 |
| 8 | Competitor table missed YouTube Create, TikTok built-in editor, Node Video, Adobe Premiere Mobile | Added (§3) — they change the *distribution* story, not the wedge. | §3 |
| 9 | Scoring table implied unweighted superiority without sensitivity check | Added explicit sensitivity: re-weight for wow+ease+polish and Joy loses to CapCut. Joy leads only where agent-editing + offline + trust are the vote. | §4.2 |
| 10 | v1 omitted that `ai_merge_transcript` is misnamed (algorithmic, no LLM), and that `remove_silence` is report-only for audio clips | Now in the defect list (§2.5). | §2.5 |

What v2 deliberately does **not** do: re-litigate the two sibling reports, or duplicate their exact work orders. My §8 work orders are self-consistent within this document and marked as an alternative plan to theirs; JoyRaptor picks one.

---

## 1. The layers (all of them, explicitly)

### 1.1 Product layers (DESIGN_JOY_CREATOR §7 — one body, four products)

```
CAPTURE → LIBRARY → STUDIO → REMOTE      (+ STORY BOARD as the pre-edit pin/tag surface, below all queues)
```

### 1.2 Technical layers — where every asset and every new build lands

```
L7  GROWTH      Play listing · closed testing · YouTube engine · pack/template sharing loop · F-Droid/GitHub builds
L6  UX          Editor (FaditorEditorActivity) · Chat (ChatAssistantActivity) · Avatar Studio · Sprite editor
                Asset browser · Slides import · Pack manager (new §7) · Onboarding (new §6.1)
L5  AI AGENT    AIToolExecutor (55 tools) · system prompt + live project dump · proposal cards (@@PROPOSAL:*@@)
                AIJobService/AIJobStore (background, wake lock, resume) · AIChatState (one-undo signal)
L4  PROVIDERS   NEW: capability router (chat / vision / image / video roles) → OpenRouter, Anthropic, OpenAI,
                Google AI Studio, fal, Replicate, BFL, custom OpenAI-compat (Groq/Together/DeepInfra/Ollama/LM Studio),
                no-key try-path. AIKeyVault (DataStore+Tink). §6
L3.5 PACKS      NEW: faditor-pack v1 — themes, GLSL transition/effect packs, visualizer presets, slide templates,
                caption-style packs, beat templates, sprite/rig templates. PackManager + community index. §7
L3  CONTRACT    EditScript (23 ops, validate-all-then-apply, structural sim, one undo) · AIChatState.signalModified
                ApplyEditsActivity (headless apply) · SlideContract (v1, hash cache, fallback, external-paste)
L2  ENGINE      Timeline/layers/masks/chroma · GL transitions (35+/37) · slides renderer (WebView→PNG/MP4,
                content-hash cache) · TranscriptionEngine (Vosk 40MB/128MB + whisper.cpp 57MB, offline) ·
                SilenceDetector/BeatDetector/OnsetDetector/FFT (pure DSP) · EBU R128 loudness · CaptionAnimator
                · export (:export out-of-process, effect-order canon, silent-AAC rule)
L1  DATA TRUTH  project JSON (schema v13) + undo history · transcripts as NamedTranscript versions on
                Clip/AudioClip · GeneratedSource recipes · .broll_tags.json sidecars · BRollBucket
L0  PLATFORM    Android · WebView (GSAP vendored) · ffmpeg(-kit, retired upstream — replacement pending) · Media3 ·
                OkHttp · Keystore/Tink
```

**Reading the stack honestly:** L3 (contract) and L2 (engine) are the deep assets — top-3 on Android. L4 (providers) is one hardcoded vendor today — the §6 build. L3.5 (packs) is specced-and-parked Tier-4 work — the §7 build. L7 (growth) is 12 of 14 gates not started — the actual bottleneck.

---

## 2. AI inventory — corrected

### 2.1 Where it lives (measured)

```
ai/    AIToolExecutor.java 3,268 · ChatAssistantActivity.java 1,653 · EditScriptApplier.java 1,017 ·
       EditScript.java 141 · TranscriptSynthesizer.java 396 · SequenceAiOps.java 293 · BRollBucket.java 219 ·
       ModelCapabilities.java 168 · AIJobService 189 · AIJobStore 90 · AIChatState 45 · ApplyEditsActivity 101
slides/  SlideContract 323 · SlideRenderer 337 · SlideFiles/SlideCache/SlideCaptureEngine/SlideEncoder/
       SlideRenderActivity/GeneratedSlideView (device-proven 2026-07-16)
transcript/  TranscriptionEngine (Vosk + whisper.cpp, all offline) · Caption* (deterministic)
avatar/ sprite/  rigs, validators, grid detector (AI does structure, human does taste)
```

### 2.2 The 55 tools (`executeTool()` L89–144)

- **Offline/deterministic (the moat):** project sensing (`get_project_state`, `describe_clip`, `health_check`, `auto_chapters`); transcript surgery (`generate_transcript` Vosk FAST 40MB / BALANCED 128MB / whisper.cpp BEST WORDING 57MB, all offline; `get_transcript`, `correct_transcript`, `retime_words`, `synthesize_transcript`+`ai_merge_transcript` (LCS merge, misnamed), `cut_all_fillers`); one-tap `ai_enhance` (checklist loop); surgical edits (`split_clip`, `delete_clip`, `remove_span`, `set_clip_speed`, `set_clip_muted`, `set_clip_zoom`, `auto_zoom`, `move_clip_to`, `reorder_clips_by_name`, `rename_clip`, `rename_asset`); captions/canvas (`toggle_captions`, `set_caption_style` ×5 styles, `set_canvas_preset`, text overlays, `resize_overlay`, `add_opacity_keyframe`, `add_transition` ×13 GL); audio (`set_audio_volume`, `set_audio_fade`, `detect_silence`, `remove_silence` (report-only), `fix_audio` baked loudnorm chain); b-roll (`list_broll`, `add_broll_overlay`); sprite/sequence (`describe_sprite_sheet`, `set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation`, `apply_sprite_proposal`, `describe_sequence`, `edit_sequence`); avatar (`author_avatar_rig`, `apply_avatar_rig`).
- **Network (OpenRouter, key-gated):** `analyze_narrative_structure` → `@@PROPOSAL:narrative@@`, `suggest_broll_placements` → `@@PROPOSAL:broll@@` (guardrails 1.5–8s, 2s edges, ≤2 uses/asset), `tag_broll_assets` (vision tagging → `.broll_tags.json`), `generate_slide` (contract → validate → retry-once → fallback).
- **Hidden from the model:** `export_project` (stub), `health_check`, `auto_chapters`.

### 2.3 The contract

EditScript: 23 ops (`REMOVE_SPAN, ADD/REMOVE_TEXT_OVERLAY, SET_OVERLAY_{RANGE,POSITION,TEXT}, SET_CLIP_{SPEED,VOLUME,MUTED}, SET_CAPTIONS_{ENABLED,STYLE}, SET_CANVAS_PRESET, SET_EXPORT_SETTING, MOVE/ADD/ADD_OPACITY_KEYFRAME, CLEAR_KEYFRAMES, ADD_GENERATED_SLIDE, REGENERATE_SLIDE, SPLIT_CLIP_AT_TIME, REORDER_CLIPS, INSERT_BROLL_CUTAWAY, ADD_VISUALIZER`). Validate-all-then-apply; structural ops simulated on a deep copy; one labeled violet undo step per script; `REORDER_CLIPS` requires a complete permutation; unknown ops rejected. **The AI can never touch the project outside this contract** — same property §7 gives packs.

### 2.4 What makes it structurally rare

An agent that *operates a real compositor* under a validated mutation contract, with offline transcript surgery and a deterministic GSAP slide renderer — while every competitor's "AI" is captions/templates/text-to-video-into-a-bin. Sibling grok-4.6 reached the same verdict independently; convergence noted.

### 2.5 Known open defects (fix list for the executing agent)

1. Tool-loop recursion unbounded (chat); chat `max_tokens` 1024 too small for edit-script replies.
2. Plaintext `ai_api_key` in prefs while `security/StreamKeyManager` Keystore machinery exists unused.
3. Default model `openrouter/auto` is the **paid** router — new users hit a paywall on message one.
4. `ai_merge_transcript` misnamed (algorithmic). `remove_silence` audio path unimplemented.
5. Three proposal tools still spine-only (`analyze_narrative_structure`, `apply_narrative_proposal`, `suggest_broll_placements`) — `SPEC_20260828_AI_AUDIO_TOOLS.md` is the fix spec, one file.
6. Exported `ApplyEditsActivity` accepts edit scripts from any app; `SlideRenderActivity` exported (both headless by design — gate or document).
7. No streaming; "Thinking…" void. No model picker; key entry buried in chat settings dialog.

---

## 3. Competitors (Sept 2026)

| App | AI headliners | Pricing reality | Ratings / sentiment | Complaints that matter |
|---|---|---|---|---|
| **CapCut** | captions, cutout, Seedance t2v, teleprompter, credit pool | Pro **$19.99/mo / $179.99/yr** (Feb 2026, unannounced ~2.3×) | Play 3.4★ (12M), **Trustpilot 1.2–1.3** | "CapCut Cliff" renewal; free features paywalled; billing traps; AI freezes; AI slop; ToS license |
| **Canva** | Magic Studio, tiered AI allowances + AI Pass | Pro $15/mo; Teams +317% (2024); Q2 2026 revenue miss | souring | credits burn on failed gens; feature takeback; "old buttons back" |
| **InShot** | captions, bg removal | Pro $3.99–4.99/mo; **lifetime $39.99–49.99** | 100M+ MAU | lifetime buyers re-paywalled; watermark persists despite Pro |
| **VN** | AutoCut, BeatsClips, AI Kits (credit-gated) | **Free: no watermark/ads, 4K/60**; Pro ~$8/mo | **4.7★ (5M ratings, 100M+)** | freezes; creeping credits eroding trust |
| **YouCut** | light AI | Free, no watermark; ~$9.99/yr | **4.8★ (7.8M reviews)** | single-track; gates the good stuff |
| **KineMaster** | captions/tracking/chroma free w/ watermark+ads | $9/mo, ~$60/yr; no lifetime | revenue −2%; "zero moat" | the cautionary tale (€2 lifetime → sub → 6× spike) |
| **PowerDirector** | big AI suite; mobile ≠ desktop | $59.99/yr; 100 expiring credits/mo | declining | expiring credits; full-library permission; double-billing |
| **Splice** | light AI | **$9.99–12.99/week**; ~40 hidden A/B plans | iOS 4.6★ but 4.2 written | "£9.99 A WEEK??? GREED"; predatory auto-select |
| **Videoleap** | t2v, AI chat editor | $9.99/mo + $30/100 credits | billing-trap Trustpilot | layers cut 4→2 post-purchase; refund refusals |
| **Filmora mobile** | Veo 3.1 t2v, Story-to-Video | Perpetual $79.99 **then re-paywall behind $20/mo** (Jun 2026); real cost $270–340/yr | 4.0★ | perpetual burned; credit hunger; AI slop on open |
| **Captions (Mirage)** | AI Edit, AI Twin, dubbing | free = 200 *lifetime* credits; entry tier retired; Max $24.99/mo floor | $500M valuation | credit opacity; free tier = demo |
| **Edits (Instagram)** | AI assistant + desktop announced | **Free, 4K, no watermark** | 7.1M wk-1 downloads; >100M installs | draft reliability; Meta login |
| **YouTube Create** | Google funnel, templates | Free | — | distribution, not depth — the reach competitor |
| **TikTok built-in** | captions, effects, AI effects | Free | — | where the audience already is |
| **Node Video** | none generative; node VFX, 100% offline | Pro $3.49/mo, $20/yr, life $60 | craft niche | cliff learning; no 4K even paid |
| **Adobe Premiere Mobile** | Firefly t2i/t2v (commercially safe) | free 4K no watermark; Firefly credits $10/2k | — | credit burn opaque; iOS-first launch |
| **LumaFusion** | minimal, ethical on-device | **$29.99 one-time** + $19.99 add-ons | "gold standard" | Android stability gripes |

**Evidence-quality box (honesty):** prices are secondary research, region/variant-drifty ±15–30%; Trustpilot over-samples billing anger; Play ratings over-sample casuals. Weight billing/support complaints HIGH (they predict your reviews if you copy the model), crash reports MEDIUM-HIGH on 4GB devices. Re-verify at ship week; do not quote in listing copy.

**Cross-cutting whitespace (unchanged from v1, now cross-confirmed by both sibling reports):** free no-watermark core · no AI-credit roulette · lifetime that stays lifetime · stability/"never lose your edit" · on-device processing · honest checkout · long-form on mobile · no AI slop UX. **BYOK: none of the ~16 apps above offer it.**

---

## 4. Audit & ranking

### 4.1 Scorecard (0–10; unweighted mean, with sensitivity)

| Axis | Joy | CapCut | VN | Edits | Kine | PowerDir | Filmora | LumaF | Node | Alight |
|---|---|---|---|---|---|---|---|---|---|---|
| Agent edits the timeline | 10 | 6 | 1 | 6 | 0 | 7 | 4 | 0 | 0 | 0 |
| Transcript-driven editing | 10 | 6 | 3 | 5 | 3 | 5 | 5 | 0 | 0 | 0 |
| Offline / privacy | 10 | 3 | 8 | 5 | 7 | 4 | 3 | 10 | 10 | 10 |
| Price honesty / no hobbles | 10 | 5 | 9 | 9 | 6 | 4 | 3 | 10 | 6 | 4 |
| Motion-design depth | 8 | 6 | 4 | 5 | 6 | 6 | 5 | 6 | 9 | 10 |
| Generative wow (t2i/t2v) | 2 | 10 | 5 | 7 | 2 | 10 | 9 | 0 | 1 | 0 |
| Ease / onboarding / templates | 3 | 10 | 8 | 8 | 7 | 7 | 9 | 5 | 3 | 4 |
| Stability / polish (at scale) | 4* | 6 | 7 | 6 | 5 | 5 | 6 | 9 | 5 | 5 |
| **Mean** | **7.1** | 6.5 | 5.4 | 6.4 | 4.5 | 6.0 | 5.5 | 5.0 | 4.3 | 4.1 |

\* unproven at scale, not measured-bad. **Sensitivity (v2 fix):** weight wow+ease+polish 2× and Joy drops to ~6.0 vs CapCut 7.3 — **Joy leads only where agent-editing, offline and trust are the vote; loses every mass-market vote.** That is the wedge, and it must be said plainly.

### 4.2 Strengths / weaknesses / novelty (blunt, unchanged in substance from v1)

- **Strengths:** only agentic editor under a validated contract (undoable, auditable); only offline transcript surgery (Vosk clock + Whisper words + LCS + filler strike, $0 compute); deterministic slide generator that can't break the project; AE-depth (layers/masks/puppet/sprites/GL transitions); price honesty as values *and* demand.
- **Weaknesses:** zero generative wow; setup cliff (raw key + paid default model); no streaming; no voice (TTS/clone); polish unproven at scale; no automated tests (`app/src/test` empty); ffmpeg-kit retired upstream + AAB 200MB limit unmeasured; README/PRIVACY still FadCam's (factually false once AI ships); 12 of 14 launch gates not started; god-class velocity tax.
- **Novelty:** EditScript-agent + offline transcript + contract-slides + rig/sprite JSON-as-product + BYOK-paste — a combo nobody ships. **With §6 (provider-flex gen) and §7 (packs), the moat becomes three layers:** (1) agent that edits a real timeline offline-first; (2) provider-flex visual-gen with no single-vendor dependency; (3) file-owned, shareable pack extensibility. No competitor has even one of the three as BYOK/file-owned; most have none.

### 4.3 Viability restated (v2, sharpened)

- **Cult-tool trajectory is real if all three moat layers land** — "After Effects with an intern who does the boring cuts, where you bring your own AI and own your effects as files."
- **Default-AI-editor trajectory is near-zero without ads, regardless of quality** — people share *videos*, not editors, and no watermark means no attribution loop.
- Market share of "mobile video editors" stays <0.01% in every scenario. The winnable prize is the trust+depth niche (both siblings independently put it at 3–12% of the subscription-averse prosumer niche). I concur.

---

## 5. Forecast & monetization (v2 — funnel, not vibes)

**Funnel (so the numbers can be argued with):** listing views → install (20–35% niche) → open+import (40–60%) → first export (20–35%) → donation prompt after ~5th export (8–15% of installers see it, per LAUNCH_STRATEGY's own rule) → pay $10 (6–10% of prompted *in goodwill cases*, 2–4% typical; **0.5–1.5% of installs** is the honest install-to-payer band).

| Scenario | Conditions | Installs @12mo | Payers | Revenue |
|---|---|---|---|---|
| **Bear — listing only** (no YouTube engine, no viral event) | P0 done, product competent | 1.5k–6k | 15–60 | **$150–$600** |
| **Base — YouTube engine runs** (weekly tutorials, $0 ads) | P0–P2 done, demo video, steady Reddit/XDA cadence | 8k–30k | 80–400 | **$800–$4,000** |
| **Breakout — one big wave** (≥500K-sub YouTuber, or HN/r-androidapps front page) | all above + stability survives traffic | 40k–120k | 300–1,500 | **$3,000–$15,000** |
| **Blackswan** | CapCut-class exodus event coincides | 200k+ | — | $20k+ |

Bear/base/breakout converge with both sibling reports (muse: 1.5–6k bear / 5–15k base / 40–120k breakout; grok: 1.5–6k / 8–30k / 40–120k) — the only variable that matters is **whether JoyRaptor publishes weekly**. Weight the scenarios honestly: bear ~30%, base ~50%, breakout ~15–20%, blackswan ~2%. Closed-testing gate (12×14d, not started) delays everything ≥2 weeks once triggered — start before any AI push. v1's "$600–$6,000 base" was the base case *assuming* the engine; without it, v1 was ~10× optimistic on revenue. Corrected.

**Monetization (v2, tight):**
1. **Free forever, unhobbled** — editing/export/transcripts/captions never gated; say it in the listing as a standing promise. Don't ship the phrase "free during beta" (implies future paywall); call it v1.0.
2. **$10 Supporter, one-time, forever** (Play Billing — unlocks cosmetics/badge/early access, satisfies the unlock-something rule; post-Epic rates ~20% IAP / 10% subs / 10% first $1M, +5% only if using Play Billing). Parallel Ko-fi link that unlocks *nothing* — keep the two visually separate. **Do not ship "$10 = ownership after beta"** — it fights GPLv3 (code is free; a free build always exists), fights Play policy, and reads as future hobbling. Ship instead: *"Beta installs keep full editing+export free forever. $10 Supporter unlocks themes/icons/badge/early builds — it never gates export."*
3. **AI stays 100% BYOK, $0 margin, $0 servers** — the anti-credit-roulette differentiator; nobody else can say "your AI costs exactly what the provider charges."
4. Later: hosted relay only after donations cover 6 months of hosting (LAUNCH_STRATEGY Tier-2 trigger — keep it). Theme packs are also the natural Supporter-vehicle *and* a pack-type (§7).

---

## 6. Plan to #1 + BYOK/generative-media (v2 of my §5 — agent-ready)

> Repo law for every executing agent: read `tasks/LANES.md`, claim a lane; never run gradle (watcher + `build.log`); `git add` each file as written; compile-verified ≠ device-verified; "PREVIEW AND EXPORT MUST AGREE"; AI mutates only via validated EditScript; update `docs/project-schema.md` + handoff per phase; no comments unless asked.

**P0 — Ship-safe (before any AI push).** README/PRIVACY rewrite (still FadCam's — false about AI); create the missing `TRADEMARK.md`; strip Play blockers (aliases, `MANAGE_EXTERNAL_STORAGE`→SAF/MediaStore, accessibility, cloud/streaming gone); **AAB size vs 200MB + ffmpeg-kit replacement decision**; closed testing 12×14d started; 60–120s demo video (lecture→AI edit→chapter cards→export, price $0 on screen). **Done-when:** testers install, transcribe offline, export 4K no watermark.

**P1 — Setup cliff → 2-minute delight (highest ROI).** (1) Default model `openrouter/auto` → a `:free` slug (one-line, biggest cost-trust fix); (2) Provider picker + per-role model dropdowns + cost hints + Test button (§6.3); (3) OpenRouter PKCE one-tap connect; (4) SSE streaming; (5) tool-loop depth cap + `max_tokens` 4096; (6) AIKeyVault migration; (7) one-tap chips exposing existing `ai_enhance` / `fix_audio` / `auto_chapters`; (8) rename `ai_merge_transcript`. **Done-when:** fresh install → 3 taps → streamed AI edit, $0 spent, zero free-text fields.

**P2 — Flagship loop + growth loops.** Lecture mode (transcribe→enhance→narrative card→slides→captions→export) reusing existing tools; expose `auto_chapters`; shareable slide templates (`.jyslide` zip → existing `SlideImportBottomSheet` — the Alight-QR mechanic); 3 YouTube scripts + r/androidapps launch post. **Done-when:** stranger completes the 10-minute-video lecture flow in <15 min; template round-trips.

**P3 — Universal visual-gen (§6 below).** **Done-when:** §6.8 acceptance.

**P4 — Packs (§7 below).** **Done-when:** §7.6 acceptance.

**P5 — Moat hardening.** Composed-frame vision (send what the user *sees*); audio-clip-aware proposal tools (SPEC_20260828); JVM tests for validators/synthesizer (golden EditScripts); Story Board; crash-free instrumentation; AI-output report button (Play GenAI policy).

### 6.1 Auth reality (design around this, not the wishlist)

| Provider | Chat | Image | Video | Auth | Notes |
|---|---|---|---|---|---|
| **OpenRouter** | ✅ | ✅ (probe shape first) | ✅ `/api/v1/videos` job-poll (Veo 3.1, Kling 3.0, Seedance, Wan, Hailuo) | **PKCE one-tap** or paste key | default; `:free` models for $0 onboarding (20/min; 50/day <$10 lifetime credits, 1,000/day ≥$10) |
| **Google AI Studio** | ✅ | ✅ Nano Banana $0.02–0.13/img, Imagen 4 $0.02–0.06 | ✅ Veo 3.1 $0.05–0.40/s | paste key (free tier) | Gemini **subscription** OAuth banned Feb 2026 (permanent-ban risk) — API key only |
| **Anthropic** | ✅ Messages API | ➖ | ➖ | paste key | **subscription OAuth banned** (ToS Feb 19 2026, enforced) — never promise Claude-Pro login |
| **OpenAI** | ✅ | ✅ gpt-image-1.x | ⚠ Sora-2 API **stops Sep 24 2026** — do not integrate | paste key | Codex-OAuth gray zone exists; v1 ships key-only |
| **fal.ai** | ➖ | ✅ FLUX Klein $0.014/img, Nano Banana | ✅ Kling/Veo/Luma/Hailuo, one key | paste key | job-queue API; budget backend |
| **Replicate** | ➖ | ✅ (SD/nano-banana hosts) | ✅ | paste key | per-second/per-image billing |
| **BFL direct** | ➖ | ✅ FLUX.2 family | ➖ | paste key | cheapest images |
| **Custom OpenAI-compat** | ✅ | ✅ varies | ➖ | baseUrl + key | **one wire format covers Groq, Together, DeepInfra, Novita, LM Studio, Ollama, llama.cpp-server** — local/offline chat for free |
| **No-key try-path** | ➖ | ✅ Pollinations (rate-limited, quality varies — labeled) | ➖ | none | "try before you buy" |
| **openart.ai** | ➖ | ➖ REST **does not exist** | ➖ | — | surface is MCP+OAuth (`mcp.openart.ai`), not a Bearer key. v1 = **import-sheet path** (generate on their site → paste/URL → `import_external_media`); MCP client = future option, parked behind the same security review as WASM |

**Subscriptions cannot be pasted — say so in UI:** ChatGPT Plus / Claude Pro / Gemini Advanced are consumer plans, not API keys. The import sheet is how subscription users participate without API spend.

### 6.2 Architecture (no single-vendor dependency)

```
NEW ai/provider/
  AIProvider.java        interface: chat(msgs,opts,streamCb) · generateImage(req) · startVideo(req)/pollVideo(job)
  OpenRouterProvider · GoogleAIProvider · AnthropicProvider · OpenAIProvider · FalProvider · ReplicateProvider
  · BFLProvider · CustomCompatProvider(baseUrl+key — covers Groq/Together/DeepInfra/Ollama/LM Studio)
  CapabilityRouter.java  tool calls name a CAPABILITY + BUDGET, never a vendor:
                         {kind:"image", quality:"draft|standard|hero", maxCostCents:N}
                         → routes to cheapest configured backend satisfying it; skips unconfigured (never error-blocks);
                           reports where it went + what it cost ("OpenRouter not configured — used fal ($0.014) [Change]")
  AIKeyVault.java        DataStore + Tink AEAD, Keystore-wrapped master; allowBackup excludes key file;
                         FLAG_SECURE on key screens; FLog redacts Bearer; migrate legacy ai_api_key once
  ProviderPrefs.java     per-role models: chat / vision / slide / image / video
```

Refactor (not fork): the 4 hardcoded OpenRouter call sites (chat ×3 in `ChatAssistantActivity`, slide/vision in `AIToolExecutor`) route through the router; `ModelCapabilities` gains `supportsImageGen/supportsVideoGen/costHint`. **Resilience rule: every capability class has ≥3 independent implementations at ship, and no capability may depend on exactly one vendor.** Video dies at any single vendor (Sora shutdown Sep 24 2026 is the fresh proof).

### 6.3 Tools + project injection (uses what exists)

- `generate_image(prompt, negative?, aspect, style_preset?, placement?)` · `generate_video(prompt, duration_s 2–8, aspect, placement?)` · `import_external_media(fileUri|base64|url, kind, prompt?)` — the last one is the Claude/OpenArt/anything escape hatch.
- Files (scoped-storage-safe): bucket copy via **MediaStore/SAF** (`Pictures/FadCam/assets/gen/`) for the user-visible library; **project-private copy** `<projectDir>/gen/` always `File` (app-private, export-safe). `.gen_meta.json` sidecar (prompt/negative/seed/model/provider/hash) in both. Timeline/export reference **only** the project copy; missing file → relink gate → regenerate-from-recipe. Vision tags pre-populated into `.broll_tags.json` so `list_broll`/`suggest_broll_placements` see generations instantly.
- EditScript ops (mirror `ADD_GENERATED_SLIDE`): `ADD_GENERATED_IMAGE`, `ADD_GENERATED_VIDEO` (fullscreen clip or overlay/`INSERT_BROLL_CUTAWAY` when placed), `REGENERATE_MEDIA`. Propose-then-confirm via new `@@PROPOSAL:media_gen@@` card (preview, model+cost, Place/Retake/Delete/Apply) — never auto-insert; doctrine "AI does structure, human does taste."
- **Async:** image sync (<60s) may run in-tool; **video must NOT** — `TOOL_TIMEOUT_MS=120s` would kill it. `generate_video` returns `{jobId, pollAfterS}` immediately; `AIJobService`/`AIJobStore` carry progress + resume (30-min wake lock, notification, tap-to-reopen) — the transcription pattern, already built.
- **Normalization at import (the slide lesson):** probe duration; H.264 yuv420p; **add silent AAC track when missing** (Media3 rejects video-only-before-audio); flatten rotation. Otherwise preview-plays-but-export-dies.
- **Moderation/Play policy:** handle provider 400 content-moderation gracefully ("blocked — rephrase", never retry-loop paid calls); in-app "Report this result" on gen cards; no real-person-face default prompts.

### 6.4 End-to-end journey (the "effortless" bar)

> 50-minute lecture → AI Edit checklist runs → *"6 filler clusters, better order. Want chapter cards? I can also generate b-roll."* → *"generate abstract neural-net images for the intro and a 5s night-city cutaway"* → 3 image cards + 1 video job card (progress in notification; survives app close) → Place → clips on the timeline at the playhead, tagged, visible in Assets → Export. Only TLS calls to the user's chosen providers left the phone. Cost that session ≈ $0.40; Joy Creator's margin: $0. If OpenRouter is down/unconfigured: router silently used Google or fal; if *nothing* is configured: chat still edits (offline tools) and gen offers the no-key try-path + import sheet. No dead ends.

### 6.5 Build order (mine — alternative to the sibling reports' work orders)

| Step | Scope | Done-when |
|---|---|---|
| G0 | Provider probe (½ day, no app code): temp keys → record shape/cost/poll per vendor → `tasks/PROVIDER_PROBE.md` (redacted; `grep -ri sk- tasks/` = 0) | probe doc exists; OpenRouter video + image shapes settled |
| G1 | AIKeyVault + migrate key | old key still works; backup rules exclude vault |
| G2 | AIProvider + OpenRouter + router; refactor 4 call sites; default model → free | chat identical; $0 default |
| G3 | Picker UI + PKCE + streaming | 3-tap onboarding; streamed replies |
| G4 | Anthropic/OpenAI/Google/fal/Replicate/BFL/Custom + Pollinations | per-role routing honored; airplane-mode → clean errors |
| G5 | generate_image + ops + GenMediaFiles (MediaStore/SAF dual-copy) + media_gen card + report btn | image visible in timeline AND export; tagged; undo reverts; delete-bucket-still-exports |
| G6 | generate_video (async job pattern) + normalization | 5s clip lands as cutaway; survives process-kill resume; airplane-mode-mid-render |
| G7 | (optional) LiteRT-LM offline tier | on-device chat, clearly labeled |

**Traps:** SSE deltas posted to main thread; never block main on video polls; never log keys; scoped storage via MediaStore/SAF on Play build; `:export` process can't do network/WebView — gen downloads in editor-process pre-pass; `perl -i` needs `-CSD` + mojibake gate; no comments; `git add` per file; device-verify G5/G6 before claiming done.

---

## 7. Plugins — the parked idea made load-bearing (integrated, not costume)

### 7.1 Prior work found (verified citations — the idea is not stale, it was sequenced)

| Prior work | Where | Status |
|---|---|---|
| **Tier-4 "plugin folder P0 + templates"** | `road_map.md:314` (EVAL tier table, Tier-4 not started), `handoff.md:1725`, `PLAN_QUICKWINS_20260702.md:6` ("Tier 4 = plugin folder P0 + templates + presets pattern", gated *after M-EXPORT-1*) | **parked for stability sequencing, never rejected** |
| **WASM/native plugins parked** | `PLAN_QUICKWINS_20260702.md:45` ("On-device LLM, music/video generation, WASM/native plugins… — parked per the tier plan") | stays parked until a dated security proposal |
| **Sprite model pre-shaped as plugin client** | `PLAN_SPRITE_ANIMATION.md` ("future (plugins: parenting, nested dope sheets)") — the named, versioned, LLM-legible JSON model is the product; UI/AI/plugins are clients | design principle already locked |
| **Visualizer presets = a pack that already ships** | `feature-visualizer-studio-spec.md:24,35,56` — Tier 3 custom HTML/Canvas via the slides pipeline; presets live in a **shared app-level library** (`visualizer_presets/`), with "Save to my presets" promotion | the preset-library pattern is proven |
| **SlideContract = the working pack precedent** | `SlideContract` v1: versioned contract + content-hash cache + fallback + external-paste | every pack copies this shape |
| **EditScript = the plugin mutation API that already exists** | 23 ops, atomic, one-undo (§2.3) | packs mutate exactly like the AI does |
| **Selection badges = the declared extension point** | `PLAN_LAYER_GESTURE_CONTRACT.md:34` (user-endorsed 2026-07-03): new per-item actions ship as selection badges, never new gestures | the UX home for per-pack item actions |
| No prior spec names Blender/Winamp/XBMC/Obsidian | searched `tasks/*.md` — the analogies are the owner's | mapped below |

### 7.2 One mechanism, four inheritances — `FaditorPacks/`

Every pack = a folder/zip: `pack.json` (`{contract:"faditor-pack v1", id, name, version, kind[], params schema, defaults, assets[]}`) + assets, hash-verified on install. **No native/WASM execution ever in v1** — packs are data + GLSL + sandboxed JS in the existing WebView sandbox (the slides engine — no new runtime; same `evaluateJavascript`-only rule, no JS bridge).

| Analogy | What it inherits | Concrete Joy pack kinds → existing host |
|---|---|---|
| **Blender** (node/graph extensibility) | capability via composable primitives | **effect/transition packs** (GLSL shader + params schema → the existing 35+/37 GL transition host and layer-effect pipeline); **rig packs** (AvatarRigTemplates-shaped JSON → `avatar/` validators); **sprite behavior packs** (FrameTrack weights/parenting → the pre-shaped JSON model) |
| **Winamp** (skins + visualizers) | identity + audio-reactive delight | **theme packs** (palette/frosted-glass section colors — also the $10 Supporter vehicle); **visualizer packs** (JSON/HTML preset + BeatDetector/OnsetDetector DSP → `visualizer_presets/` shared library — already exists) |
| **XBMC/Kodi** (repo model, no server) | community distribution, $0 infra | **community index**: one hash-pinned `index.json` on GitHub Pages the app browses; packs otherwise shared as zips (Discord/GitHub/F-Droid) — no store, no review queue, no server |
| **Obsidian** (folder-first, user-owned vault) | ownership + versioning | `Pictures/FadCam/packs/` (user-visible, SAF/MediaStore-aware) + `<projectDir>/packs/` (export-safe pinned copy, dual-copy rule like gen assets) + `.pack_meta.json` sidecar; enable/disable **per project**; project JSON references `{packId, packHash, params}` — **no schema bump required**; missing pack → graceful fallback style + notice, never crash |

### 7.3 AI × packs (the integration that makes it more than a folder)

- Pack summary injected into the chat system prompt (names + 1-line descriptions, capped — same discipline as `BRollBucket.getAssetsSummary()`), so "make this chapter neon" routes to the pack.
- New tools: `describe_pack(packId)` (deterministic introspection, schema + params the model may fill) and `apply_pack(packId, target, params)` — **propose-then-confirm card, EditScript-only mutation, atomic, one undo** (reuses the `apply_avatar_rig`/proposal-card pattern and validator discipline). Local packs apply **without any key** — packs are offline like the DSP; only LLM-authored params need a key.
- Per-item pack actions ship as **selection badges** (the declared extension point), not new gestures.
- Templates-as-packs unify the growth loop: the §6.5-adjacent shareable slide templates ARE slide packs; the beat-cut template (existing beat DSP) is a pack; the Winamp-style skin gallery is the pack manager.

### 7.4 Why this flips the verdict

CapCut templates are server-locked and credit-taxed; Joy packs are **files the user owns, shares, and versions**. Combined with §6.2 (swappable AI backends), Joy becomes the only mobile editor where **both the AI brain and the effect library are swappable** — that is the "capabilities far exceeding competitors" claim made concrete without a native plugin host, and it is built 80% from things the repo already has (contract, hash cache, proposal cards, badges, presets library, GL host, DSP).

### 7.5 Build order (lane-split)

| Step | Scope | Done-when |
|---|---|---|
| PK0 | `PackManager` (scan/install/enable/verify-hash per `faditor-pack v1`) + `Pictures/FadCam/packs/` + `<projectDir>/packs/` + `.pack_meta.json`; 5 built-in packs (2 caption styles, 1 chapter-card slide pack, 1 beat-cut template, 1 theme) | zip install offline; hash-mismatch refused with reason; old project without packs opens byte-identical |
| PK1 | `describe_pack` + `apply_pack` tools + system-prompt pack summary + proposal card + badge actions | "make this neon" → card → Apply → one undo step; no-key local apply works |
| PK2 | Community index (`index.json` on Pages, hash-pinned) + "share pack" (zip export via existing bundling work) | pack round-trips device↔device; index browsable in-app |

**Non-goals (keep it shippable):** no native/WASM execution, no paid pack store, no auto-update, no JS bridge beyond `evaluateJavascript`, no per-export gating ever.

---

## 8. Consolidated work orders (my alternative to the sibling reports' Agent A–E split)

- **Agent 0 — Provider probe** (§6.5 G0). Blocks media-gen coding; also settles OpenArt-MCP, OpenRouter `/videos` shape, fal/Replicate auth.
- **Agent A — Vault + providers + router** (G1–G4).
- **Agent B — Chat + tools + ops** (G5–G6; touches `AIToolExecutor`, `EditScript(+Applier)`, `ChatAssistantActivity`, `AIJobService` reuse).
- **Agent C — Files + schema + import sheet** (GenMediaFiles dual-copy, normalization, GenImportBottomSheet, relink gate).
- **Agent D — Pack foundation** (PK0). **Agent E — AI × packs + index** (PK1–PK2).
Sequencing: P0 (ship-safe) → P1 (setup cliff) → G5 (image E2E) → PK0/PK1 in parallel with G6 (video) → P2 flagship/growth. Single-writer per lane per LANES.md.

---

## 9. #1 definition (12 months, falsifiable)

4.5★+ with 5k+ reviews mentioning "no subscription + AI actually edits"; top-10 Play search for "AI video editor no watermark"; 50k+ installs; $30k+ Supporter revenue; F-Droid featured; YouTube ≥10k subs compounding; **and the three-layer moat live in production** (agent-editing + provider-flex gen + packs). Not "beat CapCut installs" — own the trust+agent+ownership corner, then expand.

---

## 10. What is still unverified (probe list — do not build against these as facts)

1. OpenRouter image-gen request shape (`modalities` vs dedicated endpoint) + `/api/v1/videos` exact contract — G0 probe.
2. fal/Replicate/BFL exact auth + queue semantics on Android (timeouts, redirects, file sizes).
3. Whether any OpenAI-compat vendor serves *image* models over the chat-completions wire format.
4. AAB size with ffmpeg replacement vs 200MB limit; ffmpeg-kit fork vs Media3-native.
5. Crash-free rate on 4GB devices (test on a low-RAM Moto-class device, not just the Note 9/20 sandbox).
6. Donor conversion under the exact prompt copy (the only number that can be A/B'd honestly, post-success).
7. Play policy interpretation of the Supporter tier at ship week (rates moved in Jun 2026; re-read the policy page, not my summary).

**Document layers map (for the judge):** §0 self-audit → §1 all-layers stack → §2 corrected inventory → §3 competitors + evidence quality → §4 audit/sensitivity → §5 funnel forecast + monetization → §6 plan + BYOK/generative media (auth matrix, router, tools, journey, build order) → §7 packs integrated with prior work → §8 work orders → §9 falsifiable #1 → §10 probe list.
