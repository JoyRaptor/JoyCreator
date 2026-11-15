# Shot list: what to prepare so each shot can be filmed

**Job:** L29 / SHOW-3 prep  ·  **Written:** 2026-11-15  ·  Pairs with [SIZZLE_STORYBOARD.md](SIZZLE_STORYBOARD.md) (shot numbers match).
Also feeds [STORE_SHOTS.md](STORE_SHOTS.md) and [REEL_PER_ROOM.md](REEL_PER_ROOM.md): **film once, reuse three ways.**

## Who does what

| Marker | Meaning |
|---|---|
| **AGENT** | An agent can set it up and record it on the **Note 9** (sandbox: install and test freely; never uninstall, never clear data, never touch his drawings) or on the **Tab S8 Ultra** test tablet (throwaway "TEST ..." projects only). Never the Note 20 (his real projects, closed to sessions). Rotation and other system settings stay as they are (AGENT_START, phone rule). Launch with `am start -n`, never `monkey`. |
| **OWNER** | Needs his hand or his taste: the art, his voice, his face, his verdict on a 🟡 feature |
| **EITHER** | An agent drafts a stand-in; he redoes it if he wants it better |

## Before any filming (all shots)

1. **One build for the whole shoot.** The week-3 closed-test build on the Note 9 (note it in the card: build number, date). If the app changes visibly later, only the shots listed as "first to redo" are reshot.
2. **Clean screen.** Notification bar: no notifications, battery full, Wi-Fi on, mobile signal full, no Do Not Disturb or alarm icons, clock plain. Play requires this for store pictures ([rules](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)). Samsung's system demo mode would do it but is a system setting: **ask the owner first**; the fallback is to set the above by hand and to crop the status bar in Studio.
3. **Language and folder.** English UI. The Joy Creator folder is a fresh test folder named `Joy Creator`, with only showcase projects in it (no private files visible in the file picker or Library).
4. **No identifiers in frame or in the repo.** No real names, e-mail, serials, accounts; the app's folder and project names are plain ("Fox in the Rain"). Footage lives outside git (`D:` or the owner's media folder); the repo holds only this plan and tiny stills if needed (AGENT_START rule 1; no owner media in git).
5. **Record with Capture.** Capture room, screen recording, portrait, with the floating controls hidden (auto-hide). Do not record touches, pointer trails or finger overlays (Play: no people interacting with the device). Record each shot 12 s longer each side so Studio has handles.
6. **Heat.** Probe the phone's temperature before and after each long take; stop if it throttles (lesson: a throttled phone makes every wash and look look slow).
7. **Audio.** Record screens silent; music is added in the edit. Only the S08 and S13 voice clips need a mic.
8. **Take list.** For each shot keep the best two takes; name files `S05_dino_take2.mp4` etc.

## The showcase projects (made once, used by many shots)

