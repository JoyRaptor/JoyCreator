# Launch triage of the 11-13 remaining-work audit (written 2026-11-14)

Source: `tasks/audits/remaining_work_20261113.md` (287 rows) against `tasks/LAUNCH_MONTH_20261114.md`. Points: S=1, M=2, L=4, XL=8. The 67 IDs named in commits since the audit count as DONE, except STU-B3 and STU-B5 (diagnosed only). Eight DONE rows were bigger than one commit could plausibly finish, so they are tagged DONE? and need a check against the code; they are not counted in any other class.

## 1. Summary

| Class | Rows | Points | Owner needed: rows | Owner needed: points | No owner: rows | No owner: points |
|---|---|---|---|---|---|---|
| LAUNCH | 42 | 105 | 20 | 39 | 22 | 66 |
| POLISH | 75 | 165 | 21 | 43 | 54 | 122 |
| AFTER | 105 | 484 | 39 | 176 | 66 | 308 |
| DONE | 57 | 77 | 0 | 0 | 57 | 77 |
| DONE? | 8 | 32 | 0 | 0 | 8 | 32 |
| Total | 287 | 863 | 80 | 258 | 207 | 605 |

DONE? rows (verify first): BOT-A16, BOT-A29, BOT-A31, BOT-A33, BOT-A7, JBX-18, L18, L19. If any is not done, its likely class is: BOT-A7 POLISH, BOT-A16 AFTER, BOT-A29 AFTER, BOT-A31 AFTER, BOT-A33 AFTER, L18 POLISH, L19 LAUNCH (accessibility basics), JBX-18 AFTER.

Overlaps inside LAUNCH (same job counted twice): L10 + L11 + SHELL-43 (Play build); L16 + SHELL-44 (FadCam folders and names); SHELL-60 + L32 (store, donation links); SHELL-40 + L37 (hide unproven features); SHELL-34 + L28 (intro copy). The true point load is lower than the table.

## 2. LAUNCH list by wing

### Joybot

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| BOT-A1 | S | Check on the phone that Joybot's marker undo and cut-clip really work. | no | yes |
| BOT-A12 | M | Phone-check the Joybot paths a keyless tester hits first: free-model fallback, borrowed eyes, add media, relink, ducking. | no | yes |
| BOT-A26 | M | Joybot's character sprite sheet and moods (owner's art) so his arrival matches the picked design. | yes | yes |

### Studio

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| STU-B1 | M | Finish the five Studio checks from the 11-10 landing (audio cut, text drawer reopen and others). | no | yes |
| STU-B3 | L | Cut audio spans still play and export; needs the owner's ruling, then a fix. | no | yes |
| STU-C4 | M | Owner places every transition type and looks for black frames or glitches. | yes | yes |

### App shell

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| SHELL-09 | S | Look at onboarding, What's New and update sheets on the phone; fix the serif checkbox lines. | no | yes |
| SHELL-10 | S | Prove the watermark text in a saved video, photo and screen recording is Joy Creator's. | no | yes |
| SHELL-12 | S | Check Library item sheet for a photo item and a missing-file item (no crash). | no | yes |
| SHELL-15 | S | Owner cuts or commits the nine 'coming soon' gadgets that show as dead rows. | yes | no |
| SHELL-17 | S | Owner picks the front door design so DOOR-2 can be built. | yes | yes |
| SHELL-18 | XL | Build the chosen lobby front door (New row, room names, floor words); his biggest worry. | yes | yes |
| SHELL-28 | S | Owner-owed file checks: Move undo on a big file, recording when the folder is missing. | yes | yes |
| SHELL-31 | M | Recording on a nearly full disk stops safely; write-error and copy follow-ups from the work-loss report. | no | yes |
| SHELL-34 | M | Owner supplies the intro text; slides carry it in all languages. | yes | yes |
| SHELL-36 | L | Ask for permissions where first needed, not up front; drop all-files access (Play policy). | no | yes |
| SHELL-37 | M | One continuous first run: intro, folder step, optional Joybot greeting, no flashes. | no | yes |
| SHELL-40 | M | Owner agrees which unproven yellow features are hidden at launch. | yes | no |
| SHELL-43 | M | Play build: one launcher icon, no accessibility screenshot service, no all-files access. | no | yes |
| SHELL-44 | L | Replace FadCam folder and file names on the phone (Downloads/FadCam, FadRec, Faditor_ prefix). | no | yes |
| SHELL-45 | M | Owner picks the permanent app ID and the move from com.fadcam.beta is proven. | yes | yes |
| SHELL-58 | S | Owner rules on Wear OS screens and mini-apps (QR, torch) still in the hub. | yes | no |
| SHELL-60 | M | Measure size against Play's 200 MB; replace the fastlane listing and donation links. | yes | no |

