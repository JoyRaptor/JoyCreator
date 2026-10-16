# JoyBot — the plan (2026-10-14)

**Owner:** JoyRaptor. **Lane:** JoyBot specialist (Claude, cross-cutting account). **Brief:** `JOYBOT_SPECIALIST_BRIEF_20261014.md`.
This file is the source of truth for JoyBot. Part A is for JoyRaptor; Part B is for the agents building it.

---

# PART A — what JoyBot becomes (plain words)

## The one-sentence version

JoyBot is the studio's helper who **notices what you're doing, offers the next step as a button, does it
instantly when he can (even offline, even free), shows you exactly what changed with a one-tap Undo, and only
reaches for a paid AI when the job really needs one — and tells you the price first.**

## What it feels like

1. **He has an idea.** You finish a recording and go back to the lobby. JoyBot's green glow slowly breathes —
   no sound, no badge, easy to ignore. Tap him.
2. **He flies down and says it.** "Hey, I noticed *Interview 3* has speech but no words yet. Want me to transcribe
   it?" Under it: **[Transcribe this]** **[Add captions]** **[Something else?]** — the first one green because
   it's his best guess.
3. **One tap does it.** Transcribing runs on the phone (no internet, no key). When it's done he shows a card:
   what changed, **[Undo]**, **[Show me]**. The next buttons are already there: **[Cut the ums]** **[Make chapters]**.
4. **You can always just talk.** Hold the mic, say "make the music quieter under my voice", let go — it sends.
   Or type. Or tap a button. All three land in the same place.
5. **He makes things.** "Help me make a title" → he asks three quick button questions (what kind, what it says,
   what mood) and builds an animated title card. Pictures can be *designed* (free), *generated* (paid, price
   shown first, about 1½¢), or *made in your ChatGPT/Gemini app* and brought back with Share. When a picture lands
   he flies up to it, shows it off, and flies back.
6. **He knows the whole studio.** Ask "how do I key out a green screen?" and he answers for whichever wing it lives
   in, with a **[Take me there]** button.
7. **Every JoyBot change is its own undo step.** Three requests = three undo steps, each named in plain words.

## The rules he lives by

- **He never invents a number.** Every "I noticed…" is computed from your project, not guessed.
- **Buttons first, AI second.** If something can be done on the phone, a button does it directly: instant,
  free, works on a plane. The AI is called only for things that need judgment or writing.
- **Quiet.** At most 3 ideas + "Something else?". A waved-away idea stays quiet for that project. Ignored twice →
  quiet. No sounds, no vibration, no badges. Reduced-motion → a still tint instead of a breath.
- **Honest about money and limits.** Paid things show the price before they run. If the free service is busy he
  says so in one plain sentence and offers to try again.
- **Your art is the brightest thing.** Old black, zinc greys, flat pills; colour only to guide. Every button has
  a hover label.

## Offline, free, and paid — what works where

| | No internet / no key | Free key (openrouter/free) | Paid model / picture model |
|---|---|---|---|
| Idea buttons (transcribe, cut ums, silences, captions, chapters, transitions, reframe, export draft) | ✅ all | ✅ all | ✅ all |
| Undo / Show me | ✅ | ✅ | ✅ |
| "How do I…" help with Take-me-there | ✅ built-in answers | ✅ smarter answers | ✅ |
| Typed requests ("cut the first 10 seconds") | ✅ common ones understood | ✅ anything | ✅ |
| Title / chapter / quote / list / credits slides | ✅ built-in designs | ✅ AI-designed | ✅ better designs |
| Pictures | ↗ hand off to your ChatGPT/Gemini app | ↗ hand-off (designed HTML/SVG graphics not built yet — hand-off item 10) | ✅ generated pictures (~1½¢ each) |
| Look at a frame / find matching b-roll | — | ✅ (max 2–3 pictures per ask) | ✅ |

## JoyBot as a character (your sprite sheet)

