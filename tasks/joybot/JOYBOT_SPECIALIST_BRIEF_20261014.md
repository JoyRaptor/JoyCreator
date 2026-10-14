# Brief — Joybot specialist: suggestions, the pulse, and what the AI can do

**For:** the Joybot specialist session the owner spins up. **From:** the cross-cutting Claude account (2026-10-14).
**Read first:** this file, then `app/src/main/java/com/fadcam/ui/faditor/ai/` (ChatAssistantActivity, AIToolExecutor,
AiKey, AiService, ModelCapabilities). Ask before touching anything under `joybrush/` or `joybrush-android/`.

## 0. Where the assistant is today (all on joy-creator)

| Piece | State |
|---|---|
| Chat (`ChatAssistantActivity`) | Replies **stream** word by word (`streamCompletion`); a reply that is a tool command shows "Working…" while it streams. Up to 4096 tokens. |
| Tools (`AIToolExecutor`, ~60) | Transcripts (generate, correct, retime, merge, cut fillers, `ai_enhance`), silence, clips (split, delete, speed, mute, zoom, auto_zoom, reorder, rename), captions, text overlays, transitions, keyframes, b-roll (list, add, suggest, apply), narrative (analyse, apply), **generate_slide** (HTML/CSS/GSAP → frames), sprites (describe, grid, label, author animation), avatar rig, audio (volume, fade, remove_silence, fix_audio), describe_clip, export, health_check, auto_chapters. The model sees them as the numbered list in the system prompt near the top of `AIToolExecutor`. |
| Safety | Tool chain capped at 8 steps per message (`MAX_TOOL_ROUNDS`). Key encrypted (`AiKey`, Android Keystore). |
| Service | OpenRouter by default (`openrouter/free`), or any OpenAI-compatible address in settings (`AiService`). |
| Offline | No key and no address → `handleOfflineQuery` (canned help). |
| Joybot himself | `ui/lobby/JoybotView` (orb gradient `setOrb`, `react()`, `watch(scrollable)`, film playback `JoybotFilm`). **The owner is drawing his sprite animations** (talking, activities). Leave hooks for them; don't draw your own. |

The owner has pasted a working key on the Note 9 (`com.fadcam.beta`). Never print, log or echo it.

## 1. Suggestions that show up in the chat ("our little eager AI helper")

Owner, 2026-10-14, in short: buttons should come up as **automatic suggestions about the next likely step**. A long
transcription → "cut the ums". Nothing transcribed → "transcribe this". Several buttons, and they can always type
instead.

**Build it as a local decision tree, not an AI call.** The project state decides which 2–4 chips to offer; the model is
only called when a chip is tapped (the chip sends the same text a person could type, so it lands in the existing path).
That keeps it instant, free and honest — Joybot's rule: "it never invents a number."

Signals → chips (a starting table; refine with the owner):

| Signal (from `get_project_state` / the model) | Chips |
|---|---|
| Video clip with speech, no transcript | **Transcribe this** · Add captions |
| Transcript exists, fillers detected/unstruck | **Cut the ums** · Tighten the pauses |
| Transcript exists, long (> ~3 min) | Make chapters · Find the best 60 seconds |
| Captions off but transcript exists | Turn on captions · Pick a caption style |
| Long silent gaps (`detect_silence`) | Remove silences |
| No title / first clip starts cold | Make a title card (→ slide wizard, §3) |
| Audio-only project (music/voice) | Make a visualizer · Lyric captions |
| Many clips, no transitions | Add gentle transitions |
| Vertical export preset but horizontal footage | Reframe for vertical |
| Project never exported | Export a draft (720p) |
| B-roll bucket has assets, none placed | Suggest b-roll spots |
| Sprite sheet without names/animations | Name the cells · Make a walk cycle |
| Empty project | Start from a recording · Make an intro slide · Generate an image |

Rules: at most 4 chips; the most useful first; a dismissed chip stays quiet for that project; "Something else?" is
always last and just focuses the text box. Chips follow the studio look: flat zinc pills, the studio green only on the
first (the one Joybot thinks is best). Every chip gets a hover label (owner rule).

## 2. The pulse ("he's got a good idea")

When the tree has a suggestion the person hasn't seen, Joybot's orb **breathes**: the studio green gradient behind his
icon, slow and calm (about a 4 s cycle, low alpha), so it is easy to ignore. Tapping him opens the chat already saying
"Hey, I noticed you're doing X. Would you like me to Y?" with the chips under it.

- Where: the lobby (`JoybotView` in `LobbyFragment`) and the editor's Joybot button.
- Never sound, never vibrate, never a badge count. Stops after the suggestion is seen or dismissed; ignored twice →
  that suggestion stays quiet.
- Build it as a state on `JoybotView` (e.g. `setHasIdea(boolean)`) so the owner's sprite films can play over it later.
- Respect reduced motion: a still tint instead of a breath.

## 3. What the AI could do — brainstorm (✅ exists, 🔨 needs building)

Make things without transcripts:
- 🔨 **Generate and insert an image** (roadmap 3.10, OpenRouter image models) — onto the timeline or as b-roll.
- ✅/🔨 **Slide wizard:** "help me make a slide" → Joybot asks what it's for (title, chapter, quote, list, credits),
  what it says, the mood → `generate_slide`. The asking part is new; the slide engine exists.
- 🔨 Title / end card from the project name; 🔨 cover/thumbnail picked from the best frame (`describe_clip` exists).
- 🔨 Colour look presets ("warmer", "film", "match these two clips").
- 🔨 Reframe for vertical (face/subject tracking via `describe_clip` on sampled frames).
- 🔨 Music bed suggestions from the mood; 🔨 ducking under speech (`set_audio_volume` exists).
- ✅ b-roll suggest/apply · ✅ chapters · ✅ narrative re-order · ✅ zoom punch-ins.
- 🔨 "Find the best 60 seconds" social cut (narrative tools + a length budget).
- 🔨 Captions from a lyric sheet (paste lyrics → align to the song; `retime_words` exists).
- 🔨 Rename my clips/assets sensibly (✅ `rename_clip`, `rename_asset` — a batch "tidy names" chip is new).
- Sprites/avatars: ✅ describe/label cells, ✅ author animations, ✅ rig. 🔨 "make the in-betweens".
- JoyBrush (coordinate with the JoyBrush lanes): 🔨 name my drawing, 🔨 palette from a reference picture, 🔨 suggest a
  paper, 🔨 turn a drawing into animation frames, 🔨 describe what I drew (vision models).
- 🔨 Voice commands through the mic button that already exists ("cut from here", "louder").

Prioritise by value per hour: the chips (§1) and the slide wizard first, then image generation.

## 4. Test with the real key (owner asked for this)

On the Note 9, Joybot chat:
1. "hi" → words stream in; no frozen "Thinking…".
2. "transcribe the first clip" → "Working…" then a tool result card, then a summary.
3. A request that chains tools ("transcribe, then cut the ums") → finishes, or stops at 8 steps with the sentence.
4. Attach a frame (image button) → the model answers about the picture (vision).
5. Settings: put a bad address → a plain network error; clear it → OpenRouter again.
Report each with the actual text shown, not what you expected.

## 5. House rules that bite

- Never run gradle; the watcher builds (`.\tools\phone.ps1 build`). Never `adb uninstall`.
- Ask before installing while the owner might be on the phone (Note 9 unlocked).
- One press, one undo. Tooltips on every button. Old black, zinc greys, flat pills; colour guides.
- Report to the owner in plain words with an emoji status table; he is an artist, not a developer.
