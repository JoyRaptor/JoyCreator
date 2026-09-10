# grok-4.6 — Joy Creator AI Audit, Plugin Revival, Multi-Provider Media Gen

**Model:** `opencode-go/grok-4.6`
**Written:** 2026-09-08 (v2 — plugins revived, gen backends de-OpenRoutered)
**Owner:** JoyRaptor / JoyRaptor
**Status:** RESEARCH + EXECUTION SPEC. Do not code until JoyRaptor confirms.
**This file only.** Other same-day agent reports in `tasks/` are not mine. Do not merge/overwrite them.

Give an implementing agent **§10**. Doctrine and file paths are load-bearing.

---

## 0. Verdict (v2)

Joy Creator already has an in-editor agent with **hands** (56 tools that mutate a real compositor). CapCut/Canva/KineMaster mostly have captions, templates, and credit-gated gen toys.

v1 of this audit treated BYOK chat + image inject as the moat. That is necessary and still not enough. The **stale** idea — a Blender/Winamp/XBMC/Obsidian-style plugin folder — was not a daydream. It was **user-approved Tier 4 on 2026-07-02**, then parked behind export/Layers. Meanwhile the codebase grew the actual primitives of a plugin host and never named them that.

**Three-layer moat (this is the competitive edge):**

1. **Agent that edits** — EditScript + tools + on-device Whisper/Vosk. Files stay on the phone.
2. **Provider-flex generation** — chat and pixels through *whatever key the user already has* (OpenRouter, OpenAI, Anthropic, Gemini, fal.ai, Replicate, custom/Comfy, share-in). No Joy servers. No single-vendor hostage.
3. **User-owned packs** — drop a folder/zip of GLSL, LUTs, slides, sprites, vis presets, themes. The AI can list and apply them. CapCut templates live on ByteDance's servers and expire with a plan. Joy packs are files.

No mass-market mobile editor has all three. Most have none.

**#1 vs CapCut at CapCut's game: no.** Hundreds of millions of users, TikTok, Seedance, ads. A no-name listing with zero UA will not take that.

**#1 in the winnable category: yes, if 1+2+3 land.**

> The Android studio whose AI can edit, whose models you bring, whose effects you own as files.

**Year-1, no ads, word of mouth:**

| Scenario | Installs | $10-equivalent money |
|---|---|---|
| Listing only, no YouTube | 1,500–6,000 | $150–$600 |
| YouTube tutorials running ($0 ads) | 8,000–30,000 | $800–$4,000 |
| One viral post + YouTube | 40,000–120,000 | $3,000–$15,000 |
| Pack-sharing loop extra (Obsidian-style) | +20–50% on the YouTube row | not a new TAM, better retention |

Market share of mobile video editors: **< 0.01%**. Niche share of "AE-class Android, no sub": **3–12% if quality is real**. Default-name "AI video editor": **near zero without ads**. Cult tool that power users pass packs around: **the actual business**.

**Do not ship "$10 = ownership after beta."** GPL + Play Billing + bait-and-switch. Forever free, unhobbled. Founder cosmetics via Play Billing; Ko-fi unlocks nothing. Details §6.

**Do not claim ChatGPT Plus / Claude Pro / Gemini Advanced plug in.** Those are chat plans, not APIs. Honest doors: API key, OpenRouter, fal/Replicate, custom endpoint, or **Share into Joy Creator** (the Plus-honest path).

---

## 1. Product

| Name | Reality |
|---|---|
| FadCam | Upstream GPLv3 recorder. Forked `78647c1` (2026-06-05). `applicationId` still `com.fadcam.beta`. |
| Faditor | Editor. ~87% of fork code. `com.fadcam.ui.faditor.*`. |
| Joy Creator | Locked name 2026-07-02. Capture → Library → Studio. |

Thesis already in `tasks/LAUNCH_STRATEGY.md`: no hostage export, no ads, no sub, BYOK until donations cover 6 months of hosting. Chat persona still says "FadCam AI" — rebrand gap.

