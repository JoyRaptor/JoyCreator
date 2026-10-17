# Joybot — hand-off and roadmap (2026-10-15)

**For:** the next Joybot specialist (fresh session). **From:** the 10-14 Joybot specialist (Claude), stopping to save
context. **Read first:** `JOYBOT_PLAN_20261014.md` (Part A = what Joybot is, B2 = architecture, B6 = ledger),
then this file. Owner: JoyRaptor — non-technical, phone-first (Note 9), wants emoji status tables, no jargon.

## Update — 2026-10-17, app-wide host + amber warning (read this first)

All pushed (origin/joy-creator at 34fd0e4e9 + this note). Built on the 10-15 work:
- **App host** `AppActions`/`AppCtx` beside `StudioActions`: open_room (studio, spritelab, avatar, library, record,
  joybrush, space, setup, trash), list_library, open_library_item, new_project_from (recording → new Studio project).
  The chat takes `takePendingRoute` / `takePendingOpen` after the action and closes; navigating ends the request.
- **New Studio families:** motion, sprite on video, clip shape (loop ≤ 100), visualizer.
- **ToolRouter**: core tools + keyword-picked families + `more_tools`; a request went 90k → 44k chars.
- **Editor yellow dot is gone**: Joybot breathes amber (`JoybotPulse.warn`) and says it in chat with the same
  BringInPanel (Do that / Copy all / Move all / Leave them). `OutsideFiles` is the shared logic.