### Animation and labs

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| AN-03 | S | Owner decides Avatar Studio at launch (keep, beta label or hide); gate its four entry points. | yes | no |
| VL-11 | S | Owner signs off VisLab R1-R16 by trying them. | yes | no |
| TX-16 | S | Owner tries TextLab, names the wing, runs it on the Note 20. | yes | no |

### JoyBrush

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| JBX-09 | M | Top buttons and icons unreadable on dark papers; repair contrast. | no | yes |
| JBX-35 | L | Find or retire the lost-tile cause; allocation-failure tests (lost work). | no | yes |
| JBX-36 | XL | Streaming saves and paint-room size so a big painting saves within memory (lost work). | no | yes |
| JBX-37 | XL | Strokes survive activity recreation and graphics loss; failed writer leaves the file intact. | no | yes |
| JBX-40 | M | Owner paints a long session on wet sheet / atlas on Note 9 then Note 20 and meets no refusal. | yes | yes |

### Launch and hygiene

| ID | Size | What | Owner? | Phone? |
|---|---|---|---|---|
| L09 | M | Draft the Play data-safety and permissions answers. | no | no |
| L10 | L | Add the 'play' product flavour (no disguise icons, accessibility service, all-files access, black screen). | no | yes |
| L11 | M | Launcher-icon switcher and its Settings UI vanish in the play build. | no | yes |
| L15 | M | Release signing and versioning: real app ID, no beta suffix, upload key handled privately. | yes | no |
| L16 | L | Rename user-visible FadCam folders and files (same job as SHELL-44). | no | yes |
| L22 | M | Smoke test that every wing opens on the release build without a crash. | no | yes |
| L27 | S | Remove tasks/api.txt from the project folder, rotate the key, add a guard. | yes | no |
| L28 | S | What's New and intro copy approved (owner's 9-09 text still missing). | yes | no |
| L30 | L | First website skeleton on GitHub Pages; a one-page version serves the privacy policy Play needs. | no | no |
| L32 | M | Remove stale FadCam Pro / donation strings; owner's own links in a non-blocking prompt. | yes | yes |
| L37 | L | A single switch that hides the unproven yellow features at launch. | yes | yes |

## 3. POLISH (only if time allows)

### Joybot

- BOT-A3 (S) Read the request-size log with a free model on the Note 9 (PromptBudget)
- BOT-A6 (M) Help topics for Capture: photo/screenshot, dual camera, Remote entry points, watermark switch, Settings hub v2, Home recorder, About
- BOT-A8 (M) Phone-ask the 18 new help topics (JoyBrush/SpriteLab/Library) plus TextLab, Settings, Remote topics
- BOT-A11 (S) Offline wording owner check (NetCheck, TipBook.offlineHope)
- BOT-A13 (S) Owner voice test of the hands-free loop and OpenAI provider
- BOT-A27 (L) Joybot as first-run guide and tour; tap-for-voice, hold-for-chat; replace FadCam avatar toggle
- BOT-A39 (S) Re-run the JVM harness and bench after 11-04 to 11-13 changes; keep BOT docs current

### Studio