---

## 2. AI as built (more than the three specs)

You named slides, audio-tools, A5 rigging. Live code is larger.

**Package** `app/src/main/java/com/fadcam/ui/faditor/ai/` — 12 classes. No `OpenRouterClient.java`; HTTP is inlined in `ChatAssistantActivity` + `AIToolExecutor`. That extraction is P0 for multi-provider.

**Loop:** editor robot → chat → OpenRouter `chat/completions` → model emits `{"tool","args"}` **in text** (not native function-calling) → executor → `ProjectStorage.save` → `AIChatState.signalModified`. Destructive narrative/b-roll/rigs use `@@PROPOSAL:*@@` cards. `generate_slide` applies immediately.

**Prefs:** `ai_api_key`, `ai_model`, `ai_provider` (declared, **unused**). Default `openrouter/auto` is **paid**. Change it.

**56 tools.** Inspect, on-device speech (Vosk/Whisper), timeline mutations, LLM slides/narrative/b-roll/vision tags, sprite/sequence/avatar (no network). Stubs: `export_project`, unpublished `auto_chapters` / `health_check`.

**Not built:** `generate_image`, `generate_video`, music, on-device LLM, native tools array, hosted credits, project-folder agent (path in prompt only), EditScript ops for sprites/avatars/PiP/layers.

**Inject path already exists.** `FaditorEditorActivity.importInsertedAsset` (~39518) copies images and ≤25 MB files into `<project>/assets/`. `TextOverlayItem.createImage`. `AssetResolver` `project://`. B-roll bucket (`Pictures/FadCam/assets/` + `.broll_tags.json`) is a **second** asset world — gen must copy into the project, not only the bucket.

**North star** (slides spec §1): one chat turn cuts silence, reorders the argument, makes chapter cards, pulls b-roll. Tools exist separately. No confirm-all orchestration.

Extra docs: `DESIGN_JOY_CREATOR.md`, `EVAL_20260701_joy_creator.md`, `LAUNCH_STRATEGY.md`, `feature-narrative-reorder-spec.md`, `feature-broll-matching-spec.md`, `PLAN_SPRITE_ANIMATION.md`, `PLAN_AVATAR_STUDIO.md`, `SPEC_IMAGE_SEQUENCE.md`, `SPEC_AUDIO_UX_V1.md`, `ASSETS_WISHLIST.md` #12 (unanswered until this doc), `HANDOFF_20260806_PICKUP.md`, `handoff.md`, `docs/project-schema.md`. `road_map.md` Phase 6 is stale.

---

## 3. Competitors (mobile, 2026)

Three kinds of "AI": assists an editor / is the interface / creates the footage. Joy is kind 1, deep. Kind 3 is the hole. Kind 2 exists as a JSON-tool chat, not a director UX.

| | CapCut | Canva | KineMaster | VN | Alight | Descript | Runway/fal |
|---|---|---|---|---|---|---|---|
| Timeline | social-fast | pages | real layers | real | AE-lite | transcript | gen bin |
| Agent-edits | weak | weak | none | none | none | strong desktop | none |
| Gen pixels | Seedance (region-locked) | Veo | almost none | none | none | weak | **the product** |
| Tax | Pro + credits + privacy | Pro + AI allowances | watermark/ads | **free 4K** | sub | $24/mo | credits |
| BYOK | no | no | no | no | no | no | you are the backend |
| User-owned packs | server templates | Brand Kit in cloud | asset store | no | some presets | no | no |

Review anger, usable against them: subscription creep, watermarks, AI that does not edit, gen sameness, cloud, credits-on-credits, "I already pay for ChatGPT."

Joy answers the first three and privacy. Packs answer template lock-in. Multi-provider answers the last two. Gen inserts (not fake movies) close the FOMO hole without becoming Sora.

---

## 4. Ranking (v2)

