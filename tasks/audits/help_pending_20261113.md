# STATE rows whose help mapping is still 'pending' (job L03), 2026-11-13

Method: lines of `tasks/STATE.md` containing both 'help' and 'pending'; HelpBook has 110 topics (`new Topic("id"` count). **Candidate topic** is a keyword match on the row's first words, not a verified mapping: whoever maps it (job BOT-A7 / BOT-2) must read the topic. Rows are as of the pushed STATE, so line numbers drift.

| STATE line | Row (first cell) | Candidate topic ids |
|---|---|---|
| 10 | help mapping pending. [Proof and limits](joybrush/reviews/LIVE_SAVE_HANDOFF_20261104.md). | joybrush, joybrush_open, joybrush_brushes, joybrush_lines |
| 74 | studio.text-animation — animated titles, words and timers | text, studio_marks, studio_title_rename, studio_arrange_tools |
| 75 | studio.caption-animation — animated transcript phrases | captions, studio_marks, studio_title_rename, studio_arrange_tools |
| 76 | shared.transforms — move/resize/rotate/pivot, mesh and corner-pin families | rotate |
| 77 | vislab.studio-bridge — music visuals in Studio and reusable look elements | studio_marks, studio_title_rename, studio_arrange_tools, studio_drawer_back |
| 79 | joybrush.boards — create, size, move and lock Image, Animation and Sprite regions | image, sprites, joybrush, sprite_open |
| 80 | joybrush.board-preview — scope layer previews, held layers and onion skin to a board | joybrush, sprite_onion, joybrush_open, fx_layer |
| 81 | joybrush.tile-pattern — preview a repeating Image pattern and wrap supported strokes into  | image, joybrush, joybrush_open, joybrush_brushes |
| 82 | joybrush.sprite-roll — size Sprite cells, preview their order and separately arm swaps | sprites, joybrush, sprite_open, sprite_slice |
| 83 | joybrush.board-export — export board/range/frame images, animation, GIF or sheet; open sta | keyframes, export, export_safety, studio_arrange_tools |
| 84 | joybrush.paper-surfaces — choose the background and custom color independently from the ph | joybrush, joybrush_open, record_background, joybrush_brushes |
| 86 | joybrush.watercolor-lines — pressure/tilt-driven editable wash recipes | joybrush, joybrush_open, recipes, joybrush_brushes |
| 87 | joybrush.frame-tweens — frame in-betweens and animation | keyframes, joybrush, sprite_animation, joybrush_open |
| 101 | Recording never loses what it recorded when the phone fills (work-loss F5) | (none by keyword) |
| 160 | An audio-only project opens again | fixaudio, extract_audio, library_projects |
| 161 | Export keeps sound, fades and length in step after transitions (model/TimeMap, job [STU-5] | transitions, export, export_safety, sprite_export |
| 163 | Export finished screen says where the file is | export, export_safety, sprite_export, record_screen |
| 167 | Link to a pin of a bent sprite (studio.pin-links) | studio_marks, studio_title_rename, studio_arrange_tools, studio_drawer_back |
| 170 | Studio drawer polish: visualiser drawer in two columns, "Follow what" dialog with a number | studio_marks, studio_title_rename, studio_arrange_tools, studio_drawer_back |
| 346 | Font per Words layer: each Words layer in any app font or his imported ones. Where: the ro | text_font, fx_layer, joybrush_layers, vislab_words |
| 347 | Parts instead of lumped scenes: Stars (crisp, twinkle / pulse to the beat / sparkle on hi- | beats |
| 354 | Studio effect sliders that follow the music: a "♪ music" row under every slider of an effe | effects, studio_marks, studio_title_rename, studio_arrange_tools |
| 386 | Packs read by VisLab and Studio (11-13): a pack's VisLab looks join VisLab's strip (your c | studio_marks, studio_title_rename, studio_arrange_tools, studio_drawer_back |
| 387 | Words in a TextLab look in VisLab (11-13): VisLab › Edit › a Picture layer › Words in a lo | fx_layer, joybrush_layers, library_look, textlab |
| 388 | Joy packs (11-12): one .joypack file brings looks (and later brushes, papers, fonts and mo | joybrush_brushes |
| 402 | Watercolour on the wet sheet (the default since 11-11; the old way one tap away) | sprite_sheets |
| 403 | Water detail: Detailed or Fast | (none by keyword) |
| 404 | Painting on when a drawing's realistic-paint room is full | (none by keyword) |
| 405 | Large washes beyond resident atlas room | (none by keyword) |
| 416 | Oil knives: soft passage, and Palette knife 3 held like a real knife | (none by keyword) |
| 425 | Choose paper background, surface and brush response (joybrush.paper-surfaces) | joybrush, joybrush_open, record_background, joybrush_brushes |
| 485 | Joybot's cut really cuts (code review D8, owner ruling 11-12) | joybot |
| 498 | Joybot makes pictures (OpenRouter picture service, or your own ChatGPT/Gemini app and Shar | share, joybot, share_add |
| 514 | Joybot can name clips and layers (rename_item, rename_clip) | studio_title_rename, joybot, sprite_names, joybrush_clips |
| 529 | Nothing saves over newer work (SAFE-1) | export_safety, voiceover |
| 530 | Characters and imported fonts live in the Joy Creator folder (work-loss F6) | folder |
| 531 | VisLab follows a change of folder at once (work-loss F7) | folder, vislab, vislab_build, vislab_music |
| 535 | Delete and MOVE are safe (SAFE-1) | delete, export_safety |
| 536 | Joy Brush: a drawing is never lost to its list or to a failed open (SAFE-1) | export_safety, joybrush, sprite_open, joybrush_open |
| 573 | Lobby recents list sprite sheets, characters and VisLab looks | sprites, sprite_open, sprite_slice, sprite_names |
| 602 | Library item sheet: Save to gallery, Open in Studio, Details | studio_marks, studio_title_rename, studio_arrange_tools, studio_drawer_back |
| 603 | Library Recordings sub-filter: All / Camera / Screen / Stream / Dual | library, record_screen, library_open_studio, library_exports |
| 604 | Library multi-select with a batch bar ("Choose several") | library, library_open_studio, library_exports, joybrush_select |
| 613 | Space: where the room on the phone goes, and a way to free some; move several unused files | space, hands_free |
| 656 | - 🟡 Sharp turns (2026-10-31, on joy-creator, Note 9 bench; owner has not tried it): oil br | (none by keyword) |