| Project (in the test folder) | Made of | Used by | Who |
|---|---|---|---|
| **"Sizzle Cut"** (Studio) | The sizzle itself, assembled from the clips below | S01, S16; the zoom-out end | AGENT builds the stand-in early so S01 and S16 can be framed; OWNER taste decides the cut; final assembled by whoever edits |
| **"Fox in the Rain"** (JoyBrush, one drawing) | A pencil-and-colour drawing (the subject is his choice; a small character on a rainy street shows pencil, colour, then a watercolour wash). 1 layer pencil, 1 layer colour, a final wash. | S03, S04, store screenshot 2, reel R-JoyBrush | **OWNER** draws it (his art is the point; an agent's stand-in is fallback-only) |
| **"Dino"** (SpriteLab + Studio) | The rigged dinosaur sprite (confirmed bending 2026-09-15) on a simple video background; a two-pass perform: pose pass, then record limbs live | S05, store screenshot 6, reel R-SpriteLab | OWNER's character. He copies the sprite and its rig to the Note 9's test folder; an agent never reads from the Note 20 |
| **"Edit Demo"** (Studio) | 3 to 4 short clips (6 to 10 s each) made by the owner or the app itself: a hand drawing a sketch, a screen recording of the paint room, a cooking-board shot, nothing with children, strangers or third-party logos. Two tracks, a text, a music lane, keyframes on one clip, a green-screen clip (the keyed subject: the owner's hand or a toy against a green paper). | S06, S07, store screenshot 1, tablet T1, reel R-Studio | EITHER: AGENT builds the timeline from the clips; OWNER films the green-paper clip |
| **"Voice Test"** (Studio, captions) | A 10 to 12 s voice recording, the owner speaking one plain line with one "um" in it ("I made this on my phone, with one app, and it is free" is fine), transcribed offline with Vosk or Whisper | S08, store screenshot 4, reel R-Studio | OWNER records his voice; AGENT transcribes and picks the caption style |
| **"Beat Song"** (VisLab) | The sizzle's music (once chosen) or VisLab's built-in demo song; three looks picked by the owner from the look book (`tools/vislab-web`, 42 looks); one "words as art" look carrying the words "paint animate edit" (no price words: the same clip may reach the Play video) | S09, S10, store screenshot 3, reel R-VisLab | OWNER picks the three looks (a one-page choose, 5 minutes); AGENT records |
| **TextLab looks** | Gold Crown, Blaze, Chrome '84 (the names in STATE section 5.55); words: "JOY CREATOR" and one line such as "PAINT. ANIMATE. EDIT." | S11, store screenshot 5, feature graphic (optional), reel R-TextLab | OWNER: his verdict first |
| **Capture session** | A screen recording of JoyBrush with the floating camera bubble on top | S12, store screenshot 7, reel R-Capture | EITHER: the bubble needs a face; owner's or none. Fallback: a toy or a hand in the bubble |
| **Joybot demo** | A Studio project with a spoken clip, an AI key from a free OpenRouter model (the one used in the 11-12 agent check), and a plain prompt that is proven to run | S13, store screenshot 8 | AGENT sets up the key on the Note 9 test profile (a free-tier key, never a paid one, never shown on screen); OWNER's prompt wording |

## Shot by shot

Device default = Note 9, portrait, 1440 x 2960 native. The tablet is used only where marked.

| # | Prepare | Take | Hand | Redo if |
|---|---|---|---|---|
| **S01** | Open "Sizzle Cut" with its real clips on the timeline (last thing assembled; until then a stand-in "Edit Demo" with the music lane). Drawers closed, zoom so the whole 56 s fills the strip. | The playhead runs left to right across the strip once in 3 s; scrub is smooth | AGENT, stand-in; final after the edit | Studio's timeline look changes |
| **S02** | Lobby with at least four rooms showing real recents (a drawing, a Studio export, a look, a recording) so the hero frames are real. Seed the test folder with the showcase projects first. | Slow dial through rooms; marquee wraps; hero swaps | AGENT | DOOR-2 lands (the lobby changes) |
| **S03** | "Fox in the Rain": record the owner drawing a few seconds for real (stylus), then speed up in Studio (4 s from a 20 s take). Pick colour, erase a stroke, close and reopen. **Hide the toolbar's debug items.** | Close on the canvas; the S Pen's hover not visible | **OWNER** | Brush set or drawer redesign lands (brush editor pick) |
| **S04** | Same drawing, a fresh layer, the wet-sheet watercolour (default since 11-11). Wait for the bloom and the dry. Check the **Dry now** control state and that no memory warning, no "paint room full" state appears. | Close on the paper; 10 s watercolour bloom | **OWNER** (his bar, JBX-40 open) and only after his OK | JBX-40 or atlas fixes change the water |
| **S05** | "Dino" in SpriteLab; record the performance in two passes (position, then limbs) so the dope sheet shows both tracks; a 4 s clip where it walks and bends | A close view with the tape | OWNER's character; AGENT records, EITHER performs | The puppet UI is redesigned |
| **S06** | "Edit Demo": a 4-clip, 2-track timeline; beat ticks visible; razor and trim live | Two beats of cuts; keep the pinch-zoom out of it | AGENT | Studio edit UI changes (TAB-1 is tablet only) |
| **S07** | Green-screen clip with the keyer applied; a keyframe pair with easing on a title or sticker | The keyed subject slides in | EITHER; footage OWNER | Keyer UI changes |
| **S08** | "Voice Test": transcription done; transcript view; pick caption style "pop" or "hot"; second pass, strike the "um" and play (only after STU-B3 owner check) | Play the line; words pop | OWNER voice, AGENT edit | STU-B3 changes or caption UI changes |
| **S09** | "Beat Song": a high-energy look; the song's drop on a beat; full screen; no debug overlay or FPS counter | One bar of the look on the drop; then reuse that clip for the cut-in | AGENT | Look set changes |
| **S10** | A words-as-art look; then Studio > visualiser Style tab, look row, keyframe a look swap on beats | 8 s; swap on the beats | AGENT, OWNER picks | VisLab in Studio UI changes |
| **S11** | TextLab: type "JOY CREATOR"; pick Gold Crown; set the light to Live and tilt the phone (hold in hand, steady), then a burn or grow look's progress control; then in Studio, wear the look on a title over the "Edit Demo" video | 8 s, two looks | **OWNER first**, then EITHER | Any TextLab verdict or the speed port lands |
| **S12** | Capture: floating bubble on, screen recording of JoyBrush open on "Fox in the Rain"; the bubble shows the owner's face (his choice) | 10 s | OWNER (his face) or a toy | Capture UI changes |
| **S13** | Studio, "Edit Demo" with the voice clip; open Joybot; type "remove the silences" (confirm it maps to a real action in the Joybot HelpBook before filming; else use "make the title gold"); proposal appears; apply; undo. Verify the AI-reply "report" control if landed. | 12 s with a visible Undo | AGENT; OWNER approves the prompt | Joybot chat UI changes (BOT-5 landing) |
| **S14** | Android Files on the Note 9, showing `Joy Creator` and its projects; the voiceover and Supplies folders visible and readable names. **Do not film an uninstall on a device with his work.** If the owner wants the uninstall shot, use a clean second install on the Note 9 sandbox only after he agrees | 6 s | AGENT | Folder layout changes |
| **S15** | Nothing to film. Reuse frames from S03, S05, S06, S09, S11, S12 | Studio export frames | EITHER | A reused shot changes |
| **S16** | The "Sizzle Cut" timeline zoom out (pinch) over the finished edit; a plain Studio title; the icon from the repo (`JoyCreator-icon-director-512.png` or `JoyCreator-icon-expanse-512.png`; the owner picks) | 6 s on the real project | AGENT after the edit is done | The icon changes |

## Order of filming (to save the owner's hours)

1. **Owner's 60 minutes:** draw "Fox in the Rain" (S03, S04); voice line (S08); green-paper clip (S07); copy "Dino" to the Note 9 (S05); pick three VisLab looks (S09/S10); choose the music track (and licence page screenshot); give his verdict on the two or three TextLab looks he wants shown (S11).
2. **Agent day:** set up the folder, projects and AI key; record every shot marked AGENT or EITHER; Capture room recordings; keep the takes on `D:`.
3. **Owner's look:** a 10-minute pass through the takes; he picks or asks for a redo (the owner "redo" list: S03, S04, S05, S12, S08).
4. **Edit** in Studio (storyboard section "The edit").
5. **Reuse:** cut store screenshots and the reels from the same session (the stills are taken from the same take sets: STORE_SHOTS "Captured with the same session").

## Tablet footage (for the two tablet pictures and optional tablet shots)

On the Tab S8 Ultra (test tablet; throwaway "TEST ..." projects only): Studio with drawers beside the picture (Editing desk layout) on "Edit Demo", and JoyBrush with the layer column pulled out and the brush panel open (TAB-1 §K9, K10). **Only after TAB-1 reaches the build that ships and the owner has looked at the pictures.** See STORE_SHOTS T1 and T2.

## What can NOT be filmed yet (so nobody tries)

Anything marked 🔵 or 🟡 in STATE that the owner has not cleared: frame tweens (not user-reachable), editable-line frame duplication, Select on pixels, Joybot the brush maker, the Story Board, Remote Studio folded in, TextLab in JoyBrush/VisLab as a one-tap style (VisLab "Words in a look" is 🟡), pack selling. Parked list: [LAUNCH_MONTH section 5](../LAUNCH_MONTH_20261114.md).