Android power-studio, 1–10.

| Dimension | Joy | CapCut | Canva | Kine | VN | Alight |
|---|---|---|---|---|---|---|
| Compositor | 9 | 6 | 3 | 8 | 8 | 9 |
| AI mutates edit | 8 | 5 | 4 | 4 | 1 | 1 |
| Gen pixels (today) | 1 | 8 | 7 | 2 | 1 | 1 |
| Extensibility (packs) | 4 (primitives exist, not named) | 3 (locked mall) | 4 | 3 | 2 | 5 |
| Privacy / offline | 9 | 2 | 2 | 4 | 7 | 5 |
| Unhobbled export | 10 | 3 | 4 | 4 | 9 | 3 |
| Distribution | 1 | 10 | 9 | 7 | 5 | 5 |
| **Power studio** | **7.4** | 6.1 | 4.8 | 6.0 | 5.7 | 5.4 |
| **Mass-market AI video** | 3.9 | **8.6** | 7.2 | 6.3 | 4.1 | 4.0 |

After packs P0 + provider-flex gen, Joy's power-studio score is the one that can go to **8.5+**. Mass-market stays low without ads. That is the category, not a failure.

**Novelty to defend:** agent + file-owned effects + swappable backends. Do not defend "we generate video better than ByteDance."

---

## 5. Viability reassessment

v1 numbers hold. Plugins do **not** 10x installs in year 1. They 10x **why people stay and share**. Obsidian did not beat Word by ads; people mailed vaults. Winamp skins were the social layer. Joy packs (a neon chapter-card zip, a gl-transition folder, a sprite+rig) are the thing a YouTube description can link.

Without YouTube, $10 donations do not feed a family. With YouTube + Founder cosmetics + pack culture, the cult-tool path is solvent enough to keep building. Hosted credits remain LAUNCH_STRATEGY Tier 2.

Closed-testing (12 testers × 14 days) is a Play gate. Start it before any AI marketing push or every scenario slips two weeks.

---

## 6. Money (unchanged recommendation)

- Forever free, unhobbled, no ads, no watermark.
- Ko-fi / Sponsors: unlocks **nothing** (Play-legal external).
- Play Billing Founder $10–$25: **cosmetics / themes / badge only**. Theme packs are also the Winamp-skin unlock if you want one paid door.
- YouTube is the business.
- Hosted credits later, after 6 months of donations cover hosting.
- Drop "ownership after beta."

---

## 7. Plugins — previous work, made non-stale

### 7.1 What was actually decided (not a vibe)

| Source | What it says | Status |
|---|---|---|
| `tasks/PLAN_QUICKWINS_20260702.md:6` | Tier 4 = **plugin folder P0 + templates + presets pattern**. User-approved 2026-07-02. | Not started as a named feature |
| same file :45 | WASM/native plugins **parked** (sequencing, not rejection) | Stay parked |
| `tasks/handoff.md:1725` | Same Tier-4 line in the live backlog | Stale vs later work, still authorized |
| `tasks/road_map.md:314` | EVAL tiers table, Tier-4 = plugin folder + templates | Listed, not built |
| `PLAN_SPRITE_ANIMATION.md` | Ceiling includes "future plugins: parenting, nested dope sheets." JSON model is the client. | Model shipped; plugins didn't |
| `SpriteSheet.java:18` | Sidecar `.sprite.json` is "**the sharing format, the plugin contract**" | Code comment is the spec |
| `GlExternalTransitions.java` | Scan pinned SAF folder for `*.glsl`, register if `transition(` exists | **SHIPPED. This is Winamp vis.** |
| `PLAN_filters_color_text_transitions.md` | User `.cube` LUT import; community shaders beyond the curated 26 | LUT import not the pack host |
| `feature-visualizer-studio-spec.md` | Shared `visualizer_presets/` library; "Save to my presets"; Tier 3 HTML via slides pipeline | Spec ready; preset folder is a plugin |
| `PLAN_QUICKWINS` item 26 | Standing pattern: every tool "save current as preset" | Pattern, not a host |
| Slide `SlideContract` + `SlideImportBottomSheet` | Versioned HTML pack + copy-prompt / paste | **SHIPPED pack format** |
| `EditScript` | Validate-all-then-apply mutation API | **The plugin must emit this, never touch `project.json` raw** |
| FadCam MiniApps | Recorder gadgets (QR scanner, etc.) | Different product. Do not overload. |