- 90 s read timeout; refusal guidance; no-credit errors say "no credit left" (OpenAI reports them as busy).
- Phone-green: list_library, open_room, place_sprite, trash, open_library_item, new_project_from, a 3-call request
  as one undo, amber Joybot + bring-in card (**Do it never tapped — real files; owner's call**).
- **VisLab (done 10-17, 34fd0e4e9):** `VisLabActions` (app host) on the VisLab lane's `VisLabSession`; change
  lines parsed by pure-Java `VisLabEdit` (`run-vislab-edit.sh`, 46 checks). One request = one VisLab undo
  (request token = undo group). Inside VisLab the router sends core + vislab only. Phone-green on free models:
  look switch + knob changes, one undo restores both; "A calmer version" adds a new look, one undo removes it.
  **Owed:** a look Joybot adds lives only in the session until the VisLab lane ships save_look — wire it then.
- OpenAI test shelved until the owner buys API credits (use gpt-6-luna; a stale duplicate "OpenAI"
  account with a broken key can be removed in ⚙). An "Untitled" Studio project from the 10-15 screen recording was
  made by the new_project_from test — owner keeps or deletes it.
- Still untested on phone: hands-free voice, F9 fallback, add_media / relink / list_items, ducking.

## Update — 2026-10-15 evening, F2–F5 built

Gaps F2 (action registry, 9 families / 25 actions), F3 (Director mode: plan → run → review), F4 (budgets, Keep
going, cost line, Carry on after a kill), F5 (list_items / get_item / find_words), M1 (slide edit), M3 (SpriteLab +
Library rooms), G3 (help, 61 topics) and G5 (AiRequest) are **built and type-checked, none phone-tested.** Ledger and
the ordered phone checks: `JOYBOT_PLAN_20261014.md` B6 "2026-10-15 evening". Adding a family: one file per
`ACTION_FAMILY_BRIEF_20261015.md`, one `add(...)` line in `StudioActions.build()`. Harness: `run-actions.sh` (43) and
`run-ideas.sh` (325). Next frontier: F6 cross-wing (JoyBrush interface with its lead), F7 recipes, F8 show-me-how, F9
model routing; family candidates left: markers (needs the host to write bookmarks.json), export_project that really
exports (needs a confirm + ExportService start), sources (PiP from the phone, image as clip), project care (relink).

## Update — 2026-10-15 midday session (the sections below are the morning's)

Phone-tested on the Note 9, 11:04–11:27 (owner OK'd the install; "Restore video test" left with the same layers —
its undo list has one extra "remove" entry, because the test slide's own add step did not survive my reinstalls).

| Item | Result |
|---|---|
| **P0-1 overlay slides invisible** | 🟢 FIXED, three stacked bugs, each measured: (1) `FxLivePreviewController.belowTexts` handed every "text" under a blending picture to GL, which drew a slide as an EMPTY string and set its view to alpha 0 (logged alpha=0.0 every frame) → slides now stay on their view, like bent/pinned text; (2) `TextOverlayLayer` overhang strips drew the WebView 3×/frame and the last (clipped) draw won → `drawChild` skips slides in strip passes (proven: with the skip off, only a sliver showed); (3) WebView won't zoom below 25 %, so a 1080-wide slide in a ~390 px preview showed its left 70 % → `GeneratedSlideView` now scales #stage itself (`__jbFit`, re-run on seek + resize; also fixes in-line slide previews — NOT yet eyeballed on an in-line slide). |
| AI-written slide that is EMPTY | 🟢 new gate `SlideContract.validateContent`: animated ids must exist, #stage not empty, tweens on the timeline. One correction round, then the built-in design. The free model produced exactly this on the phone. 5 harness tests (123 pass). |
| **P0-3 model requests stop when the chat closes** | 🟢 FIXED + phone-tested: new headless `ai/joybot/JoybotAgent` runs ask→tools→reply on Joybot's one worker (`JoybotJobs.execute`); the chat is a `Listener`. Sent "make the hello 2 text fade in over 2 seconds", closed the chat at once: editor logged `Recorded AI checkpoint: Joybot: “make the hello 2 text fade in…”` after close. Reopened chat mid-run shows "Still working on …"; finished-while-away replies wait (`JoybotAgent.takeWaiting`, shown by the chat's "While you were away"). |
| Pictures act, not just talk | 🟢 built, 🟡 not phone-tested: attaching a picture now STAGES it ("What should I do with it?") and the next message carries it through the agent WITH tools. Was: auto-sent "Describe what's in this image", words only. |
| `look` tool (Joybot's eyes) | 🟢 built, 🟡 not phone-tested (needs a vision model): `ai/joybot/JoybotEyes` — sprite sheet with numbered grid (indices = label_sprite_cells), sequence contact sheet, picture layer, clip frame, playhead frame; the next request carries the picture. This is what makes "label my sprite sheet's expressions" real. |
| P0-4 Show me for layers | 🟢 built, 🟡 not phone-tested: editor selects the layer, scrolls its row into view, parks the playhead in it. |
| Request spanning chat-open → chat-closed | 🟢 built: `AIChatState.Step.request` token; the editor's drained step adopts later live writes (one press, one step). Not phone-tested. |

**Afternoon (12:00–13:00), commits 5e897f772 + af897d1f9:** `look` phone-tested 🟢 (free router saw "Starguy",
15/16 cells right); "While you were away" 🟢; a request that only DESCRIBED a change now gets one push to apply it
(`HelpBook.wantsChange`); no false Undo card when the editor takes over mid-request; saved AI accounts
(`ai/AiAccounts`, key shape → service in `joybot/AiProviders`, one-tap switch in "Talk to…"); services that refuse
`max_tokens`/`temperature`/streaming are adapted to (`JoybotAgent.Quirks`); chat renders bold/bullets, hides leaked
`<dots_function_call>` tokens, no longer doubles messages on reopen; Show me on anything a job ADDED; picture models
badged when they take reference pictures (`input_references`, read from OpenRouter) and an attached picture rides
along as the reference; "Remove this slide?" wording; hover labels on copy/Apply/Discard/ticks. Export check (old
item 8) done by another session: **no export bug**. 142 harness tests pass.

**Evening (18:00), commit 260f399cf — gap F1 native tool calling:** tools go as real API tool definitions parsed
from `getToolDescriptions()` (`joybot/ToolDocs`, 60+ tools, harness-tested against the real text); JSON-in-text is the
fallback (`Quirks.nativeTools`, adapted on a 400/404/422 that mentions tools); past tool turns are downgraded to text
when native is off or a call was left unanswered; after a nudge `tool_choice: required`. Phone 🟢: "make the hello 2
text fade in over 2 seconds" on the free router = 2 native calls by two different free models + a summary, one undo
step; undone. Log line per reply: `JoybotAgent: reply: native tools on, N native call(s)…`. Also: routes to SpriteLab /
Avatar / Space (Haiku agent; Trash needs a MainActivity hook — not done); keys cleaned of whitespace (the owner's pasted
OpenAI key had a line break and lost one character: phone stored 163 of 164 → HTTP 401; **owner must re-paste it**);
"pick a model" for an account without one. OpenAI test still owed: once re-pasted, pick **gpt-6-luna** (owner's
"luna level"; the account lists it), run checklist #4 + the fade request, few calls.

### Phone checklist — grunt work (any careful agent; follow tasks/LANES.md + tools/phone.ps1 rules)

Setup each time: ask the owner before installing; `bash tools/build-install.sh`; claim the phone lock as `joybot`;
open "Restore video test" (Lobby → Carry on), open Joybot (top-right). Note the editor's undo count first and put it
back after (editor's undo button); never leave a test layer. Screenshot every result; report as 🟢/🔴 with the shot.
Long typed messages grow the text box — the Send button moves up; screenshot before tapping Send.

| # | Do | Expect |
|---|---|---|
| 1 | Tap 🖼 (attach), pick any photo | Bubble "Picture attached"; Joybot: "What should I do with it?"; nothing sent yet |
| 2 | Then type "what colours are in this?" → Send | A real answer about THAT picture (vision model) |
| 3 | Attach a photo, then Send with the box empty | Answers "what do you see"; no error |
| 4 | "this is my Starguy sprite sheet of expressions. Look at it and name every cell with its expression", close the chat at once, wait 90 s, reopen | "While you were away I finished…"; a ✓ Look line; ✓ Label sprite cells line; the sheet's cells named (SpriteLab shows names). Undo it after |
| 5 | Help me make a slide → Title → Use name → Bold → On top, from the start | Card "Added 1 layer." has **Show me** → editor selects the slide row, scrolls to it, playhead inside it; the slide is visible over the video, whole (not cut off) |
| 6 | Same flow, but **In line, at the start** | The in-line slide preview shows the WHOLE slide (fit fix), not its left 70 % |
| 7 | Long-press the new slide row → delete | Dialog says "Remove this slide?" |
| 8 | With a stylus/mouse hover: copy icon, Apply/Discard on a proposal card, the scroll ticks on the right | Each shows a label |
| 9 | Close and reopen the chat twice | No doubled messages; tool lines stay small grey lines |
| 10 | Talk to a picture model (tap model name → 🎨 one), attach a sketch, type "make this a finished cover" | Price shown first (owner OKs any paid call!); result uses the sketch |

**OpenAI / several accounts (needs the owner's key — never type a real key yourself):** owner adds the account
(model name → + Add another AI account… → paste → Save) → model list shows THEIR OpenAI models → pick a cheap one →
ask "make the hello 2 text fade in over 2 seconds" (expect one undo step), then the sprite labelling (#4) — compare
with the free router. Switch back to OpenRouter in one tap ("⇄ Use your OpenRouter account"). Keep calls few.

## Where it stood this morning (all committed in the commit that adds this file)

Device-verified on the Note 9, 2026-10-15 10:00–10:08 (phone lock claimed and released; owner's project
"Restore video test" left exactly as it was — every test edit undone in the editor):

| Check | Result |
|---|---|
| Studio gradient instead of solid green (owner ask) | 🟢 your bubbles, Send, scroll ticks = aqua→lime (`studio_action_pill` / `Studio.GO→GO_END`, ink `ON_GO`); mic + status strip grey |
| "make this end here" (image layer selected, playhead 1.695 s) | 🟢 runs locally, no AI: card "The image layer "image" now runs from 0:00.00 to 0:01.69." + Undo; editor shows one step "Joybot: End it at the playhead" |
| That undo after an app reinstall | 🟢 still on the editor's stack (undo_history.json), undid cleanly |
| Slide wizard asks In line / On top | 🟢 "In line adds it to the video (it gets longer); on top lays it over what's playing." |
| Long job survives closing the chat | 🟢 chose "On top", closed the chat in the same instant; 33 s later the editor adopted it as ONE undo step "Joybot: Make a cinematic title slide on top of the video" (logcat `UndoManager: Recorded AI checkpoint`) |
| **On-top (overlay) HTML slide visible in the editor preview** | 🔴 **BUG** — see P0-1 |

Earlier (10-14) device results are in the plan's B6 ledger: idea chips, one-undo-per-request, help, slide wizard
in line, picture generation, vision tagging, model picker/search/stars/badges, fades via `set_fades`.

## Roadmap — everything known that's left, in order

### P0 — fix and finish testing what's built

1. 🔴 **Overlay slides don't draw in the preview.** The AI-designed overlay HTML is fine (transparent stage,
   gsap + `faditor_runtime.js` present beside it in `files/faditor/projects/<id>/slides/`), the layer exists and
   selects full-frame, but at 0.9 s nothing shows — not even its 60 % backdrop — so the live
   `GeneratedSlideView` in `TextOverlayLayer.createOverlayView` (app/.../ui/faditor/overlay/TextOverlayLayer.java
   ~line 1105) isn't drawing/seeking. Check: does it get playhead updates (`mapOverlayToAnimMs`), its size
   (`WRAP_CONTENT` then `position()`), and whether the built-in offline overlay (`SlideDesigns` overlay=true) shows
   the same. Also check export (`CompositeExportOverlay` → `SlideRenderer.renderAllOverlays` PNG frames) — may
   work even if the preview doesn't. This is Studio-editor code: announce in `tasks/LANES.md` first.
2. **Not yet tried on the phone:** picture **In line / On top** (costs ~1.5¢ with the owner's key — ask him);
   dictation mic (needs his voice); read-aloud toggle; lobby "Transcribe all" → **Open in Studio**;
   talking straight to a picture model from the switcher; the "claimed it but called no tool" nudge and its
   honest failure message (`ChatAssistantActivity` `nudgedForTool` / `claimsAChange`).
3. **Model-chained requests still stop when the chat closes.** Button/idea jobs run on `JoybotJobs`; a request
   the MODEL is carrying out (`handleAiQuery` → `continueAiConversation` loop) lives in the activity. Move that
   loop onto `JoybotJobs` the same way (it already writes through `JoybotStorage`, so adoption by the editor works).
4. "Show me" should scroll the editor's layer band to the new item — the band scrolls, and a new row can land
   below the fold (looked like the VIZ row vanished; it hadn't).

### P1 — owner-requested, designed, not built

5. **Naming clips/layers** ("Cat 1", "Cat 2") — full design + owner rulings in `NAMING_DESIGN_20261014.md`
   (duplicates/splits keep the same name; same names allowed, Joybot asks which). Trap: `Clip.displayName` is the
   relink/auto-blank memory — needs a NEW `name` field. Build is a Studio-editor session; Joybot then lists names
   in context, `rename_item` writes `name` for every kind (it refuses clips today on purpose), tools accept names.
6. **Placed images + Joybot image jobs** — owner brief `tasks/joybrush/briefs/PLACED_IMAGES_JOYBOT_20261014.md`
   (in commit 49cd733d4; not in the main folder's working tree — `git show 49cd733d4:<path>`). Joybot's half:
   a. find out which picture models take **reference images** (OpenRouter `/images/models` — read its input
      modalities, don't assume) → badge them in `ModelPicker`;
   b. send the chat's attached pictures as references on picture requests (`JoybotPictures.generate`);
   c. a job remembers the project/canvas revision it started from and never overwrites later edits — it places
      its result as its own undoable layer (Studio already does this; keep it true for JoyBrush);
   d. cost shown + consent before any paid call (already for pictures; keep for edits).
   The canvas half (select a sketch region, hand Joybot an immutable snapshot, apply the result as one undoable
   board) is the JoyBrush engine lead's (Codex) — agree the interface with them; do NOT edit `joybrush*/`.
7. **His sprite sheet.** Owner will supply a labelled sheet. `JoybotSheet` already loads
   `<Joy folder>/Joybot/joybot.png` + `joybot.sprite.json` and maps animation names to moods
   (idle · listen · think · work · talk · excited · show · oops). Then: fly to his corner and hover while
   suggesting; fly beside a new picture to show it off (`JoybotView.flyTo/flyHome`).

### P2 — phase 4, "he's everywhere"

8. Rooms: SpriteLab, Avatar, Library, VisLab open Joybot with `EXTRA_ROOM` and their own ideas in `IdeaEngine`
   (only LOBBY and STUDIO wired). JoyBrush goes through `BRIEF_FOR_JOYBRUSH_LANE_20261014.md`.
9. B-roll that matches what's said: facts + paced vision tagging exist; verify end-to-end on the phone
   (free models throttle at 2–3 images — keep the pacing).
10. "Design a picture" (free HTML/SVG graphic, no paid model) — not built; the picture flow offers make-here /
    your own AI app instead.
11. Hover labels on every Joybot button (house rule: stylus + mouse make tooltips real) — sweep the chat screen
    and picker.
12. Owner questions still open (defaults in place): default paid picture model (Klein ~1½¢), "ask before paid
    under 5¢" switch, his animation names.

## How to work here (learned the hard way)

- Checks: `bash tools/jvm-harness/typecheck.sh` (whole app, ~3 min; "TYPECHECK OK — 826 sources") and
  `bash tools/jvm-harness/run-ideas.sh` (118 pass). Never run Gradle — the watcher builds into `build.log`;
  `.\tools\phone.ps1 build` shows the last result.
- Phone: `.\tools\phone.ps1 who / claim joybot "<why>" / install / launch / shot / release joybot`, with
  `$env:PHONE_OWNER="joybot"`. Ask the owner before installing (he may be drawing). Other lanes install builds from
  other folders — check `dumpsys package com.fadcam.beta | grep lastUpdateTime` and reinstall from the main folder
  before testing your own code.
- minSdk 24: no `String.join`, `java.nio.file`, other API-26 calls.
- Never print/log the API key. Undo every test edit in the owner's project before leaving.
- Commit only your files (`git add <exact paths>`); many harness files show line-ending-only changes that are
  not yours. For `tasks/LANES.md`, stage only your entry (build the blob from `HEAD` + your hunk,
  `git hash-object -w` + `git update-index --cacheinfo`). Commit name JoyRaptor; message ends with the
  Co-Authored-By line.
