# Joybot — feature gaps, tiered (2026-10-15)

**Purpose:** everything on the table, sorted by who should build it. Owner picks the order.
**Tiers:** 🧠 **Frontier** = architecture / judgment / design calls a strong model must make (and own).
⚙️ **Mid** = clear spec exists or follows from a frontier decision; a capable model builds it.
🐜 **Grunt** = mechanical, checkable, low-risk; cheap agents can swarm it.

The yardstick is the owner's vision: Joybot is the harness that lets any AI see the whole project, act the way a
person would in ANY part of the app, and run anything from one button to a whole headless job — for people who aren't
technical, and as a co-pilot for power users. Examples: label a sprite sheet's expressions; read the transcripts and
place matching B-roll and chapter titles on its own judgment; then "change the font on that slide".

## 🧠 Frontier

| # | Gap | Why it matters (user side) | Today |
|---|---|---|---|
| F1 | ✅ **DONE 10-15 (260f399cf), phone-tested on 3 free models** — **Native tool calling** (the model API's own `tools`, not JSON typed into a reply) | The #1 reliability gap: free models "describe instead of do", leak tokens, mangle JSON. OpenAI/Anthropic/Google all support native tools; OpenRouter passes them through. Keep JSON-in-text as the fallback for models without it. | JSON-in-text only, with nudges as patches |
| F2 | 🔨 **BUILT 10-15 evening (compiles, not phone-tested)** — 9 families, 25 typed actions, see JOYBOT_PLAN B6 — **One action registry for the whole app** — every person-action (each drawer, sheet, button, in every wing) declared once with name, args, undo, and plain-words help; Joybot's tools, offline buttons, macros and help all read it | "Anything the user can do" can't be met by hand-writing 66 switch cases; it also gives discovery ("what can I do here?") for free | `AIToolExecutor` switch, ~66 tools, Studio-centric |
| F3 | 🔨 **BUILT 10-15 evening (compiles, not phone-tested)** — propose_plan → Go ahead → Review card (Show me / Undo from here) — **Director mode: plan → run → review** for big mandates | "Find B-roll that matches what I say and add chapter titles": Joybot shows a short plan, runs headless (already possible via JoybotAgent), then a **Review** screen lists each change with Show me / Keep / Revert, and "change the font on that slide" works from there | Runs, but no plan view, no per-change review, 8-step cap |
| F4 | 🔨 **BUILT 10-15 evening** — 40 steps for an approved plan, Keep going, cost/step line, Carry on after the phone kills the app — **Job budgets instead of the 8-step cap** | Big jobs need 30+ steps; people need a cost/step meter, a pause, and resume after the phone kills the app | Hard stop at 8; job state not persisted |
| F5 | 🔨 **BUILT 10-15 evening** — list_items / get_item / find_words; the context says it is a summary — **Context protocol for large projects** | Long projects overflow the 14k-char context; models need a compact map + "ask for details" tools, names (Cat 1, Cat 2), selection, playhead, every layer's properties | One flat text block, capped, 30 layers max |
| F6 | **Cross-wing jobs** (sprite sheet → animation → placed in the video; JoyBrush canvas region → picture job → back as a layer) | The studio's whole point; needs the JoyBrush interface agreed with its lead (Codex) | Studio-only tools; JoyBrush brief written, not built |
| F7 | 🔨 **BUILT 10-15 night** (record + replay; triggers not yet) — Save as a recipe → My recipes → Run (offline when it names nothing project-specific, else the AI redoes the request); files in Joy folder Joybot/recipes — **Recipes: record, replay, trigger** ("every new recording: transcribe, cut ums, captions, export a draft") | Offline scripted automation for everyone; power users build their own | Ideas are one-shot; no recording or triggers |
| F8 | 🔨 **BUILT 10-15 night** (Studio strip) — help answers get "Show me how": the editor rings the real button — **Show-me-how mode** (Joybot points at the real buttons and walks you through, instead of doing it) | Discovery for non-technical people; teaches the app | Help answers in words + "Take me there" routes |
| F9 | 🔨 **BUILT 10-15 night** (fallback only) — a busy/retired model falls back once to the free router, never to paid; per-step routing not yet — **Model routing** (cheap fast model for simple steps, a seeing model for `look`, a strong one for plans; auto-fallback when one fails) | Cost and quality without the person thinking about models | One model per request; manual switch |

## ⚙️ Mid

| # | Gap | Notes |
|---|---|---|
| M1 | 🔨 BUILT 10-15 (get_slide / set_slide, in line AND on top) — Review-and-revise tools for slides: `edit_slide` (rewrite a slide's HTML from an instruction), slide font/colour | "I didn't like this font in this slide" |
| M2 | Naming clips/layers (designed: NAMING_DESIGN_20261014.md) + `rename_item` for every kind | Studio-editor session |
| M3 | 🔨 BUILT 10-15 (Joybot buttons in SpriteLab + Library; Avatar stays hidden for launch) — Rooms: SpriteLab / Avatar / Library ideas + chat opened there (`EXTRA_ROOM`) | IdeaEngine is pure Java, easy to test |
| M4 | Joybot's own sprite sheet (owner draws it): moods → animations, fly-to places | Loader exists |
| M5 | Picture jobs keep the revision they started from (never overwrite later edits) — JoyBrush half with Codex | Brief exists |
| M6 | Per-request visual diff (before/after thumbnails on the result card) | Builds on F3 |
| M7 | Voice-first loop: hold-to-talk → spoken reply → "yes/undo" by voice | Dictation + read-aloud exist; not joined up |
| M8 | Library-wide B-roll index (tag every asset once, cached; rate-limit aware) | Tagging tool exists; no index |

## 🐜 Grunt

| # | Gap | Notes |
|---|---|---|
| G1 | Phone checklist (10 checks + OpenAI) | In JOYBOT_HANDOFF_20261015.md |
| G2 | App-wide hover-label sweep (house rule) | 10-15 night: 93 controls in 50 files outside the editor (lobby, records, settings…); editor drawers + SpriteLab still owed |
| G3 | ✅ 10-15: 61 topics (+25), 4 wrong old answers corrected, harness-tested — HelpBook answers for every wing's main features | Pure Java + harness tests |
| G4 | Harness tests for every pure Joybot helper | 325 + 43 (ActionRegistryTest) on 10-15 evening |
| G5 | ✅ 10-15: `joybot/AiRequest` — slide writer, narrative, b-roll, vision tagging — Other AI call sites still send `max_tokens`/`temperature` (slide writer, vision tagging, narrative) — move them onto one request helper with the Quirks fallback | Mechanical once F1 lands |

## Studio coverage — what a person can do that Joybot can't (Haiku inventory, spot-checked 10-15)

Joybot reaches ~63 tools + `look` + 23 edit-script ops. Covered well: split/delete/move/trim, silences and fillers,
transcripts (make, fix, merge, retime), captions on/off + 5 styles, basic transitions, canvas presets, punch-in zoom,
speed, mute, volume, fades, one-tap audio fix, one visualizer style, AI slides, sprites (grid, names, animations),
avatar rigs, B-roll (list, tag, suggest, place), export.

Not reachable (each row = a family of tools; with **F2** they come from the registry instead of hand-writing):

| Area | Missing | Tier once F2 exists |
|---|---|---|
| Text look | font, colour, shadow, glow, background, animation presets + zones, easing | ⚙️ (highest user value: "change the font") |
| Clip transform | crop, rotate, flip, corner pin, bend, reframe, auto-crop black bars | ⚙️ |
| Looks | colour grade, LUTs, FX looks, adjustment layers, master FX, masks, chroma key, blend modes | ⚙️ |
| Audio detail | ducking ("music quieter under my voice"), volume envelope points, voiceover, crossfades, extract audio, the full audio drawer | ⚙️ |
| Organisation | duplicate, link groups, markers/bookmarks, lanes/z-order/solo, multi-select group move, ripple/snap | ⚙️/🐜 |
| Transitions | the GL transition catalogue (zoom, burn, kaleidoscope…) | 🐜 (list + one tool) |
| Captions | caption tracks, position, size, animation zones | ⚙️ |
| Sources | PiP video from the phone, image as clip, image sequences, hand-written slide HTML | ⚙️ |
| Project care | relink missing media, proxies, consolidate/bundle | 🐜 |
| Docs | `auto_chapters`, `health_check`, `export_project` work but aren't described to the model | 🐜 (one-liners) |

## Other wings — Joybot's reach (Haiku inventory, spot-checked 10-15)

| Wing | Joybot can | Can't (yet) | Tier |
|---|---|---|---|
| SpriteLab | grid, cell names, animations, sequences, `look` | pivots/auto-centre, background key + bake, dope sheet, viseme labels, export, **place a sprite on the video**; isn't opened from Joybot | ⚙️ |
| Avatar Studio | author + apply a rig (with a confirm card) | pose matrix, face tracking, mic lip-sync, the .avatar library; no route, no ideas | ⚙️ (🧠 for live tracking jobs) |
| Capture / Library | "Take me there" only | start/stop a recording, webcam bubble, annotation; list/sort/open/delete recordings; "open my last recording in Studio" | ⚙️ |
| Lobby / folder / Trash / Share-in | Lobby ideas + glow | open SpriteLab / Avatar / Trash / Space screens; restore from Trash; help is words only | 🐜 (routes) |
| JoyBrush | open the drawing library | everything — brushes, layers, frames, export; zero ideas (owned by the JoyBrush lane; interface via F6) | 🧠 interface, then ⚙️ |
| VisLab | add a visualizer (one default style) | the style sheet, live visualizer; no VisLab room | 🐜 |
| Export | — | **`export_project` doesn't export** — it tells you to tap Export | ⚙️ (needs a safe way to start ExportService from Joybot, with a confirm) |

Dead wiring found: `IdeaEngine.Room` has SPRITELAB, AVATAR, LIBRARY, JOYBRUSH, but nothing opens Joybot with them, so
their ideas never show (only LOBBY and STUDIO are live).

## Recommended order (my pick — owner decides)

1. 🧠 **F1 native tool calling** — makes every model, free ones especially, actually do things. Small, contained,
   biggest reliability win. Frontier because it touches the agent loop, streaming and every provider's quirks.
2. 🧠 **F2 action registry** — design it once (a frontier session), then 🐜/⚙️ agents swarm the Studio table above
   family by family: text look first ("change the font"), then audio ducking, transforms, looks, organisation.
3. 🧠 **F3 + F4 Director mode with budgets** — the B-roll-and-chapters mandate, reviewable, no 8-step wall.
4. ⚙️ **Rooms + routes** (M3 + the 🐜 route list) — Joybot everywhere, cheap once F2 names the actions.
5. 🧠 **F6 cross-wing / JoyBrush interface** — with the JoyBrush lead.
6. 🧠 **F7 recipes**, **F8 show-me-how**, **F9 model routing** — power and discovery layers on top.

Grunts can start NOW without waiting: the phone checklist (G1), routes to SpriteLab/Avatar/Trash/Space, the GL
transition tool, describing the three undocumented tools, help topics for every wing, hover-label sweep.