No prior in-repo spec named Blender / Winamp / XBMC / Obsidian. Those are JoyRaptor's analogies. They map onto **code that already exists**. Tier 4 went stale because it was queued behind Layers/export, not because it was rejected.

### 7.2 The four analogies, bound to this codebase

**Blender (capability).** Add-ons extend the host without forking it. Joy's host already has layers, masks, keyframes, GL transitions, sprites/rigs, slides, vis. A pack is `{manifest, assets, optional sandboxed HTML/JS}`. It does not get a JVM classloader. WASM/native stays parked (`PLAN_QUICKWINS:45`) until there is a security model. P0 packs are **data + GLSL + JSON + HTML the slides WebView already runs**. That is how you get AE-plugin reach without an SDK team.

**Winamp (skins + vis).** Milkdrop was a folder of `.milk` files. `GlExternalTransitions` is already that for `.glsl` in the pinned SAF folder. Visualizer presets (`visualizer_presets/`) are vis skins. `DESIGN_JOY_CREATOR.md` section colors + frosted glass become **theme packs** (also the legal Founder cosmetic). Users already know how to zip a skin.

**XBMC/Kodi (add-on repo, no app-store tax).** Add-ons are zips + a repository XML you can host on GitHub Pages for $0. Joy P0 needs **no server**: share a zip. P1 is a static `index.json` on Pages (hash-pinned, like F-Droid without the infra). Never auto-run native code from a zip.

**Obsidian (closest).** Community plugins + templates are folders the user owns. Enable per vault. `Pictures/FadCam/packs/` (user-visible, same pattern as `BRollBucket`) + `<projectDir>/packs/` (pinned copy so export/share survives). Sidecar `.pack.json` like `.broll_tags.json` and sprite sidecars. Chat system prompt lists installed packs. "Apply the neon pack to this chapter" → `apply_pack` → proposal card.

### 7.3 Pack contract (P0 — this is the integration, not a rewrite)

```
<pack>/
  pack.json          # {contract:"joy-pack/v1", id, name, version, kinds[], minApp}
  transitions/*.glsl # GlExternalTransitions already loads these from a folder
  luts/*.cube        # LutManager path from PLAN_filters
  slides/*.html      # SlideContract. validate, then ADD_GENERATED_SLIDE
  sprites/*.png + *.sprite.json   # already the plugin contract
  visualizers/*.json # visualizer preset objects
  themes/*.json      # palette tokens from DESIGN_JOY_CREATOR.md
  prompts/*.txt      # copy-prompt templates for API-less gen
```

`pack.json` kinds is a subset of those folders. Unknown kinds = ignore, do not crash (Obsidian rule). Invalid GLSL = skip that file (`GlExternalTransitions` already skips non-`transition(`).

**AI tools (add to executor, deterministic, no network):**

- `list_packs` — installed packs + kinds + file counts
- `describe_pack` `{packId}` — manifest + asset names
- `apply_pack` `{packId, target:"project"|"selection", items?:[]}` → `@@PROPOSAL:pack@@` then apply via EditScript / existing import paths

**Do not** give packs a second mutation API. EditScript + existing import (`importSlideHtml`, `registerExternal`, `createImage`) are the host.

### 7.4 Why this exceeds competitors