- STU-A2 (M) Help mappings for Studio rows marked 'Help: PENDING'
- STU-B5 (M) Joybot marks undo limits (multi-step chat visit; recreated Studio)
- STU-C1 (M) Re-run STU-1 phone backlog: Bend on text, corner-pinned text with effect export parity
- STU-C2 (S) Re-run STU-1 phone backlog: drag a sprite's corner pin and sprite on transform surface
- STU-C3 (M) Audio ducking real test and the 24.41 s fade-in readout
- STU-C5 (S) Finish STU-3 marks checks: finishing a region, loop timing, region export, rename, reopen, undo of a mark
- STU-C6 (L) Sweep unproven ðŸŸ¡ Studio rows: link to pin of bent sprite, drag-rotation fold, drawer undo steps, paused-effect redraw, drawer polish, hover labels
- STU-D1 (L) Hover labels sweep for Studio (201 uncovered listener sites at audit time)
- STU-D4 (L) Restyle Studio's ~90 plain dialogs (Studio frame instead of Material grey/purple)
- STU-D5 (M) Drawer duplicate cleanup (top 10 of the drawers audit)
- STU-D6 (S) Colour picker hex touches column edge ('#FF000B')
- STU-E2 (M) TimeMap leftovers: seek at a cut (D7), rounding (D9), playhead during a blend
- STU-E3 (L) E5 and the blend's missing fades (audio volume curve and picture fades during a transition)
- STU-F1 (S) Text Fit mode and side-handle drag verification (S9)
- STU-F6 (M) S21 PiP audio gaps
- STU-F7 (M) S2 Loop / ping-pong: run the 7 acceptance checks (last)
- STU-F8 (XL) S1 re-run the 41-check device sweep (14 never-seen features)
- STU-F9 (S) S5 audio editor: mini-map shows audio lanes in their own colour
- STU-G9 (M) Memory trim on background and the 5-minute silent-clip Media3 check

### App shell

- SHELL-02 (S) Make the lobby 'Recent work' recording card play the recording
- SHELL-07 (M) Home recorder landscape layout still old
- SHELL-08 (M) Restyle the fullscreen camera preview and classic Studio list leftovers
- SHELL-13 (S) Settings: choose a location glyph for 'Location in video files' and confirm location lands in a real recording
- SHELL-16 (S) Settle the owner's open shell decisions: support_url, theme prune, Setup grouping, Library Clips kind
- SHELL-23 (M) Land 'Send to Studio into an existing project' and prove it on the Note 9
- SHELL-29 (M) JoyBrush half of the folder: paper in thumbnails, thumbnail = last view, Open... adds a new drawing
- SHELL-38 (M) Ship a small demo project you can press play on
- SHELL-39 (M) Empty-state illustrations and hero art for the lobby (asset-blocked)
- SHELL-49 (L) Remote Studio restyle: Joy Creator name, look, owner's ring colours, LAN-only copy
- SHELL-51 (L) Smart Detection restyle: Joy Creator name, chips, motion/noise-triggered capture as a Capture mode
- SHELL-61 (XL) Tablet layouts (sw600dp) for the shell
- SHELL-62 (M) Theme prune: reduce nine colour themes to Joy Creator plus one dark alternative; section accent per room

### Animation and labs

- AN-01 (S) Puppet: a screenshot of the overlapping corner widget (ledger C2), then fix it
- AN-06 (M) Phone-prove puppet basics on a throwaway rigged picture: place pins, key a pin, live take, tape drags, bone tap, rotated-picture pins
- AN-08 (S) Timeline grab bar: run the diagnostic and read moves= (ledger C1)
- AN-09 (S) Phone-drag a sprite's corner pin and bend (never dragged on a phone)
- VL-02 (M) Land the unlanded VisLab review branch origin/claude/festive-perlman-8dde53 (V1, V3, V4, V5, SoundHits fix)
- VL-03 (S) Words in a look: measure frame cost in VisLab and try a Studio export of a VisLab look carrying words
- VL-05 (M) VisLab words into strings.xml for translation, and a hover-label audit
- VL-07 (M) Echo trails restart at a clip boundary in exports
- VL-12 (M) Owner-deferred checks: R1 audit (floating visualiser, needs Start now), R2 audit (Android 12+), D2 hour-long heat test
- TX-04 (S) Rename the lobby 'uArc' name clash (lightning knob vs tube share)
- TX-09 (M) TextLab phone engine speed: per-pass GPU timing; app ~2x slower than the look book
- TX-17 (S) STATE and help mapping for TextLab rows after owner tries