He is built as **moods** — idle, listening, thinking, working, talking, excited, showing-off, oops — and
**places** — his corner, beside a new picture, home. Today each mood is played with the two stills you drew
(breath, dip, smile). When you load his labelled sprite sheet, a mood plays the animation with the same name
automatically. Name your animations with those words (or tell me yours and I'll map them).

## Build order (each phase is usable on its own)

| Phase | What you get | Status |
|---|---|---|
| **1. He notices** | Idea buttons in chat, the breathing glow (lobby + editor), buttons that run instantly (offline OK), result cards with Undo + Show me, one undo step per JoyBot change, sturdier chat (no stuck "Thinking", a Stop button, always-fresh project info), moods/places with the stills | ✅ on the phone |
| **2. He builds with you** | Slide wizard (5 kinds × 5 moods, offline designs), offline understanding of common requests, studio-wide "how do I" help with Take me there, hold-to-talk mic | ✅ built (mic = dictation; phone test owed) |
| **3. He makes pictures** | Design / generate / hand-off, lands in your Joy Creator folder + timeline as one undo, b-roll finder that reads what you say, show-off flight | 🟡 make + place work; b-roll + show-off flight owed |
| **4. He's everywhere** | SpriteLab, Avatar, Library and JoyBrush (via the JoyBrush lane) with their own ideas; optional read-aloud; your sprite sheet loader | 🟡 read-aloud + sheet loader built; rooms owed |

## Questions only you can answer (no rush — I picked a default for each)

1. **Read replies aloud?** Default: **off**, one switch in JoyBot settings.
2. **Default picture model when paying:** FLUX.2 Klein (~1½¢, fast) vs Gemini "Nano Banana" (~4¢, better at
   lettering in pictures). Default: **Klein**, changeable.
3. **Price check:** always ask before a paid picture? Default: **yes**, with a "don't ask under 5¢" switch.
4. **Animation names** for your sprite sheet: idle · listen · think · work · talk · excited · show · oops — OK?

---

# PART B — engineering

## B1. What exists (verified 2026-10-14, see the three scout reports in this session)

- `ai/ChatAssistantActivity` (1,960 lines): streaming SSE chat; tool calls are a reply that IS a JSON object
  `{"tool":…,"args":…}` (fragile: prose + JSON is not executed); project context is written ONCE into the system
  prompt at chat open (stale after the first tool; whole word-level transcripts inlined = huge prompts); vision is
  a separate non-streaming path; offline = keyword FAQ; proposal cards (narrative, b-roll, avatar rig).
- `ai/AIToolExecutor` (3,542 lines, ~55 tools): load → mutate → `storage.save` → `AIChatState.signalModified`.
  Results are technical strings ("clip 1a2b…"). `export_project` does not export. `generate_slide` has an offline
  template fallback (`SlideContract.buildFallbackHtml`).
- Undo: editor saves on pause (`saveProjectNow(true)`), so disk == editor state when chat opens. On resume, if
  `AIChatState.projectModifiedByAI`, it records **one** `UndoManager.recordAiCheckpoint` (whole chat session =
  one step), swaps the project, `invalidateActionsForProjectSwap`, `resetBaseline`. Snapshot format is
  `ProjectStorage.toJson(project)` — the chat can produce the same string from disk.
- Share-in: `joyfolder/studio/ShareInActivity` saves into the Joy Creator folder (`JoyFolder.prepareDir`,
  `addFile`, `Fingerprint` de-dupe) and `ShareInbox.put(projectId, uris)`; the editor's `takeSharedFiles` inserts
  at the playhead with the asset-browser path = one undo. **Generated pictures reuse this exactly.**
- On device: Vosk (2 models) + whisper.cpp (base.en), English only; SilenceDetector, BeatDetector, OnsetDetector,
  LoudnessAnalyzer, MediaPipe face landmarker, TFLite EfficientDet. No TTS, no on-device LLM (parked on roadmap).
- Joybot: `ui/lobby/JoybotView` (stills, hover, react/smile, orb, `JoybotFilm` contiguous-range sheet player).
  Lobby: `openAssistant()` passes the newest project; `paintBotLine()` already embodies "never invent a number".
- OpenRouter: no free image models. `POST /api/v1/images` {model, prompt, aspect_ratio, …} → `data[].b64_json`.
  Cheapest `black-forest-labs/flux.2-klein-4b` $0.014/MP. Gemini image models also via chat completions.

## B2. Architecture

New package `com.fadcam.ui.faditor.ai.joybot` (pure logic first, Android glue second):

| Class | Pure? | Job |
|---|---|---|
| `ProjectFacts` | ✅ | Plain snapshot of what JoyBot may say about a project (counts, durations, flags). Built by `ProjectFactsReader` (Android) from `FaditorProject`. Every field is a measurement, never an estimate. |
| `Idea` | ✅ | id, label (chip), say (the user-bubble text), opener ("I noticed…"), hint (tooltip), `Action` (DIRECT tool chain / ASK the model / WIZARD), priority. |
| `IdeaEngine` | ✅ | `List<Idea> ideasFor(ProjectFacts, Room, Quiet)` — the brief §1 decision tree, ranked, ≤3. JVM-tested. |
| `IdeaMemory` | Android | Per-project seen / dismissed / ignored counts (SharedPreferences). Drives the pulse and quieting. |
| `JoybotSteps` | Android | Before-snapshot + plain label per JoyBot change; chat Undo pops one; editor replays the rest as N undo entries. |
| `DirectRunner` | Android | Runs an Idea's tool chain through `AIToolExecutor` without the model, then writes a plain-words result. |
| `JoybotMood` / `JoybotActor` | Android | Moods + places on `JoybotView`; `setHasIdea` pulse; sprite-preset lookup by mood name. |

Chat changes (`ChatAssistantActivity`): chip row above the input; opener message when launched from a pulse
(`EXTRA_OPEN_IDEA`); result card (what changed · Undo · Show me); Stop button; tolerant tool-call parsing (JSON
object with a `tool` key anywhere in the reply, prose kept); project context sent as a **fresh compact summary
each turn** (transcripts only via `get_transcript`, or inline when short); `EXTRA_ROOM`.

Editor change (`FaditorEditorActivity`, other lanes active — keep to the AIChatState block + one pulse hook):
replay `JoybotSteps` as one undo entry per step; pulse on `btn_ai_assistant`.
UndoManager: `recordAiCheckpoint(description, snapshotBefore)` overload.

## B3. Idea table v1 (refine with JoyRaptor)

| Signal (ProjectFacts) | Idea → action |
|---|---|
| empty project | Start from a recording (route) · Make an intro slide (wizard) · Make a picture (phase 3) |
| speech-capable clip (has audio, not muted, ≥5 s), no transcript | **Transcribe this** → `generate_transcript` (whisper if downloaded else vosk) · Add captions → transcribe + `toggle_captions` |
| transcript exists, captions off | Turn on captions → `toggle_captions` · Pick a caption style (ask) |
| transcript exists, fillers not cut (struck words or um/uh tokens outside removed spans) | **Cut the ums** → `cut_all_fillers`; when nothing is marked yet → `ai_enhance` |
| silence candidates not cut / long gaps | Remove silences → `remove_silence` |
| transcript total > 3 min | Make chapters → `auto_chapters` · Find the best 60 seconds (ask, phase 2) |
| ≥3 clips, 0 transitions | Add gentle transitions → `add_transition` CROSS_DISSOLVE between each |
| no title (first clip not a slide, no text overlay in first 3 s) | Make a title card → wizard |
| canvas 9:16 but footage landscape | Reframe for vertical → ask |
| audio-only project | Make a visualizer (ADD_VISUALIZER) · Lyric captions (ask) |
| b-roll bucket has assets, none placed | Suggest b-roll spots → `suggest_broll_placements` (needs key) |
| sprite sheet without names / animations | Name the cells · Make a walk cycle (ask, key) |
| never exported (no record) | Export a draft (720p) → route to export |
Always last: **Something else?** (focuses the text box). At most 3 ideas before it.

## B4. Test plan

- JVM: `tools/jvm-harness/IdeaEngineTest.java` (+ `run-ideas.sh`) — every row of B3, ranking, cap of 3,
  quieting, "never a number that isn't in the facts". Pure `JoybotIntent` (phase 2) tests likewise.
- Parse check: `tools/javacheck.sh` on every touched file; the watcher builds (`.\tools\phone.ps1 build`).
- Device (Note 9, only after asking JoyRaptor): brief §4 script + chips + pulse + undo per step.

## B5. Coordination

- `FaditorEditorActivity`, `LobbyFragment`, `StudioMirror` have other lanes' uncommitted work (Joy folder lane).
  Touch only the AIChatState reload block, the AI-button hook and Joybot lines; stage exact paths only.
- JoyBrush: no edits under `joybrush/` or `joybrush-android/`. Phase 4 ships a brief to the JoyBrush lane.
- Claimed in `tasks/LANES.md` as `JOYBOT_20261014`.

## B6. Ledger

### 2026-10-14 — built (compiles; whole-app typecheck OK; real Gradle build OK 15:36; 108/108 JVM tests). NOT yet tried on the phone.

| Item | State | Where |
|---|---|---|
| Idea engine (decision tree, ≤3 + Something else, quiet, offline filter, never-invent test) | 🔨 built, tested off-device | `ai/joybot/IdeaEngine`, `ProjectFacts`, `ProjectFactsReader`, `Idea`, `IdeaMemory` |
| Idea buttons in chat; tap runs on the phone (no model) with a plain-words result card | 🔨 | `ChatAssistantActivity` (JOYBOT section), `DirectRunner`, `Outcome`, `JoybotChatViews` |
| One undo step per Joybot request (chat Undo, "undo that", editor gets N named steps) | 🔨 | `AIChatState` steps, `UndoManager.recordAiCheckpoint(desc, snapshot)`, editor reload block |
| Glow when he has an unseen idea (lobby Joybot + editor button), reduced-motion still tint | 🔨 | `JoybotView.setHasIdea`, `JoybotPulse` |
| Moods + places (fly from header to corner, fly to results, back); sprite sheet loader | 🔨 | `JoybotView` moods/flyTo, `JoybotFilm` frame lists, `JoybotSheet` (`<Joy folder>/Joybot/joybot.png` + `.sprite.json`) |
| Sturdier chat: tool command anywhere in a reply, words shown never JSON, Stop, fresh compact project context each request, plain errors (429/401/402/offline), per-project conversation | 🔨 | `ChatAssistantActivity` |
| Slide wizard (4 questions) + 25 offline designs (5 kinds × 5 moods), previewed in a browser | 🔨 | `SlideFlow`, `slides/SlideDesigns`, `AIToolExecutor.builtInSlide` |
| Tools fixed: transcribe downloads its model + scales timeout; remove_silence really cuts; cut_all_fillers also cuts plain um/uh; new add_visualizer; chapters ≤12 real breaks; vision tagging paced on free models | 🔨 | `AIToolExecutor` |
| Hold-to-talk (speak, let go to send), tap-mic auto-sends, optional read-aloud (off by default) | 🔨 | `JoybotVoice`, settings checkbox |
| Studio-wide help with Do it for me / Take me there (labels from FaditorToolRegistry) | 🔨 | `HelpBook`; routes incl. JoyBrush library, Setup |
| Pictures: make here (price from OpenRouter catalogue on the button), or free via your ChatGPT/Gemini app + Share back; saved in the Joy folder; "Put it in my video" = share hand-off (one undo) | 🔨 | `JoybotPictures`, picture flow in chat, settings "Picture model" |
| MainActivity.onNewIntent → tab switching works when the app is open | 🔨 | `MainActivity` |
| typecheck.sh now compiles studiokit + resolves JoyBrush jars | ✅ verified (822 sources) | `tools/jvm-harness/typecheck.sh` |
| JoyBrush hook | 📝 brief written | `BRIEF_FOR_JOYBRUSH_LANE_20261014.md` |

### 2026-10-14 15:41–15:56 — Note 9 test (owner approved; phone lock claimed/released; project "Restore video test" left as it was)

| Check | Seen on the phone |
|---|---|
| ✅ Lobby → Joybot opens with an opener | "4 of your clips have sound but no words yet. Want me to transcribe them?" + Transcribe all 4 (green) · Add captions · Turn on captions · Something else? |
| ✅ Brief §4.1 "hi" streams | "Thinking…" with Stop, then "Hey there! … 'Restore video test' with 7 clips and a few sprite sheets…" (fresh context) |
| ✅ Direct button + result card | Turn on captions → "Captions are on." [Undo] [Show me]; next ideas updated (Add gentle transitions) |
| ✅ Chat Undo | "Undone." card retired, "Undone — it's back how it was.", Turn on captions offered again |
| ✅ Help book | "how do I add captions" → instant answer naming Captions in the tool strip + Do it for me / Take me there |
| ✅ Slide wizard | What's it for → Title → Use "Restore video test" → Cinematic → "Added … The video is 4 s longer (3:16 → 3:20)"; preview shows the wide-spaced serif title |
| ✅ Editor undo history | "Joybot: Make a cinematic title…" as its own violet step; editor Undo → "Undid: Joybot: Make a cinematic title slide at the start", 3:16 |
| ✅ Picture (≈1½¢ spent) | "Make it here — about 1½¢" / "Ask my ChatGPT or Gemini (free)"; cat astronaut over the moon in ~20 s, saved in the Joy folder; Put it in my video → 5 s picture clip at the playhead (3:21), editor Undo → 3:16 |
| ✅ Brief §4.4 vision | frame at 1.50 s → "I see a white cat with black spots curled up and sleeping soundly on a brown textured couch…" |
| ✅ Brief §4.5 bad address | "I can't reach the AI service — check the internet, or the address in ⚙. The buttons below still work offline."; cleared → "Hello! How can I help you today?" |
| ⏳ Brief §4.2/4.3 transcribe + chained tools | not run (transcribing 4 long clips takes minutes); Transcribe button untested on device |
| 🐞→✅ fixed after the test | messages scrolled over the title bar (fly-in clipping); key shown in plain text in settings (now dots + hold-to-show, verified); "1 file is missing" for a just-made slide (ProjectIntegrity now skips slides); prefilled words lingered after wizard/vision; no buttons after a picture; "Added 1 clip" → "Added a slide" |
| 🐞 handed off | editor preview keeps showing a removed slide after a snapshot undo (task chip spawned; editor lane) |

### 2026-10-14 evening — owner feedback round (built, type-checked, 118 JVM tests; not yet on the phone)

| Owner said | Done |
|---|---|
| Closing the chat stops a long transcription | Jobs run on an app-wide worker under the foreground notification (`JoybotJobs`); results wait for the chat ("While you were away…"). While the editor is on screen Joybot writes INTO the editor's open project (`JoybotStorage` + `AIChatState.LiveEditor`, editor `joybotLive` adopts as one undo step per request) so nothing you edit meanwhile is overwritten; transcription re-reads the project when it saves |
| Captions need a transcript first | `toggle_captions` transcribes first when there are no words |
| Ask "in line or on top" when inserting, and explain | Picture card: **In line** (clip, video longer) / **On top** (new layer via the editor's "Image as new layer"); slide wizard asks the same for every kind (overlay slides are see-through designs); persona tells the model to ask |
| Where does an undone picture live? | Unchanged on disk: beside the project's file in the Joy folder, else Assets, else the top. The card now names the folder and file |
| "2 s fade in/out on all clips" failed; fades vs transitions | New `set_fades` (fade handles on clips, layers, sprites, visualizers, audio), `remove_transitions` (all / fades only / seams), context lists each item's fade handles and every transition by seam; persona explains the difference and asks when unclear |
| Mic sends on every breath | Mic is dictation: tap to start (solid cyan), words collect across pauses, tap to stop, YOU press Send |
| "Transcribe all 4" — what, where? | Progress names each clip "(2 of 4)"; result lists each clip with its word count; from the lobby every result has **Open in Studio** |
| Model switching with stars, free / vision / picture badges, talk to a picture model | `ModelCatalog` + `ModelPicker` (search, ★, FREE · 👁 · 🎨, Chat / Picture tabs); tap the model name in the chat header for the starred quick switcher; picking a picture model makes every message a picture (price shown) |
| Read-aloud toggle | Speaker button in the chat header (plus the settings checkbox) |
| "Cut this so it ends here" did the wrong thing | The editor now tells Joybot what's selected; new `set_item_time` retimes ANY item (spine clips are trimmed); "this" = selected, "here" = playhead in the persona |
| Name clips/layers ("Cat 1", "Cat 2") | Design + edge cases: `NAMING_DESIGN_20261014.md` (trap: `Clip.displayName` is the relink/auto-blank memory — needs a new field). Three questions for the owner |

**Known limits:** button jobs now survive closing the chat, but a request the MODEL is carrying out (several
tools chained by the AI) still stops at the step in progress when the chat closes. SpriteLab / Avatar don't yet
open Joybot with their room. "Design a picture" (free HTML/SVG graphic) is not built — the picture flow offers make
here / your own AI app.

### 2026-10-15 — phone results + hand-off

Gradient UI, "make this end here" (local, exact), undo surviving a reinstall, the In line / On top question and a
long job finishing after the chat closed (adopted by the editor as one undo step) all 🟢 on the Note 9.
🔴 Found: an On-top (overlay) HTML slide does not draw in the editor preview. Full results, the bug trail and the
ordered roadmap for the next agent: **`JOYBOT_HANDOFF_20261015.md`**.

### 2026-10-15 evening — F2 registry, Director mode, big projects (compiles; whole-app typecheck OK 850 sources; 325 + 43 harness; NOT on the phone)

Built by the Joybot lead (core, engine, review) with a swarm of Haiku agents (each family, the chat cards, help, rooms, G5),
every agent report checked against the code; brief for future families: `ACTION_FAMILY_BRIEF_20261015.md`.

| Item | State | Where |
|---|---|---|
| **F2 action registry**: each action declared once (typed params → exact JSON Schema for native tools, numbered text for the fallback, plain-words argument errors); handlers change the loaded project, the host saves once (= one undo step per request); names accepted as well as ids; one bad family can't take the others down | 🔨 | `ai/actions/` ActionSpec, ActionRegistry, Args, ActionResult, ActionError, StudioCtx, StudioActions; `AIToolExecutor.executeTool` asks the registry first, old tools fall through |
| Families (25 actions): text look (font/colour/size/outline/shadow/glow/plate/opacity, words, 11 animations, list_fonts) · transitions (whole GL catalogue, any cut or all) · audio (**ducking is real** — "music quieter under my voice", needs the voice's transcript; volume points; pan + voice clean-up) · captions (look, placement, motion — per-track draft like the drawer) · transform (move/size/turn/mirror/fade, clip crop) · organise (duplicate, hide/lock, row up/down) · slides (get_slide/set_slide, in line and on top) · looks (grade, blend mode, green screen, saved looks) · inspect (list_items, get_item, find_words) | 🔨 | `ai/actions/*Actions.java` |
| Held back: markers (live in bookmarks.json, the host can't save them yet) | 📝 | `OrganiseActions.MARKERS_PERSIST` |
| **F3 Director mode**: `propose_plan` → plan card (Go ahead / Not now) → run with 40 steps → Review card per change (Show me, Undo from here = put back before that change, as one editor undo step) | 🔨 | `actions/AgentTools`, `JoybotAgent` (Plan, Change, revertTo), `ChatAssistantActivity` (renderFinishedExtras) |
| **F4 budgets**: per-request step budget; Keep going; "N steps · tokens · about $x" line (OpenRouter reports cost); job file each round → "The phone stopped me… [Carry on] [Leave it]" | 🔨 | `JoybotAgent`, `joybot/JobFile` |
| **F5**: context says it's a summary and names list_items / get_item / find_words | 🔨 | `JoybotAgent.Run.context()` |
| M3 rooms: Joybot buttons in SpriteLab and Library (+ "Show my newest recording") | 🔨 | SpriteSheetEditorActivity, RecordsFragment, IdeaEngine |
| G3 help: 61 topics; 4 old answers named buttons that don't exist — corrected | ✅ harness | `HelpBook` |
| G5: slide writer, narrative, b-roll, vision tagging adapt to services that refuse max_tokens/temperature | 🔨 | `joybot/AiRequest` |
| Editor: in-line slide preview forgets its cached slide on a project swap (a Joybot set_slide keeps the clip id) | 🔨 | `FaditorEditorActivity.swapInProject` (1 line) |

**Phone checks owed (in this order):** "change the font of the hello text to Bold Serif and make it yellow" (one undo step) ·
"make the music quieter under my voice" on a project whose voice clip has words · a big ask ("add a gentle transition at
every cut and a chapter title at each topic") → plan card → Go ahead → Review → Undo from here on the 2nd change ·
force-stop the app mid-job → reopen chat → Carry on · SpriteLab Joybot button → "name the cells" → back in SpriteLab the
names show and Save keeps them.

### 2026-10-15 night — phone rounds + F7/F8/F9 + more families (typecheck OK 855 sources; 362 + 43 harness)

**Phone (Note 9, owner OK'd installs; "Restore video test" restored to its 32,947-byte file each round):**
🟢 text font + colour = one undo step · 🟢 plan card (free models read the project, list transitions, then propose) ·
🟢 plan survives closing the chat · 🟢 Carry on after a force-stop · 🟢 usage line ("2 steps · 71k tokens · free").
Bugs the phone found, all fixed in code: false "Changed: … Undo" after read-only steps (now gated on Joybot's own
writes, `JoybotStorage.writes()`) · plan and Review cards lost on reopen / after Show me (kept per project) · endless
"Thinking…" on a stalled free model (75 s watchdog) · "Done!" after three failed tool calls (honest failure line) ·
label_sprite_cells rejected list-shaped labels (now takes every shape) · **the three sprite tools never raised
AIChatState.signalModified** (SpriteLab now reloads on Joybot's write count) · "Undo from here" refused after the
editor's plain re-save (fingerprint ignores `lastModified`) · Review rows without Show me for legacy tools (ids diffed).

| Built this round (not yet phone-proved unless the final pass says so) | Where |
|---|---|
| F7 recipes: Save as a recipe, My recipes chip, Run (offline when portable), Delete to Trash | `joybot/Recipe`, `RecipeStore`, chat |
| F8 Show me how: 28 help topics name their strip button; the editor rings it | `HelpBook.Topic.tool`, `AIChatState.showHowTool`, `FaditorEditorActivity.showHowTool()` |
| F9 fallback to openrouter/free on 429/5xx/"no endpoints"/retired id (never paid, never 401/402/403) | `JoybotAgent.Run.shouldFallBack` |
| Families: export_video (card → Export now, same start as the Studio) · list_media / add_media · check_project / relink_missing | `actions/ExportActions`, `SourcesActions`, `ProjectCareActions` |
| G2 hover labels: 93 controls, 50 files outside the editor; About back arrow now says "Back" | ui/**, lobby |

### 2026-10-15 late — final phone passes (Note 9; last install 20:57; phone released; "Restore video test" back to its 32,947-byte file)

| Check | Result |
|---|---|
| SpriteLab → Joybot names 16 cells → names on the grid, survive Save + reopen | 🟢 (log: "back from Joybot: 1 write(s)" → "Joybot reload: sheet … found") |
| Director: plan → Go ahead → Review: Show me on every row, jumps to the item, card back after reopen | 🟢 |
| Undo from here on row 2 → "Put back how it was before …", rows 2+ grey, still grey after reopen | 🟢 |
| Recipe: Save as a recipe → My recipes → Run (no model, ~5 s) → Undo → Delete | 🟢 |
| Show me how: "change the speed" → the editor rings the 1x Speed button | 🟢 |
| Export card: "720p · Medium quality · full video", Not now → "Not exported." | 🟢 card; the "Done." under it fixed after (not re-run) |
| Carry on after force-stop · plan survives reopen · no false Changed card · usage line | 🟢 (earlier passes) |
| Not phone-tested: hands-free voice loop (M7, needs his voice), F9 fallback + borrowed eyes, add_media / relink / list_items, ducking (needs a music+voice project), markers (add_marker held until markers join undo) | 🟡 |

Also built this session after the first ledger entry: M7 hands-free (hold mic → auto-send → reply read aloud → "yes / undo / stop / no"),
F9 borrowed eyes (a text-only model's picture step goes to the free router), markers persist via the sidecar (list_markers on; add_marker
off until undoable), G2 labels for the editor, drawers and SpriteLab (≈ 130 more controls). Lessons: tasks/lessons.md (CRLF, pretty JSON).