CapCut templates are trendy and dead next month, and they are not yours. Alight has some preset sharing. Nobody on Android has: **agent can see the pack, propose applying it, undo it, and the pack travels inside the project zip** (`SPEC_20260829_PROJECT_BUNDLING`). That is Blender-level extendability with Obsidian-level ownership.

P0 is a folder scanner + manifest + three AI tools + a share zip. It is smaller than `generate_slide` was. The stale part was the *name*, not the missing engine.

---

## 8. Media gen — not OpenRouter-only

v1 over-weighted OpenRouter. It is the **best default**, not the spine. fal.ai holds ~50% of gen-media API share (2026). OpenRouter's Image API (June 2026) is chat-adjacent. Video is fal/Replicate/Google/OpenArt territory. A Joy outage of one vendor must not kill "make a still."

### 8.1 Backend matrix (all BYOK, all optional)

| Backend | Auth | Chat | Image | Video | Why it exists |
|---|---|---|---|---|---|
| OpenRouter | `sk-or-v1-` | yes | `/api/v1/images` | weak | One key for GPT/Claude/Gemini + stills |
| OpenAI | `sk-` | yes | `/v1/images/generations` | skip v1 | User already has it; text-in-image |
| Anthropic | `sk-ant-` | yes | no | no | Best editor-agent; images via another backend |
| Google Gemini | API key | yes | Flash Image / Imagen | Veo (confirm-gated) | One Google key |
| **fal.ai** | `FAL_KEY` | no | Flux etc. ~$0.003 Schnell | **primary video** Kling/Veo/Seedance/Wan | Speed + catalog. REST per-model or queue+webhook |
| Replicate | `r8_` | no | community | Kling/Veo/Wan | Models fal does not carry |
| Custom OpenAI-compat | bearer + base URL | yes | if `/images/generations` exists | no | OpenCode, LiteLLM, LAN |
| ComfyUI LAN | none / token | no | yes | maybe | Power users, $0 per image after GPU |
| OpenArt | OAuth MCP, **no REST** | no | yes | yes | v1.5. v1 = share-in |
| Share / paste | none | n/a | yes | yes | ChatGPT Plus honest path |

Chat provider and pixel provider are **independent prefs**. Claude-for-chat + fal-for-video is the expected power setup.

### 8.2 Routing rules (executor, not the model)

```
generate_image:
  1. ai_image_provider if set and keyed
  2. else if chat provider has image (OpenRouter/OpenAI/Gemini) use it
  3. else error: name Settings + "Share an image from ChatGPT/OpenArt"

generate_video:
  1. fal if keyed
  2. else Replicate if keyed
  3. else Gemini Veo if keyed and user confirmed cost
  4. else error: name fal/Replicate/Share
```

Never call OpenRouter for video in v1. Never block image gen on fal. Never require OpenArt.

### 8.3 fal.ai (video P0 when JoyRaptor has a key)

Queue API, poll or webhook. On Android: POST, poll with `AIJobService` (already used for Whisper), 3–8s clips, write `assets/{uuid}.mp4`, place as `INSERT_BROLL_CUTAWAY` or clip. Always `@@PROPOSAL:video@@` (cost + duration). Read timeout 180s+.

Do not vendor fal's proprietary SDK if a raw REST POST is enough. Lock the request shape in `FalMediaProvider.java` so swapping to Replicate is one class.

### 8.4 API-less (required, not a fallback)

Same as slides: copy packed prompt (canvas size, "no watermark") → user gens in ChatGPT/Claude/Gemini/OpenArt/web fal → Share/`ACTION_SEND` `image/*` `video/*` → `importInsertedAsset` → overlay/clip. `sourceModel: external-paste`.

This is how Plus subscriptions actually enter the app.

---

## 9. Plan to be #1 in the winnable category

**A. Trust.** Play-sanitize FadCam cloud (`LAUNCH_STRATEGY` §5), rewrite PRIVACY.md for AI keys, default model not `openrouter/auto`, persona "Joy". Device-verify existing tools with a real key.