### JoyBrush

- JBX-04 (M) Make moving the symmetry line one Undo step
- JBX-08 (S) Phone-look the hover outline (never seen working) and fix the lean direction if wrong
- JBX-10 (M) Sweep JoyBrush chrome for missing hover labels
- JBX-11 (M) Group the long more menu (about 20+ rows) into sections
- JBX-12 (M) Write the missing and stale JoyBrush STATE rows and help mappings
- JBX-14 (S) Doc name follows a drawing's rename
- JBX-16 (M) Hold-to-open: make 'Open...' bring a file in as a new drawing instead of replacing the current one
- JBX-17 (L) Wire the tap-fill (bucket) tool
- JBX-23 (M) Symmetry phone checks still owed: scatter, 30 copies speed, pen tilt and pressure
- JBX-24 (L) Boards, Tile and Sprite acceptance sweep on the Note 9
- JBX-25 (M) Audit fill, brush-import and export entry points on a phone with real files
- JBX-26 (S) Animation clips acceptance (U1-U8) with the owner
- JBX-31 (M) Paint on a paint layer automatically when the target is an ink layer or a mask is selected
- JBX-38 (M) Oil and knife: owner tries Knife 3, hair Varied/Even, soft passage, hover outline; tune from his words
- JBX-41 (L) Unibrush: speed (U9/U10), context loss and recovery, replacement-failure safety, line parity for Ink/Marker/Bristle, Note 20
- JBX-42 (XL) Vector watercolour lines: cold first stroke, View save/reopen/recovery, fade matrix, grainy-preset look, performance
- JBX-43 (L) Frame duplication: captured and joint cases, plus owner UI acceptance
- JBX-46 (S) Decide and land the tuft-tuning orphan files from the owner's main folder
- JBX-47 (S) Commit the untracked JoyBrush handoffs and reviews in the owner's main folder
- JBX-49 (M) Joybot help mappings for pending JoyBrush rows

### Launch and hygiene

- L17 (M) Clean the 11 translations: leftover 'FadCam' in 10-44 strings each; new strings_*.xml files have no translations **DONE 11-15** (12 languages, `tools/check_strings.py`; branch claude/translations-20261115-bea302; reading by a native speaker still owed)
- L26 (S) HYG-1 leftovers: land or drop the JoyBrush tuft-tuning files and staged tween reviews in the main folder
- L29 (M) SHOW-3 prep: shot list, store screenshots plan, feature graphic and demo-video storyboard from the ðŸŸ¢ STATE rows
- L33 (S) Letter to the FadCam author and licence/credit review

## 4. AFTER (not needed to launch)

### Joybot

- BOT-A4 (L) Compile and phone-try clip/layer naming and finish its Studio UI half
- BOT-A14 (M) Avatar room Joybot button and ideas
- BOT-A15 (M) Per-project chat log trial (B-5)
- BOT-A17 (L) Edits against a revision (document id + revision + op id) starting with Studio
- BOT-A19 (M) Live free-model benchmark table (run-joybot-bench.sh --live)
- BOT-A21 (M) Real Claude Code and Codex over Wi-Fi against the phone bridge
- BOT-A22 (XL) Talk half: Joybot chat sends to the computer's AI (OpenCode serve first)
- BOT-A23 (S) Ask Anthropic in writing about starting a person's Claude Code (S17)
- BOT-A24 (M) Pairing-screen mix-and-match design page and tablet QR path
- BOT-A28 (XL) Joybot cross-wing jobs (sprite sheet to animation to video; JoyBrush region to picture; VisLab look to Studio visualizer)
- BOT-A30 (L) JoyBrush deeper actions: Select, symmetry, paper, undo/redo by voice, export, Send to Studio
- BOT-A32 (L) Pictures: placed reference images, free 'design a picture' (SVG/HTML), picture jobs keep their revision
- BOT-A34 (L) Outside-AI launch buttons, prompt packing and notices (B-2, B-3, B-4) and Joybot skins (B-1)
- BOT-A35 (L) Before/after thumbnails on a result card, Library-wide B-roll index, full media seam
- BOT-A36 (XL) Joybot the brush maker (JoyBrush wish list) and after-launch items: video generation, TTS, voice cloning, lecture mode

### Studio

- STU-E4 (L) D2, D3, D10, D11: keys in source vs timeline time, PiP at non-normal speed, loop seek units, clamp
- STU-E5 (XL) Review the parts of Studio never reviewed (drawers, timeline touch, compositor, player)
- STU-F2 (L) S12: double-tap toggles every drawer closed; S11 one slider row everywhere
- STU-F3 (L) S7 one snapping authority and object alignment on canvas
- STU-F4 (M) S16 multi-select fade/trim and S20 playhead rework
- STU-F5 (L) S3 object time-scrubber for sprites, audio and PiP (text only today)
- STU-G1 (XL) STU-6 part 1: effect picker as a grid of pictures, live swatch on each stack card
- STU-G2 (XL) STU-6 part 2: seven Generate cards (Tiles, Cells, Halftone/Stripes/Checks, Brushed metal, Chipped wear, Caustics, Glitter)
- STU-G3 (XL) STU-6 part 3: over-video effects (Burn away, Stained glass, LED board, Flag wave, Steamed glass)
- STU-G4 (XL) STU-5 export speed: one-pass GPU stage ('one tap, fast')
- STU-G5 (XL) STU-5 primitives: Path object, text on a path, per-letter channels, paths as masks and motion paths
- STU-G6 (XL) S13 bend on video (PiP and spine) and mesh bend on sprites/PiP/text/spine
- STU-G7 (L) Luma key and parameter drivers (S10)
- STU-G8 (XL) Wish-list Studio ideas: motion blur, save-as-preset everywhere, per-word caption styling, low-bandwidth Baseline export, failure report

### App shell