**B. Provider extraction + stills.** §10. OpenRouter/OpenAI/Gemini images + share-in. Claude chat + other pixels.

**C. Pack folder P0.** Manifest + scan (reuse `GlExternalTransitions` scan) + `list/describe/apply_pack` + zip share. One demo pack: 3 glsl + 1 slide HTML + 1 vis preset.

**D. Video gen via fal or share-in.** Inserts only. User footage stays the spine.

**E. One-shot north star.** One confirm card: fillers, KEEP/DROP, b-roll, slides, optional gen still, optional pack. Existing tools, new orchestrator later.

**F. Distribution.** YouTube on-camera. r/androidapps, HN, F-Droid. Pack zips in descriptions. No ads until 50 organic reviews. Start Play closed testing now.

Proof of #1 in 12 months is not download charts. It is: "CapCut for TikTok, Joy when I actually edit"; packs circulating; BYOK normal; a few 50k-view tutorials.

---

## 10. Agent-executable spec

Read `tasks/LANES.md`. Do not run gradle unless the lane allows. `git add` each file as you write it. No comments unless asked. No commit unless asked. `rg -c "â|Ã|Â"` on dirty files = 0. Do not touch other agents' `tasks/*REPORT*` files.

### 10.0 Doctrine

1. Extract HTTP. Stop inlining OkHttp in two Activities.
2. Mutations via tools/EditScript only.
3. Generated files and packs that a project uses live under the **project dir** (`assets/`, `packs/`). Never cache-only. Never persist remux paths (lessons.md).
4. `project://` only (`AssetResolver`).
5. One undo checkpoint per successful generate+place or apply_pack.
6. Confirm cards for video gen, n>1 images, and `apply_pack`. Stills the user asked to place may apply immediately (like `generate_slide`).
7. Extend prefs; do not wipe `ai_api_key`.
8. Never log keys. Redact `Authorization`.
9. `DEFAULT_MODEL` must not be `openrouter/auto`.
10. No chatgpt.com OAuth. No WASM/native packs (parked).
11. Chat backend ≠ pixel backend.
12. Packs cannot run arbitrary Java. Data + GLSL + HTML/JSON only.

### 10.1 New files

```
ai/llm/LlmProvider.java
ai/llm/OpenRouterProvider.java      # move chat/completions
ai/llm/OpenAiCompatProvider.java    # OpenAI + Custom
ai/llm/AnthropicProvider.java
ai/llm/GeminiProvider.java
ai/llm/ProviderRegistry.java

ai/media/MediaGenProvider.java      # generateImage / generateVideo optional
ai/media/OpenRouterImageProvider.java  # POST https://openrouter.ai/api/v1/images
ai/media/OpenAiImageProvider.java
ai/media/GeminiImageProvider.java
ai/media/FalMediaProvider.java      # image + video; stub video if no key
ai/media/ReplicateMediaProvider.java # can stub v1
ai/media/MediaRouter.java           # §8.2 routing
ai/media/GeneratedAssetWriter.java  # bytes → <project>/assets/<uuid>.ext → project://
ai/media/PlacementApplier.java      # overlay|clip|layer|bin. TextOverlayItem.createImage

ai/packs/PackManifest.java          # joy-pack/v1
ai/packs/PackScanner.java           # Pictures/FadCam/packs + project/packs + SAF
ai/packs/PackApplier.java           # dispatch kinds to existing loaders
```

Reuse `GlExternalTransitions.scanAndRegister` inside PackApplier for `transitions/`. Reuse `SlideContract` for `slides/`. Do not duplicate shader parse.

### 10.2 Edit these files