- SHELL-19 (L) Lobby search palette (swipe down finds every room, setting, project, action)
- SHELL-20 (L) Live strip: one persistent strip for recording / export / AI / transcribe that survives leaving the editor
- SHELL-21 (M) Hold-to-fan floor words (depth under the thumb)
- SHELL-22 (L) Durable modules: sprite sheets reusable across projects with two doors each
- SHELL-24 (L) Studio to JoyBrush: edit a frame or picture as a drawing (nothing flows back today)
- SHELL-30 (L) Bring the remaining storage roots under the Joy Creator folder (avatars, fonts, voiceovers, drawing working copies)
- SHELL-32 (XL) Presets and settings into the Joy Creator folder; version history
- SHELL-33 (S) Delete FILE-1 test leftovers on the Note 9
- SHELL-42 (M) Remove the ~30 FadCam classes with no caller and 4 unused drawables (after S10 yes)
- SHELL-46 (XL) Rename the Java packages com.fadcam.* (or let rebuilt code take new packages)
- SHELL-50 (L) Auto-markers: detector events and loud moments become timeline marks on long recordings
- SHELL-52 (XL) Story Board: pin, tag and sequence clips and notes, hand a rough cut to Studio
- SHELL-53 (XL) Multi-camera: front+back at once, a remote phone as a second camera, layers in Studio
- SHELL-54 (L) Dual-stream recording finish: webcam file rollover, linked badge, trim and split mirroring
- SHELL-55 (XL) Annotation becomes a JoyBrush layer over video (replace FadRec's ~18,000-line annotation)
- SHELL-56 (XL) Rebuild the recorder: Home recorder, recording service, Records, players on Joy Creator code (keep Capture parity)
- SHELL-57 (XL) Rebuild MainActivity as a lobby host and drop the six old tabs and bottom nav

### Animation and labs

- AN-02 (S) Owner call: does Puppets need an 'Everything' chip (C3)?
- AN-04 (S) Owner call: sprite tape colour ramp (D-16) and SpriteLab range shading (D-21)
- AN-07 (S) Phone-witness dangle (a tail swing) and the hole/mesh-depth sliders
- AN-11 (L) Sprite Lab: swipeable panes, GIF/WebP preview per clip, onion-skin anchor ghost, sheet from a video
- AN-12 (L) Sprite Lab: multi-sheet merge with a Sources rail, and a bakedFrom field
- AN-13 (M) Importer accepts visemeMap and offers 'build a rig from this sheet'
- AN-14 (L) Sprite sheet library across projects (the third missing door; sheets live inside project.json)
- AN-15 (L) Shareable character bundle (sheet + JSON + rig in one file)
- AN-16 (XL) Puppet face/voice drivers for a picture rig (ledger C4)
- AN-17 (M) Puppet: per-key easing override and key-level polish (A8)
- AN-18 (XL) Bend and puppets on video PiP, text and the spine (S13)
- AN-19 (XL) Avatar ideas: viseme calibration, behaviour hotbar, mesh density, auto cut-out, AI 3/4 views (A7)
- AN-20 (XL) JoyBrush puppet and character boards (JB-7.01 to 7.04)
- AN-21 (L) SpriteLab on tablets (two-pane) and hosting the web SpriteLab on joycreator.cc
- VL-04 (S) Avatars shown inside a look (R9) on the phone
- VL-06 (M) VisLab V2: stop Studio's GPU-to-CPU-to-GPU copy per frame (step 1: reuse one bitmap per item, draw upside down)
- VL-08 (M) Studio's per-letter text animations offered as VisLab letter styles
- VL-09 (L) Element packs with new shader pieces (vis-element): test-compiled, timed, off until turned on
- VL-10 (L) Emitter as a VisLab layer (tl-particles: sparks off a logo or words)
- VL-13 (S) Close or keep: visualizer studio Tier 3 (custom HTML visualisers, ffmpeg templates) (S24)
- TX-05 (M) JVM tests for TextLab shelf, LookFrame, packs reader and linkSheets
- TX-06 (M) STU-6 stage 1: effect picker as pictures and a live swatch on every effect card (branch unlanded)
- TX-07 (L) STU-6 stage 2: Generate cards (Tiles, Cells, Halftone/Stripes/Checks, Brushed metal, Chipped wear, Caustics, Glitter)
- TX-08 (XL) STU-6 stage 3: over-video effects (Burn away, Stained glass, LED board, Flag wave, Steamed glass)
- TX-10 (XL) Looks to the owner's bar: Rusted, Crystal Ice, Phantom, Dust, Water drips; next level for Liquid Chrome, Diamond, Retro Pop, Hologram; Stained Glass floor light, Banner pole and shadow, script hand; fire toward the photograph; water splashes
- TX-11 (S) Bundle open-licence display fonts for TextLab
- TX-12 (S) Phone proof that Studio shows pack grades (.cube) and caption styles in its UI
- TX-13 (L) JoyBrush reads brushes, papers and palettes from packs (reader spec for its lead)
- TX-14 (XL) Pack signing, the shop and paywall, release waves, pack update (versions) and manager
- TX-15 (XL) JoyBrush text tool so a look has somewhere to go there (and hand-drawn ink as letters)

### JoyBrush

- JBX-07 (M) Zoom readout while pinching, with a back-to-100% action
- JBX-19 (L) SVG export UI for editable-line drawings
- JBX-20 (S) Decide which other export formats are wanted (JPEG, WebP, PSD) and Studio hand-off set
- JBX-21 (L) Edit a Studio frame/picture, or a shared picture, in JoyBrush
- JBX-22 (M) Shape every symmetry copy (tapered/dashed symmetry strokes are plain paint today); symmetry for tuft and fill
- JBX-27 (M) Plan card for live tweens (GL render, playback, controls, export, lifetime)
- JBX-28 (XL) Build live tween playback and export: GPU rendering, controls, resource lifetime
- JBX-29 (XL) Select: pixel selections (fill/move/delete/flip/duplicate/copy/invert/HSB/clip), outside-selection fade, first-use notices
- JBX-30 (XL) Lines over realistic paint (K26) and smudge-splits-the-line (SLICE-SMUDGE)
- JBX-32 (XL) Wet sheet on animation frames and layers with editable lines (C6) and follow-ups C2-C5, C7, C8
- JBX-33 (L) Water speed to about 20x: cheap tilt path, 4x4 running water, step only moving water
- JBX-34 (L) Large-brush speed leftovers: 512 px oil, first stroke after opening, water flush, half-resolution simulation
- JBX-39 (S) S Pen side button: switch lay and scrape, or load a new colour pair (owner brainstorm)
- JBX-44 (XL) Paper pack: GPU borrower/seam/save-export checks, owner evaluation of the 45 papers, compression toward 10x, decisions on base-app versus optional packs
- JBX-45 (S) Pencil Tuner comparison on the Note 9 (S8)
- JBX-48 (L) JoyBrush Joy-pack shelf: brushes, papers and palettes from packs
- JBX-50 (XL) Puppet and character boards (JB-7.01 to 7.04)
- JBX-51 (XL) Annotation becomes a JoyBrush layer; placed images and Joybot generation contracts
- JBX-52 (XL) Joybot the brush maker (parked by owner until base brushes are perfected)

### Launch and hygiene

- L04 (L) Re-run the second half of HYG-5: re-check every ðŸ”´/ðŸŸ¡ row and the '~' inherited recording rows (sections 1-4) against code
- L13 (M) Consider removing OpenCV/TFLite/MediaPipe weight if Smart Detection keeps them (decision on slim detection)
- L20 (M) Add an automated lint that fails on new clickable icons without tooltips or hard-coded strings
- L21 (L) Automated test gaps: Java app module has no src/test; the 233 JVM-harness tests run only through shell scripts, no CI
- L23 (M) HYG-3 tracked junk: tasks/ root still holds 164 files, tracked logs, scratch PNG/XML, repo-root wf_ed.png and star-guy-spritesheet.png
- L24 (M) HYG-3 archive pass for old specs and PLAN/SPEC files (about 120 SPEC/PLAN/AUDIT files at tasks root)
- L25 (L) HYG-3 worktree and branch inventory (172 worktrees, 351 local branches now vs 156/256 on 10-27)
- L34 (M) S10: remove 11 unused FadCam classes and 4 unused drawables in one pass
- L35 (XL) Wish list: Story Board, auto-markers, remote-phone-as-camera (F2, F3, F4)
- L36 (XL) Wish list: ffmpeg-kit replacement plan, tablet layouts, theme prune (B6, O3, O2)

## 5. DONE? rows to verify

- BOT-A7 (L) Sweep the ~43 STATE rows marked 'help mapping pending' against the 104 HelpBook topics
- BOT-A16 (L) Resume for VisLab and JoyBrush jobs (checkpoint by conversation)
- BOT-A29 (L) Capture actions: start/pause/stop recording, webcam bubble, floating visualiser
- BOT-A31 (L) SpriteLab deeper actions: pivots/auto-centre, background key and bake, dope sheet, viseme labels, export
- BOT-A33 (L) Recipes with triggers and model routing per step
- JBX-18 (L) Give brush imports (ABR, Procreate, Krita, MyPaint) a user entry point
- L18 (L) Hard-coded English: about 117 literal Toasts and 183 literal setText in Java/Kotlin
- L19 (L) Hover labels: finish the app-wide sweep (FadCam-inherited layouts, 19 ImageButtons without contentDescription)