| File | Change |
|---|---|
| `ChatAssistantActivity.java` | ProviderRegistry for chat. Settings: chat provider, pixel provider, keys, models, custom base URL. Persona Joy. Thumbnails from saved files, not b64 in history. |
| `AIToolExecutor.java` | `generate_image`, `generate_video` (may error-not-configured), `import_generated_asset`, `list_packs`, `describe_pack`, `apply_pack`. Thread `playheadMs` into constructor. Never `getSelectedClip()` (blank-spacer trap). |
| `EditScript.java` / Applier | Prefer `ADD_IMAGE_OVERLAY`. Pack apply should emit existing ops where possible (`ADD_GENERATED_SLIDE`, transitions). |
| prefs | `ai_provider`, `ai_base_url`, `ai_image_provider`, `ai_image_api_key`, `ai_image_model`, `ai_video_provider`, `ai_video_model`, `ai_fal_key`, `ai_replicate_key`. Migrate: key prefix `sk-or-` → provider openrouter. |
| Manifest | `ACTION_SEND` image/* video/* into current project. |
| `docs/project-schema.md` | packs + generated assets sidecar if needed. |
| `ASSETS_WISHLIST.md` #12 | Decided: OpenRouter default stills; fal primary video; Replicate/OpenAI/Gemini optional; OpenArt MCP later; share-in always; Comfy via Custom. |

Do not touch ExportManager, SlideCaptureEngine, avatar lock list, or other agents' reports.

### 10.3 `generate_image`

Args: `prompt`, `aspect_ratio?`, `resolution?`, `n?` 1–4, `placement?` overlay|clip|layer|bin, `startMs?`, `durationMs?`, `centerX/Y?`, `sizeFraction?`.

Empty key → error names Settings **and** share-in. `n>1` → proposal card, no place. Timeout 120s via `AIJobService`. Reject SVG v1. Default placement overlay if user said put/drop/insert, else bin. Empty startMs = playhead. Return `{ok, path, projectUri, overlayId?, costUsd?}`.

Description must distinguish from `generate_slide` (animated HTML) and `add_broll_overlay` (existing file).

OpenRouter image POST (do not invent):

```
POST https://openrouter.ai/api/v1/images
{ "model", "prompt", "aspect_ratio", "resolution", "n" }
→ data[].b64_json + media_type
```

fal image: use their current REST for Flux Schnell; isolate in `FalMediaProvider`. If the exact path drifts, fail with the HTTP body — do not guess a second shape in ChatAssistant.

### 10.4 `generate_video`

If no fal/Replicate/Gemini video key: error names those + share-in. If keyed: always confirm card. 2–8s. `INSERT_BROLL_CUTAWAY` or new clip. Job service. Write under `assets/`.

### 10.5 Packs P0

Scanner looks at:

1. `context.getExternalFilesDir` or `Pictures/FadCam/packs/` (match `BRollBucket` style)
2. `<projectDir>/packs/`
3. Optional SAF tree (same as `GlExternalTransitions`)

`list_packs` / `describe_pack` are read-only. `apply_pack` validates then `@@PROPOSAL:pack@@`. Apply copies needed files into the project (so the project zip still works) then registers GLSL / imports HTML / adds vis preset. One undo.

Ship **one** example pack in `app/src/main/assets/packs/joy-starter/` for first-run copy.

### 10.6 Settings UI

Replace `showSettingsDialog` (~1231). Chips: OpenRouter / OpenAI / Claude / Gemini / Custom / fal / Replicate / OpenArt.

OpenArt chip v1: copy explaining MCP-later + share-in. Do not fake a key field.

Two columns conceptually: **Talk** (chat provider+key+model) and **Make** (image/video provider+key+model). Checkbox "different key for pixels."

Footer: keys on device; prompts go to the selected vendor; we do not operate a server.

### 10.7 System prompt

Name: Joy Creator AI. Explain generate_image vs generate_slide vs apply_pack. List installed pack ids (like b-roll summary). Playhead + canvas. One sentence if user mentions Plus/Pro/Advanced: those are chat plans; paste an API key or Share the file.

### 10.8 Order of work

1. `GeneratedAssetWriter` + JVM harness (round-trip `project://`).
2. `OpenRouterImageProvider` against a fixture JSON.
3. `generate_image` overlay place + save + signalModified.
4. Extract chat HTTP to `OpenRouterProvider` with no behavior change.
5. Settings + `PREF_PROVIDER` live + default model change.
6. Share intent.
7. `PackScanner` + `list_packs` wrapping existing glsl scan; starter pack.
8. `apply_pack` proposal for slides+glsl only.
9. `FalMediaProvider` image; video if a fixture exists.
10. Stop. OpenArt OAuth is a follow-up spec.

### 10.9 Acceptance

- [ ] `get_transcript` / `generate_slide` / external `.glsl` scan still work
- [ ] `PREF_PROVIDER` used
- [ ] `DEFAULT_MODEL` ≠ `openrouter/auto`
- [ ] PNG under `files/faditor/projects/<id>/assets/`, overlay URI `project://` after save
- [ ] Chat history has no megabyte b64
- [ ] Keys never in logcat
- [ ] No key: share-in places an image
- [ ] Image works with OpenRouter **or** OpenAI **or** share-in (at least two paths in code, even if JoyRaptor only keys one)
- [ ] `list_packs` sees `joy-starter` and user `.glsl` folder
- [ ] Video without fal key returns an error that names fal **and** Share
- [ ] `rg -c "â|Ã|Â"` = 0 on touched files

### 10.10 Out of scope

Hosted Joy credits. OpenArt MCP. Native function-calling array. Music gen. Changing `generate_slide`. WASM/native packs. iOS. Other agents' reports.

---

## 11. Competitive map (where to act)

| Weakness out there | Joy move | Where |
|---|---|---|
| Credits + subs | BYOK, never meter | Settings |
| AI does not edit | Keep tools; later one-shot card | Executor |
| Gen FOMO | Stills now, short B-roll via fal/share | MediaRouter |
| "I pay for ChatGPT" | Honest API vs Plus + Share | Settings copy + intent |
| Locked template mall | User-owned packs | PackScanner |
| One-API hostage | Chat ≠ pixels; fal/Replicate/Custom | MediaRouter |
| Cloud | Project folder is the document | Bundling spec, already |

---

## 12. Sources

Internal: `AIToolExecutor.java`, `ChatAssistantActivity.java`, `GlExternalTransitions.java`, `SpriteSheet.java:18`, `importInsertedAsset` ~39518, `TextOverlayItem.createImage`, `AssetResolver`, `BRollBucket`, `PLAN_QUICKWINS_20260702.md`, `handoff.md:1725`, `road_map.md:314`, `PLAN_SPRITE_ANIMATION.md`, `PLAN_filters_color_text_transitions.md`, `feature-visualizer-studio-spec.md`, `feature-ai-generated-slides-spec.md`, `LAUNCH_STRATEGY.md`, `DESIGN_JOY_CREATOR.md`, `SPEC_20260829_PROJECT_BUNDLING.md`, `ASSETS_WISHLIST.md` #12.

External 2026: OpenRouter Image API docs; fal vs Replicate (TeamDay, ~50%/44% gen-media share); Eden AI on OpenRouter Image vs fal; OpenArt MCP-only (`https://mcp.openart.ai/mcp`); CapCut/Canva/KineMaster reviews as in v1.

---

## 13. Review

v1 was right that Joy cannot take CapCut's mass market on word of mouth, and right that Plus ≠ API. It was too OpenRouter-centric and it left Tier-4 packs as a ghost. The edge is three-layered: **hands, swappable backends, file-owned packs**. P0 for packs is a scanner on top of code that already loads dropped `.glsl` and versioned HTML. That is not a new runtime. That is naming the host.

JoyRaptor decisions: (1) OpenRouter stills default, fal for video when keyed — yes/no. (2) Pack P0 in the same milestone as image gen — recommended yes, it is smaller than it sounds. (3) OpenArt native OAuth later.
